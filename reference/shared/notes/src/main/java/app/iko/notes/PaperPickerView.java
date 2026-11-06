package app.iko.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Compact, scrollable paper controls. All mutations go through the editor history. */
public final class PaperPickerView extends ScrollView {
  private final NoteDocument.Page page;
  private final Consumer<Runnable> edit;
  private final LinearLayout body, templates;
  private String category = "课堂";
  private final ArrayList<Runnable> selectionUpdates = new ArrayList<>();
  private boolean landscape;

  public PaperPickerView(
      Context c,
      NoteDocument.Page page,
      Consumer<Runnable> edit,
      Runnable importPdf,
      Runnable importImage) {
    super(c);
    this.page = page;
    this.edit = edit;
    landscape = page.width > page.height;
    setFillViewport(false);
    body = Ui.column(c);
    Ui.pad(body, 8, 4);
    addView(body);
    if (!page.backgroundPath.isEmpty())
      body.addView(Ui.text(c, "课件背景保持原样；新增空白页可直接使用所选纸型。", 11, Ui.MUTED));
    for (String[] t : PaperCatalog.TEMPLATES) if (t[0].equals(page.paper)) category = t[2];
    LinearLayout categories = strip();
    for (String name : PaperCatalog.CATEGORIES) {
      TextView button =
          chip(
              categories,
              name,
              () -> {
                category = name;
                showTemplates();
                updateSelection();
              });
      selectionUpdates.add(() -> mark(button, name.equals(category)));
    }
    templates = strip();
    showTemplates();
    LinearLayout colors = strip();
    chip(colors, "纸色", null);
    for (int color : PaperCatalog.COLORS) {
      TextView swatch = chip(colors, "●", () -> change(() -> page.color = color));
      swatch.setTextColor(color);
      swatch.setBackground(Ui.border(c, Ui.FLOAT, 10, Ui.LINE));
      swatch.setContentDescription("纸色 " + Integer.toHexString(color));
      selectionUpdates.add(
          () -> {
            mark(swatch, page.color == color);
            swatch.setTextColor(color);
          });
    }
    LinearLayout textures = strip();
    chip(textures, "材质", null);
    for (int i = 0; i < PaperCatalog.TEXTURES.length; i++) {
      final String texture = PaperCatalog.TEXTURES[i];
      LinearLayout item = Ui.column(c);
      NoteDocument.Page sample = PaperCatalog.newPageLike(page);
      sample.texture = texture;
      item.addView(
          new Preview(c, sample), new LinearLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 32)));
      TextView label = Ui.text(c, PaperCatalog.TEXTURE_LABELS[i], 11, Ui.TEXT);
      item.addView(label);
      Ui.pad(item, 6, 2);
      item.setOnClickListener(v -> change(() -> page.texture = texture));
      item.setContentDescription(PaperCatalog.TEXTURE_LABELS[i] + "材质");
      textures.addView(item);
      item.setMinimumHeight(Ui.dp(c, 48));
      item.setMinimumWidth(Ui.dp(c, 54));
      selectionUpdates.add(
          () -> {
            item.setSelected(texture.equals(page.texture));
            item.setBackground(
                Ui.border(c, Ui.SURFACE, 9, texture.equals(page.texture) ? Ui.ACCENT : Ui.LINE));
            sample.color = page.color;
            item.getChildAt(0).invalidate();
          });
    }
    LinearLayout spacing = Ui.row(c);
    body.addView(spacing);
    TextView value =
        Ui.text(c, page.lineSpacing == 0 ? "行距：模板默认" : "行距：" + (int) page.lineSpacing, 12, Ui.TEXT);
    spacing.addView(value, new LinearLayout.LayoutParams(Ui.dp(c, 130), Ui.dp(c, 48)));
    SeekBar slider = new DragSlider(c);
    slider.setMax(108);
    slider.setProgress(page.lineSpacing > 0 ? (int) page.lineSpacing - 12 : 36);
    slider.setContentDescription("行距，12至120纸张单位");
    spacing.addView(slider, new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1));
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int progress, boolean user) {
            if (user) value.setText("行距：" + (progress + 12));
          }

          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {
            change(() -> page.lineSpacing = bar.getProgress() + 12);
          }
        });
    chip(
        spacing,
        "默认",
        () -> {
          change(() -> page.lineSpacing = 0);
          value.setText("行距：模板默认");
        });
    boolean locked = page.infinite || PaperCatalog.hasContent(page);
    TextView hint =
        Ui.text(
            c,
            page.infinite
                ? "无边记没有固定纸张尺寸，背景随视口延伸"
                : locked ? "已有内容，尺寸与方向已锁定，保护笔迹及资料" : "尺寸与方向 · 仅空白页可调整",
            11,
            Ui.MUTED);
    body.addView(hint);
    LinearLayout sizes = strip();
    for (int i = 0; i < PaperCatalog.SIZES.length; i++) {
      final int index = i;
      TextView button =
          chip(
              sizes,
              PaperCatalog.SIZES[i],
              () -> change(() -> PaperCatalog.applySize(page, index, landscape)));
      button.setEnabled(!locked);
      button.setAlpha(locked ? .45f : 1);
      selectionUpdates.add(
          () -> {
            NoteDocument.Page sample = new NoteDocument.Page();
            PaperCatalog.applySize(sample, index, page.width > page.height);
            mark(
                button,
                Math.abs(sample.width - page.width) < 2
                    && Math.abs(sample.height - page.height) < 2);
          });
    }
    LinearLayout options = strip();
    for (boolean horizontal : new boolean[] {false, true}) {
      TextView button =
          chip(
              options,
              horizontal ? "▭ 横向" : "▯ 竖向",
              () -> {
                landscape = horizontal;
                change(
                    () -> {
                      if (!PaperCatalog.hasContent(page)) {
                        float a = Math.min(page.width, page.height),
                            b = Math.max(page.width, page.height);
                        page.width = horizontal ? b : a;
                        page.height = horizontal ? a : b;
                      }
                    });
              });
      button.setEnabled(!locked);
      button.setAlpha(locked ? .45f : 1);
      selectionUpdates.add(() -> mark(button, horizontal == landscape));
    }
    chip(options, "导入 PDF", importPdf);
    chip(options, "导入图片", importImage);
    updateSelection();
  }

  private void mark(TextView button, boolean selected) {
    button.setSelected(selected);
    button.setBackground(Ui.border(getContext(), Ui.FLOAT, 10, selected ? Ui.ACCENT : Ui.LINE));
    button.setTextColor(selected ? Ui.ACCENT : Ui.TEXT);
  }

  private void updateSelection() {
    for (Runnable update : selectionUpdates) update.run();
  }

  private void change(Runnable mutation) {
    edit.accept(mutation);
    showTemplates();
    updateSelection();
  }

  private LinearLayout strip() {
    HorizontalScrollView scroll = new HorizontalScrollView(getContext());
    scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout row = Ui.row(getContext());
    scroll.addView(row);
    body.addView(scroll);
    return row;
  }

  private TextView chip(LinearLayout row, String text, Runnable action) {
    TextView v = Ui.text(getContext(), text, 12, Ui.TEXT);
    Ui.pad(v, 10, 5);
    v.setMinHeight(Ui.dp(getContext(), 48));
    v.setMinWidth(Ui.dp(getContext(), 48));
    v.setGravity(Gravity.CENTER);
    v.setBackground(Ui.bg(getContext(), Ui.FLOAT, 10));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.setMargins(0, 2, Ui.dp(getContext(), 5), 2);
    row.addView(v, lp);
    if (action != null) v.setOnClickListener(w -> action.run());
    return v;
  }

  private void showTemplates() {
    templates.removeAllViews();
    for (String[] t : PaperCatalog.TEMPLATES)
      if (category.equals(t[2])) {
        NoteDocument.Page sample = PaperCatalog.newPageLike(page);
        sample.paper = t[0];
        LinearLayout item = Ui.column(getContext());
        Ui.pad(item, 5, 3);
        item.setBackground(
            Ui.border(getContext(), Ui.SURFACE, 9, t[0].equals(page.paper) ? Ui.ACCENT : Ui.LINE));
        item.addView(
            new Preview(getContext(), sample),
            new LinearLayout.LayoutParams(Ui.dp(getContext(), 51), Ui.dp(getContext(), 58)));
        TextView label = Ui.text(getContext(), t[1], 11, Ui.TEXT);
        label.setGravity(Gravity.CENTER);
        item.addView(label);
        item.setContentDescription(t[1] + "纸张模板" + (t[0].equals(page.paper) ? "，已选择" : ""));
        item.setOnClickListener(v -> change(() -> PaperCatalog.applyTemplate(page, t[0])));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 3, Ui.dp(getContext(), 6), 3);
        templates.addView(item, lp);
      }
  }

  private static final class Preview extends View {
    final NoteDocument.Page sample;

    Preview(Context c, NoteDocument.Page p) {
      super(c);
      sample = p;
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      int save = canvas.save();
      float scale = Math.min(getWidth() / sample.width, getHeight() / sample.height);
      canvas.translate(
          (getWidth() - sample.width * scale) / 2, (getHeight() - sample.height * scale) / 2);
      canvas.scale(scale, scale);
      PageRenderer.drawPaper(canvas, sample, Math.max(1, .65f / scale));
      canvas.restoreToCount(save);
    }
  }
}
