package app.iko.notes;

import android.content.Context;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import java.util.function.Consumer;

/** Inline visual cover chooser; edits use the editor's existing history/save boundary. */
final class CoverPickerView extends LinearLayout {
  private final NoteDocument note;
  private final Consumer<Runnable> edit;
  private final Runnable importImage;
  private int category = 0;

  CoverPickerView(
      Context context, NoteDocument note, Consumer<Runnable> edit, Runnable importImage) {
    super(context);
    this.note = note;
    this.edit = edit;
    this.importImage = importImage;
    category = CoverCatalog.group(note.coverPattern);
    setOrientation(VERTICAL);
    refresh();
  }

  private void commit(Runnable change) {
    edit.accept(change);
    refresh();
  }

  void refresh() {
    removeAllViews();
    Context c = getContext();
    TextView heading = Ui.label(c, "封面 · 原创文具", 15);
    addView(heading, new LayoutParams(-1, Ui.dp(c, 36)));
    LinearLayout tabs = Ui.row(c);
    for (int i = 0; i < CoverCatalog.GROUPS.length; i++) {
      final int group = i;
      TextView tab =
          Ui.button(
              c,
              CoverCatalog.GROUPS[i],
              category == i,
              () -> {
                category = group;
                refresh();
              });
      tab.setTextColor(category == i ? Ui.ACCENT : Ui.MUTED);
      tab.setBackground(Ui.bg(c, category == i ? Ui.SELECTED : Ui.FLOAT, 14));
      LayoutParams p = new LayoutParams(Ui.dp(c, 88), Ui.dp(c, 48));
      p.setMargins(0, 0, Ui.dp(c, 6), 0);
      tabs.addView(tab, p);
    }
    addView(tabs);
    HorizontalScrollView scroller = new HorizontalScrollView(c);
    scroller.setHorizontalScrollBarEnabled(false);
    LinearLayout samples = Ui.row(c);
    Ui.pad(samples, 0, 10);
    for (int i = 0; i < CoverCatalog.IDS.length; i++) {
      String id = CoverCatalog.IDS[i];
      if (CoverCatalog.group(id) != category) continue;
      final String selected = id;
      boolean active = id.equals(note.coverPattern) && note.coverImagePath.isEmpty();
      LinearLayout tile = Ui.column(c);
      Ui.pad(tile, 5, 5);
      tile.setBackground(
          Ui.border(c, active ? Ui.FLOAT : Ui.SURFACE, 12, active ? Ui.ACCENT : Ui.LINE));
      CoverArtView art = new CoverArtView(c, id, CoverCatalog.color(note.cover));
      tile.addView(art, new LayoutParams(Ui.dp(c, 78), Ui.dp(c, 104)));
      TextView name =
          Ui.text(
              c, (active ? "✓ " : "") + CoverCatalog.NAMES[i], 12, active ? Ui.ACCENT : Ui.TEXT);
      name.setGravity(Gravity.CENTER);
      tile.addView(name, new LayoutParams(-1, Ui.dp(c, 29)));
      tile.setContentDescription(CoverCatalog.NAMES[i] + "封面" + (active ? "，已选" : ""));
      tile.setOnClickListener(
          v ->
              commit(
                  () -> {
                    note.coverPattern = selected;
                    note.coverImagePath = "";
                  }));
      LayoutParams p = new LayoutParams(-2, -2);
      p.rightMargin = Ui.dp(c, 8);
      samples.addView(tile, p);
    }
    scroller.addView(samples);
    addView(scroller, new LayoutParams(-1, -2));
    addView(Ui.text(c, "柔和配色", 12, Ui.MUTED), new LayoutParams(-1, Ui.dp(c, 28)));
    HorizontalScrollView colorScroll = new HorizontalScrollView(c);
    colorScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout colors = Ui.row(c);
    for (int i = 0; i < CoverCatalog.COLORS.length; i++) {
      int value = CoverCatalog.COLORS[i];
      boolean active = CoverCatalog.color(note.cover) == value;
      TextView chip =
          Ui.text(c, (active ? "✓ " : "") + CoverCatalog.COLOR_NAMES[i], 12, 0xff29343d);
      chip.setGravity(Gravity.CENTER);
      chip.setBackground(Ui.border(c, value, 14, active ? Ui.ACCENT : value));
      chip.setContentDescription(CoverCatalog.COLOR_NAMES[i] + "配色" + (active ? "，已选" : ""));
      chip.setOnClickListener(
          v -> commit(() -> note.cover = String.format(Locale.ROOT, "#%06X", value & 0xffffff)));
      LayoutParams p = new LayoutParams(Ui.dp(c, 72), Ui.dp(c, 48));
      p.rightMargin = Ui.dp(c, 7);
      colors.addView(chip, p);
    }
    colorScroll.addView(colors);
    addView(colorScroll);
    TextView image =
        Ui.button(
            c, note.coverImagePath.isEmpty() ? "选择图片封面" : "更换图片封面 · 当前使用图片", false, importImage);
    LayoutParams imageParams = new LayoutParams(-1, Ui.dp(c, 48));
    imageParams.topMargin = Ui.dp(c, 12);
    addView(image, imageParams);
    if (!note.coverImagePath.isEmpty()) {
      TextView reset =
          Ui.button(c, "改用所选文具封面", false, () -> commit(() -> note.coverImagePath = ""));
      addView(reset, new LayoutParams(-1, Ui.dp(c, 48)));
    }
  }
}
