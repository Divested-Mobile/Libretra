package app.intra;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

/**
 * Broadcast receiver that runs on boot, and also when the app is restarted due to an update.
 */
public class AutoStarter extends BroadcastReceiver {

  private static String LOG_TAG = "AutoStarter";

  @Override
  public void onReceive(Context context, Intent intent) {
    Log.d(LOG_TAG, "Boot event");
    if (DnsVpnServiceState.getInstance().isDnsVpnServiceStarting()) {
      // The service is already started or starting, so there's no work to do.
      Log.d(LOG_TAG, "Already running");
      return;
    }
    if (Preferences.getVpnEnabled(context)) {
      Log.d(LOG_TAG, "Autostart enabled");
      if (VpnService.prepare(context) != null) {
        // prepare() returns a non-null intent if VPN permission has not been granted.
        Log.w(LOG_TAG, "VPN permission not granted");
        return;
      }
      Intent startServiceIntent = new Intent(context, DnsVpnService.class);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(startServiceIntent);
      } else {
        context.startService(startServiceIntent);
      }
      DnsVpnServiceState.getInstance().setDnsVpnServiceStarting();
    }
  }
}
