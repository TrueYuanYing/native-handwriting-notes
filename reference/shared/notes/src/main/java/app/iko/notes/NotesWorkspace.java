package app.iko.notes;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Native, local-first IKO notebook workspace. Disk and document imports run off the UI thread. */
public final class NotesWorkspace extends android.view.ContextThemeWrapper {
  private final Activity activity;
  private final boolean embedded;
  private final Runnable close;
  private final NotesWorkspaceRoot workspaceView;
  private Intent intent;
  private boolean requestedVisible, disposed, visitVisible;
  private boolean libraryIndexLoading, libraryIndexDirty = true;
  private boolean trashCheckPending, recordingInboxBusy;
  private long recordingInboxChecked;
  private int libraryRevision;
  private int activeContentJobs;
  private Runnable pendingImportChoice;
  private boolean compactHeader, compactWorkspace;
  private int fitRequest;
  private final Set<Integer> activityRequests = new HashSet<>(), permissionRequests = new HashSet<>();
  private int measuredWidthDp, measuredHeightDp;
  private Runnable stateObserver;

  public NotesWorkspace(Activity activity, Intent intent, Bundle state, boolean embedded, Runnable close) {
    super(activity, R.style.AppThemeLight);
    this.activity = activity;
    this.intent = intent == null ? new Intent() : new Intent(intent);
    this.embedded = embedded;
    compactWorkspace = this.intent.getBooleanExtra("compactWorkspace",false);
    this.close = close;
    requestedVisible = !embedded;
    workspaceView = new NotesWorkspaceRoot(this);
    workspaceView.setVisibility(requestedVisible ? View.VISIBLE : View.GONE);
    create(state);
  }

  public View getView() { return workspaceView; }
  public Activity getActivity() { return activity; }
  public boolean isDisposed() { return disposed; }
  public boolean isBusy() { return activeContentJobs > 0 || noteCaptureBusy || pendingExport != null; }
  /** Commits current input and waits behind pending saves. A non-null reply is a failure reason. */
  public void flush(Consumer<String> reply) {
    if (disposed) { reply.accept("笔记工作区已关闭"); return; }
    if (isBusy()) { reply.accept("笔记正在导入、处理媒体或导出，请完成后重试"); return; }
    if (studyTools != null && !studyTools.finishText(true)) {
      reply.accept("当前文字尚未提交，请完成文本编辑后重试"); return;
    }
    if (activeToolPanelCommit != null) activeToolPanelCommit.run();
    if (ink != null) ink.finishInput();
    saveReadingPosition(); saveCurrent(false);
    io.execute(() -> main.post(() -> {
      if (isBusy()) reply.accept("笔记资源仍在写入，请稍后重试");
      else if (dirty || !unsavedSnapshots.isEmpty()) reply.accept("笔记尚未保存成功，请重试保存");
      else reply.accept(null);
    }));
  }
  public void whenIdle(Consumer<String> reply) { flush(reply); }
  public boolean handlesActivityResult(int request) { return activityRequests.contains(request); }
  public boolean handlesPermissionResult(int request) { return permissionRequests.contains(request); }
  private void executeContent(Runnable task) {
    activeContentJobs++;
    libraryIndexDirty = true;
    libraryRevision++;
    io.execute(() -> { try { task.run(); } finally { main.post(() -> activeContentJobs--); } });
  }
  private Intent getIntent() { return intent; }
  private void setIntent(Intent value) {
    intent = value;
    if (!embedded) activity.setIntent(value);
  }
  private boolean isFinishing() { return disposed || activity.isFinishing(); }
  private boolean isDestroyed() { return disposed || activity.isDestroyed(); }
  private View getCurrentFocus() { return workspaceView.findFocus(); }
  private void startActivityForResult(Intent value, int request) {
    activity.startActivityForResult(value, request);
    activityRequests.add(request);
  }
  private void requestPermissions(String[] permissions, int request) {
    activity.requestPermissions(permissions, request);
    permissionRequests.add(request);
  }
  private static final int RESULT_OK = Activity.RESULT_OK;

  public void setVisible(boolean visible) {
    if (disposed || requestedVisible == visible) return;
    requestedVisible = visible;
    workspaceView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    if (!visible) { suspendInput(); saveReadingPosition(); saveCurrent(false); }
    updateVisibility();
  }

  void updateVisibility() {
    boolean shown = !disposed && hostResumed && requestedVisible && workspaceView.isAttachedToWindow()
        && workspaceView.getWindowVisibility() == View.VISIBLE && workspaceView.isShown();
    if (shown != visitVisible) {
      visitVisible = shown;
      if (!shown) { main.removeCallbacks(hostTick); suspendInput(); }
      else { main.removeCallbacks(hostTick); main.post(hostTick); checkTrashExpiry(); }
    }
    String visit = shown + "/" + (document == null ? "" : document.id) + "/" + (ink == null ? "" : ink.getPage().id);
    if (!visit.equals(reportedVisit)) {
      reportedVisit = visit;
      NotesHost.visibility(this, activity, shown, document, ink == null ? null : ink.getPage().id);
    }
  }
  private void checkTrashExpiry(){
    if(trashCheckPending||disposed)return;trashCheckPending=true;
    io.execute(()->{
      boolean updated=false;
      try {updated=NotesStorage.prune(this,System.currentTimeMillis());}catch(Exception ignored){/* Preserve failed files for retry. */}
      final boolean changed=updated;
      main.post(()->{trashCheckPending=false;if(disposed)return;
        if(changed){libraryIndexDirty=true;libraryRevision++;if(document==null&&libraryLoaded&&!libraryIndexLoading)showLibrary();}
      });
    });
  }

  private void suspendInput() {
    if (ink != null) ink.finishInput();
    if (studyTools != null) studyTools.onPause();
    cancelCapture(); closeImageTransform(); restoreHeldTool(); stylusButtons.reset();
    suppressCaptureContact = false; dismissPanelContact = false;
  }

  int containerWidthDp() {
    return measuredWidthDp > 0 ? measuredWidthDp : getResources().getConfiguration().screenWidthDp;
  }
  int containerHeightDp() {
    return measuredHeightDp > 0 ? measuredHeightDp : getResources().getConfiguration().screenHeightDp;
  }
  void onContainerSizeChanged(int width, int height) {
    int w = Math.max(1, Math.round(width / getResources().getDisplayMetrics().density));
    int h = Math.max(1, Math.round(height / getResources().getDisplayMetrics().density));
    if (w == measuredWidthDp && h == measuredHeightDp) return;
    int previousWidth = measuredWidthDp;
    measuredWidthDp = w; measuredHeightDp = h;
    if (previousWidth > 0 && document == null && libraryPanel == null
        && (previousWidth >= 650) == (w >= 650) && libraryColumns(previousWidth) == libraryColumns(w)) return;
    if (preferences != null) onHostConfigurationChanged(getResources().getConfiguration());
  }
  private int libraryColumns(int width) {
    return Math.max(1, Math.min(5, (width - (width >= 650 ? 152 : 48)) / 180));
  }
  void observeState(Runnable observer) { stateObserver = observer; notifyState(); }
  private void notifyState() { if (stateObserver != null) stateObserver.run(); }
  void copyLegacyState(MainActivity shell) {
    shell.ink = ink; shell.document = document; shell.store = store; shell.io = io;
    shell.root = root; shell.libraryLoaded = libraryLoaded;
  }
  private static final int IMPORT_PDF = 10,
      IMPORT_IMAGE = 11,
      IMPORT_BACKUP = 12,
      IMPORT_OFFICE = 13,
      IMPORT_PDF_PAGES = 14,
      IMPORT_PAPER_IMAGE = 15,
      IMPORT_COVER_IMAGE = 16,
      REQUEST_SCREEN_CAPTURE = 17,
      IMPORT_IMAGE_NOTE = 18,
      IMPORT_TEMPLATE = 19,
      EXPORT_PDF = 20,
      EXPORT_PNG = 21,
      EXPORT_BACKUP = 22,
      IMPORT_DOCX_TEXT = 23;
  private static final int REQUEST_GALLERY = 40;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService thumbnailWorker = Executors.newSingleThreadExecutor();
  private final List<LibraryEntry> notebooks = new ArrayList<>();
  private LibraryCovers libraryCovers;
  private final java.util.Map<String, View> libraryCards = new java.util.HashMap<>();
  private final java.util.Map<String, LibraryEntry> libraryCardModels = new java.util.HashMap<>();
  private final List<View> libraryTabs = new ArrayList<>();
  private TextView libraryTitle;
  private ScrollView libraryScroll;
  private String libraryCardAppearance = "";
  private final List<View> toolViews = new ArrayList<>();
  private final List<PageThumb> pageThumbs = new ArrayList<>();
  private FrameLayout root;
  private boolean libraryLoaded;
  private FrameLayout writingToolbarHost;
  private FrameLayout toolOptionsHost;
  private View libraryPanel;
  private boolean rebuildingChrome;
  private int shareFormat = EXPORT_PDF;
  private NoteDocument creatingNote;
  private NoteDocument.Page paperPanelPage;
  private ScrollView activeToolPanelScroll;
  private Runnable activePenPanelRefresh;
  private Runnable activeToolPanelCommit;
  private View editorHeader;
  private boolean dismissPanelContact;
  private int imageEditGeneration;
  private HorizontalScrollView actionToolbarScroll;
  private LinearLayout cards, pageRail, folderRow;
  private HorizontalScrollView folderStrip;
  private TextView saveLabel, noteTitle, pageLabel, widthLabel, libraryCount;
  private IconView undoButton, redoButton;
  private View colorButton;
  private TextView pressureLabel, selectionButton;
  private final ArrayList<TextView> presetButtons = new ArrayList<>();
  private final PenPreset[] penPresets = new PenPreset[6];
  private String pressureMode = "standard";
  private float eraserWidth = 32;
  private String eraserMode = "stroke";
  private LinearLayout focusPanel;
  private FocusWritingView focusWriting;
  private TextView referenceBackButton, readingButton;
  private View inputModeButton;
  private IconView stylusModeIcon, fingerModeIcon;
  private View paperViewButton;
  private PageNavigation.Location focusReturnLocation;
  private final PageNavigation navigation = new PageNavigation();
  private InkView ink;
  private StudyTools studyTools;
  private IconView textBoxButton;
  private NoteDocument document, pendingExport;
  private DocumentStore store;
  private SharedPreferences preferences;
  private int libraryTab = 0, inkColor = 0xff272d3b, revision = 0, openRequest = 0;
  private int toolbarDensity = -1;
  private float inkWidth = 2.4f;
  private boolean dirty, pagesVisible, fingerDrawing;
  private String tool = "pen",
      query = "",
      folderFilter = "",
      imageTargetId,
      imagePageId,
      coverTargetId;
  private TextView learningReadout;
  private TextView penKeyReadout;
  private String lastPenKey = "等待按下小米笔侧键…";
  private boolean learningKeyActive;
  private String learnedDescriptor;
  private int learnedCode = -1;
  private String heldTool, heldDescriptor;
  private boolean heldMotionButton;
  private boolean inputSettingsChild;
  private int heldCode = -1;
  private String eraserReturnTool = "pen";
  private boolean noteCaptureBusy, suppressCaptureContact;
  private FrameLayout paperSurface, captureLayer;
  private Runnable pendingNoteCapture;
  private ImageTransformView imageTransform;
  private InkView.SelectionData objectClipboard;
  private IconView pasteButton;
  private boolean objectActionsPanel;
  private CaptureCropView captureCrop;
  private final StylusButtonState stylusButtons = new StylusButtonState();
  private int pendingSaves, documentSession;
  private final ConcurrentHashMap<String, NoteDocument> unsavedSnapshots =
      new ConcurrentHashMap<>();
  private String lastStoreWarning = "";
  private String captureDocumentId, capturePageId;
  private boolean captureReceiverRegistered;
  private final BroadcastReceiver screenCaptureReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (ScreenCaptureService.ACTION_CAPTURED.equals(intent.getAction())) {
            String path = intent.getStringExtra("path");
            if (!isOwnedMediaFile(path)) {
              toast("截屏文件无效，未插入笔记");
              return;
            }
            if (document == null || !document.id.equals(captureDocumentId)) {
              toast("截屏已保存，但原笔记已关闭；请重新插入图片");
              return;
            }
            int targetPage = document.findPageIndex(capturePageId);
            if (targetPage < 0) {
              toast("截屏已保存，但原页面已不存在");
              return;
            }
            if (document.currentPage != targetPage) switchPage(targetPage);
            closeToolPanel();
            addImageToCurrentPage(path);
            toast("屏幕截图已插入当前页");
          } else if (ScreenCaptureService.ACTION_FAILED.equals(intent.getAction())) {
            String reason = intent.getStringExtra("reason");
            toast(reason == null || reason.isEmpty() ? "截屏失败，请重试" : "截屏失败：" + reason);
          }
        }
      };
  private TextView hostBar;
  private View hostNavigation;
  private boolean hostResumed;
  private String hostLocation = "";
  private String reportedVisit = "";
  private final Runnable hostTick = new Runnable() {
    @Override public void run() {
      if (!visitVisible || disposed) return;
      refreshStudyStatus();
      if(studyTools!=null)studyTools.refreshHostAudioTime();
      String current = document == null ? "" : document.id + "/" + (ink == null ? "" : ink.getPage().id);
      if (!current.equals(hostLocation)) { reportHostVisit(); hostLocation = current; }
      if (hostBar != null) {
        boolean running = Boolean.TRUE.equals(NotesHost.state.get("running"));
        boolean onlyAudio = Boolean.TRUE.equals(NotesHost.state.get("recordingOnly"));
        String title = running ? "‹  返回 IKO · " + (NotesHost.recording() ? (onlyAudio?"笔记录音保存中":"课堂录音保存中") : onlyAudio ? "笔记录音已暂停保存" : "听写与提醒进行中") : "‹  返回 IKO";
        Object alert = NotesHost.state.get("alertText");
        if (alert instanceof String && !((String) alert).isEmpty()) title += "  ·  " + alert;
        if (!title.contentEquals(hostBar.getText())) hostBar.setText(title);
        Object level = NotesHost.state.get("alertLevel");
        hostBar.setTextColor("red".equals(level) ? 0xffdd3344 : "yellow".equals(level) ? 0xff926700 : Ui.TEXT);
      }
      drainNoteRecordings();
      main.postDelayed(this, 500);
    }
  };
  private final Runnable delayedSave = () -> saveCurrent(false);
  private final Runnable delayedThumbnails = () -> refreshCurrentThumbnail();

  private void create(Bundle state) {
    try { NotesLibraryMigration.recoverInterrupted(this); }
    catch(Exception error) { new android.app.AlertDialog.Builder(this).setTitle("笔记迁移尚未恢复").setMessage("原独立笔记和备份保留。请返回 IKO 保留完整备份后重试，不要清除数据。").setPositiveButton("返回 IKO",(dialog,which)->finish()).setOnCancelListener(dialog->finish()).show();return; }
    preferences = getSharedPreferences("iko_input", MODE_PRIVATE);
    if (getIntent().hasExtra("hostTheme")) {
      preferences.edit().putString("appearanceMode", getIntent().getStringExtra("hostTheme"))
          .putString("appearanceColor", getIntent().getStringExtra("hostPalette")).apply();
    }
    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplicationContext());
    loadAppearance();
    registerScreenCaptureReceiver();
    updateSystemBars();
    if (preferences.getInt("penInputPolicy", 0) < 1) {
      preferences.edit().putBoolean("fingerDrawing", false).putInt("penInputPolicy", 1).apply();
    }
    fingerDrawing = preferences.getBoolean("fingerDrawing", false);
    eraserWidth = Math.max(8, Math.min(120, preferences.getFloat("eraserWidth", 32)));
    loadPenPresets();
    eraserMode = preferences.getString("eraserMode", "stroke");
    store = new DocumentStore(this);
    root = new FrameLayout(this);
    root.setBackgroundColor(Ui.BG);
    LinearLayout hostFrame = Ui.column(this);
    hostFrame.setBackgroundColor(Ui.BG);
    if (!embedded && NotesHost.attached()) {
      TextView back = Ui.button(this, "‹  返回 IKO", false, this::finish);
      hostBar = back;
      hostFrame.addView(back, new LinearLayout.LayoutParams(-1, d(36)));
    }
    hostFrame.addView(root, new LinearLayout.LayoutParams(-1, 0, 1));
    if(!embedded && NotesHost.attached()) {
      hostNavigation=HostNavigation.create(activity);
      hostFrame.addView(hostNavigation,new LinearLayout.LayoutParams(-1,d(68)));
    }
    workspaceView.addView(hostFrame, new FrameLayout.LayoutParams(-1, -1));
    if (!embedded) root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else
            view.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
    showLibrary();
    if (state != null) {
      ArrayList<Integer> requests = state.getIntegerArrayList("activityRequests");
      if (requests != null) activityRequests.addAll(requests);
      requests = state.getIntegerArrayList("permissionRequests");
      if (requests != null) permissionRequests.addAll(requests);
      imageTargetId = state.getString("imageTarget");
      imagePageId = state.getString("imagePage");
      captureDocumentId = state.getString("captureDocument");
      capturePageId = state.getString("capturePage");
      if (state.getString("openNote") != null) openNote(state.getString("openNote"));
    }
    consumeIncomingIntent(getIntent());
    String requested = getIntent().getStringExtra("noteId");
    if (requested != null) openNote(requested);
  }

  public void acceptIntent(Intent intent) {
    if (intent == null || disposed || preferences == null) return;
    String oldStyle = getIntent().getStringExtra("hostStyle");
    String oldTheme = getIntent().getStringExtra("hostTheme");
    String oldPalette = getIntent().getStringExtra("hostPalette");
    boolean changedCompact=compactWorkspace!=intent.getBooleanExtra("compactWorkspace",false);
    compactWorkspace=intent.getBooleanExtra("compactWorkspace",false);
    setIntent(intent);
    if(changedCompact&&document!=null&&ink!=null){rebuildingChrome=true;buildEditor();rebuildingChrome=false;}

    if (intent.hasExtra("hostTheme")) preferences.edit()
        .putString("appearanceMode", intent.getStringExtra("hostTheme"))
        .putString("appearanceColor", intent.getStringExtra("hostPalette")).apply();
    if (!java.util.Objects.equals(oldStyle, intent.getStringExtra("hostStyle"))
        || !java.util.Objects.equals(oldTheme, intent.getStringExtra("hostTheme"))
        || !java.util.Objects.equals(oldPalette, intent.getStringExtra("hostPalette"))) applyAppearance();
    consumeIncomingIntent(intent);
    String requested=intent.getStringExtra("noteId");
    if(requested!=null) {
      if (document == null || !requested.equals(document.id)) openNote(requested);
      else { String page = intent.getStringExtra("pageId");
        if (page != null && ink != null) { int index = document.findPageIndex(page); if (index >= 0) {switchPage(index); if(intent.getBooleanExtra("fitRequestedPage",false))fitRequestedPage();} }
      }
    }
  }

  public void onHostConfigurationChanged(Configuration config) {
    if (disposed || preferences == null) return;
    cancelCapture();
    closeImageTransform();
    boolean systemNight =
        (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    if ("system".equals(preferences.getString("appearanceMode", "light"))
        && systemNight != Ui.dark) {
      applyAppearance();
      return;
    }
    if (document == null) {
      View overlay = libraryPanel;
      showLibrary();
      if (overlay != null) {
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        libraryPanel = overlay;
        View sheet = ((ViewGroup) overlay).getChildAt(0);
        ViewGroup.LayoutParams params = sheet.getLayoutParams();
        params.width = d(Math.min(760, Math.max(160, containerWidthDp() - 40)));
        sheet.setLayoutParams(params);
      }
    } else {
      if (compactHeader != (containerWidthDp() < 600)) {
        PageNavigation.Location location = ink.captureViewport();
        boolean focus = focusPanel != null && focusPanel.getVisibility() == View.VISIBLE;
        rebuildingChrome = true;
        buildEditor();
        rebuildingChrome = false;
        focusPanel.setVisibility(focus ? View.VISIBLE : View.GONE);
        restoreReadingPosition(location);
      }
      int density = toolbarDensity(containerWidthDp());
      if (writingToolbarHost != null && density != toolbarDensity) {
        writingToolbarHost.removeAllViews();
        writingToolbarHost.addView(buildWritingToolbar(), new FrameLayout.LayoutParams(-1, -1));
        refreshToolSelection();
        refreshPenControls();
        updateFooterDensity();
      }
      if (activeToolPanelScroll != null && toolOptionsHost != null) {
        activeToolPanelScroll.requestLayout();
        toolOptionsHost.requestLayout();
      }
      if (actionToolbarScroll != null) {
        ViewGroup.LayoutParams params = actionToolbarScroll.getLayoutParams();
        if (params != null) {
          params.width = 0;
          actionToolbarScroll.setLayoutParams(params);
        }
      }
      if (containerWidthDp() < 650 && pageRail != null) {
        pagesVisible = false;
        pageRail.setVisibility(View.GONE);
      }
      // InkView preserves its reading center and magnification across size changes.
    }
    if (libraryPanel != null) {
      View sheet = ((ViewGroup) libraryPanel).getChildAt(0);
      ViewGroup.LayoutParams params = sheet.getLayoutParams();
      params.width = d(Math.min(760, Math.max(160, containerWidthDp() - 40)));
      sheet.setLayoutParams(params);
    }
  }

  public void finish() { if (close != null) close.run(); }

  public void saveState(Bundle state) {
    state.putIntegerArrayList("activityRequests", new ArrayList<>(activityRequests));
    state.putIntegerArrayList("permissionRequests", new ArrayList<>(permissionRequests));
    if (document != null) state.putString("openNote", document.id);
    state.putString("imageTarget", imageTargetId);
    state.putString("imagePage", imagePageId);
    state.putString("captureDocument", captureDocumentId);
    state.putString("capturePage", capturePageId);
  }

  public void onHostResume() { hostResumed = true; updateVisibility(); }
  private void reportHostVisit() { updateVisibility(); notifyState(); }
  public void onHostPause() {
    hostResumed = false; updateVisibility(); suspendInput();
    saveReadingPosition(); saveCurrent(false);
  }
  public void onHostStop() { hostResumed = false; updateVisibility(); saveCurrent(false); }

  public void dispose() {
    if (disposed) return;
    onHostPause();
    // Queue the last committed snapshot before rejecting further executor work.
    saveCurrent(false);
    disposed = true;
    pendingImportChoice = null;
    NotesHost.visibility(this, activity, false, document, ink == null ? null : ink.getPage().id);
    openRequest++;
    main.removeCallbacksAndMessages(null);
    if (captureReceiverRegistered) {
      unregisterReceiver(screenCaptureReceiver);
      captureReceiverRegistered = false;
    }
    io.shutdown();
    thumbnailWorker.shutdown();
    if (libraryCovers != null) libraryCovers.close();
  }

  private void registerScreenCaptureReceiver() {
    IntentFilter filter = new IntentFilter(ScreenCaptureService.ACTION_CAPTURED);
    filter.addAction(ScreenCaptureService.ACTION_FAILED);
    if (android.os.Build.VERSION.SDK_INT >= 33)
      registerReceiver(
          screenCaptureReceiver,
          filter,
          getPackageName() + ".permission.RECEIVE_CAPTURE_EVENTS",
          null,
          Context.RECEIVER_NOT_EXPORTED);
    else registerLegacyScreenCaptureReceiver(filter);
    captureReceiverRegistered = true;
  }

  @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
  private void registerLegacyScreenCaptureReceiver(IntentFilter filter) {
    // API 29–32 has no exported-state overload; the signature-level permission still restricts
    // which senders can reach this receiver.
    registerReceiver(
        screenCaptureReceiver, filter, getPackageName() + ".permission.RECEIVE_CAPTURE_EVENTS", null);
  }

  private boolean isOwnedMediaFile(String path) {
    if (path == null) return false;
    try {
      File mediaRoot = new File(getFilesDir(), "media").getCanonicalFile();
      File image = new File(path).getCanonicalFile();
      return image.isFile() && image.getPath().startsWith(mediaRoot.getPath() + File.separator);
    } catch (IOException ignored) {
      return false;
    }
  }

  private void startScreenCapture() {
    if (document == null || ink == null) return;
    captureDocumentId = document.id;
    capturePageId = ink.getPage().id;
    android.media.projection.MediaProjectionManager manager =
        (android.media.projection.MediaProjectionManager)
            getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    if (manager == null) {
      toast("当前设备不支持屏幕截取");
      return;
    }
    toast("接下来由 Android 询问是否允许截取屏幕");
    try {
      startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
    } catch (android.content.ActivityNotFoundException error) {
      toast("设备没有可用的屏幕截取服务");
    }
  }

  private void captureNoteViewport() {
    if (document == null || ink == null || noteCaptureBusy || ink.getWidth() <= 0) return;
    if (toolOptionsHost != null && toolOptionsHost.getVisibility() == View.VISIBLE) {
      if (activeToolPanelCommit != null) activeToolPanelCommit.run();
      inputSettingsChild = false;
      closeToolPanel();
      noteCaptureBusy = true;
      pendingNoteCapture =
          () -> {
            pendingNoteCapture = null;
            noteCaptureBusy = false;
            if (!isFinishing() && !isDestroyed() && document != null) captureNoteViewport();
          };
      main.postDelayed(pendingNoteCapture, 160);
      return;
    }
    closeImageTransform();
    ink.finishInput();
    NoteDocument.Page sourcePage = ink.getPage();
    int sourceSession = documentSession;
    Bitmap image = NoteCapture.capture(ink);
    noteCaptureBusy = true;
    captureLayer = new FrameLayout(this);
    LinearLayout actions = Ui.row(this);
    Ui.pad(actions, 10, 4);
    actions.setBackground(Ui.border(this, Ui.SURFACE, 18, Ui.LINE));
    actions.addView(Ui.text(this, "框选截图", 13, Ui.TEXT), lp(88, 48));
    actions.addView(icon("close", "取消截图", this::cancelCapture), lp(48, 48));
    View confirm =
        icon(
            "check",
            "确认截图",
            () -> {
              if (captureCrop == null) return;
              if (sourceSession != documentSession || ink.getPage() != sourcePage) {
                cancelCapture();
                return;
              }
              Bitmap cropped = captureCrop.takeCrop();
              paperSurface.removeView(captureLayer);
              captureLayer = null;
              captureCrop = null;
              insertCapturedImage(cropped);
            });
    confirm.setEnabled(false);
    actions.addView(confirm, lp(48, 48));
    captureCrop = new CaptureCropView(this, image, confirm::setEnabled);
    captureLayer.addView(captureCrop, new FrameLayout.LayoutParams(-1, -1));
    FrameLayout.LayoutParams controls =
        new FrameLayout.LayoutParams(-2, d(56), Gravity.TOP | Gravity.END);
    controls.setMargins(d(16), d(12), d(16), 0);
    captureLayer.addView(actions, controls);
    paperSurface.addView(captureLayer, new FrameLayout.LayoutParams(-1, -1));
  }

  private void cancelCapture() {
    if (pendingNoteCapture != null) {
      main.removeCallbacks(pendingNoteCapture);
      pendingNoteCapture = null;
      noteCaptureBusy = false;
    }
    if (captureLayer == null) return;
    if (captureLayer.getParent() != null)
      ((ViewGroup) captureLayer.getParent()).removeView(captureLayer);
    captureLayer = null;
    captureCrop = null;
    noteCaptureBusy = false;
  }

  private void insertCapturedImage(Bitmap image) {
    float imageRatio = image.getHeight() / (float) image.getWidth();
    int session = documentSession;
    NoteDocument.Page targetPage = ink.getPage();
    PageNavigation.Location position = ink.captureViewport();
    noteCaptureBusy = true;
    executeContent(
        () -> {
          try {
            File file = NoteCapture.save(this, image);
            main.post(
                () -> {
                  noteCaptureBusy = false;
                  if (isFinishing()
                      || isDestroyed()
                      || ink == null
                      || session != documentSession
                      || ink.getPage() != targetPage) {
                    file.delete();
                    toast("页面已切换，截图已取消");
                    return;
                  }
                  NoteDocument.Element element =
                      createImageElement(targetPage, file.getAbsolutePath());
                  float ratio = imageRatio;
                  element.height = Math.min(targetPage.height * .6f, element.width * ratio);
                  element.width = element.height / ratio;
                  element.x =
                      Math.max(
                          0,
                          Math.min(
                              targetPage.width - element.width,
                              position.centerX - element.width / 2));
                  element.y =
                      Math.max(
                          0,
                          Math.min(
                              targetPage.height - element.height,
                              position.centerY - element.height / 2));
                  if (targetPage.infinite) {
                    element.x = position.centerX - element.width / 2;
                    element.y = position.centerY - element.height / 2;
                  }
                  ink.mutate(() -> targetPage.elements.add(element));
                  if (!targetPage.elements.contains(element)) file.delete();
                  else {
                    editImage(element);
                    toast("拖动图片移动 · 拖角点或双指缩放 · 点空白处完成");
                  }
                });
          } catch (IOException error) {
            main.post(
                () -> {
                  noteCaptureBusy = false;
                  error("应用内截图失败", error);
                });
          } finally {
            image.recycle();
          }
        });
  }

  private void editImage(NoteDocument.Element element) {
    closeImageTransform();
    final int generation = imageEditGeneration;
    if (ink == null || !ink.getPage().elements.contains(element)) return;
    final InkView surface = ink;
    final NoteDocument.Page page = ink.getPage();
    thumbnailWorker.execute(
        () -> {
          android.graphics.BitmapFactory.Options bounds =
              new android.graphics.BitmapFactory.Options();
          bounds.inJustDecodeBounds = true;
          android.graphics.BitmapFactory.decodeFile(element.path, bounds);
          android.graphics.BitmapFactory.Options options =
              new android.graphics.BitmapFactory.Options();
          options.inSampleSize = 1;
          while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048)
            options.inSampleSize *= 2;
          Bitmap image = android.graphics.BitmapFactory.decodeFile(element.path, options);
          main.post(
              () -> {
                if (image == null) return;
                if (isFinishing()
                    || isDestroyed()
                    || generation != imageEditGeneration
                    || ink != surface
                    || ink.getPage() != page
                    || !page.elements.contains(element)) {
                  image.recycle();
                  return;
                }
                closeImageTransform();
                ink.setEditingImage(element);
                imageTransform =
                    new ImageTransformView(this, ink, element, image, this::closeImageTransform);
                paperSurface.addView(imageTransform, new FrameLayout.LayoutParams(-1, -1));
                showImageActions();
              });
        });
  }

  private void closeImageTransform() {
    imageEditGeneration++;
    if (imageTransform == null) return;
    ImageTransformView closing = imageTransform;
    imageTransform = null;
    closing.finish();
    if (closing.getParent() != null) ((ViewGroup) closing.getParent()).removeView(closing);
    if (ink != null) ink.setEditingImage(null);
    if (objectActionsPanel) closeToolPanel();
  }

  private static String temporarySideAction(String action) {
    return "hold_eraser".equals(action)
        ? "eraser"
        : "eraser".equals(action) || "lasso".equals(action) ? action : "none";
  }

  private String sidePreferenceKey(String side) {
    String profile = preferences.getString("stylusProfile", "auto");
    return "auto".equals(profile) ? side : "penProfile:" + profile + ":" + side;
  }

  private String sideAction(String side) {
    return preferences.getString(sidePreferenceKey(side), preferences.getString(side, "eraser"));
  }

  private void updateSideButtonActions() {
    if (ink != null)
      ink.setSideButtonActions(
          temporarySideAction(sideAction("sidePrimary")),
          temporarySideAction(sideAction("sideSecondary")));
  }

  private String learnedMotionAction(String side, MotionEvent event) {
    String descriptor =
        event.getDevice() == null
            ? "virtual:" + event.getDeviceId()
            : event.getDevice().getDescriptor();
    return preferences.getString(
        mappingKey(descriptor, side.equals("sidePrimary") ? 308 : 309), null);
  }

  private String motionSideAction(String side, MotionEvent event) {
    String learned = learnedMotionAction(side, event);
    return learned == null ? sideAction(side) : learned;
  }

  private boolean routeStylusButton(MotionEvent event) {
    if (document == null || ink == null) return false;
    boolean stylus = false;
    for (int i = 0; i < event.getPointerCount(); i++)
      if (event.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS
          || event.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER) stylus = true;
    if (!stylus) return false;
    ink.setSideButtonActions(
        temporarySideAction(motionSideAction("sidePrimary", event)),
        temporarySideAction(motionSideAction("sideSecondary", event)));
    int mask = MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_STYLUS_SECONDARY;
    String eventDescriptor =
        event.getDevice() == null
            ? "virtual:" + event.getDeviceId()
            : event.getDevice().getDescriptor();
    int heldBit =
        heldCode == 308 ? MotionEvent.BUTTON_STYLUS_PRIMARY : MotionEvent.BUTTON_STYLUS_SECONDARY;
    if (heldTool != null
        && (heldCode == 308 || heldCode == 309)
        && eventDescriptor.equals(heldDescriptor)
        && (event.getButtonState() & heldBit) != 0) heldMotionButton = true;
    if (heldMotionButton
        && eventDescriptor.equals(heldDescriptor)
        && (event.getButtonState() & heldBit) == 0) {
      stylusButtons.key(event.getDeviceId(), heldBit, false);
      restoreHeldTool();
    }
    int rising = stylusButtons.update(event.getDeviceId(), event.getButtonState() & mask);
    boolean handled = false;
    if ((rising & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
      handled = performSideAction("sidePrimary", event);
    if ((rising & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0)
      handled = performSideAction("sideSecondary", event) || handled;
    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      restoreHeldTool();
      stylusButtons.reset();
    }
    return handled;
  }

  private boolean performSideAction(String preference, MotionEvent event) {
    String descriptor =
        event.getDevice() == null
            ? "virtual:" + event.getDeviceId()
            : event.getDevice().getDescriptor();
    String learned = learnedMotionAction(preference, event);
    String action = learned == null ? sideAction(preference) : learned;
    if (learned != null && action.equals("hold_eraser")) {
      heldMotionButton = true;
      if (heldTool == null) {
        heldTool = tool;
        heldCode = preference.equals("sidePrimary") ? 308 : 309;
        heldDescriptor = descriptor;
        selectTool("eraser", true);
      }
      return true;
    }
    if (learned == null && !"toggle_eraser".equals(action) && !"capture_note".equals(action))
      return false;
    if ("capture_note".equals(action)
        && (event.getActionMasked() == MotionEvent.ACTION_DOWN
            || event.getActionMasked() == MotionEvent.ACTION_MOVE)) suppressCaptureContact = true;
    if (!"none".equals(action)) performAction(action);
    return true;
  }

  boolean preDispatchGenericMotionEvent(MotionEvent event) {
    boolean handled = routeStylusButton(event);
    return handled;
  }

  boolean preDispatchTouchEvent(MotionEvent event) {
    routeStylusButton(event);
    if (suppressCaptureContact) {
      if (event.getActionMasked() == MotionEvent.ACTION_UP
          || event.getActionMasked() == MotionEvent.ACTION_CANCEL) suppressCaptureContact = false;
      return true;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN && document != null) {
      if (!touchInside(paperSurface, event)) {
        if (ink != null) ink.stopNavigation();
        if (!touchInside(toolOptionsHost, event)) closeImageTransform();
        if (captureLayer != null) cancelCapture();
      }
      if (captureLayer == null
          && toolOptionsHost != null
          && toolOptionsHost.getVisibility() == View.VISIBLE
          && toolOptionsHost.getChildCount() > 0
          && !(objectActionsPanel && touchInside(paperSurface, event))
          && !touchInside(toolOptionsHost, event)
          && !touchInside(editorHeader, event)
          && !touchInside(writingToolbarHost, event)) {
        if (studyTools != null && studyTools.ownsTouch(event))
          return false;
        boolean startText =
            "text".equals(tool)
                && studyTools != null
                && !studyTools.editingText()
                && touchInside(paperSurface, event);
        if (activeToolPanelCommit != null) activeToolPanelCommit.run();
        inputSettingsChild = false;
        learningKeyActive = false;
        learningReadout = null;
        closeToolPanel();
        dismissPanelContact = !startText;
      }
    }
    if (dismissPanelContact) {
      if (event.getActionMasked() == MotionEvent.ACTION_UP
          || event.getActionMasked() == MotionEvent.ACTION_CANCEL) dismissPanelContact = false;
      return true;
    }
    return false;
  }
  boolean protectPalmAt(MotionEvent event){
    return compactWorkspace&&!fingerDrawing&&document!=null&&ink!=null&&!"text".equals(tool)
        &&touchInside(paperSurface,event)&&(studyTools==null||!studyTools.ownsTouch(event));
  }

  private static boolean touchInside(View view, MotionEvent event) {
    if (view == null || !view.isShown()) return false;
    int[] location = new int[2];
    view.getLocationOnScreen(location);
    return event.getRawX() >= location[0]
        && event.getRawX() < location[0] + view.getWidth()
        && event.getRawY() >= location[1]
        && event.getRawY() < location[1] + view.getHeight();
  }

  public boolean handleBack() {
    if (!visitVisible) return false;
    handleBackNavigation();
    return true;
  }

  private void handleBackNavigation() {
    if (captureLayer != null || pendingNoteCapture != null) {
      cancelCapture();
      return;
    }
    boolean editedObjects = imageTransform != null || objectActionsPanel;
    closeImageTransform();
    if (editedObjects && ink != null) ink.finishSelectionEditing();
    if (libraryPanel != null) {
      if (inputSettingsChild) closeToolPanel();
      else dismissLibraryPanel();
      return;
    }
    if (document != null) {
      if (!editedObjects
          && toolOptionsHost != null
          && toolOptionsHost.getVisibility() == View.VISIBLE) {
        learningKeyActive = false;
        closeToolPanel();
        return;
      }
      if (focusPanel != null && focusPanel.getVisibility() == View.VISIBLE) toggleFocusWriting();
      if (studyTools != null) studyTools.onPause();
      navigation.switchDocument(null);
      saveReadingPosition();
      saveCurrent(false);
      document = null;
      ink = null;
      showLibrary();
    } else finish();
  }

  private int d(float value) {
    return Ui.dp(this, value);
  }

  private LinearLayout.LayoutParams lp(int w, int h) {
    return new LinearLayout.LayoutParams(w < 0 ? w : d(w), h < 0 ? h : d(h));
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
  }

  private void error(String title, Exception e) {
    if (isFinishing() || isDestroyed()) return;
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
        .setPositiveButton("知道了", null)
        .show();
  }

  private void present(View view) {
    libraryPanel = null;
    root.removeAllViews();
    root.addView(view, new FrameLayout.LayoutParams(-1, -1));
    Ui.tintControls(view);
    notifyState();
    // A bottom-tab page must be opaque on its first frame; keep editor transitions.
    if (!rebuildingChrome && !embedded) Ui.animateIn(view);
  }

  private IconView icon(String name, String description, Runnable action) {
    IconView v = new IconView(this, name);
    v.setContentDescription(description);
    v.setTooltipText(description);
    v.setOnClickListener(w -> action.run());
    Ui.press(v);
    v.setLayoutParams(lp(48, 48));
    return v;
  }

  private void spacer(LinearLayout row, int width) {
    View v = new View(this);
    row.addView(v, lp(width, 1));
  }

  private void loadAppearance() {
    String mode = preferences.getString("appearanceMode", "light");
    boolean night =
        "dark".equals(mode)
            || "system".equals(mode)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
    Ui.configure(
        night,
        preferences.getString("appearanceColor", "blue"),
        preferences.getString("toolbarStyle", "tint"));
    if(NotesHost.attached() && getIntent().hasExtra("hostStyle")) {
      try { Ui.configureHost(new org.json.JSONObject(getIntent().getStringExtra("hostStyle")));night=Ui.dark; }
      catch(org.json.JSONException ignored) { /* Keep a readable native theme if a host message is incomplete. */ }
    }
    setTheme(night ? R.style.AppTheme : R.style.AppThemeLight);
    if(NotesHost.attached()) {
      String palette=getIntent().getStringExtra("hostPalette");
      int hostTheme="forest".equals(palette)?(night?R.style.HostForestDark:R.style.HostForestLight):"plum".equals(palette)?(night?R.style.HostPlumDark:R.style.HostPlumLight):(night?R.style.HostOceanDark:R.style.HostOceanLight);
      setTheme(hostTheme);
    }
  }

  private void updateSystemBars() {
    if (embedded) return;
    activity.getWindow().setStatusBarColor(Ui.BG);
    activity.getWindow().setNavigationBarColor(Ui.BG);
    activity.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
    activity.getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            Ui.dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  }

  private void applyAppearance() {
    // A first external import can arrive before the host finishes syncing its theme.
    // Rebuild the choice in the new theme instead of silently discarding the URI.
    Runnable restoreImportChoice = pendingImportChoice;
    loadAppearance();
    updateSystemBars();
    root.setBackgroundColor(Ui.BG);
    if (document == null) showLibrary();
    else {
      PageNavigation.Location location = ink.captureViewport();
      boolean focus = focusPanel.getVisibility() == View.VISIBLE;
      rebuildingChrome = true;
      buildEditor();
      rebuildingChrome = false;
      focusPanel.setVisibility(focus ? View.VISIBLE : View.GONE);
      restoreReadingPosition(location);
    }
    if (restoreImportChoice != null) restoreImportChoice.run();
  }

  private void fitRequestedPage(){
    final InkView target=ink;final int session=documentSession,request=++fitRequest;
    target.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
      public boolean onPreDraw(){
        if(target.getWidth()<=0||target.getHeight()<=0)return true;
        target.getViewTreeObserver().removeOnPreDrawListener(this);
        if(!disposed&&ink==target&&session==documentSession&&request==fitRequest)target.fitPage();
        return true;
      }
    });
    target.invalidate();
  }
  private void drainNoteRecordings(){
    if(disposed||document==null||recordingInboxBusy||android.os.SystemClock.uptimeMillis()-recordingInboxChecked<1500)return;
    recordingInboxBusy=true;recordingInboxChecked=android.os.SystemClock.uptimeMillis();final String id=document.id;
    io.execute(()->{
      java.util.List<NoteDocument.Recording> clips;
      try{clips=NoteRecordingInbox.pending(this,id);}catch(Exception error){main.post(()->recordingInboxBusy=false);return;}
      main.post(()->{
        recordingInboxBusy=false;if(disposed||document==null||!id.equals(document.id)||clips.isEmpty())return;
        boolean added=false;
        for(NoteDocument.Recording clip:clips){boolean exists=false;for(NoteDocument.Recording old:document.recordings)if(old.id.equals(clip.id)){exists=true;break;}
          if(!exists&&document.recordings.size()<200){clip.title=document.nextRecordingTitle()+(clip.title.startsWith("中断")?"（中断原文件）":"");document.recordings.add(clip);added=true;}}
        if(added){markDirty();saveCurrent(false);refreshStudyStatus();if(studyTools!=null)studyTools.recordingsChanged();}
        // This runs behind the normal save queue. Never acknowledge an unsaved in-memory mutation.
        io.execute(()->{try{NoteRecordingInbox.acknowledge(this,store.load(id));}catch(Exception ignored){}});
      });
    });
  }
  private void saveReadingPosition() {
    if (document != null && ink != null && ink.getWidth() > 0 && ink.getHeight() > 0)
      ReadingPositionStore.save(preferences, document.id, ink.captureViewport());
  }

  private void restoreReadingPosition(PageNavigation.Location location) {
    InkView target = ink;
    int session = documentSession;
    target.post(
        () -> {
          if (ink == target && session == documentSession) target.restoreViewport(location);
        });
  }

  private void showAppearance() {
    if(NotesHost.attached()) {
      LinearLayout content=Ui.column(this);
      content.addView(Ui.text(this,"笔记界面跟随 IKO 的明暗与主题配色。纸张、墨水和封面保持各自设置。",14,Ui.TEXT));
      content.addView(Ui.button(this,"前往 IKO 外观设置",true,()->{
        NotesHost.Client client=NotesHost.client;
        if(client!=null)client.command(activity,"selectTab",java.util.Collections.singletonMap("index",4),result->{if(result.get("error")==null)finish();});
      }));
      if(document!=null)showToolPanel("外观与主题",content,()->{});
      else showLibraryPanel("外观与主题",content,"完成",this::dismissLibraryPanel);
      return;
    }
    AppearanceView picker =
        new AppearanceView(
            this,
            preferences.getString("appearanceMode", "light"),
            preferences.getString("appearanceColor", "blue"),
            preferences.getString("toolbarStyle", "tint"),
            (mode, palette) -> {
              preferences
                  .edit()
                  .putString("appearanceMode", mode)
                  .putString("appearanceColor", palette)
                  .apply();
              applyAppearance();
              showAppearance();
            },
            style -> {
              preferences.edit().putString("toolbarStyle", style).apply();
              applyAppearance();
              showAppearance();
            });
    if (document != null) showToolPanel("外观与主题", picker, () -> {});
    else showLibraryPanel("外观与主题", picker, "完成", this::dismissLibraryPanel);
  }

  private void dismissLibraryPanel() {
    if (libraryPanel == null) return;
    pendingImportChoice = null;
    inputSettingsChild = false;
    learningKeyActive = false;
    learningReadout = null;
    root.removeView(libraryPanel);
    libraryPanel = null;
    ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(root.getWindowToken(), 0);
  }

  private void showLibraryPanel(String title, View content, String action, Runnable done) {
    dismissLibraryPanel();
    FrameLayout backdrop = new FrameLayout(this);
    backdrop.setBackgroundColor(Ui.dark ? 0x99000000 : 0x55344168);
    backdrop.setOnClickListener(v -> dismissLibraryPanel());
    LinearLayout sheet = Ui.column(this);
    sheet.setBackground(Ui.bg(this, Ui.SURFACE, 26));
    sheet.setElevation(d(10));
    sheet.setOnClickListener(v -> {});
    Ui.pad(sheet, 20, 12);
    LinearLayout header = Ui.row(this);
    header.addView(Ui.label(this, title, 19), new LinearLayout.LayoutParams(0, d(48), 1));
    header.addView(icon("close", "关闭" + title, this::dismissLibraryPanel));
    sheet.addView(header, lp(-1, 48));
    ScrollView scroll =
        new ScrollView(this) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int cap =
                d(
                    Math.max(
                        120,
                        Math.min(470, Math.max(96, containerHeightDp() - 220))));
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
          }
        };
    scroll.setFillViewport(false);
    if (content instanceof ScrollView) {
      FrameLayout viewport =
          new FrameLayout(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
              int cap =
                  d(
                      Math.max(
                          120,
                          Math.min(470, Math.max(96, containerHeightDp() - 220))));
              super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY));
            }
          };
      viewport.addView(content, new FrameLayout.LayoutParams(-1, -1));
      sheet.addView(viewport, lp(-1, -2));
    } else {
      scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
      sheet.addView(scroll, lp(-1, -2));
    }
    View finish = Ui.button(this, action, true, done);
    LinearLayout.LayoutParams finishParams = lp(-1, 48);
    finishParams.topMargin = d(12);
    sheet.addView(finish, finishParams);
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(
            d(Math.min(760, Math.max(160, containerWidthDp() - 40))),
            -2,
            Gravity.CENTER);
    backdrop.addView(sheet, params);
    root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
    Ui.tintControls(sheet);
    libraryPanel = backdrop;
    sheet.setContentDescription("面板：" + title);
    Ui.animateIn(sheet);
  }

  private ImageView ikoLogo() {
    if(NotesHost.attached() && getIntent().hasExtra("hostStyle")) {
      try {
        String mode=new org.json.JSONObject(getIntent().getStringExtra("hostStyle")).optString("iconMode","system");
        return new IkoLogoView(this,mode,"dark".equals(mode));
      }catch(org.json.JSONException ignored) { /* Fall through to the local logo. */ }
    }
    return new IkoLogoView(this, preferences.getString("appearanceColor", "blue"), Ui.dark);
  }

  private void showLibrary() {
    if(hostBar!=null)hostBar.setVisibility(View.GONE);
    if(hostNavigation!=null)hostNavigation.setVisibility(View.VISIBLE);
    reportHostVisit();
    activePenPanelRefresh = null;
    if (libraryCovers == null)
      libraryCovers =
          new LibraryCovers(new File(getCacheDir(), "library-covers"), this::readDocument);
    String appearance = Ui.BG + ":" + Ui.ACCENT + ":" + Ui.TEXT;
    if (!appearance.equals(libraryCardAppearance)) {
      libraryCards.clear();
      libraryCardModels.clear();
      libraryCardAppearance = appearance;
    }
    libraryTabs.clear();
    LinearLayout shell = Ui.row(this);
    shell.setGravity(Gravity.TOP);
    if (containerWidthDp() >= 650) {
      LinearLayout rail = Ui.column(this);
      rail.setGravity(Gravity.CENTER_HORIZONTAL);
      Ui.pad(rail, 12, 24);
      rail.setBackgroundColor(Ui.BG);
      rail.addView(ikoLogo(), lp(72, 72));
      String[] names = {"全部笔记", "最近打开", "收藏笔记", "回收站"},
          icons = {"library", "recent", "star", "trash"};
      int[] tabs = {0, 1, 3, 2};
      for (int i = 0; i < names.length; i++) {
        final int tab = tabs[i];
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER);
        IconView iv = new IconView(this, icons[i]);
        iv.tint(libraryTab == tab ? Ui.ACCENT : Ui.MUTED);
        item.addView(iv, lp(48, 42));
        TextView label = Ui.text(this, names[i], 11, libraryTab == tab ? Ui.ACCENT : Ui.MUTED);
        label.setGravity(Gravity.CENTER);
        item.addView(label, lp(-1, 24));
        item.setContentDescription(names[i]);
        item.setTag(tab);
        libraryTabs.add(item);
        item.setBackground(Ui.bg(this, libraryTab == tab ? Ui.SURFACE : Ui.BG, 15));
        LinearLayout.LayoutParams itemParams = lp(76, 76);
        itemParams.topMargin = d(14);
        rail.addView(item, itemParams);
        item.setOnClickListener(
            v -> {
              switchLibraryTab(tab);
            });
      }
      rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
      if (!NotesHost.attached()) rail.addView(icon("palette", "外观与主题", this::showAppearance), lp(52, 52));
      rail.addView(icon("settings", "手写笔与快捷键设置", this::showInputSettings), lp(52, 52));
      shell.addView(rail, lp(104, -1));
    }
    LinearLayout body = Ui.column(this);
    Ui.pad(body, 24, 22);
    shell.addView(body, new LinearLayout.LayoutParams(0, -1, 1));
    LinearLayout heading = Ui.row(this);
    if (containerWidthDp() < 650) {
      heading.addView(ikoLogo(), lp(44, 44));
      spacer(heading, 12);
    }
    TextView title =
        Ui.label(
            this,
            libraryTab == 2 ? "回收站" : libraryTab == 1 ? "最近打开" : libraryTab == 3 ? "收藏笔记" : "我的笔记",
            28);
    title.setSingleLine(true);
    libraryTitle = title;
    title.setEllipsize(TextUtils.TruncateAt.END);
    heading.addView(title, new LinearLayout.LayoutParams(0, d(56), 1));
    if (!NotesHost.attached()) heading.addView(icon("palette", "外观与主题", this::showAppearance));
    heading.addView(icon("paper", "手账模板库", this::showTemplateLibrary));
    heading.addView(icon("import", "导入 PDF 或笔记备份", this::chooseImport));
    spacer(heading, 8);
    heading.addView(Ui.button(this, "＋ 新建", true, this::createNote), lp(-2, 48));
    body.addView(heading, lp(-1, 62));
    libraryCount = Ui.text(this, "给今天的想法，一点纸上的空间。", 13, Ui.MUTED);
    body.addView(libraryCount, lp(-1, 34));
    LinearLayout searchRow = Ui.row(this);
    EditText search = new EditText(this);
    search.setSingleLine();
    search.setTextColor(Ui.TEXT);
    search.setTextSize(14);
    search.setHintTextColor(Ui.MUTED);
    search.setHint("搜索笔记、标签或课程");
    search.setText(query);
    Ui.pad(search, 18, 4);
    search.setBackground(Ui.border(this, Ui.SURFACE, 15, Ui.LINE));
    search.setContentDescription("搜索笔记");
    searchRow.addView(search, new LinearLayout.LayoutParams(0, d(48), 1));
    if (containerWidthDp() < 650) {
      spacer(searchRow, 8);
      searchRow.addView(
          icon(
              "more",
              "书库筛选与设置",
              () ->
                  new AlertDialog.Builder(this)
                      .setItems(
                          new String[] {"全部笔记", "最近打开", "收藏笔记", "回收站", "输入设置"},
                          (di, w) -> {
                            if (w == 4) showInputSettings();
                            else {
                              switchLibraryTab(new int[] {0, 1, 3, 2}[w]);
                            }
                          })
                      .show()));
    }
    body.addView(searchRow, lp(-1, 56));
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            query = s.toString();
            renderCards();
          }

          public void afterTextChanged(Editable e) {}
        });
    folderStrip = new HorizontalScrollView(this);
    folderStrip.setHorizontalScrollBarEnabled(false);
    folderRow = Ui.row(this);
    folderStrip.addView(folderRow);
    body.addView(folderStrip, lp(-1, 56));
    ScrollView scroll = new ScrollView(this);
    libraryScroll = scroll;
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setPadding(0, d(20), 0, d(20));
    cards = Ui.column(this);
    scroll.addView(cards, new ScrollView.LayoutParams(-1, -2));
    body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    present(shell);
    renderCards();
    if (libraryIndexLoading || libraryLoaded && !libraryIndexDirty) return;
    libraryIndexLoading = true;
    final int indexVersion = libraryRevision;
    io.execute(
        () -> {
          try {
            try {NotesStorage.prune(this,System.currentTimeMillis());}
            catch(Exception ignored){/* Keep failed trash files and the readable library. */}
            List<LibraryEntry> loaded = store.listEntries();
            String warning = String.join("\n", store.getWarnings());
            for (NoteDocument pending : unsavedSnapshots.values()) {
              loaded.removeIf(n -> n.id.equals(pending.id));
              loaded.add(LibraryEntry.from(pending, 0, 0));
            }
            main.post(
                () -> {
                  libraryIndexLoading = false;
                  if (isFinishing() || isDestroyed()) return;
                  notebooks.clear();
                  notebooks.addAll(loaded);
                  libraryLoaded = true; libraryIndexDirty = libraryRevision != indexVersion; notifyState();
                  Set<String> ids = new HashSet<>();
                  for (LibraryEntry entry : loaded) ids.add(entry.id);
                  libraryCards.keySet().retainAll(ids);
                  libraryCardModels.keySet().retainAll(ids);
                  if (document == null) renderCards();
                  if (!warning.isEmpty() && !warning.equals(lastStoreWarning)) {
                    lastStoreWarning = warning;
                    new AlertDialog.Builder(this)
                        .setTitle("部分笔记需要恢复")
                        .setMessage(warning + "\n\n其余笔记仍可使用，原文件已保留。")
                        .setPositiveButton("知道了", null)
                        .show();
                  }
                });
          } catch (IOException e) {
            main.post(() -> { libraryIndexLoading = false; libraryLoaded = true; notifyState(); error("书库读取失败", e); });
          }
        });
  }

  private View folderChip(String label, String filter) {
    TextView c = Ui.text(this, label, 12, folderFilter.equals(filter) ? Ui.ACCENT : Ui.MUTED);
    c.setGravity(Gravity.CENTER);
    Ui.pad(c, 14, 4);
    c.setBackground(Ui.bg(this, folderFilter.equals(filter) ? Ui.FLOAT : Ui.SURFACE, 12));
    LinearLayout.LayoutParams p = lp(-2, 48);
    p.rightMargin = d(8);
    folderChipAction(c, filter);
    c.setLayoutParams(p);
    return c;
  }

  private void switchLibraryTab(int tab) {
    if (libraryTab == tab && folderFilter.isEmpty()) return;
    libraryTab = tab;
    folderFilter = "";
    if (libraryTitle != null)
      libraryTitle.setText(tab == 2 ? "回收站" : tab == 1 ? "最近打开" : tab == 3 ? "收藏笔记" : "我的笔记");
    for (View view : libraryTabs) {
      boolean selected = ((Integer) view.getTag()) == tab;
      view.setBackground(Ui.bg(this, selected ? Ui.SURFACE : Ui.BG, 15));
      ViewGroup group = (ViewGroup) view;
      ((IconView) group.getChildAt(0)).tint(selected ? Ui.ACCENT : Ui.MUTED);
      ((TextView) group.getChildAt(1)).setTextColor(selected ? Ui.ACCENT : Ui.MUTED);
    }
    renderCards();
    if (libraryScroll != null) libraryScroll.scrollTo(0, 0);
  }

  private void folderChipAction(View c, String filter) {
    c.setOnClickListener(
        v -> {
          folderFilter = filter;
          renderCards();
        });
  }

  private void renderCards() {
    if (cards == null || document != null) return;
    cards.removeAllViews();
    if (!libraryLoaded) {
      if (libraryCount != null) libraryCount.setText("正在读取本机书库…");
      LinearLayout loading = Ui.column(this);
      loading.setGravity(Gravity.CENTER);
      Ui.pad(loading, 20, 40);
      loading.addView(new android.widget.ProgressBar(this), lp(40, 40));
      loading.addView(Ui.text(this, "正在读取本机笔记…", 14, Ui.MUTED), lp(-2, 48));
      cards.addView(loading, lp(-1, -2));
      return;
    }
    List<LibraryEntry> shown = new ArrayList<>();
    String term = query.toLowerCase(Locale.ROOT).trim();
    if (folderRow != null) {
      folderRow.removeAllViews();
      Set<String> folderNames = new LinkedHashSet<>();
      for (LibraryEntry n : notebooks)
        if (n.trashed == (libraryTab == 2) && n.folder != null && !n.folder.trim().isEmpty())
          folderNames.add(n.folder);
      folderStrip.setVisibility(folderNames.isEmpty() ? View.GONE : View.VISIBLE);
      if (!folderNames.isEmpty()) {
        folderRow.addView(folderChip("全部目录", ""));
        for (String folder : folderNames) folderRow.addView(folderChip(folder, folder));
      }
    }
    for (LibraryEntry n : notebooks) {
      if (n.trashed != (libraryTab == 2)) continue;
      if (libraryTab == 3 && !n.tags.contains("收藏")) continue;
      if (libraryTab == 1 && preferences.getLong("opened:" + n.id, 0) == 0) continue;
      if (!folderFilter.isEmpty() && !folderFilter.equals(n.folder)) continue;
      String hay = n.title + " " + n.folder + " " + String.join(" ", n.tags);
      if (!term.isEmpty() && !hay.toLowerCase(Locale.ROOT).contains(term)) continue;
      shown.add(n);
    }
    Collections.sort(
        shown,
        (a, b) ->
            libraryTab == 1
                ? Long.compare(
                    preferences.getLong("opened:" + b.id, 0),
                    preferences.getLong("opened:" + a.id, 0))
                : Long.compare(b.modifiedAt, a.modifiedAt));
    if (libraryTab == 1 && shown.size() > 12) shown = new ArrayList<>(shown.subList(0, 12));
    if (libraryCount != null)
      libraryCount.setText(shown.size() + " 本笔记 · " + (libraryTab == 2 ? "7天后自动删除 · 长按可恢复或永久删除" : "仅保存在此设备"));
    if (shown.isEmpty()) {
      LinearLayout empty = Ui.column(this);
      empty.setGravity(Gravity.CENTER);
      Ui.pad(empty, 20, 64);
      IconView iv = new IconView(this, libraryTab == 2 ? "trash" : "paper");
      iv.tint(Ui.ACCENT);
      empty.addView(iv, lp(68, 68));
      TextView t =
          Ui.label(
              this, term.isEmpty() ? (libraryTab == 2 ? "回收站很干净" : "给下一个想法留一页") : "没有找到匹配的笔记", 20);
      t.setGravity(Gravity.CENTER);
      empty.addView(t, lp(-1, 52));
      TextView sub =
          Ui.text(this, libraryTab == 2 ? "删除的笔记可以在这里恢复。" : "手写、画图，或从一份 PDF 开始。", 14, Ui.MUTED);
      sub.setGravity(Gravity.CENTER);
      empty.addView(sub, lp(-1, 42));
      if (libraryTab != 2 && term.isEmpty())
        empty.addView(Ui.button(this, "创建第一本笔记", true, this::createNote), lp(-2, 48));
      cards.addView(empty, lp(-1, -2));
      return;
    }
    int columns = libraryColumns(containerWidthDp());
    if (libraryTab == 0 && term.isEmpty() && folderFilter.isEmpty()) {
      LibraryEntry last = null;
      long lastOpened = 0;
      for (LibraryEntry n : shown) {
        long opened = preferences.getLong("opened:" + n.id, 0);
        if (opened > lastOpened) {
          lastOpened = opened;
          last = n;
        }
      }
      if (last != null) {
        final String id = last.id;
        LinearLayout resume = Ui.row(this);
        Ui.pad(resume, 16, 10);
        resume.setBackground(Ui.bg(this, Ui.SELECTED, 20));
        IconView glyph = new IconView(this, "recent");
        glyph.tint(Ui.ACCENT);
        resume.addView(glyph, lp(48, 48));
        LinearLayout info = Ui.column(this);
        info.addView(Ui.text(this, "继续上次", 11, Ui.ACCENT), lp(-1, 22));
        TextView lastTitle = Ui.label(this, last.title, 15);
        lastTitle.setSingleLine();
        lastTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(lastTitle, lp(-1, 28));
        resume.addView(info, new LinearLayout.LayoutParams(0, d(50), 1));
        resume.addView(new IconView(this, "next"), lp(48, 48));
        resume.setContentDescription("继续上次：" + last.title);
        resume.setOnClickListener(v -> openNote(id));
        Ui.press(resume);
        LinearLayout.LayoutParams params = lp(-1, 72);
        params.bottomMargin = d(24);
        cards.addView(resume, params);
      }
    }
    for (int start = 0; start < shown.size(); start += columns) {
      LinearLayout row = Ui.row(this);
      row.setGravity(Gravity.TOP);
      for (int col = 0; col < columns; col++) {
        int index = start + col;
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.rightMargin = col == columns - 1 ? 0 : d(16);
        cp.bottomMargin = d(22);
        if (index >= shown.size()) {
          row.addView(new View(this), cp);
          continue;
        }
        row.addView(noteCard(shown.get(index)), cp);
      }
      cards.addView(row, lp(-1, -2));
    }
  }

  private View noteCard(LibraryEntry n) {
    View old = libraryCards.get(n.id);
    LibraryEntry oldModel = libraryCardModels.get(n.id);
    if (old != null && oldModel != null && oldModel.sameCard(n)) {
      if (old.getParent() != null) ((ViewGroup) old.getParent()).removeView(old);
      return old;
    }
    LinearLayout card = Ui.column(this);
    card.setBackground(Ui.border(this, Ui.SURFACE, 18, Ui.LINE));
    card.setClipToOutline(true);
    BookCover cover = new BookCover(n);
    card.addView(cover, lp(-1, 190));
    LinearLayout text = Ui.column(this);
    Ui.pad(text, 16, 10);
    LinearLayout titleRow = Ui.row(this);
    TextView t = Ui.label(this, n.title, 16);
    t.setSingleLine();
    t.setEllipsize(TextUtils.TruncateAt.END);
    titleRow.addView(t, new LinearLayout.LayoutParams(0, d(48), 1));
    IconView more = icon("more", "管理 " + n.title, () -> noteMenu(n));
    titleRow.addView(more, lp(48, 48));
    text.addView(titleRow, lp(-1, 48));
    String info =
        n.pageCount
            + " 页 · "
            + new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(new Date(n.modifiedAt));
    LinearLayout metadata = Ui.row(this);
    TextView modified = Ui.text(this, info, 10, Ui.MUTED);
    modified.setSingleLine();
    modified.setEllipsize(TextUtils.TruncateAt.END);
    metadata.addView(modified, new LinearLayout.LayoutParams(0, d(26), 1));
    TextView type = Ui.text(this, NoteTypes.label(n.infinite), 10, Ui.ACCENT);
    type.setGravity(Gravity.CENTER);
    type.setSingleLine();
    type.setBackground(Ui.bg(this, Ui.SELECTED, 7));
    type.setContentDescription("笔记类型：" + NoteTypes.label(n.infinite));
    metadata.addView(type, lp(60, 24));
    text.addView(metadata, lp(-1, 28));
    if (!n.tags.isEmpty() || (n.folder != null && !n.folder.isEmpty())) {
      String detail =
          (n.folder == null || n.folder.isEmpty() ? "" : n.folder + "   ")
              + String.join(" · ", n.tags);
      TextView tags = Ui.text(this, detail, 11, Ui.ACCENT);
      tags.setSingleLine();
      tags.setEllipsize(TextUtils.TruncateAt.END);
      text.addView(tags, lp(-1, 24));
    }
    card.addView(text, lp(-1, -2));
    card.setContentDescription(n.title + "，" + n.pageCount + " 页");
    card.setTag(n.id);
    card.setOnClickListener(
        v -> {
          if (n.trashed) noteMenu(n);
          else openNote(n.id);
        });
    card.setOnLongClickListener(
        v -> {
          noteMenu(n);
          return true;
        });
    Ui.press(card);
    libraryCards.put(n.id, card);
    libraryCardModels.put(n.id, n);
    return card;
  }

  private final class BookCover extends View {
    final LibraryEntry note;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    LibraryCovers.Request coverRequest;
    final CoverArtView artwork;

    BookCover(LibraryEntry n) {
      super(NotesWorkspace.this);
      note = n;
      artwork = new CoverArtView(NotesWorkspace.this, n.coverPattern, CoverCatalog.color(n.cover));
    }

    @Override
    protected void onDraw(Canvas c) {
      super.onDraw(c);
      LibraryCovers.Images images = libraryCovers.peek(note.id);
      Bitmap coverImage = images == null || note.coverImagePath.isEmpty() ? null : images.cover,
          preview = images == null ? null : images.preview;
      if ((note.hasPreview || !note.coverImagePath.isEmpty())
          && (images == null || !images.version.equals(note.version()))
          && coverRequest == null)
        coverRequest =
            libraryCovers.request(
                note,
                result -> {
                  coverRequest = null;
                  invalidate();
                });
      if (coverImage != null) {
        c.drawBitmap(coverImage, null, new RectF(0, 0, getWidth(), getHeight()), p);
        p.setStyle(Paint.Style.FILL);
        p.setShader(
            new android.graphics.LinearGradient(
                0,
                getHeight() * .48f,
                0,
                getHeight(),
                0x000d1118,
                0x990d1118,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, getHeight() * .48f, getWidth(), getHeight(), p);
        p.setShader(null);
      } else {
        artwork.layout(0, 0, getWidth(), getHeight());
        artwork.draw(c);
      }
      p.setStyle(Paint.Style.FILL);
      if (preview != null && note.hasPreview) {
        float w = getWidth() * (note.infinite ? .80f : .57f),
            h = getHeight() * (note.infinite ? .56f : .75f);
        float s = Math.min(w / preview.getWidth(), h / preview.getHeight());
        float pw = preview.getWidth() * s, ph = preview.getHeight() * s;
        c.save();
        c.translate(
            note.infinite ? (getWidth() - pw) / 2 : getWidth() * .34f,
            note.infinite ? d(42) : d(20));
        if (!note.infinite) c.rotate(7);
        p.setColor(0x22000000);
        c.drawRoundRect(new RectF(d(3), d(4), pw + d(3), ph + d(4)), d(3), d(3), p);
        p.setAlpha(255);
        c.drawBitmap(preview, null, new RectF(0, 0, pw, ph), p);
        c.restore();
      }
      p.setColor(coverImage == null ? 0xff202733 : Color.WHITE);
      p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      p.setTextSize(d(11));
      c.drawText("iko · notes", d(26), d(28), p);
      p.setTextSize(d(15));
      String title = note.title;
      while (p.measureText(title) > getWidth() - d(58) && title.length() > 1)
        title = title.substring(0, title.length() - 1);
      c.drawText(title, d(30), getHeight() - d(35), p);
    }

    @Override
    protected void onDetachedFromWindow() {
      if (coverRequest != null) {
        coverRequest.cancel();
        coverRequest = null;
      }
      super.onDetachedFromWindow();
    }
  }

  private void createNote() {
    creatingNote = NoteDocument.create("未命名笔记");
    creatingNote.coverPattern = "clouds";
    creatingNote.cover = "#A8BCE5";
    showNoteComposer();
  }

  private void showNoteComposer() {
    NoteDocument draft = creatingNote;
    LinearLayout content = Ui.column(this);
    EditText name = new EditText(this);
    name.setSingleLine();
    name.setText(draft.title);
    name.setTextColor(Ui.TEXT);
    name.setHintTextColor(Ui.MUTED);
    name.setTextSize(18);
    name.setSelectAllOnFocus(true);
    name.setContentDescription("新笔记名称");
    Ui.pad(name, 14, 4);
    name.setBackground(Ui.bg(this, Ui.FLOAT, 16));
    content.addView(name, lp(-1, 52));
    LinearLayout typeRow = Ui.row(this);
    TextView paged = Ui.button(this, "分页笔记", !draft.pages.get(0).infinite, () -> {});
    TextView infinite = Ui.button(this, "无边记", draft.pages.get(0).infinite, () -> {});
    paged.setContentDescription("新建类型：分页笔记");
    infinite.setContentDescription("新建类型：无边记");
    TextView typeHint = Ui.text(this, "", 12, Ui.MUTED);
    Runnable refreshType =
        () -> {
          boolean free = draft.pages.get(0).infinite;
          paged.setBackground(Ui.bg(this, free ? Ui.FLOAT : Ui.SELECTED, 14));
          paged.setTextColor(free ? Ui.MUTED : Ui.ACCENT);
          infinite.setBackground(Ui.bg(this, free ? Ui.SELECTED : Ui.FLOAT, 14));
          infinite.setTextColor(free ? Ui.ACCENT : Ui.MUTED);
          typeHint.setText(free ? "向四周自由书写 · 双指缩放 · 本地保存" : "连续纵向翻阅 · 保留独立纸页");
        };
    paged.setOnClickListener(
        v -> {
          draft.pages.get(0).infinite = false;
          refreshType.run();
        });
    infinite.setOnClickListener(
        v -> {
          draft.pages.get(0).infinite = true;
          draft.pages.get(0).paper = "dotted";
          refreshType.run();
        });
    typeRow.addView(paged, new LinearLayout.LayoutParams(0, d(48), 1));
    typeRow.addView(infinite, new LinearLayout.LayoutParams(0, d(48), 1));
    content.addView(typeRow, lp(-1, 56));
    content.addView(typeHint, lp(-1, 28));
    refreshType.run();
    LinearLayout tabs = Ui.row(this);
    FrameLayout gallery = new FrameLayout(this);
    Runnable cover =
        () -> {
          gallery.removeAllViews();
          gallery.addView(
              new CoverPickerView(
                  this,
                  draft,
                  Runnable::run,
                  () -> {
                    draft.title = name.getText().toString();
                    dismissLibraryPanel();
                    startCoverImageImport(draft);
                  }));
        };
    Runnable paper =
        () -> {
          gallery.removeAllViews();
          gallery.addView(
              new PaperPickerView(
                  this,
                  draft.pages.get(0),
                  Runnable::run,
                  () -> {
                    draft.title = name.getText().toString();
                    dismissLibraryPanel();
                    creatingNote = null;
                    openDocument(draft);
                    markDirty();
                    startPdfPageImport();
                  },
                  () -> {
                    draft.title = name.getText().toString();
                    dismissLibraryPanel();
                    creatingNote = null;
                    openDocument(draft);
                    markDirty();
                    startPaperImageImport();
                  }));
        };
    TextView covers = Ui.button(this, "封面与配色", true, cover);
    TextView papers = Ui.button(this, "纸张与尺寸", false, paper);
    covers.setOnClickListener(
        v -> {
          cover.run();
          covers.setBackground(Ui.bg(this, Ui.ACCENT, 16));
          covers.setTextColor(Ui.ON_ACCENT);
          papers.setBackground(Ui.bg(this, Ui.FLOAT, 16));
          papers.setTextColor(Ui.TEXT);
        });
    papers.setOnClickListener(
        v -> {
          paper.run();
          papers.setBackground(Ui.bg(this, Ui.ACCENT, 16));
          papers.setTextColor(Ui.ON_ACCENT);
          covers.setBackground(Ui.bg(this, Ui.FLOAT, 16));
          covers.setTextColor(Ui.TEXT);
        });
    LinearLayout.LayoutParams tab = new LinearLayout.LayoutParams(0, d(48), 1);
    tab.setMargins(0, d(12), d(8), d(6));
    tabs.addView(covers, tab);
    tabs.addView(papers, new LinearLayout.LayoutParams(0, d(48), 1));
    content.addView(tabs, lp(-1, 66));
    content.addView(gallery, lp(-1, -2));
    cover.run();
    showLibraryPanel(
        "新建笔记",
        content,
        "创建笔记",
        () -> {
          draft.title = name.getText().toString().trim();
          if (draft.title.isEmpty()) draft.title = "未命名笔记";
          dismissLibraryPanel();
          creatingNote = null;
          openDocument(draft);
          markDirty();
        });
  }

  private NoteDocument readDocument(String id) throws IOException {
    NoteDocument pending = unsavedSnapshots.get(id);
    return pending == null ? store.load(id) : pending.copy();
  }

  private void openNote(String id) {
    saveCurrent(false);
    final int ticket = ++openRequest;
    io.execute(
        () -> {
          try {
            NoteDocument loaded = readDocument(id);
            main.post(
                () -> {
                  if (!disposed && ticket == openRequest) openDocument(loaded);
                });
          } catch (IOException e) {
            main.post(() -> error("无法打开笔记", e));
          }
        });
  }

  private void openDocument(NoteDocument n) {
    if (studyTools != null) studyTools.onPause();
    saveCurrent(false);
    saveReadingPosition();
    navigation.switchDocument(n.id);
    main.removeCallbacks(delayedSave);
    main.removeCallbacks(delayedThumbnails);
    openRequest++;
    document = n;
    documentSession++;
    revision = 0;
    dirty = unsavedSnapshots.containsKey(n.id);
    preferences.edit().putLong("opened:" + n.id, System.currentTimeMillis()).apply();
    tool = "pen";
    restorePenStyle(tool);
    heldTool = null;
    heldDescriptor = null;
    heldMotionButton = false;
    heldCode = -1;
    pagesVisible = false;
    buildEditor();
    PageNavigation.Location reading = ReadingPositionStore.read(preferences, n.id);
    if (reading != null) {
      int page = n.findPageIndex(reading.pageId);
      if (page >= 0) {
        document.currentPage = page;
        ink.setPage(page);
        refreshPages();
        restoreReadingPosition(reading);
      }
    }
    String requestedPage = getIntent().getStringExtra("pageId");
    if (n.id.equals(getIntent().getStringExtra("noteId")) && requestedPage != null) {
      int at = n.findPageIndex(requestedPage);
      if (at >= 0) { switchPage(at); if(getIntent().getBooleanExtra("fitRequestedPage",false))fitRequestedPage(); }
      getIntent().removeExtra("pageId");
    }
    reportHostVisit();
    if (dirty) {
      saveLabel.setText("有修改 · 等待重试保存");
      saveLabel.setTextColor(Ui.DANGER);
    }
  }

  private void input(String title, String hint, String initial, Consumer<String> callback) {
    EditText e = new EditText(this);
    e.setFilters(
        new android.text.InputFilter[] {
          new android.text.InputFilter.LengthFilter(
              title.contains("目录") || title.contains("标签") ? 200 : 500)
        });
    e.setText(initial);
    e.setSelectAllOnFocus(true);
    e.setSingleLine();
    e.setHint(hint);
    Ui.pad(e, 22, 14);
    AlertDialog di =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(e)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", (dialog, which) -> callback.accept(e.getText().toString()))
            .create();
    di.setOnShowListener(
        v -> {
          e.requestFocus();
          di.getWindow()
              .setSoftInputMode(
                  android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
    di.show();
  }

  private void noteMenu(LibraryEntry n) {
    if (n.trashed) {
      new AlertDialog.Builder(this)
          .setTitle(n.title)
          .setItems(
              new String[] {"恢复到书库", "永久删除"},
              (di, w) -> {
                if(w==0)changeLibraryTrash(n,"restore");
                else new AlertDialog.Builder(this).setTitle("永久删除笔记？")
                    .setMessage("“"+n.title+"”及其独占附件将被删除，无法恢复。")
                    .setNegativeButton("取消",null).setPositiveButton("永久删除",(a,b)->changeLibraryTrash(n,"delete")).show();
              })
          .setNegativeButton("取消", null)
          .show();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(n.title)
        .setItems(
            new String[] {
              "重命名",
              "编辑标签",
              "移动到目录",
              "封面与样式",
              "移入回收站",
              n.tags.contains("收藏") ? "取消收藏" : "收藏笔记",
              "属性"
            },
            (di, w) -> {
              if (w == 0)
                input(
                    "重命名笔记",
                    "标题",
                    n.title,
                    s -> {
                      if (!s.trim().isEmpty()) updateLibraryNote(n, x -> x.title = s.trim());
                    });
              else if (w == 1)
                input(
                    "编辑标签",
                    "用逗号分隔，例如 课程,物理",
                    String.join(",", n.tags),
                    s -> {
                      LinkedHashSet<String> tags = new LinkedHashSet<>();
                      for (String tag : s.split("[,，]")) {
                        String value = tag.trim();
                        if (value.length() > 200) {
                          toast("单个标签不能超过 200 字");
                          return;
                        }
                        if (!value.isEmpty()) tags.add(value);
                      }
                      if (tags.size() > 100) {
                        toast("最多支持 100 个标签（含收藏标记）");
                        return;
                      }
                      updateLibraryNote(
                          n,
                          x -> {
                            x.tags.clear();
                            x.tags.addAll(tags);
                          });
                    });
              else if (w == 2)
                input(
                    "移动到目录",
                    "留空即移回书库",
                    n.folder == null ? "" : n.folder,
                    s -> updateLibraryNote(n, x -> x.folder = s.trim()));
              else if (w == 3) loadLibraryNote(n.id, this::chooseCover);
              else if (w == 6) loadLibraryNote(n.id, this::showDocumentInfo);
              else if (w == 5) {
                if (!n.tags.contains("收藏") && n.tags.size() >= 100) {
                  toast("标签已满，请先移除一个标签再收藏");
                  return;
                }
                updateLibraryNote(
                    n,
                    x -> {
                      if (x.tags.contains("收藏")) x.tags.remove("收藏");
                      else x.tags.add("收藏");
                    });
              } else
                new AlertDialog.Builder(this)
                    .setTitle("移入回收站？")
                    .setMessage("“" + n.title + "”将在7天后自动删除，此前可从回收站恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "移入回收站", (a, b) -> changeLibraryTrash(n,"trash"))
                    .show();
            })
        .show();
  }
  private void changeLibraryTrash(LibraryEntry note,String action){
    if(unsavedSnapshots.containsKey(note.id)){toast("这本笔记尚未保存成功，请先重试保存再整理");return;}
    executeContent(()->{
      try {
        if(unsavedSnapshots.containsKey(note.id))throw new IOException("笔记仍有未保存内容，请先重试保存");
        NotesStorage.change(this,java.util.Collections.singletonList(note.id),action);
        main.post(()->{if(!disposed){libraryIndexDirty=true;showLibrary();}});}
      catch(Exception failure){main.post(()->error("笔记整理未完成",failure));}
    });
  }

  private void chooseCover(NoteDocument n) {
    NoteDocument draft = n.copy();
    Runnable apply =
        () -> {
          if (document != null && document.id.equals(n.id)) {
            document.cover = draft.cover;
            document.coverPattern = draft.coverPattern;
            document.coverImagePath = draft.coverImagePath;
            markDirty();
          } else
            updateLibraryNote(
                n,
                note -> {
                  note.cover = draft.cover;
                  note.coverPattern = draft.coverPattern;
                  note.coverImagePath = draft.coverImagePath;
                });
        };
    CoverPickerView picker =
        new CoverPickerView(
            this,
            draft,
            Runnable::run,
            () -> {
              apply.run();
              dismissLibraryPanel();
              closeToolPanel();
              startCoverImageImport(n);
            });
    if (document != null) showToolPanel("封面与样式", picker, apply);
    else
      showLibraryPanel(
          "封面与样式",
          picker,
          "保存封面",
          () -> {
            apply.run();
            dismissLibraryPanel();
          });
  }

  private void startCoverImageImport(NoteDocument note) {
    coverTargetId = note.id;
    launchOpen("image/*", IMPORT_COVER_IMAGE);
  }

  private void updateLibraryNote(NoteDocument n, Consumer<NoteDocument> change) {
    updateLibraryNote(n.id, change);
  }

  private void updateLibraryNote(LibraryEntry n, Consumer<NoteDocument> change) {
    updateLibraryNote(n.id, change);
  }

  private void loadLibraryNote(String id, Consumer<NoteDocument> ready) {
    io.execute(
        () -> {
          try {
            NoteDocument note = readDocument(id);
            main.post(
                () -> {
                  if (!isFinishing() && !isDestroyed() && document == null) ready.accept(note);
                });
          } catch (IOException e) {
            main.post(() -> error("笔记读取失败", e));
          }
        });
  }

  private void updateLibraryNote(String id, Consumer<NoteDocument> change) {
    executeContent(
        () -> {
          try {
            NoteDocument loaded = readDocument(id);
            change.accept(loaded);
            loaded.modifiedAt = System.currentTimeMillis();
            store.save(loaded);
            unsavedSnapshots.remove(id);
            main.post(
                () -> {
                  if (document == null) showLibrary();
                });
          } catch (IOException e) {
            main.post(() -> error("笔记修改失败", e));
          }
        });
  }

  private void buildEditor() {
    if(hostBar!=null)hostBar.setVisibility(View.VISIBLE);
    if(hostNavigation!=null)hostNavigation.setVisibility(View.GONE);
    cancelCapture();
    closeImageTransform();
    paperPanelPage = null;
    activePenPanelRefresh = null;
    // Operate: paper owns the viewport; IKO graphite/blue-violet chrome supports writing.
    LinearLayout shell = Ui.column(this);
    shell.setBackgroundColor(Ui.BG);
    LinearLayout top = Ui.row(this);
    editorHeader = top;
    top.setBackgroundColor(Ui.CHROME);
    Ui.pad(top, 8, 0);
    top.addView(icon("back", "返回书库", this::handleBackNavigation));
    LinearLayout titleStack = Ui.column(this);
    Ui.pad(titleStack, 8, 0);
    noteTitle = Ui.label(this, document.title, 15);
    noteTitle.setTextColor(Ui.CHROME_TEXT);
    noteTitle.setSingleLine();
    noteTitle.setEllipsize(TextUtils.TruncateAt.END);
    titleStack.addView(noteTitle, lp(-1, 25));
    saveLabel = Ui.text(this, "已保存到本机", 10, Ui.CHROME_MUTED);
    titleStack.addView(saveLabel, lp(-1, 18));
    if(compactWorkspace)saveLabel.setVisibility(View.GONE);
    titleStack.setContentDescription("笔记标题，点击重命名");
    titleStack.setOnClickListener(v -> renameCurrent());
    int screenWidth = containerWidthDp();
    compactHeader = screenWidth < 600;
    top.addView(titleStack, lp(compactHeader ? 96 : 176, 44));
    undoButton = icon("undo", "撤销", () -> performAction("undo"));
    redoButton = icon("redo", "重做", () -> performAction("redo"));
    actionToolbarScroll = new HorizontalScrollView(this);
    actionToolbarScroll.setHorizontalScrollBarEnabled(false);
    actionToolbarScroll.setFillViewport(true);
    LinearLayout actions = Ui.row(this);
    actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    actions.addView(undoButton);
    actions.addView(redoButton);
    toolbarDivider(actions);
    textBoxButton = icon("text", "文本输入框", () -> studyTools.text());
    actions.addView(textBoxButton);
    pasteButton = icon("paste", "粘贴", this::pasteObjects);
    pasteButton.setVisibility(objectClipboard == null ? View.GONE : View.VISIBLE);
    actions.addView(pasteButton);
    actions.addView(icon("pages", "页面目录", this::showOutline));
    actions.addView(icon("addpage", "新增页面", this::addPage));
    actions.addView(icon("paper", "纸张与材质", this::showPaper));
    actions.addView(icon("library", "手账模板库", this::showTemplateLibrary));
    toolbarDivider(actions);
    actions.addView(icon("image", "插入图片 · 查看最近图片", this::showRecentImages));
    actions.addView(icon("screenshot", "应用内截图并插入当前页", this::captureNoteViewport));
    toolbarDivider(actions);
    actions.addView(icon("import", "导入 PDF、Office 或 OneNote", this::chooseImport));
    actions.addView(icon("info", "文档属性", this::showDocumentInfo));
    actions.addView(icon("stylus", "手写笔与快捷键", this::showInputSettings));
    actionToolbarScroll.addView(actions);
    for (int j = 0; j < actions.getChildCount(); j++) {
      View action = actions.getChildAt(j);
      if (action instanceof IconView) action.setLayoutParams(lp(44, 44));
    }
    top.addView(actionToolbarScroll, new LinearLayout.LayoutParams(0, d(44), 1));
    if (!compactHeader) {
      top.addView(icon("export", "导出笔记", this::chooseExport));
      if (!NotesHost.attached()) top.addView(icon("palette", "外观与主题", this::showAppearance));
    }
    top.addView(icon("more", "其他笔记功能", this::showEditorMenu));
    tintHeader(top);
    shell.addView(top, lp(-1, compactWorkspace?44:52));
    View topLine = new View(this);
    topLine.setBackgroundColor(Ui.LINE);
    shell.addView(topLine, lp(-1, 1));
    writingToolbarHost = new FrameLayout(this);
    writingToolbarHost.addView(buildWritingToolbar(), new FrameLayout.LayoutParams(-1, -1));
    shell.addView(writingToolbarHost, lp(-1, compactWorkspace?52:64));
    toolOptionsHost = new FrameLayout(this);
    toolOptionsHost.setVisibility(View.GONE);
    shell.addView(toolOptionsHost, lp(-1, -2));
    LinearLayout workspace = Ui.row(this);
    workspace.setGravity(Gravity.TOP);
    workspace.setBackgroundColor(Ui.WORKSPACE);
    pageRail = Ui.column(this);
    pageRail.setBackgroundColor(Ui.BG);
    pageRail.setVisibility(pagesVisible ? View.VISIBLE : View.GONE);
    workspace.addView(pageRail, lp(96, -1));
    if (rebuildingChrome) ((ViewGroup) ink.getParent()).removeView(ink);
    else {
      ink = new InkView(this);
      ink.setDocument(document);
      ink.setContinuousScrolling(preferences.getBoolean("continuousPages", true));
      ink.setWidthFitPreferred(true);
    }
    ink.setTool(tool);
    ink.setInkColor(inkColor);
    ink.setInkWidth(inkWidth);
    ink.setPressureMode(pressureMode);
    ink.setEraserWidth(eraserWidth);
    ink.setEraserMode(eraserMode);
    ink.setReferenceListener(this::navigateToReference);
    ink.setPageChangeListener(
        () -> {
          cancelCapture();
          closeImageTransform();
          if (toolOptionsHost != null && toolOptionsHost.getVisibility() == View.VISIBLE) {
            inputSettingsChild = false;
            learningKeyActive = false;
            closeToolPanel();
          }
          updatePageLabel();
          updateUndo();
          updateNavigationControls();
        });
    ink.setImageEditListener(this::editImage);
    ink.setPageAppendListener(
        () -> {
          refreshPages();
          updatePageLabel();
          updateNavigationControls();
        });
    updateSideButtonActions();
    ink.setSelectionListener(this::refreshSelectionActions);
    ink.setFingerDrawing(fingerDrawing);
    ink.setListener(
        () -> {
          markDirty();
          updateUndo();
          main.removeCallbacks(delayedThumbnails);
          main.postDelayed(delayedThumbnails, 850);
        });
    paperSurface = new FrameLayout(this);
    paperSurface.addView(ink, new FrameLayout.LayoutParams(-1, -1));
    if (rebuildingChrome && studyTools != null) studyTools.rebind(paperSurface);
    else
      studyTools =
          new StudyTools(
              this,
              activity,
              this::finish,
              this::requestPermissions,
              ink,
              paperSurface,
              new StudyTools.Panels() {
                public void show(String title, View content, Runnable commit) {
                  if (content instanceof HorizontalScrollView) {
                    showQuickToolPanel(title, content);
                    activeToolPanelCommit = commit;
                  } else showToolPanel(title, content, commit);
                }

                public void close() {
                  closeToolPanel();
                }
              },
              this::selectTool,
              this::markDirty,
              this::refreshStudyStatus);
    workspace.addView(paperSurface, new LinearLayout.LayoutParams(0, -1, 1));
    LinearLayout workingArea = Ui.column(this);
    workingArea.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
    focusPanel = Ui.column(this);
    focusPanel.setBackground(Ui.bg(this, Ui.SURFACE, 0));
    focusPanel.setVisibility(View.GONE);
    if (rebuildingChrome && focusWriting != null)
      ((ViewGroup) focusWriting.getParent()).removeView(focusWriting);
    else focusWriting = new FocusWritingView(this, ink);
    focusWriting.setFingerDrawing(fingerDrawing);
    focusWriting.setAutoAdvance(preferences.getBoolean("focusAutoAdvance", true));
    ink.setFocusLocator(point -> focusWriting.locate(point.x, point.y));
    HorizontalScrollView focusControlsScroll = new HorizontalScrollView(this);
    focusControlsScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout focusControls = Ui.row(this);
    Ui.pad(focusControls, 12, 0);
    focusControls.addView(Ui.text(this, "上方定位 · 下方书写", 12, Ui.ACCENT), lp(144, 48));
    String[] focusLabels = {"上一行", "下一行", "行首", "向右", "自动推进"};
    Runnable[] focusActions = {
      () -> focusWriting.previousLine(),
      () -> focusWriting.nextLine(),
      () -> focusWriting.lineStart(),
      () -> focusWriting.advanceRight(),
      this::toggleAutoAdvance
    };
    for (int i = 0; i < focusLabels.length; i++) {
      final Runnable action = focusActions[i];
      TextView control = Ui.text(this, focusLabels[i], 12, Ui.TEXT);
      control.setGravity(Gravity.CENTER);
      control.setContentDescription("放大书写窗：" + focusLabels[i]);
      control.setOnClickListener(v -> action.run());
      focusControls.addView(control, lp(72, 48));
    }
    focusControlsScroll.addView(focusControls);
    LinearLayout focusHeader = Ui.row(this);
    focusHeader.addView(focusControlsScroll, new LinearLayout.LayoutParams(0, d(48), 1));
    focusHeader.addView(icon("back", "关闭放大书写窗", this::toggleFocusWriting));
    focusPanel.addView(focusHeader, lp(-1, 48));
    int paneHeight =
        Math.max(96, Math.min(170, (containerHeightDp() - 160) / 3));
    focusPanel.addView(focusWriting, lp(-1, paneHeight));
    workingArea.addView(focusPanel, lp(-1, -2));
    shell.addView(workingArea, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout footer = Ui.row(this);
    Ui.pad(footer, 8, 0);
    footer.setBackgroundColor(Ui.BG);
    footer.addView(icon("pages", "页面列表", this::togglePages));
    footer.addView(icon("back", "上一页", () -> switchPage(document.currentPage - 1)));
    pageLabel = Ui.text(this, "", 12, Ui.MUTED);
    pageLabel.setGravity(Gravity.CENTER);
    pageLabel.setOnClickListener(v -> showOutline());
    footer.addView(pageLabel, lp(60, 48));
    footer.addView(icon("next", "下一页", () -> switchPage(document.currentPage + 1)));
    footer.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    selectionButton = Ui.text(this, "编辑选区", 12, Ui.ACCENT);
    selectionButton.setGravity(Gravity.CENTER);
    selectionButton.setVisibility(View.GONE);
    selectionButton.setOnClickListener(v -> showSelectionActions());
    footer.addView(selectionButton, lp(88, 48));
    referenceBackButton = Ui.text(this, "返回引用处", 12, Ui.ACCENT);
    referenceBackButton.setGravity(Gravity.CENTER);
    referenceBackButton.setOnClickListener(v -> returnFromReference());
    footer.addView(referenceBackButton, lp(96, 48));
    LinearLayout modeSwitch = Ui.row(this);
    modeSwitch.setGravity(Gravity.CENTER);
    modeSwitch.setBackground(Ui.bg(this, Ui.FLOAT, 12));
    stylusModeIcon = new IconView(this, "stylus");
    fingerModeIcon = new IconView(this, "finger");
    modeSwitch.addView(stylusModeIcon, lp(48, 48));
    modeSwitch.addView(fingerModeIcon, lp(48, 48));
    modeSwitch.setContentDescription("输入方式切换：手写笔书写或手指书写");
    inputModeButton = modeSwitch;
    inputModeButton.setOnClickListener(v -> toggleInputMode());
    footer.addView(inputModeButton, lp(100, 48));
    paperViewButton = icon("fit", "纸面视图：适宽书写或整页总览", this::showPaperView);
    footer.addView(paperViewButton);
    readingButton = null;
    pressureLabel = null;
    presetButtons.clear();
    HorizontalScrollView footerScroll = new HorizontalScrollView(this);
    footerScroll.setHorizontalScrollBarEnabled(false);
    footerScroll.setFillViewport(true);
    footerScroll.addView(footer);
    shell.addView(footerScroll, lp(-1, compactWorkspace?40:48));
    present(shell);
    refreshToolSelection();
    refreshPages();
    updateUndo();
    refreshPenControls();
    updateNavigationControls();
    refreshInputMode();
    refreshStudyStatus();
    if (dirty) saveLabel.setText("有修改 · 等待保存");
  }

  private View buildWritingToolbar() {
    int screenWidth = containerWidthDp();
    toolbarDensity = toolbarDensity(screenWidth);
    boolean compact = screenWidth < 600;
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setFillViewport(false);
    scroll.setBackgroundColor(Ui.TOOLBAR);
    LinearLayout row = Ui.row(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    Ui.pad(row, 12, 4);
    toolViews.clear();
    String[] ids =
        compact
            ? new String[] {
              "pen-family",
              "highlighter",
              "eraser",
              "lasso",
              "shape",
              "hand",
              "laser",
              "tape",
              "audio"
            }
            : new String[] {
              "pen-family",
              "highlighter",
              "eraser",
              "lasso",
              "shape",
              "hand",
              "laser",
              "tape",
              "audio"
            };
    for (String id : ids) {
      LinearLayout item = Ui.column(this);
      item.setGravity(Gravity.CENTER);
      String kind =
          id.equals("pen-family")
              ? (PenPreset.isPen(tool) && !tool.equals("highlighter") ? tool : "pen")
              : id.equals("audio") ? "microphone" : id;
      IconView glyph = new IconView(this, kind);
      item.addView(glyph, lp(48, 30));
      TextView label =
          Ui.text(
              this,
              id.equals("tape") ? "胶带" : id.equals("audio") ? "录音" : toolName(kind),
              10,
              Ui.MUTED);
      label.setGravity(Gravity.CENTER);
      label.setSingleLine(true);
      item.addView(label, lp(48, 18));
      item.setTag(id);
      item.setContentDescription(id.equals("audio") ? "课堂录音" : toolName(kind));
      item.setOnClickListener(
          v -> {
            boolean detailsOpen=toolOptionsHost!=null && toolOptionsHost.getVisibility()==View.VISIBLE && toolOptionsHost.getChildCount()>0;
            if(detailsOpen && activeToolPanelCommit!=null)activeToolPanelCommit.run();
            if (id.equals("laser")) studyTools.laser();
            else if (id.equals("tape")) studyTools.tape();
            else if (id.equals("audio")) studyTools.audio();
            else if (id.equals("pen-family")) {
              if (PenPreset.isPen(tool) && !tool.equals("highlighter")) chooseInkWidth();
              else { selectTool(preferences.getString("lastWritingPen", "pen")); if(detailsOpen)chooseInkWidth(); }
            } else if (id.equals("shape")) chooseShape();
            else if (id.equals(tool) && PenPreset.isPen(id)) chooseInkWidth();
            else if (id.equals(tool) && id.equals("eraser")) chooseEraser();
            else {
              selectTool(id);
              if(detailsOpen) {
                if(PenPreset.isPen(id))chooseInkWidth();
                else if(id.equals("eraser"))chooseEraser();
                else if(id.equals("lasso")) {
                  if(ink.hasSelection())showSelectionActions();
                  else showQuickToolPanel("套索",Ui.text(this,"圈住笔迹、文字或图片后，可移动、缩放、旋转、复制与删除。",12,Ui.TEXT));
                } else if(id.equals("hand"))showQuickToolPanel("漫游",Ui.text(this,"拖动浏览纸面，双指缩放；切回书写工具即可继续写字。",12,Ui.TEXT));
              }
            }
          });
      item.setOnLongClickListener(
          v -> {
            if (id.equals("pen-family")) chooseInkWidth();
            else if (id.equals("eraser")) chooseEraser();
            else if (id.equals("highlighter")) {
              selectTool(id);
              chooseInkWidth();
            } else return false;
            return true;
          });
      LinearLayout.LayoutParams params = lp(48, 52);
      params.rightMargin = d(4);
      if (id.equals("laser")) params.leftMargin = d(8);
      row.addView(item, params);
      toolViews.add(item);
    }
    View rule = new View(this);
    rule.setBackgroundColor(Ui.LINE);
    LinearLayout.LayoutParams ruleParams = lp(1, 28);
    ruleParams.setMargins(d(8), 0, d(8), 0);
    row.addView(rule, ruleParams);
    int[] swatches = {0xff272d3b, 0xff4568df, 0xffcc535d, 0xff238b75, 0xffedbb4a, 0xffa76bd2};
    int toolWidth = ids.length * 52 + 8;
    int count = Math.max(0, Math.min(6, (screenWidth - toolWidth - 145) / 40));
    for (int i = 0; i < count; i++) {
      final int color = swatches[i];
      FrameLayout cell = new FrameLayout(this);
      View dot = new View(this);
      dot.setBackground(Ui.border(this, color, 12, 0xff697180));
      cell.addView(dot, new FrameLayout.LayoutParams(d(22), d(22), Gravity.CENTER));
      cell.setContentDescription(String.format(Locale.ROOT, "墨水颜色 #%06X", color & 0xffffff));
      cell.setOnClickListener(v -> applyInkColor(color));
      row.addView(cell, lp(40, 48));
    }
    FrameLayout colorBox = new FrameLayout(this);
    colorBox.setContentDescription("连续色盘与不透明度");
    colorBox.setOnClickListener(v -> chooseInkColor());
    colorButton = new View(this);
    colorButton.setBackground(Ui.border(this, inkColor, 12, 0xffcad1e1));
    colorBox.addView(colorButton, new FrameLayout.LayoutParams(d(26), d(26), Gravity.CENTER));
    row.addView(colorBox, lp(48, 48));
    widthLabel = Ui.label(this, "", 12);
    widthLabel.setGravity(Gravity.CENTER);
    widthLabel.setBackground(Ui.bg(this, Ui.SURFACE, 10));
    widthLabel.setContentDescription("笔型、笔宽与笔触设置");
    widthLabel.setOnClickListener(v -> chooseWidth());
    row.addView(widthLabel, lp(56, 48));
    scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
    return scroll;
  }

  private void tintHeader(View view) {
    if (view instanceof IconView) {
      ((IconView) view).tint(Ui.CHROME_MUTED);
      view.setLayoutParams(lp(44, 44));
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int n = 0; n < group.getChildCount(); n++) tintHeader(group.getChildAt(n));
    }
  }

  private void toolbarDivider(LinearLayout row) {
    View divider = new View(this);
    divider.setBackgroundColor(Ui.mix(Ui.CHROME_MUTED, Ui.CHROME, .22f));
    LinearLayout.LayoutParams p = lp(1, 20);
    p.setMargins(d(6), 0, d(6), 0);
    row.addView(divider, p);
  }

  private String toolName(String id) {
    switch (id) {
      case "pen":
        return "钢笔";
      case "ballpoint":
        return "圆珠笔";
      case "pencil":
        return "铅笔";
      case "brush":
        return "毛笔";
      case "fineliner":
        return "针管笔";
      case "highlighter":
        return "荧光笔";
      case "eraser":
        return "橡皮";
      case "lasso":
        return "套索";
      case "shape":
        return "几何";
      case "hand":
        return "漫游";
      case "laser":
        return "激光笔";
      case "text":
        return "文本框";
      case "tape":
        return "背诵胶带";
      default:
        return "笔";
    }
  }

  private void showEditorMenu() {
    String[] labels = {
      "插入文字", "插入图片", "几何图形", "页目录与引用", "放大书写窗", "纸张与材质", "常用笔盒",
      "手写笔与快捷键", "阅读 / 漫游", "增加空白页", "导入资料", "导出笔记", "重命名笔记", "管理文字与图片", "外观与主题"
    };
    Runnable[] actions = {
      this::insertText,
      this::showRecentImages,
      this::chooseShape,
      this::showOutline,
      this::toggleFocusWriting,
      this::showPaper,
      this::showPenBox,
      this::showInputSettings,
      () -> selectTool("hand"),
      this::addPage,
      this::chooseImport,
      this::chooseExport,
      this::renameCurrent,
      this::manageElements,
      this::showAppearance
    };
    String[] glyphs = {
      "text", "image", "shape", "pages", "focus", "paper", "pen",
      "stylus", "hand", "addpage", "import", "export", "text", "pages", "palette"
    };
    int widthDp = containerWidthDp();
    int columns = widthDp >= 1000 ? 7 : widthDp >= 700 ? 6 : widthDp >= 600 ? 5 : 3;
    GridLayout options = new GridLayout(this);
    options.setColumnCount(columns);
    for (int i = 0; i < labels.length; i++) {
      if (NotesHost.attached() && "外观与主题".equals(labels[i])) continue;
      final int index = i;
      final Runnable action = actions[i];
      LinearLayout choice = Ui.column(this);
      choice.setGravity(Gravity.CENTER);
      IconView glyph = new IconView(this, glyphs[i]);
      glyph.setFocusable(false);
      glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      choice.addView(glyph, lp(48, 34));
      TextView caption = Ui.text(this, labels[i], 10, Ui.MUTED);
      caption.setGravity(Gravity.CENTER);
      caption.setSingleLine(true);
      choice.addView(caption, lp(-1, 22));
      choice.setContentDescription(labels[i]);
      choice.setTooltipText(labels[i]);
      choice.setOnClickListener(
          v -> {
            closeToolPanel();
            action.run();
          });
      GridLayout.LayoutParams params =
          new GridLayout.LayoutParams(
              GridLayout.spec(index / columns), GridLayout.spec(index % columns, 1f));
      params.width = 0;
      params.height = d(56);
      params.setMargins(d(2), 0, d(2), 0);
      options.addView(choice, params);
    }
    showToolPanel("笔记与工具", options, () -> {});
  }

  private void showPaperView() {
    LinearLayout options = Ui.row(this);
    options.setGravity(Gravity.CENTER);
    boolean free = ink.getPage().infinite;
    String[] labels =
        free
            ? new String[] {"回到内容", "回到原点", "适合书写"}
            : new String[] {"适宽书写", "整页总览", "放大书写窗", ink.isContinuousScrolling() ? "单页查看" : "连续页面"};
    String[] glyphs =
        free
            ? new String[] {"fit", "focus", "fit-width"}
            : new String[] {"fit-width", "fit", "focus", "pages"};
    Runnable[] actions =
        free
            ? new Runnable[] {ink::fitContent, ink::centerOrigin, ink::fitWidth}
            : new Runnable[] {
              () -> ink.fitWidth(),
              () -> ink.fitPage(),
              this::toggleFocusWriting,
              () -> {
                boolean enabled = !ink.isContinuousScrolling();
                preferences.edit().putBoolean("continuousPages", enabled).apply();
                ink.setContinuousScrolling(enabled);
                ink.fitWidth();
              }
            };
    for (int i = 0; i < labels.length; i++) {
      final Runnable action = actions[i];
      IconView choice =
          optionIcon(
              glyphs[i],
              labels[i],
              false,
              () -> {
                closeToolPanel();
                action.run();
              });
      LinearLayout.LayoutParams params = lp(64, 48);
      params.rightMargin = d(6);
      options.addView(choice, params);
    }
    showQuickToolPanel("纸面视图", options);
  }

  private void refreshInputMode() {
    if (inputModeButton == null) return;
    inputModeButton.setContentDescription(
        fingerDrawing ? "手指书写已开启；点击切换为手写笔书写，手指用于导航" : "手写笔书写已开启；点击切换为手指书写");
    if (stylusModeIcon != null) {
      stylusModeIcon.tint(fingerDrawing ? Ui.MUTED : Ui.ACCENT);
      fingerModeIcon.tint(fingerDrawing ? Ui.ACCENT : Ui.MUTED);
      stylusModeIcon.setBackground(Ui.bg(this, fingerDrawing ? Ui.FLOAT : Ui.SELECTED, 10));
      fingerModeIcon.setBackground(Ui.bg(this, fingerDrawing ? Ui.SELECTED : Ui.FLOAT, 10));
      stylusModeIcon.setContentDescription("手写笔" + (fingerDrawing ? "（未选中）" : "（已选中）"));
      fingerModeIcon.setContentDescription("手指" + (fingerDrawing ? "（已选中）" : "（未选中）"));
    }
  }

  private void toggleInputMode() {
    fingerDrawing = !fingerDrawing;
    preferences.edit().putBoolean("fingerDrawing", fingerDrawing).apply();
    ink.cancelInput();
    ink.setFingerDrawing(fingerDrawing);
    focusWriting.setFingerDrawing(fingerDrawing);
    refreshInputMode();
  }

  private void renameCurrent() {
    if (document == null) return;
    String documentId = document.id;
    showInlineTextEditor(
        "重命名笔记",
        "标题",
        document.title,
        "保存标题",
        value -> {
          if (!value.trim().isEmpty() && document != null && document.id.equals(documentId)) {
            document.title = value.trim();
            noteTitle.setText(document.title);
            markDirty();
          }
        });
  }

  private void showInlineTextEditor(
      String title, String hint, String initial, String actionLabel, Consumer<String> save) {
    EditText edit = new EditText(this);
    edit.setSingleLine();
    edit.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(500)});
    edit.setHint(hint);
    edit.setText(initial);
    Ui.pad(edit, 16, 8);
    LinearLayout body = Ui.column(this);
    body.addView(edit, lp(-1, 56));
    body.addView(
        Ui.button(
            this,
            actionLabel,
            true,
            () -> {
              String value = edit.getText().toString();
              save.accept(value);
              closeToolPanel();
            }),
        lp(-1, 48));
    showToolPanel(title, body, () -> {});
    edit.post(
        () -> {
          edit.requestFocus();
          ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
              .showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
  }

  private void showDocumentInfo() {
    if (document == null) return;
    showDocumentInfo(document);
  }

  private void showDocumentInfo(NoteDocument note) {
    long strokes = 0, points = 0, elements = 0, mediaBytes = 0;
    Set<String> assets = new HashSet<>();
    if (note.coverImagePath != null && !note.coverImagePath.isEmpty())
      assets.add(note.coverImagePath);
    for (NoteDocument.Page page : note.pages) {
      strokes += page.strokes.size();
      elements += page.elements.size();
      for (NoteDocument.Stroke stroke : page.strokes) points += stroke.points.size();
      if (page.backgroundPath != null && !page.backgroundPath.isEmpty())
        assets.add(page.backgroundPath);
      for (NoteDocument.Element element : page.elements)
        if ("image".equals(element.kind) && !element.path.isEmpty()) assets.add(element.path);
    }
    for (NoteDocument.Recording recording : note.recordings) assets.add(recording.path);
    for (String path : assets) {
      try {
        File file = new File(path).getCanonicalFile();
        File mediaRoot = new File(getFilesDir(), "media").getCanonicalFile();
        if (file.getPath().startsWith(mediaRoot.getPath() + File.separator) && file.isFile())
          mediaBytes += file.length();
      } catch (IOException ignored) {
        // Missing attachments are surfaced in the document's visual page instead.
      }
    }
    long noteBytes = 0;
    try {
      noteBytes = store.fileSize(note.id);
    } catch (IOException error) {
      lastStoreWarning = error.getMessage();
    }
    String modified =
        android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", new Date(note.modifiedAt))
            .toString();
    LinearLayout body = Ui.column(this);
    Ui.pad(body, 8, 6);
    boolean free = note.pages.size() == 1 && note.pages.get(0).infinite;
    addInfoRow(body, "名称", note.title);
    addInfoRow(body, "类型", NoteTypes.label(note));
    addInfoRow(
        body,
        "页面",
        free ? "1 个无限画布" : note.pages.size() + " 页 · 当前第 " + (note.currentPage + 1) + " 页");
    addInfoRow(body, "笔迹", strokes + " 笔 · " + String.format(Locale.ROOT, "%,d", points) + " 个采样点");
    addInfoRow(body, "页面元素", elements + " 个文字框、图片或胶带");
    addInfoRow(body, "课堂录音", note.recordings.size() + " 段");
    addInfoRow(body, "笔记数据", formatBytes(noteBytes) + "（压缩后）");
    addInfoRow(body, "图片与资料", formatBytes(mediaBytes) + " · " + assets.size() + " 个文件");
    addInfoRow(body, "最后修改", modified);
    addInfoRow(body, "笔记 ID", note.id);
    if (importReportFile(note).isFile())
      body.addView(
          Ui.button(this, "查看导入内容与兼容范围", false, () -> showSavedImportReport(note)), lp(-1, 48));
    if (note == document && ink != null && ink.getPage() != null) {
      NoteDocument.Page page = ink.getPage();
      addInfoRow(body, "当前纸页", page.paper + " · " + page.width + " × " + page.height);
      addInfoRow(body, "纸面材质", page.texture);
    }
    ScrollView scroll = new ScrollView(this);
    scroll.addView(body);
    showToolPanel("文档属性", scroll, () -> {});
  }

  private void addInfoRow(LinearLayout body, String label, String value) {
    LinearLayout row = Ui.row(this);
    row.setBackground(Ui.bg(this, Ui.SURFACE, 10));
    Ui.pad(row, 12, 8);
    row.addView(Ui.text(this, label, 12, Ui.MUTED), lp(100, -2));
    TextView detail = Ui.text(this, value, 12, Ui.TEXT);
    detail.setTextIsSelectable(true);
    row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams params = lp(-1, -2);
    params.bottomMargin = d(6);
    body.addView(row, params);
  }

  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f);
    return String.format(Locale.ROOT, "%.2f MB", bytes / (1024f * 1024f));
  }

  private void toggleFocusWriting() {
    if (ink == null || focusWriting == null) return;
    InkView target = ink;
    FocusWritingView focusTarget = focusWriting;
    target.cancelInput();
    boolean open = focusPanel.getVisibility() != View.VISIBLE;
    if (open) focusReturnLocation = target.captureViewport();
    focusPanel.setVisibility(open ? View.VISIBLE : View.GONE);
    target.setFrameListener(open ? focusTarget::invalidate : null);
    if (open) {
      target.post(
          () -> {
            if (ink == target && document != null) target.fitOverviewPreservingPreference();
          });
      focusTarget.refreshPage();
      focusTarget.post(
          () -> {
            if (focusWriting == focusTarget && document != null) focusTarget.refreshPage();
          });
    } else {
      target.setFocusRegion(null);
      PageNavigation.Location previous = focusReturnLocation;
      if (previous != null)
        target.post(
            () -> {
              if (ink == target && document != null) target.restoreViewport(previous);
            });
      focusReturnLocation = null;
    }
  }

  private static int toolbarDensity(int widthDp) {
    return widthDp < 600 ? 0 : widthDp < 800 ? 1 : widthDp < 1000 ? 2 : 3;
  }

  private void toggleAutoAdvance() {
    boolean enabled = !preferences.getBoolean("focusAutoAdvance", true);
    preferences.edit().putBoolean("focusAutoAdvance", enabled).apply();
    focusWriting.setAutoAdvance(enabled);
    toast(enabled ? "放大窗右侧自动推进已开启" : "放大窗自动推进已关闭");
  }

  private void updateNavigationControls() {
    if (referenceBackButton != null) {
      boolean canBack = navigation.canBack(document);
      referenceBackButton.setVisibility(canBack ? View.VISIBLE : View.GONE);
      referenceBackButton.setEnabled(canBack);
    }
    if (readingButton != null)
      readingButton.setTextColor("hand".equals(tool) ? Ui.ACCENT : Ui.MUTED);
    updateFooterDensity();
  }

  private void updateFooterDensity() {
    if (inputModeButton == null) return;
    boolean compact = containerWidthDp() < 600;
    boolean context =
        (selectionButton != null && selectionButton.getVisibility() == View.VISIBLE)
            || (referenceBackButton != null && referenceBackButton.getVisibility() == View.VISIBLE);
    inputModeButton.setVisibility(compact && context ? View.GONE : View.VISIBLE);
    if (paperViewButton != null)
      paperViewButton.setVisibility(compact && context ? View.GONE : View.VISIBLE);
    if (selectionButton != null) {
      if (compact) selectionButton.setText("选区");
      selectionButton.getLayoutParams().width = d(compact ? 48 : 88);
    }
    if (referenceBackButton != null) {
      referenceBackButton.setText(compact ? "回跳" : "返回引用处");
      referenceBackButton.getLayoutParams().width = d(compact ? 48 : 96);
    }
  }

  private String pageName(int index) {
    NoteDocument.Page page = document.pages.get(index);
    return (index + 1)
        + " · "
        + (page.title.isEmpty() ? "未命名页面" : page.title)
        + (page.pdfPage >= 0 ? " · PDF" : "");
  }

  private void showOutline() {
    if (document == null) return;
    LinearLayout body = Ui.column(this);
    LinearLayout actions = Ui.row(this);
    actions.addView(
        Ui.button(this, "命名当前页", false, this::renameCurrentPage),
        new LinearLayout.LayoutParams(0, d(44), 1));
    actions.addView(
        Ui.button(this, "插入页引用", false, () -> chooseReferenceTarget(null)),
        new LinearLayout.LayoutParams(0, d(44), 1));
    body.addView(actions, lp(-1, 48));
    for (int i = 0; i < document.pages.size(); i++) {
      final int index = i;
      TextView page =
          Ui.button(
              this,
              pageName(i),
              i == document.currentPage,
              () -> {
                switchPage(index);
                closeToolPanel();
              });
      LinearLayout.LayoutParams params = lp(-1, 44);
      params.bottomMargin = d(4);
      body.addView(page, params);
    }
    showToolPanel("页目录 · 自建标题", body, () -> {});
  }

  private void renameCurrentPage() {
    if (ink == null || ink.getPage() == null) return;
    NoteDocument.Page page = ink.getPage();
    showInlineTextEditor(
        "页面标题",
        "为空时显示页码",
        page.title,
        "保存页面标题",
        value -> {
          if (ink != null && ink.getPage() == page) ink.mutate(() -> page.title = value.trim());
        });
  }

  private void chooseReferenceTarget(NoteDocument.Element existing) {
    if (document == null) return;
    LinearLayout pages = Ui.column(this);
    for (int i = 0; i < document.pages.size(); i++) {
      final int index = i;
      TextView target =
          Ui.button(
              this,
              pageName(i),
              false,
              () -> {
                NoteDocument.Page page = ink.getPage(), destination = document.pages.get(index);
                if (existing != null) ink.mutate(() -> existing.targetPageId = destination.id);
                else {
                  NoteDocument.Element link = new NoteDocument.Element();
                  link.text = "↗ " + pageName(index);
                  link.targetPageId = destination.id;
                  link.color = 0xff5269a2;
                  link.textSize = 36;
                  link.x = page.width * .14f;
                  link.y = page.height * .84f;
                  link.width = page.width * .7f;
                  if (!PageRenderer.fitTextToPage(page, link)) {
                    toast("引用标题过长，请先缩短页面标题");
                    return;
                  }
                  ink.mutate(() -> page.elements.add(link));
                }
                selectTool("hand");
                closeToolPanel();
                toast("已建立页引用；阅读模式轻点可跳转，套索可移动");
              });
      LinearLayout.LayoutParams params = lp(-1, 44);
      params.bottomMargin = d(4);
      pages.addView(target, params);
    }
    showToolPanel(existing == null ? "选择引用页面" : "更改引用目标", pages, () -> {});
  }

  private void navigateToReference(String pageId) {
    if (document == null || ink == null) return;
    int index = navigation.open(document.id, ink.captureViewport(), pageId, document);
    if (index < 0) {
      toast("目标页已删除，引用文字仍会保留");
      return;
    }
    if (focusPanel.getVisibility() == View.VISIBLE) toggleFocusWriting();
    if (index != document.currentPage) switchPage(index);
    updateNavigationControls();
  }

  private void returnFromReference() {
    PageNavigation.Location location = navigation.back(document);
    if (location == null || ink == null) {
      updateNavigationControls();
      return;
    }
    if (focusPanel.getVisibility() == View.VISIBLE) toggleFocusWriting();
    switchPage(document.findPageIndex(location.pageId));
    ink.post(
        () -> {
          if (ink != null) ink.restoreViewport(location);
        });
    updateNavigationControls();
  }

  private void selectTool(String id) {
    selectTool(id, false);
  }

  private void selectTool(String id, boolean fromPenButton) {
    if (ink == null) return;
    if (studyTools != null && !"text".equals(id)) studyTools.finishText(true);
    if (studyTools != null && studyTools.editingText() && !"text".equals(id)) return;
    closeImageTransform();
    rememberPenStyle();
    tool = id;
    if (PenPreset.isPen(id) && !id.equals("highlighter"))
      preferences.edit().putString("lastWritingPen", id).apply();
    if (InkGeometry.isShape(id)) preferences.edit().putString("lastGeometry", id).apply();
    if (PenPreset.isPen(id)) restorePenStyle(id);
    if (fromPenButton) ink.setToolAtButtonBoundary(id);
    else ink.setTool(id);
    ink.setInkColor(inkColor);
    ink.setInkWidth(inkWidth);
    ink.setPressureMode(pressureMode);
    refreshToolSelection();
    refreshPenControls();
    updateNavigationControls();
    refreshStudyStatus();
  }

  private void refreshStudyStatus() {
    if (textBoxButton != null
        && textBoxButton.color != ("text".equals(tool) ? Ui.CHROME_TEXT : Ui.CHROME_MUTED))
      textBoxButton.tint("text".equals(tool) ? Ui.CHROME_TEXT : Ui.CHROME_MUTED);
    boolean recording = studyTools != null && studyTools.recording();
    for (View view : toolViews) {
      String id = (String) view.getTag();
      if (!id.equals("laser") && !id.equals("tape") && !id.equals("audio")) continue;
      boolean active = id.equals(tool) || id.equals("audio") && recording;
      int color = id.equals("audio") && recording ? Ui.DANGER : active ? Ui.ACCENT : Ui.MUTED;
      if (view.isSelected() != active) {
        view.setSelected(active);
        view.setBackground(Ui.bg(this, active ? Ui.SELECTED : Ui.TOOLBAR, 12));
      }
      LinearLayout item = (LinearLayout) view;
      IconView glyph = (IconView) item.getChildAt(0);
      TextView caption = (TextView) item.getChildAt(1);
      if (glyph.color != color) glyph.tint(color);
      if (caption.getCurrentTextColor() != color) caption.setTextColor(color);
      if (id.equals("audio")) {
        String text = recording ? "● " + studyTools.recordingTime() : "录音";
        if (!text.contentEquals(caption.getText())) caption.setText(text);
      }
    }
  }

  private void refreshToolSelection() {
    for (View view : toolViews) {
      String id = (String) view.getTag();
      if (id.equals("laser") || id.equals("tape") || id.equals("audio")) continue;
      boolean active =
          id.equals(tool)
              || (id.equals("pen-family") && PenPreset.isPen(tool) && !tool.equals("highlighter"))
              || (id.equals("shape") && InkGeometry.isShape(tool));
      view.setBackground(Ui.bg(this, active ? Ui.SELECTED : Ui.TOOLBAR, 12));
      LinearLayout item = (LinearLayout) view;
      if (id.equals("pen-family") && PenPreset.isPen(tool) && !tool.equals("highlighter")) {
        ((IconView) item.getChildAt(0)).icon = tool;
        ((TextView) item.getChildAt(1)).setText(toolName(tool));
        item.setContentDescription(toolName(tool) + "，再次点击设置笔触");
      }
      if (id.equals("shape")) {
        ((IconView) item.getChildAt(0)).icon = active ? tool : "shape";
        item.setContentDescription(active ? "几何：" + geometryName(tool) : "几何");
      }
      ((IconView) item.getChildAt(0)).tint(active ? Ui.ACCENT : Ui.MUTED);
      ((TextView) item.getChildAt(1)).setTextColor(active ? Ui.ACCENT : Ui.MUTED);
      view.setSelected(active);
    }
    refreshStudyStatus();
  }

  private String geometryName(String id) {
    return GeometryPresets.name(id);
  }

  private void updateUndo() {
    if (ink == null) return;
    undoButton.setAlpha(ink.canUndo() ? 1 : .3f);
    redoButton.setAlpha(ink.canRedo() ? 1 : .3f);
    undoButton.setEnabled(ink.canUndo());
    redoButton.setEnabled(ink.canRedo());
  }

  private void loadPenPresets() {
    PenPreset[] defaults = {
      new PenPreset("pen", 0xff272d3b, 4, "standard"),
      new PenPreset("ballpoint", 0xff5269a2, 3, "standard"),
      new PenPreset("highlighter", 0xffedc663, 10, "off"),
      new PenPreset("pencil", 0xff626b7b, 3, "soft"),
      new PenPreset("fineliner", 0xffbd526d, 2, "off"),
      new PenPreset("brush", 0xff27877b, 6, "soft")
    };
    for (int i = 0; i < penPresets.length; i++) {
      penPresets[i] = defaults[i];
      String saved = preferences.getString("penPreset:" + i, "");
      try {
        if (!saved.isEmpty()) penPresets[i] = PenPreset.fromJson(new org.json.JSONObject(saved));
      } catch (org.json.JSONException ignored) {
        /* Keep a usable local default after corrupt preferences. */
      }
    }
  }

  private void rememberPenStyle() {
    if (!PenPreset.isPen(tool)) return;
    try {
      preferences
          .edit()
          .putString(
              "penStyle:" + tool,
              new PenPreset(tool, inkColor, inkWidth, pressureMode).toJson().toString())
          .apply();
    } catch (org.json.JSONException e) {
      error("无法保存笔偏好", e);
    }
  }

  private void restorePenStyle(String id) {
    PenPreset fallback =
        new PenPreset(
            id,
            "highlighter".equals(id) ? 0xffedc663 : 0xff272d3b,
            "highlighter".equals(id) ? 10 : "pencil".equals(id) ? 3 : 4,
            "standard");
    String saved = preferences.getString("penStyle:" + id, "");
    try {
      if (!saved.isEmpty()) fallback = PenPreset.fromJson(new org.json.JSONObject(saved));
    } catch (org.json.JSONException ignored) {
    }
    inkColor = fallback.color;
    inkWidth = fallback.width;
    pressureMode = fallback.pressureMode;
  }

  private String pressureName(String mode) {
    if ("off".equals(mode)) return "关闭";
    if ("soft".equals(mode)) return "轻柔";
    if ("firm".equals(mode)) return "用力";
    return "标准";
  }

  private void refreshPenControls() {
    if (widthLabel != null)
      widthLabel.setText(
          String.format(Locale.ROOT, "%.1f", "eraser".equals(tool) ? eraserWidth : inkWidth));
    if (colorButton != null) colorButton.setBackground(Ui.border(this, inkColor, 12, 0xffc6ccd8));
    if (pressureLabel != null) {
      pressureLabel.setText("压感 · " + pressureName(pressureMode));
      pressureLabel.setContentDescription("调整新笔迹压感，当前" + pressureName(pressureMode));
      pressureLabel.setEnabled(PenPreset.isPen(tool));
      pressureLabel.setAlpha(PenPreset.isPen(tool) ? 1 : .45f);
    }
    for (int i = 0; i < presetButtons.size(); i++) {
      TextView button = presetButtons.get(i);
      PenPreset preset = penPresets[i];
      button.setText("笔 " + (i + 1));
      button.setCompoundDrawablePadding(d(4));
      android.graphics.drawable.Drawable swatch = Ui.border(this, preset.color, 6, Ui.MUTED);
      swatch.setBounds(0, 0, d(10), d(10));
      button.setCompoundDrawables(swatch, null, null, null);
      button.setTextColor(Ui.TEXT);
      boolean active =
          tool.equals(preset.tool)
              && inkColor == preset.color
              && Math.abs(inkWidth - preset.width) < .01
              && pressureMode.equals(preset.pressureMode);
      button.setBackground(
          Ui.border(this, active ? Ui.SELECTED : Ui.SURFACE, 10, active ? Ui.ACCENT : Ui.LINE));
      button.setContentDescription(
          "常用笔 "
              + (i + 1)
              + "，宽度"
              + preset.width
              + "，压感"
              + pressureName(preset.pressureMode)
              + "；长按替换为当前笔");
      button.setSelected(active);
    }
    if (activePenPanelRefresh != null) activePenPanelRefresh.run();
  }

  private void applyPenPreset(int index) {
    rememberPenStyle();
    PenPreset preset = penPresets[index];
    tool = preset.tool;
    inkColor = preset.color;
    inkWidth = preset.width;
    pressureMode = preset.pressureMode;
    ink.setTool(tool);
    ink.setInkColor(inkColor);
    ink.setInkWidth(inkWidth);
    ink.setPressureMode(pressureMode);
    rememberPenStyle();
    refreshToolSelection();
    refreshPenControls();
  }

  private void savePenPreset(int index) {
    if (!PenPreset.isPen(tool)) {
      toast("先选择钢笔、圆珠笔、铅笔或荧光笔，再长按保存");
      return;
    }
    penPresets[index] = new PenPreset(tool, inkColor, inkWidth, pressureMode);
    try {
      preferences
          .edit()
          .putString("penPreset:" + index, penPresets[index].toJson().toString())
          .apply();
    } catch (org.json.JSONException e) {
      error("无法保存常用笔", e);
      return;
    }
    refreshPenControls();
    toast("已将当前笔保存为常用笔 " + (index + 1));
  }

  private void choosePressure() {
    String[] modes = {"standard", "soft", "firm", "off"};
    LinearLayout options = Ui.row(this);
    for (String mode : modes) {
      TextView choice =
          Ui.button(
              this,
              pressureName(mode),
              pressureMode.equals(mode),
              () -> {
                pressureMode = mode;
                ink.setPressureMode(pressureMode);
                rememberPenStyle();
                refreshPenControls();
                chooseInkWidth();
              });
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, d(48), 1);
      params.rightMargin = d(6);
      options.addView(choice, params);
    }
    showToolPanel("压感响应 · 只影响新笔迹", options, () -> {});
  }

  private void refreshSelectionActions() {
    if (selectionButton == null || ink == null) return;
    boolean selected = ink.hasSelection();
    selectionButton.setVisibility(selected ? View.VISIBLE : View.GONE);
    selectionButton.setText(
        "选区 · " + (ink.selectionStrokeCount() + ink.selectionElementCount()) + " 项");
    selectionButton.setContentDescription("选区操作");
    updateFooterDensity();
    InkView target = ink;
    if (selected && !objectActionsPanel && imageTransform == null)
      main.post(
          () -> {
            if (ink == target
                && target.hasSelection()
                && !objectActionsPanel
                && imageTransform == null) showSelectionActions();
          });
  }

  private void showSelectionActions() {
    if (ink == null || !ink.hasSelection()) return;
    LinearLayout actions = Ui.row(this);
    objectAction(
        actions,
        "trash",
        "删除选区",
        () -> {
          ink.deleteSelection();
          closeToolPanel();
        });
    objectAction(actions, "zoom-out", "缩小选区", () -> ink.transformSelection(.9f, 0));
    objectAction(actions, "zoom-in", "放大选区", () -> ink.transformSelection(1.1f, 0));
    objectAction(actions, "rotate", "选区旋转90°", () -> ink.transformSelection(1, 90));
    objectAction(actions, "copy", "复制选区", () -> copyObjects(false));
    objectAction(actions, "cut", "剪切选区", () -> copyObjects(true));
    objectAction(actions, "palette", "选区改色", () -> ink.recolorSelection(inkColor));
    objectAction(
        actions,
        "close",
        "取消选区编辑",
        () -> {
          ink.cancelSelectionEditing();
          closeToolPanel();
        });
    objectAction(
        actions,
        "check",
        "完成选区编辑",
        () -> {
          ink.finishSelectionEditing();
          closeToolPanel();
        });
    showQuickToolPanel("编辑选区", actions);
    objectActionsPanel = true;
  }

  private void objectAction(LinearLayout row, String glyph, String label, Runnable action) {
    row.addView(optionIcon(glyph, label, false, action), lp(50, 48));
  }

  private void showImageActions() {
    LinearLayout actions = Ui.row(this);
    objectAction(
        actions,
        "rotate",
        "图片旋转90°",
        () -> {
          if (imageTransform != null) imageTransform.rotateBy(90);
        });
    objectAction(actions, "copy", "复制图片", () -> copyObjects(false));
    objectAction(actions, "cut", "剪切图片", () -> copyObjects(true));
    objectAction(
        actions,
        "trash",
        "删除图片",
        () -> {
          if (imageTransform == null) return;
          NoteDocument.Element image = imageTransform.getElement();
          closeImageTransform();
          ink.mutate(() -> ink.getPage().elements.remove(image));
        });
    objectAction(
        actions,
        "close",
        "取消图片编辑",
        () -> {
          if (imageTransform != null) imageTransform.cancel();
        });
    objectAction(actions, "check", "完成图片编辑", this::closeImageTransform);
    showQuickToolPanel("图片编辑", actions);
    objectActionsPanel = true;
  }

  private void copyObjects(boolean cut) {
    if (ink == null) return;
    if (imageTransform != null) {
      NoteDocument.Element image = imageTransform.getElement();
      objectClipboard = InkView.SelectionData.image(image);
      if (cut) {
        closeImageTransform();
        ink.mutate(() -> ink.getPage().elements.remove(image));
      }
    } else if (ink.hasSelection()) {
      objectClipboard = ink.copySelection();
      if (cut) {
        ink.deleteSelection();
        closeToolPanel();
      }
    }
    if (pasteButton != null)
      pasteButton.setVisibility(objectClipboard == null ? View.GONE : View.VISIBLE);
    if (objectClipboard != null) toast(cut ? "已剪切，可在当前页或其他笔记粘贴" : "已复制，可在当前页或其他笔记粘贴");
  }

  private void pasteObjects() {
    if (ink == null || objectClipboard == null) return;
    closeImageTransform();
    closeToolPanel();
    selectTool("lasso", true);
    ink.pasteSelection(objectClipboard);
    if (ink.hasSelection()) {
      if (objectClipboard.strokes.isEmpty()
          && objectClipboard.elements.size() == 1
          && "image".equals(objectClipboard.elements.get(0).kind))
        editImage(ink.getPage().elements.get(ink.getPage().elements.size() - 1));
      else showSelectionActions();
    }
  }

  private void chooseShape() {
    if (!InkGeometry.isShape(tool)) selectTool(preferences.getString("lastGeometry", "line"));
    if (!InkGeometry.isShape(tool)) return;
    LinearLayout groups = Ui.row(this);
    for (int group = 0; group < GeometryPresets.GROUPS.length; group++) {
      LinearLayout section = Ui.column(this);
      TextView heading = Ui.text(this, GeometryPresets.GROUP_NAMES[group], 10, Ui.MUTED);
      section.addView(heading, lp(-1, 22));
      LinearLayout options = Ui.column(this);
      String[] shapes = GeometryPresets.GROUPS[group];
      int columns = shapes.length <= 3 ? shapes.length : (shapes.length + 1) / 2;
      for (int first = 0; first < shapes.length; first += columns) {
        LinearLayout line = Ui.row(this);
        for (int n = first; n < Math.min(shapes.length, first + columns); n++) {
          String shape = shapes[n];
          IconView choice =
              optionIcon(
                  shape,
                  GeometryPresets.name(shape),
                  tool.equals(shape),
                  () -> {
                    selectTool(shape);
                    chooseShape();
                  });
          LinearLayout.LayoutParams params = lp(44, 44);
          params.rightMargin = d(4);
          line.addView(choice, params);
        }
        options.addView(line, lp(-2, 44));
      }
      section.addView(options);
      LinearLayout.LayoutParams params = lp(-2, -2);
      params.rightMargin = d(16);
      groups.addView(section, params);
    }
    showQuickToolPanel("几何图形", groups);
  }

  private IconView optionIcon(String glyph, String label, boolean selected, Runnable action) {
    IconView choice = new IconView(this, glyph);
    choice.setContentDescription(label);
    choice.setTooltipText(label);
    choice.setOnClickListener(v -> action.run());
    styleOptionIcon(choice, selected);
    return choice;
  }

  private void styleOptionIcon(IconView choice, boolean selected) {
    choice.setSelected(selected);
    choice.tint(selected ? Ui.ACCENT : Ui.MUTED);
    android.graphics.drawable.LayerDrawable background =
        new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[] {
              Ui.bg(this, selected ? Ui.SELECTED : Ui.SURFACE, 12)
            });
    background.setLayerSize(0, d(40), d(40));
    background.setLayerGravity(0, Gravity.CENTER);
    choice.setBackground(background);
  }

  private void showQuickToolPanel(String title, View content) {
    showQuickToolPanel(title, content, null);
  }

  private void showQuickToolPanel(String title, View content, View leading) {
    if (studyTools != null && !studyTools.showingPanel() && !studyTools.finishText(true)) return;
    if (toolOptionsHost == null) return;
    boolean entering = toolOptionsHost.getVisibility() != View.VISIBLE;
    objectActionsPanel = false;
    activeToolPanelCommit = null;
    activePenPanelRefresh = null;
    paperPanelPage = null;
    LinearLayout panel = new InlineToolStrip(this);
    Ui.pad(panel, 12, 6);
    panel.setBackgroundColor(Ui.PANEL);
    if (leading == null) panel.addView(Ui.label(this, title, 11), lp(84, 44));
    else {
      LinearLayout.LayoutParams previewParams = lp(104, 80);
      previewParams.rightMargin = d(12);
      panel.addView(leading, previewParams);
    }
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setFillViewport(false);
    if (content instanceof HorizontalScrollView) {
      View child = ((HorizontalScrollView) content).getChildAt(0);
      ((HorizontalScrollView) content).removeView(child);
      content = child;
    }
    scroll.addView(content, new HorizontalScrollView.LayoutParams(-2, -2));
    panel.addView(scroll, new LinearLayout.LayoutParams(0, -2, 1));
    View doneButton =
        icon(
            "check",
            "完成",
            () -> {
              if (objectActionsPanel) {
                if (imageTransform != null) closeImageTransform();
                else if (ink != null) ink.finishSelectionEditing();
              }
              closeToolPanel();
            });
    doneButton.setTooltipText("完成");
    panel.addView(doneButton, lp(48, 48));
    toolOptionsHost.removeAllViews();
    toolOptionsHost.addView(panel, new FrameLayout.LayoutParams(-1, -2));
    Ui.tintControls(panel);
    toolOptionsHost.setVisibility(View.VISIBLE);
    activeToolPanelScroll = null;
    panel.setContentDescription("工具选项：" + title);
    if (entering) Ui.animateIn(panel);
  }

  private void applyInkColor(int color) {
    inkColor = color;
    ink.setInkColor(color);
    rememberPenStyle();
    refreshPenControls();
    LinkedHashSet<String> recent = new LinkedHashSet<>();
    recent.add(String.format(Locale.ROOT, "%08X", color));
    String saved = preferences.getString("recentInkColors", "");
    for (String value : saved.split(",")) if (value.matches("[0-9A-Fa-f]{8}")) recent.add(value);
    ArrayList<String> entries = new ArrayList<>(recent);
    preferences
        .edit()
        .putString(
            "recentInkColors", String.join(",", entries.subList(0, Math.min(6, entries.size()))))
        .apply();
  }

  private void chooseInkColor() {
    ArrayList<Integer> recent = new ArrayList<>();
    for (String value : preferences.getString("recentInkColors", "").split(","))
      if (value.matches("[0-9A-Fa-f]{8}")) recent.add((int) Long.parseLong(value, 16));
    int[] colors = new int[recent.size()];
    for (int i = 0; i < colors.length; i++) colors[i] = recent.get(i);
    ColorPickerView picker = new ColorPickerView(this, inkColor, colors);
    showToolPanel("墨水颜色", picker, () -> applyInkColor(picker.getColor()));
  }

  private void showToolPanel(String title, View content, Runnable done) {
    if (studyTools != null && !studyTools.showingPanel() && !studyTools.finishText(true)) return;
    inputSettingsChild = false;
    if (document == null) {
      View panelContent = content;
      if (content instanceof ScrollView && ((ScrollView) content).getChildCount() == 1) {
        View body = ((ScrollView) content).getChildAt(0);
        ((ScrollView) content).removeView(body);
        panelContent = body;
      }
      showLibraryPanel(
          title,
          panelContent,
          "完成",
          () -> {
            done.run();
            closeToolPanel();
          });
      return;
    }
    if (toolOptionsHost == null) return;
    boolean entering = toolOptionsHost.getVisibility() != View.VISIBLE;
    activeToolPanelCommit =
        () -> {
          if (!(content instanceof ColorPickerView) || ((ColorPickerView) content).isValidInput())
            done.run();
        };
    activePenPanelRefresh = null;
    paperPanelPage = null;
    LinearLayout panel = new InlineToolStrip(this);
    Ui.pad(panel, 12, 6);
    panel.setBackgroundColor(Ui.PANEL);
    TextView panelTitle = Ui.label(this, title, 11);
    panelTitle.setMaxLines(2);
    panel.addView(panelTitle, lp(84, 44));
    View doneButton =
        icon(
            "check",
            "完成",
            () -> {
              if (content instanceof ColorPickerView && !((ColorPickerView) content).isValidInput())
                return;
              done.run();
              closeToolPanel();
            });
    doneButton.setMinimumWidth(d(48));
    doneButton.setMinimumHeight(d(48));
    doneButton.setTooltipText("完成");
    View panelBody = content;
    if (content instanceof ScrollView && ((ScrollView) content).getChildCount() == 1) {
      panelBody = ((ScrollView) content).getChildAt(0);
      ((ScrollView) content).removeView(panelBody);
    }
    ToolPanelScrollView scroll = new ToolPanelScrollView(this);
    scroll.addView(panelBody, new ScrollView.LayoutParams(-1, -2));
    panel.addView(scroll, new LinearLayout.LayoutParams(0, -2, 1));
    panel.addView(doneButton, lp(48, 48));
    activeToolPanelScroll = scroll;
    toolOptionsHost.removeAllViews();
    toolOptionsHost.addView(panel, new FrameLayout.LayoutParams(-1, -2));
    Ui.tintControls(panel);
    toolOptionsHost.setVisibility(View.VISIBLE);
    panel.setContentDescription("工具选项：" + title);
    if (entering) Ui.animateIn(panel);
  }

  private void showPenBox() {
    LinearLayout content = Ui.column(this), row = Ui.row(this);
    content.addView(Ui.text(this, "轻点使用 · 长按用当前笔替换", 12, Ui.MUTED), lp(-1, 32));
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    ArrayList<View> tiles = new ArrayList<>();
    ArrayList<BrushPreviewView> previews = new ArrayList<>();
    ArrayList<IconView> glyphs = new ArrayList<>();
    ArrayList<TextView> names = new ArrayList<>();
    Runnable refresh =
        () -> {
          for (int i = 0; i < tiles.size(); i++) {
            PenPreset preset = penPresets[i];
            View tile = tiles.get(i);
            boolean chosen =
                tool.equals(preset.tool)
                    && inkColor == preset.color
                    && Math.abs(inkWidth - preset.width) < .01f;
            tile.setSelected(chosen);
            tile.setBackground(
                Ui.border(this, chosen ? Ui.SELECTED : Ui.FLOAT, 16, chosen ? Ui.ACCENT : Ui.LINE));
            previews.get(i).setStyle(preset.tool, preset.color, preset.width, preset.pressureMode);
            glyphs.get(i).icon = preset.tool;
            glyphs.get(i).tint(Ui.ACCENT);
            names
                .get(i)
                .setText(
                    toolName(preset.tool)
                        + " · "
                        + String.format(Locale.ROOT, "%.1f", preset.width));
            tile.setContentDescription("常用笔 " + (i + 1) + "，" + toolName(preset.tool) + "，长按替换");
          }
        };
    for (int i = 0; i < penPresets.length; i++) {
      final int slot = i;
      LinearLayout tile = Ui.column(this);
      Ui.pad(tile, 8, 6);
      LinearLayout label = Ui.row(this);
      IconView glyph = new IconView(this, penPresets[i].tool);
      glyph.setFocusable(false);
      glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      label.addView(glyph, lp(28, 28));
      TextView name = Ui.text(this, "", 11, Ui.TEXT);
      label.addView(name, new LinearLayout.LayoutParams(0, d(28), 1));
      tile.addView(label, lp(-1, 28));
      BrushPreviewView preview = new BrushPreviewView(this);
      tile.addView(preview, lp(-1, 48));
      tile.setOnClickListener(
          v -> {
            applyPenPreset(slot);
            refresh.run();
          });
      tile.setOnLongClickListener(
          v -> {
            savePenPreset(slot);
            refresh.run();
            return true;
          });
      Ui.press(tile);
      LinearLayout.LayoutParams params = lp(130, 92);
      params.rightMargin = d(8);
      row.addView(tile, params);
      tiles.add(tile);
      previews.add(preview);
      glyphs.add(glyph);
      names.add(name);
    }
    scroll.addView(row);
    content.addView(scroll, lp(-1, 100));
    refresh.run();
    showToolPanel("常用笔盒", content, () -> {});
    activePenPanelRefresh = refresh;
  }

  private void chooseWidth() {
    if ("eraser".equals(tool)) {
      chooseEraser();
      return;
    }
    chooseInkWidth();
  }

  private void chooseEraser() {
    LinearLayout options = Ui.row(this);
    LinearLayout modes = Ui.row(this);
    for (String mode : new String[] {"stroke", "segment"}) {
      IconView choice =
          optionIcon(
              "stroke".equals(mode) ? "erase-stroke" : "erase-segment",
              "stroke".equals(mode) ? "整笔擦除" : "局部擦除",
              mode.equals(eraserMode),
              () -> {
                eraserMode = mode;
                preferences.edit().putString("eraserMode", mode).apply();
                ink.setEraserMode(mode);
                refreshPenControls();
                for (int j = 0; j < modes.getChildCount(); j++) {
                  IconView item = (IconView) modes.getChildAt(j);
                  styleOptionIcon(item, mode.equals(item.getTag()));
                }
              });
      choice.setTag(mode);
      LinearLayout.LayoutParams params = lp(52, 48);
      params.rightMargin = d(8);
      modes.addView(choice, params);
    }
    options.addView(modes);
    TextView value = Ui.label(this, String.format(Locale.ROOT, "%.0f", eraserWidth), 13);
    value.setGravity(Gravity.CENTER);
    value.setContentDescription("橡皮直径");
    options.addView(value, lp(56, 48));
    android.widget.SeekBar width = new DragSlider(this);
    width.setMax(198);
    width.setProgress(Math.round(eraserWidth) - 2);
    width.setContentDescription("连续橡皮直径，2 到 200");
    width.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    width.setOnSeekBarChangeListener(
        new android.widget.SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean user) {
            if (!user) return;
            eraserWidth = progress + 2;
            ink.setEraserWidth(eraserWidth);
            value.setText(String.format(Locale.ROOT, "%.0f", eraserWidth));
          }

          public void onStartTrackingTouch(android.widget.SeekBar bar) {}

          public void onStopTrackingTouch(android.widget.SeekBar bar) {
            preferences.edit().putFloat("eraserWidth", eraserWidth).apply();
            refreshPenControls();
          }
        });
    options.addView(width, lp(240, 48));
    showQuickToolPanel("橡皮", options);
  }

  private void chooseInkWidth() {
    if (!PenPreset.isPen(tool)) selectTool(preferences.getString("lastWritingPen", "pen"));
    LinearLayout body = Ui.column(this);
    body.setMinimumWidth(d(388));
    LinearLayout identity = Ui.column(this);
    Ui.pad(identity, 10, 4);
    identity.setBackground(Ui.border(this, Ui.FLOAT, 14, Ui.LINE));
    LinearLayout identityTitle = Ui.row(this);
    View inkSwatch = new View(this);
    identityTitle.addView(inkSwatch, lp(7, 7));
    TextView penName = Ui.label(this, toolName(tool), 12);
    LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, d(28), 1);
    nameParams.leftMargin = d(7);
    identityTitle.addView(penName, nameParams);
    identity.addView(identityTitle, lp(-1, 28));
    BrushPreviewView preview = new BrushPreviewView(this);
    identity.addView(preview, lp(-1, 48));
    LinearLayout kinds = Ui.row(this);
    kinds.setGravity(Gravity.CENTER_VERTICAL);
    String[] types = {"pen", "ballpoint", "pencil", "brush", "fineliner", "highlighter"};
    for (String id : types) {
      IconView chip = optionIcon(id, "选择" + toolName(id), id.equals(tool), () -> {});
      chip.setTag(id);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(d(48), d(48), 1);
      params.rightMargin = d(6);
      kinds.addView(chip, params);
    }
    HorizontalScrollView kindsScroll = new HorizontalScrollView(this);
    kindsScroll.setHorizontalScrollBarEnabled(false);
    kindsScroll.setFillViewport(true);
    kindsScroll.addView(kinds);
    body.addView(kindsScroll, lp(-1, 48));
    LinearLayout widthRow = Ui.row(this);
    TextView value = Ui.label(this, "", 13);
    value.setGravity(Gravity.CENTER);
    widthRow.addView(value, lp(44, 48));
    android.widget.SeekBar width = new DragSlider(this);
    width.setMax(235);
    width.setContentDescription("连续笔宽，0.5 到 24 纸面单位");
    width.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    widthRow.addView(width, new LinearLayout.LayoutParams(d(120), d(48), 1));
    body.addView(widthRow, lp(-1, 48));
    LinearLayout pressureRow = Ui.row(this);
    pressureRow.setGravity(Gravity.CENTER);
    TextView pressureTitle = Ui.text(this, "压感", 12, Ui.MUTED);
    pressureTitle.setGravity(Gravity.CENTER_VERTICAL);
    pressureRow.addView(pressureTitle, lp(32, 48));
    LinearLayout pressures = Ui.row(this);
    for (String mode : new String[] {"standard", "soft", "firm", "off"}) {
      TextView choice = Ui.text(this, pressureName(mode), 12, Ui.TEXT);
      choice.setGravity(Gravity.CENTER);
      choice.setTag(mode);
      choice.setContentDescription("压感：" + pressureName(mode));
      pressures.addView(choice, lp(48, 48));
    }
    pressureRow.addView(pressures);
    widthRow.addView(pressureRow, lp(224, 48));
    Runnable refresh =
        () -> {
          penName.setText(toolName(tool));
          inkSwatch.setBackground(Ui.bg(this, inkColor | 0xff000000, 4));
          identity.setContentDescription("当前笔触：" + toolName(tool));
          preview.setStyle(tool, inkColor, inkWidth, pressureMode);
          value.setText(String.format(Locale.ROOT, "%.1f", inkWidth));
          value.setContentDescription("笔宽 " + value.getText());
          width.setProgress(Math.round((Math.max(.5f, Math.min(24, inkWidth)) - .5f) * 10));
          for (int i = 0; i < kinds.getChildCount(); i++) {
            IconView chip = (IconView) kinds.getChildAt(i);
            styleOptionIcon(chip, chip.getTag().equals(tool));
          }
          for (int i = 0; i < pressures.getChildCount(); i++) {
            TextView chip = (TextView) pressures.getChildAt(i);
            boolean active = chip.getTag().equals(pressureMode);
            chip.setBackground(Ui.bg(this, active ? Ui.SELECTED : Ui.SURFACE, 10));
            chip.setTextColor(active ? Ui.ACCENT : Ui.MUTED);
            chip.setSelected(active);
          }
        };
    for (int i = 0; i < kinds.getChildCount(); i++) {
      View chip = kinds.getChildAt(i);
      chip.setOnClickListener(
          v -> {
            selectTool((String) v.getTag());
            refresh.run();
          });
    }
    for (int i = 0; i < pressures.getChildCount(); i++) {
      View chip = pressures.getChildAt(i);
      chip.setOnClickListener(
          v -> {
            pressureMode = (String) v.getTag();
            ink.setPressureMode(pressureMode);
            rememberPenStyle();
            refresh.run();
          });
    }
    width.setOnSeekBarChangeListener(
        new android.widget.SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean user) {
            if (!user) return;
            inkWidth = .5f + progress / 10f;
            ink.setInkWidth(inkWidth);
            value.setText(String.format(Locale.ROOT, "%.1f", inkWidth));
            value.setContentDescription("笔宽 " + value.getText());
            widthLabel.setText(value.getText());
            preview.setStyle(tool, inkColor, inkWidth, pressureMode);
          }

          public void onStartTrackingTouch(android.widget.SeekBar bar) {}

          public void onStopTrackingTouch(android.widget.SeekBar bar) {
            rememberPenStyle();
            refreshPenControls();
          }
        });
    refresh.run();
    showQuickToolPanel("笔触", body, identity);
    activePenPanelRefresh = refresh;
  }

  private void refreshPages() {
    if (document == null || pageRail == null) return;
    updatePageLabel();
    pageRail.removeAllViews();
    pageThumbs.clear();
    if (!pagesVisible) return;
    TextView label = Ui.text(this, "纸页", 12, Ui.MUTED);
    label.setGravity(Gravity.CENTER);
    pageRail.addView(label, lp(-1, 36));
    ScrollView scroll = new ScrollView(this);
    LinearLayout contents = Ui.column(this);
    Ui.pad(contents, 11, 4);
    scroll.addView(contents);
    pageRail.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    for (int i = 0; i < document.pages.size(); i++) {
      final int index = i;
      NoteDocument.Page page = document.pages.get(i);
      LinearLayout cell = Ui.column(this);
      Ui.pad(cell, 5, 5);
      cell.setBackground(
          Ui.border(
              this,
              i == document.currentPage ? Ui.SELECTED : Ui.SURFACE,
              10,
              i == document.currentPage ? Ui.ACCENT : Ui.LINE));
      PageThumb thumb = new PageThumb(page);
      pageThumbs.add(thumb);
      cell.addView(thumb, lp(-1, 90));
      TextView num =
          Ui.text(
              this,
              String.format(Locale.ROOT, "%02d", i + 1),
              11,
              i == document.currentPage ? Ui.ACCENT : Ui.MUTED);
      num.setGravity(Gravity.CENTER);
      cell.addView(num, lp(-1, 24));
      cell.setContentDescription("第 " + (i + 1) + " 页，长按管理");
      cell.setOnClickListener(v -> switchPage(index));
      cell.setOnLongClickListener(
          v -> {
            pageMenu(index);
            return true;
          });
      LinearLayout.LayoutParams cp = lp(-1, 124);
      cp.bottomMargin = d(10);
      contents.addView(cell, cp);
    }
    IconView add = icon("plus", "增加空白页", this::addPage);
    pageRail.addView(add, new LinearLayout.LayoutParams(-1, d(48)));
    updatePageLabel();
  }

  private void updatePageLabel() {
    if (pageLabel != null)
      pageLabel.setText(
          document.pages.get(document.currentPage).infinite
              ? "无边记"
              : (document.currentPage + 1) + " / " + document.pages.size() + " 页");
  }

  private final class PageThumb extends View {
    final NoteDocument.Page page;
    Bitmap preview;
    int generation;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    PageThumb(NoteDocument.Page page) {
      super(NotesWorkspace.this);
      this.page = page;
      refresh();
    }

    void refresh() {
      int ticket = ++generation;
      renderPreview(
          page,
          156,
          220,
          bitmap -> {
            if (ticket == generation) {
              preview = bitmap;
              invalidate();
            }
          });
    }

    @Override
    protected void onDraw(Canvas c) {
      super.onDraw(c);
      float s = Math.min(getWidth() / page.width, getHeight() / page.height);
      RectF rect =
          new RectF(
              (getWidth() - page.width * s) / 2,
              (getHeight() - page.height * s) / 2,
              (getWidth() + page.width * s) / 2,
              (getHeight() + page.height * s) / 2);
      if (preview != null) c.drawBitmap(preview, null, rect, p);
      else {
        p.setColor(page.color);
        c.drawRect(rect, p);
      }
    }
  }

  private void renderPreview(
      NoteDocument.Page page, int width, int height, Consumer<Bitmap> ready) {
    NoteDocument.Page snapshot = page.copy();
    thumbnailWorker.execute(
        () -> {
          try {
            RectF extent =
                snapshot.infinite
                    ? InfiniteCanvas.contentBounds(snapshot)
                    : new RectF(0, 0, snapshot.width, snapshot.height);
            float scale = Math.min(width / extent.width(), height / extent.height());
            Bitmap bitmap =
                Bitmap.createBitmap(
                    Math.max(1, Math.round(extent.width() * scale)),
                    Math.max(1, Math.round(extent.height() * scale)),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            canvas.translate(-extent.left, -extent.top);
            PageRenderer.draw(canvas, snapshot, true);
            main.post(() -> ready.accept(bitmap));
          } catch (RuntimeException | OutOfMemoryError ignored) {
            /* The full page remains available; a failed preview never mutates its document. */
          }
        });
  }

  private void refreshCurrentThumbnail() {
    if (document != null && pagesVisible && document.currentPage < pageThumbs.size())
      pageThumbs.get(document.currentPage).refresh();
  }

  private void togglePages() {
    if (containerWidthDp() < 650) {
      showOutline();
      return;
    }
    pagesVisible = !pagesVisible;
    pageRail.setVisibility(pagesVisible ? View.VISIBLE : View.GONE);
    refreshPages();
  }

  private void switchPage(int index) {
    cancelCapture();
    closeImageTransform();
    if (document == null || index < 0 || index >= document.pages.size()) return;
    if (studyTools != null && !studyTools.finishText(true)) return;
    if (paperPanelPage != null) closeToolPanel();
    ink.setPage(index);
    if (focusPanel != null && focusPanel.getVisibility() == View.VISIBLE)
      focusWriting.refreshPage();
    document.currentPage = index;
    markDirty();
    refreshPages();
    updateUndo();
    updateNavigationControls();
  }

  private void addPage() {
    if (document == null) return;
    if (ink.getPage().infinite) {
      ink.fitContent();
      toast("无边记无需添加纸页，可向四周继续书写");
      return;
    }
    if (document.pages.size() >= NoteDocument.MAX_PAGES) {
      toast("每本笔记最多 " + NoteDocument.MAX_PAGES + " 页");
      return;
    }
    NoteDocument.Page current = document.pages.get(document.currentPage);
    NoteDocument.Page page = PaperCatalog.newPageLike(current);
    document.pages.add(page);
    switchPage(document.pages.size() - 1);
  }

  private void pageMenu(int index) {
    if (document == null || index < 0 || index >= document.pages.size()) return;
    if (document.pages.get(index).infinite) {
      ink.fitContent();
      return;
    }
    LinearLayout actions = Ui.row(this);
    TextView open =
        Ui.button(
            this,
            "打开",
            true,
            () -> {
              switchPage(index);
              closeToolPanel();
            });
    TextView duplicate =
        Ui.button(
            this,
            "复制页面",
            false,
            () -> {
              if (document.pages.size() >= NoteDocument.MAX_PAGES) {
                toast("页数已达上限");
                return;
              }
              document.pages.add(index + 1, document.pages.get(index).copyAsNew());
              switchPage(index + 1);
              closeToolPanel();
            });
    TextView delete = Ui.button(this, "删除", false, () -> confirmDeletePage(index));
    actions.addView(open, new LinearLayout.LayoutParams(0, d(48), 1));
    actions.addView(duplicate, new LinearLayout.LayoutParams(0, d(48), 1));
    actions.addView(delete, new LinearLayout.LayoutParams(0, d(48), 1));
    showToolPanel("第 " + (index + 1) + " 页", actions, () -> {});
  }

  private void confirmDeletePage(int index) {
    if (document == null || index < 0 || index >= document.pages.size()) return;
    if (document.pages.size() == 1) {
      toast("至少保留一页纸");
      return;
    }
    LinearLayout actions = Ui.row(this);
    actions.addView(
        Ui.button(this, "取消", false, this::closeToolPanel),
        new LinearLayout.LayoutParams(0, d(48), 1));
    actions.addView(
        Ui.button(
            this,
            "删除第 " + (index + 1) + " 页",
            false,
            () -> {
              if (document == null || index >= document.pages.size()) return;
              document.pages.remove(index);
              switchPage(Math.min(index, document.pages.size() - 1));
              closeToolPanel();
            }),
        new LinearLayout.LayoutParams(0, d(48), 1));
    showToolPanel("删除页面后无法撤销，建议先导出备份", actions, () -> {});
  }

  private void insertText() {
    if (studyTools != null) studyTools.text();
  }

  private void updateText(
      NoteDocument.Page page, NoteDocument.Element element, String text, float size) {
    NoteDocument.Element candidate = element.copy();
    candidate.text = text;
    candidate.textSize = size;
    if (!PageRenderer.fitTextToPage(page, candidate)) {
      toast("文字超过整页高度，请拆分文字或使用更小字号");
      return;
    }
    ink.mutate(
        () -> {
          element.text = candidate.text;
          element.textSize = candidate.textSize;
          element.height = candidate.height;
          element.y = candidate.y;
        });
  }

  private void manageElements() {
    if (document == null) return;
    NoteDocument.Page page = ink.getPage();
    if (page.elements.isEmpty()) {
      toast("当前页还没有文字或图片");
      return;
    }
    LinearLayout items = Ui.column(this);
    for (int i = 0; i < page.elements.size(); i++) {
      final int index = i;
      NoteDocument.Element element = page.elements.get(i);
      String preview =
          "image".equals(element.kind) ? "图片 " + (i + 1) : element.text.replace('\n', ' ');
      if (preview.length() > 48) preview = preview.substring(0, 48) + "…";
      TextView item =
          Ui.button(this, preview, false, () -> showElementActions(page, page.elements.get(index)));
      LinearLayout.LayoutParams params = lp(-1, 48);
      params.bottomMargin = d(4);
      items.addView(item, params);
    }
    showToolPanel("当前页文字与图片", items, () -> {});
  }

  private void showElementActions(NoteDocument.Page page, NoteDocument.Element element) {
    if (document == null || ink == null || page != ink.getPage()) return;
    LinearLayout body = Ui.column(this);
    if ("text".equals(element.kind)) {
      EditText edit = new EditText(this);
      edit.setFilters(
          new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(100000)});
      edit.setText(element.text);
      edit.setMinLines(3);
      edit.setGravity(Gravity.TOP);
      Ui.pad(edit, 18, 10);
      body.addView(edit, lp(-1, 112));
      body.addView(
          Ui.button(
              this,
              "保存文字",
              true,
              () -> {
                NoteDocument.Element candidate = element.copy();
                candidate.text = edit.getText().toString();
                if (!PageRenderer.fitTextToPage(page, candidate)) {
                  toast("文字超过整页高度，请拆分文字或使用更小字号");
                  return;
                }
                updateText(page, element, candidate.text, element.textSize);
                closeToolPanel();
              }),
          lp(-1, 44));
      LinearLayout sizes = Ui.row(this);
      float[] values = {16, 24, 36, 48, 72};
      for (float size : values) {
        TextView choice =
            Ui.button(
                this,
                (int) size + "",
                Math.abs(element.textSize - size) < .1f,
                () -> {
                  updateText(page, element, element.text, size);
                  showElementActions(page, element);
                });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, d(44), 1);
        params.rightMargin = d(4);
        sizes.addView(choice, params);
      }
      body.addView(Ui.label(this, "字号", 13), lp(-1, 34));
      body.addView(sizes, lp(-1, 44));
      body.addView(
          Ui.button(
              this,
              "使用当前墨水颜色",
              false,
              () -> {
                ink.mutate(() -> element.color = inkColor);
                closeToolPanel();
              }),
          lp(-1, 44));
      body.addView(
          Ui.button(
              this,
              element.targetPageId.isEmpty() ? "添加页引用" : "更换页引用目标",
              false,
              () -> chooseReferenceTarget(element)),
          lp(-1, 44));
      if (!element.targetPageId.isEmpty())
        body.addView(
            Ui.button(
                this,
                "移除页引用",
                false,
                () -> {
                  ink.mutate(() -> element.targetPageId = "");
                  closeToolPanel();
                }),
            lp(-1, 44));
    } else {
      LinearLayout scaleActions = Ui.row(this);
      scaleActions.addView(
          Ui.button(this, "缩小 20%", false, () -> resizeImageElement(page, element, .8f)),
          new LinearLayout.LayoutParams(0, d(44), 1));
      scaleActions.addView(
          Ui.button(this, "放大 20%", false, () -> resizeImageElement(page, element, 1.2f)),
          new LinearLayout.LayoutParams(0, d(44), 1));
      body.addView(scaleActions, lp(-1, 48));
    }
    body.addView(
        Ui.button(this, "删除元素…", false, () -> confirmDeleteElement(page, element)), lp(-1, 44));
    showToolPanel("文字与图片", body, () -> {});
  }

  private void resizeImageElement(
      NoteDocument.Page page, NoteDocument.Element element, float scale) {
    ink.mutate(
        () -> {
          element.width = Math.max(10, Math.min(page.width * 2, element.width * scale));
          element.height = Math.max(10, Math.min(page.height * 2, element.height * scale));
        });
    showElementActions(page, element);
  }

  private void confirmDeleteElement(NoteDocument.Page page, NoteDocument.Element element) {
    LinearLayout actions = Ui.row(this);
    actions.addView(
        Ui.button(this, "取消", false, () -> showElementActions(page, element)),
        new LinearLayout.LayoutParams(0, d(48), 1));
    actions.addView(
        Ui.button(
            this,
            "确认删除",
            false,
            () -> {
              ink.mutate(() -> page.elements.remove(element));
              closeToolPanel();
            }),
        new LinearLayout.LayoutParams(0, d(48), 1));
    TextView message = Ui.text(this, "此操作会从当前纸页移除这项内容。", 13, Ui.MUTED);
    LinearLayout body = Ui.column(this);
    body.addView(message, lp(-1, 42));
    body.addView(actions, lp(-1, 50));
    showToolPanel("删除元素？", body, () -> {});
  }

  private void showPaper() {
    if (document == null) return;
    NoteDocument.Page target = ink.getPage();
    PaperPickerView picker =
        new PaperPickerView(
            this,
            target,
            change -> {
              if (ink.getPage() != target) {
                closeToolPanel();
                return;
              }
              float oldWidth = target.width, oldHeight = target.height;
              ink.mutate(change);
              ink.refreshPaper();
              refreshPages();
              if (target.width != oldWidth || target.height != oldHeight) {
                if (ink.isWidthFitPreferred()) ink.fitWidth();
                else ink.fitPage();
              }
            },
            this::startPdfPageImport,
            this::startPaperImageImport);
    showToolPanel("纸张与材质", picker, () -> {});
    paperPanelPage = target;
  }

  private void showTemplateLibrary() {
    if (document != null) closeToolPanel();
    TemplatePickerView picker =
        new TemplatePickerView(
            this,
            new TemplatePickerView.Callback() {
              @Override
              public void onSelected(TemplateLibrary.Entry entry, int pageIndex) {
                applyTemplate(entry, pageIndex);
              }

              @Override
              public void onNotebookSelected(TemplateLibrary.Entry entry) {
                importTemplateNotebook(entry);
              }

              @Override
              public void onImportRequested() {
                launchOpen("*/*", IMPORT_TEMPLATE);
              }
            });
    showLibraryPanel("手账模板库", picker, "返回笔记", this::dismissLibraryPanel);
  }

  private void applyTemplate(TemplateLibrary.Entry entry, int pageIndex) {
    boolean free = document != null && ink.getPage().infinite;
    if (free) {
      ink.finishInput();
      saveCurrent(false);
    }
    final String target = document == null || free ? null : document.id;
    final String after = document == null ? null : ink.getPage().id;
    ProgressDialog progress = ProgressDialog.show(this, "使用模板", "准备纸张背景…", true, false);
    executeContent(
        () -> {
          try {
            NoteDocument.Page page = TemplateLibrary.createPage(this, entry, pageIndex);
            NoteDocument created = null;
            if (target == null) {
              created = NoteDocument.create(entry.title);
              created.pages.set(0, page);
              store.save(created);
            }
            final NoteDocument ready = created;
            main.post(
                () -> {
                  progress.dismiss();
                  if (isFinishing() || isDestroyed()) return;
                  dismissLibraryPanel();
                  if (target == null) {
                    openDocument(ready);
                    return;
                  }
                  if (document == null || !target.equals(document.id)) {
                    toast("笔记已切换，请重新选择模板");
                    return;
                  }
                  if (document.pages.size() >= NoteDocument.MAX_PAGES) {
                    toast("笔记页数已达上限，请新建笔记使用模板");
                    return;
                  }
                  int index = document.findPageIndex(after);
                  int at = index < 0 ? document.pages.size() : index + 1;
                  document.pages.add(at, page);
                  switchPage(at);
                  markDirty();
                  refreshPages();
                  toast("模板已作为新页加入，可直接书写");
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  progress.dismiss();
                  if (isFinishing() || isDestroyed()) return;
                  error("模板无法使用", error);
                });
          }
        });
  }

  private void importTemplateNotebook(TemplateLibrary.Entry entry) {
    if (document != null) {
      ink.finishInput();
      saveCurrent(false);
    }
    ProgressDialog progress = ProgressDialog.show(this, "整本导入", "读取封面、内页与目录…", true, false);
    executeContent(
        () -> {
          try {
            NoteDocument note = TemplateLibrary.createNotebook(this, entry);
            store.save(note);
            main.post(
                () -> {
                  progress.dismiss();
                  if (isFinishing() || isDestroyed()) return;
                  dismissLibraryPanel();
                  openDocument(note);
                  boolean linked = note.pages.stream().anyMatch(p -> !p.links.isEmpty());
                  toast(
                      "已导入 "
                          + note.pages.size()
                          + " 页，可直接书写；"
                          + (linked ? "手指轻点目录可跳页" : "请通过页列表翻页"));
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  progress.dismiss();
                  if (!isFinishing() && !isDestroyed()) error("整本导入失败", error);
                });
          }
        });
  }

  private void importPersonalTemplate(Uri uri) {
    ProgressDialog progress = ProgressDialog.show(this, "收藏模板", "复制并验证 PDF 或图片…", true, false);
    executeContent(
        () -> {
          try {
            TemplateLibrary.Entry entry = TemplateLibrary.saveImported(this, uri);
            main.post(
                () -> {
                  progress.dismiss();
                  if (isFinishing() || isDestroyed()) return;
                  showTemplateLibrary();
                  toast("已收藏“" + entry.title + "”，以后可离线复用");
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  progress.dismiss();
                  if (isFinishing() || isDestroyed()) return;
                  error("模板导入失败", error);
                });
          }
        });
  }

  private void markDirty() {
    libraryIndexDirty = true;
    libraryRevision++;
    if (document == null) return;
    dirty = true;
    revision++;
    document.modifiedAt = System.currentTimeMillis();
    if (saveLabel != null) {
      saveLabel.setText("有修改 · 等待保存");
      saveLabel.setTextColor(Ui.CHROME_MUTED);
    }
    main.removeCallbacks(delayedSave);
    main.postDelayed(delayedSave, 900);
  }

  private void saveCurrent(boolean explicit) {
    main.removeCallbacks(delayedSave);
    if (document == null) return;
    if (!dirty) {
      if (explicit) toast(pendingSaves > 0 ? "正在保存，请稍候…" : "所有修改已保存到本机");
      return;
    }
    final NoteDocument snapshot = committedSnapshot();
    final String id = snapshot.id;
    final int savedRevision = revision, session = documentSession;
    dirty = false;
    unsavedSnapshots.put(id, snapshot);
    if (saveLabel != null) {
      saveLabel.setText("正在保存…");
      saveLabel.setTextColor(Ui.CHROME_MUTED);
    }
    pendingSaves++;
    io.execute(
        () -> {
          try {
            store.save(snapshot);
            unsavedSnapshots.remove(id, snapshot);
            main.post(
                () -> {
                  pendingSaves--;
                  if (document != null
                      && id.equals(document.id)
                      && savedRevision == revision
                      && session == documentSession
                      && !dirty) {
                    if (saveLabel != null) {
                      saveLabel.setText("已保存到本机");
                      saveLabel.setTextColor(Ui.CHROME_MUTED);
                    }
                    if (explicit) toast("已保存到本机");
                  }
                });
          } catch (IOException e) {
            main.post(
                () -> {
                  pendingSaves--;
                  if (document != null && id.equals(document.id) && session == documentSession) {
                    dirty = true;
                    if (saveLabel != null) {
                      saveLabel.setText("保存失败 · 点击重试");
                      saveLabel.setTextColor(Ui.DANGER);
                      saveLabel.setOnClickListener(v -> saveCurrent(true));
                    }
                  }
                  showSaveFailure(id, e);
                });
          }
        });
  }

  private void showSaveFailure(String id, Exception e) {
    if (isFinishing() || isDestroyed()) return;
    new AlertDialog.Builder(this)
        .setTitle("未能保存笔记")
        .setMessage(e.getMessage() + "\n\n未保存的快照仍保留在本次运行内。请重试或导出备份。")
        .setNegativeButton("稍后", null)
        .setPositiveButton(
            "重试",
            (di, w) -> {
              if (document != null && id.equals(document.id)) {
                dirty = true;
                saveCurrent(true);
              } else {
                NoteDocument snapshot = unsavedSnapshots.get(id);
                if (snapshot != null)
                  io.execute(
                      () -> {
                        try {
                          store.save(snapshot);
                          unsavedSnapshots.remove(id, snapshot);
                          main.post(
                              () -> {
                                toast("重试保存成功");
                                if (document == null) showLibrary();
                              });
                        } catch (IOException retry) {
                          main.post(() -> showSaveFailure(id, retry));
                        }
                      });
              }
            })
        .show();
  }

  private NoteDocument committedSnapshot() {
    return ink != null && ink.getDocument() == document ? ink.snapshotDocument() : document.copy();
  }

  private View fileTile(String badge, String title, int color, boolean selected, Runnable action) {
    LinearLayout tile = Ui.column(this);
    tile.setGravity(Gravity.CENTER);
    Ui.pad(tile, 8, 8);
    tile.setBackground(
        Ui.border(this, selected ? Ui.SELECTED : Ui.FLOAT, 16, selected ? Ui.ACCENT : Ui.LINE));
    tile.addView(new FileBadgeView(this, badge, color), lp(-1, 44));
    TextView name = Ui.text(this, title, 11, Ui.TEXT);
    name.setGravity(Gravity.CENTER);
    name.setMaxLines(2);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    tile.addView(name, lp(-1, 32));
    tile.setContentDescription(title);
    tile.setSelected(selected);
    tile.setOnClickListener(v -> action.run());
    Ui.press(tile);
    return tile;
  }

  private void chooseImport() {
    LinearLayout content = Ui.column(this);
    HorizontalScrollView strip = new HorizontalScrollView(this);
    strip.setHorizontalScrollBarEnabled(false);
    LinearLayout formats = Ui.row(this);
    String[] badges = {"PDF", "PDF+", "DOCX", "TXT", "XLSX", "ONE", "INK", "MD", "IMG", "IKO"};
    String[] labels = {
      "PDF 新笔记",
      "PDF 纸页包",
      "Word 原版 · 推荐",
      "Word 文字提取 · 重排",
      "Excel 表格",
      "OneNote · 两种版式",
      "Nebo / Flex / Goodnotes",
      "Markdown 图文",
      "插入图片",
      "完整笔记备份"
    };
    int[] colors = {
      0xffc7606c,
      0xffb16a83,
      0xff4c7cd1,
      0xff6483b8,
      0xff3a956d,
      0xff9060bd,
      0xff7c80bc,
      0xff649180,
      0xff3f9677,
      0xffc18544
    };
    Runnable[] actions = {
      () -> launchOpen("application/pdf", IMPORT_PDF),
      () -> {
        if (document == null) launchOpen("application/pdf", IMPORT_PDF);
        else startPdfPageImport();
      },
      () ->
          launchOpen(
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
              IMPORT_OFFICE),
      () ->
          launchOpen(
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
              IMPORT_DOCX_TEXT),
      this::launchOfficeOpen,
      this::launchOfficeOpen,
      this::launchOfficeOpen,
      this::launchOfficeOpen,
      () -> {
        if (document != null) showRecentImages();
        else launchOpen("image/*", IMPORT_IMAGE_NOTE);
      },
      () -> launchOpen("*/*", IMPORT_BACKUP)
    };
    for (int i = 0; i < labels.length; i++) {
      final Runnable action = actions[i];
      View tile =
          fileTile(
              badges[i],
              labels[i],
              colors[i],
              false,
              () -> {
                dismissLibraryPanel();
                closeToolPanel();
                action.run();
              });
      LinearLayout.LayoutParams params = lp(116, 92);
      params.rightMargin = d(8);
      formats.addView(tile, params);
    }
    strip.addView(formats);
    content.addView(strip, lp(-1, 100));
    LinearLayout sources = Ui.row(this);
    sources.addView(Ui.text(this, "资料来自", 12, Ui.MUTED), lp(68, 48));
    for (String source : new String[] {"本机文件", "微信", "QQ", "WPS"}) {
      TextView item =
          Ui.button(
              this,
              source,
              false,
              () -> {
                if ("本机文件".equals(source)) {
                  dismissLibraryPanel();
                  closeToolPanel();
                  launchOfficeOpen();
                } else showImportHelp(source);
              });
      LinearLayout.LayoutParams params = lp(-2, 48);
      params.rightMargin = d(6);
      sources.addView(item, params);
    }
    HorizontalScrollView sourceScroll = new HorizontalScrollView(this);
    sourceScroll.setHorizontalScrollBarEnabled(false);
    sourceScroll.addView(sources);
    content.addView(sourceScroll, lp(-1, 52));
    TextView hint =
        Ui.text(
            this, "Word 默认按原版排版导入，保留公式并可手写批注；需要改文字时选择“Word 可编辑文字”。聊天文件可直接分享给 IKO。", 11, Ui.MUTED);
    content.addView(hint, lp(-1, 40));
    if (document != null) showToolPanel("导入资料", content, () -> {});
    else showLibraryPanel("导入资料", content, "完成", this::dismissLibraryPanel);
  }

  private void showImportHelp(String app) {
    LinearLayout content = Ui.column(this);
    String steps =
        "WPS".equals(app)
            ? "在 WPS 打开文档 → 分享 / 用其他应用打开 → 选择 IKO 笔记。\nDOCX 会在 IKO 本地排版，保留公式和分页后直接批注。"
            : "在" + app + "中打开收到的文件 → 右上角菜单 → 其他应用打开 / 分享 → 选择 IKO 笔记。\n图片可保存到相册，再从 IKO 的最近图片插入。";
    TextView instruction = Ui.text(this, steps, 14, Ui.TEXT);
    Ui.pad(instruction, 4, 12);
    content.addView(instruction, lp(-1, -2));
    String pkg =
        "微信".equals(app)
            ? "com.tencent.mm"
            : "QQ".equals(app) ? "com.tencent.mobileqq" : "cn.wps.moffice_eng";
    Intent open = getPackageManager().getLaunchIntentForPackage(pkg);
    if (open != null)
      content.addView(Ui.button(this, "打开" + app, false, () -> startActivity(open)), lp(-1, 48));
    content.addView(
        Ui.button(
            this,
            "从本机文件选取",
            false,
            () -> {
              dismissLibraryPanel();
              closeToolPanel();
              launchOfficeOpen();
            }),
        lp(-1, 48));
    if (document != null) showToolPanel(app + "文件导入", content, () -> {});
    else showLibraryPanel(app + "文件导入", content, "完成", this::dismissLibraryPanel);
  }

  private void launchOpen(String mime, int code) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType(mime);
    try {
      startActivityForResult(intent, code);
    } catch (android.content.ActivityNotFoundException e) {
      error("设备上没有文档选择器", e);
    }
  }

  private void launchOfficeOpen() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*");
    intent.putExtra(
        Intent.EXTRA_MIME_TYPES,
        new String[] {
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "application/msword",
          "application/vnd.ms-excel",
          "application/onenote",
          "application/octet-stream",
          "application/zip",
          "application/pdf",
          "text/plain",
          "text/markdown",
          "text/html"
        });
    try {
      startActivityForResult(intent, IMPORT_OFFICE);
    } catch (android.content.ActivityNotFoundException error) {
      error("设备上没有文档选择器", error);
    }
  }

  private void startImageImport() {
    if (document == null) return;
    imageTargetId = document.id;
    imagePageId = ink.getPage().id;
    launchOpen("image/*", IMPORT_IMAGE);
  }

  private boolean hasGalleryAccess() {
    if (android.os.Build.VERSION.SDK_INT >= 34)
      return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
              == PackageManager.PERMISSION_GRANTED
          || checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
              == PackageManager.PERMISSION_GRANTED;
    if (android.os.Build.VERSION.SDK_INT >= 33)
      return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
          == PackageManager.PERMISSION_GRANTED;
    return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void requestGalleryAccess() {
    String[] permissions;
    if (android.os.Build.VERSION.SDK_INT >= 34)
      permissions =
          new String[] {
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
          };
    else if (android.os.Build.VERSION.SDK_INT >= 33)
      permissions = new String[] {Manifest.permission.READ_MEDIA_IMAGES};
    else permissions = new String[] {Manifest.permission.READ_EXTERNAL_STORAGE};
    requestPermissions(permissions, REQUEST_GALLERY);
  }

  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    permissionRequests.remove(requestCode);
    if (requestCode == StudyTools.MICROPHONE_PERMISSION && studyTools != null)
      studyTools.microphoneResult();
    if (requestCode == REQUEST_GALLERY) showRecentImages();
  }

  private void showRecentImages() {
    if (document == null || ink == null) return;
    imageTargetId = document.id;
    imagePageId = ink.getPage().id;
    LinearLayout body = Ui.column(this);
    TextView hint = Ui.text(this, "点一张图片直接放进当前页；更多图片会打开系统相册。", 12, Ui.MUTED);
    body.addView(hint, lp(-1, 34));
    if (!hasGalleryAccess()) {
      body.addView(Ui.button(this, "允许显示最近图片", true, this::requestGalleryAccess), lp(-1, 48));
      body.addView(Ui.button(this, "更多图片…", false, this::startImageImport), lp(-1, 48));
      showToolPanel("插入图片", body, () -> {});
      requestGalleryAccess();
      return;
    }
    HorizontalScrollView recentScroll = new HorizontalScrollView(this);
    recentScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout recent = Ui.row(this);
    recent.setGravity(Gravity.CENTER_VERTICAL);
    recentScroll.addView(recent);
    body.addView(recentScroll, lp(-1, 112));
    TextView more = Ui.button(this, "＋ 更多图片", false, this::startImageImport);
    LinearLayout.LayoutParams moreParams = lp(-1, 44);
    moreParams.topMargin = d(4);
    body.addView(more, moreParams);
    showToolPanel("最近图片", body, () -> {});
    thumbnailWorker.execute(
        () -> {
          ArrayList<Uri> uris = new ArrayList<>();
          try (Cursor cursor =
              getContentResolver()
                  .query(
                      android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                      new String[] {android.provider.MediaStore.Images.Media._ID},
                      null,
                      null,
                      android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
              int idColumn =
                  cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID);
              while (cursor.moveToNext() && uris.size() < 10) {
                uris.add(
                    android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn)));
              }
            }
          } catch (Exception ignored) {
            // A partial photo grant or a provider without a recent-media query can return no items.
          }
          ArrayList<Uri> result = uris;
          main.post(
              () -> {
                if (isFinishing()
                    || toolOptionsHost == null
                    || toolOptionsHost.getVisibility() != View.VISIBLE) return;
                recent.removeAllViews();
                if (result.isEmpty()) {
                  TextView empty = Ui.text(this, "相册暂时没有可显示的图片", 12, Ui.MUTED);
                  recent.addView(empty, lp(220, 88));
                }
                for (Uri photo : result) {
                  ImageView thumbnail = new ImageView(this);
                  thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                  thumbnail.setBackground(Ui.bg(this, Ui.FLOAT, 10));
                  thumbnail.setContentDescription("插入最近图片");
                  thumbnail.setOnClickListener(
                      v -> importRecentImage(photo, imageTargetId, imagePageId));
                  LinearLayout.LayoutParams tile = lp(94, 88);
                  tile.rightMargin = d(8);
                  recent.addView(thumbnail, tile);
                  thumbnailWorker.execute(
                      () -> {
                        try {
                          android.graphics.Bitmap bitmap =
                              getContentResolver()
                                  .loadThumbnail(
                                      photo, new android.util.Size(d(188), d(176)), null);
                          main.post(
                              () -> {
                                if (!isFinishing()) thumbnail.setImageBitmap(bitmap);
                              });
                        } catch (Exception ignored) {
                          // The provider may revoke a photo between listing it and decoding its
                          // preview.
                        }
                      });
                }
              });
        });
  }

  private void importRecentImage(Uri uri, String targetId, String pageId) {
    ProgressDialog progress = ProgressDialog.show(this, "正在插入图片", "复制到笔记…", true, false);
    executeContent(
        () -> {
          try {
            String path = MediaIo.importImage(this, uri);
            main.post(
                () -> {
                  progress.dismiss();
                  if (document == null || !document.id.equals(targetId)) {
                    toast("笔记已切换，请重新选择图片");
                    return;
                  }
                  int pageIndex = document.findPageIndex(pageId);
                  if (pageIndex < 0) {
                    toast("原页面已不存在");
                    return;
                  }
                  if (document.currentPage != pageIndex) switchPage(pageIndex);
                  closeToolPanel();
                  addImageToCurrentPage(path);
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  progress.dismiss();
                  error("图片插入失败", error);
                });
          }
        });
  }

  private void closeToolPanel() {
    if (studyTools != null) studyTools.finishText(true);
    if (studyTools != null && studyTools.editingText()) return;
    pendingImportChoice = null;
    objectActionsPanel = false;
    if (inputSettingsChild) {
      inputSettingsChild = false;
      learningKeyActive = false;
      learningReadout = null;
      showInputSettings();
      return;
    }
    if (document == null) {
      dismissLibraryPanel();
      return;
    }
    paperPanelPage = null;
    activePenPanelRefresh = null;
    activeToolPanelCommit = null;
    if (toolOptionsHost == null) return;
    ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(root.getWindowToken(), 0);
    View closing = toolOptionsHost.getChildCount() == 0 ? null : toolOptionsHost.getChildAt(0);
    if (closing != null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
      FrameLayout host = toolOptionsHost;
      closing.animate().cancel();
      closing
          .animate()
          .alpha(0)
          .translationY(-d(5))
          .setDuration(140)
          .setInterpolator(Ui.EASE)
          .withEndAction(
              () -> {
                if (closing.getParent() == host) host.removeView(closing);
                if (host.getChildCount() == 0) host.setVisibility(View.GONE);
              })
          .start();
    } else {
      toolOptionsHost.removeAllViews();
      toolOptionsHost.setVisibility(View.GONE);
    }
    activeToolPanelScroll = null;
  }

  private void startPdfPageImport() {
    if (document == null || ink == null) return;
    if (ink.getPage().infinite) {
      toast("PDF 资料将作为独立笔记打开，保留无边记画布");
      launchOpen("application/pdf", IMPORT_PDF);
      return;
    }
    imageTargetId = document.id;
    imagePageId = ink.getPage().id;
    launchOpen("application/pdf", IMPORT_PDF_PAGES);
  }

  private void startPaperImageImport() {
    if (document == null || ink == null) return;
    imageTargetId = document.id;
    imagePageId = ink.getPage().id;
    launchOpen("image/*", IMPORT_PAPER_IMAGE);
  }

  private void consumeIncomingIntent(Intent intent) {
    if (intent == null) return;
    String action = intent.getAction();
    if(!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_VIEW.equals(action))return;
    Uri uri = null;
    if (Intent.ACTION_SEND.equals(action)) {
      if (android.os.Build.VERSION.SDK_INT >= 33)
        uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
      else {
        @SuppressWarnings("deprecation")
        Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        uri = stream;
      }
      if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0)
        uri = intent.getClipData().getItemAt(0).getUri();
    } else if (Intent.ACTION_VIEW.equals(action)) uri = intent.getData();
    if (uri != null) receiveSharedFile(uri, intent.getType());
    Intent consumed=new Intent(intent).setAction(Intent.ACTION_MAIN).setDataAndType(null,null);
    consumed.removeExtra(Intent.EXTRA_STREAM);consumed.setClipData(null);
    setIntent(consumed);
  }

  private void receiveSharedFile(Uri uri, String mime) {
    String name = withMimeExtension(displayName(uri), mime).toLowerCase(Locale.ROOT);
    if (name.endsWith(".one") || name.endsWith(".onepkg")) {
      chooseOneNoteLayout(uri, mime);
      return;
    }
    receiveSharedFile(uri, mime, true);
  }

  private void chooseOneNoteLayout(Uri uri, String mime) {
    saveCurrent(false);
    LinearLayout body = Ui.column(this);
    body.addView(Ui.text(this, "两种方式都保留矢量内容，可放大并添加手写批注。", 12, Ui.MUTED), lp(-1, 34));
    LinearLayout choices = Ui.row(this);
    for (boolean infinite : new boolean[] {true, false}) {
      LinearLayout card = Ui.column(this);
      Ui.pad(card, 14, 8);
      card.setGravity(Gravity.CENTER);
      boolean lastUsed = preferences.getBoolean("oneNoteInfinite", true) == infinite;
      card.setBackground(
          Ui.border(this, lastUsed ? Ui.SELECTED : Ui.SURFACE, 16, lastUsed ? Ui.ACCENT : Ui.LINE));
      card.addView(new IconView(this, infinite ? "infinite" : "pages"), lp(48, 48));
      TextView title = Ui.text(this, infinite ? "无边记" : "分页笔记", 16, Ui.TEXT);
      title.setGravity(Gravity.CENTER);
      card.addView(title, lp(-1, 28));
      TextView detail =
          Ui.text(
              this,
              (infinite ? "原页连续展开，自由缩放书写" : "A4 分页，可连续滚动与打印") + (lastUsed ? " · 上次使用" : ""),
              11,
              Ui.MUTED);
      detail.setGravity(Gravity.CENTER);
      card.addView(detail, lp(-1, 32));
      card.setContentDescription(infinite ? "以无边记导入 OneNote" : "以分页笔记导入 OneNote");
      card.setOnClickListener(
          v -> {
            preferences.edit().putBoolean("oneNoteInfinite", infinite).apply();
            dismissLibraryPanel();
            closeToolPanel();
            receiveSharedFile(uri, mime, infinite);
          });
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, d(132), 1);
      params.setMargins(d(4), 0, d(4), d(4));
      choices.addView(card, params);
    }
    body.addView(choices, lp(-1, 140));
    if (document == null) showLibraryPanel("OneNote 导入方式", body, "取消", this::dismissLibraryPanel);
    else showToolPanel("OneNote 导入方式", body, () -> {});
    pendingImportChoice = () -> chooseOneNoteLayout(uri, mime);
  }

  private void receiveSharedFile(Uri uri, String mime, boolean oneNoteInfinite) {
    String name = withMimeExtension(displayName(uri), mime);
    String lower = name.toLowerCase(Locale.ROOT);
    boolean image = mime != null && mime.startsWith("image/");
    String target = image && document != null ? document.id : null;
    String pageId = target == null || ink == null ? null : ink.getPage().id;
    ProgressDialog progress = ProgressDialog.show(this, "正在接收文件", name, true, false);
    executeContent(
        () -> {
          try {
            if (image && target != null) {
              String path = MediaIo.importImage(this, uri);
              main.post(
                  () -> {
                    progress.dismiss();
                    if (document == null || !document.id.equals(target)) {
                      toast("笔记已切换，请重新分享图片");
                      return;
                    }
                    int index = document.findPageIndex(pageId);
                    if (index < 0) {
                      toast("原页面已不存在，请重新分享图片");
                      return;
                    }
                    if (document.currentPage != index) switchPage(index);
                    addImageToCurrentPage(path);
                  });
              return;
            }
            NoteDocument imported;
            if (lower.endsWith(".pdf") || "application/pdf".equals(mime))
              imported = MediaIo.importPdf(this, uri);
            else if (image) {
              String path = MediaIo.importImage(this, uri);
              imported = NoteDocument.create(officeTitle(name));
              imported.pages.get(0).elements.add(createImageElement(imported.pages.get(0), path));
            } else {
              imported = importOtherDocument(uri, name, oneNoteInfinite);
            }
            store.save(imported);
            NoteDocument ready = imported;
            main.post(
                () -> {
                  progress.dismiss();
                  if (isFinishing() || isDestroyed()) return;
                  openDocument(ready);
                  if (!ready.tags.contains("Word 原版")) showSavedImportReport(ready);
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  progress.dismiss();
                  if (isFinishing() || isDestroyed()) return;
                  error("文件导入失败", error);
                });
          }
        });
  }

  private String displayName(Uri uri) {
    try (Cursor cursor =
        getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        String name = cursor.getString(0);
        if (name != null && !name.trim().isEmpty()) return Uri.decode(name);
      }
    } catch (Exception ignored) {
      // Some share providers expose a URI but no display name.
    }
    String path = uri.getLastPathSegment();
    return path == null || path.trim().isEmpty() ? "共享文档" : Uri.decode(path);
  }

  private NoteDocument importOtherDocument(Uri uri, String name) throws IOException {
    return importOtherDocument(uri, name, preferences.getBoolean("oneNoteInfinite", true));
  }

  private NoteDocument importOtherDocument(Uri uri, String name, boolean oneNoteInfinite)
      throws IOException {
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".ikonote")
        || ((lower.endsWith(".zip") || !name.contains(".")) && MediaIo.isBackup(this, uri)))
      return MediaIo.importBackup(this, uri);
    if (NativeNotebookImport.supports(name)) {
      NativeNotebookImport.Result result = NativeNotebookImport.read(this, uri, name);
      for (NoteDocument note : result.documents) saveImportReport(note, result.description());
      for (int i = 1; i < result.documents.size(); i++) store.save(result.documents.get(i));
      return result.documents.get(0);
    }
    if (lower.endsWith(".docx")) {
      NoteDocument imported = DocxImport.importDocument(this, uri, name);
      saveImportReport(
          imported,
          "已在平板本地排版为矢量页面，保留公式、表格、图片及原始分页，可直接手写批注。原 DOCX 已保留。需要修改文字时，可从导入栏选择“Word"
              + " 可编辑文字”；该模式会重新排版。缺少原字体时使用兼容字体。");
      return imported;
    }
    if (lower.endsWith(".md")) {
      RichTextImport.Result result = RichTextImport.importWithReport(this, uri, name);
      saveImportReport(result.document, result.description());
      return result.document;
    }
    if (lower.endsWith(".pdf")) return MediaIo.importPdf(this, uri);
    if (lower.endsWith(".one") || lower.endsWith(".onepkg"))
      return OneNoteLocalImporter.importDocument(this, uri, name, oneNoteInfinite);
    try (InputStream stream = getContentResolver().openInputStream(uri)) {
      return OfficeImport.importDocument(name, stream);
    }
  }

  private File importReportFile(NoteDocument note) {
    return new File(getFilesDir(), "import-reports/" + note.id + ".txt");
  }

  private void saveImportReport(NoteDocument note, String report) throws IOException {
    File file = importReportFile(note);
    if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
      throw new IOException("无法保存导入报告");
    java.nio.file.Files.write(
        file.toPath(), report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private void showSavedImportReport(NoteDocument note) {
    File file = importReportFile(note);
    if (!file.isFile()) return;
    try {
      String report =
          new String(
              java.nio.file.Files.readAllBytes(file.toPath()),
              java.nio.charset.StandardCharsets.UTF_8);
      new AlertDialog.Builder(this)
          .setTitle("导入结果与范围")
          .setMessage(report)
          .setPositiveButton("知道了", null)
          .show();
    } catch (IOException e) {
      error("无法读取导入报告", e);
    }
  }

  private String withMimeExtension(String name, String mime) {
    if (name.lastIndexOf('.') > 0 || mime == null) return name;
    String extension;
    switch (mime) {
      case "application/pdf":
        extension = ".pdf";
        break;
      case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
        extension = ".docx";
        break;
      case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
        extension = ".xlsx";
        break;
      case "application/msword":
        extension = ".doc";
        break;
      case "application/vnd.ms-excel":
        extension = ".xls";
        break;
      case "application/onenote":
        extension = ".one";
        break;
      case "application/vnd.iko.notes":
        extension = ".ikonote";
        break;
      case "text/markdown":
        extension = ".md";
        break;
      case "text/html":
        extension = ".html";
        break;
      case "text/plain":
        extension = ".txt";
        break;
      default:
        return name;
    }
    return name + extension;
  }

  private String officeTitle(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  private void addImageToCurrentPage(String path) {
    if (document == null || ink == null) return;
    NoteDocument.Page page = ink.getPage();
    NoteDocument.Element element = createImageElement(page, path);
    ink.mutate(() -> page.elements.add(element));
    selectTool("lasso");
  }

  private NoteDocument.Element createImageElement(NoteDocument.Page page, String path) {
    NoteDocument.Element element = new NoteDocument.Element();
    element.kind = "image";
    element.path = path;
    element.x = page.width * .15f;
    element.y = page.height * .18f;
    element.width = page.width * .7f;
    android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    android.graphics.BitmapFactory.decodeFile(path, options);
    float ratio = options.outWidth > 0 ? options.outHeight / (float) options.outWidth : .7f;
    element.height = Math.min(page.height * .65f, element.width * ratio);
    if (page.infinite && ink != null && ink.getPage() == page) {
      PageNavigation.Location at = ink.captureViewport();
      element.x = at.centerX - element.width / 2;
      element.y = at.centerY - element.height / 2;
    }
    return element;
  }

  private void chooseExport() {
    if (document == null) return;
    if (studyTools != null) {
      studyTools.finishText(true);
      studyTools.finishRecording();
    }
    LinearLayout content = Ui.column(this), formats = Ui.row(this), targets = Ui.row(this);
    int[] choices = {EXPORT_PDF, EXPORT_PNG, EXPORT_BACKUP};
    String[] badges = {"PDF", "PNG", "IKO"}, names = {"全部页面 PDF", "当前页面 PNG", "完整可编辑备份"};
    int[] colors = {0xffc7606c, 0xff3f9677, 0xff6c74c8};
    Runnable refreshTargets =
        () -> {
          targets.removeAllViews();
          String mime =
              shareFormat == EXPORT_PDF
                  ? "application/pdf"
                  : shareFormat == EXPORT_PNG ? "image/png" : MainlandShare.BACKUP_MIME;
          for (MainlandShare.Target target : MainlandShare.targets(this, mime)) {
            LinearLayout tile = Ui.column(this);
            tile.setGravity(Gravity.CENTER);
            Ui.pad(tile, 6, 4);
            if (target.packageName != null) {
              ImageView image = new ImageView(this);
              try {
                image.setImageDrawable(getPackageManager().getApplicationIcon(target.packageName));
              } catch (PackageManager.NameNotFoundException error) {
                continue;
              }
              tile.addView(image, lp(32, 32));
            } else tile.addView(new IconView(this, "share"), lp(32, 32));
            TextView label = Ui.text(this, target.label, 11, Ui.TEXT);
            label.setGravity(Gravity.CENTER);
            tile.addView(label, lp(-1, 24));
            tile.setContentDescription("分享到" + target.label);
            tile.setOnClickListener(v -> prepareShare(shareFormat, target));
            Ui.press(tile);
            targets.addView(tile, lp(76, 64));
          }
          for (int i = 0; i < formats.getChildCount(); i++) {
            boolean active = choices[i] == shareFormat;
            View tile = formats.getChildAt(i);
            tile.setSelected(active);
            tile.setBackground(
                Ui.border(this, active ? Ui.SELECTED : Ui.FLOAT, 16, active ? Ui.ACCENT : Ui.LINE));
          }
        };
    for (int i = 0; i < choices.length; i++) {
      final int format = choices[i];
      View tile =
          fileTile(
              badges[i],
              names[i],
              colors[i],
              shareFormat == format,
              () -> {
                shareFormat = format;
                refreshTargets.run();
              });
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, d(84), 1);
      params.rightMargin = d(8);
      formats.addView(tile, params);
    }
    content.addView(formats, lp(-1, 88));
    HorizontalScrollView shareScroll = new HorizontalScrollView(this);
    shareScroll.setHorizontalScrollBarEnabled(false);
    shareScroll.addView(targets);
    content.addView(shareScroll, lp(-1, 64));
    content.addView(Ui.button(this, "保存到本机文件", false, () -> startExport(shareFormat)), lp(-1, 48));
    refreshTargets.run();
    showToolPanel("分享与导出", content, () -> {});
  }

  private void prepareShare(int format, MainlandShare.Target target) {
    saveCurrent(false);
    NoteDocument snapshot = committedSnapshot();
    String extension = format == EXPORT_PDF ? "pdf" : format == EXPORT_PNG ? "png" : "ikonote";
    String mime =
        format == EXPORT_PDF
            ? "application/pdf"
            : format == EXPORT_PNG ? "image/png" : MainlandShare.BACKUP_MIME;
    ProgressDialog progress =
        ProgressDialog.show(
            this, "准备分享", "正在生成" + extension.toUpperCase(Locale.ROOT) + "…", true, false);
    executeContent(
        () -> {
          try {
            File dir = new File(getCacheDir(), "share");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建分享文件夹");
            File[] previous = dir.listFiles();
            if (previous != null)
              for (File old : previous)
                if (old.isFile()
                    && old.lastModified() < System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
                  old.delete();
            String title = snapshot.title.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (title.length() > 48) title = title.substring(0, 48);
            File file = new File(dir, title + "-" + System.currentTimeMillis() + "." + extension);
            try (OutputStream out = new java.io.FileOutputStream(file)) {
              if (format == EXPORT_PDF) MediaIo.exportPdf(snapshot, out);
              else if (format == EXPORT_PNG)
                MediaIo.exportPng(snapshot.pages.get(snapshot.currentPage), out);
              else MediaIo.exportBackup(this, snapshot, out);
            }
            main.post(
                () -> {
                  progress.dismiss();
                  try {
                    MainlandShare.share(activity, file, mime, target);
                  } catch (IOException
                      | android.content.ActivityNotFoundException
                      | SecurityException error) {
                    error("无法打开分享目标", error);
                  }
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  progress.dismiss();
                  error("准备分享失败", error);
                });
          }
        });
  }

  private void startExport(int format) {
    if (document == null) return;
    closeToolPanel();
    saveCurrent(false);
    pendingExport = committedSnapshot();
    String extension = format == EXPORT_PDF ? ".pdf" : format == EXPORT_PNG ? ".png" : ".ikonote";
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType(
        format == EXPORT_PDF
            ? "application/pdf"
            : format == EXPORT_PNG ? "image/png" : "application/octet-stream");
    intent.putExtra(
        Intent.EXTRA_TITLE, document.title.replaceAll("[\\\\/:*?\"<>|]", "_") + extension);
    try {
      startActivityForResult(intent, format);
    } catch (android.content.ActivityNotFoundException error) {
      pendingExport = null;
      error("设备上没有文档保存器", error);
    }
  }

  public void onActivityResult(int request, int result, Intent data) {
    activityRequests.remove(request);
    if (request == REQUEST_SCREEN_CAPTURE) {
      if (result != RESULT_OK || data == null) {
        toast("已取消屏幕截取");
        return;
      }
      Intent service = new Intent(this, ScreenCaptureService.class);
      service.putExtra("resultCode", result);
      service.putExtra("resultData", data);
      try {
        startForegroundService(service);
      } catch (Exception error) {
        toast("无法启动截屏服务：" + error.getMessage());
      }
      return;
    }
    if (result != RESULT_OK || data == null || data.getData() == null) {
      if (request >= EXPORT_PDF && request <= EXPORT_BACKUP) pendingExport = null;
      if (request == IMPORT_COVER_IMAGE && creatingNote != null) showNoteComposer();
      return;
    }
    Uri uri = data.getData();
    if (request == IMPORT_OFFICE) {
      String name = displayName(uri).toLowerCase(Locale.ROOT);
      if (name.endsWith(".one") || name.endsWith(".onepkg")) {
        chooseOneNoteLayout(uri, null);
        return;
      }
    }
    if (request == IMPORT_TEMPLATE) {
      importPersonalTemplate(uri);
      return;
    }
    if (request == IMPORT_IMAGE_NOTE) {
      receiveSharedFile(uri, "image/*");
      return;
    }
    if (request == IMPORT_PDF
        || request == IMPORT_BACKUP
        || request == IMPORT_OFFICE
        || request == IMPORT_DOCX_TEXT
        || request == IMPORT_PDF_PAGES) {
      saveCurrent(false);
      ProgressDialog progress =
          ProgressDialog.show(
              this,
              "正在导入",
              request == IMPORT_PDF || request == IMPORT_PDF_PAGES
                  ? "复制并解析 PDF 页面…"
                  : request == IMPORT_OFFICE
                      ? "正在本机排版文档、解析笔迹和附件…"
                      : request == IMPORT_DOCX_TEXT ? "提取可编辑文字和图片…" : "恢复笔记与原始笔迹…",
              true,
              false);
      final String targetId = imageTargetId, pageId = imagePageId;
      executeContent(
          () -> {
            try {
              NoteDocument imported;
              if (request == IMPORT_PDF || request == IMPORT_PDF_PAGES)
                imported = MediaIo.importPdf(this, uri);
              else if (request == IMPORT_OFFICE) {
                imported = importOtherDocument(uri, displayName(uri));
              } else if (request == IMPORT_DOCX_TEXT) {
                RichTextImport.Result editable =
                    RichTextImport.importWithReport(this, uri, displayName(uri));
                imported = editable.document;
                saveImportReport(imported, editable.description());
              } else imported = MediaIo.importBackup(this, uri);
              if (request != IMPORT_PDF_PAGES) store.save(imported);
              main.post(
                  () -> {
                    progress.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    if (request == IMPORT_PDF_PAGES) {
                      if (document == null || !document.id.equals(targetId)) {
                        toast("笔记已切换，PDF 纸页没有插入");
                        return;
                      }
                      int sourceIndex = document.findPageIndex(pageId);
                      int at = sourceIndex < 0 ? document.pages.size() : sourceIndex + 1;
                      document.pages.addAll(at, imported.pages);
                      document.currentPage = at;
                      ink.setPage(at);
                      markDirty();
                      refreshPages();
                      toast("已插入 " + imported.pages.size() + " 页 PDF 纸页");
                    } else {
                      openDocument(imported);
                      if (!imported.tags.contains("Word 原版")) showSavedImportReport(imported);
                    }
                  });
            } catch (Exception e) {
              main.post(
                  () -> {
                    progress.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    error("导入失败", e);
                  });
            }
          });
    } else if (request == IMPORT_COVER_IMAGE) {
      ProgressDialog progress = ProgressDialog.show(this, "正在导入封面", "优化并复制图片…", true, false);
      executeContent(
          () -> {
            try {
              String path = MediaIo.importImage(this, uri);
              main.post(
                  () -> {
                    progress.dismiss();
                    if (creatingNote != null && creatingNote.id.equals(coverTargetId)) {
                      creatingNote.coverImagePath = path;
                      showNoteComposer();
                      return;
                    }
                    if (document != null && document.id.equals(coverTargetId)) {
                      document.coverImagePath = path;
                      markDirty();
                      toast("封面图片已加入笔记本");
                      return;
                    }
                    for (LibraryEntry note : notebooks) {
                      if (note.id.equals(coverTargetId)) {
                        updateLibraryNote(note, copy -> copy.coverImagePath = path);
                        toast("封面图片已加入笔记本");
                        return;
                      }
                    }
                    toast("笔记本列表已刷新，请重新选择封面");
                  });
            } catch (Exception error) {
              main.post(
                  () -> {
                    progress.dismiss();
                    if (creatingNote != null) showNoteComposer();
                    error("封面导入失败", error);
                  });
            }
          });
    } else if (request == IMPORT_IMAGE || request == IMPORT_PAPER_IMAGE) {
      final String target = imageTargetId, pageId = imagePageId;
      ProgressDialog progress = ProgressDialog.show(this, "正在插入图片", "复制图片到本机…", true, false);
      executeContent(
          () -> {
            try {
              String path = MediaIo.importImage(this, uri);
              main.post(
                  () -> {
                    progress.dismiss();
                    if (document == null || !document.id.equals(target)) {
                      toast("笔记已切换，请重新选择图片");
                      return;
                    }
                    int pageIndex = -1;
                    for (int i = 0; i < document.pages.size(); i++)
                      if (document.pages.get(i).id.equals(pageId)) pageIndex = i;
                    if (pageIndex < 0) {
                      toast("目标页面已不存在");
                      return;
                    }
                    if (document.currentPage != pageIndex) switchPage(pageIndex);
                    NoteDocument.Page page = ink.getPage();
                    if (request == IMPORT_PAPER_IMAGE && !page.infinite) {
                      ink.mutate(
                          () -> {
                            page.backgroundPath = path;
                            page.pdfPage = -1;
                            page.paper = "blank";
                          });
                      ink.refreshPaper();
                      refreshPages();
                      toast("已将图片设为当前纸页背景");
                      return;
                    }
                    NoteDocument.Element e = createImageElement(page, path);
                    ink.mutate(() -> page.elements.add(e));
                    editImage(e);
                    toast("图片已插入当前视口，可直接移动与缩放");
                  });
            } catch (Exception e) {
              main.post(
                  () -> {
                    progress.dismiss();
                    error("图片导入失败", e);
                  });
            }
          });
    } else if (request >= EXPORT_PDF && request <= EXPORT_BACKUP) {
      final NoteDocument snapshot = pendingExport;
      pendingExport = null;
      if (snapshot == null) {
        toast("导出状态已失效，请重新导出");
        return;
      }
      ProgressDialog progress = ProgressDialog.show(this, "正在导出", "正在写入选定位置…", true, false);
      executeContent(
          () -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new IOException("无法打开选定的保存位置");
              if (request == EXPORT_PDF) MediaIo.exportPdf(snapshot, out);
              else if (request == EXPORT_PNG)
                MediaIo.exportPng(snapshot.pages.get(snapshot.currentPage), out);
              else MediaIo.exportBackup(this, snapshot, out);
              out.flush();
              main.post(
                  () -> {
                    progress.dismiss();
                    toast("导出完成");
                  });
            } catch (Exception e) {
              main.post(
                  () -> {
                    progress.dismiss();
                    error("导出失败", e);
                  });
            }
          });
    }
  }

  private void showInputSettings() {
    inputSettingsChild = false;
    ScrollView scroll = new ScrollView(this);
    LinearLayout body = Ui.column(this);
    Ui.pad(body, 24, 8);
    scroll.addView(body);
    TextView intro = Ui.text(this, "优先使用 Android 标准手写笔事件。笔身键、橡皮端与键盘快捷键按设备实际发送的事件工作。", 13, Ui.MUTED);
    intro.setLineSpacing(d(4), 1);
    body.addView(intro, lp(-1, -2));
    String profile = preferences.getString("stylusProfile", "auto");
    body.addView(
        Ui.button(this, "笔配置 · " + StylusProfiles.label(profile), false, this::chooseStylusProfile),
        lp(-1, 52));
    body.addView(
        Ui.text(
            this, "品牌配置覆盖标准笔尖、橡皮端、压力、倾角与侧键。自动识别依据输入设备；平板品牌仅供推荐。专用轻捏、双击需要厂商向应用开放事件。", 12, Ui.MUTED),
        lp(-1, -2));
    Switch finger = new Switch(this);
    finger.setText("手指书写");
    finger.setTextColor(Ui.TEXT);
    finger.setTextSize(14);
    finger.setChecked(fingerDrawing);
    finger.setContentDescription("手指书写开关，关闭后手指只负责缩放和平移");
    finger.setOnCheckedChangeListener(
        (button, checked) -> {
          fingerDrawing = checked;
          preferences.edit().putBoolean("fingerDrawing", checked).apply();
          if (ink != null) ink.setFingerDrawing(checked);
          if (focusWriting != null) focusWriting.setFingerDrawing(checked);
          refreshInputMode();
        });
    body.addView(finger, lp(-1, 60));
    TextView gesture = Ui.text(this, "双指缩放与平移始终可用。关闭手指书写后，单指用于平移。检测不到笔时可打开手指书写。", 12, Ui.MUTED);
    body.addView(gesture, lp(-1, -2));
    body.addView(Ui.label(this, "笔侧键动作", 15), lp(-1, 50));
    TextView primary = Ui.button(this, "", false, () -> {});
    TextView secondary = Ui.button(this, "", false, () -> {});
    refreshSideButtonLabel(primary, true);
    refreshSideButtonLabel(secondary, false);
    primary.setOnClickListener(v -> chooseSideButton(true, primary));
    secondary.setOnClickListener(v -> chooseSideButton(false, secondary));
    body.addView(primary, lp(-1, 48));
    LinearLayout.LayoutParams sideSpacing = lp(-1, 48);
    sideSpacing.topMargin = d(8);
    body.addView(secondary, sideSpacing);
    TextView sideHint =
        Ui.text(this, "可选按住临时擦除、点按切换笔/橡皮、应用内截图等。截图只加入笔记，不写入相册。已学习的按键映射优先，可在下方修改或删除。", 12, Ui.MUTED);
    Ui.pad(sideHint, 0, 12);
    body.addView(sideHint, lp(-1, -2));
    body.addView(Ui.label(this, "当前输入设备", 15), lp(-1, 54));
    int count = 0;
    for (int id : InputDevice.getDeviceIds()) {
      InputDevice device = InputDevice.getDevice(id);
      if (device == null) continue;
      boolean penKeyboard =
          XiaomiPenKeys.isPenKeyboard(
              device.getName(), device.getVendorId(), device.getProductId());
      if (!penKeyboard
          && (device.getSources() & InputDevice.SOURCE_STYLUS) != InputDevice.SOURCE_STYLUS)
        continue;
      count++;
      StringBuilder details = new StringBuilder(device.getName()).append("\n");
      String detected =
          StylusProfiles.identify(
              device.getName(),
              (device.getSources() & InputDevice.SOURCE_STYLUS) != 0,
              device.getVendorId(),
              device.getProductId());
      details
          .append("识别：")
          .append(StylusProfiles.label(detected))
          .append(" · 当前配置：")
          .append(StylusProfiles.label("auto".equals(profile) ? detected : profile))
          .append("\n");
      if (penKeyboard) details.append("小米笔侧键已识别 · 侧键一 / 侧键二\n");
      appendAxis(details, device, "压力", android.view.MotionEvent.AXIS_PRESSURE);
      appendAxis(details, device, "倾斜", android.view.MotionEvent.AXIS_TILT);
      appendAxis(details, device, "笔尖距离", android.view.MotionEvent.AXIS_DISTANCE);
      details.append("设备标识：").append(device.getDescriptor());
      TextView text = Ui.text(this, details.toString(), 12, Ui.MUTED);
      text.setTextIsSelectable(true);
      Ui.pad(text, 12, 12);
      text.setBackground(Ui.bg(this, Ui.SURFACE, 12));
      LinearLayout.LayoutParams p = lp(-1, -2);
      p.bottomMargin = d(10);
      body.addView(text, p);
      if (penKeyboard) {
        penKeyReadout = Ui.text(this, lastPenKey, 12, Ui.ACCENT);
        penKeyReadout.setContentDescription("小米笔侧键诊断");
        body.addView(penKeyReadout, lp(-1, 44));
      }
    }
    if (count == 0)
      body.addView(
          Ui.text(this, "系统当前未报告 SOURCE_STYLUS 设备。部分笔只在连接或落笔后出现；这不代表已验证厂商专用快捷键。", 12, Ui.MUTED),
          lp(-1, -2));
    TextView learning = Ui.button(this, "学习笔身 / 键盘按键", true, this::learnKey);
    LinearLayout.LayoutParams bl = lp(-1, 48);
    bl.topMargin = d(20);
    body.addView(learning, bl);
    TextView mapping = Ui.button(this, "查看已学习的按键", false, this::showMappings);
    LinearLayout.LayoutParams ml = lp(-1, 48);
    ml.topMargin = d(8);
    body.addView(mapping, ml);
    body.addView(Ui.label(this, "键盘快捷键", 15), lp(-1, 50));
    TextView keys =
        Ui.text(
            this,
            "Ctrl+Z  撤销\n"
                + "Ctrl+Shift+Z / Ctrl+Y  重做\n"
                + "Ctrl+S  立即保存\n"
                + "Ctrl+E  导出 · Ctrl+Shift+C  应用内截图\n"
                + "P 钢笔 · B 圆珠笔 · H 荧光笔 · E 橡皮\n"
                + "L 套索 · V 平移 · [ / ] 切换页面",
            13,
            Ui.MUTED);
    keys.setLineSpacing(d(5), 1);
    body.addView(keys, lp(-1, -2));
    TextView disclaimer =
        Ui.text(this, "系统未传入的厂商双击、隔空手势或截图键无法通过按键学习补出。品牌支持需要对应真机验证；本页只显示系统报告的实际能力。", 12, Ui.MUTED);
    Ui.pad(disclaimer, 0, 18);
    body.addView(disclaimer, lp(-1, -2));
    showToolPanel("手写笔与快捷键", scroll, () -> {});
  }

  private void refreshSideButtonLabel(TextView label, boolean primary) {
    String action = sideAction(primary ? "sidePrimary" : "sideSecondary");
    String name =
        "eraser".equals(action)
            ? "按住临时擦除"
            : "lasso".equals(action)
                ? "按住临时套索"
                : "toggle_eraser".equals(action)
                    ? "切换笔 / 橡皮"
                    : "capture_note".equals(action) ? "应用内截图到笔记" : "忽略";
    label.setText((primary ? "侧键一" : "侧键二") + " · " + name);
  }

  private void chooseStylusProfile() {
    LinearLayout choices = Ui.column(this);
    String selected = preferences.getString("stylusProfile", "auto");
    for (int i = 0; i < StylusProfiles.IDS.length; i++) {
      String id = StylusProfiles.IDS[i];
      choices.addView(
          Ui.button(
              this,
              StylusProfiles.LABELS[i],
              id.equals(selected),
              () -> {
                preferences.edit().putString("stylusProfile", id).apply();
                updateSideButtonActions();
                closeToolPanel();
              }),
          lp(-1, 44));
    }
    choices.addView(
        Ui.text(
            this, "各品牌分别记忆侧键动作。选择品牌不会改变硬件配对；未知型号使用标准 Android 协议。系统未传入的专有手势不会被标为可用。", 12, Ui.MUTED),
        lp(-1, -2));
    showToolPanel("选择手写笔配置", choices, () -> {});
    inputSettingsChild = true;
  }

  private void chooseSideButton(boolean primary, TextView label) {
    String side = primary ? "sidePrimary" : "sideSecondary";
    String key = sidePreferenceKey(side);
    String[] actions = {"eraser", "toggle_eraser", "capture_note", "lasso", "none"};
    String[] labels = {"按住临时擦除", "点按切换笔 / 橡皮", "应用内截图到当前页", "按住临时套索", "忽略此侧键"};
    LinearLayout options = Ui.column(this);
    String selected = sideAction(side);
    for (int i = 0; i < actions.length; i++) {
      final String action = actions[i];
      TextView choice =
          Ui.button(
              this,
              labels[i],
              action.equals(selected),
              () -> {
                preferences.edit().putString(key, action).apply();
                updateSideButtonActions();
                refreshSideButtonLabel(label, primary);
                closeToolPanel();
              });
      LinearLayout.LayoutParams params = lp(-1, 44);
      params.bottomMargin = d(4);
      options.addView(choice, params);
    }
    showToolPanel(primary ? "标准侧键一" : "标准侧键二", options, () -> {});
    inputSettingsChild = true;
  }

  private void appendAxis(StringBuilder out, InputDevice device, String name, int axis) {
    InputDevice.MotionRange range = device.getMotionRange(axis, InputDevice.SOURCE_STYLUS);
    if (range == null) range = device.getMotionRange(axis);
    if (range == null) out.append(name).append("：未报告\n");
    else
      out.append(name)
          .append("：")
          .append(String.format(Locale.ROOT, "%.3f – %.3f", range.getMin(), range.getMax()))
          .append("\n");
  }

  private static final String[] ACTION_IDS = {
    "hold_eraser",
    "toggle_eraser",
    "capture_note",
    "undo",
    "redo",
    "pen",
    "lasso",
    "next_page",
    "previous_page"
  };
  private static final String[] ACTION_NAMES = {
    "按住时使用橡皮，松开恢复", "切换笔 / 橡皮", "应用内截图到当前页", "撤销", "重做", "切换钢笔", "切换套索", "下一页", "上一页"
  };

  private void learnKey() {
    learnedCode = -1;
    learnedDescriptor = null;
    LinearLayout body = Ui.column(this);
    Ui.pad(body, 24, 8);
    learningReadout =
        Ui.text(
            this,
            "现在按一下需要映射的笔身键或键盘键。\n\n"
                + "只有 Android 实际传入 KeyEvent 的按键才能学习。标准笔侧键若以 MotionEvent 发送，由书写引擎自动临时切换橡皮。",
            13,
            Ui.MUTED);
    learningReadout.setMinHeight(d(132));
    learningReadout.setTextIsSelectable(true);
    TextView readout = learningReadout;
    body.addView(learningReadout, lp(-1, -2));
    Spinner actions = new Spinner(this);
    actions.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ACTION_NAMES));
    body.addView(actions, lp(-1, 54));
    body.addView(
        Ui.button(
            this,
            "保存映射",
            true,
            () -> {
              if (learnedCode < 0 || learnedDescriptor == null) {
                toast("尚未接收到实际按键，请先按键");
                return;
              }
              preferences
                  .edit()
                  .putString(
                      mappingKey(learnedDescriptor, learnedCode),
                      ACTION_IDS[actions.getSelectedItemPosition()])
                  .apply();
              learningKeyActive = false;
              closeToolPanel();
              toast("按键映射已保存在此设备");
            }),
        lp(-1, 48));
    body.addView(
        Ui.button(
            this,
            "取消学习",
            false,
            () -> {
              learningKeyActive = false;
              learningReadout = null;
              closeToolPanel();
            }),
        lp(-1, 44));
    showToolPanel(
        "按键学习",
        body,
        () -> {
          learningKeyActive = false;
          learningReadout = null;
        });
    learningReadout = readout;
    learningKeyActive = true;
    inputSettingsChild = true;
    root.setFocusableInTouchMode(true);
    root.requestFocus();
  }

  private boolean recordLearning(KeyEvent event) {
    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
      learnedCode = event.getKeyCode();
      learnedDescriptor = descriptor(event);
      if (learningReadout != null)
        learningReadout.setText(
            "已收到："
                + KeyEvent.keyCodeToString(learnedCode)
                + " ("
                + learnedCode
                + ")\n设备："
                + (event.getDevice() == null ? "虚拟输入" : event.getDevice().getName())
                + "\n标识："
                + learnedDescriptor
                + "\n\n选择这个按键执行的动作，然后保存。");
    }
    return true;
  }

  private String descriptor(KeyEvent event) {
    return event.getDevice() == null
        ? "virtual:" + event.getDeviceId()
        : event.getDevice().getDescriptor();
  }

  private String mappingKey(String descriptor, int code) {
    return "mapping:" + descriptor + ":" + code;
  }

  private void showMappings() {
    List<String> prefKeys = new ArrayList<>(), labels = new ArrayList<>();
    for (String key : preferences.getAll().keySet())
      if (key.startsWith("mapping:")) {
        prefKeys.add(key);
        String action = preferences.getString(key, "");
        int index = 0;
        for (int i = 0; i < ACTION_IDS.length; i++) if (ACTION_IDS[i].equals(action)) index = i;
        int end = key.lastIndexOf(':');
        String code = key.substring(end + 1);
        labels.add("按键 " + code + " → " + ACTION_NAMES[index] + "\n" + key.substring(8, end));
      }
    if (labels.isEmpty()) {
      TextView empty = Ui.text(this, "尚无自定义映射。标准笔侧键和橡皮端由书写引擎识别；厂商专用按键可使用按键学习。", 13, Ui.MUTED);
      Ui.pad(empty, 14, 14);
      showToolPanel("已学习的按键", empty, () -> {});
      return;
    }
    LinearLayout mappings = Ui.column(this);
    for (int i = 0; i < labels.size(); i++) {
      final int index = i;
      TextView mapping =
          Ui.button(
              this,
              labels.get(i) + "\n轻点删除",
              false,
              () -> confirmDeleteMapping(prefKeys.get(index), labels.get(index)));
      LinearLayout.LayoutParams params = lp(-1, 62);
      params.bottomMargin = d(6);
      mappings.addView(mapping, params);
    }
    showToolPanel("已学习的按键", mappings, () -> {});
  }

  private void confirmDeleteMapping(String key, String description) {
    LinearLayout actions = Ui.row(this);
    actions.addView(
        Ui.button(this, "保留", false, this::showMappings),
        new LinearLayout.LayoutParams(0, d(48), 1));
    actions.addView(
        Ui.button(
            this,
            "删除映射",
            false,
            () -> {
              preferences.edit().remove(key).apply();
              showMappings();
              toast("按键映射已删除");
            }),
        new LinearLayout.LayoutParams(0, d(48), 1));
    TextView details = Ui.text(this, description, 12, Ui.MUTED);
    Ui.pad(details, 10, 8);
    LinearLayout body = Ui.column(this);
    body.addView(details, lp(-1, -2));
    body.addView(actions, lp(-1, 50));
    showToolPanel("删除这个按键映射？", body, () -> {});
  }

  public boolean preDispatchKeyEvent(KeyEvent event) {
    if (!visitVisible) return false;
    View focused = activity.getCurrentFocus();
    InputDevice input = event.getDevice();
    String side =
        input == null
            ? null
            : XiaomiPenKeys.preference(
                input.getName(), input.getVendorId(), input.getProductId(), event.getKeyCode());
    if (side == null) side = StylusProfiles.standardButton(event.getKeyCode());
    if (embedded && side == null && (focused == null || !isWorkspaceChild(focused))) return false;
    boolean sideEdge =
        side == null
            || stylusButtons.key(
                event.getDeviceId(),
                StylusProfiles.buttonBit(side),
                event.getAction() == KeyEvent.ACTION_DOWN);
    if (side != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
      lastPenKey =
          "已收到："
              + (side.equals("sidePrimary") ? "侧键一" : "侧键二")
              + " · "
              + KeyEvent.keyCodeToString(event.getKeyCode());
      if (penKeyReadout != null) penKeyReadout.setText(lastPenKey);
      android.util.Log.i("IkoPen", lastPenKey + " · " + sideAction(side));
    }
    if (learningKeyActive
        && (!event.isSystem() || event.getKeyCode() >= 308 && event.getKeyCode() <= 311))
      return recordLearning(event);
    String device = descriptor(event);
    int code = event.getKeyCode();
    if (event.getAction() == KeyEvent.ACTION_UP
        && heldTool != null
        && heldCode == code
        && device.equals(heldDescriptor)) {
      restoreHeldTool();
      return true;
    }
    if (document == null || ink == null || getCurrentFocus() instanceof EditText)
      return false;
    String mapped = preferences.getString(mappingKey(device, code), null);
    boolean sideDefault = mapped == null && side != null;
    if (mapped == null && side != null) mapped = sideAction(side);
    if (mapped != null) {
      if (event.getAction() == KeyEvent.ACTION_DOWN
          && event.getRepeatCount() == 0
          && (side == null || sideEdge)) {
        if (mapped.equals("hold_eraser")
            || (sideDefault && (mapped.equals("eraser") || mapped.equals("lasso")))) {
          if (heldTool == null) {
            heldTool = tool;
            heldCode = code;
            heldDescriptor = device;
            selectTool(mapped.equals("lasso") ? "lasso" : "eraser", true);
          }
        } else if (!mapped.equals("none")) performAction(mapped);
      }
      return true;
    }
    boolean ctrl = event.isCtrlPressed();
    boolean shift = event.isShiftPressed();
    String action = null;
    if (ctrl) {
      if (code == KeyEvent.KEYCODE_Z) action = shift ? "redo" : "undo";
      else if (code == KeyEvent.KEYCODE_Y) action = "redo";
      else if (code == KeyEvent.KEYCODE_S) action = "save";
      else if (code == KeyEvent.KEYCODE_E) action = "export";
      else if (code == KeyEvent.KEYCODE_C && shift) action = "capture_note";
      else if (code == KeyEvent.KEYCODE_C) action = "copy_objects";
      else if (code == KeyEvent.KEYCODE_X) action = "cut_objects";
      else if (code == KeyEvent.KEYCODE_V) action = "paste_objects";
    } else if (!event.isAltPressed() && !event.isMetaPressed()) {
      switch (code) {
        case KeyEvent.KEYCODE_P:
          action = "pen";
          break;
        case KeyEvent.KEYCODE_B:
          action = "ballpoint";
          break;
        case KeyEvent.KEYCODE_H:
          action = "highlighter";
          break;
        case KeyEvent.KEYCODE_E:
          action = "eraser";
          break;
        case KeyEvent.KEYCODE_L:
          action = "lasso";
          break;
        case KeyEvent.KEYCODE_V:
          action = "hand";
          break;
        case KeyEvent.KEYCODE_LEFT_BRACKET:
          action = "previous_page";
          break;
        case KeyEvent.KEYCODE_RIGHT_BRACKET:
          action = "next_page";
          break;
      }
    }
    if (action != null) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
        performAction(action);
      return true;
    }
    return false;
  }

  private boolean isWorkspaceChild(View view) {
    for (View current = view; current != null;) {
      if (current == workspaceView) return true;
      android.view.ViewParent parent = current.getParent();
      current = parent instanceof View ? (View) parent : null;
    }
    return false;
  }

  private void restoreHeldTool() {
    if (heldTool == null) return;
    String restore = heldTool;
    heldTool = heldDescriptor = null;
    heldCode = -1;
    heldMotionButton = false;
    selectTool(restore, true);
  }

  private void performAction(String action) {
    if (document == null || ink == null) return;
    if ("copy_objects".equals(action) || "cut_objects".equals(action)) {
      copyObjects("cut_objects".equals(action));
      return;
    }
    if ("paste_objects".equals(action)) {
      pasteObjects();
      return;
    }
    closeImageTransform();
    switch (action) {
      case "undo":
        ink.undo();
        updateUndo();
        break;
      case "redo":
        ink.redo();
        updateUndo();
        break;
      case "save":
        saveCurrent(true);
        break;
      case "export":
        chooseExport();
        break;
      case "toggle_eraser":
        if (tool.equals("eraser")) selectTool(eraserReturnTool, true);
        else {
          eraserReturnTool =
              PenPreset.isPen(tool) ? tool : preferences.getString("lastWritingPen", "pen");
          selectTool("eraser", true);
        }
        break;
      case "capture_note":
        captureNoteViewport();
        break;
      case "next_page":
        if (document.currentPage + 1 < document.pages.size()) switchPage(document.currentPage + 1);
        else toast("已经是最后一页");
        break;
      case "previous_page":
        if (document.currentPage > 0) switchPage(document.currentPage - 1);
        else toast("已经是第一页");
        break;
      default:
        selectTool(action);
    }
  }
}
