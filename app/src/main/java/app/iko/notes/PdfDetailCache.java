package app.iko.notes;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Source PDF/image tiles at visible density. No source file is opened by a UI draw. */
final class PdfDetailCache {
  private static final int TILE = 512, BORDER = 2;
  private static final int LIMIT_BYTES = 32 * 1024 * 1024;
  private static final int MAX_PENDING = 64, MAX_OWNERS = 12;
  private static final double DENSITY_STEP = Math.pow(2, 1d / 8);
  private static final int VIEWPORT_TILES =
      LIMIT_BYTES / ((TILE + BORDER * 2) * (TILE + BORDER * 2) * 4);
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final LinkedHashMap<Key, Tile> tiles = new LinkedHashMap<>(32, .75f, true);
  private final LinkedHashSet<Key> pending = new LinkedHashSet<>();
  private final LinkedHashSet<Key> failed = new LinkedHashSet<>();
  private final WeakHashMap<Object, Set<Key>> viewports = new WeakHashMap<>();
  private final WeakHashMap<Object, Double> viewportWeights = new WeakHashMap<>();
  private final WeakHashMap<Object, WeakHashMap<NoteDocument.Element, Object>> imageOwners =
      new WeakHashMap<>();
  private final Map<Source, Set<Watch>> watches = new LinkedHashMap<>();
  private int bytes;
  private boolean draining;

  private static final class Source {
    final String path;
    final int page;
    final float width, height;

    Source(NoteDocument.Page page) {
      this(page.backgroundPath, page.pdfPage, page.width, page.height);
    }

    Source(String path, int page, float width, float height) {
      this.path = path;
      this.page = page;
      this.width = width;
      this.height = height;
    }

    boolean sameFile(Source source) {
      return page == source.page && path.equals(source.path);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Source)) return false;
      Source source = (Source) other;
      return page == source.page
          && width == source.width
          && height == source.height
          && path.equals(source.path);
    }

    @Override
    public int hashCode() {
      int hash = 31 * path.hashCode() + page;
      return 31 * (31 * hash + Float.floatToIntBits(width)) + Float.floatToIntBits(height);
    }
  }

  private static final class Key {
    final Source source;
    final float density;
    final int x, y;

    Key(Source source, float density, int x, int y) {
      this.source = source;
      this.density = density;
      this.x = x;
      this.y = y;
    }

    int left() {
      return Math.max(0, x * TILE - BORDER);
    }

    int top() {
      return Math.max(0, y * TILE - BORDER);
    }

    int right() {
      return Math.min((int) Math.ceil(source.width * density), (x + 1) * TILE + BORDER);
    }

    int bottom() {
      return Math.min((int) Math.ceil(source.height * density), (y + 1) * TILE + BORDER);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Key)) return false;
      Key key = (Key) other;
      return x == key.x && y == key.y && density == key.density && source.equals(key.source);
    }

    @Override
    public int hashCode() {
      return 31 * (31 * (31 * source.hashCode() + Float.floatToIntBits(density)) + x) + y;
    }
  }

  private static final class Tile {
    final Bitmap bitmap;
    final RectF bounds;

    Tile(Bitmap bitmap, Key key) {
      this.bitmap = bitmap;
      bounds =
          new RectF(
              key.left() / key.density,
              key.top() / key.density,
              key.right() / key.density,
              key.bottom() / key.density);
    }
  }

  private final class Watch implements PageRenderer.MediaRequest {
    final Source source;
    final Runnable ready;
    boolean cancelled;

    Watch(Source source, Runnable ready) {
      this.source = source;
      this.ready = ready;
    }

    @Override
    public void cancel() {
      synchronized (PdfDetailCache.this) {
        if (cancelled) return;
        cancelled = true;
        Set<Watch> group = watches.get(source);
        if (group == null) return;
        group.remove(this);
        if (!group.isEmpty()) return;
        watches.remove(source);
        boolean stillObserved = false;
        for (Source other : watches.keySet())
          if (source.sameFile(other)) {
            stillObserved = true;
            break;
          }
        if (!stillObserved)
          for (Iterator<Map.Entry<Object, Set<Key>>> it = viewports.entrySet().iterator();
              it.hasNext(); ) {
            Map.Entry<Object, Set<Key>> entry = it.next();
            if (!entry.getValue().isEmpty()
                && entry.getValue().iterator().next().source.sameFile(source)) {
              viewportWeights.remove(entry.getKey());
              it.remove();
            }
          }
        prunePending();
      }
    }

    void deliver() {
      synchronized (PdfDetailCache.this) {
        if (cancelled) return;
      }
      try {
        if (ready != null) ready.run();
      } catch (RuntimeException ignored) {
      }
    }
  }

  PageRenderer.MediaRequest subscribe(NoteDocument.Page page, Runnable ready) {
    if (page.backgroundPath == null || page.backgroundPath.isEmpty()) return () -> {};
    return subscribe(new Source(page), ready);
  }

  PageRenderer.MediaRequest subscribeImage(NoteDocument.Element image, Runnable ready) {
    return subscribe(new Source(image.path, -1, image.width, image.height), ready);
  }

  private PageRenderer.MediaRequest subscribe(Source source, Runnable ready) {
    Watch watch = new Watch(source, ready);
    synchronized (this) {
      watches.computeIfAbsent(watch.source, ignored -> new LinkedHashSet<>()).add(watch);
      failed.removeIf(key -> key.source.equals(watch.source));
    }
    return watch;
  }

  void release(Object owner) {
    synchronized (this) {
      viewports.remove(owner);
      viewportWeights.remove(owner);
      Map<NoteDocument.Element, Object> children = imageOwners.remove(owner);
      if (children != null)
        for (Object child : children.values()) {
          viewports.remove(child);
          viewportWeights.remove(child);
        }
      prunePending();
    }
  }

  void draw(Canvas canvas, NoteDocument.Page page, Object owner, float previewDensity) {
    draw(canvas, new Source(page), owner, previewDensity);
  }

  void drawImage(
      Canvas canvas, NoteDocument.Element image, Object viewportOwner, float previewDensity) {
    Object owner;
    synchronized (this) {
      owner =
          imageOwners
              .computeIfAbsent(viewportOwner, key -> new WeakHashMap<>())
              .computeIfAbsent(image, key -> new Object());
    }
    draw(canvas, new Source(image.path, -1, image.width, image.height), owner, previewDensity);
  }

  void retainImages(Object owner, Set<NoteDocument.Element> visible) {
    synchronized (this) {
      Map<NoteDocument.Element, Object> children = imageOwners.get(owner);
      if (children == null) return;
      for (Iterator<Map.Entry<NoteDocument.Element, Object>> it = children.entrySet().iterator();
          it.hasNext(); ) {
        Map.Entry<NoteDocument.Element, Object> entry = it.next();
        if (!visible.contains(entry.getKey())) {
          viewports.remove(entry.getValue());
          viewportWeights.remove(entry.getValue());
          it.remove();
        }
      }
      if (children.isEmpty()) imageOwners.remove(owner);
      prunePending();
    }
  }

  private void draw(Canvas canvas, Source source, Object owner, float previewDensity) {
    List<Key> keys = visibleKeys(canvas, source, owner, previewDensity);
    if (keys.isEmpty()) {
      synchronized (this) {
        viewports.remove(owner);
        viewportWeights.remove(owner);
        prunePending();
      }
      return;
    }
    if (Looper.myLooper() != Looper.getMainLooper()) {
      // Export/capture workers have only one draw; they must not export an unfinished refinement.
      drawBlocking(canvas, keys);
      return;
    }
    boolean start;
    ArrayList<Tile> available = new ArrayList<>();
    synchronized (this) {
      if (!viewports.containsKey(owner) && viewports.size() >= MAX_OWNERS) {
        Iterator<Object> oldest = viewports.keySet().iterator();
        if (oldest.hasNext()) {
          Object evicted = oldest.next();
          viewports.remove(evicted);
          viewportWeights.remove(evicted);
        }
      }
      viewports.put(owner, new HashSet<>(keys));
      prunePending();
      // Keep usable detail through zoom changes and scaled in-app captures until the new level
      // arrives.
      RectF clip = new RectF(canvas.getClipBounds());
      for (Map.Entry<Key, Tile> entry : tiles.entrySet()) {
        Key key = entry.getKey();
        Tile tile = entry.getValue();
        if (key.source.equals(source)
            && key.density != keys.get(0).density
            && RectF.intersects(tile.bounds, clip)) available.add(tile);
      }
      for (Key key : keys) {
        Tile tile = tiles.get(key);
        if (tile != null) available.add(tile);
        else if (!failed.contains(key) && pending.size() < MAX_PENDING) pending.add(key);
      }
      start = !draining && !pending.isEmpty();
      if (start) draining = true;
    }
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    if (source.page < 0) paint.setBlendMode(android.graphics.BlendMode.SRC);
    for (Tile tile : available) canvas.drawBitmap(tile.bitmap, null, tile.bounds, paint);
    if (start) worker.execute(this::drain);
  }

  private List<Key> visibleKeys(Canvas canvas, Source source, Object owner, float previewDensity) {
    ArrayList<Key> keys = new ArrayList<>();
    Matrix matrix = new Matrix();
    canvas.getMatrix(matrix);
    float[] values = new float[9];
    matrix.getValues(values);
    float density =
        (float) Math.max(Math.hypot(values[0], values[3]), Math.hypot(values[1], values[4]));
    if (!Float.isFinite(density) || density <= previewDensity * 1.1f) return keys;
    Rect clip = canvas.getClipBounds();
    RectF visible = new RectF(clip);
    if (!visible.intersect(0, 0, source.width, source.height)) return keys;
    // Small buckets avoid almost doubling one screen's pixel count at a zoom boundary.
    density = (float) Math.pow(2, Math.ceil(Math.log(density) / Math.log(2) * 8) / 8);
    int budget = VIEWPORT_TILES;
    if (Looper.myLooper() == Looper.getMainLooper())
      synchronized (this) {
        double weight = tileCount(visible, density), total = weight;
        for (Map.Entry<Object, Double> entry : viewportWeights.entrySet())
          if (entry.getKey() != owner) total += entry.getValue();
        viewportWeights.put(owner, weight);
        budget = Math.max(1, (int) Math.floor(VIEWPORT_TILES * weight / total));
      }
    // A stationary viewport must fit its actual cache, including tile borders, or it reloads
    // forever.
    while (tileCount(visible, density) > budget) density /= DENSITY_STEP;
    int left = Math.max(0, (int) Math.floor(visible.left * density / TILE));
    int top = Math.max(0, (int) Math.floor(visible.top * density / TILE));
    int right = Math.max(left, (int) Math.ceil(visible.right * density / TILE) - 1);
    int bottom = Math.max(top, (int) Math.ceil(visible.bottom * density / TILE) - 1);
    for (int y = top; y <= bottom; y++)
      for (int x = left; x <= right; x++) keys.add(new Key(source, density, x, y));
    return keys;
  }

  private static double tileCount(RectF bounds, float density) {
    return (Math.ceil(bounds.right * density / TILE) - Math.floor(bounds.left * density / TILE))
        * (Math.ceil(bounds.bottom * density / TILE) - Math.floor(bounds.top * density / TILE));
  }

  private boolean wanted(Key key) {
    for (Set<Key> keys : viewports.values()) if (keys.contains(key)) return true;
    return false;
  }

  private void prunePending() {
    pending.removeIf(key -> !wanted(key));
  }

  private synchronized Key take(Source source) {
    Iterator<Key> iterator = pending.iterator();
    while (iterator.hasNext()) {
      Key key = iterator.next();
      if (!wanted(key) || tiles.containsKey(key)) {
        iterator.remove();
        continue;
      }
      if (source != null && !source.equals(key.source)) return null;
      iterator.remove();
      return key;
    }
    return null;
  }

  private void drain() {
    while (true) {
      Key key;
      synchronized (this) {
        key = take(null);
        if (key == null) {
          draining = false;
          return;
        }
      }
      Source source = key.source;
      try (Session session = new Session(source)) {
        do {
          Tile tile = null;
          try {
            tile = session.render(key);
          } catch (RuntimeException | OutOfMemoryError ignored) {
          }
          finish(key, tile);
          key = take(source);
        } while (key != null);
      } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
        if (key != null) finish(key, null);
      }
    }
  }

  private void finish(Key key, Tile tile) {
    ArrayList<Watch> notify = new ArrayList<>();
    synchronized (this) {
      if (!wanted(key)) {
        if (tile != null) tile.bitmap.recycle();
        return;
      }
      if (tile == null) {
        failed.add(key);
        while (failed.size() > MAX_PENDING) failed.remove(failed.iterator().next());
        return;
      }
      Tile previous = tiles.put(key, tile);
      if (previous != null) bytes -= previous.bitmap.getAllocationByteCount();
      bytes += tile.bitmap.getAllocationByteCount();
      while (bytes > LIMIT_BYTES) {
        Key oldest = tiles.keySet().iterator().next();
        bytes -= tiles.remove(oldest).bitmap.getAllocationByteCount();
      }
      // Evicted bitmaps are left to GC: an already submitted hardware draw may still read them.
      for (Map.Entry<Source, Set<Watch>> group : watches.entrySet())
        if (group.getKey().sameFile(key.source)) notify.addAll(group.getValue());
    }
    for (Watch watch : notify) watch.deliver();
  }

  private void drawBlocking(Canvas canvas, List<Key> keys) {
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    if (keys.get(0).source.page < 0) paint.setBlendMode(android.graphics.BlendMode.SRC);
    try (Session session = new Session(keys.get(0).source)) {
      for (Key key : keys) {
        Tile tile;
        synchronized (this) {
          tile = tiles.get(key);
        }
        if (tile == null) tile = session.render(key);
        canvas.drawBitmap(tile.bitmap, null, tile.bounds, paint);
      }
    } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
      // The already painted full-page preview remains available on malformed/missing sources.
    }
  }

  private static final class Session implements AutoCloseable {
    final ParcelFileDescriptor descriptor;
    final PdfRenderer renderer;
    final PdfRenderer.Page page;
    final BitmapRegionDecoder image;

    Session(Source source) throws IOException {
      if (source.page < 0) {
        descriptor = null;
        renderer = null;
        page = null;
        image = BitmapRegionDecoder.newInstance(source.path, false);
        if (image == null) throw new IOException("图片无法读取");
        return;
      }
      image = null;
      descriptor =
          ParcelFileDescriptor.open(new File(source.path), ParcelFileDescriptor.MODE_READ_ONLY);
      PdfRenderer opened = null;
      try {
        opened = new PdfRenderer(descriptor);
        page = opened.openPage(source.page);
        renderer = opened;
      } catch (IOException | RuntimeException | OutOfMemoryError error) {
        if (opened != null) opened.close();
        descriptor.close();
        throw error;
      }
    }

    Tile render(Key key) {
      if (image != null) return renderImage(key);
      Bitmap bitmap =
          Bitmap.createBitmap(
              key.right() - key.left(), key.bottom() - key.top(), Bitmap.Config.ARGB_8888);
      try {
        bitmap.eraseColor(Color.WHITE);
        Matrix transform = new Matrix();
        transform.setValues(
            new float[] {
              key.density * key.source.width / page.getWidth(),
              0,
              -key.left(),
              0,
              key.density * key.source.height / page.getHeight(),
              -key.top(),
              0,
              0,
              1
            });
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return new Tile(bitmap, key);
      } catch (RuntimeException | OutOfMemoryError error) {
        bitmap.recycle();
        throw error;
      }
    }

    private Tile renderImage(Key key) {
      float sx = image.getWidth() / key.source.width, sy = image.getHeight() / key.source.height;
      Rect region =
          new Rect(
              Math.max(0, (int) Math.floor(key.left() / key.density * sx)),
              Math.max(0, (int) Math.floor(key.top() / key.density * sy)),
              Math.min(image.getWidth(), (int) Math.ceil(key.right() / key.density * sx)),
              Math.min(image.getHeight(), (int) Math.ceil(key.bottom() / key.density * sy)));
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = 1;
      while (options.inSampleSize * 2 <= Math.min(sx, sy) / key.density) options.inSampleSize *= 2;
      Bitmap decoded = image.decodeRegion(region, options);
      if (decoded == null) throw new IllegalStateException("图片区域无法读取");
      Bitmap bitmap = null;
      try {
        bitmap =
            Bitmap.createBitmap(
                key.right() - key.left(), key.bottom() - key.top(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(
            decoded,
            null,
            new RectF(
                region.left / sx * key.density - key.left(),
                region.top / sy * key.density - key.top(),
                region.right / sx * key.density - key.left(),
                region.bottom / sy * key.density - key.top()),
            new Paint(Paint.FILTER_BITMAP_FLAG));
        return new Tile(bitmap, key);
      } catch (RuntimeException | OutOfMemoryError error) {
        if (bitmap != null) bitmap.recycle();
        throw error;
      } finally {
        decoded.recycle();
      }
    }

    @Override
    public void close() throws IOException {
      if (image != null) {
        image.recycle();
        return;
      }
      try {
        page.close();
      } finally {
        try {
          renderer.close();
        } finally {
          descriptor.close();
        }
      }
    }
  }

  synchronized int byteCount() {
    return bytes;
  }

  synchronized int queuedCount() {
    return pending.size();
  }
}
