package app.iko.notes;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Dns;
import okhttp3.OkHttpClient;

/** Dedicated public-file transport. TLS and hostname verification use OkHttp defaults. */
public final class ResourceHttp {
  private ResourceHttp() {}
  public static final OkHttpClient CLIENT = new OkHttpClient.Builder()
      .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
      .followRedirects(false).followSslRedirects(false).fastFallback(true)
      .dns(host -> {
        // Some campus networks advertise IPv6 without a working external route.
        // Keep all system-resolved addresses, prefer IPv4, then let OkHttp retry.
        List<InetAddress> addresses = new ArrayList<>(Dns.SYSTEM.lookup(host));
        addresses.sort((a,b) -> Boolean.compare(!(a instanceof Inet4Address), !(b instanceof Inet4Address)));
        return addresses;
      }).build();
}
