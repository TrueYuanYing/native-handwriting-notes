package app.iko.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** A magnified viewport onto the original ink controller, with no separate document or history. */
public final class FocusWritingView extends View {
  private final InkView ink;
  private final RectF region = new RectF();
  private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
  private String pageId;
  private float pageWidth, pageHeight;
  private boolean autoAdvance, fingerDrawing;
  private boolean navigating, ignoreUntilDown;
  private int stylusPointer = -1, writingPointer = -1;
  private float lastNavX, lastNavY, lastNavDistance;
  private int navCount;

  public FocusWritingView(Context context, InkView ink) {
    super(context);
    if (ink == null) throw new IllegalArgumentException("书写窗需要原始纸面");
    this.ink = ink;
    setFocusable(true);
    setContentDescription("放大书写窗；手写笔书写，手指平移，双指缩放；原页点击可定位");
  }

  public void setAutoAdvance(boolean enabled) {
    autoAdvance = enabled;
    invalidate();
  }

  public void setFingerDrawing(boolean enabled) {
    if (fingerDrawing != enabled) stopInput();
    fingerDrawing = enabled;
    ink.setFingerDrawing(enabled);
  }

  /** Centers the viewport on a point selected on the original paper. */
  public void locate(float paperX, float paperY) {
    if (!Float.isFinite(paperX) || !Float.isFinite(paperY)) return;
    stopInput();
    if (!ensureRegion()) return;
    region.offsetTo(paperX - region.width() / 2, paperY - region.height() / 2);
    clampRegion();
    publishRegion();
  }

  public void nextLine() {
    moveLine(1);
  }

  public void previousLine() {
    moveLine(-1);
  }

  public void lineStart() {
    stopInput();
    if (!ensureRegion()) return;
    region.offsetTo(lineStartX(), region.top);
    clampRegion();
    publishRegion();
  }

  public void advanceRight() {
    stopInput();
    if (!ensureRegion()) return;
    advanceRegion();
  }

  public void refreshPage() {
    stopInput();
    if (ensureRegion()) publishRegion();
  }

  /** The caller receives a copy so focus changes always pass through page clamping. */
  public RectF getRegion() {
    ensureRegion();
    return new RectF(region);
  }

  private void moveLine(int direction) {
    stopInput();
    if (!ensureRegion()) return;
    region.offsetTo(lineStartX(), region.top + direction * region.height() * .75f);
    clampRegion();
    publishRegion();
  }

  private float lineStartX() {
    return Math.min(70, Math.max(0, pageWidth - region.width()));
  }

  private void advanceRegion() {
    float remaining = pageWidth - region.right;
    if (remaining > .5f) {
      region.offset(Math.min(region.width() * .7f, remaining), 0);
    } else {
      // At the final row, stop at the edge instead of silently writing over that row again.
      if (region.bottom >= pageHeight - .5f) return;
      region.offsetTo(lineStartX(), region.top + region.height() * .75f);
    }
    clampRegion();
    publishRegion();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    stopInput();
    if (w <= 0 || h <= 0 || !ensureRegion()) return;
    float centerX = region.centerX(), centerY = region.centerY();
    resizeRegion(region.width(), centerX, centerY);
    publishRegion();
  }

  private boolean ensureRegion() {
    NoteDocument.Page page = ink.getPage();
    if (page == null) {
      if (pageId != null) {
        stopInput();
        pageId = null;
        region.setEmpty();
        ink.setFocusRegion(null);
      }
      return false;
    }
    if (!page.id.equals(pageId)) {
      stopInput();
      pageId = page.id;
      pageWidth = page.width;
      pageHeight = page.height;
      resizeRegion(pageWidth * .5f, 0, 0);
      region.offsetTo(70, 100);
      clampRegion();
      publishRegion();
    } else if (pageWidth != page.width || pageHeight != page.height) {
      stopInput();
      pageWidth = page.width;
      pageHeight = page.height;
      resizeRegion(region.width(), region.centerX(), region.centerY());
      publishRegion();
    }
    return true;
  }

  private float aspect() {
    return getWidth() > 0 && getHeight() > 0 ? (float) getWidth() / getHeight() : 4f;
  }

  private void resizeRegion(float requestedWidth, float centerX, float centerY) {
    float aspect = aspect();
    float maximum = Math.min(pageWidth, pageHeight * aspect);
    float minimum = Math.min(maximum, Math.max(40, pageWidth * .12f));
    float width = Math.max(minimum, Math.min(maximum, requestedWidth));
    float height = width / aspect;
    region.set(
        centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2);
    clampRegion();
  }

  private void clampRegion() {
    float x = Math.max(0, Math.min(pageWidth - region.width(), region.left));
    float y = Math.max(0, Math.min(pageHeight - region.height(), region.top));
    region.offsetTo(x, y);
  }

  private void publishRegion() {
    ink.setFocusRegion(region);
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.drawColor(Ui.SURFACE);
    if (!ensureRegion()) return;
    ink.drawViewport(canvas, region, getWidth(), getHeight());
    guide.setStyle(Paint.Style.STROKE);
    guide.setStrokeWidth(getResources().getDisplayMetrics().density);
    guide.setColor((Ui.ACCENT & 0xffffff) | 0x66000000);
    canvas.drawRect(.5f, .5f, getWidth() - .5f, getHeight() - .5f, guide);
    if (autoAdvance) {
      guide.setColor((Ui.ACCENT & 0xffffff) | 0x30000000);
      canvas.drawLine(getWidth() * .9f, 0, getWidth() * .9f, getHeight(), guide);
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!ensureRegion() || getWidth() == 0 || getHeight() == 0) return false;
    int action = event.getActionMasked(), index = event.getActionIndex();
    if (action == MotionEvent.ACTION_DOWN) {
      stopInput();
      ignoreUntilDown = false;
      requestFocus();
      if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
      int type = event.getToolType(index);
      if (isStylus(type)) stylusPointer = event.getPointerId(index);
      if ("hand".equals(ink.getTool()) || (!isStylus(type) && !fingerDrawing)) {
        navigating = true;
        resetNavigation(event, -1);
      } else {
        writingPointer = event.getPointerId(index);
        forward(event);
      }
      return true;
    }
    if (action == MotionEvent.ACTION_CANCEL) {
      stopInput();
      return true;
    }
    if (ignoreUntilDown) return true;
    if (action == MotionEvent.ACTION_POINTER_DOWN) {
      int type = event.getToolType(index);
      if (isStylus(type)) {
        if (stylusPointer < 0) {
          ink.cancelInput();
          stylusPointer = event.getPointerId(index);
          writingPointer = stylusPointer;
          navigating = "hand".equals(ink.getTool());
          if (navigating) resetNavigation(event, -1);
          else forward(event);
        } else if (!navigating) {
          forward(event);
        }
      } else if (stylusPointer >= 0) {
        if (!navigating) forward(event);
      } else {
        // A second finger changes drawing into navigation, and rolls back the temporary stroke.
        ink.cancelInput();
        writingPointer = -1;
        navigating = true;
        resetNavigation(event, -1);
      }
      return true;
    }
    if (navigating) {
      if (action == MotionEvent.ACTION_MOVE) navigate(event);
      else if (action == MotionEvent.ACTION_POINTER_UP) {
        if (event.getPointerId(index) == stylusPointer) stopInput();
        else resetNavigation(event, index);
      } else if (action == MotionEvent.ACTION_UP) {
        stopInput();
        performClick();
      }
      return true;
    }
    if (writingPointer < 0) return true;
    boolean endsOwner =
        (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
            && event.getPointerId(index) == writingPointer;
    boolean advance =
        endsOwner
            && (event.getFlags() & MotionEvent.FLAG_CANCELED) == 0
            && autoAdvance
            && ink.isWritingGesture()
            && event.getX(index) >= getWidth() * .9f
            && event.getX(index) <= getWidth()
            && event.getY(index) >= 0
            && event.getY(index) <= getHeight();
    int count = ink.getPage().strokes.size();
    forward(event);
    if (endsOwner) {
      stylusPointer = writingPointer = -1;
      ignoreUntilDown = true;
      // Failed capacity validation or a canceled owner must not move the focus.
      if (advance && ink.getPage().strokes.size() > count) advanceRegion();
      performClick();
    }
    return true;
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent event) {
    int action = event.getActionMasked();
    if (writingPointer >= 0
        && (action == MotionEvent.ACTION_BUTTON_PRESS
            || action == MotionEvent.ACTION_BUTTON_RELEASE)) {
      forward(event);
      return true;
    }
    return super.onGenericMotionEvent(event);
  }

  private void forward(MotionEvent event) {
    ink.dispatchViewportTouch(event, region, getWidth(), getHeight());
    invalidate();
  }

  private static boolean isStylus(int type) {
    return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER;
  }

  /** Finds the contacts belonging to this pane's navigation, excluding a departing contact. */
  private int navigationCount(MotionEvent event, int excluded) {
    int count = 0;
    for (int i = 0; i < event.getPointerCount(); i++) {
      if (i == excluded) continue;
      if (stylusPointer >= 0 && event.getPointerId(i) != stylusPointer) continue;
      count++;
    }
    return count;
  }

  private void resetNavigation(MotionEvent event, int excluded) {
    navCount = navigationCount(event, excluded);
    if (navCount == 0) {
      lastNavDistance = 0;
      return;
    }
    float x = 0, y = 0;
    int first = -1, second = -1;
    for (int i = 0; i < event.getPointerCount(); i++) {
      if (i == excluded) continue;
      if (stylusPointer >= 0 && event.getPointerId(i) != stylusPointer) continue;
      x += event.getX(i);
      y += event.getY(i);
      if (first < 0) first = i;
      else if (second < 0) second = i;
    }
    lastNavX = x / navCount;
    lastNavY = y / navCount;
    lastNavDistance = second >= 0 ? distance(event, first, second) : 0;
  }

  private void navigate(MotionEvent event) {
    int count = navigationCount(event, -1);
    if (count == 0) return;
    if (count != navCount) {
      resetNavigation(event, -1);
      return;
    }
    float anchorX = region.left + lastNavX / getWidth() * region.width();
    float anchorY = region.top + lastNavY / getHeight() * region.height();
    float previousDistance = lastNavDistance;
    resetNavigation(event, -1);
    if (lastNavDistance > 0 && previousDistance > 0)
      resizeRegion(
          region.width() * previousDistance / lastNavDistance, region.centerX(), region.centerY());
    region.offsetTo(
        anchorX - lastNavX / getWidth() * region.width(),
        anchorY - lastNavY / getHeight() * region.height());
    clampRegion();
    publishRegion();
  }

  private static float distance(MotionEvent event, int first, int second) {
    return (float)
        Math.hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second));
  }

  private void stopInput() {
    ink.cancelInput();
    writingPointer = stylusPointer = -1;
    navigating = false;
    navCount = 0;
    lastNavDistance = 0;
    ignoreUntilDown = true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (isShown() && getWidth() > 0 && ensureRegion()) publishRegion();
  }

  @Override
  protected void onDetachedFromWindow() {
    stopInput();
    ink.setFocusRegion(null);
    super.onDetachedFromWindow();
  }
}
