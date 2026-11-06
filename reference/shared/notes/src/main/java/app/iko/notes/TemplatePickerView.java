package app.iko.notes;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Actual PDF/image previews, with per-page selection. No copyrighted artwork ships in the APK. */
public final class TemplatePickerView extends ScrollView {
  public interface Callback {
    void onSelected(TemplateLibrary.Entry entry, int pageIndex);

    void onNotebookSelected(TemplateLibrary.Entry entry);

    void onImportRequested();
  }

  private final Callback callback;
  private static final int PAGE_SIZE = 12;
  private final LinearLayout body;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ArrayList<Bitmap> bitmaps = new ArrayList<>();
  private List<TemplateLibrary.Entry> entries = new ArrayList<>();
  private ExecutorService executor = Executors.newFixedThreadPool(2);
  private volatile int generation;
  private int columns = 1;
  private int galleryPage, detailPage;
  private TemplateLibrary.Entry detailEntry;
  private String filter = "全部";
  private volatile boolean detached;
  private boolean loading;
  private boolean detailVisible;

  public TemplatePickerView(Context context, Callback callback) {
    super(context);
    this.callback = callback;
    setFillViewport(false);
    setClipToPadding(false);
    body = Ui.column(context);
    Ui.pad(body, 8, 8);
    addView(body, new ScrollView.LayoutParams(-1, -2));
    refresh();
  }

  /** Call after saveImported completes; reads the persistent personal library again. */
  public void refresh() {
    int request = ++generation;
    loading = true;
    detailVisible = false;
    body.removeAllViews();
    releaseGalleryBitmaps();
    body.addView(Ui.text(getContext(), "正在打开模板库…", 13, Ui.MUTED));
    work(
        () -> {
          try {
            List<TemplateLibrary.Entry> loaded = TemplateLibrary.list(getContext());
            main.post(
                () -> {
                  if (detached || generation != request) return;
                  loading = false;
                  entries = loaded;
                  renderGallery();
                });
          } catch (IOException error) {
            main.post(
                () -> {
                  if (detached || generation != request) return;
                  loading = false;
                  body.removeAllViews();
                  body.addView(Ui.text(getContext(), error.getMessage(), 13, Ui.DANGER));
                  body.addView(button(getContext(), "导入我的模板", true, callback::onImportRequested));
                  body.addView(button(getContext(), "重新打开", false, this::refresh));
                });
          }
        });
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    int next = width >= Ui.dp(getContext(), 680) ? 3 : width >= Ui.dp(getContext(), 440) ? 2 : 1;
    if (columns != next) {
      columns = next;
      if (!entries.isEmpty() && !loading && !detailVisible) main.post(this::renderGallery);
      else if (detailVisible && detailEntry != null) {
        TemplateLibrary.Entry entry = detailEntry;
        int request = generation;
        main.post(
            () -> {
              if (!detached && generation == request && detailVisible && detailEntry == entry)
                showDetail(entry, detailPage);
            });
      }
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (detached) {
      detached = false;
      executor = Executors.newFixedThreadPool(2);
      if (detailVisible && detailEntry != null) showDetail(detailEntry, detailPage);
      else refresh();
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    detached = true;
    generation++;
    executor.shutdownNow();
    body.removeAllViews();
    releaseGalleryBitmaps();
    super.onDetachedFromWindow();
  }

  private void renderGallery() {
    if (detached) return;
    detailVisible = false;
    detailEntry = null;
    int request = ++generation;
    Context context = getContext();
    body.removeAllViews();
    releaseGalleryBitmaps();
    TextView description = Ui.text(context, "收藏喜欢的纸页，或整本导入手账。", 12, Ui.MUTED);
    description.setMaxLines(2);
    TextView add = button(context, "＋ 导入模板", true, callback::onImportRequested);
    add.setContentDescription("导入 PDF / 图片模板");
    LinearLayout importRow = Ui.row(context);
    importRow.addView(description, new LinearLayout.LayoutParams(0, Ui.dp(context, 44), 1));
    importRow.addView(add, new LinearLayout.LayoutParams(Ui.dp(context, 112), Ui.dp(context, 44)));
    body.addView(importRow);
    android.widget.HorizontalScrollView tabsScroll =
        new android.widget.HorizontalScrollView(context);
    tabsScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout tabs = Ui.row(context);
    for (String category : new String[] {"全部", "手账", "学习", "计划", "读书", "复盘", "我的模板"}) {
      TextView tab =
          button(
              context,
              category,
              category.equals(filter),
              () -> {
                filter = category;
                galleryPage = 0;
                renderGallery();
              });
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, Ui.dp(context, 44));
      params.rightMargin = Ui.dp(context, 5);
      params.topMargin = Ui.dp(context, 8);
      params.bottomMargin = Ui.dp(context, 8);
      tabs.addView(tab, params);
    }
    tabsScroll.addView(tabs);
    body.addView(tabsScroll);
    ArrayList<TemplateLibrary.Entry> filtered = new ArrayList<>();
    for (TemplateLibrary.Entry entry : entries)
      if (filter.equals("全部") || filter.equals(entry.category)) filtered.add(entry);
    int totalPages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    galleryPage = Math.min(galleryPage, totalPages - 1);
    if (totalPages > 1) {
      LinearLayout pages = Ui.row(context);
      TextView previous =
          button(
              context,
              "‹ 上一组",
              false,
              () -> {
                galleryPage--;
                renderGallery();
              });
      previous.setEnabled(galleryPage > 0);
      pages.addView(previous, new LinearLayout.LayoutParams(0, Ui.dp(context, 44), 1));
      TextView position = Ui.text(context, (galleryPage + 1) + " / " + totalPages, 12, Ui.MUTED);
      position.setGravity(Gravity.CENTER);
      pages.addView(position, new LinearLayout.LayoutParams(0, Ui.dp(context, 44), 1));
      TextView next =
          button(
              context,
              "下一组 ›",
              false,
              () -> {
                galleryPage++;
                renderGallery();
              });
      next.setEnabled(galleryPage + 1 < totalPages);
      pages.addView(next, new LinearLayout.LayoutParams(0, Ui.dp(context, 44), 1));
      previous.setAlpha(galleryPage > 0 ? 1 : .4f);
      next.setAlpha(galleryPage + 1 < totalPages ? 1 : .4f);
      body.addView(pages);
    }
    LinearLayout row = null;
    int count = 0;
    for (int i = galleryPage * PAGE_SIZE;
        i < Math.min(filtered.size(), (galleryPage + 1) * PAGE_SIZE);
        i++) {
      TemplateLibrary.Entry entry = filtered.get(i);
      if (count % columns == 0) {
        row = Ui.row(context);
        row.setGravity(Gravity.TOP);
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
      }
      LinearLayout card = card(entry, request);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
      params.setMargins(Ui.dp(context, 3), Ui.dp(context, 8), Ui.dp(context, 3), 0);
      row.addView(card, params);
      count++;
    }
    if (count == 0) {
      TextView empty = Ui.text(context, "还没有此类模板，导入喜欢的 PDF 或图片后即可收藏复用。", 13, Ui.MUTED);
      Ui.pad(empty, 4, 22);
      body.addView(empty);
    } else if (count % columns != 0) {
      for (int i = count % columns; i < columns; i++)
        row.addView(new android.view.View(context), new LinearLayout.LayoutParams(0, 1, 1));
    }
  }

  private LinearLayout card(TemplateLibrary.Entry entry, int request) {
    Context context = getContext();
    LinearLayout card = Ui.column(context);
    card.setBackground(Ui.border(context, Ui.SURFACE, 14, Ui.LINE));
    Ui.pad(card, 9, 9);
    ImageView image = new ImageView(context);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setBackgroundColor(0xfff3f0e9);
    image.setContentDescription(entry.title + "原作预览");
    card.addView(image, new LinearLayout.LayoutParams(-1, Ui.dp(context, 164)));
    TextView title = Ui.label(context, entry.title, 14);
    title.setMaxLines(2);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    card.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(context, 44)));
    TextView author = Ui.text(context, entry.author + " · " + entry.pageCount + " 页", 10, Ui.MUTED);
    author.setSingleLine(true);
    author.setEllipsize(android.text.TextUtils.TruncateAt.END);
    card.addView(author, new LinearLayout.LayoutParams(-1, Ui.dp(context, 20)));
    TextView state = Ui.text(context, "正在读取原作…", 11, Ui.MUTED);
    card.addView(state, new LinearLayout.LayoutParams(-1, Ui.dp(context, 24)));
    TextView action = button(context, "预览与选页", false, () -> showDetail(entry));
    card.addView(action, new LinearLayout.LayoutParams(-1, Ui.dp(context, 44)));
    card.setOnClickListener(view -> showDetail(entry));
    image.setOnClickListener(view -> showDetail(entry));
    work(
        () -> {
          if (detached || generation != request) return;
          try {
            if (!TemplateLibrary.file(context, entry).isFile()) {
              main.post(
                  () -> {
                    if (detached || generation != request) return;
                    state.setText("待下载 · 免费个人使用");
                    image.setContentDescription(entry.title + "，尚未下载；点击从原站下载后显示真实预览");
                    action.setText("从来源下载");
                    action.setOnClickListener(view -> download(entry, action, state));
                    card.setOnClickListener(view -> download(entry, action, state));
                    image.setOnClickListener(view -> download(entry, action, state));
                  });
              return;
            }
            Bitmap bitmap = TemplateLibrary.preview(context, entry, entry.previewPage, 420);
            main.post(
                () -> {
                  if (detached || generation != request) {
                    bitmap.recycle();
                    return;
                  }
                  bitmaps.add(bitmap);
                  image.setImageBitmap(bitmap);
                  state.setText("已收藏 · 可离线复用");
                });
          } catch (IOException | RuntimeException error) {
            main.post(
                () -> {
                  if (detached || generation != request) return;
                  state.setText("预览失败，可访问来源重新导入");
                  action.setText("查看来源与说明");
                  action.setOnClickListener(view -> showSource(entry, error.getMessage()));
                  card.setOnClickListener(view -> showSource(entry, error.getMessage()));
                  image.setOnClickListener(view -> showSource(entry, error.getMessage()));
                });
          }
        });
    return card;
  }

  private void download(TemplateLibrary.Entry entry, TextView action, TextView status) {
    if (!action.isEnabled()) return;
    int request = generation;
    action.setEnabled(false);
    status.setText("正在从原作者来源下载…");
    work(
        () -> {
          try {
            TemplateLibrary.download(getContext(), entry);
            main.post(
                () -> {
                  if (!detached && generation == request) refresh();
                });
          } catch (IOException | RuntimeException error) {
            main.post(
                () -> {
                  if (detached || generation != request) return;
                  action.setEnabled(true);
                  status.setText("下载失败，可稍后重试或访问来源");
                  showSource(entry, error.getMessage());
                });
          }
        });
  }

  private void showDetail(TemplateLibrary.Entry entry) {
    showDetail(entry, entry.previewPage);
  }

  private void showDetail(TemplateLibrary.Entry entry, int pageIndex) {
    detailEntry = entry;
    detailPage = pageIndex;
    int request = beginDetail(entry.title);
    Context context = getContext();
    ImageView image = new ImageView(context);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setBackgroundColor(0xfff3f0e9);
    image.setContentDescription(entry.title + "所选内页原作预览");
    int imageHeight =
        Math.min(Ui.dp(context, 250), getResources().getDisplayMetrics().heightPixels / 4);
    body.addView(image, new LinearLayout.LayoutParams(-1, imageHeight));
    int[] selected = {pageIndex}, token = {0};
    Bitmap[] current = {null};
    TextView status = Ui.text(context, "", 12, Ui.MUTED);
    status.setGravity(Gravity.CENTER);
    status.setSingleLine(true);
    status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LinearLayout navigation = Ui.row(context);
    body.addView(navigation);
    TextView previous = button(context, "‹", false, () -> {});
    previous.setContentDescription("上一页");
    previous.setTextSize(22);
    TextView next = button(context, "›", false, () -> {});
    next.setContentDescription("下一页");
    next.setTextSize(22);
    navigation.addView(
        previous, new LinearLayout.LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44)));
    navigation.addView(status, new LinearLayout.LayoutParams(0, Ui.dp(context, 44), 1));
    navigation.addView(next, new LinearLayout.LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44)));
    TextView whole =
        button(
            context,
            "整本导入 · " + entry.pageCount + " 页",
            true,
            () -> callback.onNotebookSelected(entry));
    whole.setContentDescription("整本导入：" + entry.title);
    TextView hint =
        Ui.text(
            context,
            "新建一本笔记，保留封面与所有内页。"
                + (android.os.Build.VERSION.SDK_INT >= 35 ? "支持的目录可用手指轻点跳转。" : "导入后通过页列表翻页。"),
            11,
            Ui.MUTED);
    Ui.pad(hint, 0, 6);
    TextView use = button(context, "仅使用此页", false, () -> callback.onSelected(entry, selected[0]));
    use.setContentDescription("使用此页");
    LinearLayout actions = Ui.row(context);
    LinearLayout.LayoutParams firstAction = new LinearLayout.LayoutParams(0, Ui.dp(context, 44), 1);
    firstAction.rightMargin = Ui.dp(context, 8);
    actions.addView(whole, firstAction);
    actions.addView(use, new LinearLayout.LayoutParams(0, Ui.dp(context, 44), 1));
    LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, Ui.dp(context, 44));
    actionsParams.topMargin = Ui.dp(context, 8);
    body.addView(actions, actionsParams);
    body.addView(hint);
    addSource(entry);
    Runnable render =
        () -> {
          int page = selected[0], version = ++token[0];
          detailPage = page;
          status.setText("第 " + (page + 1) + " / " + entry.pageCount + " 页 · 正在读取…");
          previous.setEnabled(page > 0);
          next.setEnabled(page + 1 < entry.pageCount);
          previous.setAlpha(page > 0 ? 1 : .4f);
          next.setAlpha(page + 1 < entry.pageCount ? 1 : .4f);
          use.setEnabled(false);
          whole.setEnabled(false);
          work(
              () -> {
                try {
                  Bitmap bitmap = TemplateLibrary.preview(context, entry, page, 850);
                  main.post(
                      () -> {
                        if (detached || generation != request || token[0] != version) {
                          bitmap.recycle();
                          return;
                        }
                        image.setImageBitmap(bitmap);
                        if (current[0] != null) {
                          bitmaps.remove(current[0]);
                          current[0].recycle();
                        }
                        current[0] = bitmap;
                        bitmaps.add(bitmap);
                        status.setText("第 " + (page + 1) + " / " + entry.pageCount + " 页 · 原作预览");
                        use.setEnabled(true);
                        whole.setEnabled(true);
                      });
                } catch (IOException | RuntimeException error) {
                  main.post(
                      () -> {
                        if (!detached && generation == request && token[0] == version)
                          status.setText(error.getMessage());
                      });
                }
              });
        };
    previous.setOnClickListener(
        v -> {
          if (selected[0] > 0) {
            selected[0]--;
            render.run();
          }
        });
    next.setOnClickListener(
        v -> {
          if (selected[0] + 1 < entry.pageCount) {
            selected[0]++;
            render.run();
          }
        });
    render.run();
  }

  private int beginDetail(String title) {
    int request = ++generation;
    detailVisible = true;
    body.removeAllViews();
    releaseGalleryBitmaps();
    LinearLayout headingRow = Ui.row(getContext());
    headingRow.addView(
        button(getContext(), "‹ 返回模板库", false, this::renderGallery),
        new LinearLayout.LayoutParams(Ui.dp(getContext(), 112), Ui.dp(getContext(), 44)));
    TextView heading = Ui.label(getContext(), title, 16);
    Ui.pad(heading, 12, 0);
    heading.setMaxLines(2);
    heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
    headingRow.addView(heading, new LinearLayout.LayoutParams(0, Ui.dp(getContext(), 48), 1));
    body.addView(headingRow);
    scrollTo(0, 0);
    return request;
  }

  private void addSource(TemplateLibrary.Entry entry) {
    Context context = getContext();
    TextView description = Ui.text(context, entry.description, 12, Ui.TEXT);
    Ui.pad(description, 0, 10);
    body.addView(description);
    body.addView(Ui.text(context, entry.author + "\n" + entry.terms, 11, Ui.MUTED));
    LinearLayout links = Ui.row(context);
    if (!entry.source.isEmpty()) {
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Ui.dp(context, 44));
      p.rightMargin = Ui.dp(context, 8);
      links.addView(button(context, "原作者来源 ↗", false, () -> open(entry.source)), p);
    }
    if (!entry.termsUrl.isEmpty())
      links.addView(
          button(context, "使用条款 ↗", false, () -> open(entry.termsUrl)),
          new LinearLayout.LayoutParams(-2, Ui.dp(context, 44)));
    LinearLayout.LayoutParams linksParams = new LinearLayout.LayoutParams(-1, Ui.dp(context, 44));
    linksParams.topMargin = Ui.dp(context, 8);
    if (links.getChildCount() > 0) body.addView(links, linksParams);
  }

  private void showSource(TemplateLibrary.Entry entry, String message) {
    detailEntry = null;
    beginDetail(entry.title);
    body.addView(
        Ui.text(getContext(), message == null ? "来源暂时不可用，请稍后重试。" : message, 13, Ui.DANGER));
    addSource(entry);
    body.addView(button(getContext(), "导入已下载的 PDF / 图片", true, callback::onImportRequested));
  }

  private void open(String address) {
    if (!address.startsWith("https://")) return;
    try {
      getContext()
          .startActivity(
              new Intent(Intent.ACTION_VIEW, Uri.parse(address))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (ActivityNotFoundException error) {
      Toast.makeText(getContext(), "没有可打开来源的浏览器：" + address, Toast.LENGTH_LONG).show();
    }
  }

  private void work(Runnable task) {
    if (detached) return;
    try {
      executor.execute(task);
    } catch (RejectedExecutionException ignored) {
      /* Closed picker. */
    }
  }

  private TextView button(Context context, String text, boolean primary, Runnable action) {
    TextView button = Ui.label(context, text, 12);
    button.setGravity(Gravity.CENTER);
    button.setMinHeight(Ui.dp(context, 44));
    button.setSingleLine(true);
    button.setEllipsize(android.text.TextUtils.TruncateAt.END);
    Ui.pad(button, 12, 4);
    button.setTextColor(primary ? Ui.ON_ACCENT : Ui.TEXT);
    button.setBackground(Ui.bg(context, primary ? Ui.ACCENT : Ui.FLOAT, 10));
    button.setContentDescription(text);
    button.setSelected(primary);
    button.setOnClickListener(v -> action.run());
    Ui.press(button);
    return button;
  }

  private void releaseGalleryBitmaps() {
    for (Bitmap bitmap : bitmaps) if (!bitmap.isRecycled()) bitmap.recycle();
    bitmaps.clear();
  }
}
