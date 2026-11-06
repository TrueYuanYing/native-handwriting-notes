package app.iko.notes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** A continuous circular arrow shared by toolbar and canvas controls. */
final class RotationGlyph {
  static void arrow(Canvas c, float x, float y, float radius, Paint p) {
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
    c.drawArc(x - radius, y - radius, x + radius, y + radius, -55, 300, false, p);
    double end = Math.toRadians(245);
    float tipX = x + radius * (float) Math.cos(end), tipY = y + radius * (float) Math.sin(end);
    float tangentX = -(float) Math.sin(end),
        tangentY = (float) Math.cos(end),
        length = radius * .48f,
        wing = radius * .3f;
    Path head = new Path();
    head.moveTo(
        tipX - tangentX * length - tangentY * wing, tipY - tangentY * length + tangentX * wing);
    head.lineTo(tipX, tipY);
    head.lineTo(
        tipX - tangentX * length + tangentY * wing, tipY - tangentY * length - tangentX * wing);
    c.drawPath(head, p);
  }

  static void handle(Canvas c, float x, float y, float radius, int color, Paint p) {
    int save = c.save();
    p.setStyle(Paint.Style.FILL);
    p.setColor(0xffffffff);
    c.drawCircle(x, y, radius, p);
    p.setColor(color);
    p.setStrokeWidth(radius * .14f);
    p.setStyle(Paint.Style.STROKE);
    c.drawCircle(x, y, radius, p);
    arrow(c, x, y, radius * .57f, p);
    c.restoreToCount(save);
    p.setStyle(Paint.Style.FILL);
  }

  private RotationGlyph() {}
}
