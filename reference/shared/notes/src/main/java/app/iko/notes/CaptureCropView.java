package app.iko.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.function.Consumer;

/** Frozen paper preview: gestures change only a rectangle, never compress or save images. */
final class CaptureCropView extends View {
  private final Bitmap image;
  private final RectF selected = new RectF(), start = new RectF(), viewport = new RectF();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Consumer<Boolean> changed;
  private float downX, downY;
  private int handle = -1;

  CaptureCropView(Context context, Bitmap image, Consumer<Boolean> changed) {
    super(context);
    this.image = image;
    this.changed = changed;
    setContentDescription("截图框选区域");
  }

  private boolean valid() {
    return selected.width() >= Ui.dp(getContext(), 20)
        && selected.height() >= Ui.dp(getContext(), 20);
  }

  Bitmap takeCrop() {
    if (!valid()) throw new IllegalStateException("请先拖动选择截图区域");
    Rect pixels =
        new Rect(
            Math.max(0, (int) Math.floor(selected.left * image.getWidth() / getWidth())),
            Math.max(0, (int) Math.floor(selected.top * image.getHeight() / getHeight())),
            Math.min(
                image.getWidth(), (int) Math.ceil(selected.right * image.getWidth() / getWidth())),
            Math.min(
                image.getHeight(),
                (int) Math.ceil(selected.bottom * image.getHeight() / getHeight())));
    Bitmap cropped =
        Bitmap.createBitmap(image, pixels.left, pixels.top, pixels.width(), pixels.height());
    return cropped == image ? image.copy(Bitmap.Config.ARGB_8888, false) : cropped;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    viewport.set(0, 0, getWidth(), getHeight());
    canvas.drawBitmap(image, null, viewport, paint);
    int save = canvas.save();
    if (valid()) canvas.clipOutRect(selected);
    canvas.drawColor(0x770c1424);
    canvas.restoreToCount(save);
    if (!valid()) {
      paint.setColor(0xffffffff);
      paint.setTextSize(Ui.dp(getContext(), 16));
      paint.setTextAlign(Paint.Align.CENTER);
      canvas.drawText("拖动框选区域，再点 ✓", getWidth() / 2f, getHeight() / 2f, paint);
      return;
    }
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Ui.dp(getContext(), 2));
    paint.setColor(0xffffffff);
    canvas.drawRect(selected, paint);
    paint.setStyle(Paint.Style.FILL);
    for (int corner = 0; corner < 4; corner++) {
      float x = (corner % 2 == 0) ? selected.left : selected.right,
          y = corner < 2 ? selected.top : selected.bottom;
      paint.setColor(0xffffffff);
      canvas.drawCircle(x, y, Ui.dp(getContext(), 7), paint);
      paint.setColor(Ui.ACCENT);
      canvas.drawCircle(x, y, Ui.dp(getContext(), 4), paint);
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    float x = Math.max(0, Math.min(getWidth(), event.getX())),
        y = Math.max(0, Math.min(getHeight(), event.getY()));
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        getParent().requestDisallowInterceptTouchEvent(true);
        downX = x;
        downY = y;
        start.set(selected);
        handle = -1;
        if (valid()) {
          for (int n = 0; n < 4; n++)
            if (Math.hypot(
                    x - (n % 2 == 0 ? selected.left : selected.right),
                    y - (n < 2 ? selected.top : selected.bottom))
                < Ui.dp(getContext(), 24)) handle = n;
          if (handle < 0 && selected.contains(x, y)) handle = 4;
        }
        if (handle < 0) selected.set(x, y, x, y);
        invalidate();
        return true;
      case MotionEvent.ACTION_MOVE:
      case MotionEvent.ACTION_UP:
        if (handle == 4) {
          selected.set(start);
          selected.offset(
              Math.max(-start.left, Math.min(getWidth() - start.right, x - downX)),
              Math.max(-start.top, Math.min(getHeight() - start.bottom, y - downY)));
        } else if (handle >= 0) {
          selected.set(start);
          if (handle % 2 == 0) selected.left = Math.min(x, start.right - 1);
          else selected.right = Math.max(x, start.left + 1);
          if (handle < 2) selected.top = Math.min(y, start.bottom - 1);
          else selected.bottom = Math.max(y, start.top + 1);
        } else
          selected.set(
              Math.min(downX, x), Math.min(downY, y), Math.max(downX, x), Math.max(downY, y));
        changed.accept(valid());
        invalidate();
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return true;
      case MotionEvent.ACTION_CANCEL:
        selected.set(start);
        changed.accept(valid());
        invalidate();
        return true;
      default:
        return true;
    }
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  protected void onDetachedFromWindow() {
    image.recycle();
    super.onDetachedFromWindow();
  }
}
