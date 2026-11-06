package app.iko.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.BiConsumer;

/** Visual theme previews; selecting a theme never edits notebook paper or ink. */
final class AppearanceView extends LinearLayout {
  AppearanceView(
      Context c,
      String mode,
      String palette,
      String toolbarStyle,
      BiConsumer<String, String> choose,
      java.util.function.Consumer<String> chooseToolbar) {
    super(c);
    setOrientation(VERTICAL);
    LinearLayout modes = Ui.row(c);
    String[] ids = {"light", "dark", "system"}, names = {"明亮白", "柔和黑", "跟随系统"};
    for (int i = 0; i < ids.length; i++) {
      final String id = ids[i];
      LinearLayout tile = Ui.column(c);
      Ui.pad(tile, 8, 8);
      boolean selected = id.equals(mode);
      tile.setBackground(
          Ui.border(c, selected ? Ui.SELECTED : Ui.FLOAT, 18, selected ? Ui.ACCENT : Ui.LINE));
      tile.addView(
          new Sample(c, i, ThemePalette.index(palette)), new LayoutParams(-1, Ui.dp(c, 46)));
      TextView label = Ui.label(c, names[i], 12);
      label.setGravity(Gravity.CENTER);
      tile.addView(label, new LayoutParams(-1, Ui.dp(c, 28)));
      tile.setContentDescription("主题：" + names[i]);
      tile.setSelected(selected);
      tile.setOnClickListener(v -> choose.accept(id, palette));
      Ui.press(tile);
      LayoutParams params = new LayoutParams(0, -2, 1);
      params.setMargins(0, 0, Ui.dp(c, 8), 0);
      modes.addView(tile, params);
    }
    addView(modes);
    TextView heading = Ui.text(c, "IKO 配色", 12, Ui.MUTED);
    addView(heading, new LayoutParams(-1, Ui.dp(c, 32)));
    HorizontalScrollView scroll = new HorizontalScrollView(c);
    scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout colors = Ui.row(c);
    for (int i = 0; i < ThemePalette.IDS.length; i++) {
      final String id = ThemePalette.IDS[i];
      TextView swatch =
          Ui.button(c, "●  " + ThemePalette.NAMES[i], false, () -> choose.accept(mode, id));
      swatch.setTextColor((Ui.dark ? ThemePalette.DARK : ThemePalette.LIGHT)[i]);
      swatch.setBackground(
          Ui.border(
              c,
              id.equals(palette) ? Ui.SELECTED : Ui.FLOAT,
              15,
              id.equals(palette) ? Ui.ACCENT : Ui.LINE));
      swatch.setContentDescription("配色：" + ThemePalette.NAMES[i]);
      swatch.setSelected(id.equals(palette));
      LayoutParams params = new LayoutParams(Ui.dp(c, 104), Ui.dp(c, 48));
      params.rightMargin = Ui.dp(c, 8);
      colors.addView(swatch, params);
    }
    scroll.addView(colors);
    addView(scroll);
    addView(Ui.text(c, "标题栏样式", 12, Ui.MUTED), new LayoutParams(-1, Ui.dp(c, 30)));
    LinearLayout styles = Ui.row(c);
    String[] styleIds = {"tint", "clean", "solid"}, styleNames = {"柔和", "纯净", "主题色"};
    for (int n = 0; n < styleIds.length; n++) {
      final String id = styleIds[n];
      LinearLayout tile = Ui.column(c);
      Ui.pad(tile, 8, 6);
      boolean active = id.equals(toolbarStyle);
      tile.setBackground(
          Ui.border(c, active ? Ui.SELECTED : Ui.FLOAT, 12, active ? Ui.ACCENT : Ui.LINE));
      int accent = ThemePalette.LIGHT[ThemePalette.index(palette)];
      TextView miniature =
          Ui.text(c, "‹   IKO    ◇   ○   ⋯", 13, id.equals("solid") ? 0xffffffff : Ui.TEXT);
      miniature.setGravity(Gravity.CENTER);
      miniature.setBackground(
          Ui.bg(
              c,
              id.equals("solid")
                  ? accent
                  : id.equals("clean") ? Ui.SURFACE : Ui.mix(Ui.ACCENT, Ui.SURFACE, .15f),
              7));
      tile.addView(miniature, new LayoutParams(-1, Ui.dp(c, 28)));
      TextView label = Ui.text(c, styleNames[n], 11, active ? Ui.ACCENT : Ui.MUTED);
      label.setGravity(Gravity.CENTER);
      tile.addView(label, new LayoutParams(-1, Ui.dp(c, 22)));
      tile.setSelected(active);
      tile.setContentDescription("标题栏样式：" + styleNames[n]);
      tile.setOnClickListener(v -> chooseToolbar.accept(id));
      LayoutParams p = new LayoutParams(0, Ui.dp(c, 68), 1);
      p.rightMargin = Ui.dp(c, 8);
      styles.addView(tile, p);
    }
    addView(styles);
  }

  private static final class Sample extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final int mode, palette;

    Sample(Context c, int mode, int palette) {
      super(c);
      this.mode = mode;
      this.palette = palette;
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth(), h = getHeight(), r = Ui.dp(getContext(), 9);
      for (int half = 0; half < (mode == 2 ? 2 : 1); half++) {
        boolean dark = mode == 1 || mode == 2 && half == 1;
        int save = canvas.save();
        if (mode == 2) canvas.clipRect(half * w / 2, 0, (half + 1) * w / 2, h);
        p.setColor(dark ? 0xff20232b : 0xfff8f9fd);
        canvas.drawRoundRect(0, 0, w, h, r, r, p);
        p.setColor((dark ? ThemePalette.DARK : ThemePalette.LIGHT)[palette]);
        canvas.drawRoundRect(w * .08f, h * .13f, w * .15f, h * .4f, r / 3, r / 3, p);
        canvas.drawRoundRect(w * .22f, h * .16f, w * .62f, h * .22f, r / 3, r / 3, p);
        p.setColor(dark ? 0xff11151c : 0xffe5e9f4);
        canvas.drawRoundRect(w * .08f, h * .49f, w * .92f, h * .9f, r / 2, r / 2, p);
        p.setColor(dark ? 0xffaab3c5 : 0xff8c98ad);
        for (int i = 0; i < 3; i++)
          canvas.drawRoundRect(
              w * .15f,
              h * (.59f + i * .1f),
              w * (.7f - i * .08f),
              h * (.62f + i * .1f),
              r / 4,
              r / 4,
              p);
        canvas.restoreToCount(save);
      }
    }
  }
}
