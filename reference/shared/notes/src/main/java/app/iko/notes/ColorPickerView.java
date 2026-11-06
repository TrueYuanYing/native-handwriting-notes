package app.iko.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

/**
 * Continuous HSV selection with explicit alpha and editable ARGB, without external dependencies.
 */
final class ColorPickerView extends LinearLayout {
  private final float[] hsv = new float[3];
  private int alpha;
  private boolean updating;
  private final EditText hex;
  private final TextView alphaLabel;
  private final View preview;
  private final SeekBar hue, opacity;
  private final Field field;
  private LinearLayout controls;
  private boolean wideLayout;

  ColorPickerView(Context context, int color, int[] recent) {
    super(context);
    setOrientation(VERTICAL);
    Color.colorToHSV(color, hsv);
    alpha = Color.alpha(color);
    LinearLayout sample = Ui.row(context);
    preview =
        new View(context) {
          private final Paint paint = new Paint();

          @Override
          protected void onDraw(Canvas canvas) {
            float size = Ui.dp(context, 8);
            for (int y = 0; y < getHeight() / size + 1; y++)
              for (int x = 0; x < getWidth() / size + 1; x++) {
                paint.setColor((x + y) % 2 == 0 ? 0xffd4d7df : 0xfff4f5f7);
                canvas.drawRect(x * size, y * size, (x + 1) * size, (y + 1) * size, paint);
              }
            paint.setColor(getColor());
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
          }
        };
    preview.setContentDescription("当前颜色与透明度预览");
    sample.addView(preview, new LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)));
    hex = new EditText(context);
    hex.setTextColor(Ui.TEXT);
    hex.setTextSize(15);
    hex.setSingleLine();
    hex.setHint("#RRGGBB / #AARRGGBB");
    hex.setHintTextColor(Ui.MUTED);
    hex.setContentDescription("HEX 颜色，支持 RRGGBB 或 AARRGGBB");
    LayoutParams hexParams = new LayoutParams(0, Ui.dp(context, 48), 1);
    hexParams.leftMargin = Ui.dp(context, 16);
    sample.addView(hex, hexParams);
    addView(sample, new LayoutParams(-1, Ui.dp(context, 56)));
    field = new Field(context);
    addView(field, new LayoutParams(-1, Ui.dp(context, 176)));
    addView(Ui.text(context, "色相", 12, Ui.MUTED), new LayoutParams(-1, Ui.dp(context, 32)));
    hue = new DragSlider(context);
    hue.setMax(3599);
    hue.setProgress(Math.round(hsv[0] * 10));
    hue.setContentDescription("连续色相");
    hue.setProgressDrawable(
        new android.graphics.drawable.Drawable() {
          private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);

          @Override
          public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            track.setShader(
                new LinearGradient(
                    bounds.left,
                    0,
                    bounds.right,
                    0,
                    new int[] {
                      Color.RED,
                      Color.YELLOW,
                      Color.GREEN,
                      Color.CYAN,
                      Color.BLUE,
                      Color.MAGENTA,
                      Color.RED
                    },
                    null,
                    Shader.TileMode.CLAMP));
            float thickness = Ui.dp(getContext(), 4);
            canvas.drawRoundRect(
                bounds.left,
                bounds.exactCenterY() - thickness,
                bounds.right,
                bounds.exactCenterY() + thickness,
                thickness,
                thickness,
                track);
          }

          @Override
          public void setAlpha(int alpha) {
            track.setAlpha(alpha);
          }

          @Override
          public void setColorFilter(android.graphics.ColorFilter filter) {
            track.setColorFilter(filter);
          }

          @Override
          public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
          }
        });
    hue.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
    addView(hue, new LayoutParams(-1, Ui.dp(context, 48)));
    alphaLabel = Ui.text(context, "", 12, Ui.MUTED);
    addView(alphaLabel, new LayoutParams(-1, Ui.dp(context, 28)));
    opacity = new DragSlider(context);
    opacity.setMax(255);
    opacity.setProgress(alpha);
    opacity.setContentDescription("墨水不透明度");
    opacity.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    addView(opacity, new LayoutParams(-1, Ui.dp(context, 48)));
    hue.setOnSeekBarChangeListener(
        listener(
            () -> {
              hsv[0] = hue.getProgress() / 10f;
              refresh();
            }));
    opacity.setOnSeekBarChangeListener(
        listener(
            () -> {
              alpha = opacity.getProgress();
              refresh();
            }));
    hex.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (updating) return;
            String value = s.toString().trim().replace("#", "");
            if (!value.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) return;
            int parsed = (int) Long.parseLong(value, 16);
            if (value.length() == 6) parsed |= 0xff000000;
            Color.colorToHSV(parsed, hsv);
            alpha = Color.alpha(parsed);
            refresh(false);
          }

          public void afterTextChanged(Editable e) {}
        });
    addView(Ui.text(context, "常用颜色", 12, Ui.MUTED), new LayoutParams(-1, Ui.dp(context, 32)));
    addSwatches(
        new int[] {
          0xff272d3b,
          0xff4568df,
          0xff238b75,
          0xffcc535d,
          0xffa76bd2,
          0xffedbb4a,
          0xff6ea9e9,
          0xff9ac8ad,
          0xffe6a8b7,
          0xffe3cb91,
          0xffbca6df,
          0xfffaf9f6
        });
    if (recent.length > 0) {
      addView(Ui.text(context, "最近使用", 12, Ui.MUTED), new LayoutParams(-1, Ui.dp(context, 32)));
      addSwatches(recent);
    }
    arrangeCompactControls();
    refresh();
  }

  private void arrangeCompactControls() {
    View sample = getChildAt(0);
    java.util.ArrayList<View> swatches = new java.util.ArrayList<>();
    for (int i = 0; i < getChildCount(); i++) {
      if (!(getChildAt(i) instanceof GridLayout)) continue;
      GridLayout grid = (GridLayout) getChildAt(i);
      while (grid.getChildCount() > 0) {
        View cell = grid.getChildAt(0);
        grid.removeView(cell);
        cell.setTooltipText(cell.getContentDescription());
        swatches.add(cell);
      }
    }
    removeAllViews();
    controls = Ui.column(getContext());
    controls.addView(sample, new LayoutParams(-1, Ui.dp(getContext(), 48)));
    LinearLayout sliders = Ui.row(getContext());
    LinearLayout hueGroup = Ui.row(getContext());
    hueGroup.addView(
        Ui.text(getContext(), "色相", 11, Ui.MUTED), new LayoutParams(Ui.dp(getContext(), 36), -1));
    hueGroup.addView(hue, new LayoutParams(0, -1, 1));
    sliders.addView(hueGroup, new LayoutParams(0, -1, 1));
    LinearLayout alphaGroup = Ui.row(getContext());
    alphaLabel.setTextSize(11);
    alphaLabel.setTooltipText("不透明度");
    alphaGroup.addView(alphaLabel, new LayoutParams(Ui.dp(getContext(), 44), -1));
    alphaGroup.addView(opacity, new LayoutParams(0, -1, 1));
    sliders.addView(alphaGroup, new LayoutParams(0, -1, 1));
    controls.addView(sliders, new LayoutParams(-1, Ui.dp(getContext(), 48)));
    LinearLayout colors = Ui.row(getContext());
    for (View cell : swatches)
      colors.addView(cell, new LayoutParams(Ui.dp(getContext(), 48), Ui.dp(getContext(), 48)));
    android.widget.HorizontalScrollView palette =
        new android.widget.HorizontalScrollView(getContext());
    palette.setHorizontalScrollBarEnabled(false);
    palette.setContentDescription("常用颜色与最近颜色，横向滑动查看更多");
    palette.addView(colors);
    controls.addView(palette, new LayoutParams(-1, Ui.dp(getContext(), 48)));
    addView(field, new LayoutParams(-1, Ui.dp(getContext(), 144)));
    addView(controls, new LayoutParams(-1, -2));
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
    boolean wide = available >= Ui.dp(getContext(), 560);
    if (wide != wideLayout) {
      wideLayout = wide;
      removeView(field);
      removeView(controls);
      setOrientation(wide ? HORIZONTAL : VERTICAL);
      addView(field, new LayoutParams(wide ? 0 : -1, Ui.dp(getContext(), 144), wide ? 1 : 0));
      addView(controls, new LayoutParams(-1, -2));
    }
    LayoutParams params = (LayoutParams) controls.getLayoutParams();
    params.width = wide ? Math.min(Ui.dp(getContext(), 360), available / 2) : -1;
    params.leftMargin = wide ? Ui.dp(getContext(), 12) : 0;
    super.onMeasure(widthSpec, heightSpec);
  }

  int getColor() {
    return Color.HSVToColor(alpha, hsv);
  }

  boolean isValidInput() {
    String value = hex.getText().toString().trim().replace("#", "");
    boolean valid = value.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}");
    if (!valid) hex.setError("请输入 6 位 RGB 或 8 位 ARGB");
    return valid;
  }

  private void addSwatches(int[] colors) {
    GridLayout grid = new GridLayout(getContext());
    grid.setColumnCount(6);
    for (int value : colors) {
      android.widget.FrameLayout cell = new android.widget.FrameLayout(getContext());
      View dot = new View(getContext());
      dot.setBackground(Ui.border(getContext(), value, 14, Ui.LINE));
      cell.addView(
          dot,
          new android.widget.FrameLayout.LayoutParams(
              Ui.dp(getContext(), 26), Ui.dp(getContext(), 26), Gravity.CENTER));
      cell.setContentDescription(String.format(Locale.ROOT, "选择颜色 #%08X", value));
      cell.setOnClickListener(
          v -> {
            Color.colorToHSV(value, hsv);
            alpha = Color.alpha(value);
            refresh();
          });
      grid.addView(
          cell,
          new android.view.ViewGroup.LayoutParams(
              Ui.dp(getContext(), 48), Ui.dp(getContext(), 48)));
    }
    addView(grid);
  }

  private void refresh() {
    refresh(true);
  }

  private void refresh(boolean replaceHex) {
    updating = true;
    if (replaceHex) hex.setText(String.format(Locale.ROOT, "#%08X", getColor()));
    hue.setProgress(Math.round(hsv[0] * 10));
    opacity.setProgress(alpha);
    alphaLabel.setText(Math.round(alpha * 100f / 255) + "%");
    alphaLabel.setContentDescription("不透明度 " + alphaLabel.getText());
    field.invalidate();
    preview.invalidate();
    updating = false;
  }

  static SeekBar.OnSeekBarChangeListener listener(Runnable change) {
    return new SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        if (fromUser) change.run();
      }

      public void onStartTrackingTouch(SeekBar bar) {}

      public void onStopTrackingTouch(SeekBar bar) {}
    };
  }

  private final class Field extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Field(Context context) {
      super(context);
      setContentDescription("饱和度与明度，水平调饱和度，垂直调明度");
    }

    @Override
    protected void onDraw(Canvas c) {
      float inset = Ui.dp(getContext(), 8), w = getWidth() - inset * 2, h = getHeight() - inset * 2;
      int save = c.save();
      c.translate(inset, inset);
      paint.setStyle(Paint.Style.FILL);
      paint.setShader(
          new LinearGradient(
              0,
              0,
              w,
              0,
              Color.WHITE,
              Color.HSVToColor(new float[] {hsv[0], 1, 1}),
              Shader.TileMode.CLAMP));
      c.drawRoundRect(0, 0, w, h, Ui.dp(getContext(), 8), Ui.dp(getContext(), 8), paint);
      paint.setShader(
          new LinearGradient(0, 0, 0, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
      c.drawRoundRect(0, 0, w, h, Ui.dp(getContext(), 8), Ui.dp(getContext(), 8), paint);
      paint.setShader(null);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(Ui.dp(getContext(), 3));
      paint.setColor(0x66000000);
      c.drawCircle(hsv[1] * w, (1 - hsv[2]) * h, Ui.dp(getContext(), 7), paint);
      paint.setStrokeWidth(Ui.dp(getContext(), 2));
      paint.setColor(Color.WHITE);
      c.drawCircle(hsv[1] * w, (1 - hsv[2]) * h, Ui.dp(getContext(), 6), paint);
      c.restoreToCount(save);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN
          || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
        getParent().requestDisallowInterceptTouchEvent(true);
        float inset = Ui.dp(getContext(), 8);
        hsv[1] = Math.max(0, Math.min(1, (event.getX() - inset) / (getWidth() - inset * 2)));
        hsv[2] = 1 - Math.max(0, Math.min(1, (event.getY() - inset) / (getHeight() - inset * 2)));
        refresh();
        return true;
      }
      if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
      return true;
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
