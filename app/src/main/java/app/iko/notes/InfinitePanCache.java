package app.iko.notes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Reuse a bounded, overscanned viewport while panning at the same zoom. */
final class InfinitePanCache {
  private Bitmap bitmap;
  private NoteDocument.Page page;
  private long revision;
  private float zoom, originX, originY, density;
  private int viewWidth, viewHeight, padding;
  private StrokeSpatialIndex index;
  private RectF region;
  private RectF contentBounds;
  private boolean wholeContent;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

  void draw(
      Canvas target,
      NoteDocument.Page source,
      float factor,
      float x,
      float y,
      int width,
      int height,
      long generation) {
    draw(target,source,factor,x,y,width,height,generation,false);
  }

  void draw(Canvas target,NoteDocument.Page source,float factor,float x,float y,
      int width,int height,long generation,boolean interacting) {
    boolean changed = page != source || revision != generation;
    int save = target.save();
    target.translate(x, y);
    target.scale(factor, factor);
    if (!source.infinite) target.clipRect(0, 0, source.width, source.height);
    PageRenderer.drawBackground(target, source, true, this);
    target.restoreToCount(save);
    if (source.strokes.isEmpty() && source.elements.isEmpty()) {
      if (bitmap != null) {
        bitmap.recycle();
        bitmap = null;
      }
      page = source;
      revision = generation;
      return;
    }
    int pixelLimit = 4_000_000;
    if (changed) {
      index = new StrokeSpatialIndex(source.strokes);
      contentBounds = source.infinite ? InfiniteCanvas.contentBounds(source)
          : new RectF(0, 0, source.width, source.height);
    }
    // A gesture transforms the same overscanned raster. Rebuild at the exact
    // zoom once fingers lift, or sooner if the visible region/quality bound changes.
    boolean reusableZoom = zoom == factor || interacting && zoom > 0
        && factor / zoom >= .7f && factor / zoom <= 1.5f;
    float visibleLeft = -x / factor, visibleTop = -y / factor,
        visibleRight = (width - x) / factor, visibleBottom = (height - y) / factor;
    if (!source.infinite) {
      visibleLeft = Math.max(0, visibleLeft); visibleTop = Math.max(0, visibleTop);
      visibleRight = Math.min(source.width, visibleRight); visibleBottom = Math.min(source.height, visibleBottom);
    }
    boolean regionCovered = region != null && (wholeContent ||
        region.left <= visibleLeft && region.top <= visibleTop &&
        region.right >= visibleRight && region.bottom >= visibleBottom);
    boolean covered =
        bitmap != null
            && !changed
            && reusableZoom
            && viewWidth == width
            && viewHeight == height
            && regionCovered;
    if (!covered) {
      padding = Math.min(384, Math.max(160, Math.min(width, height) / 2));
      int wide = width + padding * 2, high = height + padding * 2;
      RectF content = contentBounds;
      wholeContent =
          content.width() * factor <= 4096
              && content.height() * factor <= 4096
              && content.width() * factor * content.height() * factor <= pixelLimit;
      region =
          wholeContent
              ? content
              : new RectF(
                  (-x - padding) / factor,
                  (-y - padding) / factor,
                  (width - x + padding) / factor,
                  (height - y + padding) / factor);
      wide = Math.max(1, (int) Math.ceil(region.width() * factor));
      high = Math.max(1, (int) Math.ceil(region.height() * factor));
      density = Math.min(1, (float) Math.sqrt((double) pixelLimit / ((double) wide * high)));
      int rw = Math.max(1, Math.round(wide * density)),
          rh = Math.max(1, Math.round(high * density));
      if (bitmap == null || bitmap.getWidth() != rw || bitmap.getHeight() != rh) {
        if (bitmap != null) bitmap.recycle();
        bitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
      }
      bitmap.eraseColor(android.graphics.Color.TRANSPARENT);
      Canvas canvas = new Canvas(bitmap);
      canvas.scale(density * factor, density * factor);
      canvas.translate(-region.left, -region.top);
      if (!source.infinite) canvas.clipRect(0, 0, source.width, source.height);
      PageRenderer.drawElements(canvas, source, null, this);
      for (NoteDocument.Stroke stroke :
          index.queryInPaintOrder(region.left, region.top, region.right, region.bottom))
        PageRenderer.drawInk(canvas, stroke);
      page = source;
      revision = generation;
      zoom = factor;
      originX = x;
      originY = y;
      viewWidth = width;
      viewHeight = height;
    }
    target.drawBitmap(
        bitmap,
        null,
        new RectF(
            x + region.left * factor,
            y + region.top * factor,
            x + region.right * factor,
            y + region.bottom * factor),
        paint);
  }

  boolean ready() {
    return page != null;
  }

  void release() {
    if (bitmap != null) bitmap.recycle();
    bitmap = null;
    page = null;
    index = null;
    contentBounds = null;
    PageRenderer.releaseBackground(this);
  }
}
