package app.iko.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Captures only the app's paper viewport; never writes shared storage or the gallery. */
final class NoteCapture {
  static Bitmap capture(View paper) {
    float scale = Math.min(1, 2048f / Math.max(paper.getWidth(), paper.getHeight()));
    Bitmap image =
        Bitmap.createBitmap(
            Math.max(1, Math.round(paper.getWidth() * scale)),
            Math.max(1, Math.round(paper.getHeight() * scale)),
            Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(image);
    canvas.scale(scale, scale);
    paper.draw(canvas);
    return image;
  }

  static File save(Context context, Bitmap image) throws IOException {
    File directory = new File(context.getFilesDir(), "media");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法保存笔记截图");
    File file = new File(directory, "capture-" + UUID.randomUUID() + ".webp");
    boolean complete = false;
    try (FileOutputStream output = new FileOutputStream(file)) {
      Bitmap.CompressFormat format =
          Build.VERSION.SDK_INT >= 30
              ? Bitmap.CompressFormat.WEBP_LOSSLESS
              : Bitmap.CompressFormat.WEBP;
      if (!image.compress(format, 100, output)) throw new IOException("笔记截图编码失败");
      output.getFD().sync();
      complete = true;
      return file;
    } finally {
      if (!complete) file.delete();
    }
  }

  private NoteCapture() {}
}
