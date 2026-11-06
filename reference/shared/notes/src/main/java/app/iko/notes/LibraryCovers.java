package app.iko.notes;

import android.graphics.*;
import android.os.*;
import android.util.AtomicFile;
import android.util.LruCache;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Visible shelf covers: deduplicated background work, bounded memory and disposable disk cache. */
final class LibraryCovers implements AutoCloseable {
  interface Loader {
    NoteDocument load(String id) throws IOException;
  }

  interface Request {
    void cancel();
  }

  static final class Images {
    final String version;
    final Bitmap cover, preview;

    Images(String version, Bitmap cover, Bitmap preview) {
      this.version = version;
      this.cover = cover;
      this.preview = preview;
    }

    int bytes() {
      return (cover == null ? 0 : cover.getAllocationByteCount())
          + (preview == null ? 0 : preview.getAllocationByteCount());
    }
  }

  private static final class Listener {
    final Consumer<Images> callback;
    boolean canceled;

    Listener(Consumer<Images> callback) {
      this.callback = callback;
    }
  }

  private static final class Job {
    final ArrayList<Listener> listeners = new ArrayList<>();
    volatile boolean canceled;
    volatile Future<?> future;
    volatile PageRenderer.MediaRequest media;

    void cancel() {
      canceled = true;
      PageRenderer.MediaRequest request = media;
      if (request != null) request.cancel();
      Future<?> task = future;
      if (task != null) task.cancel(true);
    }
  }

  private static final class Failure {
    final int attempts;
    final long retryAt;

    Failure(int attempts) {
      this.attempts = attempts;
      retryAt = SystemClock.uptimeMillis() + Math.min(16000, 500L << Math.min(5, attempts));
    }
  }

  private final File directory;
  private final Loader loader;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Map<String, Job> jobs = new HashMap<>();
  private final LruCache<String, Failure> failures = new LruCache<>(32);
  private final LruCache<String, Images> cache =
      new LruCache<String, Images>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Images value) {
          return Math.max(1, value.bytes());
        }
      };
  private boolean closed;

  LibraryCovers(File directory, Loader loader) {
    this.directory = directory;
    this.loader = loader;
  }

  Images peek(String id) {
    return cache.get(id);
  }

  Request request(LibraryEntry entry, Consumer<Images> ready) {
    if (closed) return () -> {};
    Images cached = cache.get(entry.id);
    if (cached != null && cached.version.equals(entry.version())) {
      ready.accept(cached);
      return () -> {};
    }
    String key = entry.version();
    Job job = jobs.get(key);
    boolean start = job == null;
    if (start) {
      job = new Job();
      jobs.put(key, job);
    }
    Job pending = job;
    Listener listener = new Listener(ready);
    pending.listeners.add(listener);
    if (start) {
      Failure failure = failures.get(key);
      long delay = failure == null ? 0 : Math.max(0, failure.retryAt - SystemClock.uptimeMillis());
      main.postDelayed(
          () -> {
            if (closed || pending.canceled) return;
            pending.future =
                worker.submit(
                    () -> {
                      if (pending.canceled) return;
                      Images images;
                      try {
                        images = load(entry, pending);
                      } catch (IOException | RuntimeException e) {
                        images = null;
                      }
                      Images result = images;
                      main.post(
                          () -> {
                            if (closed) return;
                            if (jobs.get(key) == pending) jobs.remove(key);
                            if (!pending.canceled) {
                              if (result != null) {
                                cache.put(entry.id, result);
                                failures.remove(key);
                              } else {
                                Failure previous = failures.get(key);
                                failures.put(
                                    key, new Failure(previous == null ? 1 : previous.attempts + 1));
                              }
                              for (Listener l : pending.listeners)
                                if (!l.canceled) l.callback.accept(result);
                            }
                          });
                    });
          },
          delay);
    }
    return () -> {
      listener.canceled = true;
      boolean any = false;
      for (Listener l : pending.listeners) if (!l.canceled) any = true;
      if (!any) {
        pending.cancel();
        if (jobs.get(key) == pending) jobs.remove(key);
      }
    };
  }

  private Images load(LibraryEntry entry, Job job) throws IOException {
    if (!directory.isDirectory()) directory.mkdirs();
    String key = entry.version();
    File photoFile = new File(directory, key + "-cover.webp"),
        pageFile = new File(directory, key + (entry.infinite ? "-canvas2.webp" : "-page.webp"));
    Bitmap photo =
        entry.coverImagePath.isEmpty() || !photoFile.isFile()
            ? null
            : BitmapFactory.decodeFile(photoFile.getPath());
    Bitmap preview =
        entry.hasPreview && pageFile.isFile() ? BitmapFactory.decodeFile(pageFile.getPath()) : null;
    if (job.canceled) return null;
    if (photo == null && !entry.coverImagePath.isEmpty()) {
      photo = decodeCover(entry.coverImagePath);
      if (photo == null) throw new IOException("原图片封面暂时无法读取");
      if (photo != null) cacheImage(photoFile, photo);
    }
    if (preview == null && entry.hasPreview) {
      NoteDocument note = loader.load(entry.id);
      if (job.canceled) return null;
      NoteDocument.Page page = note.pages.get(0);
      CountDownLatch ready = new CountDownLatch(1);
      PageRenderer.MediaRequest media = PageRenderer.prepareCancelable(page, ready::countDown);
      job.media = media;
      try {
        if (job.canceled) return null;
        if (!ready.await(10, TimeUnit.SECONDS)) throw new IOException("封面预览资料尚未就绪");
        if (PageRenderer.preparationFailed(page)) throw new IOException("封面预览资料暂时无法读取");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      } finally {
        media.cancel();
        job.media = null;
      }
      if (job.canceled) return null;
      RectF bounds =
          page.infinite ? canvasBounds(page, entry.id) : new RectF(0, 0, page.width, page.height);
      float scale =
          page.infinite
              ? 480 / bounds.width()
              : Math.min(180 / bounds.width(), 255 / bounds.height());
      preview =
          Bitmap.createBitmap(
              Math.max(1, Math.round(bounds.width() * scale)),
              Math.max(1, Math.round(bounds.height() * scale)),
              Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(preview);
      canvas.scale(scale, scale);
      canvas.translate(-bounds.left, -bounds.top);
      if (page.infinite) {
        // The bitmap clips this fixed window; full-content bounds must not change its paper.
        PageRenderer.drawBackground(canvas, page, true);
        PageRenderer.drawForeground(canvas, page);
      } else PageRenderer.draw(canvas, page, true);
      cacheImage(pageFile, preview);
    }
    trimDisk();
    return new Images(key, photo, preview);
  }

  private RectF canvasBounds(NoteDocument.Page page, String id) {
    AtomicFile focus = new AtomicFile(new File(directory, id + "-" + page.id + "-focus"));
    float x = 0, y = 0;
    try (DataInputStream input = new DataInputStream(focus.openRead())) {
      x = input.readFloat();
      y = input.readFloat();
      if (Float.isFinite(x)
          && Float.isFinite(y)
          && Math.abs(x) <= 1_000_000
          && Math.abs(y) <= 1_000_000) return new RectF(x - 120, y - 160, x + 1080, y + 640);
    } catch (IOException ignored) {
      // This tiny, disposable focus record is independent of notebook versions.
    }
    x = 0;
    y = 0;
    boolean found = false;
    for (NoteDocument.Stroke stroke : page.strokes) {
      if (stroke.points.isEmpty()) continue;
      x = stroke.points.get(0).x;
      y = stroke.points.get(0).y;
      found = true;
      break;
    }
    if (!found && !page.elements.isEmpty()) {
      ObjectTransform.Bounds bounds = ObjectTransform.bounds(page.elements.get(0));
      x = bounds.left;
      y = bounds.top;
    }
    FileOutputStream output = null;
    try {
      output = focus.startWrite();
      DataOutputStream data = new DataOutputStream(output);
      data.writeFloat(x);
      data.writeFloat(y);
      data.flush();
      focus.finishWrite(output);
    } catch (IOException ignored) {
      if (output != null) focus.failWrite(output);
    }
    return new RectF(x - 120, y - 160, x + 1080, y + 640);
  }

  private static Bitmap decodeCover(String path) {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(path, options);
    int largest = Math.max(options.outWidth, options.outHeight);
    options.inJustDecodeBounds = false;
    options.inSampleSize = 1;
    while (largest / options.inSampleSize > 1440) options.inSampleSize *= 2;
    Bitmap image = BitmapFactory.decodeFile(path, options);
    if (image == null) return null;
    int size = Math.max(image.getWidth(), image.getHeight());
    if (size <= 720) return image;
    Bitmap scaled =
        Bitmap.createScaledBitmap(
            image,
            Math.max(1, image.getWidth() * 720 / size),
            Math.max(1, image.getHeight() * 720 / size),
            true);
    if (scaled != image) image.recycle();
    return scaled;
  }

  private static void save(File destination, Bitmap image) throws IOException {
    AtomicFile file = new AtomicFile(destination);
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      Bitmap.CompressFormat format =
          Build.VERSION.SDK_INT >= 30
              ? Bitmap.CompressFormat.WEBP_LOSSLESS
              : Bitmap.CompressFormat.PNG;
      if (!image.compress(format, 100, out)) throw new IOException("封面缓存编码失败");
      file.finishWrite(out);
    } catch (IOException e) {
      if (out != null) file.failWrite(out);
      throw e;
    }
  }

  private static void cacheImage(File destination, Bitmap image) {
    try {
      save(destination, image);
    } catch (IOException ignored) {
      // Display remains available when this disposable disk cache cannot be written.
    }
  }

  private void trimDisk() {
    File[] files = directory.listFiles((dir, name) -> name.endsWith(".webp"));
    if (files == null) return;
    long bytes = 0;
    for (File file : files) bytes += file.length();
    if (bytes <= 32L * 1024 * 1024) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    for (File file : files) {
      if (bytes <= 24L * 1024 * 1024) break;
      long size = file.length();
      if (file.delete()) bytes -= size;
    }
  }

  @Override
  public void close() {
    closed = true;
    main.removeCallbacksAndMessages(null);
    for (Job job : jobs.values()) job.cancel();
    jobs.clear();
    cache.evictAll();
    worker.shutdownNow();
  }
}
