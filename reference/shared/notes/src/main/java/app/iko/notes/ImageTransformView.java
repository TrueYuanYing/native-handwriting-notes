package app.iko.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Hardware-composited image preview, shared paper-space transforms, and cancellable edit session.
 */
final class ImageTransformView extends View {
  private final InkView ink;
  private final NoteDocument.Element element;
  private final Bitmap bitmap;
  private final Runnable close;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private NoteDocument.Element preview, start, committed;
  private float downX, downY, distance, angle;
  private int corner = -1;
  private boolean dragging, pinching, rotating, closed;

  ImageTransformView(
      Context context, InkView ink, NoteDocument.Element element, Bitmap bitmap, Runnable close) {
    super(context);
    this.ink = ink;
    this.element = element;
    this.bitmap = bitmap;
    this.close = close;
    preview = element.copy();
    committed = preview.copy();
    ink.beginObjectEditSession();
    // Preserve the existing discoverable label used by capture and import workflows.
    setContentDescription("图片移动与缩放");
  }

  public NoteDocument.Element getElement() {
    return element;
  }

  /** Axis-aligned screen bounds of the visible, rotated preview. */
  public RectF previewBounds() {
    ObjectTransform.Bounds bounds = ObjectTransform.bounds(screenElement(preview));
    return new RectF(bounds.left, bounds.top, bounds.right, bounds.bottom);
  }

  public void finish() {
    if (closed) return;
    dragging = pinching = rotating = false;
    commit();
    closed = true;
    ink.finishObjectEditSession();
    close.run();
  }

  /** Cancel only this editor session; pre-existing and just-imported assets remain on the page. */
  public void cancel() {
    if (closed) return;
    closed = true;
    dragging = pinching = rotating = false;
    ink.cancelObjectEditSession();
    close.run();
  }

  public void rotateBy(float degrees) {
    if (closed || !Float.isFinite(degrees)) return;
    NoteDocument.Element candidate = preview.copy();
    ObjectTransform.applyElement(
        preview,
        candidate,
        preview.x + preview.width / 2,
        preview.y + preview.height / 2,
        1,
        degrees,
        0,
        0);
    accept(candidate);
    commit();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (closed) return;
    NoteDocument.Element screen = screenElement(preview);
    RectF box = new RectF(screen.x, screen.y, screen.x + screen.width, screen.y + screen.height);
    int save = canvas.save();
    canvas.rotate(screen.rotation, box.centerX(), box.centerY());
    paint.setStyle(Paint.Style.FILL);
    canvas.drawBitmap(bitmap, null, box, paint);
    paint.setColor(Ui.ACCENT);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Ui.dp(getContext(), 2));
    canvas.drawRect(box, paint);
    float handleY = box.top - Ui.dp(getContext(), 36);
    canvas.drawLine(box.centerX(), box.top, box.centerX(), handleY, paint);
    paint.setStyle(Paint.Style.FILL);
    for (int n = 0; n < 4; n++)
      drawHandle(canvas, n == 0 || n == 3 ? box.left : box.right, n < 2 ? box.top : box.bottom);
    RotationGlyph.handle(canvas, box.centerX(), handleY, Ui.dp(getContext(), 12), Ui.ACCENT, paint);
    paint.setStyle(Paint.Style.FILL);
    canvas.restoreToCount(save);
  }

  private void drawHandle(Canvas canvas, float x, float y) {
    paint.setColor(0xffffffff);
    canvas.drawCircle(x, y, Ui.dp(getContext(), 10), paint);
    paint.setColor(Ui.ACCENT);
    canvas.drawCircle(x, y, Ui.dp(getContext(), 6), paint);
    paint.setColor(0xffffffff);
    canvas.drawCircle(x, y, Ui.dp(getContext(), 4), paint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (closed) return false;
    float x = event.getX(), y = event.getY();
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        NoteDocument.Element screen = screenElement(preview);
        float[] corners = ObjectTransform.corners(screen);
        float cx = screen.x + screen.width / 2, cy = screen.y + screen.height / 2;
        float handleX =
            ObjectTransform.mapX(
                cx, screen.y - Ui.dp(getContext(), 36), cx, cy, 1, screen.rotation, 0);
        float handleY =
            ObjectTransform.mapY(
                cx, screen.y - Ui.dp(getContext(), 36), cx, cy, 1, screen.rotation, 0);
        rotating = Math.hypot(x - handleX, y - handleY) <= Ui.dp(getContext(), 24);
        corner = -1;
        if (!rotating)
          for (int n = 0; n < 4; n++)
            if (Math.hypot(x - corners[n * 2], y - corners[n * 2 + 1]) <= Ui.dp(getContext(), 28))
              corner = n;
        if (!rotating && corner < 0 && !ObjectTransform.contains(screen, x, y)) {
          finish();
          return true;
        }
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        dragging = true;
        pinching = false;
        downX = x;
        downY = y;
        start = preview.copy();
        angle = degrees(x - cx, y - cy);
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
        if (dragging && event.getPointerCount() == 2) {
          start = preview.copy();
          distance = span(event);
          angle = pointerAngle(event);
          downX = (event.getX(0) + event.getX(1)) / 2;
          downY = (event.getY(0) + event.getY(1)) / 2;
          pinching = true;
          rotating = false;
        }
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!dragging) return true;
        NoteDocument.Element source = screenElement(start), candidate = source.copy();
        if (pinching && event.getPointerCount() >= 2) {
          ObjectTransform.applyElement(
              source,
              candidate,
              downX,
              downY,
              Math.max(.01f, span(event) / Math.max(1, distance)),
              ObjectTransform.normalizeRotation(pointerAngle(event) - angle),
              (event.getX(0) + event.getX(1)) / 2 - downX,
              (event.getY(0) + event.getY(1)) / 2 - downY);
        } else if (rotating) {
          float pivotX = source.x + source.width / 2, pivotY = source.y + source.height / 2;
          ObjectTransform.applyElement(
              source,
              candidate,
              pivotX,
              pivotY,
              1,
              ObjectTransform.normalizeRotation(degrees(x - pivotX, y - pivotY) - angle),
              0,
              0);
        } else if (corner >= 0) {
          float[] positions = ObjectTransform.corners(source);
          int opposite = (corner + 2) % 4;
          float anchorX = positions[opposite * 2], anchorY = positions[opposite * 2 + 1];
          float vx = downX - anchorX, vy = downY - anchorY;
          float factor = ((x - anchorX) * vx + (y - anchorY) * vy) / Math.max(1, vx * vx + vy * vy);
          ObjectTransform.applyElement(
              source, candidate, anchorX, anchorY, Math.max(.01f, factor), 0, 0, 0);
        } else {
          ObjectTransform.applyElement(source, candidate, 0, 0, 1, 0, x - downX, y - downY);
        }
        accept(paperElement(candidate));
        return true;
      case MotionEvent.ACTION_POINTER_UP:
        if (dragging && event.getPointerCount() == 2) {
          pinching = rotating = false;
          corner = -1;
          start = preview.copy();
          int remaining = event.getActionIndex() == 0 ? 1 : 0;
          downX = event.getX(remaining);
          downY = event.getY(remaining);
        }
        return true;
      case MotionEvent.ACTION_UP:
        if (dragging) {
          dragging = pinching = rotating = false;
          commit();
          performClick();
        }
        return true;
      case MotionEvent.ACTION_CANCEL:
        dragging = pinching = rotating = false;
        preview = committed.copy();
        invalidate();
        return true;
      default:
        return true;
    }
  }

  private NoteDocument.Element screenElement(NoteDocument.Element paper) {
    RectF rect = ink.imageScreenBounds(paper);
    NoteDocument.Element result = paper.copy();
    result.x = rect.left;
    result.y = rect.top;
    result.width = rect.width();
    result.height = rect.height();
    return result;
  }

  private NoteDocument.Element paperElement(NoteDocument.Element screen) {
    RectF rect =
        ink.screenToPaper(
            new RectF(screen.x, screen.y, screen.x + screen.width, screen.y + screen.height));
    NoteDocument.Element result = screen.copy();
    result.x = rect.left;
    result.y = rect.top;
    result.width = rect.width();
    result.height = rect.height();
    return result;
  }

  private void accept(NoteDocument.Element candidate) {
    NoteDocument.Page page = ink.getPage();
    if (page == null) return;
    if (!page.infinite) {
      ObjectTransform.Bounds bounds = ObjectTransform.bounds(candidate);
      float factor =
          Math.min(1, Math.min(page.width / bounds.width(), page.height / bounds.height()));
      if (factor < 1) {
        NoteDocument.Element resized = candidate.copy();
        ObjectTransform.applyElement(
            candidate,
            resized,
            candidate.x + candidate.width / 2,
            candidate.y + candidate.height / 2,
            factor,
            0,
            0,
            0);
        candidate = resized;
        bounds = ObjectTransform.bounds(candidate);
      }
      candidate.x += Math.max(-bounds.left, Math.min(0, page.width - bounds.right));
      candidate.y += Math.max(-bounds.top, Math.min(0, page.height - bounds.bottom));
    }
    if (!ObjectTransform.isValid(candidate, page)) return;
    preview = candidate;
    invalidate();
  }

  private void commit() {
    if (sameGeometry(preview, committed)) return;
    if (!ObjectTransform.isValid(preview, ink.getPage())) return;
    ink.mutate(
        () -> {
          element.x = preview.x;
          element.y = preview.y;
          element.width = preview.width;
          element.height = preview.height;
          element.rotation = preview.rotation;
        });
    committed = element.copy();
    preview = committed.copy();
    invalidate();
  }

  private static boolean sameGeometry(NoteDocument.Element a, NoteDocument.Element b) {
    return Math.abs(a.x - b.x) < .001f
        && Math.abs(a.y - b.y) < .001f
        && Math.abs(a.width - b.width) < .001f
        && Math.abs(a.height - b.height) < .001f
        && Math.abs(ObjectTransform.normalizeRotation(a.rotation - b.rotation)) < .001f;
  }

  private static float degrees(float x, float y) {
    return (float) Math.toDegrees(Math.atan2(y, x));
  }

  private static float span(MotionEvent event) {
    return (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
  }

  private static float pointerAngle(MotionEvent event) {
    return degrees(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  protected void onDetachedFromWindow() {
    if (!closed) ink.finishObjectEditSession();
    closed = true;
    if (!bitmap.isRecycled()) bitmap.recycle();
    super.onDetachedFromWindow();
  }
}
