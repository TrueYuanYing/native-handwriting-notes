package app.iko.notes;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/** Inline controls wrap short content and scroll only when the actual viewport requires it. */
final class ToolPanelScrollView extends ScrollView {
  ToolPanelScrollView(Context context) {
    super(context);
    setFillViewport(false);
    setClipToPadding(false);
    setVerticalScrollBarEnabled(false);
    setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int heightDp = getResources().getConfiguration().screenHeightDp;
    int cap = Ui.dp(getContext(), Math.max(96, Math.min(176, heightDp / 3)));
    if (getChildCount() > 0 && getChildAt(0) instanceof AppearanceView)
      cap = Ui.dp(getContext(), Math.max(120, Math.min(280, heightDp / 2)));
    if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
      cap = Math.min(cap, MeasureSpec.getSize(heightSpec));
    if (getChildCount() > 0 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
      View child = getChildAt(0);
      FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
      params.width =
          Math.min(
              MeasureSpec.getSize(widthSpec),
              Ui.dp(getContext(), "compact-options".equals(child.getTag()) ? 600 : 880));
      params.gravity = Gravity.TOP | Gravity.START;
    }
    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
  }
}
