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

import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.ProtocolException;
import java.nio.ByteBuffer;

import app.intra.util.DnsMetadata;
import app.intra.util.DnsPacket;
import app.intra.util.DnsTransaction;
import app.intra.util.IpPacket;
import app.intra.util.IpTagInterceptor;
import app.intra.util.Ipv4Packet;
import app.intra.util.Ipv6Packet;
import app.intra.util.Names;
import app.intra.util.UdpPacket;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

// Reads DNS requests over UDP from |tunFd|, forwards them and
// writes them back to the tun interface. Takes ownership of the file descriptor.
public class DnsResolverUdpToHttps extends Thread {

  private static final String LOG_TAG = "DnsResolverUdpToHttps";

  // IP constants
  private static final int IP_MIN_HEADER_LENGTH = 20;
  private static final byte IPV4_VERSION = 4;

  // ICMP constants
  private static final byte ICMP_PROTOCOL = 1;
  private static final byte ICMP_IPV6_PROTOCOL = 58;

  // TCP constants
  private static final byte TCP_PROTOCOL = 6;

  // UDP constants
  private static final int UDP_MAX_DATAGRAM_LEN = 32767;
  private static final byte UDP_PROTOCOL = 17;

  private static final int DNS_DEFAULT_PORT = 53;

  // Misc constants
  private static final int SLEEP_MILLIS = 1500;

  private ParcelFileDescriptor tunFd = null;
  public ServerConnection serverConnection = null;

  public DnsResolverUdpToHttps(ParcelFileDescriptor tunFd, ServerConnection serverConnection) {
    super(LOG_TAG);
    this.tunFd = tunFd;
    this.serverConnection = serverConnection;
  }

  @Override
  // In addition to reading DNS requests from the VPN interface and forwarding them via HTTPS, this
  // thread is responsible for maintaining a connected socket to a DNS-over-HTTPS API.
  public void run() {
    Log.d(LOG_TAG, "Query thread starting");
    if (tunFd == null) {
      // This check is necessary due to a race, where the VPN has been closed and the device regains
      // network connectivity before the service stops. As a result the TUN file descriptor is null
      // at the time the resolver is created.
      Log.w(LOG_TAG, "VPN/TUN file descriptor is null");
      return;
    }

    ByteBuffer buffer = ByteBuffer.allocate(UDP_MAX_DATAGRAM_LEN);
    FileInputStream in = new FileInputStream(tunFd.getFileDescriptor());
    FileOutputStream out = new FileOutputStream(tunFd.getFileDescriptor());

    while (!isInterrupted()) {
      try {
        buffer.clear(); // Reset buffer before reading.
        int length;
        try {
          length = in.read(buffer.array());
        } catch (IOException e) {
          if (!isInterrupted()) {
            Log.e(LOG_TAG, "Failed to read from tun interface.");
          }
          return;
        }
        if (length <= 0) {
          // This should only happen on API < 21, where tunFd is nonblocking.
          try {
            Thread.sleep(SLEEP_MILLIS);
          } catch (InterruptedException e) {
            return;
          }
          continue;
        }
        if (length < IP_MIN_HEADER_LENGTH) {
          Log.w(LOG_TAG, "Received malformed IP packet.");
          continue;
        }
        buffer.limit(length);

        byte version = IpPacket.getIpVersion(buffer);
        Log.d(LOG_TAG, String.format("Received IPv%d packet", version));
        IpPacket ipPacket;
        try {
          if (version == IPV4_VERSION) {
            ipPacket = new Ipv4Packet(buffer);
          } else {
            ipPacket = new Ipv6Packet(buffer);
          }
        } catch (IllegalArgumentException e) {
          Log.w(LOG_TAG, "Received malformed IP packet: " + e.getMessage());
          continue;
        }
        byte protocol = ipPacket.getProtocol();
        if (protocol != UDP_PROTOCOL) {
          Log.w(LOG_TAG, getProtocolErrorMessage(protocol));
          continue;
        }

        UdpPacket udpPacket = new UdpPacket(ByteBuffer.wrap(ipPacket.getPayload()));
        if (udpPacket.destPort != DNS_DEFAULT_PORT) {
          Log.w(LOG_TAG, "Received non-DNS UDP packet");
          continue;
        }

        if (udpPacket.length == 0) {
          Log.i(LOG_TAG, "Received interrupt UDP packet.");
          continue;
        }

        DnsMetadata dnsRequest = parseDnsPacket(udpPacket.data);
        if (dnsRequest == null) {
          Log.e(LOG_TAG, "Failed to parse DNS request");
          continue;
        }
        Log.d(
            LOG_TAG,
            "NAME: "
                + dnsRequest.name
                + " ID: "
                + dnsRequest.requestId
                + " TYPE: "
                + dnsRequest.type);

        dnsRequest.sourceAddress = ipPacket.getDestAddress();
        dnsRequest.destAddress = ipPacket.getSourceAddress();
        dnsRequest.sourcePort = udpPacket.destPort;
        dnsRequest.destPort = udpPacket.sourcePort;

        serverConnection.performDnsRequest(dnsRequest, udpPacket.data,
            new DnsResponseCallback(dnsRequest, out));

        Intent intent = new Intent(Names.QUERY.name());
        DnsVpnService dnsVpnService = DnsVpnServiceState.getInstance().getDnsVpnService();
        LocalBroadcastManager.getInstance(dnsVpnService).sendBroadcast(intent);

      } catch (Exception e) {
        if (!isInterrupted()) {
          Log.w(LOG_TAG, "Unexpected exception in UDP loop.");
        }
      }
    }
  }

  // Writes |dsnRequestId| to |dnsRequestId| ID header.
  private void writeRequestIdToDnsResponse(byte[] dnsResponse, short dnsRequestId) {
    ByteBuffer buffer = ByteBuffer.wrap(dnsResponse);
    buffer.putShort(dnsRequestId);
  }

  // Returns the question name, type, and id  present in |dnsPacket|, as a DnsMetadata object.
  // Assumes the DNS packet has been validated.
  private DnsMetadata parseDnsPacket(byte[] data) {
    DnsMetadata dnsMetadata = new DnsMetadata();
    dnsMetadata.timestamp = SystemClock.elapsedRealtime();

    DnsPacket dnsPacket;
    try {
      dnsPacket = new DnsPacket(data);
    } catch (ProtocolException e) {
      Log.i(LOG_TAG, "Received invalid DNS request");
      return null;
    }
    if (!dnsPacket.isNormalQuery() && !dnsPacket.isResponse()) {
      Log.i(LOG_TAG, "Dropping strange DNS query");
      return null;
    }

    dnsMetadata.type = dnsPacket.getQueryType();
    dnsMetadata.name = dnsPacket.getQueryName();
    if (dnsMetadata.name == null || dnsMetadata.type == 0) {
      Log.i(LOG_TAG, "No question in DNS packet");
      return null;
    }
    dnsMetadata.requestId = dnsPacket.getId();

    return dnsMetadata;
  }

  // Returns an error string for unexpected, non-UDP protocols.
  private String getProtocolErrorMessage(byte protocol) {
    String msg;
    switch (protocol) {
      case ICMP_IPV6_PROTOCOL:
      case ICMP_PROTOCOL:
        msg = "Received ICMP packet.";
        break;
      case TCP_PROTOCOL:
        msg = "Received TCP packet.";
        break;
      default:
        msg = String.format("Received non-UDP IP packet: %d", protocol);
        break;
    }
    return msg;
  }

  /**
   * A callback object to listen for a DNS response. The caller should create one such object for
   * each DNS request. Responses will run on a reader thread owned by OkHttp.
   */
  private class DnsResponseCallback implements Callback {

    private final FileOutputStream outputStream;
    private final DnsMetadata dnsMetadata;
    private final DnsTransaction transaction;

    /**
     * Constructs a callback object to listen for a DNS response
     *
     * @param request Represents the request. Used to know the request ID, and the client's ip and
     * port.
     * @param output Represents the VPN TUN device.
     */
    public DnsResponseCallback(DnsMetadata request, FileOutputStream output) {
      dnsMetadata = request;
      outputStream = output;
      transaction = makeTransaction(request);
    }

    private DnsTransaction makeTransaction(DnsMetadata query) {
      DnsTransaction transaction = new DnsTransaction();
      transaction.name = query.name;
      transaction.queryTime = query.timestamp;
      transaction.type = query.type;
      return transaction;
    }

    @Override
    public void onFailure(Call call, IOException e) {
      transaction.status = DnsTransaction.Status.SEND_FAIL;
      Log.w(LOG_TAG, "Failed to read HTTPS response: " + e.toString());
    }

    private void sendResult() {
      DnsVpnService dnsVpnService = DnsVpnServiceState.getInstance().getDnsVpnService();
      if (dnsVpnService != null) {
        dnsVpnService.recordTransaction(transaction);
      } else {
        Log.i(LOG_TAG, "VPN service was gone when result arrived.");
      }
    }

    @Override
    public void onResponse(Call call, Response response) {
      transaction.serverIp = response.header(IpTagInterceptor.HEADER_NAME);
      if (!response.isSuccessful()) {
        transaction.status = DnsTransaction.Status.HTTP_ERROR;
        sendResult();
        return;
      }
      byte[] dnsResponse;
      try {
        dnsResponse = response.body().bytes();
      } catch (IOException e) {
        transaction.status = DnsTransaction.Status.BAD_RESPONSE;
        sendResult();
        return;
      }
      writeRequestIdToDnsResponse(dnsResponse, dnsMetadata.requestId);
      DnsMetadata parsedDnsResponse = parseDnsPacket(dnsResponse);
      if (parsedDnsResponse != null) {
        Log.d(LOG_TAG, "RNAME: " + parsedDnsResponse.name + " NAME: " + dnsMetadata.name);
        if (!dnsMetadata.name.equals(parsedDnsResponse.name)) {
          Log.e(LOG_TAG, "Mismatch in request and response names.");
          transaction.status = DnsTransaction.Status.BAD_RESPONSE;
          sendResult();
          return;
        }
      }

      transaction.status = DnsTransaction.Status.COMPLETE;
      transaction.response = dnsResponse;

      UdpPacket udpResponsePacket =
          new UdpPacket(dnsMetadata.sourcePort, dnsMetadata.destPort, dnsResponse);
      byte[] rawUdpResponse = udpResponsePacket.getRawPacket();

      IpPacket ipPacket;
      if (dnsMetadata.sourceAddress instanceof Inet4Address) {
        ipPacket = new Ipv4Packet(
            UDP_PROTOCOL,
            dnsMetadata.sourceAddress,
            dnsMetadata.destAddress,
            rawUdpResponse);
      } else {
        ipPacket = new Ipv6Packet(
            UDP_PROTOCOL,
            dnsMetadata.sourceAddress,
            dnsMetadata.destAddress,
            rawUdpResponse);
      }

      byte[] rawIpResponse = ipPacket.getRawPacket();
      try {
        outputStream.write(rawIpResponse);
      } catch (IOException e) {
        Log.e(LOG_TAG, "Failed to write to VPN/TUN interface.");
        transaction.status = DnsTransaction.Status.INTERNAL_ERROR;
      }

      sendResult();
    }
  }
}
