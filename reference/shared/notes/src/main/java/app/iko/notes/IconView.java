package app.iko.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small original vector icons. Every icon is paired with an accessibility label by its caller. */
final class IconView extends View {
  String icon;
  int color = Ui.MUTED;
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

  IconView(Context c, String icon) {
    super(c);
    this.icon = icon;
    setMinimumWidth(Ui.dp(c, 48));
    setMinimumHeight(Ui.dp(c, 48));
    setFocusable(true);
  }

  void tint(int color) {
    this.color = color;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.save();
    float s = Ui.dp(getContext(), 24) / 24f;
    canvas.translate((getWidth() - 24 * s) / 2, (getHeight() - 24 * s) / 2);
    canvas.scale(s, s);
    p.setColor(color);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(1.65f);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
    Path q = new Path();
    switch (icon) {
      case "infinite":
        q.moveTo(12, 12);
        q.cubicTo(4, -1, -1, 22, 8, 16);
        q.lineTo(16, 7);
        q.cubicTo(24, 1, 25, 23, 16, 16);
        q.close();
        break;
      case "laser":
        canvas.drawCircle(12, 12, 3, p);
        for (int i = 0; i < 8; i++) {
          double a = i * Math.PI / 4;
          canvas.drawLine(
              12 + (float) Math.cos(a) * 7,
              12 + (float) Math.sin(a) * 7,
              12 + (float) Math.cos(a) * 10,
              12 + (float) Math.sin(a) * 10,
              p);
        }
        break;
      case "tape":
        q.moveTo(3, 7);
        q.lineTo(21, 7);
        q.lineTo(19, 10);
        q.lineTo(21, 13);
        q.lineTo(19, 17);
        q.lineTo(3, 17);
        q.lineTo(5, 14);
        q.lineTo(3, 11);
        q.close();
        canvas.drawLine(8, 8, 5, 15, p);
        canvas.drawLine(13, 8, 9, 16, p);
        canvas.drawLine(18, 8, 14, 16, p);
        break;
      case "microphone":
        canvas.drawRoundRect(9, 2, 15, 15, 3, 3, p);
        canvas.drawArc(5, 7, 19, 19, 0, 180, false, p);
        q.moveTo(12, 19);
        q.lineTo(12, 22);
        q.moveTo(8, 22);
        q.lineTo(16, 22);
        break;
      case "record":
        canvas.drawCircle(12, 12, 8, p);
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(12, 12, 4, p);
        break;
      case "play":
        q.moveTo(8, 4);
        q.lineTo(20, 12);
        q.lineTo(8, 20);
        q.close();
        break;
      case "pause":
        canvas.drawRoundRect(6, 4, 9, 20, 1, 1, p);
        canvas.drawRoundRect(15, 4, 18, 20, 1, 1, p);
        break;
      case "stop":
        canvas.drawRoundRect(5, 5, 19, 19, 3, 3, p);
        break;
      case "eye":
      case "eye-off":
        q.moveTo(2, 12);
        q.cubicTo(7, 3, 17, 3, 22, 12);
        q.cubicTo(17, 21, 7, 21, 2, 12);
        canvas.drawCircle(12, 12, 3, p);
        if ("eye-off".equals(icon)) {
          q.moveTo(3, 3);
          q.lineTo(21, 21);
        }
        break;
      case "bold":
        q.moveTo(7, 3);
        q.lineTo(7, 21);
        q.lineTo(14, 21);
        q.cubicTo(21, 21, 21, 11, 13, 12);
        q.lineTo(7, 12);
        q.lineTo(13, 12);
        q.cubicTo(20, 12, 20, 3, 13, 3);
        q.close();
        p.setStrokeWidth(2.4f);
        break;
      case "align-left":
      case "align-center":
      case "align-right":
        for (int i = 0; i < 4; i++) {
          float length = i % 2 == 0 ? 16 : 10;
          float left =
              "align-left".equals(icon)
                  ? 4
                  : "align-right".equals(icon) ? 20 - length : 12 - length / 2;
          canvas.drawLine(left, 5 + i * 5, left + length, 5 + i * 5, p);
        }
        break;
      case "back":
        q.moveTo(14, 5);
        q.lineTo(7, 12);
        q.lineTo(14, 19);
        break;
      case "plus":
        q.moveTo(12, 5);
        q.lineTo(12, 19);
        q.moveTo(5, 12);
        q.lineTo(19, 12);
        break;
      case "addpage":
        canvas.drawRoundRect(new RectF(4, 3, 18, 21), 2, 2, p);
        q.moveTo(11, 9);
        q.lineTo(11, 17);
        q.moveTo(7, 13);
        q.lineTo(15, 13);
        break;
      case "library":
        canvas.drawRoundRect(new RectF(4, 4, 20, 20), 3, 3, p);
        q.moveTo(9, 4);
        q.lineTo(9, 20);
        break;
      case "recent":
        canvas.drawCircle(12, 12, 8, p);
        q.moveTo(12, 7);
        q.lineTo(12, 12);
        q.lineTo(16, 14);
        break;
      case "star":
        q.moveTo(12, 2);
        q.lineTo(15, 8);
        q.lineTo(22, 9);
        q.lineTo(17, 14);
        q.lineTo(18, 21);
        q.lineTo(12, 18);
        q.lineTo(6, 21);
        q.lineTo(7, 14);
        q.lineTo(2, 9);
        q.lineTo(9, 8);
        q.close();
        break;
      case "trash":
        q.moveTo(4, 6);
        q.lineTo(20, 6);
        q.moveTo(9, 6);
        q.lineTo(9, 3);
        q.lineTo(15, 3);
        q.lineTo(15, 6);
        q.moveTo(6, 6);
        q.lineTo(7, 21);
        q.lineTo(17, 21);
        q.lineTo(18, 6);
        q.moveTo(10, 10);
        q.lineTo(10, 17);
        q.moveTo(14, 10);
        q.lineTo(14, 17);
        break;
      case "settings":
        canvas.drawCircle(12, 12, 4, p);
        canvas.drawCircle(12, 12, 8, p);
        for (int i = 0; i < 8; i++) {
          double a = i * Math.PI / 4;
          canvas.drawLine(
              12 + (float) Math.cos(a) * 8,
              12 + (float) Math.sin(a) * 8,
              12 + (float) Math.cos(a) * 10,
              12 + (float) Math.sin(a) * 10,
              p);
        }
        break;
      case "undo":
        q.moveTo(8, 5);
        q.lineTo(3, 10);
        q.lineTo(8, 15);
        q.moveTo(3, 10);
        q.lineTo(14, 10);
        q.cubicTo(23, 10, 23, 20, 13, 20);
        break;
      case "redo":
        q.moveTo(16, 5);
        q.lineTo(21, 10);
        q.lineTo(16, 15);
        q.moveTo(21, 10);
        q.lineTo(10, 10);
        q.cubicTo(1, 10, 1, 20, 11, 20);
        break;
      case "pages":
        canvas.drawRoundRect(new RectF(7, 3, 20, 18), 2, 2, p);
        q.moveTo(4, 7);
        q.lineTo(4, 21);
        q.lineTo(16, 21);
        break;
      case "export":
        q.moveTo(12, 16);
        q.lineTo(12, 3);
        q.moveTo(7, 8);
        q.lineTo(12, 3);
        q.lineTo(17, 8);
        q.moveTo(5, 13);
        q.lineTo(5, 21);
        q.lineTo(19, 21);
        q.lineTo(19, 13);
        break;
      case "import":
        q.moveTo(12, 3);
        q.lineTo(12, 16);
        q.moveTo(7, 11);
        q.lineTo(12, 16);
        q.lineTo(17, 11);
        q.moveTo(5, 15);
        q.lineTo(5, 21);
        q.lineTo(19, 21);
        q.lineTo(19, 15);
        break;
      case "paper":
        canvas.drawRoundRect(new RectF(5, 3, 19, 21), 2, 2, p);
        q.moveTo(8, 8);
        q.lineTo(16, 8);
        q.moveTo(8, 12);
        q.lineTo(16, 12);
        q.moveTo(8, 16);
        q.lineTo(13, 16);
        break;
      case "info":
        canvas.drawCircle(12, 12, 9, p);
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(12, 7, 1, p);
        q.moveTo(12, 11);
        q.lineTo(12, 17);
        break;
      case "screenshot":
        q.moveTo(8, 3);
        q.lineTo(4, 3);
        q.lineTo(4, 8);
        q.moveTo(16, 3);
        q.lineTo(20, 3);
        q.lineTo(20, 8);
        q.moveTo(4, 16);
        q.lineTo(4, 21);
        q.lineTo(9, 21);
        q.moveTo(15, 21);
        q.lineTo(20, 21);
        q.lineTo(20, 16);
        canvas.drawCircle(12, 12, 4, p);
        break;
      case "stylus":
        q.moveTo(6, 18);
        q.lineTo(17, 4);
        q.lineTo(20, 7);
        q.lineTo(9, 21);
        q.lineTo(5, 22);
        q.close();
        q.moveTo(8, 18);
        q.lineTo(12, 20);
        break;
      case "finger":
        q.moveTo(8, 21);
        q.lineTo(5, 17);
        q.cubicTo(3, 14, 6, 12, 8, 15);
        q.lineTo(9, 16);
        q.lineTo(9, 6);
        q.cubicTo(9, 3, 13, 3, 13, 6);
        q.lineTo(13, 13);
        q.lineTo(14, 9);
        q.cubicTo(15, 7, 18, 8, 18, 10);
        q.lineTo(19, 15);
        q.cubicTo(20, 19, 17, 22, 14, 22);
        q.close();
        break;
      case "eraser":
        q.moveTo(3, 14);
        q.lineTo(12, 4);
        q.lineTo(21, 12);
        q.lineTo(12, 21);
        q.lineTo(9, 21);
        q.close();
        q.moveTo(7, 10);
        q.lineTo(16, 18);
        q.moveTo(12, 21);
        q.lineTo(22, 21);
        break;
      case "lasso":
        p.setPathEffect(new android.graphics.DashPathEffect(new float[] {3, 3}, 0));
        canvas.drawOval(new RectF(3, 3, 21, 17), p);
        p.setPathEffect(null);
        q.moveTo(7, 16);
        q.cubicTo(3, 22, 12, 23, 12, 18);
        break;
      case "shape":
        canvas.drawRoundRect(new RectF(4, 4, 17, 17), 2, 2, p);
        canvas.drawCircle(16, 16, 5, p);
        break;
      case "line":
        q.moveTo(4, 20);
        q.lineTo(20, 4);
        break;
      case "rectangle":
        canvas.drawRoundRect(new RectF(3, 5, 21, 19), 1.5f, 1.5f, p);
        break;
      case "ellipse":
        canvas.drawOval(new RectF(3, 5, 21, 19), p);
        break;
      case "arrow":
        q.moveTo(4, 20);
        q.lineTo(20, 4);
        q.moveTo(10, 4);
        q.lineTo(20, 4);
        q.lineTo(20, 14);
        break;
      case "erase-stroke":
        q.moveTo(3, 15);
        q.cubicTo(6, 2, 10, 22, 14, 10);
        q.cubicTo(17, 1, 18, 18, 21, 9);
        q.moveTo(4, 20);
        q.lineTo(20, 4);
        break;
      case "erase-segment":
        q.moveTo(3, 15);
        q.cubicTo(5, 6, 7, 8, 9, 12);
        q.moveTo(15, 12);
        q.cubicTo(18, 3, 19, 18, 21, 9);
        p.setStrokeWidth(1.1f);
        canvas.drawCircle(12, 12, 4, p);
        break;
      case "fit-width":
        q.moveTo(3, 4);
        q.lineTo(3, 20);
        q.moveTo(21, 4);
        q.lineTo(21, 20);
        q.moveTo(5, 12);
        q.lineTo(19, 12);
        q.moveTo(8, 9);
        q.lineTo(5, 12);
        q.lineTo(8, 15);
        q.moveTo(16, 9);
        q.lineTo(19, 12);
        q.lineTo(16, 15);
        break;
      case "focus":
        canvas.drawRoundRect(new RectF(3, 3, 21, 21), 2, 2, p);
        q.moveTo(3, 13);
        q.lineTo(21, 13);
        canvas.drawRoundRect(new RectF(8, 6, 16, 10), 1, 1, p);
        q.moveTo(7, 17);
        q.lineTo(17, 17);
        break;
      case "copy":
        canvas.drawRoundRect(new RectF(8, 8, 21, 21), 2, 2, p);
        q.moveTo(16, 5);
        q.lineTo(16, 3);
        q.lineTo(3, 3);
        q.lineTo(3, 16);
        q.lineTo(5, 16);
        break;
      case "palette":
        canvas.drawCircle(12, 12, 9, p);
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(8, 8, 1.5f, p);
        canvas.drawCircle(15, 7, 1.5f, p);
        canvas.drawCircle(18, 13, 1.5f, p);
        canvas.drawCircle(8, 16, 1.5f, p);
        break;
      case "width":
        canvas.drawLine(4, 5, 20, 5, p);
        p.setStrokeWidth(3);
        canvas.drawLine(4, 12, 20, 12, p);
        p.setStrokeWidth(5);
        canvas.drawLine(4, 20, 20, 20, p);
        break;
      case "text":
        q.moveTo(3, 5);
        q.lineTo(21, 5);
        q.moveTo(12, 5);
        q.lineTo(12, 21);
        q.moveTo(8, 21);
        q.lineTo(16, 21);
        break;
      case "image":
        canvas.drawRoundRect(new RectF(3, 4, 21, 20), 2, 2, p);
        canvas.drawCircle(16, 9, 2, p);
        q.moveTo(3, 17);
        q.lineTo(9, 11);
        q.lineTo(15, 17);
        q.lineTo(18, 14);
        q.lineTo(21, 17);
        break;
      case "highlighter":
        q.moveTo(6, 16);
        q.lineTo(13, 3);
        q.lineTo(21, 7);
        q.lineTo(14, 20);
        q.close();
        q.moveTo(6, 16);
        q.lineTo(3, 21);
        q.lineTo(10, 21);
        q.lineTo(14, 20);
        break;
      case "ballpoint":
        q.moveTo(5, 18);
        q.lineTo(15, 3);
        q.lineTo(20, 7);
        q.lineTo(9, 21);
        q.lineTo(4, 22);
        q.close();
        q.moveTo(12, 7);
        q.lineTo(17, 11);
        break;
      case "fineliner":
        canvas.drawRoundRect(new RectF(8, 3, 16, 15), 1, 1, p);
        q.moveTo(9, 15);
        q.lineTo(10, 20);
        q.lineTo(14, 20);
        q.lineTo(15, 15);
        q.moveTo(12, 20);
        q.lineTo(12, 23);
        q.moveTo(8, 7);
        q.lineTo(16, 7);
        break;
      case "pencil":
        q.moveTo(4, 17);
        q.lineTo(16, 3);
        q.lineTo(21, 7);
        q.lineTo(9, 21);
        q.lineTo(3, 22);
        q.close();
        q.moveTo(13, 6);
        q.lineTo(18, 10);
        break;
      case "pen":
        q.moveTo(12, 2);
        q.lineTo(19, 15);
        q.lineTo(14, 22);
        q.lineTo(10, 22);
        q.lineTo(5, 15);
        q.close();
        q.moveTo(12, 2);
        q.lineTo(12, 13);
        canvas.drawCircle(12, 14, 1.7f, p);
        break;
      case "brush":
        q.moveTo(10, 14);
        q.lineTo(18, 2);
        q.lineTo(21, 4);
        q.lineTo(13, 16);
        q.close();
        q.moveTo(10, 14);
        q.cubicTo(2, 13, 7, 22, 3, 22);
        q.cubicTo(13, 23, 17, 18, 13, 16);
        break;
      case "more":
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) canvas.drawCircle(5 + i * 7, 12, 1.5f, p);
        break;
      case "hand":
        q.moveTo(7, 14);
        q.lineTo(7, 8);
        q.cubicTo(7, 5, 10, 5, 10, 8);
        q.lineTo(10, 3);
        q.cubicTo(10, 1, 13, 1, 13, 3);
        q.lineTo(13, 10);
        q.lineTo(13, 6);
        q.cubicTo(13, 4, 16, 4, 16, 6);
        q.lineTo(16, 11);
        q.lineTo(16, 9);
        q.cubicTo(16, 7, 19, 7, 19, 9);
        q.lineTo(19, 16);
        q.cubicTo(19, 24, 10, 24, 7, 19);
        q.lineTo(3, 14);
        q.cubicTo(1, 11, 4, 10, 7, 14);
        break;
      case "next":
        q.moveTo(9, 5);
        q.lineTo(16, 12);
        q.lineTo(9, 19);
        break;
      case "fit":
        q.moveTo(8, 4);
        q.lineTo(4, 4);
        q.lineTo(4, 8);
        q.moveTo(16, 4);
        q.lineTo(20, 4);
        q.lineTo(20, 8);
        q.moveTo(4, 16);
        q.lineTo(4, 20);
        q.lineTo(8, 20);
        q.moveTo(20, 16);
        q.lineTo(20, 20);
        q.lineTo(16, 20);
        break;
      case "check":
        q.moveTo(5, 12);
        q.lineTo(10, 17);
        q.lineTo(20, 6);
        break;
      case "close":
        q.moveTo(6, 6);
        q.lineTo(18, 18);
        q.moveTo(18, 6);
        q.lineTo(6, 18);
        break;
      case "rotate":
        RotationGlyph.arrow(canvas, 12, 12, 8, p);
        break;
      case "zoom-in":
      case "zoom-out":
        canvas.drawCircle(10, 10, 7, p);
        q.moveTo(15, 15);
        q.lineTo(21, 21);
        q.moveTo(6, 10);
        q.lineTo(14, 10);
        if ("zoom-in".equals(icon)) {
          q.moveTo(10, 6);
          q.lineTo(10, 14);
        }
        break;
      case "cut":
        canvas.drawCircle(6, 17, 3, p);
        canvas.drawCircle(18, 17, 3, p);
        q.moveTo(8, 15);
        q.lineTo(19, 3);
        q.moveTo(16, 15);
        q.lineTo(5, 3);
        break;
      case "paste":
        q.moveTo(8, 5);
        q.lineTo(5, 5);
        q.lineTo(5, 22);
        q.lineTo(20, 22);
        q.lineTo(20, 5);
        q.lineTo(16, 5);
        canvas.drawRoundRect(8, 2, 16, 7, 2, 2, p);
        break;
      case "share":
        q.moveTo(12, 15);
        q.lineTo(12, 3);
        q.moveTo(7, 8);
        q.lineTo(12, 3);
        q.lineTo(17, 8);
        q.moveTo(5, 12);
        q.lineTo(5, 21);
        q.lineTo(19, 21);
        q.lineTo(19, 12);
        break;
      default:
        if (GeometryPresets.contains(icon)) {
          java.util.List<NoteDocument.Point> points = GeometryPresets.outline(icon,
              new NoteDocument.Point(3, 3, 1, 0, 0), new NoteDocument.Point(21, 21, 1, 0, 0), .7f);
          q.moveTo(points.get(0).x, points.get(0).y);
          for (int n = 1; n < points.size(); n++) q.lineTo(points.get(n).x, points.get(n).y);
        } else canvas.drawCircle(12, 12, 7, p);
    }
    canvas.drawPath(q, p);
    canvas.restore();
  }
}
