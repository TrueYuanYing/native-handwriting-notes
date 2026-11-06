package app.iko.notes;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** USB-only desktop renderer companion. The endpoint cannot be changed to a remote service. */
public final class OneNotePdfBridge {
  private OneNotePdfBridge() {}

  public static boolean supports(String name) {
    String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".one") || lower.endsWith(".onepkg");
  }

  public static NoteDocument importDocument(Context context, Uri uri, String name) throws IOException {
    if (!supports(name)) throw new IOException("请选择 .one 或 .onepkg 文件");
    String token = readPairing(context);
    verifyComputer(token);
    File source = File.createTempFile("onenote-", ".source", context.getCacheDir());
    File pdf = File.createTempFile("onenote-", ".pdf", context.getCacheDir());
    HttpURLConnection connection = null;
    try {
      try (InputStream input = context.getContentResolver().openInputStream(uri);
          OutputStream output = new FileOutputStream(source)) {
        if (input == null) throw new IOException("无法打开 OneNote 文件");
        ArchivePolicy.copyBounded(input, output, ArchivePolicy.MAX_ASSET_BYTES);
      }
      if (source.length() < 36) throw new IOException("OneNote 文件过短或已损坏");
      connection = (HttpURLConnection) new URL("http://127.0.0.1:18327/v1/convert").openConnection(Proxy.NO_PROXY);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(2500);
      connection.setReadTimeout(200000);
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", "application/onenote");
      connection.setRequestProperty("X-IKO-Converter", "1");
      connection.setRequestProperty("Authorization", "Bearer " + token);
      connection.setRequestProperty("X-IKO-File-Type", name.toLowerCase(Locale.ROOT).endsWith(".onepkg") ? "onepkg" : "one");
      connection.setFixedLengthStreamingMode(source.length());
      connection.setDoOutput(true);
      try (InputStream input = new FileInputStream(source);
          OutputStream output = connection.getOutputStream()) {
        ArchivePolicy.copyBounded(input, output, ArchivePolicy.MAX_ASSET_BYTES);
      } catch (java.net.ConnectException | java.net.SocketTimeoutException error) {
        throw new IOException("OneNote 转换需要 USB 连接运行 IKO 转换器的电脑。连接后重新导入；也可直接导入已转换的 PDF。", error);
      }
      int status = connection.getResponseCode();
      if (status != 200) {
        String message = "OneNote 转换失败（" + status + "）";
        try (InputStream input = connection.getErrorStream()) {
          if (input != null) {
            byte[] bytes = new byte[4096];
            int used = 0, count;
            while (used < bytes.length && (count = input.read(bytes, used, bytes.length - used)) != -1)
              used += count;
            message = new String(bytes, 0, used, StandardCharsets.UTF_8);
          }
        }
        throw new IOException(message);
      }
      if (!"application/pdf".equals(connection.getContentType()))
        throw new IOException("电脑转换器没有返回 PDF");
      if (connection.getContentLengthLong() > ArchivePolicy.MAX_ASSET_BYTES)
        throw new IOException("转换后的 PDF 超过 128 MB");
      try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(pdf)) {
        ArchivePolicy.copyBounded(input, output, ArchivePolicy.MAX_ASSET_BYTES);
      }
      NoteDocument document = MediaIo.importPdf(context, Uri.fromFile(pdf));
      String title = name.replaceFirst("(?i)\\.(one|onepkg)$", "");
      document.title = title.substring(0, Math.min(title.length(), 500));
      for (int i = 0; i < document.pages.size(); i++) document.pages.get(i).title = "第 " + (i + 1) + " 页";
      return document;
    } finally {
      if (connection != null) connection.disconnect();
      source.delete();
      pdf.delete();
    }
  }

  private static String readPairing(Context context) throws IOException {
    File file = new File(context.getFilesDir(), "onenote-bridge.token");
    if (!file.isFile() || file.length() > 100) throw disconnected();
    byte[] bytes = new byte[(int) file.length()];
    try (InputStream input = new FileInputStream(file)) {
      int used = 0, count;
      while (used < bytes.length && (count = input.read(bytes, used, bytes.length - used)) != -1)
        used += count;
      String token = new String(bytes, 0, used, StandardCharsets.US_ASCII).trim();
      if (!token.matches("[0-9a-f]{64}")) throw disconnected();
      return token;
    }
  }

  private static IOException disconnected() {
    return new IOException("OneNote 转换需要连接电脑：请在电脑启动 IKO OneNote 转换器并完成 USB 配对，再重新导入。转换后的 PDF 可离线批注。");
  }

  private static void verifyComputer(String token) throws IOException {
    String nonce = UUID.randomUUID().toString().replace("-", "");
    HttpURLConnection check = null;
    try {
      check = (HttpURLConnection) new URL("http://127.0.0.1:18327/v1/challenge").openConnection(Proxy.NO_PROXY);
      check.setInstanceFollowRedirects(false);
      check.setConnectTimeout(2500);
      check.setReadTimeout(5000);
      check.setRequestProperty("X-IKO-Nonce", nonce);
      if (check.getResponseCode() != 200) throw disconnected();
      byte[] received = new byte[65];
      int used = 0, count;
      try (InputStream input = check.getInputStream()) {
        while (used < received.length && (count = input.read(received, used, received.length - used)) != -1)
          used += count;
      }
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
      StringBuilder expected = new StringBuilder();
      for (byte value : mac.doFinal(nonce.getBytes(StandardCharsets.US_ASCII)))
        expected.append(String.format(Locale.ROOT, "%02x", value & 255));
      byte[] response = java.util.Arrays.copyOf(received, used);
      if (!MessageDigest.isEqual(expected.toString().getBytes(StandardCharsets.US_ASCII), response))
        throw new IOException("电脑转换器配对不匹配，请重新启动电脑转换器后再导入。");
    } catch (java.net.ConnectException | java.net.SocketTimeoutException error) {
      throw disconnected();
    } catch (java.security.GeneralSecurityException error) {
      throw new IOException("无法验证电脑转换器", error);
    } finally {
      if (check != null) check.disconnect();
    }
  }
}
