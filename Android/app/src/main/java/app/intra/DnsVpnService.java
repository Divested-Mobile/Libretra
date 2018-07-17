/*
Copyright 2018 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package app.intra;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import app.intra.util.DnsQueryTracker;
import app.intra.util.DnsTransaction;
import app.intra.util.Names;

public class DnsVpnService extends VpnService implements NetworkManager.NetworkListener {

  private static final String LOG_TAG = "DnsVpnService";
  private static final int SERVICE_ID = 1; // Only has to be unique within this app.
  private static final int VPN_INTERFACE_MTU = 32767;
  // Randomly generated unique local IPv6 unicast subnet prefix, as defined by RFC 4193.
  private static final String IPV6_SUBNET = "fd66:f83a:c650::%s";
  private static final String CHANNEL_ID = "vpn";

  private NetworkManager networkManager;
  private PrivateAddress privateIpv4Address = null;
  private PrivateAddress privateIpv6Address = null;
  private ParcelFileDescriptor tunFd = null;
  private DnsResolverUdpToHttps dnsResolver = null;
  private ServerConnection serverConnection = null;
  private String url = null;

  private BroadcastReceiver messageReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          String action = intent.getAction();
          if (Names.RESOLVED.name().equals(action)) {
            onResolved(intent);
          }
        }

        private void onResolved(Intent intent) {
        }

      };

  @Override
  public synchronized int onStartCommand(Intent intent, int flags, int startId) {
    url = Preferences.getServerUrl(this);
    Log.i(LOG_TAG, String.format("Starting DNS VPN service, url=%s", url));

    if (networkManager != null) {
      if (serverConnection != null) {
        new Thread(
                new Runnable() {
                  public void run() {
                    updateServerConnection();
                  }
                }, "updateServerConnection-onStartCommand")
                .start();
      }
      return START_REDELIVER_INTENT;
    }

    // If we're online, |networkManager| immediately calls this.onNetworkConnected(), which in turn
    // calls startVpn() to actually start.  If we're offline, the startup actions will be delayed
    // until we come online.
    networkManager = new NetworkManager(DnsVpnService.this, DnsVpnService.this);

    // Mark this as a foreground service.  This is normally done to ensure that the service
    // survives under memory pressure.  Since this is a VPN service, it is presumably protected
    // anyway, but the foreground service mechanism allows us to set a persistent notification,
    // which helps users understand what's going on, and return to the app if they want.
    PendingIntent mainActivityIntent =
        PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

    Notification.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      CharSequence name = getString(R.string.channel_name);
      String description = getString(R.string.channel_description);
      // LOW is the lowest importance that is allowed with startForeground in Android O.
      int importance = NotificationManager.IMPORTANCE_LOW;
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
      channel.setDescription(description);

      NotificationManager notificationManager = getSystemService(NotificationManager.class);
      notificationManager.createNotificationChannel(channel);
      builder = new Notification.Builder(this, CHANNEL_ID);
    } else {
      builder = new Notification.Builder(this);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
        // Min-priority notifications don't show an icon in the notification bar, reducing clutter.
        // Only available in API >= 16.  Deprecated in API 26.
        builder = builder.setPriority(Notification.PRIORITY_MIN);
      }
    }

    builder.setSmallIcon(R.drawable.ic_status_bar)
            .setContentTitle(getResources().getText(R.string.notification_title))
            .setContentText(getResources().getText(R.string.notification_content))
            .setContentIntent(mainActivityIntent);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // Secret notifications are not shown on the lock screen.  No need for this app to show there.
      // Only available in API >= 21
      builder = builder.setVisibility(Notification.VISIBILITY_SECRET);
    }

    startForeground(SERVICE_ID, builder.getNotification());

    return START_REDELIVER_INTENT;
  }

  private synchronized void updateServerConnection() {
    if (serverConnection != null) {
      String currentUrl = serverConnection.getUrl();
      if (currentUrl == null) {
        if (url == null) {
          return;
        }
      } else if (currentUrl.equals(url)) {
        return;
      }
    }

    // Bootstrap the new server connection, which may require resolving the new server's name, using
    // the current DNS configuration.
    Bundle bootstrap = new Bundle();
    long beforeBootstrap = SystemClock.elapsedRealtime();
    if (url == null || url.isEmpty()) {
      serverConnection = StandardServerConnection.get(url);
    }

    if (serverConnection == null) {
      stopSelf();
      return;
    }

    // Measure bootstrap delay.
    long afterBootStrap = SystemClock.elapsedRealtime();
    bootstrap.putInt(Names.LATENCY.name(), (int) (afterBootStrap - beforeBootstrap));

    if (dnsResolver != null) {
      dnsResolver.serverConnection = serverConnection;
    }
  }

  /**
   * Starts the VPN. This method performs network activity, so it must not run on the main thread.
   * This method is idempotent, and is marked synchronized so that it can safely be called from a
   * freshly spawned thread.
   *
   * TODO: Consider cancellation races more carefully.
   */
  private synchronized void startVpn() {
    if (tunFd != null) {
      return;
    }

    updateServerConnection();

    tunFd = establishVpn();
    if (tunFd == null) {
      Log.w(LOG_TAG, "Failed to get TUN device");
      stopSelf();
      return;
    }

    startDnsResolver();
    broadcastIntent(true);
  }

  @Override
  public void onCreate() {
    Log.i(LOG_TAG, "Creating DNS VPN service");
    DnsVpnServiceState.getInstance().setDnsVpnService(DnsVpnService.this);

    syncNumRequests();

    LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(DnsVpnService.this);
    IntentFilter intentFilter = new IntentFilter(Names.QUERY.name());
    intentFilter.addAction(Names.RESOLVED.name());
    intentFilter.addAction(Names.DROPPED.name());

    broadcastManager.registerReceiver(messageReceiver, intentFilter);
  }

  public void signalStopService(boolean userInitiated) {
    // TODO(alalama): display alert if not user initiated
    Log.i(LOG_TAG,
        String.format("Received stop signal. User initiated: %b", userInitiated));

    // stopDnsResolver() performs network activity, so it can't run on the main thread due to
    // Android rules.  closeVpnInterface() can't be run until after the network action, because
    // we need the packet to go through the VPN interface.  Therefore, both actions have to run in
    // a new thread, in this order.
    new Thread(
            new Runnable() {
              public void run() {
                stopDnsResolver();
                closeVpnInterface();
              }
            }, "Stop VPN on signal")
        .start();
    stopSelf();
  }

  private void closeVpnInterface() {
    if (tunFd != null) {
      try {
        tunFd.close();
      } catch (IOException e) {
        Log.e(LOG_TAG, "Failed to close the VPN interface.");
      } finally {
        tunFd = null;
      }
    }
  }

  private void pingLocalDns() {
    try {
      // Send an empty UDP packet to the DNS server, which will cause the read() call in
      // DnsResolverUdpToHttps to unblock and return.  This wakes up that thread, so it can detect
      // that it has been interrupted and perform a clean shutdown.
      byte zero[] = {0};
      DatagramPacket packet =
          new DatagramPacket(
              zero, zero.length, 0, InetAddress.getByName(privateIpv4Address.router), 53);
      DatagramSocket datagramSocket = new DatagramSocket();
      datagramSocket.send(packet);
    } catch (IOException e) {
      // An IOException likely means that the VPN has already been torn down, so there's no need for
      // this ping.
      Log.e(LOG_TAG, "Failed to send UDP ping: " + e.getMessage());
    }
  }

  private synchronized void startDnsResolver() {
    if (dnsResolver == null && serverConnection != null) {
      Log.i(LOG_TAG, "Starting DNS resolver");
      dnsResolver = new DnsResolverUdpToHttps(tunFd, serverConnection);
      dnsResolver.start();
    }
  }

  private synchronized void stopDnsResolver() {
    if (dnsResolver != null) {
      dnsResolver.interrupt();
      pingLocalDns(); // Try to wake up the resolver thread if it's blocked on a read() call.
      dnsResolver = null;
    }
  }

  @Override
  public synchronized void onDestroy() {
    Log.i(LOG_TAG, "Destroying DNS VPN service");
    broadcastIntent(false);

    if (networkManager != null) {
      networkManager.destroy();
    }

    syncNumRequests();
    serverConnection = null;

    DnsVpnServiceState.getInstance().setDnsVpnService(null);

    LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(this);
    broadcastManager.unregisterReceiver(messageReceiver);

    stopForeground(true);
    if (dnsResolver != null) {
      signalStopService(false);
    }
  }

  @Override
  public void onRevoke() {
    Log.e(LOG_TAG, "VPN service revoked.");
    // Revocation isn't intrinsically an error, but it can occur as a result of error conditions,
    // and user-initiated revocation is expected to be extremely rare.
    stopDnsResolver();
    stopSelf();

    // Disable autostart if VPN permission is revoked.
    Preferences.setVpnEnabled(this, false);

    // Show revocation warning
    Notification.Builder builder;
    NotificationManager notificationManager =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      CharSequence name = getString(R.string.warning_channel_name);
      String description = getString(R.string.warning_channel_description);
      int importance = NotificationManager.IMPORTANCE_HIGH;
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
      channel.setDescription(description);

      notificationManager.createNotificationChannel(channel);
      builder = new Notification.Builder(this, CHANNEL_ID);
    } else {
      builder = new Notification.Builder(this);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
        // Only available in API >= 16.  Deprecated in API 26.
        builder = builder.setPriority(Notification.PRIORITY_MAX);
      }
    }

    PendingIntent mainActivityIntent = PendingIntent.getActivity(
        this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

    builder.setSmallIcon(R.drawable.ic_status_bar)
        .setContentTitle(getResources().getText(R.string.warning_title))
        .setContentText(getResources().getText(R.string.notification_content))
        .setFullScreenIntent(mainActivityIntent, true)  // Open the main UI if possible.
        .setAutoCancel(true);

    notificationManager.notify(0, builder.getNotification());
  }

  @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
  private ParcelFileDescriptor establishVpn() {
    privateIpv6Address = new PrivateAddress(IPV6_SUBNET, 120);
    privateIpv4Address = selectPrivateAddress();
    if (privateIpv4Address == null) {
      Log.e(LOG_TAG, "Unable to find a private address on which to establish a VPN.");
      return null;
    }
    Log.i(LOG_TAG, String.format("VPN address: { IPv4: %s, IPv6: %s }",
                                 privateIpv4Address, privateIpv6Address));

    final String establishVpnErrorMsg = "Failed to establish VPN ";
    ParcelFileDescriptor tunFd = null;
    try {
      VpnService.Builder builder =
          (new VpnService.Builder())
              .setSession("Jigsaw DNS Protection")
              .setMtu(VPN_INTERFACE_MTU)
              .addAddress(privateIpv4Address.address, privateIpv4Address.prefix)
              .addRoute(privateIpv4Address.subnet, privateIpv4Address.prefix)
              .addDnsServer(privateIpv4Address.router)
              .addAddress(privateIpv6Address.address, privateIpv6Address.prefix)
              .addRoute(privateIpv6Address.subnet, privateIpv6Address.prefix)
              .addDnsServer(privateIpv6Address.router);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        builder = builder.setBlocking(true); // Only available in API >= 21

        try {
          // Workaround for Play Store App Download bug:
          // https://code.google.com/p/android/issues/detail?id=210305
          builder = builder.addDisallowedApplication("com.android.vending");
        } catch (Exception e) {
          Log.e(LOG_TAG, "Failed to exclude Play Store", e);
        }
      }
      tunFd = builder.establish();
    } catch (IllegalArgumentException e) {
      Log.e(LOG_TAG, establishVpnErrorMsg, e);
    } catch (SecurityException e) {
      Log.e(LOG_TAG, establishVpnErrorMsg, e);
    } catch (IllegalStateException e) {
      Log.e(LOG_TAG, establishVpnErrorMsg, e);
    }

    return tunFd;
  }

  private static class PrivateAddress {
    final String address;
    final String subnet;
    final String router;
    final int prefix;

    PrivateAddress(String addressFormat, int subnetPrefix) {
      subnet = String.format(addressFormat, "0");
      address = String.format(addressFormat, "1");
      router = String.format(addressFormat, "2");
      prefix = subnetPrefix;
    }

    @Override
    public String toString() {
      return String.format("{ subnet: %s, address: %s, router: %s, prefix: %d }",
                           subnet, address, router, prefix);
    }
  }

  private static PrivateAddress selectPrivateAddress() {
    // Select one of 10.0.0.1, 172.16.0.1, or 192.168.0.1 depending on
    // which private address range isn't in use.
    HashMap<String, PrivateAddress> candidates = new HashMap<>();
    candidates.put("10", new PrivateAddress("10.0.0.%s", 8));
    candidates.put("172", new PrivateAddress("172.16.0.%s", 12));
    candidates.put("192", new PrivateAddress("192.168.0.%s", 16));
    candidates.put("169", new PrivateAddress("169.254.1.%s", 24));
    List<NetworkInterface> netInterfaces;
    try {
      netInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
    } catch (SocketException e) {
      e.printStackTrace();
      return null;
    }

    for (NetworkInterface netInterface : netInterfaces) {
      for (InetAddress inetAddress : Collections.list(netInterface.getInetAddresses())) {
        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
          String ipAddress = inetAddress.getHostAddress();
          if (ipAddress.startsWith("10.")) {
            candidates.remove("10");
          } else if (ipAddress.length() >= 6
              && ipAddress.substring(0, 6).compareTo("172.16") >= 0
              && ipAddress.substring(0, 6).compareTo("172.31") <= 0) {
            candidates.remove("172");
          } else if (ipAddress.startsWith("192.168")) {
            candidates.remove("192");
          }
        }
      }
    }

    if (candidates.size() > 0) {
      return candidates.values().iterator().next();
    }

    return null;
  }

  // Broadcasts the status of the service.
  private void broadcastIntent(boolean dnsStatusOn) {
    Intent broadcast = new Intent(Names.DNS_STATUS.name());
    broadcast.putExtra(Names.DNS_STATUS.name(), dnsStatusOn);
    LocalBroadcastManager.getInstance(DnsVpnService.this).sendBroadcast(broadcast);
  }

  public void recordTransaction(DnsTransaction transaction) {
    transaction.responseTime = SystemClock.elapsedRealtime();
    transaction.responseCalendar = Calendar.getInstance();

    getTracker().recordTransaction(transaction);

    Intent intent = new Intent(Names.RESULT.name());
    intent.putExtra(Names.TRANSACTION.name(), transaction);
    LocalBroadcastManager.getInstance(DnsVpnService.this).sendBroadcast(intent);
  }

  private DnsQueryTracker getTracker() {
    return DnsVpnServiceState.getInstance().getTracker(this);
  }

  private void syncNumRequests() {
    getTracker().sync();
  }

  // NetworkListener interface implementation
  @Override
  public void onNetworkConnected(NetworkInfo networkInfo) {
    Log.i(LOG_TAG, "Connected event.");
    if (tunFd != null) {
      startDnsResolver();
    } else {
      // startVpn performs network activity so it has to run on a separate thread.
      new Thread(
              new Runnable() {
                public void run() {
                  startVpn();
                }
              }, "startVpn-onNetworkConnected")
          .start();
    }
  }

  @Override
  public void onNetworkDisconnected() {
    Log.i(LOG_TAG, "Disconnected event.");
    // stopDnsResolver performs network activity so it has to run on a separate thread.
    new Thread(
            new Runnable() {
              public void run() {
                stopDnsResolver();
              }
            }, "stopDnsResolver-onNetworkDisconnected")
        .start();
  }
}
