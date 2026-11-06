package app.iko.notes;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Title, naturally sized scrollable options, then completion; no stretched gaps between controls.
 */
final class InlineToolStrip extends LinearLayout {
  InlineToolStrip(Context context) {
    super(context);
    setOrientation(HORIZONTAL);
    setGravity(android.view.Gravity.CENTER_VERTICAL);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    if (getChildCount() == 3 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
      View title = getChildAt(0), options = getChildAt(1), done = getChildAt(2);
      LayoutParams left = (LayoutParams) title.getLayoutParams();
      LayoutParams right = (LayoutParams) done.getLayoutParams();
      int reserved =
          left.width
              + left.leftMargin
              + left.rightMargin
              + right.width
              + right.leftMargin
              + right.rightMargin
              + getPaddingLeft()
              + getPaddingRight();
      int available = Math.max(0, MeasureSpec.getSize(widthSpec) - reserved);
      options.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), heightSpec);
      LayoutParams params = (LayoutParams) options.getLayoutParams();
      params.width = options.getMeasuredWidth();
      params.weight = 0;
    }
    super.onMeasure(widthSpec, heightSpec);
  }
}
