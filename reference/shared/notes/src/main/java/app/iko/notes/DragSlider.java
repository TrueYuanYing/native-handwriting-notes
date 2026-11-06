package app.iko.notes;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;

/** A full-height drag target that keeps ownership inside scrolling tool panels. */
final class DragSlider extends SeekBar {
  private OnSeekBarChangeListener listener;
  private boolean dragging;
  private int startProgress;

  DragSlider(Context context) {
    super(context);
    setMinimumHeight(Ui.dp(context, 48));
    setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0);
    setMinHeight(Ui.dp(context, 6));
    setMaxHeight(Ui.dp(context, 6));
    android.graphics.drawable.GradientDrawable thumb =
        Ui.border(context, Ui.SURFACE, 12, Ui.ACCENT);
    thumb.setSize(Ui.dp(context, 24), Ui.dp(context, 24));
    setThumb(thumb);
    setThumbOffset(Ui.dp(context, 12));
    setSplitTrack(false);
    setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.LINE));
  }

  @Override
  public void setOnSeekBarChangeListener(OnSeekBarChangeListener listener) {
    this.listener = listener;
    super.setOnSeekBarChangeListener(
        new OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int progress, boolean user) {
            listener.onProgressChanged(bar, progress, user || dragging);
            if (user && !dragging) listener.onStopTrackingTouch(bar);
          }

          public void onStartTrackingTouch(SeekBar bar) {
            listener.onStartTrackingTouch(bar);
          }

          public void onStopTrackingTouch(SeekBar bar) {
            listener.onStopTrackingTouch(bar);
          }
        });
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!isEnabled()) return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        getParent().requestDisallowInterceptTouchEvent(true);
        dragging = true;
        startProgress = getProgress();
        setPressed(true);
        if (listener != null) listener.onStartTrackingTouch(this);
        track(event.getX());
        return true;
      case MotionEvent.ACTION_MOVE:
        if (dragging) track(event.getX());
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (dragging) {
          if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) setProgress(startProgress);
          else track(event.getX());
          dragging = false;
          setPressed(false);
          if (listener != null) listener.onStopTrackingTouch(this);
          getParent().requestDisallowInterceptTouchEvent(false);
          if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        }
        return true;
      default:
        return true;
    }
  }

  private void track(float x) {
    float fraction =
        (x - getPaddingLeft()) / Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
    fraction = Math.max(0, Math.min(1, fraction));
    if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) fraction = 1 - fraction;
    setProgress(getMin() + Math.round(fraction * (getMax() - getMin())));
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
