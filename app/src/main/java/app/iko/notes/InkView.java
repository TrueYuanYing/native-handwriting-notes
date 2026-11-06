package app.iko.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Native tablet ink surface. Motion input is sampled in paper coordinates, without file I/O. */
public class InkView extends View {
  public interface Listener {
    void onChanged();
  }

  private NoteDocument document;
  private NoteDocument.Page page;
  private Listener listener;
  private Runnable selectionListener;
  private Runnable frameListener;
  private Consumer<String> referenceListener;
  private Consumer<NoteDocument.Element> imageEditListener;
  private NoteDocument.Element editingImage;
  private java.util.function.BiConsumer<NoteDocument.Element, android.graphics.PointF>
      textToolListener;
  private final LaserPointer laser = new LaserPointer();
  private NoteDocument.Element tapeDraft, tapeCandidate;
  private android.graphics.PointF studyDown;
  private float studyRawX, studyRawY, studyMotion;
  private int tapeColor = 0xff9ebdc0;
  private String tapePattern = "stripes";
  private android.graphics.PointF referencePoint;
  private float referenceDownX, referenceDownY;
  private long referenceDownTime;
  private RectF focusRegion;
  private Consumer<android.graphics.PointF> focusLocator;
  private boolean dispatchingViewport, crispBitmap;
  private String eraserMode = "stroke";
  private final InkHistory history = new InkHistory();
  private StrokeSpatialIndex eraserIndex;
  private final LinkedHashMap<NoteDocument.Stroke, List<NoteDocument.Point>> eraserPointCache =
      new LinkedHashMap<>(16, .75f, true);
  private int cachedEraserPoints;
  private RectF eraserBitmapDirty;
  private StrokeSpatialIndex eraserBitmapIndex;
  private String tool = "pen", gestureTool = "pen";
  private int inkColor = 0xff202837;
  private float inkWidth = 4;
  private float eraserWidth = 16;
  private String primaryButtonAction = "eraser", secondaryButtonAction = "eraser";
  private String pressureMode = "standard";
  private boolean fingerDrawing;
  private float scale = 1, offsetX, offsetY, fitScale = 1;
  private boolean needsFit = true;
  private boolean widthFitPreferred;
  private boolean widthFitViewport;
  private boolean continuousScrolling;
  private final ContinuousPages strip = new ContinuousPages();
  private final java.util.IdentityHashMap<NoteDocument.Page, InkViewportCache> stripCaches =
      new java.util.IdentityHashMap<>();
  private final java.util.IdentityHashMap<NoteDocument.Page, PageRenderer.MediaRequest>
      mediaRequests = new java.util.IdentityHashMap<>();
  private final java.util.IdentityHashMap<NoteDocument.Page, Long> stripRevisions =
      new java.util.IdentityHashMap<>();
  private final Set<NoteDocument.Page> queuedMediaChanges =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private Runnable pageChangeListener;
  private final android.widget.OverScroller scroller;
  private android.view.VelocityTracker velocity;
  private int flingX, flingY;
  private boolean navigationHadPinch;
  private float pagePull;
  private final PagePullIndicator pagePullIndicator = new PagePullIndicator();
  private boolean pagePullEligible;
  private Runnable pageAppendListener;
  private Bitmap pageBitmap, pageStaticBase;
  private Bitmap activeBitmap;
  private Canvas activeCanvas;
  private int nextActiveSegment = 1;
  private boolean renderDirty = true;
  private long paperGeneration;
  private final InkViewportCache mainViewportCache = new InkViewportCache();
  private final InkViewportCache focusViewportCache = new InkViewportCache();
  private int activePointer = -1, activeToolType;
  private NoteDocument.Stroke activeStroke;
  private NoteDocument.Page beforeGesture;
  private boolean gestureChanged, selectionMoving, navigating, multiNavigation;
  private float lastX, lastY, lastNavX, lastNavY, lastNavDistance;
  private final ArrayList<NoteDocument.Point> lasso = new ArrayList<>();
  private final Set<String> selectedStrokes = new HashSet<>();
  private final Set<NoteDocument.Element> selectedElements = new HashSet<>();
  private RectF selectionBounds;
  private NoteDocument.Page objectEditBefore;
  private InkHistory.Checkpoint objectEditHistory;
  private NoteDocument.Page selectionPaintPage;
  private Bitmap selectionPreview;
  private RectF selectionStart;
  private float selectionDx,
      selectionDy,
      selectionScale = 1,
      selectionDegrees,
      selectionDownX,
      selectionDownY;
  private int selectionHandle;

  public static final class SelectionData {
    final ArrayList<NoteDocument.Stroke> strokes = new ArrayList<>();
    final ArrayList<NoteDocument.Element> elements = new ArrayList<>();
    RectF bounds;

    public static SelectionData image(NoteDocument.Element image) {
      SelectionData data = new SelectionData();
      data.elements.add(image.copy());
      ObjectTransform.Bounds box = ObjectTransform.bounds(image);
      data.bounds = new RectF(box.left, box.top, box.right, box.bottom);
      return data;
    }
  }

  private float hoverX, hoverY;
  private boolean hovering;
  private String hoverTool = "pen";
  private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final RectF pageBounds = new RectF(),
      shadowBounds = new RectF(),
      rasterBounds = new RectF(),
      activeInkBounds = new RectF();
  private final Path lassoPath = new Path();
  private float dashScale = -1;
  private DashPathEffect lassoDash, selectionDash;

  public InkView(Context context) {
    this(context, null);
  }

  public InkView(Context context, AttributeSet attributes) {
    super(context, attributes);
    laser.density = getResources().getDisplayMetrics().density;
    scroller = new android.widget.OverScroller(context);
    setFocusable(true);
    setFocusableInTouchMode(true);
    setContentDescription("笔记纸面；手写笔书写，双指缩放和平移");
  }

  public void setDocument(NoteDocument value) {
    cancelGesture();
    releaseStrip();
    document = value;
    focusRegion = null;
    history.clear();
    clearSelection();
    if (value == null || value.pages.isEmpty()) {
      page = null;
      releaseBitmap();
      invalidate();
      return;
    }
    setPage(Math.max(0, Math.min(value.pages.size() - 1, value.currentPage)));
  }

  public NoteDocument getDocument() {
    return document;
  }

  /** Autosave and export must never capture a reversible eraser/lasso preview. */
  public NoteDocument snapshotDocument() {
    if (document == null) return null;
    NoteDocument snapshot = document.copy();
    if (beforeGesture != null && page != null) {
      for (int i = 0; i < snapshot.pages.size(); i++) {
        if (snapshot.pages.get(i).id.equals(page.id)) {
          snapshot.pages.set(i, beforeGesture.copy());
          break;
        }
      }
    }
    return snapshot;
  }

  public NoteDocument.Page getPage() {
    return page;
  }

  public float viewportScale() {
    return scale;
  }

  public void setPage(int index) {
    scroller.forceFinished(true);
    cancelGesture();
    laser.clear();
    eraserIndex = null;
    clearEraserPointCache();
    clearSelection();
    if (document == null || index < 0 || index >= document.pages.size()) return;
    strip.update(document);
    page = document.pages.get(index);
    focusRegion = null;
    mainViewportCache.release();
    focusViewportCache.release();
    document.currentPage = index;
    releaseBitmap();
    needsFit = true;
    refreshPaper();
  }

  public void setContinuousScrolling(boolean enabled) {
    continuousScrolling = enabled;
    if (document != null) strip.update(document);
    releaseStrip();
    invalidate();
  }

  public boolean isContinuousScrolling() {
    return continuousScrolling && page != null && !page.infinite;
  }

  public void setPageChangeListener(Runnable listener) {
    pageChangeListener = listener;
  }

  public void setPageAppendListener(Runnable listener) {
    pageAppendListener = listener;
  }

  public void stopNavigation() {
    scroller.forceFinished(true);
    pagePull = 0;
    pagePullEligible = false;
  }

  private void releaseStrip() {
    for (InkViewportCache cache : stripCaches.values()) cache.release();
    stripCaches.clear();
    for (PageRenderer.MediaRequest request : mediaRequests.values()) request.cancel();
    mediaRequests.clear();
    stripRevisions.clear();
    queuedMediaChanges.clear();
  }

  private void activatePageAt(float screenX, float screenY, boolean nearest) {
    if (!isContinuousScrolling() || activePointer >= 0) return;
    android.graphics.RectF current = strip.page(document.currentPage);
    int index =
        strip.at(
            current.left + (screenX - offsetX) / scale,
            current.top + (screenY - offsetY) / scale,
            nearest);
    if (index < 0 || index == document.currentPage) return;
    android.graphics.RectF next = strip.page(index);
    offsetX += (next.left - current.left) * scale;
    offsetY += (next.top - current.top) * scale;
    cancelGesture();
    clearSelection();
    page = document.pages.get(index);
    document.currentPage = index;
    editingImage = null;
    releaseBitmap();
    mainViewportCache.release();
    focusViewportCache.release();
    if (pageChangeListener != null) pageChangeListener.run();
  }

  public void setTool(String value) {
    cancelGesture();
    setToolAtButtonBoundary(value);
  }

  /** Keep the current segment until the next sample completes the tool boundary. */
  public void setToolAtButtonBoundary(String value) {
    tool = value == null ? "pen" : value;
    if (!"laser".equals(tool)) laser.clear();
    if (!"lasso".equals(tool)) clearSelection();
    invalidate();
  }

  public void setInkColor(int value) {
    inkColor = value;
  }

  public void setInkWidth(float value) {
    inkWidth = Math.max(.5f, Math.min(100, value));
  }

  public void setEraserWidth(float value) {
    if (Float.isNaN(value) || Float.isInfinite(value) || value <= 0)
      throw new IllegalArgumentException("橡皮宽度必须大于零");
    eraserWidth = Math.max(2, Math.min(200, value));
    invalidate();
  }

  /** This preference is captured on pen-down; existing samples and strokes are never rewritten. */
  public void setPressureMode(String value) {
    if (!BrushStyle.validPressureMode(value)) throw new IllegalArgumentException("不支持的压感档位");
    pressureMode = value;
  }

  public void setSideButtonActions(String primary, String secondary) {
    if (!validSideButtonAction(primary) || !validSideButtonAction(secondary))
      throw new IllegalArgumentException("不支持的笔侧键动作");
    if (primary.equals(primaryButtonAction) && secondary.equals(secondaryButtonAction)) return;
    cancelGesture();
    primaryButtonAction = primary;
    secondaryButtonAction = secondary;
    invalidate();
  }

  private static boolean validSideButtonAction(String value) {
    return "eraser".equals(value) || "lasso".equals(value) || "none".equals(value);
  }

  public void setFingerDrawing(boolean value) {
    cancelGesture();
    fingerDrawing = value;
  }

  public void setListener(Listener value) {
    listener = value;
  }

  public String getTool() {
    return tool;
  }

  public boolean isWritingGesture() {
    return activeStroke != null && PenPreset.isPen(activeStroke.tool);
  }

  public void setEraserMode(String mode) {
    if (!"stroke".equals(mode) && !"segment".equals(mode))
      throw new IllegalArgumentException("不支持的擦除方式");
    cancelInput();
    eraserMode = mode;
  }

  public void cancelInput() {
    cancelGesture();
    navigating = false;
    referencePoint = null;
    pagePull = 0;
    pagePullEligible = false;
    invalidate();
  }

  /** Complete the current operation before a persistent tool switch or in-app capture. */
  public void finishInput() {
    if (activePointer >= 0) finishGesture();
    navigating = false;
    multiNavigation = false;
    referencePoint = null;
    pagePull = 0;
    pagePullEligible = false;
    invalidate();
  }

  public void setFrameListener(Runnable listener) {
    frameListener = listener;
  }

  @Override
  public void invalidate() {
    super.invalidate();
    if (frameListener != null) frameListener.run();
  }

  public void setReferenceListener(Consumer<String> listener) {
    referenceListener = listener;
  }

  public void setImageEditListener(Consumer<NoteDocument.Element> listener) {
    imageEditListener = listener;
  }

  public void setTextToolListener(
      java.util.function.BiConsumer<NoteDocument.Element, android.graphics.PointF> listener) {
    textToolListener = listener;
  }

  public void setLaserStyle(int color, boolean trail, int durationMs) {
    laser.color = color;
    laser.trail = trail;
    laser.duration = Math.max(400, Math.min(4000, durationMs));
    invalidate();
  }

  public void setTapeStyle(int color, String pattern) {
    tapeColor = color;
    tapePattern = pattern;
  }

  public void revealAllTape(boolean reveal) {
    if (page == null) return;
    boolean changed = false;
    for (NoteDocument.Element e : page.elements)
      if ("tape".equals(e.kind) && e.revealed != reveal) changed = true;
    if (changed)
      mutateOverlay(
          () -> {
            for (NoteDocument.Element e : page.elements)
              if ("tape".equals(e.kind)) e.revealed = reveal;
          });
  }

  private NoteDocument.Element tapeAt(float x, float y) {
    for (int n = page.elements.size() - 1; n >= 0; n--) {
      NoteDocument.Element e = page.elements.get(n);
      if ("tape".equals(e.kind) && ObjectTransform.contains(e, x, y)) return e;
    }
    return null;
  }

  public void beginObjectEditSession() {
    finishObjectEditSession();
    cancelGesture();
    if (page == null) return;
    objectEditBefore = page.copy();
    objectEditHistory = history.checkpoint(page);
  }

  public void finishObjectEditSession() {
    objectEditBefore = null;
    objectEditHistory = null;
  }

  public void cancelObjectEditSession() {
    cancelGesture();
    if (objectEditBefore == null || page == null || !page.id.equals(objectEditBefore.id)) return;
    InkHistory.restore(page, objectEditBefore);
    history.restoreCheckpoint(page, objectEditHistory);
    finishObjectEditSession();
    clearSelection();
    refreshPaper();
    changed();
  }

  public RectF imageScreenBounds(NoteDocument.Element image) {
    if (needsFit) fitPreferred();
    return new RectF(
        offsetX + image.x * scale,
        offsetY + image.y * scale,
        offsetX + (image.x + image.width) * scale,
        offsetY + (image.y + image.height) * scale);
  }

  public RectF screenToPaper(RectF screen) {
    return new RectF(
        (screen.left - offsetX) / scale,
        (screen.top - offsetY) / scale,
        (screen.right - offsetX) / scale,
        (screen.bottom - offsetY) / scale);
  }

  public RectF paperScreenBounds() {
    if (page.infinite) return new RectF(0, 0, getWidth(), getHeight());
    return new RectF(offsetX, offsetY, offsetX + page.width * scale, offsetY + page.height * scale);
  }

  public void setEditingImage(NoteDocument.Element image) {
    cancelInput();
    clearSelection();
    editingImage = image;
    dirtyPaper();
    invalidate();
  }

  public void setFocusLocator(Consumer<android.graphics.PointF> locator) {
    focusLocator = locator;
  }

  public void setFocusRegion(RectF region) {
    focusRegion = region == null ? null : new RectF(region);
    invalidate();
  }

  public PageNavigation.Location captureViewport() {
    if (page == null) return null;
    if (needsFit) fitPreferred();
    return new PageNavigation.Location(
        page.id,
        (getWidth() / 2f - offsetX) / scale,
        (getHeight() / 2f - offsetY) / scale,
        scale / (widthFitViewport ? widthFitScale(getWidth()) : fitScale),
        widthFitViewport);
  }

  public void restoreViewport(PageNavigation.Location location) {
    if (page == null || location == null || !page.id.equals(location.pageId)) return;
    cancelInput();
    calculateFit(location.widthFit);
    scale =
        (widthFitViewport ? widthFitScale(getWidth()) : fitScale)
            * Math.max(
                page.infinite ? .0001f : .5f, Math.min(page.infinite ? 64 : 8, location.zoom));
    float cx =
        page.infinite ? location.centerX : Math.max(0, Math.min(page.width, location.centerX));
    float cy =
        page.infinite ? location.centerY : Math.max(0, Math.min(page.height, location.centerY));
    offsetX = getWidth() / 2f - cx * scale;
    offsetY = getHeight() / 2f - cy * scale;
    constrainScroll();
    invalidate();
  }

  public void zoomToRegion(RectF region) {
    if (page == null || region == null || region.width() <= 0 || region.height() <= 0) return;
    fitPage();
    restoreViewport(
        new PageNavigation.Location(
            page.id,
            region.centerX(),
            region.centerY(),
            Math.min(getWidth() / region.width(), getHeight() / region.height()) / fitScale));
  }

  public void fitContent() {
    if (page == null) return;
    if (!page.infinite) {
      fitOverviewPreservingPreference();
      return;
    }
    RectF bounds = InfiniteCanvas.contentBounds(page);
    scale =
        Math.max(
            .0001f,
            Math.min(
                8,
                Math.min(
                    (getWidth() - 48f) / bounds.width(), (getHeight() - 48f) / bounds.height())));
    offsetX = getWidth() / 2f - bounds.centerX() * scale;
    offsetY = getHeight() / 2f - bounds.centerY() * scale;
    needsFit = false;
    invalidate();
  }

  public void centerOrigin() {
    if (page == null) return;
    calculateFit(true);
    if (page.infinite && page.backgroundPath.isEmpty()) {
      offsetX = getWidth() / 2f;
      offsetY = getHeight() / 2f;
    }
    invalidate();
  }

  /** A second viewport shares this controller and never creates its own document/history. */
  public void drawViewport(Canvas canvas, RectF region, int width, int height) {
    if (page == null || region == null || width <= 0 || height <= 0) return;
    canvas.drawColor(Ui.WORKSPACE);
    float factor = Math.min(width / region.width(), height / region.height());
    int saved = canvas.save();
    canvas.clipRect(0, 0, width, height);
    paintViewport(
        focusViewportCache,
        canvas,
        factor,
        (width - region.width() * factor) / 2 - region.left * factor,
        (height - region.height() * factor) / 2 - region.top * factor,
        width,
        height);
    canvas.translate(
        (width - region.width() * factor) / 2 - region.left * factor,
        (height - region.height() * factor) / 2 - region.top * factor);
    canvas.scale(factor, factor);
    Paint p = overlayPaint;
    p.reset();
    p.setAntiAlias(true);
    p.setColor(Ui.ACCENT);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(1.5f / factor);
    if (!lasso.isEmpty()) {
      lassoPath.reset();
      lassoPath.moveTo(lasso.get(0).x, lasso.get(0).y);
      for (int i = 1; i < lasso.size(); i++) lassoPath.lineTo(lasso.get(i).x, lasso.get(i).y);
      canvas.drawPath(lassoPath, p);
    }
    if (selectionBounds != null) canvas.drawRect(selectionBounds, p);
    drawInputCursor(canvas, factor);
    canvas.restoreToCount(saved);
  }

  public boolean dispatchViewportTouch(MotionEvent event, RectF region, int width, int height) {
    if (page == null || region == null || width <= 0 || height <= 0) return false;
    if (needsFit) fitPreferred();
    float factor = Math.min(width / region.width(), height / region.height());
    float left = (width - region.width() * factor) / 2 - region.left * factor;
    float top = (height - region.height() * factor) / 2 - region.top * factor;
    MotionEvent mapped = MotionEvent.obtain(event);
    Matrix matrix = new Matrix();
    matrix.setValues(
        new float[] {
          scale / factor,
          0,
          offsetX - left * scale / factor,
          0,
          scale / factor,
          offsetY - top * scale / factor,
          0,
          0,
          1
        });
    mapped.transform(matrix);
    dispatchingViewport = true;
    try {
      return onTouchEvent(mapped);
    } finally {
      dispatchingViewport = false;
      mapped.recycle();
    }
  }

  public void setSelectionListener(Runnable value) {
    selectionListener = value;
  }

  public boolean hasSelection() {
    return !selectedStrokes.isEmpty() || !selectedElements.isEmpty();
  }

  public int selectionStrokeCount() {
    return selectedStrokes.size();
  }

  public int selectionElementCount() {
    return selectedElements.size();
  }

  public SelectionData copySelection() {
    if (!hasSelection()) return null;
    SelectionData data = new SelectionData();
    for (NoteDocument.Stroke stroke : selectedStrokeObjects()) data.strokes.add(stroke.copy());
    for (NoteDocument.Element element : selectedElements) data.elements.add(element.copy());
    data.bounds = new RectF(selectionBounds);
    return data;
  }

  public void pasteSelection(SelectionData data) {
    if (data == null || page == null) return;
    cancelGesture();
    PageNavigation.Location at = captureViewport();
    float factor =
        page.infinite
            ? 1
            : Math.min(
                1,
                Math.min(
                    page.width * .9f / Math.max(1, data.bounds.width()),
                    page.height * .9f / Math.max(1, data.bounds.height())));
    float cx = at.centerX, cy = at.centerY;
    if (!page.infinite) {
      cx =
          Math.max(
              data.bounds.width() * factor / 2,
              Math.min(page.width - data.bounds.width() * factor / 2, cx));
      cy =
          Math.max(
              data.bounds.height() * factor / 2,
              Math.min(page.height - data.bounds.height() * factor / 2, cy));
    }
    ArrayList<NoteDocument.Stroke> strokes = new ArrayList<>();
    ArrayList<NoteDocument.Element> elements = new ArrayList<>();
    for (NoteDocument.Stroke source : data.strokes) {
      NoteDocument.Stroke copy = source.copy();
      copy.id = UUID.randomUUID().toString();
      ObjectTransform.applyStroke(
          source,
          copy,
          data.bounds.centerX(),
          data.bounds.centerY(),
          factor,
          0,
          cx - data.bounds.centerX(),
          cy - data.bounds.centerY());
      strokes.add(copy);
    }
    for (NoteDocument.Element source : data.elements) {
      NoteDocument.Element copy = source.copy();
      ObjectTransform.applyElement(
          source,
          copy,
          data.bounds.centerX(),
          data.bounds.centerY(),
          factor,
          0,
          cx - data.bounds.centerX(),
          cy - data.bounds.centerY());
      elements.add(copy);
    }
    mutate(
        () -> {
          page.strokes.addAll(strokes);
          page.elements.addAll(elements);
        });
    for (NoteDocument.Stroke stroke : strokes)
      if (page.strokes.contains(stroke)) selectedStrokes.add(stroke.id);
    for (NoteDocument.Element element : elements)
      if (page.elements.contains(element)) selectedElements.add(element);
    rebuildSelectionBounds();
  }

  public boolean transformSelection(float factor, float degrees) {
    return transformSelection(factor, degrees, 0, 0);
  }

  private boolean transformSelection(float factor, float degrees, float dx, float dy) {
    if (!hasSelection() || selectionBounds == null || !Float.isFinite(factor) || factor <= 0)
      return false;
    if (Math.abs(factor - 1) < .0001f
        && Math.abs(degrees) < .0001f
        && Math.abs(dx) < .0001f
        && Math.abs(dy) < .0001f) return true;
    if (objectEditBefore == null) beginObjectEditSession();
    ArrayList<NoteDocument.Stroke> originals = selectedStrokeObjects();
    ArrayList<NoteDocument.Element> elements = new ArrayList<>(selectedElements);
    ArrayList<NoteDocument.Stroke> changedStrokes = new ArrayList<>();
    ArrayList<NoteDocument.Element> changedElements = new ArrayList<>();
    float cx = selectionBounds.centerX(), cy = selectionBounds.centerY();
    for (NoteDocument.Stroke source : originals) {
      NoteDocument.Stroke copy = source.copy();
      ObjectTransform.applyStroke(source, copy, cx, cy, factor, degrees, dx, dy);
      if (!ObjectTransform.isValid(copy, page)) return false;
      changedStrokes.add(copy);
    }
    for (NoteDocument.Element source : elements) {
      NoteDocument.Element copy = source.copy();
      ObjectTransform.applyElement(source, copy, cx, cy, factor, degrees, dx, dy);
      if (!ObjectTransform.isValid(copy, page)) return false;
      changedElements.add(copy);
    }
    NoteDocument.Page before = page.copy();
    for (int n = 0; n < originals.size(); n++)
      page.strokes.set(page.strokes.indexOf(originals.get(n)), changedStrokes.get(n));
    for (int n = 0; n < elements.size(); n++)
      page.elements.set(page.elements.indexOf(elements.get(n)), changedElements.get(n));
    selectedElements.clear();
    selectedElements.addAll(changedElements);
    try {
      document.validate();
    } catch (org.json.JSONException invalid) {
      InkHistory.restore(page, before);
      clearSelection();
      refreshPaper();
      return false;
    }
    history.record(before);
    rebuildSelectionBounds();
    dirtyPaper();
    changed();
    return true;
  }

  public void finishSelectionEditing() {
    cancelGesture();
    clearSelection();
    invalidate();
  }

  public void cancelSelectionEditing() {
    cancelObjectEditSession();
    clearSelection();
    invalidate();
  }

  private void rebuildSelectionBounds() {
    selectionBounds = null;
    for (NoteDocument.Stroke stroke : selectedStrokeObjects())
      for (NoteDocument.Point point : InkGeometry.outline(stroke))
        addBounds(
            point.x - stroke.width,
            point.y - stroke.width,
            point.x + stroke.width,
            point.y + stroke.width);
    for (NoteDocument.Element element : selectedElements) {
      ObjectTransform.Bounds box = ObjectTransform.bounds(element);
      addBounds(box.left, box.top, box.right, box.bottom);
    }
    if (selectionListener != null) selectionListener.run();
    invalidate();
  }

  private boolean selectionHit(float x, float y) {
    float radius = 24 / scale;
    return selectionBounds.contains(x, y)
        || Math.hypot(x - selectionBounds.right, y - selectionBounds.bottom) < radius
        || Math.hypot(x - selectionBounds.centerX(), y - selectionBounds.top + 28 / scale) < radius;
  }

  private void beginSelectionPreview(float x, float y) {
    selectionStart = new RectF(selectionBounds);
    selectionScale = 1;
    selectionDegrees = selectionDx = selectionDy = 0;
    selectionDownX = x;
    selectionDownY = y;
    selectionHandle =
        Math.hypot(x - selectionBounds.centerX(), y - selectionBounds.top + 28 / scale) < 24 / scale
            ? 2
            : Math.hypot(x - selectionBounds.right, y - selectionBounds.bottom) < 24 / scale
                ? 1
                : 0;
    float density =
        Math.min(
            scale * 1.5f,
            2048f / Math.max(1, Math.max(selectionStart.width(), selectionStart.height())));
    boolean inkSelected = !selectedStrokes.isEmpty();
    selectionPreview =
        Bitmap.createBitmap(
            inkSelected ? Math.max(1, (int) Math.ceil(selectionStart.width() * density)) : 1,
            inkSelected ? Math.max(1, (int) Math.ceil(selectionStart.height() * density)) : 1,
            Bitmap.Config.ARGB_8888);
    Canvas preview = new Canvas(selectionPreview);
    preview.scale(density, density);
    preview.translate(-selectionStart.left, -selectionStart.top);
    for (NoteDocument.Stroke stroke : selectedStrokeObjects())
      PageRenderer.drawInk(preview, stroke);
    selectionPaintPage = page.copy();
    selectionPaintPage.strokes.removeIf(stroke -> selectedStrokes.contains(stroke.id));
    for (int n = page.elements.size() - 1; n >= 0; n--)
      if (selectedElements.contains(page.elements.get(n))) selectionPaintPage.elements.remove(n);
    dirtyPaper();
  }

  private void updateSelectionPreview(float x, float y) {
    float cx = selectionStart.centerX(), cy = selectionStart.centerY();
    if (selectionHandle == 2)
      selectionDegrees =
          (float)
              Math.toDegrees(
                  Math.atan2(y - cy, x - cx)
                      - Math.atan2(selectionDownY - cy, selectionDownX - cx));
    else if (selectionHandle == 1)
      selectionScale =
          Math.max(
              .05f,
              Math.min(
                  20,
                  (float) Math.hypot(x - cx, y - cy)
                      / Math.max(
                          .01f, (float) Math.hypot(selectionDownX - cx, selectionDownY - cy))));
    else {
      selectionDx = x - selectionDownX;
      selectionDy = y - selectionDownY;
    }
    Matrix matrix = new Matrix();
    matrix.setScale(selectionScale, selectionScale, cx, cy);
    matrix.postRotate(selectionDegrees, cx, cy);
    matrix.postTranslate(selectionDx, selectionDy);
    selectionBounds = new RectF();
    matrix.mapRect(selectionBounds, selectionStart);
    invalidate();
  }

  private void releaseSelectionPreview() {
    if (selectionPreview == null) return;
    selectionPreview.recycle();
    selectionPreview = null;
    selectionPaintPage = null;
    selectionBounds = selectionStart;
    selectionStart = null;
    dirtyPaper();
  }

  /** Selection commands are committed as one history entry, then dismiss their selection. */
  public void duplicateSelection() {
    cancelGesture();
    if (!hasSelection() || page == null || selectionBounds == null) return;
    ArrayList<NoteDocument.Stroke> strokes = selectedStrokeObjects();
    ArrayList<NoteDocument.Element> elements = new ArrayList<>(selectedElements);
    float dx =
        page.infinite
            ? 24
            : Math.max(-selectionBounds.left, Math.min(24, page.width - selectionBounds.right));
    float dy =
        page.infinite
            ? 24
            : Math.max(-selectionBounds.top, Math.min(24, page.height - selectionBounds.bottom));
    mutate(
        () -> {
          for (NoteDocument.Stroke original : strokes) {
            NoteDocument.Stroke copy = original.copy();
            copy.id = UUID.randomUUID().toString();
            InkGeometry.move(copy, dx, dy);
            page.strokes.add(copy);
          }
          for (NoteDocument.Element original : elements) {
            NoteDocument.Element copy = original.copy();
            copy.x += dx;
            copy.y += dy;
            page.elements.add(copy);
          }
        });
  }

  public void deleteSelection() {
    cancelGesture();
    if (!hasSelection() || page == null) return;
    ArrayList<NoteDocument.Stroke> strokes = selectedStrokeObjects();
    ArrayList<NoteDocument.Element> elements = new ArrayList<>(selectedElements);
    mutate(
        () -> {
          page.strokes.removeAll(strokes);
          page.elements.removeAll(elements);
        });
  }

  public void recolorSelection(int color) {
    cancelGesture();
    if (!hasSelection() || page == null) return;
    ArrayList<NoteDocument.Stroke> strokes = selectedStrokeObjects();
    ArrayList<NoteDocument.Element> elements = new ArrayList<>(selectedElements);
    boolean changes = false;
    for (NoteDocument.Stroke stroke : strokes) changes |= stroke.color != color;
    for (NoteDocument.Element element : elements)
      changes |= "text".equals(element.kind) && element.color != color;
    if (!changes) {
      clearSelection();
      invalidate();
      return;
    }
    mutate(
        () -> {
          for (NoteDocument.Stroke stroke : strokes) stroke.color = color;
          for (NoteDocument.Element element : elements)
            if ("text".equals(element.kind)) element.color = color;
        });
  }

  public void resizeSelectedStrokes(float width) {
    cancelGesture();
    if (!hasSelection() || page == null) return;
    if (Float.isNaN(width) || Float.isInfinite(width) || width < .1f || width > 200)
      throw new IllegalArgumentException("笔宽必须在 0.1 到 200 之间");
    ArrayList<NoteDocument.Stroke> strokes = selectedStrokeObjects();
    boolean changes = false;
    for (NoteDocument.Stroke stroke : strokes) changes |= stroke.width != width;
    if (!changes) {
      clearSelection();
      invalidate();
      return;
    }
    mutate(
        () -> {
          for (NoteDocument.Stroke stroke : strokes) stroke.width = width;
        });
  }

  private ArrayList<NoteDocument.Stroke> selectedStrokeObjects() {
    ArrayList<NoteDocument.Stroke> strokes = new ArrayList<>();
    if (page != null)
      for (NoteDocument.Stroke stroke : page.strokes)
        if (selectedStrokes.contains(stroke.id)) strokes.add(stroke);
    return strokes;
  }

  public boolean canUndo() {
    return history.canUndo(page);
  }

  public boolean canRedo() {
    return history.canRedo(page);
  }

  public void undo() {
    cancelGesture();
    if (page != null && history.undo(page)) {
      clearSelection();
      if (history.restoredTapeOnly()) invalidate();
      else refreshPaper();
      changed();
    }
  }

  public void redo() {
    cancelGesture();
    if (page != null && history.redo(page)) {
      clearSelection();
      if (history.restoredTapeOnly()) invalidate();
      else refreshPaper();
      changed();
    }
  }

  /** External page changes (text, images, paper) participate in the same page history. */
  public void mutate(Runnable mutation) {
    if (page == null) return;
    cancelGesture();
    NoteDocument.Page before = page.copy();
    try {
      mutation.run();
      document.validate();
    } catch (org.json.JSONException ex) {
      InkHistory.restore(page, before);
      clearSelection();
      refreshPaper();
      android.widget.Toast.makeText(getContext(), ex.getMessage(), android.widget.Toast.LENGTH_LONG)
          .show();
      return;
    } catch (RuntimeException ex) {
      InkHistory.restore(page, before);
      clearSelection();
      refreshPaper();
      throw ex;
    }
    history.recordSnapshot(before);
    clearSelection();
    refreshPaper();
    changed();
  }

  /** Only callers changing tape visibility use this path; paper and ink geometry stay intact. */
  private void mutateOverlay(Runnable mutation) {
    if (page == null) return;
    cancelGesture();
    history.recordTapeVisibility(page);
    mutation.run();
    clearSelection();
    invalidate();
    changed();
  }

  public void refreshPaper() {
    if (document != null) strip.update(document);
    dirtyPaper();
    invalidate();
    if (page != null) {
      PageRenderer.MediaRequest old = mediaRequests.remove(page);
      if (old != null) old.cancel();
      watchPage(page);
    }
  }

  public void fitPage() {
    widthFitPreferred = false;
    calculateFit(false);
  }

  /** Temporarily show the full page without changing the user's preferred next-page fit. */
  public void fitOverviewPreservingPreference() {
    calculateFit(false);
  }

  boolean isWidthFitPreferred() {
    return widthFitPreferred;
  }

  public void fitWidth() {
    widthFitPreferred = true;
    calculateFit(true);
  }

  public void setWidthFitPreferred(boolean value) {
    widthFitPreferred = value;
    needsFit = true;
    invalidate();
  }

  private void fitPreferred() {
    calculateFit(widthFitPreferred);
  }

  private float widthFitScale(int viewportWidth) {
    float margin = 16 * getResources().getDisplayMetrics().density;
    return Math.max(page.infinite ? .0001f : .02f, (viewportWidth - margin * 2) / page.width);
  }

  private void calculateFit(boolean width) {
    if (page == null || getWidth() == 0 || getHeight() == 0) {
      needsFit = true;
      return;
    }
    float margin = (width ? 16 : 24) * getResources().getDisplayMetrics().density;
    float overviewMargin = 24 * getResources().getDisplayMetrics().density;
    fitScale =
        Math.min(
            (getWidth() - overviewMargin * 2) / page.width,
            (getHeight() - overviewMargin * 2) / page.height);
    fitScale = Math.max(page.infinite ? .0001f : .02f, fitScale);
    scale = width ? widthFitScale(getWidth()) : fitScale;
    offsetX = (getWidth() - page.width * scale) / 2;
    offsetY = width ? margin : (getHeight() - page.height * scale) / 2;
    if (page.infinite && page.backgroundPath.isEmpty()) {
      offsetX = getWidth() / 2f;
      offsetY = getHeight() / 2f;
    }
    widthFitViewport = width;
    needsFit = false;
    invalidate();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    if (page != null && !needsFit && oldw > 0 && oldh > 0 && w > 0 && h > 0) {
      if (w != oldw || activePointer < 0 || !"text".equals(gestureTool)) cancelInput();
      float centerX = (oldw / 2f - offsetX) / scale;
      float centerY = (oldh / 2f - offsetY) / scale;
      float density = getResources().getDisplayMetrics().density;
      float previousFit = widthFitViewport ? widthFitScale(oldw) : fitScale;
      float zoom = scale / previousFit;
      float margin = 24 * density;
      fitScale =
          Math.max(
              page.infinite ? .0001f : .02f,
              Math.min((w - margin * 2) / page.width, (h - margin * 2) / page.height));
      // Inline controls and the keyboard change height without changing the writing surface's
      // width. Keep its actual scale instead of shrinking it to the shorter viewport.
      if (w != oldw) scale = zoom * (widthFitViewport ? widthFitScale(w) : fitScale);
      offsetX = w / 2f - centerX * scale;
      offsetY = h / 2f - centerY * scale;
      if (w != oldw && !page.infinite && !isContinuousScrolling()) {
        float edgeMargin = (widthFitViewport ? 16 : 24) * density;
        offsetX = constrainPageOffset(offsetX, page.width * scale, w, edgeMargin);
        offsetY = constrainPageOffset(offsetY, page.height * scale, h, edgeMargin);
      }
      constrainScroll();
    } else needsFit = true;
  }

  private static float constrainPageOffset(float offset, float extent, int viewport, float margin) {
    if (extent <= viewport - margin * 2) return (viewport - extent) / 2;
    return Math.min(margin, Math.max(viewport - margin - extent, offset));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.drawColor(Ui.WORKSPACE);
    if (page == null) return;
    if (needsFit) fitPreferred();
    crispBitmap =
        selectionPreview != null
            || page.infinite
            || isContinuousScrolling()
            || scale > Math.min(1.5f, 2048f / Math.max(page.width, page.height)) * 1.15f;
    drawPageStrip(canvas);
    if (!crispBitmap) updateBitmap();
    Paint p = overlayPaint;
    p.reset();
    p.setAntiAlias(true);
    pageBounds.set(offsetX, offsetY, offsetX + page.width * scale, offsetY + page.height * scale);
    shadowBounds.set(
        pageBounds.left - 3, pageBounds.top + 3, pageBounds.right + 3, pageBounds.bottom + 8);
    p.setColor(0x66000000);
    if (!page.infinite) canvas.drawRoundRect(shadowBounds, 4, 4, p);
    if (crispBitmap)
      paintViewport(mainViewportCache, canvas, scale, offsetX, offsetY, getWidth(), getHeight());
    else if (pageBitmap != null) canvas.drawBitmap(pageBitmap, null, pageBounds, surfacePaint);
    int saved = canvas.save();
    canvas.translate(offsetX, offsetY);
    canvas.scale(scale, scale);
    if (!page.infinite) canvas.clipRect(0, 0, page.width, page.height);
    if (!crispBitmap && activeStroke != null) drawActive(canvas);
    if (!crispBitmap) PageRenderer.drawTapes(canvas, page, editingImage);
    if (tapeDraft != null
        && studyDown != null
        && Math.hypot(lastX - studyDown.x, lastY - studyDown.y) * scale > 6)
      PageRenderer.drawElement(canvas, tapeDraft);
    if (laser.draw(canvas, scale)) postInvalidateOnAnimation();
    if (dashScale != scale) updateDashes();
    if (!lasso.isEmpty()) {
      p.setColor(Ui.ACCENT);
      p.setStrokeWidth(1.5f / scale);
      p.setStyle(Paint.Style.STROKE);
      p.setPathEffect(lassoDash);
      lassoPath.reset();
      lassoPath.moveTo(lasso.get(0).x, lasso.get(0).y);
      for (int i = 1; i < lasso.size(); i++) lassoPath.lineTo(lasso.get(i).x, lasso.get(i).y);
      canvas.drawPath(lassoPath, p);
      p.setPathEffect(null);
    }
    if (selectionBounds != null) {
      if (selectionPreview != null) {
        int preview = canvas.save();
        canvas.translate(selectionDx, selectionDy);
        canvas.rotate(selectionDegrees, selectionStart.centerX(), selectionStart.centerY());
        canvas.scale(
            selectionScale, selectionScale, selectionStart.centerX(), selectionStart.centerY());
        canvas.drawBitmap(selectionPreview, null, selectionStart, surfacePaint);
        // Decode completion must update selected media too; never freeze a loading placeholder
        // into the ink preview. Text and images retain their original geometry while rotating.
        for (NoteDocument.Element element : selectedElements)
          PageRenderer.drawElement(canvas, element);
        canvas.restoreToCount(preview);
      }
      p.setColor(Ui.ACCENT);
      p.setStrokeWidth(1.5f / scale);
      p.setStyle(Paint.Style.STROKE);
      p.setPathEffect(selectionDash);
      canvas.drawRect(selectionBounds, p);
      p.setPathEffect(null);
      p.setStyle(Paint.Style.FILL);
      canvas.drawCircle(selectionBounds.right, selectionBounds.bottom, 7 / scale, p);
      canvas.drawLine(
          selectionBounds.centerX(),
          selectionBounds.top,
          selectionBounds.centerX(),
          selectionBounds.top - 28 / scale,
          p);
      RotationGlyph.handle(
          canvas,
          selectionBounds.centerX(),
          selectionBounds.top - 28 / scale,
          11 / scale,
          Ui.ACCENT,
          p);
    }
    if (focusRegion != null) {
      p.setColor(Ui.ACCENT);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(2 / scale);
      canvas.drawRect(focusRegion, p);
    }
    drawInputCursor(canvas, scale);
    canvas.restoreToCount(saved);
    drawPagePull(canvas);
  }

  private void drawPagePull(Canvas canvas) {
    if (pagePullIndicator.draw(
        canvas,
        getWidth() / 2f,
        getHeight() - Ui.dp(getContext(), 24),
        pagePull,
        pagePullThreshold(),
        getResources().getDisplayMetrics().density)) postInvalidateOnAnimation();
  }

  private void drawInputCursor(Canvas canvas, float viewportScale) {
    boolean erasing = activePointer >= 0 && "eraser".equals(gestureTool);
    if (erasing) {
      Paint p = overlayPaint;
      p.setPathEffect(null);
      p.setStyle(Paint.Style.STROKE);
      p.setColor(0xddffffff);
      p.setStrokeWidth(3 / viewportScale);
      canvas.drawCircle(lastX, lastY, eraserWidth / 2, p);
      p.setColor(Ui.ACCENT);
      p.setStrokeWidth(1.4f / viewportScale);
      canvas.drawCircle(lastX, lastY, eraserWidth / 2, p);
    } else if (hovering && activePointer < 0) {
      Paint p = overlayPaint;
      p.setColor((Ui.ACCENT & 0xffffff) | 0x99000000);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1 / viewportScale);
      canvas.drawCircle(
          hoverX,
          hoverY,
          "eraser".equals(hoverTool) ? eraserWidth / 2 : Math.max(2 / viewportScale, inkWidth / 2),
          p);
    }
  }

  private void updateBitmap() {
    float rasterScale = Math.min(1.5f, 2048f / Math.max(page.width, page.height));
    if (!renderDirty && pageBitmap != null) {
      if (eraserBitmapDirty != null) {
        InkViewportCache.redrawRegion(
            new Canvas(pageBitmap),
            pageStaticBase,
            page,
            eraserBitmapIndex,
            eraserBitmapDirty,
            rasterScale,
            1,
            0,
            0,
            pageBitmap.getWidth(),
            pageBitmap.getHeight());
        eraserBitmapDirty = null;
        eraserBitmapIndex = null;
      }
      return;
    }
    int width = Math.max(1, (int) (page.width * rasterScale)),
        height = Math.max(1, (int) (page.height * rasterScale));
    try {
      if (pageBitmap == null
          || pageBitmap.getWidth() != width
          || pageBitmap.getHeight() != height) {
        releaseBitmap();
        pageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        pageStaticBase = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      }
      InkViewportCache.drawStaticBase(pageStaticBase, page, rasterScale, 1, 0, 0, editingImage);
      Canvas raster = new Canvas(pageBitmap);
      InkViewportCache.restoreBase(raster, pageStaticBase);
      raster.scale(rasterScale, rasterScale);
      raster.clipRect(0, 0, page.width, page.height);
      for (NoteDocument.Stroke stored : page.strokes) PageRenderer.drawInk(raster, stored);
      renderDirty = false;
      eraserBitmapDirty = null;
      eraserBitmapIndex = null;
    } catch (OutOfMemoryError error) {
      releaseBitmap();
    }
  }

  private void drawPageStrip(Canvas canvas) {
    if (document == null) return;
    java.util.Set<NoteDocument.Page> visible = new java.util.HashSet<>();
    int current = document.currentPage;
    int from = current, to = current;
    if (isContinuousScrolling()) {
      if (strip.count() != document.pages.size()) strip.update(document);
      RectF rect = strip.page(current);
      from = strip.at(rect.left, rect.top - offsetY / scale, true);
      to = strip.at(rect.left, rect.top + (getHeight() - offsetY) / scale, true);
      for (int n = from; n <= to; n++) {
        NoteDocument.Page other = document.pages.get(n);
        visible.add(other);
        if (other == page) continue;
        RectF at = strip.page(n);
        float x = offsetX + (at.left - rect.left) * scale,
            y = offsetY + (at.top - rect.top) * scale;
        int top = Math.max(0, (int) Math.floor(y)),
            bottom = Math.min(getHeight(), (int) Math.ceil(y + other.height * scale));
        if (top >= bottom) continue;
        InkViewportCache cache = stripCaches.get(other);
        if (cache == null) {
          cache = new InkViewportCache();
          stripCaches.put(other, cache);
        }
        int saved = canvas.save();
        canvas.clipRect(0, top, getWidth(), bottom);
        canvas.translate(0, top);
        cache.draw(
            canvas,
            other,
            null,
            scale,
            x,
            y - top,
            getWidth(),
            bottom - top,
            stripRevisions.getOrDefault(other, 0L),
            null,
            navigating || !scroller.isFinished());
        canvas.restoreToCount(saved);
      }
    }
    visible.add(page);
    for (java.util.Iterator<java.util.Map.Entry<NoteDocument.Page, InkViewportCache>> it =
            stripCaches.entrySet().iterator();
        it.hasNext(); ) {
      java.util.Map.Entry<NoteDocument.Page, InkViewportCache> entry = it.next();
      if (!visible.contains(entry.getKey())) {
        entry.getValue().release();
        stripRevisions.remove(entry.getKey());
        it.remove();
      }
    }
    // Visible pages own subscriptions; offscreen pages are not decoded into a full notebook raster.
    for (java.util.Iterator<java.util.Map.Entry<NoteDocument.Page, PageRenderer.MediaRequest>> it =
            mediaRequests.entrySet().iterator();
        it.hasNext(); ) {
      java.util.Map.Entry<NoteDocument.Page, PageRenderer.MediaRequest> entry = it.next();
      if (!visible.contains(entry.getKey())) {
        entry.getValue().cancel();
        it.remove();
      }
    }
    for (NoteDocument.Page candidate : visible) watchPage(candidate);
  }

  private void watchPage(NoteDocument.Page candidate) {
    if (mediaRequests.containsKey(candidate)) return;
    PageRenderer.MediaRequest request =
        PageRenderer.subscribe(
            candidate,
            () -> {
              if (!queuedMediaChanges.add(candidate)) return;
              postOnAnimation(
                  () -> {
                    queuedMediaChanges.remove(candidate);
                    if (!mediaRequests.containsKey(candidate)) return;
                    stripRevisions.put(candidate, stripRevisions.getOrDefault(candidate, 0L) + 1);
                    if (candidate == page) dirtyPaper();
                    invalidate();
                  });
            });
    mediaRequests.put(candidate, request);
  }

  private void releaseBitmap() {
    if (pageBitmap != null) {
      pageBitmap.recycle();
      pageBitmap = null;
    }
    if (pageStaticBase != null) {
      pageStaticBase.recycle();
      pageStaticBase = null;
    }
    dirtyPaper();
  }

  private void paintViewport(
      InkViewportCache cache, Canvas canvas, float factor, float x, float y, int w, int h) {
    int entry = canvas.save();
    try {
      cache.draw(
          canvas,
          selectionPaintPage == null ? page : selectionPaintPage,
          activeStroke,
          factor,
          x,
          y,
          w,
          h,
          paperGeneration,
          editingImage,
          navigating || !scroller.isFinished());
    } catch (OutOfMemoryError error) {
      cache.release();
      canvas.restoreToCount(entry);
      int save = canvas.save();
      try {
        canvas.translate(x, y);
        canvas.scale(factor, factor);
        PageRenderer.drawBackground(canvas, page, true);
        PageRenderer.drawElements(canvas, page, editingImage);
        for (NoteDocument.Stroke stroke : page.strokes) PageRenderer.drawInk(canvas, stroke);
        if (activeStroke != null) PageRenderer.drawInk(canvas, activeStroke);
        PageRenderer.drawTapes(canvas, page, editingImage);
      } finally {
        canvas.restoreToCount(save);
      }
      return;
    } finally {
      if (canvas.getSaveCount() > entry) canvas.restoreToCount(entry);
    }
  }

  private void dirtyPaper() {
    renderDirty = true;
    paperGeneration++;
    if (page != null) stripRevisions.put(page, stripRevisions.getOrDefault(page, 0L) + 1);
    eraserBitmapDirty = null;
    eraserBitmapIndex = null;
  }

  private void updateDashes() {
    dashScale = scale;
    lassoDash = new DashPathEffect(new float[] {7 / scale, 5 / scale}, 0);
    selectionDash = new DashPathEffect(new float[] {6 / scale, 4 / scale}, 0);
  }

  private void drawActive(Canvas canvas) {
    if (InkGeometry.isShape(activeStroke.tool) || activeStroke.points.size() < 2) {
      PageRenderer.drawInk(canvas, activeStroke);
      return;
    }
    float rasterScale = Math.min(1.5f, 2048f / Math.max(page.width, page.height));
    if (activeBitmap == null) {
      try {
        activeBitmap =
            Bitmap.createBitmap(
                Math.max(1, (int) (page.width * rasterScale)),
                Math.max(1, (int) (page.height * rasterScale)),
                Bitmap.Config.ARGB_8888);
        activeCanvas = new Canvas(activeBitmap);
        activeCanvas.scale(rasterScale, rasterScale);
        nextActiveSegment = 1;
      } catch (OutOfMemoryError error) {
        PageRenderer.drawInk(canvas, activeStroke);
        return;
      }
    }
    boolean marker = "highlighter".equals(activeStroke.tool);
    boolean uniformOpacity = !"pencil".equals(activeStroke.tool);
    int strokeAlpha =
        marker ? BrushStyle.markerAlpha(activeStroke.color) : Color.alpha(activeStroke.color);
    while (nextActiveSegment < activeStroke.points.size() - 1) {
      PageRenderer.drawInkSegment(
          activeCanvas, activeStroke, nextActiveSegment, true, uniformOpacity);
      nextActiveSegment++;
    }
    float padding = activeStroke.width + 2;
    int layer =
        uniformOpacity && strokeAlpha < 255
            ? canvas.saveLayerAlpha(
                activeInkBounds.left - padding,
                activeInkBounds.top - padding,
                activeInkBounds.right + padding,
                activeInkBounds.bottom + padding,
                strokeAlpha)
            : -1;
    rasterBounds.set(0, 0, page.width, page.height);
    canvas.drawBitmap(activeBitmap, null, rasterBounds, surfacePaint);
    PageRenderer.drawInkSegment(
        canvas, activeStroke, activeStroke.points.size() - 1, false, uniformOpacity);
    if (layer >= 0) canvas.restoreToCount(layer);
  }

  private void releaseActiveBitmap() {
    if (activeBitmap != null) activeBitmap.recycle();
    activeBitmap = null;
    activeCanvas = null;
    nextActiveSegment = 1;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (page == null) return false;
    int action = event.getActionMasked(), index = event.getActionIndex();
    if (action == MotionEvent.ACTION_DOWN) {
      hovering = false;
      scroller.forceFinished(true);
      if (velocity != null) velocity.recycle();
      velocity = android.view.VelocityTracker.obtain();
      navigationHadPinch = false;
      pagePull = 0;
      pagePullEligible = false;
    }
    if (velocity != null) velocity.addMovement(event);
    if (action == MotionEvent.ACTION_POINTER_DOWN) {
      navigationHadPinch = true;
      pagePull = 0;
      pagePullEligible = false;
    }
    if (focusRegion != null && !dispatchingViewport && focusLocator != null) {
      if (needsFit) fitPreferred();
      if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
        focusLocator.accept(
            new android.graphics.PointF(
                (event.getX() - offsetX) / scale, (event.getY() - offsetY) / scale));
      }
      return true;
    }
    boolean canceledPointer =
        (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0
            && activePointer >= 0
            && event.getPointerId(index) == activePointer;
    boolean navigationCanceled =
        navigating
            && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0
            && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP);
    if (action == MotionEvent.ACTION_CANCEL || canceledPointer || navigationCanceled) {
      cancelGesture();
      navigating = false;
      multiNavigation = false;
      referencePoint = null;
      pagePull = 0;
      pagePullEligible = false;
      invalidate();
      return true;
    }
    if (action == MotionEvent.ACTION_DOWN) {
      if (needsFit) fitPreferred();
      activatePageAt(event.getX(), event.getY(), false);
      requestFocus();
      if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
      cancelGesture();
      navigating = false;
      multiNavigation = false;
      referenceDownX = event.getX(index);
      referenceDownY = event.getY(index);
      referenceDownTime = event.getEventTime();
      int type = event.getToolType(index);
      referencePoint =
          ("hand".equals(tool) || (type == MotionEvent.TOOL_TYPE_FINGER && !fingerDrawing))
              ? new android.graphics.PointF(
                  (referenceDownX - offsetX) / scale, (referenceDownY - offsetY) / scale)
              : null;
      boolean selectionContact =
          "lasso".equals(tool)
              && selectionBounds != null
              && selectionHit(
                  (event.getX(index) - offsetX) / scale, (event.getY(index) - offsetY) / scale);
      if (!selectionContact
          && type != MotionEvent.TOOL_TYPE_STYLUS
          && type != MotionEvent.TOOL_TYPE_ERASER
          && !fingerDrawing
          && !"text".equals(tool)) {
        beginNavigation(event);
        return true;
      }
      if ("hand".equals(eventTool(event, index))) {
        beginNavigation(event);
        return true;
      }
      beginInk(event, index);
      return true;
    }
    if (action == MotionEvent.ACTION_POINTER_DOWN) {
      referencePoint = null;
      int type = event.getToolType(index);
      // A pen arriving after a palm takes ownership; finger contacts never replace an active pen.
      if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
        if (activePointer < 0 || activeToolType == MotionEvent.TOOL_TYPE_FINGER) {
          cancelGesture();
          navigating = false;
          multiNavigation = false;
          beginInk(event, index);
        }
      } else if (activePointer < 0 || activeToolType == MotionEvent.TOOL_TYPE_FINGER) {
        cancelGesture();
        beginNavigation(event);
      }
      return true;
    }
    if (navigating) {
      if (action == MotionEvent.ACTION_MOVE) {
        if (Math.hypot(event.getX() - referenceDownX, event.getY() - referenceDownY)
            > 8 * getResources().getDisplayMetrics().density) referencePoint = null;
        navigate(event);
      } else if (action == MotionEvent.ACTION_POINTER_UP) {
        // Restart the centroid with the contacts that will remain after this event.
        int remaining = -1;
        for (int i = 0; i < event.getPointerCount(); i++)
          if (i != index) {
            remaining = i;
            break;
          }
        if (remaining >= 0) {
          lastNavX = event.getX(remaining);
          lastNavY = event.getY(remaining);
        }
        multiNavigation = false;
        lastNavDistance = 0;
      } else if (action == MotionEvent.ACTION_UP) {
        boolean append =
            pagePullEligible
                && !navigationHadPinch
                && pagePull >= pagePullThreshold()
                && atNotebookEnd();
        pagePull = 0;
        pagePullEligible = false;
        if (append) {
          navigating = false;
          multiNavigation = false;
          referencePoint = null;
          appendGesturePage();
          performClick();
          return true;
        }
        if (referencePoint == null && !navigationHadPinch && velocity != null) {
          velocity.computeCurrentVelocity(1000, 6000);
          float vx = velocity.getXVelocity(), vy = velocity.getYVelocity();
          if (Math.hypot(vx, vy) > 250) {
            flingX = flingY = 0;
            scroller.fling(0, 0, (int) vx, (int) vy, -2000000, 2000000, -2000000, 2000000);
            postInvalidateOnAnimation();
          }
        }
        navigating = false;
        multiNavigation = false;
        if (referencePoint != null && event.getEventTime() - referenceDownTime < 500) {
          NoteDocument.Element tape = tapeAt(referencePoint.x, referencePoint.y);
          if (tape != null) {
            referencePoint = null;
            mutateOverlay(() -> tape.revealed = !tape.revealed);
          }
        }
        if (referencePoint != null
            && event.getEventTime() - referenceDownTime < 500
            && imageEditListener != null) {
          for (int e = page.elements.size() - 1; e >= 0; e--) {
            NoteDocument.Element image = page.elements.get(e);
            if ("image".equals(image.kind)
                && ObjectTransform.contains(image, referencePoint.x, referencePoint.y)) {
              referencePoint = null;
              imageEditListener.accept(image);
              break;
            }
          }
        }
        if (referencePoint != null
            && event.getEventTime() - referenceDownTime < 500
            && referenceListener != null) {
          for (NoteDocument.PageLink link : page.links) {
            if (link.contains(referencePoint.x, referencePoint.y)) {
              referencePoint = null;
              referenceListener.accept(link.targetPageId);
              break;
            }
          }
        }
        if (referencePoint != null
            && event.getEventTime() - referenceDownTime < 500
            && referenceListener != null) {
          for (int i = page.elements.size() - 1; i >= 0; i--) {
            NoteDocument.Element e = page.elements.get(i);
            if (!e.targetPageId.isEmpty()
                && referencePoint.x >= e.x
                && referencePoint.x <= e.x + e.width
                && referencePoint.y >= e.y
                && referencePoint.y <= e.y + e.height) {
              referencePoint = null;
              referenceListener.accept(e.targetPageId);
              break;
            }
          }
        }
        referencePoint = null;
        performClick();
        invalidate();
      }
      return true;
    }
    if (activePointer < 0) return true;
    int pointerIndex = event.findPointerIndex(activePointer);
    if (pointerIndex < 0) {
      cancelGesture();
      invalidate();
      return true;
    }
    boolean modeSwitched =
        (action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_BUTTON_PRESS
                || action == MotionEvent.ACTION_BUTTON_RELEASE)
            && switchSideButton(event, pointerIndex);
    if (activePointer < 0) return true;
    if (action == MotionEvent.ACTION_MOVE) {
      if (!modeSwitched)
        for (int h = 0; h < event.getHistorySize() && activePointer >= 0; h++)
          sample(event, pointerIndex, h);
      sample(event, pointerIndex, -1);
      invalidate();
      return true;
    }
    if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
        && event.getPointerId(index) == activePointer) {
      sample(event, pointerIndex, -1);
      finishGesture();
      performClick();
      invalidate();
      return true;
    }
    return true;
  }

  private void beginInk(MotionEvent event, int index) {
    activePointer = event.getPointerId(index);
    activeToolType = event.getToolType(index);
    gestureTool = eventTool(event, index);
    if (!"lasso".equals(gestureTool)) clearSelection();
    gestureChanged = false;
    selectionMoving = false;
    float x = (event.getX(index) - offsetX) / scale, y = (event.getY(index) - offsetY) / scale;
    lastX = x;
    lastY = y;
    if ("laser".equals(gestureTool)) {
      laser.start(x, y);
    } else if ("text".equals(gestureTool) || "tape".equals(gestureTool)) {
      studyDown = new android.graphics.PointF(x, y);
      studyRawX = event.getRawX(index);
      studyRawY = event.getRawY(index);
      studyMotion = 0;
      if ("tape".equals(gestureTool)) {
        tapeCandidate = tapeAt(x, y);
        tapeDraft = new NoteDocument.Element();
        tapeDraft.kind = "tape";
        tapeDraft.x = x;
        tapeDraft.y = y;
        tapeDraft.width = tapeDraft.height = 1;
        tapeDraft.color = tapeColor;
        tapeDraft.pattern = tapePattern;
      }
    } else if ("eraser".equals(gestureTool)) {
      beforeGesture = page.copy();
      eraserIndex = new StrokeSpatialIndex(page.strokes);
      if ("segment".equals(eraserMode)) eraseSegment(x, y, x, y);
      else eraseWholeSegment(x, y, x, y);
    } else if ("lasso".equals(gestureTool)) {
      if (selectionBounds != null && selectionHit(x, y)) {
        selectionMoving = true;
        beginSelectionPreview(x, y);
      } else {
        clearSelection();
        lasso.clear();
        lasso.add(point(event, index, -1));
      }
    } else if (!"hand".equals(gestureTool)) {
      activeStroke = new NoteDocument.Stroke();
      activeStroke.tool = gestureTool;
      activeStroke.brushVersion = 2;
      activeStroke.color = inkColor;
      activeStroke.width = "highlighter".equals(gestureTool) ? inkWidth * 4 : inkWidth;
      activeStroke.pressureMode = pressureMode;
      InputDevice device = event.getDevice();
      if (device != null) {
        InputDevice.MotionRange range =
            device.getMotionRange(MotionEvent.AXIS_PRESSURE, event.getSource());
        if (range == null) range = device.getMotionRange(MotionEvent.AXIS_PRESSURE);
        if (range != null) {
          float min = range.getMin(), max = range.getMax();
          if (!Float.isNaN(min) && !Float.isNaN(max) && min >= 0 && max <= 2 && min < max) {
            activeStroke.pressureMin = min;
            activeStroke.pressureMax = max;
          }
        }
      }
      NoteDocument.Point first = point(event, index, -1);
      activeStroke.points.add(first);
      activeInkBounds.set(first.x, first.y, first.x, first.y);
    }
    invalidate();
  }

  private NoteDocument.Point point(MotionEvent event, int pointer, int historyIndex) {
    boolean old = historyIndex >= 0;
    float x = old ? event.getHistoricalX(pointer, historyIndex) : event.getX(pointer);
    float y = old ? event.getHistoricalY(pointer, historyIndex) : event.getY(pointer);
    float pressure =
        old ? event.getHistoricalPressure(pointer, historyIndex) : event.getPressure(pointer);
    float tilt =
        old
            ? event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointer, historyIndex)
            : event.getAxisValue(MotionEvent.AXIS_TILT, pointer);
    return new NoteDocument.Point(
        Math.max(-1000000, Math.min(1000000, (x - offsetX) / scale)),
        Math.max(-1000000, Math.min(1000000, (y - offsetY) / scale)),
        Math.max(0, Math.min(2, pressure)),
        Math.max(0, tilt),
        old ? event.getHistoricalEventTime(historyIndex) : event.getEventTime());
  }

  private String eventTool(MotionEvent event, int index) {
    int type = event.getToolType(index);
    if (type == MotionEvent.TOOL_TYPE_ERASER) return "eraser";
    if (type == MotionEvent.TOOL_TYPE_STYLUS) {
      String action = null;
      if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
        action = primaryButtonAction;
      else if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0)
        action = secondaryButtonAction;
      if (action != null && !"none".equals(action)) return action;
    }
    return tool;
  }

  private boolean switchSideButton(MotionEvent event, int index) {
    if (activeToolType != MotionEvent.TOOL_TYPE_STYLUS) return false;
    String next = eventTool(event, index);
    if (next.equals(gestureTool)) return false;
    // A mode boundary completes the existing operation; no joining line crosses the switch.
    finishGesture();
    beginInk(event, index);
    return true;
  }

  private void sample(MotionEvent event, int index, int historyIndex) {
    if (activePointer < 0) return;
    NoteDocument.Point p = point(event, index, historyIndex);
    if ("text".equals(gestureTool)) {
      studyMotion =
          Math.max(
              studyMotion,
              (float)
                  Math.hypot(event.getRawX(index) - studyRawX, event.getRawY(index) - studyRawY));
    } else if ("laser".equals(gestureTool)) {
      laser.add(p.x, p.y);
    } else if ("tape".equals(gestureTool) && tapeDraft != null && studyDown != null) {
      tapeDraft.x = Math.min(studyDown.x, p.x);
      tapeDraft.y = Math.min(studyDown.y, p.y);
      tapeDraft.width = Math.max(1, Math.abs(p.x - studyDown.x));
      tapeDraft.height = Math.max(1, Math.abs(p.y - studyDown.y));
    } else if ("eraser".equals(gestureTool)) {
      if ("segment".equals(eraserMode)) eraseSegment(lastX, lastY, p.x, p.y);
      else eraseWholeSegment(lastX, lastY, p.x, p.y);
    } else if ("lasso".equals(gestureTool)) {
      if (selectionMoving) {
        updateSelectionPreview(p.x, p.y);
      } else if (lasso.size() < 10000) lasso.add(p);
    } else if (activeStroke != null && activeStroke.points.size() < 100000) {
      activeInkBounds.union(p.x, p.y);
      if (InkGeometry.isShape(gestureTool) && activeStroke.points.size() > 1)
        activeStroke.points.set(1, p);
      else {
        NoteDocument.Point previous = activeStroke.points.get(activeStroke.points.size() - 1);
        if (Math.hypot(p.x - previous.x, p.y - previous.y) > .1) activeStroke.points.add(p);
        else {
          previous.pressure = p.pressure;
          previous.tilt = p.tilt;
          previous.time = p.time;
        }
      }
    }
    lastX = p.x;
    lastY = p.y;
  }

  private void eraseWholeSegment(float ax, float ay, float bx, float by) {
    if (eraserIndex == null) eraserIndex = new StrokeSpatialIndex(page.strokes);
    float radius = eraserWidth / 2;
    List<NoteDocument.Stroke> candidates = eraserIndex.query(ax, ay, bx, by, radius);
    for (NoteDocument.Stroke stroke : candidates) {
      if (InkGeometry.hitStroke(stroke, ax, ay, bx, by, radius)) {
        markErasedStroke(stroke);
        page.strokes.remove(stroke);
        eraserIndex.remove(stroke);
        forgetRenderedPoints(stroke);
        gestureChanged = true;
      }
    }
  }

  private void eraseSegment(float ax, float ay, float bx, float by) {
    if (eraserIndex == null) eraserIndex = new StrokeSpatialIndex(page.strokes);
    List<NoteDocument.Stroke> candidates = eraserIndex.query(ax, ay, bx, by, eraserWidth / 2);
    for (NoteDocument.Stroke original : candidates) {
      int index = page.strokes.indexOf(original);
      if (index < 0) continue;
      java.util.List<NoteDocument.Stroke> fragments;
      try {
        fragments =
            StrokeEraser.erase(
                original, ax, ay, bx, by, eraserWidth / 2, renderedPointsForEraser(original));
      } catch (IllegalArgumentException limit) {
        cancelInput();
        android.widget.Toast.makeText(
                getContext(), limit.getMessage(), android.widget.Toast.LENGTH_LONG)
            .show();
        return;
      }
      if (fragments.size() == 1 && fragments.get(0) == original) continue;
      markErasedStroke(original);
      eraserIndex.remove(original);
      forgetRenderedPoints(original);
      page.strokes.remove(index);
      page.strokes.addAll(index, fragments);
      for (NoteDocument.Stroke fragment : fragments) eraserIndex.add(fragment);
      gestureChanged = true;
    }
  }

  private List<NoteDocument.Point> renderedPointsForEraser(NoteDocument.Stroke stroke) {
    List<NoteDocument.Point> points = eraserPointCache.get(stroke);
    if (points != null) return points;
    points = InkGeometry.renderedPoints(stroke);
    if (points.size() <= 120000) {
      eraserPointCache.put(stroke, points);
      cachedEraserPoints += points.size();
      while (cachedEraserPoints > 120000 && !eraserPointCache.isEmpty()) {
        NoteDocument.Stroke oldest = eraserPointCache.keySet().iterator().next();
        forgetRenderedPoints(oldest);
      }
    }
    return points;
  }

  private void forgetRenderedPoints(NoteDocument.Stroke stroke) {
    List<NoteDocument.Point> removed = eraserPointCache.remove(stroke);
    if (removed != null) cachedEraserPoints -= removed.size();
  }

  private void clearEraserPointCache() {
    eraserPointCache.clear();
    cachedEraserPoints = 0;
  }

  private void markErasedStroke(NoteDocument.Stroke stroke) {
    float[] bounds = eraserIndex.getBounds(stroke);
    if (bounds == null) return;
    if (eraserBitmapDirty == null)
      eraserBitmapDirty = new RectF(bounds[0], bounds[1], bounds[2], bounds[3]);
    else eraserBitmapDirty.union(bounds[0], bounds[1], bounds[2], bounds[3]);
    eraserBitmapIndex = eraserIndex;
    mainViewportCache.eraseRegion(bounds, eraserIndex);
    focusViewportCache.eraseRegion(bounds, eraserIndex);
  }

  private void finishGesture() {
    if ("laser".equals(gestureTool) || "text".equals(gestureTool) || "tape".equals(gestureTool)) {
      android.graphics.PointF down = studyDown;
      boolean tap =
          down != null
              && ("text".equals(gestureTool)
                      ? studyMotion
                      : Math.hypot(lastX - down.x, lastY - down.y) * scale)
                  < 8 * getResources().getDisplayMetrics().density;
      NoteDocument.Element draft = tapeDraft, candidate = tapeCandidate;
      String completedTool = gestureTool;
      cancelGesture();
      if ("tape".equals(completedTool)) {
        if (tap && candidate != null) mutateOverlay(() -> candidate.revealed = !candidate.revealed);
        else if (!tap && draft != null) {
          draft.height = Math.max(12, draft.height);
          if (!page.infinite) {
            draft.x = Math.max(0, draft.x);
            draft.y = Math.max(0, draft.y);
            draft.width = Math.max(1, Math.min(draft.width, page.width - draft.x));
            draft.height = Math.max(1, Math.min(draft.height, page.height - draft.y));
          }
          mutate(() -> page.elements.add(draft));
        }
      } else if ("text".equals(completedTool) && tap && textToolListener != null) {
        NoteDocument.Element found = null;
        for (int n = page.elements.size() - 1; n >= 0; n--) {
          NoteDocument.Element e = page.elements.get(n);
          if ("text".equals(e.kind) && ObjectTransform.contains(e, down.x, down.y)) {
            found = e;
            break;
          }
        }
        textToolListener.accept(found, down);
      }
      invalidate();
      return;
    }
    if (selectionMoving && selectionPreview != null) {
      float factor = selectionScale, degrees = selectionDegrees, dx = selectionDx, dy = selectionDy;
      releaseSelectionPreview();
      transformSelection(factor, degrees, dx, dy);
      activePointer = -1;
      selectionMoving = false;
      gestureChanged = false;
      lasso.clear();
      return;
    }
    if (activeStroke != null && !activeStroke.points.isEmpty()) {
      long count = activeStroke.points.size();
      for (NoteDocument.Page candidate : document.pages)
        for (NoteDocument.Stroke stroke : candidate.strokes) count += stroke.points.size();
      if (page.strokes.size() >= 50000 || count > NoteDocument.MAX_POINTS) {
        android.widget.Toast.makeText(
                getContext(), "笔记已达到笔迹容量上限，请新建笔记继续书写", android.widget.Toast.LENGTH_LONG)
            .show();
      } else {
        history.record(page);
        page.strokes.add(activeStroke);
        gestureChanged = true;
        dirtyPaper();
      }
    } else if ("lasso".equals(gestureTool) && !selectionMoving) selectLasso();
    if (beforeGesture != null && gestureChanged) {
      try {
        document.validate();
        history.record(beforeGesture);
      } catch (org.json.JSONException invalid) {
        InkHistory.restore(page, beforeGesture);
        clearSelection();
        dirtyPaper();
        gestureChanged = false;
        android.widget.Toast.makeText(
                getContext(), "已恢复本次操作：" + invalid.getMessage(), android.widget.Toast.LENGTH_LONG)
            .show();
      }
    }
    boolean changed = gestureChanged;
    if (changed && page != null)
      stripRevisions.put(page, stripRevisions.getOrDefault(page, 0L) + 1);
    activeStroke = null;
    releaseActiveBitmap();
    beforeGesture = null;
    activePointer = -1;
    gestureChanged = false;
    selectionMoving = false;
    lasso.clear();
    eraserIndex = null;
    clearEraserPointCache();
    if (changed) changed();
  }

  private void cancelGesture() {
    if ("laser".equals(gestureTool)) laser.release();
    releaseSelectionPreview();
    if (beforeGesture != null && page != null && gestureChanged) {
      InkHistory.restore(page, beforeGesture);
      dirtyPaper();
      clearSelection();
    }
    activeStroke = null;
    tapeDraft = tapeCandidate = null;
    studyDown = null;
    releaseActiveBitmap();
    beforeGesture = null;
    activePointer = -1;
    gestureChanged = false;
    selectionMoving = false;
    lasso.clear();
    eraserIndex = null;
    clearEraserPointCache();
  }

  private void clearSelection() {
    boolean changed = hasSelection();
    if (changed) finishObjectEditSession();
    selectedStrokes.clear();
    selectedElements.clear();
    selectionBounds = null;
    if (changed && selectionListener != null) selectionListener.run();
  }

  private void selectLasso() {
    clearSelection();
    if (lasso.size() < 3) return;
    for (NoteDocument.Stroke s : page.strokes)
      if (InkGeometry.selectedByLasso(s, lasso)) {
        selectedStrokes.add(s.id);
        for (NoteDocument.Point p : InkGeometry.outline(s))
          addBounds(p.x - s.width, p.y - s.width, p.x + s.width, p.y + s.width);
      }
    for (NoteDocument.Element e : page.elements)
      if (InkGeometry.selectedByLasso(e, lasso)) {
        selectedElements.add(e);
        ObjectTransform.Bounds box = ObjectTransform.bounds(e);
        addBounds(box.left, box.top, box.right, box.bottom);
      }
    if (hasSelection() && selectionListener != null) selectionListener.run();
  }

  private void addBounds(float left, float top, float right, float bottom) {
    if (selectionBounds == null) selectionBounds = new RectF(left, top, right, bottom);
    else selectionBounds.union(left, top, right, bottom);
  }

  private void beginNavigation(MotionEvent event) {
    navigating = true;
    pagePull = 0;
    pagePullEligible =
        event.getPointerCount() == 1
            && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
            && !dispatchingViewport
            && !navigationHadPinch;
    multiNavigation = event.getPointerCount() >= 2;
    if (multiNavigation) {
      lastNavX = (event.getX(0) + event.getX(1)) / 2;
      lastNavY = (event.getY(0) + event.getY(1)) / 2;
      lastNavDistance =
          (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
    } else {
      lastNavX = event.getX(0);
      lastNavY = event.getY(0);
      lastNavDistance = 0;
    }
  }

  private void navigate(MotionEvent event) {
    if (event.getPointerCount() >= 2) {
      float x = (event.getX(0) + event.getX(1)) / 2, y = (event.getY(0) + event.getY(1)) / 2;
      float distance =
          (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
      if (!multiNavigation || lastNavDistance < 1) {
        beginNavigation(event);
        return;
      }
      float gestureFitScale = widthFitViewport ? widthFitScale(getWidth()) : fitScale;
      float nextScale =
          Math.max(
              gestureFitScale * (page.infinite ? .0001f : .5f),
              Math.min(
                  gestureFitScale * (page.infinite ? 64 : 8), scale * distance / lastNavDistance));
      float ratio = nextScale / scale;
      offsetX = x - (lastNavX - offsetX) * ratio;
      offsetY = y - (lastNavY - offsetY) * ratio;
      scale = nextScale;
      lastNavX = x;
      lastNavY = y;
      lastNavDistance = distance;
    } else {
      float dy = event.getY(0) - lastNavY;
      float scrollY = dy;
      if (pagePullEligible
          && atNotebookEnd()
          && Math.abs(event.getX(0) - referenceDownX)
              < Math.abs(event.getY(0) - referenceDownY) * .8f + Ui.dp(getContext(), 24)) {
        float before = pagePull;
        pagePull = Math.max(0, Math.min(pagePullThreshold() * 1.4f, pagePull - dy));
        // Returning the finger first retracts the indicator, then resumes page scrolling.
        if (before > 0 && dy > 0) scrollY = Math.max(0, dy - before);
        if (before < pagePullThreshold() && pagePull >= pagePullThreshold())
          performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
      } else pagePull = 0;
      offsetX += event.getX(0) - lastNavX;
      offsetY += scrollY;
      lastNavX = event.getX(0);
      lastNavY = event.getY(0);
    }
    constrainScroll();
    activatePageAt(getWidth() / 2f, getHeight() / 2f, true);
    invalidate();
  }

  private float pagePullThreshold() {
    return Ui.dp(getContext(), 80);
  }

  private boolean atNotebookEnd() {
    if (page == null
        || page.infinite
        || document == null
        || document.currentPage != document.pages.size() - 1
        || document.pages.size() >= NoteDocument.MAX_PAGES) return false;
    return offsetY + page.height * scale <= getHeight() - Ui.dp(getContext(), 16) + 2;
  }

  private void appendGesturePage() {
    NoteDocument.Page added = PaperCatalog.newPageLike(page);
    PageNavigation.Location location = captureViewport();
    float oldScale = scale;
    document.pages.add(added);
    strip.update(document);
    if (isContinuousScrolling()) {
      RectF current = strip.page(document.currentPage),
          next = strip.page(document.pages.size() - 1);
      float nextTop = offsetY + (next.top - current.top) * scale;
      flingX = flingY = 0;
      scroller.startScroll(0, 0, 0, Math.round(Ui.dp(getContext(), 24) - nextTop), 260);
      postInvalidateOnAnimation();
    } else {
      setPage(document.pages.size() - 1);
      restoreViewport(
          new PageNavigation.Location(
              added.id,
              location.centerX,
              (getHeight() / 2f - Ui.dp(getContext(), 24)) / oldScale,
              location.zoom,
              location.widthFit));
      if (pageChangeListener != null) pageChangeListener.run();
    }
    changed();
    if (pageAppendListener != null) pageAppendListener.run();
  }

  private void constrainScroll() {
    if (page == null || getWidth() == 0 || getHeight() == 0) return;
    if (page.infinite) {
      offsetX =
          Math.max(
              getWidth() / 2f - 900000 * scale,
              Math.min(getWidth() / 2f + 900000 * scale, offsetX));
      offsetY =
          Math.max(
              getHeight() / 2f - 900000 * scale,
              Math.min(getHeight() / 2f + 900000 * scale, offsetY));
      return;
    }
    float margin = 16 * getResources().getDisplayMetrics().density;
    if (isContinuousScrolling()) {
      RectF current = strip.page(document.currentPage);
      offsetX =
          constrainPageOffset(
                  offsetX - current.left * scale, strip.width * scale, getWidth(), margin)
              + current.left * scale;
      offsetY =
          constrainPageOffset(
                  offsetY - current.top * scale, strip.height * scale, getHeight(), margin)
              + current.top * scale;
    } else {
      offsetX = constrainPageOffset(offsetX, page.width * scale, getWidth(), margin);
      offsetY = constrainPageOffset(offsetY, page.height * scale, getHeight(), margin);
    }
  }

  @Override
  public void computeScroll() {
    if (scroller.computeScrollOffset()) {
      offsetX += scroller.getCurrX() - flingX;
      offsetY += scroller.getCurrY() - flingY;
      flingX = scroller.getCurrX();
      flingY = scroller.getCurrY();
      constrainScroll();
      activatePageAt(getWidth() / 2f, getHeight() / 2f, true);
      postInvalidateOnAnimation();
    }
  }

  private void changed() {
    if (document != null) document.modifiedAt = System.currentTimeMillis();
    if (listener != null) listener.onChanged();
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  public boolean onHoverEvent(MotionEvent event) {
    hovering = event.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT;
    hoverX = (event.getX() - offsetX) / scale;
    hoverY = (event.getY() - offsetY) / scale;
    hoverTool = eventTool(event, 0);
    invalidate();
    return true;
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent event) {
    int action = event.getActionMasked();
    if (activePointer >= 0
        && (action == MotionEvent.ACTION_BUTTON_PRESS
            || action == MotionEvent.ACTION_BUTTON_RELEASE)) {
      int index = event.findPointerIndex(activePointer);
      if (index >= 0) {
        switchSideButton(event, index);
        invalidate();
        return true;
      }
    }
    return super.onGenericMotionEvent(event);
  }

  @Override
  protected void onDetachedFromWindow() {
    releaseStrip();
    scroller.forceFinished(true);
    if (velocity != null) {
      velocity.recycle();
      velocity = null;
    }
    cancelGesture();
    releaseBitmap();
    mainViewportCache.release();
    focusViewportCache.release();
    super.onDetachedFromWindow();
  }
}
