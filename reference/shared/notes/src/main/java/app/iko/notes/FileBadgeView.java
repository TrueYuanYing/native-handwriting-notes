package app.iko.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Tiny original document illustrations, shared by import and export controls. */
final class FileBadgeView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final String label;
  private final int color;

  FileBadgeView(Context c, String label, int color) {
    super(c);
    this.label = label;
    this.color = color;
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas c) {
    float size = Math.min(getWidth() / 48f, getHeight() / 48f);
    c.save();
    c.translate((getWidth() - 48 * size) / 2, (getHeight() - 48 * size) / 2);
    c.scale(size, size);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Ui.mix(color, Ui.SURFACE, .14f));
    c.drawRoundRect(7, 2, 41, 46, 7, 7, paint);
    paint.setColor(color);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.5f);
    Path p = new Path();
    p.moveTo(30, 3);
    p.lineTo(30, 12);
    p.lineTo(40, 12);
    c.drawPath(p, paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setTextSize(label.length() > 3 ? 10 : 12);
    paint.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    c.drawText(label, 24 - paint.measureText(label) / 2, 29, paint);
    paint.setColor(Ui.mix(color, Ui.SURFACE, .45f));
    c.drawRoundRect(15, 35, 33, 37, 1, 1, paint);
    c.restore();
  }
}
