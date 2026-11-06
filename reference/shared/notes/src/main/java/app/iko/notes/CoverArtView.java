package app.iko.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Resolution independent original cover, in a 120 by 160 stationery coordinate system. */
final class CoverArtView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private String pattern;
  private int color;

  CoverArtView(Context context, String pattern, int color) {
    super(context);
    setStyle(pattern, color);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  void setStyle(String pattern, int color) {
    this.pattern = CoverCatalog.contains(pattern) ? pattern : "plain";
    this.color = color | 0xff000000;
    invalidate();
  }

  private int mix(int target, float amount) {
    return Color.rgb(
        Math.round(Color.red(color) * (1 - amount) + Color.red(target) * amount),
        Math.round(Color.green(color) * (1 - amount) + Color.green(target) * amount),
        Math.round(Color.blue(color) * (1 - amount) + Color.blue(target) * amount));
  }

  private void fill(Canvas c, int ink, float l, float t, float r, float b, float radius) {
    paint.setColor(ink);
    paint.setStyle(Paint.Style.FILL);
    c.drawRoundRect(l, t, r, b, radius, radius, paint);
  }

  private void dot(Canvas c, int ink, float x, float y, float r) {
    paint.setColor(ink);
    paint.setStyle(Paint.Style.FILL);
    c.drawCircle(x, y, r, paint);
  }

  private void line(Canvas c, int ink, float x, float y, float xx, float yy, float width) {
    paint.setColor(ink);
    paint.setStrokeWidth(width);
    c.drawLine(x, y, xx, yy, paint);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (getWidth() == 0 || getHeight() == 0) return;
    int save = canvas.save();
    canvas.scale(getWidth() / 120f, getHeight() / 160f);
    Path bounds = new Path();
    bounds.addRoundRect(new RectF(0, 0, 120, 160), 8, 8, Path.Direction.CW);
    canvas.clipPath(bounds);
    int paper = mix(Color.WHITE, .72f), light = mix(Color.WHITE, .4f), ink = mix(0xff263343, .56f);
    fill(canvas, paper, 0, 0, 120, 160, 0);
    if (pattern.equals("plain") || pattern.equals("duo")) {
      fill(canvas, color, 0, 0, 120, pattern.equals("duo") ? 92 : 160, 0);
      if (pattern.equals("duo")) line(canvas, light, 16, 92, 108, 92, 1);
    } else if (pattern.equals("dots")) {
      for (int y = 17; y < 160; y += 19)
        for (int x = 18; x < 120; x += 19) dot(canvas, color, x + (y / 19 % 2) * 4, y, 1.6f);
    } else if (pattern.equals("stripes")) {
      for (int x = -140; x < 130; x += 15) line(canvas, light, x, 0, x + 160, 160, 5);
    } else if (pattern.equals("grid")) {
      for (int x = 11; x < 120; x += 18) line(canvas, light, x, 0, x, 160, .8f);
      for (int y = 9; y < 160; y += 18) line(canvas, light, 0, y, 120, y, .8f);
    } else if (pattern.equals("flowers")) {
      for (int n = 0; n < 7; n++) {
        float x = 24 + (n * 37) % 80, y = 24 + (n * 43) % 116;
        for (int k = 0; k < 5; k++)
          dot(
              canvas,
              light,
              x + (float) Math.cos(k * 1.256) * 5,
              y + (float) Math.sin(k * 1.256) * 5,
              3.5f);
        dot(canvas, ink, x, y, 1.7f);
      }
    } else if (pattern.equals("stars") || pattern.equals("moon")) {
      fill(canvas, mix(Color.WHITE, .15f), 0, 0, 120, 160, 0);
      for (int n = 0; n < 12; n++) {
        float x = 20 + (n * 31) % 92, y = 17 + (n * 47) % 130;
        line(canvas, paper, x - 2, y, x + 2, y, .8f);
        line(canvas, paper, x, y - 2, x, y + 2, .8f);
      }
      if (pattern.equals("moon")) {
        dot(canvas, 0xfffff1cf, 78, 43, 15);
        dot(canvas, mix(Color.WHITE, .15f), 85, 37, 14);
      }
    } else if (pattern.equals("clouds")) {
      for (int n = 0; n < 5; n++) {
        float x = 15 + (n * 39) % 80, y = 28 + n * 27;
        fill(canvas, 0xfffcfcf7, x, y, x + 30, y + 9, 5);
        dot(canvas, 0xfffcfcf7, x + 11, y, 7);
        dot(canvas, 0xfffcfcf7, x + 20, y + 1, 5);
      }
    } else if (pattern.equals("hills")) {
      dot(canvas, 0xffffe8af, 88, 35, 11);
      paint.setColor(light);
      canvas.drawOval(-42, 82, 155, 234, paint);
      paint.setColor(color);
      canvas.drawOval(35, 107, 202, 224, paint);
      line(canvas, paper, 79, 130, 79, 150, 1.2f);
      dot(canvas, paper, 79, 127, 5);
    } else if (pattern.equals("geometry")) {
      dot(canvas, light, 95, 29, 27);
      fill(canvas, color, 17, 103, 53, 139, 3);
      Path triangle = new Path();
      triangle.moveTo(76, 74);
      triangle.lineTo(105, 120);
      triangle.lineTo(48, 120);
      triangle.close();
      paint.setColor(mix(Color.WHITE, .22f));
      canvas.drawPath(triangle, paint);
    } else if (pattern.equals("spine")) {
      fill(canvas, color, 0, 0, 26, 160, 0);
      line(canvas, light, 29, 0, 29, 160, 1);
      for (int y = 17; y < 147; y += 7) line(canvas, light, 4, y, 19, y, 1);
    }
    // A small real paper label gives a quiet centre and predictable text contrast.
    int label = 0xfffffdf6;
    fill(canvas, mix(Color.BLACK, .12f), 31, 60, 99, 97, 3);
    fill(canvas, label, 30, 59, 98, 95, 3);
    if (pattern.equals("label")) {
      fill(canvas, light, 48, 54, 78, 64, 0);
    }
    int labelInk = 0xff47505a;
    paint.setColor(labelInk);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTextSize(8);
    paint.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    canvas.drawText("N O T E S", 64, 76, paint);
    line(canvas, 0xffd3d4cf, 43, 83, 85, 83, .7f);
    paint.setTextSize(4.5f);
    canvas.drawText("IKO  /  EVERYDAY", 64, 90, paint);
    fill(canvas, mix(Color.BLACK, .10f), 0, 0, 7, 160, 0);
    line(canvas, mix(Color.WHITE, .5f), 8, 0, 8, 160, .7f);
    line(canvas, mix(Color.BLACK, .10f), 119, 5, 119, 155, .8f);
    canvas.restoreToCount(save);
  }
}
