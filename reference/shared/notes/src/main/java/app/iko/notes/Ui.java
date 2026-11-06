package app.iko.notes;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
  static int BG, SURFACE, FLOAT, ACCENT, TEXT, MUTED, LINE, SELECTED, WORKSPACE, ON_ACCENT, DANGER;
  static int CHROME, TOOLBAR, PANEL, CHROME_TEXT, CHROME_MUTED;
  static int ACTION, ON_ACTION;
  static boolean dark;
  static final PathInterpolator EASE = new PathInterpolator(.2f, .8f, .2f, 1f);

  static {
    configure(false, "blue");
  }

  static void configure(boolean night, String color) {
    configure(night, color, "tint");
  }

  static void configure(boolean night, String color, String toolbarStyle) {
    dark = night;
    BG = night ? 0xff15171c : 0xfff5f6fb;
    SURFACE = night ? 0xff20232b : 0xffffffff;
    FLOAT = night ? 0xff2a2e38 : 0xffedeff6;
    TEXT = night ? 0xfff2f3f8 : 0xff252a39;
    MUTED = night ? 0xffa6afc2 : 0xff657085;
    LINE = night ? 0xff343b48 : 0xffdfe3ed;
    WORKSPACE = night ? 0xff101217 : 0xffe6e9f1;
    ACCENT = (night ? ThemePalette.DARK : ThemePalette.LIGHT)[ThemePalette.index(color)];
    SELECTED = mix(ACCENT, SURFACE, night ? .19f : .12f);
    ON_ACCENT = night ? 0xff19202e : 0xffffffff;
    DANGER = night ? 0xffffb3b7 : 0xffaf3548;
    boolean solid = toolbarStyle.equals("solid"), clean = toolbarStyle.equals("clean");
    CHROME =
        solid
            ? ThemePalette.LIGHT[ThemePalette.index(color)]
            : clean ? BG : mix(ACCENT, BG, night ? .16f : .1f);
    TOOLBAR = clean ? SURFACE : mix(ACCENT, SURFACE, night ? .11f : .055f);
    PANEL = clean ? SURFACE : mix(ACCENT, SURFACE, night ? .055f : .025f);
    CHROME_TEXT = solid ? Color.WHITE : TEXT;
    CHROME_MUTED = solid ? 0xffe9edf9 : MUTED;
    ACTION=ACCENT;ON_ACTION=ON_ACCENT;
  }

  static void configureHost(org.json.JSONObject style) {
    dark=style.optBoolean("dark",dark);
    BG=style.optInt("background",BG);SURFACE=style.optInt("surface",SURFACE);
    FLOAT=style.optInt("fill",FLOAT);WORKSPACE=FLOAT;
    TEXT=style.optInt("text",TEXT);MUTED=style.optInt("muted",MUTED);LINE=style.optInt("line",LINE);
    ACCENT=style.optInt("selection",ACCENT);ON_ACCENT=style.optInt("onSelection",ON_ACCENT);
    ACTION=style.optInt("action",ACCENT);ON_ACTION=style.optInt("onAction",ON_ACCENT);
    DANGER=style.optInt("danger",DANGER);SELECTED=mix(ACCENT,SURFACE,dark?.19f:.12f);
    CHROME=BG;TOOLBAR=SURFACE;PANEL=SURFACE;CHROME_TEXT=TEXT;CHROME_MUTED=MUTED;
  }

  static int mix(int color, int base, float amount) {
    return Color.rgb(
        Math.round(Color.red(color) * amount + Color.red(base) * (1 - amount)),
        Math.round(Color.green(color) * amount + Color.green(base) * (1 - amount)),
        Math.round(Color.blue(color) * amount + Color.blue(base) * (1 - amount)));
  }

  static int dp(Context c, float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  static GradientDrawable bg(Context c, int color, float radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  static GradientDrawable border(Context c, int color, float radius, int stroke) {
    GradientDrawable d = bg(c, color, radius);
    d.setStroke(dp(c, 1), stroke);
    return d;
  }

  static TextView text(Context c, String text, float size, int color) {
    TextView v = new TextView(c);
    v.setText(text);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setGravity(Gravity.CENTER_VERTICAL);
    v.setFontFeatureSettings("kern");
    return v;
  }

  static TextView label(Context c, String text, float size) {
    TextView v = text(c, text, size, TEXT);
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return v;
  }

  static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  static LinearLayout column(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  static void pad(View v, float h, float vertical) {
    v.setPadding(
        dp(v.getContext(), h),
        dp(v.getContext(), vertical),
        dp(v.getContext(), h),
        dp(v.getContext(), vertical));
  }

  static TextView button(Context c, String label, boolean primary, Runnable action) {
    TextView v = label(c, label, 14);
    v.setGravity(Gravity.CENTER);
    v.setMinHeight(dp(c, 48));
    pad(v, 18, 8);
    v.setTextColor(primary ? ON_ACTION : TEXT);
    v.setBackground(bg(c, primary ? ACTION : FLOAT, 16));
    v.setContentDescription(label);
    v.setOnClickListener(w -> action.run());
    press(v);
    return v;
  }

  static void animateIn(View v) {
    v.animate().cancel();
    if (!ValueAnimator.areAnimatorsEnabled()) {
      v.setAlpha(1);
      v.setTranslationY(0);
      return;
    }
    v.setAlpha(0);
    v.setTranslationY(dp(v.getContext(), 9));
    v.animate().alpha(1).translationY(0).setDuration(240).setInterpolator(EASE).start();
  }

  static void press(View view) {
    view.setOnTouchListener(
        (v, event) -> {
          if (!ValueAnimator.areAnimatorsEnabled()) return false;
          int action = event.getActionMasked();
          if (action == MotionEvent.ACTION_DOWN)
            v.animate().scaleX(.96f).scaleY(.96f).setDuration(90).setInterpolator(EASE).start();
          else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            v.animate().scaleX(1).scaleY(1).setDuration(190).setInterpolator(EASE).start();
          return false;
        });
  }

  static void tintControls(View view) {
    android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(ACCENT);
    if (view instanceof android.widget.SeekBar) {
      ((android.widget.SeekBar) view).setProgressTintList(tint);
      ((android.widget.SeekBar) view).setThumbTintList(tint);
    } else if (view instanceof android.widget.Switch) {
      ((android.widget.Switch) view)
          .setThumbTintList(
              new android.content.res.ColorStateList(
                  new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                  new int[] {ACCENT, MUTED}));
      ((android.widget.Switch) view)
          .setTrackTintList(
              new android.content.res.ColorStateList(
                  new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                  new int[] {SELECTED, LINE}));
    }
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) tintControls(group.getChildAt(i));
    }
  }
}
