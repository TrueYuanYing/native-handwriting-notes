package app.iko.notes;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Offline .one -> WASM object tree -> Android vector PDF. Call on a background thread. */
public final class OneNoteLocalImporter {
  private static final long MAX_BYTES = 64L * 1024 * 1024;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  private OneNoteLocalImporter() {}

  public static NoteDocument importDocument(Context context, Uri uri, String name)
      throws IOException {
    return importDocument(context, uri, name, true);
  }

  public static NoteDocument importDocument(
      Context context, Uri uri, String name, boolean continuous) throws IOException {
    if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("OneNote 导入需要后台线程");
    synchronized (OneNoteLocalImporter.class) {
      return importInternal(context, uri, name, continuous);
    }
  }

  private static NoteDocument importInternal(
      Context context, Uri uri, String name, boolean continuous) throws IOException {
    Context app = context.getApplicationContext();
    File root = new File(app.getFilesDir(), "onenote-local");
    if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建 OneNote 本地目录");
    File job = new File(root, UUID.randomUUID().toString());
    if (!job.mkdir()) throw new IOException("无法创建转换目录");
    File
        source =
            new File(
                job,
                name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".onepkg")
                    ? "source.onepkg"
                    : "source.one"),
        scene = new File(job, "scene.json"),
        pdf = new File(job, "converted.pdf");
    try (InputStream in = app.getContentResolver().openInputStream(uri);
        OutputStream out = new FileOutputStream(source)) {
      if (in == null) throw new IOException("无法读取 OneNote 文件");
      byte[] buffer = new byte[32768];
      long total = 0;
      int n;
      while ((n = in.read(buffer)) != -1) {
        total += n;
        if (total > MAX_BYTES) throw new IOException("OneNote 文件超过 64 MiB 本地导入限制");
        out.write(buffer, 0, n);
      }
    } catch (IOException e) {
      source.delete();
      job.delete();
      throw e;
    }
    JSONObject report = new JSONObject();
    try {
      report.put("sourceName", name);
      report.put("sourceBytes", source.length());
      report.put("sourceSha256", sha256(source));
      report.put("engine", "iko-onenote-local-0.8.6");
      report.put("mode", continuous ? "infinite" : "paged");
      report.put("status", "decoding");
      writeReport(job, report);
      decode(app, source, scene);
      JSONObject object = new JSONObject(readBounded(scene));
      report.put("counts", object.optJSONObject("counts"));
      report.put("warnings", object.optJSONArray("warnings"));
      report.put("diagnostics", object.optJSONArray("diagnostics"));
      report.put("status", "rendering");
      writeReport(job, report);
      JSONObject layout = OneNoteSceneRenderer.render(object, pdf, continuous);
      NoteDocument document =
          continuous
              ? MediaIo.importPdfCanvas(app, Uri.fromFile(pdf))
              : MediaIo.importPdf(app, Uri.fromFile(pdf));
      report.put("pdfPages", document.pages.size());
      report.put("geometryCountsVerified", true);
      report.put("layout", layout);
      report.put("status", "complete");
      writeReport(job, report);
      android.util.Log.i(
          "OneNoteLocal",
          "imported pages=" + document.pages.size() + " counts=" + object.getJSONObject("counts"));
      if (name != null && !name.trim().isEmpty())
        document.title = name.replaceFirst("(?i)\\.(one|onepkg)$", "");
      return document;
    } catch (IOException e) {
      failureReport(job, report, e);
      throw new IOException(e.getMessage() + "；源文件保存在 " + source.getAbsolutePath(), e);
    } catch (Exception e) {
      failureReport(job, report, e);
      throw new IOException("OneNote 本地转换失败，源文件已保留", e);
    } finally {
      scene.delete();
      pdf.delete();
    }
  }

  private static void writeReport(File job, JSONObject report) throws IOException {
    try (Writer out =
        new OutputStreamWriter(
            new FileOutputStream(new File(job, "report.json")), StandardCharsets.UTF_8)) {
      out.write(report.toString());
    }
  }

  private static void failureReport(File job, JSONObject report, Exception failure) {
    try {
      report.put("status", "failed");
      report.put("geometryCountsVerified", false);
      report.put("error", failure.getMessage());
      writeReport(job, report);
    } catch (Exception reportingFailure) {
      failure.addSuppressed(reportingFailure);
    }
  }

  private static String sha256(File source) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    try (InputStream input = new FileInputStream(source)) {
      byte[] buffer = new byte[32768];
      int n;
      while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    StringBuilder result = new StringBuilder(64);
    for (byte value : digest.digest())
      result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }

  private static String readBounded(File scene) throws IOException {
    if (scene.length() > MAX_BYTES) throw new IOException("OneNote 解析结果过大");
    try (InputStream in = new FileInputStream(scene);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[32768];
      int n;
      long total = 0;
      while ((n = in.read(b)) != -1) {
        total += n;
        if (total > MAX_BYTES) throw new IOException("OneNote 解析结果过大");
        out.write(b, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  private static void decode(Context app, File source, File output) throws IOException {
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<String> error = new AtomicReference<>();
    AtomicReference<WebView> view = new AtomicReference<>();
    String base = "https://iko-onenote.invalid/" + UUID.randomUUID() + "/";
    try (Writer writer =
        new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8)) {
      Bridge bridge = new Bridge(writer, finished, error);
      MAIN.post(
          () -> {
            if (finished.getCount() == 0) return;
            try {
              WebView web = new WebView(app);
              view.set(web);
              WebSettings settings = web.getSettings();
              settings.setJavaScriptEnabled(true);
              settings.setAllowFileAccess(false);
              settings.setAllowContentAccess(false);
              settings.setBlockNetworkLoads(true);
              settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
              web.addJavascriptInterface(bridge, "IkoOneNote");
              web.setWebViewClient(
                  new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                      return !request.getUrl().toString().equals(base + "engine.html");
                    }

                    @Override
                    public WebResourceResponse shouldInterceptRequest(
                        WebView v, WebResourceRequest request) {
                      String url = request.getUrl().toString();
                      try {
                        if (!request.getMethod().equals("GET")) return denied();
                        if (url.equals(base + "engine.html"))
                          return new WebResourceResponse(
                              "text/html", "UTF-8", app.getAssets().open("onenote/engine.html"));
                        if (url.equals(base + "engine.wasm"))
                          return new WebResourceResponse(
                              "application/wasm",
                              null,
                              app.getAssets().open("onenote/engine.wasm"));
                        if (url.equals(base + "source.one"))
                          return new WebResourceResponse(
                              "application/octet-stream", null, new FileInputStream(source));
                        return denied();
                      } catch (IOException e) {
                        bridge.fail("无法读取本地引擎：" + e.getMessage());
                        return denied();
                      }
                    }

                    @Override
                    public void onReceivedError(
                        WebView v, WebResourceRequest r, WebResourceError e) {
                      bridge.fail("本地解析页面加载失败：" + e.getDescription());
                    }

                    @Override
                    public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                      bridge.fail("本地解析进程停止，可能内存不足");
                      return true;
                    }
                  });
              web.loadUrl(base + "engine.html");
            } catch (Exception e) {
              bridge.fail("无法启动本地 OneNote 引擎：" + e.getMessage());
            }
          });
      try {
        if (!finished.await(120, TimeUnit.SECONDS)) {
          error.compareAndSet(null, "本地解析超时");
          finished.countDown();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        error.compareAndSet(null, "OneNote 导入已取消");
        finished.countDown();
      } finally {
        bridge.cancel();
        CountDownLatch disposed = new CountDownLatch(1);
        MAIN.post(
            () -> {
              try {
                WebView web = view.getAndSet(null);
                if (web != null) {
                  web.stopLoading();
                  web.removeJavascriptInterface("IkoOneNote");
                  web.destroy();
                }
              } finally {
                disposed.countDown();
              }
            });
        try {
          if (!disposed.await(10, TimeUnit.SECONDS)) error.compareAndSet(null, "释放本地解析器超时");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          error.compareAndSet(null, "OneNote 导入已取消");
        }
      }
      if (error.get() != null) throw new IOException("OneNote 本地解析失败：" + error.get());
    }
  }

  /** Public bridge class is required for reliable WebView reflection on all Android releases. */
  public static final class Bridge {
    private final Writer writer;
    private final CountDownLatch finished;
    private final AtomicReference<String> error;
    private long length;
    private boolean closed;

    Bridge(Writer writer, CountDownLatch finished, AtomicReference<String> error) {
      this.writer = writer;
      this.finished = finished;
      this.error = error;
    }

    @JavascriptInterface
    public synchronized void append(String text) {
      if (closed) return;
      try {
        length += text.getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_BYTES) throw new IOException("解析结果超出限制");
        writer.write(text);
      } catch (IOException e) {
        fail(e.getMessage());
      }
    }

    @JavascriptInterface
    public synchronized void complete() {
      if (closed) return;
      try {
        writer.flush();
      } catch (IOException e) {
        error.compareAndSet(null, e.getMessage());
      }
      closed = true;
      finished.countDown();
    }

    @JavascriptInterface
    public synchronized void fail(String message) {
      if (closed) return;
      String detail = message == null ? "未知解析错误" : message;
      error.compareAndSet(null, detail.substring(0, Math.min(detail.length(), 2000)));
      closed = true;
      finished.countDown();
    }

    synchronized void cancel() {
      closed = true;
    }
  }

  private static WebResourceResponse denied() {
    return new WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Forbidden",
        java.util.Collections.emptyMap(),
        new ByteArrayInputStream(new byte[0]));
  }
}
