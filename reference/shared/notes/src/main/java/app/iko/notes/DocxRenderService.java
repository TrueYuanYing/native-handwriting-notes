package app.iko.notes;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.libreoffice.kit.LibreOfficeKit;
import org.libreoffice.kit.Office;

/** The verified document engine lives in a separate process, loaded only for conversion. */
public final class DocxRenderService extends Service {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private Office office;
  // Keep the shared cross-process lease until this isolated process actually exits.
  private ResourcePacks.OfficeLease engineLease;
  private final Messenger receiver =
      new Messenger(
          new Handler(
              Looper.getMainLooper(),
              message -> {
                final Messenger reply = message.replyTo;
                final Bundle data = message.getData();
                worker.execute(
                    () -> {
                      Message result = Message.obtain(null, 1);
                      Bundle response = new Bundle();
                      try {
                        render(
                            new File(data.getString("source")), new File(data.getString("output")));
                      } catch (Exception | LinkageError e) {
                        android.util.Log.e("IKODocx", "Local conversion failed", e);
                        response.putString("error", "Word 原版排版失败：" + e.getMessage() + "。原件已保留");
                      }
                      result.setData(response);
                      try {
                        reply.send(result);
                      } catch (RemoteException ignored) {
                        /* Caller closed. */
                      }
                    });
                return true;
              }));

  @Override
  public IBinder onBind(Intent intent) {
    return receiver.getBinder();
  }

  @Override
  public void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
    // This manifest-isolated process owns the native runtime; no conversions remain bound.
    android.os.Process.killProcess(android.os.Process.myPid());
  }

  private void initialize() throws IOException {
    if (office != null) return;
    if(engineLease==null)engineLease=ResourcePacks.acquireOffice(this);
    File home=engineLease.home;
    LibreOfficeKit.configureLibraryDirectory(engineLease.libraries);
    LibreOfficeKit.initializeLibrary();
    OfficeFonts.configure(getAssets(), home);
    LibreOfficeKit.putenv("SAL_LOG=+WARN");
    LibreOfficeKit.putenv("SAL_LOK_OPTIONS=compact_fonts");
    LibreOfficeKit.init(this, home.getAbsolutePath());
    java.nio.ByteBuffer handle = LibreOfficeKit.getLibreOfficeKitHandle();
    if (handle == null) throw new IOException("本地排版引擎初始化失败");
    office = new Office(handle);
  }

  private void render(File source, File output) throws IOException {
    initialize();
    org.libreoffice.kit.Document document = office.documentLoad(source.getAbsolutePath());
    if (document == null) throw new IOException(office.getError());
    File gridSource = new File(output.getParentFile(), "grid-source.odt"),
        gridReady = new File(output.getParentFile(), "grid-ready.odt");
    try {
      document.initializeForRendering();
      if (DocxGridLayout.hasGrid(source)) {
        document.saveAs(android.net.Uri.fromFile(gridSource).toString(), "odt", "{}");
        DocxGridLayout.correctMetrics(gridSource, gridReady);
        org.libreoffice.kit.Document adjusted = office.documentLoad(gridReady.getAbsolutePath());
        if (adjusted == null) throw new IOException(office.getError());
        document.destroy();
        document = adjusted;
        document.initializeForRendering();
      }
      document.saveAs(
          android.net.Uri.fromFile(output).toString(),
          "pdf",
          "{\"UseLosslessCompression\":{\"type\":\"boolean\",\"value\":\"true\"},"
              + "\"ReduceImageResolution\":{\"type\":\"boolean\",\"value\":\"false\"}}");
      if (!output.isFile() || output.length() < 100) throw new IOException(office.getError());
    } finally {
      if (document != null) document.destroy();
      gridSource.delete();
      gridReady.delete();
    }
  }

}
