package app.iko.notes;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/** Original vector artwork: a reversible study mask, always above page content. */
final class TapeArt {
  private static final class Drawing {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF box = new RectF();
    final Rect visible = new Rect();
    final Matrix matrix = new Matrix();
    final float[] values = new float[9];
    final DashPathEffect border = new DashPathEffect(new float[] {8, 5}, 0);
  }

  private static final ThreadLocal<Drawing> DRAWING = ThreadLocal.withInitial(Drawing::new);

  static void draw(Canvas c, NoteDocument.Element tape) {
    Drawing drawing = DRAWING.get();
    Paint p = drawing.paint;
    p.reset();
    p.setAntiAlias(true);
    RectF box = drawing.box;
    box.set(tape.x, tape.y, tape.x + tape.width, tape.y + tape.height);
    p.setColor(tape.color | 0xff000000);
    if (tape.revealed) {
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.8f);
      p.setPathEffect(drawing.border);
      c.drawRoundRect(box, 5, 5, p);
      p.setPathEffect(null);
      return;
    }
    c.drawRoundRect(box, 5, 5, p);
    if ("plain".equals(tape.pattern)) return;
    int saved = c.save();
    c.clipRect(box);
    Rect visible = drawing.visible;
    c.getClipBounds(visible);
    float[] matrix = drawing.values;
    c.getMatrix(drawing.matrix);
    drawing.matrix.getValues(matrix);
    float scale = Math.max(.0001f, (float) Math.hypot(matrix[0], matrix[3]));
    float spacing = Math.max(18, 12 / scale);
    p.setColor(Color.argb(35, 255, 255, 255));
    p.setStrokeWidth(4);
    if ("stripes".equals(tape.pattern)) {
      float bottom = Math.min(box.bottom, visible.bottom), top = Math.max(box.top, visible.top);
      float left = Math.max(box.left, visible.left), right = Math.min(box.right, visible.right);
      for (float x = (float) Math.floor((left - bottom) / spacing) * spacing;
          x < right - top;
          x += spacing) c.drawLine(x + top, top, x + bottom, bottom, p);
    } else {
      for (float y = (float) Math.floor(visible.top / spacing) * spacing;
          y < visible.bottom;
          y += spacing)
        for (float x = (float) Math.floor(visible.left / spacing) * spacing;
            x < visible.right;
            x += spacing) c.drawCircle(x, y, 2.3f, p);
    }
    c.restoreToCount(saved);
  }
}
