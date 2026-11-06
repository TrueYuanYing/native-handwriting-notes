package app.iko.notes;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;

/** Bounded input storage and reusable glowing curves; never enters saved notebook data. */
final class LaserPointer {
  private static final int CAPACITY = 2048;
  private final float[] xs = new float[CAPACITY], ys = new float[CAPACITY];
  private final long[] times = new long[CAPACITY];
  private final int[] gestures = new int[CAPACITY];
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path curve = new Path();
  private static final float[] WIDTHS = {20, 14, 9, 5.6f, 3.6f, 1};
  private static final int[] ALPHAS = {12, 22, 45, 90, 255, 255};
  int color = 0xffff315b, duration = 1500;
  float density = 1;
  boolean trail = true;
  private boolean contact;
  private int gesture, head, count;
  private RadialGradient halo;
  private float haloRadius;
  private int haloColor;

  void start(float x, float y) {
    gesture++;
    contact = true;
    add(x, y);
  }

  void release() {
    contact = false;
    if (count > 0) times[index(count - 1)] = SystemClock.uptimeMillis();
  }

  void add(float x, float y) {
    if (!trail) {
      head = 0;
      count = 0;
    }
    if (count > 0) {
      int last = index(count - 1);
      if (gestures[last] == gesture && xs[last] == x && ys[last] == y) {
        times[last] = SystemClock.uptimeMillis();
        return;
      }
    }
    if (count == CAPACITY) {
      head = (head + 1) % CAPACITY;
      count--;
    }
    int at = index(count++);
    xs[at] = x;
    ys[at] = y;
    times[at] = SystemClock.uptimeMillis();
    gestures[at] = gesture;
  }

  void clear() {
    head = 0;
    count = 0;
    contact = false;
  }

  private int index(int offset) {
    return (head + offset) % CAPACITY;
  }

  boolean draw(Canvas canvas, float scale) {
    long now = SystemClock.uptimeMillis();
    if (contact && count > 0) times[index(count - 1)] = now;
    while (count > 0) {
      int end = 1;
      while (end < count && gestures[index(end)] == gestures[head]) end++;
      // Opacity belongs to a complete gesture, so lifetime must use that same boundary.
      if ((contact && gestures[head] == gesture) || now - times[index(end - 1)] < duration) break;
      head = index(end);
      count -= end;
    }
    if (count == 0) return false;
    float unit = density / scale;
    if (trail && count > 1) {
      for (int begin = 0; begin < count; ) {
        int end = begin + 1;
        while (end < count && gestures[index(end)] == gestures[index(begin)]) end++;
        if (end - begin > 1) {
          drawTrail(canvas, unit, begin, end, now);
        }
        begin = end;
      }
    }
    int last = index(count - 1);
    float radius = 15 * unit, alpha = fade(now - times[last]);
    if (halo == null || haloColor != color || haloRadius != radius) {
      halo =
          new RadialGradient(
              0,
              0,
              radius,
              new int[] {color | 0xff000000, (color & 0xffffff) | 0xa0000000, color & 0xffffff},
              new float[] {0, .25f, 1},
              Shader.TileMode.CLAMP);
      haloRadius = radius;
      haloColor = color;
    }
    int saved = canvas.save();
    canvas.translate(xs[last], ys[last]);
    paint.setStyle(Paint.Style.FILL);
    paint.setShader(halo);
    paint.setAlpha(Math.round(255 * alpha));
    canvas.drawCircle(0, 0, radius, paint);
    paint.setShader(null);
    paint.setColor(color);
    paint.setAlpha(Math.round(255 * alpha));
    canvas.drawCircle(0, 0, 3.6f * unit, paint);
    paint.setColor(Color.WHITE);
    paint.setAlpha(Math.round(255 * alpha));
    canvas.drawCircle(0, 0, 1.5f * unit, paint);
    canvas.restoreToCount(saved);
    return !contact || gestures[head] != gesture;
  }

  private float fade(long age) {
    float value = Math.max(0, 1 - age / (float) duration);
    return value * value * (3 - 2 * value);
  }

  /** Native round joins remain a union at tight turns instead of folding a wide ribbon. */
  private void drawTrail(Canvas canvas, float unit, int begin, int end, long now) {
    int first = index(begin);
    curve.rewind();
    curve.moveTo(xs[first], ys[first]);
    for (int n = begin; n < end; n++) {
      int at = index(n);
      boolean last = n == end - 1;
      float x = last ? xs[at] : (xs[at] + xs[index(n + 1)]) * .5f;
      float y = last ? ys[at] : (ys[at] + ys[index(n + 1)]) * .5f;
      curve.quadTo(xs[at], ys[at], x, y);
    }
    float alpha = fade(now - times[index(end - 1)]);
    paint.setShader(null);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    for (int layer = 0; layer < WIDTHS.length; layer++) {
      paint.setStrokeWidth(WIDTHS[layer] * unit);
      paint.setColor(layer == WIDTHS.length - 1 ? Color.WHITE : color);
      paint.setAlpha(Math.round(ALPHAS[layer] * alpha));
      canvas.drawPath(curve, paint);
    }
  }
}
