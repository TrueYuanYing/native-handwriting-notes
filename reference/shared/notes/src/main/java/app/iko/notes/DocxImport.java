package app.iko.notes;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.*;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** DOCX document import boundary, shared by file selection and incoming shares. */
final class DocxImport {
  static NoteDocument importDocument(Context context, Uri uri, String name) throws IOException {
    File source = NotebookArchive.retain(context, uri, name);
    try (NotebookArchive archive =
        new NotebookArchive(source, new File(context.getFilesDir(), "media"))) {
      if (!archive.has("word/document.xml")) throw new IOException("DOCX 缺少正文，原件已保留");
    }
    File pdf = new File(source.getParentFile(), "layout.pdf");
    NoteDocument note;
    try {
      convert(context.getApplicationContext(), source, pdf);
      note = MediaIo.importPdf(context, Uri.fromFile(pdf));
    } finally {
      pdf.delete();
    }
    note.title = name.replaceFirst("(?i)\\.docx$", "");
    note.tags.add("Word 原版");
    try {
      note.validate();
    } catch (org.json.JSONException e) {
      throw new IOException("Word 页面信息无效", e);
    }
    return note;
  }

  /** Runs off the UI thread; only one native conversion is bound at a time. */
  private static synchronized void convert(Context app, File source, File pdf) throws IOException {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IllegalStateException("Document conversion must run outside the UI thread");
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<String> error = new AtomicReference<>();
    Messenger reply =
        new Messenger(
            new Handler(
                Looper.getMainLooper(),
                message -> {
                  error.set(message.getData().getString("error"));
                  finished.countDown();
                  return true;
                }));
    ServiceConnection connection =
        new ServiceConnection() {
          @Override
          public void onServiceConnected(ComponentName name, IBinder service) {
            Message request = Message.obtain(null, 1);
            Bundle data = new Bundle();
            data.putString("source", source.getAbsolutePath());
            data.putString("output", pdf.getAbsolutePath());
            request.setData(data);
            request.replyTo = reply;
            try {
              new Messenger(service).send(request);
            } catch (RemoteException e) {
              error.set("本地排版进程连接中断");
              finished.countDown();
            }
          }

          @Override
          public void onServiceDisconnected(ComponentName name) {
            error.set("本地排版进程退出，原件已保留");
            finished.countDown();
          }

          @Override
          public void onNullBinding(ComponentName name) {
            error.set("无法启动本地排版引擎");
            finished.countDown();
          }
        };
    if (!app.bindService(
        new Intent(app, DocxRenderService.class), connection, Context.BIND_AUTO_CREATE))
      throw new IOException("无法启动本地排版引擎，原件已保留");
    try {
      if (!finished.await(120, TimeUnit.SECONDS)) throw new IOException("文档排版超时，原件已保留");
      if (error.get() != null) throw new IOException(error.get());
      if (!pdf.isFile() || pdf.length() < 100) throw new IOException("文档未生成有效页面，原件已保留");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("文档导入已中断", e);
    } finally {
      app.unbindService(connection);
    }
  }
}
