package app.iko.notes;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Consumer;

/** Classroom tool UI and local audio lifecycle, sharing the native ink document and history. */
final class StudyTools {
  interface Panels {
    void show(String title, View content, Runnable commit);

    void close();
  }

  static final int MICROPHONE_PERMISSION = 41;
  private final Context context;
  private final Activity activity;
  private final Runnable close;
  private final java.util.function.BiConsumer<String[], Integer> permissions;
  private final InkView ink;
  private FrameLayout surface;
  private final Panels panels;
  private final Consumer<String> select;
  private final Runnable changed, status;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final NoteAudio audio;
  private boolean showingPanel, pendingRecord, closingText;
  private EditText textEditor;
  private NoteDocument.Element textOriginal, textDraft;
  private NoteDocument.Page textPage;
  private LinearLayout audioBody;
  private TextView audioTime;
  private TextView hostAudioTime;
  private final ArrayList<SeekBar> audioSliders = new ArrayList<>();
  private int laserColor = 0xffff315b, laserDuration = 1500, tapeColor = 0xffa4bccb;
  private boolean laserTrail = true;
  private String tapePattern = "stripes";
  private final View.OnLayoutChangeListener surfaceLayout =
      (v, l, t, r, b, ol, ot, or, ob) -> positionTextEditor();

  StudyTools(
      Context context,
      Activity activity,
      Runnable close,
      java.util.function.BiConsumer<String[], Integer> permissions,
      InkView ink,
      FrameLayout surface,
      Panels panels,
      Consumer<String> select,
      Runnable changed,
      Runnable status) {
    this.context = context;
    this.activity = activity;
    this.close = close;
    this.permissions = permissions;
    this.ink = ink;
    this.surface = surface;
    this.panels = panels;
    this.select = select;
    this.changed = changed;
    this.status = status;
    audio = new NoteAudio(context, this::audioChanged, this::audioError);
    surface.addOnLayoutChangeListener(surfaceLayout);
    ink.setTextToolListener(this::editText);
    ink.setTapeStyle(tapeColor, tapePattern);
    ink.setLaserStyle(laserColor, laserTrail, laserDuration);
    surface.post(this::restoreTextDraft);
  }

  void rebind(FrameLayout surface) {
    finishText(true);
    this.surface.removeOnLayoutChangeListener(surfaceLayout);
    this.surface = surface;
    surface.addOnLayoutChangeListener(surfaceLayout);
    if (textEditor != null) {
      if (textEditor.getParent() != null)
        ((ViewGroup) textEditor.getParent()).removeView(textEditor);
      surface.addView(textEditor);
      surface.post(this::showTextOptions);
    }
  }

  boolean showingPanel() {
    return showingPanel;
  }

  boolean editingText() {
    return textEditor != null;
  }

  boolean recording() {
    return NotesHost.attached() ? NotesHost.recording() : audio.recording();
  }

  String recordingTime() {
    return time(NotesHost.attached() ? NotesHost.durationMs() : audio.elapsedMs());
  }

  void refreshHostAudioTime() {
    if(hostAudioTime==null || !hostAudioTime.isShown())return;
    String label="录音 · "+time(NotesHost.durationMs());
    if(!label.contentEquals(hostAudioTime.getText()))hostAudioTime.setText(label);
  }

  boolean ownsTouch(MotionEvent event) {
    if (textEditor == null || !textEditor.isShown()) return false;
    int[] at = new int[2];
    textEditor.getLocationOnScreen(at);
    return event.getRawX() >= at[0]
        && event.getRawX() < at[0] + textEditor.getWidth()
        && event.getRawY() >= at[1]
        && event.getRawY() < at[1] + textEditor.getHeight();
  }

  private void show(String title, View content, Runnable commit) {
    showingPanel = true;
    try {
      panels.show(title, content, commit);
    } finally {
      showingPanel = false;
    }
  }

  void laser() {
    if (!finishText(true)) return;
    select.accept("laser");
    LinearLayout row = row();
    row.addView(
        button(
            "laser",
            "激光光点",
            !laserTrail,
            () -> {
              laserTrail = false;
              applyLaser();
              laser();
            }));
    row.addView(
        button(
            "pen",
            "渐隐轨迹",
            laserTrail,
            () -> {
              laserTrail = true;
              applyLaser();
              laser();
            }));
    addColors(
        row,
        new int[] {0xffff315b, 0xffffb72c, 0xff06cf9c, 0xff7661ff},
        laserColor,
        c -> {
          laserColor = c;
          applyLaser();
          laser();
        });
    row.addView(label("淡出", 11), lp(40, 48));
    TextView duration = label(String.format(Locale.ROOT, "%.1fs", laserDuration / 1000f), 12);
    row.addView(
        slider(
            400,
            4000,
            laserDuration,
            value -> {
              laserDuration = value;
              duration.setText(String.format(Locale.ROOT, "%.1fs", value / 1000f));
              applyLaser();
            }),
        lp(160, 48));
    row.addView(duration, lp(48, 48));
    show("激光笔", scroll(row), () -> {});
  }

  private void applyLaser() {
    ink.setLaserStyle(laserColor, laserTrail, laserDuration);
  }

  void tape() {
    if (!finishText(true)) return;
    select.accept("tape");
    LinearLayout body = column(), row = row();
    addColors(
        row,
        new int[] {0xffa4bccb, 0xffb7c6ab, 0xffe6c29c, 0xffd9b5c6},
        tapeColor,
        c -> {
          tapeColor = c;
          ink.setTapeStyle(tapeColor, tapePattern);
          tape();
        });
    for (String pattern : new String[] {"plain", "stripes", "dots"}) {
      View preview =
          new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
              NoteDocument.Element sample = new NoteDocument.Element();
              sample.kind = "tape";
              sample.pattern = pattern;
              sample.color = tapeColor;
              sample.x = 6;
              sample.y = getHeight() / 2f - dp(13);
              sample.width = getWidth() - 12;
              sample.height = dp(26);
              TapeArt.draw(canvas, sample);
            }
          };
      preview.setBackground(
          Ui.border(
              context,
              pattern.equals(tapePattern) ? Ui.SELECTED : Ui.FLOAT,
              10,
              pattern.equals(tapePattern) ? Ui.ACCENT : Ui.LINE));
      preview.setSelected(pattern.equals(tapePattern));
      preview.setContentDescription(
          "胶带纹理：" + (pattern.equals("plain") ? "纯色" : pattern.equals("stripes") ? "斜纹" : "圆点"));
      preview.setOnClickListener(
          v -> {
            tapePattern = pattern;
            ink.setTapeStyle(tapeColor, tapePattern);
            tape();
          });
      LinearLayout.LayoutParams previewParams = lp(56, 44);
      previewParams.rightMargin = dp(6);
      row.addView(preview, previewParams);
    }
    row.addView(button("eye", "揭开本页全部胶带", false, () -> ink.revealAllTape(true)));
    row.addView(button("eye-off", "遮住本页全部胶带", false, () -> ink.revealAllTape(false)));
    body.addView(row, lp(-2, 48));
    body.addView(label("拖拽遮住答案 · 轻点揭开 / 遮住 · 套索可编辑", 11), lp(-1, 24));
    show("背诵胶带", scroll(body), () -> {});
  }

  void text() {
    if (!finishText(true)) return;
    select.accept("text");
    show("文本框", label("点一下纸面开始输入；再次点已有文本框即可编辑，套索可移动和旋转。", 12), () -> {});
  }

  private void editText(NoteDocument.Element original, PointF at) {
    if (!finishText(true)) return;
    textPage = ink.getPage();
    textOriginal = original;
    textDraft = original == null ? new NoteDocument.Element() : original.copy();
    if (original == null) {
      textDraft.textSize = 26;
      int paperColor = textPage.color;
      textDraft.color =
          android.graphics.Color.red(paperColor) * .299f
                      + android.graphics.Color.green(paperColor) * .587f
                      + android.graphics.Color.blue(paperColor) * .114f
                  > 140
              ? 0xff272d3b
              : 0xffeceff5;
      textDraft.x = at.x;
      textDraft.y = at.y;
      textDraft.width =
          Math.max(120, Math.min(500, surface.getWidth() / ink.viewportScale() * .6f));
      if (!textPage.infinite) {
        textDraft.x = Math.max(12, Math.min(textDraft.x, textPage.width - 140));
        textDraft.width =
            Math.max(100, Math.min(textDraft.width, textPage.width - textDraft.x - 12));
        textDraft.y = Math.max(12, Math.min(textDraft.y, textPage.height - 80));
      }
    }
    ink.setEditingImage(original);
    EditText edit = new EditText(context);
    textEditor = edit;
    edit.setContentDescription("纸面文本输入框");
    edit.setText(textDraft.text);
    edit.setHint("输入文字…");
    edit.setGravity(Gravity.TOP);
    edit.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(100000)});
    edit.setBackground(Ui.border(context, textPage.color, 10, Ui.ACCENT));
    edit.setPadding(dp(10), dp(8), dp(10), dp(8));
    edit.setTextColor(textDraft.color);
    edit.setSelectAllOnFocus(false);
    edit.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (textDraft != null) textDraft.text = s.toString();
          }

          public void afterTextChanged(Editable e) {}
        });
    surface.addView(edit, new FrameLayout.LayoutParams(dp(280), dp(110)));
    showTextOptions();
    edit.post(
        () -> {
          if (textEditor != edit) return;
          positionTextEditor();
          edit.requestFocus();
          edit.setSelection(edit.length());
          ((InputMethodManager) context.getSystemService(Activity.INPUT_METHOD_SERVICE))
              .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
        });
  }

  private void positionTextEditor() {
    if (textEditor == null) return;
    RectF bounds = ink.imageScreenBounds(textDraft);
    int width =
        Math.max(
            dp(60),
            Math.min(
                surface.getWidth() - dp(16),
                Math.max(dp(180), Math.round(bounds.width()) + dp(20))));
    int height = Math.max(dp(96), Math.min(dp(220), Math.round(bounds.height()) + dp(40)));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
    params.leftMargin =
        Math.max(dp(8), Math.min(surface.getWidth() - width - dp(8), Math.round(bounds.left)));
    params.topMargin =
        Math.max(
            dp(8),
            Math.min(
                Math.max(dp(8), surface.getHeight() - height - dp(8)), Math.round(bounds.top)));
    textEditor.setLayoutParams(params);
    textEditor.setTextSize(
        Math.max(
            14,
            Math.min(
                44,
                textDraft.textSize
                    * ink.viewportScale()
                    / context.getResources().getDisplayMetrics().scaledDensity)));
    textEditor.setTypeface(
        android.graphics.Typeface.DEFAULT,
        textDraft.bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    textEditor.setGravity(
        Gravity.TOP
            | (textDraft.textAlign.equals("center")
                ? Gravity.CENTER_HORIZONTAL
                : textDraft.textAlign.equals("right") ? Gravity.RIGHT : Gravity.LEFT));
    textEditor.setTextColor(textDraft.color);
  }

  private void showTextOptions() {
    LinearLayout row = row();
    row.addView(
        button(
            "bold",
            "文字加粗",
            textDraft.bold,
            () -> {
              textDraft.bold = !textDraft.bold;
              positionTextEditor();
              showTextOptions();
            }));
    for (String align : new String[] {"left", "center", "right"})
      row.addView(
          button(
              "align-" + align,
              "文字对齐：" + align,
              textDraft.textAlign.equals(align),
              () -> {
                textDraft.textAlign = align;
                positionTextEditor();
                showTextOptions();
              }));
    TextView size = label(Math.round(textDraft.textSize) + "", 12);
    row.addView(
        slider(
            10,
            96,
            Math.round(textDraft.textSize),
            value -> {
              textDraft.textSize = value;
              size.setText(value + "");
              positionTextEditor();
            }),
        lp(150, 48));
    row.addView(size, lp(32, 48));
    addColors(
        row,
        new int[] {0xff272d3b, 0xff476ad1, 0xffb84f67, 0xff398777, 0xffeceff5},
        textDraft.color,
        color -> {
          textDraft.color = color;
          positionTextEditor();
          showTextOptions();
        });
    row.addView(
        button(
            "close",
            "取消文字修改",
            false,
            () -> {
              finishText(false);
              panels.close();
            }));
    row.addView(
        button(
            "trash",
            "删除文本框",
            false,
            () -> {
              NoteDocument.Element original = textOriginal;
              NoteDocument.Page page = textPage;
              finishText(false);
              if (original != null) ink.mutate(() -> page.elements.remove(original));
              panels.close();
            }));
    show("编辑文本框", scroll(row), () -> finishText(true));
    positionTextEditor();
  }

  boolean finishText(boolean commit) {
    if (textEditor == null) return true;
    if (closingText) return false;
    closingText = true;
    EditText editor = textEditor;
    NoteDocument.Element draft = textDraft, original = textOriginal;
    NoteDocument.Page page = textPage;
    draft.text = editor.getText().toString();
    if (commit && !draft.text.trim().isEmpty() && !PageRenderer.fitTextToPage(page, draft)) {
      retainTextDraft();
      toast("文字超过页面高度，输入已保留；请缩小字号或分成多个文本框");
      closingText = false;
      return false;
    }
    textEditor = null;
    textDraft = null;
    textOriginal = null;
    textPage = null;
    context.getSharedPreferences("text-drafts", 0).edit().remove(ink.getDocument().id).apply();
    ((InputMethodManager) context.getSystemService(Activity.INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(editor.getWindowToken(), 0);
    if (editor.getParent() != null) ((ViewGroup) editor.getParent()).removeView(editor);
    ink.setEditingImage(null);
    if (commit && (!draft.text.trim().isEmpty() || original != null)) {
      Runnable update =
          () -> {
            if (draft.text.trim().isEmpty()) page.elements.remove(original);
            else if (original == null) page.elements.add(draft);
            else {
              int index = page.elements.indexOf(original);
              if (index >= 0) page.elements.set(index, draft);
            }
          };
      if (page == ink.getPage()) ink.mutate(update);
      else {
        update.run();
        changed.run();
      }
    }
    closingText = false;
    return true;
  }

  private void retainTextDraft() {
    try {
      org.json.JSONObject saved =
          new org.json.JSONObject()
              .put("pageId", textPage.id)
              .put("element", textDraft.toJson())
              .put("originalIndex", textPage.elements.indexOf(textOriginal))
              .put("original", textOriginal == null ? "" : textOriginal.toJson().toString());
      context
          .getSharedPreferences("text-drafts", 0)
          .edit()
          .putString(ink.getDocument().id, saved.toString())
          .apply();
    } catch (org.json.JSONException error) {
      throw new IllegalStateException(error);
    }
  }

  private void restoreTextDraft() {
    if (!surface.isAttachedToWindow() || textEditor != null) return;
    String stored =
        context.getSharedPreferences("text-drafts", 0).getString(ink.getDocument().id, "");
    if (stored.isEmpty()) return;
    try {
      org.json.JSONObject saved = new org.json.JSONObject(stored);
      if (!ink.getPage().id.equals(saved.getString("pageId"))) return;
      NoteDocument.Element draft = NoteDocument.Element.fromJson(saved.getJSONObject("element"));
      int index = saved.optInt("originalIndex", -1);
      NoteDocument.Element original =
          index >= 0 && index < ink.getPage().elements.size()
              ? ink.getPage().elements.get(index)
              : null;
      if (original != null && !original.toJson().toString().equals(saved.optString("original")))
        original = null;
      select.accept("text");
      editText(original, new PointF(draft.x, draft.y));
      textDraft = draft;
      textEditor.setText(draft.text);
      showTextOptions();
      toast("已恢复尚未完成的文本，请调整字号后保存");
    } catch (org.json.JSONException error) {
      toast("未完成文本暂时无法恢复，原草稿仍保留");
    }
  }

  void audio() {
    if (finishText(true)) showAudioPanel();
  }

  private void showAudioPanel() {
    audioBody = column();
    audioBody.setTag("compact-options");
    audioSliders.clear();
    LinearLayout controls = row();
    if (NotesHost.attached()) {
      controls.addView(hostAudioControls(), new LinearLayout.LayoutParams(-1, dp(48)));
    } else if (!audio.recording())
      controls.addView(button("record", "开始课堂录音", false, this::startRecording));
    else {
      controls.addView(
          button(
              audio.paused() ? "play" : "pause",
              audio.paused() ? "继续录音" : "暂停录音",
              true,
              () -> {
                try {
                  audio.togglePause();
                } catch (RuntimeException e) {
                  audioError("无法暂停录音，已结束并保存当前片段");
                }
              }));
      controls.addView(button("stop", "停止并保存录音", false, this::finishRecording));
    }
    audioTime =
        label(
            audio.recording()
                ? (audio.paused() ? "已暂停  " : "正在录音  ") + time(audio.elapsedMs())
                : recordingLocationText(),
            13);
    if (!NotesHost.attached()) controls.addView(audioTime, new LinearLayout.LayoutParams(0, dp(48), 1));
    else audioBody.addView(audioTime, lp(-1, 32));
    audioBody.addView(controls, lp(-1, 48));
    for (NoteDocument.Recording clip : ink.getDocument().recordings) {
      LinearLayout row = row();
      row.addView(
          button(
              audio.isPlaying(clip.id) ? "pause" : "play",
              "播放或暂停：" + clip.title,
              audio.isPlaying(clip.id),
              () -> {
                try {
                  audio.play(clip);
                } catch (IOException e) {
                  toast(e.getMessage());
                }
              }));
      long bytes=new java.io.File(clip.path).length();
      String fileSize=bytes<1024?bytes+" B":bytes<1048576?String.format(Locale.ROOT,"%.1f KB",bytes/1024.0):String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0);
      TextView title = label(clip.title + "  ·  " + time(clip.durationMs)+" · "+fileSize, 12);
      title.setSingleLine(true);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      title.setContentDescription("定位录音页面：" + clip.title);
      title.setOnClickListener(
          v -> {
            int page = ink.getDocument().findPageIndex(clip.pageId);
            if (page >= 0) ink.setPage(page);
            else toast("录音原页面已删除，仍可继续播放");
          });
      row.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
      DragSlider seek =
          slider(
              0,
              Math.max(1, (int) clip.durationMs),
              audio.position(clip.id),
              value -> audio.seek(clip.id, value));
      seek.setTag(clip.id);
      seek.setContentDescription("录音播放进度：" + clip.title);
      audioSliders.add(seek);
      row.addView(seek, lp(Math.max(56,Math.min(140,(int)(ink.getWidth()/context.getResources().getDisplayMetrics().density)-240)), 48));
      row.addView(
          button(
              "trash",
              "删除录音：" + clip.title,
              false,
              () -> {
                LinearLayout confirm = row();
                confirm.addView(Ui.button(context, "取消", false, this::showAudioPanel), lp(96, 48));
                confirm.addView(
                    Ui.button(
                        context,
                        "删除这段录音",
                        false,
                        () -> {
                          audio.stopPlayback();
                          ink.getDocument().recordings.remove(clip);
                          changed.run();
                          showAudioPanel();
                        }),
                    lp(160, 48));
                show("删除录音？", confirm, this::showAudioPanel);
              }));
      audioBody.addView(row, lp(-1, 48));
    }
    if (ink.getDocument().recordings.isEmpty())
      audioBody.addView(label(NotesHost.attached() ? "独立录音随笔记保存；课堂分屏录音留在课堂记录。" : "开始后可继续写字和翻页。离开应用或笔记时自动停止并保存。", 11), lp(-1, 32));
    show(Boolean.TRUE.equals(NotesHost.state.get("running"))&&!Boolean.TRUE.equals(NotesHost.state.get("noteRecording"))?"课堂录音":"笔记录音", audioBody, () -> {});
  }

  private String recordingLocationText(){
    if(!NotesHost.attached())return "录音保存在这本笔记里";
    if(Boolean.TRUE.equals(NotesHost.state.get("running"))&&!Boolean.TRUE.equals(NotesHost.state.get("noteRecording")))return "课堂录音保存在课堂记录，笔记停留时间同步记录";
    Object owner=NotesHost.state.get("noteRecordingOwnerId");
    if(Boolean.TRUE.equals(NotesHost.state.get("noteRecording"))&&owner!=null&&!ink.getDocument().id.equals(owner))return "录音仍保存在开始时的笔记；暂停后可切换";
    return "独立录音保存在这本笔记里，可在下方回放";
  }
  private LinearLayout hostAudioControls() {
    LinearLayout controls = row();
    controls.addView(button("record", NotesHost.recording() ? "暂停保存录音" : "开启课堂录音", NotesHost.recording(),
        () -> { if (!NotesHost.recording()) { startRecording(); return; }
          NotesHost.request(activity, "pauseRecording", result -> {
          Object error = result.get("error");
          if (error != null) toast(error.toString());
          else { status.run(); showAudioPanel(); }
        }); }));
    hostAudioTime=label("录音 · " + time(NotesHost.durationMs()), 12);
    controls.addView(hostAudioTime, new LinearLayout.LayoutParams(0, dp(48), 1));
    if(Boolean.TRUE.equals(NotesHost.state.get("running"))&&!Boolean.TRUE.equals(NotesHost.state.get("noteRecording"))) controls.addView(button("folder", "查看课堂录音与关联", false,
        () -> NotesHost.request(activity, "openArchives", result -> { if (result.get("error") == null) close.run(); else toast(result.get("error").toString()); })));
    if (Boolean.TRUE.equals(NotesHost.state.get("running")) && Boolean.TRUE.equals(NotesHost.state.get("recordingOnly")))
      controls.addView(button("stop", "停止并保存到笔记", false,
          () -> NotesHost.request(activity,"stopRecordingClassroom",result -> {
            if(result.get("error")!=null)toast(result.get("error").toString()); else showAudioPanel();
          })));
    return controls;
  }

  private void startRecording() {
    if (ink.getDocument().recordings.size() >= 200) {
      toast("本笔记已有 200 段录音，请换一本继续录制");
      return;
    }

    if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      pendingRecord = true;
      permissions.accept(new String[] {Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
      return;
    }
    if (NotesHost.attached()) {
      NotesHost.request(activity, "startRecording", new java.util.HashMap<String,Object>(){{put("noteId",ink.getDocument().id);put("pageId",ink.getPage().id);}}, result -> { if (result.get("error") != null) toast(result.get("error").toString()); else {status.run();showAudioPanel();} });
      return;
    }
    if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      pendingRecord = true;
      permissions.accept(
          new String[] {Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
      return;
    }
    try {
      audio.start(ink.getPage().id);
    } catch (IOException error) {
      toast(error.getMessage());
    }
  }

  void microphoneResult() {
    if (!pendingRecord) return;
    pendingRecord = false;
    if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED) startRecording();
    else toast("没有获得麦克风权限，尚未开始录音");
  }

  void finishRecording() {
    if (!audio.recording()) return;
    try {
      NoteDocument.Recording clip = audio.stop();
      if (clip != null) {
        clip.title = ink.getDocument().nextRecordingTitle();
        ink.getDocument().recordings.add(clip);
        changed.run();
      }
    } catch (IOException error) {
      toast(error.getMessage());
    }
    audioChanged();
  }

  private void audioError(String message) {
    toast(message);
    finishRecording();
  }

  private final Runnable refreshAudio = () -> refreshAudioPanel();

  void recordingsChanged(){if(audioBody!=null&&audioBody.isShown())showAudioPanel();}
  private void refreshAudioPanel() {
    if (audioBody != null && audioBody.isShown()) showAudioPanel();
    status.run();
  }

  private void audioChanged() {
    main.removeCallbacks(refreshAudio);
    main.post(refreshAudio);
    main.removeCallbacks(tick);
    main.post(tick);
  }

  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (audioBody != null && audioBody.isShown()) {
            if (audioTime != null && audio.recording()) {
              String text = (audio.paused() ? "已暂停  " : "正在录音  ") + time(audio.elapsedMs());
              if (!text.contentEquals(audioTime.getText())) audioTime.setText(text);
            }
            for (SeekBar slider : audioSliders) {
              if (!slider.isPressed()) slider.setProgress(audio.position((String) slider.getTag()));
            }
          }
          status.run();
          if (audio.recording() || audio.hasPlayback()) main.postDelayed(this, 500);
        }
      };

  void onPause() {
    finishText(true);
    if (!NotesHost.attached()) finishRecording();
    audio.stopPlayback();
    main.removeCallbacks(tick);
    main.removeCallbacks(refreshAudio);
    status.run();
    if (audioBody != null && audioBody.isShown()) showAudioPanel();
  }

  private LinearLayout row() {
    return Ui.row(context);
  }

  private LinearLayout column() {
    return Ui.column(context);
  }

  private int dp(float value) {
    return Ui.dp(context, value);
  }

  private LinearLayout.LayoutParams lp(int w, int h) {
    return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
  }

  private TextView label(String text, int size) {
    TextView view = Ui.text(context, text, size, Ui.MUTED);
    view.setGravity(Gravity.CENTER_VERTICAL);
    return view;
  }

  private IconView button(String icon, String description, boolean selected, Runnable action) {
    IconView view = new IconView(context, icon);
    LinearLayout.LayoutParams params = lp(44, 44);
    params.setMargins(dp(2), dp(2), dp(2), dp(2));
    view.setLayoutParams(params);
    view.setContentDescription(description);
    view.setTooltipText(description);
    view.tint(selected ? Ui.ACCENT : Ui.MUTED);
    view.setSelected(selected);
    view.setBackground(Ui.bg(context, selected ? Ui.SELECTED : Ui.FLOAT, 10));
    view.setOnClickListener(v -> action.run());
    return view;
  }

  private View scroll(View row) {
    HorizontalScrollView scroll = new HorizontalScrollView(context);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.addView(row);
    return scroll;
  }

  private void addColors(LinearLayout row, int[] colors, int selected, Consumer<Integer> apply) {
    for (int color : colors) {
      FrameLayout cell = new FrameLayout(context);
      View dot = new View(context);
      dot.setBackground(Ui.border(context, color, 12, color == selected ? Ui.ACCENT : Ui.LINE));
      cell.addView(dot, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
      cell.setContentDescription(String.format(Locale.ROOT, "工具颜色 #%06X", color & 0xffffff));
      cell.setOnClickListener(v -> apply.accept(color));
      row.addView(cell, lp(40, 48));
    }
  }

  private DragSlider slider(int min, int max, int value, Consumer<Integer> apply) {
    DragSlider slider = new DragSlider(context);
    slider.setMax(max - min);
    slider.setProgress(value - min);
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int at, boolean user) {
            if (user) apply.accept(at + min);
          }

          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {}
        });
    return slider;
  }

  private void toast(String message) {
    android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show();
  }

  private static String time(long millis) {
    return String.format(Locale.ROOT, "%02d:%02d", millis / 60000, millis / 1000 % 60);
  }
}
