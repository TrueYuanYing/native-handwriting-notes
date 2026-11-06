package app.iko.notes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import java.util.HashSet;
import java.util.Set;

/** A bounded raster of one visible viewport, with incremental in-progress ink. */
final class InkViewportCache {
  private Bitmap paper, ink, staticBase;
  private Canvas inkCanvas;
  private NoteDocument.Page cachedPage;
  private NoteDocument.Stroke active;
  private long generation = -1;
  private float scale, left, top, raster;
  private int width, height, nextSegment = 1;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final RectF destination = new RectF();
  private RectF dirtyBounds;
  private StrokeSpatialIndex dirtyIndex;
  private StrokeSpatialIndex visibleIndex;
  private final InfinitePanCache overview = new InfinitePanCache();

  void eraseRegion(float[] bounds, StrokeSpatialIndex index) {
    if (bounds == null) return;
    if (dirtyBounds == null) dirtyBounds = new RectF(bounds[0], bounds[1], bounds[2], bounds[3]);
    else dirtyBounds.union(bounds[0], bounds[1], bounds[2], bounds[3]);
    dirtyIndex = index;
    visibleIndex = null;
  }

  void draw(
      Canvas canvas,
      NoteDocument.Page page,
      NoteDocument.Stroke stroke,
      float factor,
      float x,
      float y,
      int w,
      int h,
      long revision,
      NoteDocument.Element excluded) {
    draw(canvas, page, stroke, factor, x, y, w, h, revision, excluded, false);
  }

  void draw(
      Canvas canvas,
      NoteDocument.Page page,
      NoteDocument.Stroke stroke,
      float factor,
      float x,
      float y,
      int w,
      int h,
      long revision,
      NoteDocument.Element excluded,
      boolean panning) {
    drawContent(canvas, page, stroke, factor, x, y, w, h, revision, excluded, panning);
    int saved = canvas.save();
    canvas.translate(x, y);
    canvas.scale(factor, factor);
    if (!page.infinite) canvas.clipRect(0, 0, page.width, page.height);
    PageRenderer.drawTapes(canvas, page, excluded);
    canvas.restoreToCount(saved);
  }

  private void drawContent(
      Canvas canvas,
      NoteDocument.Page page,
      NoteDocument.Stroke stroke,
      float factor,
      float x,
      float y,
      int w,
      int h,
      long revision,
      NoteDocument.Element excluded,
      boolean panning) {
    if ((page.infinite && factor <= .35f
            || panning
            || overview.ready()
            || page.strokes.isEmpty() && page.elements.isEmpty())
        && stroke == null
        && excluded == null
        && dirtyBounds == null) {
      if (paper != null) release();
      overview.draw(canvas, page, factor, x, y, w, h, revision, panning);
      return;
    }
    overview.release();
    float density =
        Math.min(
            1f, Math.min(2560f / Math.max(w, h), (float) Math.sqrt(4_000_000d / ((double) w * h))));
    int rw = Math.max(1, Math.round(w * density)), rh = Math.max(1, Math.round(h * density));
    boolean resized = paper == null || paper.getWidth() != rw || paper.getHeight() != rh;
    boolean moved =
        resized || scale != factor || left != x || top != y || width != w || height != h;
    if (resized) {
      release();
      paper = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
      ink = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
      staticBase = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
    }
    if (moved || cachedPage != page || generation != revision) {
      if (visibleIndex == null || cachedPage != page || generation != revision)
        visibleIndex = new StrokeSpatialIndex(page.strokes);
      drawStaticBase(staticBase, page, density, factor, x, y, excluded, this);
      Canvas rasterCanvas = new Canvas(paper);
      restoreBase(rasterCanvas, staticBase);
      rasterCanvas.scale(density, density);
      rasterCanvas.translate(x, y);
      rasterCanvas.scale(factor, factor);
      if (!page.infinite) rasterCanvas.clipRect(0, 0, page.width, page.height);
      Iterable<NoteDocument.Stroke> visible =
          visibleIndex.queryInPaintOrder(
              -x / factor, -y / factor, (w - x) / factor, (h - y) / factor);
      for (NoteDocument.Stroke stored : visible) PageRenderer.drawInk(rasterCanvas, stored);
      cachedPage = page;
      generation = revision;
    } else if (dirtyBounds != null) {
      redrawRegion(
          new Canvas(paper),
          staticBase,
          page,
          dirtyIndex,
          dirtyBounds,
          density,
          factor,
          x,
          y,
          rw,
          rh);
    }
    dirtyBounds = null;
    dirtyIndex = null;
    scale = factor;
    left = x;
    top = y;
    width = w;
    height = h;
    raster = density;
    destination.set(0, 0, w, h);
    canvas.drawBitmap(paper, null, destination, paint);
    if (stroke == null) {
      active = null;
      return;
    }
    if (InkGeometry.isShape(stroke.tool) || stroke.points.size() < 2) {
      int save = canvas.save();
      canvas.translate(x, y);
      canvas.scale(factor, factor);
      if (!page.infinite) canvas.clipRect(0, 0, page.width, page.height);
      PageRenderer.drawInk(canvas, stroke);
      canvas.restoreToCount(save);
      return;
    }
    if (active != stroke || moved || nextSegment >= stroke.points.size()) {
      ink.eraseColor(Color.TRANSPARENT);
      inkCanvas = new Canvas(ink);
      inkCanvas.scale(raster, raster);
      inkCanvas.translate(x, y);
      inkCanvas.scale(factor, factor);
      if (!page.infinite) inkCanvas.clipRect(0, 0, page.width, page.height);
      active = stroke;
      nextSegment = 1;
    }
    boolean marker = "highlighter".equals(stroke.tool);
    while (nextSegment < stroke.points.size() - 1) {
      PageRenderer.drawInkSegment(inkCanvas, stroke, nextSegment, true, marker);
      nextSegment++;
    }
    int layer =
        marker ? canvas.saveLayerAlpha(0, 0, w, h, BrushStyle.markerAlpha(stroke.color)) : -1;
    canvas.drawBitmap(ink, null, destination, paint);
    int save = canvas.save();
    canvas.translate(x, y);
    canvas.scale(factor, factor);
    if (!page.infinite) canvas.clipRect(0, 0, page.width, page.height);
    PageRenderer.drawInkSegment(canvas, stroke, stroke.points.size() - 1, false, marker);
    canvas.restoreToCount(save);
    if (marker) canvas.restoreToCount(layer);
  }

  void release() {
    overview.release();
    PageRenderer.releaseBackground(this);
    if (paper != null) paper.recycle();
    if (ink != null) ink.recycle();
    if (staticBase != null) staticBase.recycle();
    paper = ink = staticBase = null;
    inkCanvas = null;
    cachedPage = null;
    active = null;
    generation = -1;
    nextSegment = 1;
    dirtyBounds = null;
    dirtyIndex = null;
    visibleIndex = null;
  }

  /** Redraw complete intersecting strokes in their original compositing order and resolution. */
  static void drawStaticBase(
      Bitmap base, NoteDocument.Page page, float density, float factor, float x, float y) {
    drawStaticBase(base, page, density, factor, x, y, null);
  }

  static void drawStaticBase(
      Bitmap base,
      NoteDocument.Page page,
      float density,
      float factor,
      float x,
      float y,
      NoteDocument.Element excluded) {
    drawStaticBase(base, page, density, factor, x, y, excluded, page);
  }

  private static void drawStaticBase(
      Bitmap base,
      NoteDocument.Page page,
      float density,
      float factor,
      float x,
      float y,
      NoteDocument.Element excluded,
      Object viewportOwner) {
    base.eraseColor(Color.TRANSPARENT);
    Canvas canvas = new Canvas(base);
    canvas.scale(density, density);
    canvas.translate(x, y);
    canvas.scale(factor, factor);
    if (!page.infinite) canvas.clipRect(0, 0, page.width, page.height);
    PageRenderer.drawBackground(canvas, page, true, viewportOwner);
    PageRenderer.drawElements(canvas, page, excluded, viewportOwner);
  }

  static void restoreBase(Canvas canvas, Bitmap base) {
    Paint copy = new Paint();
    copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
    canvas.drawBitmap(base, 0, 0, copy);
  }

  static void redrawRegion(
      Canvas rasterCanvas,
      Bitmap base,
      NoteDocument.Page page,
      StrokeSpatialIndex index,
      RectF bounds,
      float density,
      float factor,
      float x,
      float y,
      int width,
      int height) {
    float transform = density * factor;
    // Align the clip with raster pixel boundaries. The two-pixel guard preserves antialiasing at
    // the edge of a stroke/shape without changing the resolution or curve tolerance.
    int left = Math.max(0, (int) Math.floor(density * x + bounds.left * transform) - 2);
    int top = Math.max(0, (int) Math.floor(density * y + bounds.top * transform) - 2);
    int right = Math.min(width, (int) Math.ceil(density * x + bounds.right * transform) + 2);
    int bottom = Math.min(height, (int) Math.ceil(density * y + bounds.bottom * transform) + 2);
    if (left >= right || top >= bottom) return;
    Set<NoteDocument.Stroke> visible = new HashSet<>();
    boolean expanded;
    do {
      expanded = false;
      for (NoteDocument.Stroke stroke :
          index.query(
              (left - density * x) / transform,
              (top - density * y) / transform,
              (right - density * x) / transform,
              (bottom - density * y) / transform,
              0)) {
        if (!visible.add(stroke)) continue;
        float[] b = index.getBounds(stroke);
        float padding = stroke.width + 2;
        int l = Math.max(0, (int) Math.floor(density * x + (b[0] - padding) * transform) - 2);
        int t = Math.max(0, (int) Math.floor(density * y + (b[1] - padding) * transform) - 2);
        int r = Math.min(width, (int) Math.ceil(density * x + (b[2] + padding) * transform) + 2);
        int bottomEdge =
            Math.min(height, (int) Math.ceil(density * y + (b[3] + padding) * transform) + 2);
        if (l < left || t < top || r > right || bottomEdge > bottom) {
          left = Math.min(left, l);
          top = Math.min(top, t);
          right = Math.max(right, r);
          bottom = Math.max(bottom, bottomEdge);
          expanded = true;
        }
      }
    } while (expanded);
    int save = rasterCanvas.save();
    try {
      rasterCanvas.clipRect(left, top, right, bottom);
      restoreBase(rasterCanvas, base);
      rasterCanvas.scale(density, density);
      rasterCanvas.translate(x, y);
      rasterCanvas.scale(factor, factor);
      if (!page.infinite) rasterCanvas.clipRect(0, 0, page.width, page.height);
      // Grid traversal order is unrelated to painter order, particularly for transparent markers.
      for (NoteDocument.Stroke stroke : page.strokes)
        if (visible.contains(stroke)) PageRenderer.drawInk(rasterCanvas, stroke);
    } finally {
      rasterCanvas.restoreToCount(save);
    }
  }
}
