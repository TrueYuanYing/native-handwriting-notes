package app.iko.notes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;

/** One reusable, frame-driven indicator; touch distance remains the source of truth. */
final class PagePullIndicator {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path icon = new Path();
  private float visibility, check, progress;
  private long lastFrame;

  boolean draw(
      Canvas canvas, float center, float bottom, float distance, float threshold, float dp) {
    long now = SystemClock.uptimeMillis();
    float dt = lastFrame == 0 ? 16 : Math.min(40, now - lastFrame);
    lastFrame = now;
    float shown = Math.min(1, distance / (24 * dp));
    float ready = distance >= threshold ? 1 : 0;
    visibility = approach(visibility, shown, dt, 65);
    check = approach(check, ready, dt, 48);
    if (distance > 0) progress = Math.min(1, distance / threshold);
    if (visibility < .005f && shown == 0) {
      visibility = check = progress = 0;
      lastFrame = 0;
      return false;
    }
    int saved =
        canvas.saveLayerAlpha(
            center - 100 * dp,
            bottom - 45 * dp,
            center + 100 * dp,
            bottom + 15 * dp,
            Math.round(255 * visibility));
    canvas.translate(center, bottom + 14 * dp * (1 - visibility));
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Ui.mix(Ui.ACCENT, Ui.SURFACE, .06f + .1f * check));
    canvas.drawRoundRect(-99 * dp, -44 * dp, 99 * dp, 0, 22 * dp, 22 * dp, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeWidth(2 * dp);
    paint.setColor(Ui.mix(Ui.ACCENT, Ui.SURFACE, .18f));
    canvas.drawCircle(-72 * dp, -22 * dp, 12 * dp, paint);
    paint.setColor(Ui.ACCENT);
    canvas.drawArc(-84 * dp, -34 * dp, -60 * dp, -10 * dp, -90, 360 * progress, false, paint);
    int iconSave = canvas.save();
    canvas.translate(-72 * dp, -22 * dp);
    canvas.scale(dp, dp);
    icon.rewind();
    icon.moveTo(-4 - check, 0);
    icon.lineTo(-check, -4 + 8 * check);
    icon.lineTo(4 + 2 * check, -4 * check);
    paint.setStrokeWidth(1.8f);
    canvas.drawPath(icon, paint);
    paint.setAlpha(Math.round(255 * (1 - check)));
    canvas.drawLine(0, -3.5f, 0, 5, paint);
    canvas.restoreToCount(iconSave);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Ui.TEXT);
    paint.setTextSize(13 * dp);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setAlpha(Math.round(255 * (1 - check)));
    canvas.drawText("上推新增一页", 14 * dp, -17 * dp, paint);
    paint.setAlpha(Math.round(255 * check));
    canvas.drawText("松开新增一页", 14 * dp, -17 * dp, paint);
    canvas.restoreToCount(saved);
    return Math.abs(visibility - shown) > .005f || Math.abs(check - ready) > .005f;
  }

  private static float approach(float value, float target, float dt, float duration) {
    if (Math.abs(value - target) < .005f) return target;
    return target + (value - target) * (float) Math.exp(-dt / duration);
  }
}
