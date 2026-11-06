package app.iko.notes;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

/** SAF-independent document import/export; caller owns and closes exported streams. */
public final class MediaIo {
  private MediaIo() {}

  public static NoteDocument importPdf(Context context, Uri uri) throws IOException {
    return importPdf(context, uri, false);
  }

  static NoteDocument importPdfCanvas(Context context, Uri uri) throws IOException {
    return importPdf(context, uri, true);
  }

  private static NoteDocument importPdf(Context context, Uri uri, boolean canvas)
      throws IOException {
    File local = copyFromUri(context, uri, "pdf", ArchivePolicy.MAX_ASSET_BYTES);
    try (ParcelFileDescriptor descriptor =
            ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(descriptor)) {
      int count = renderer.getPageCount();
      if (count < 1 || count > NoteDocument.MAX_PAGES)
        throw new IOException("支持导入 1–" + NoteDocument.MAX_PAGES + " 页的 PDF");
      NoteDocument document = NoteDocument.create(displayName(context, uri, "PDF 笔记"));
      document.pages.clear();
      for (int index = 0; index < count; index++) {
        try (PdfRenderer.Page source = renderer.openPage(index)) {
          NoteDocument.Page page = NoteDocument.newPage();
          page.paper = "blank";
          if (canvas) {
            float ratio = (float) source.getHeight() / source.getWidth();
            float preferredWidth = Math.max(100, Math.min(1400, 20000 / ratio));
            page.width = Math.min(20000, Math.max(100 / ratio, preferredWidth));
            page.height = Math.min(20000, page.width * ratio);
            page.infinite = true;
          } else {
            page.width = 1400;
            page.height =
                Math.max(200, Math.min(12000, 1400f * source.getHeight() / source.getWidth()));
          }
          page.backgroundPath = local.getAbsolutePath();
          page.pdfPage = index;
          document.pages.add(page);
        }
      }
      return document;
    } catch (Exception error) {
      local.delete();
      throw ioError("无法读取 PDF；请检查文件是否损坏或需要密码", error);
    }
  }

  public static String importImage(Context context, Uri uri) throws IOException {
    File local = copyFromUri(context, uri, "img", 32L * 1024 * 1024);
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(local.getAbsolutePath(), options);
    if (options.outWidth <= 0
        || options.outHeight <= 0
        || options.outWidth > 30000
        || options.outHeight > 30000) {
      local.delete();
      throw new IOException("无法读取图片，请选择 PNG、JPEG 或 WebP 图片");
    }
    int sample = 1;
    while (Math.max(options.outWidth, options.outHeight) / sample > 3072) sample *= 2;
    BitmapFactory.Options decode = new BitmapFactory.Options();
    decode.inSampleSize = sample;
    Bitmap bitmap = BitmapFactory.decodeFile(local.getAbsolutePath(), decode);
    if (bitmap == null) {
      local.delete();
      throw new IOException("图片解码失败，请重新选择文件");
    }
    File optimized =
        new File(mediaDirectory(context), "image-" + java.util.UUID.randomUUID() + ".webp");
    boolean complete = false;
    try (FileOutputStream output = new FileOutputStream(optimized)) {
      Bitmap.CompressFormat format =
          android.os.Build.VERSION.SDK_INT >= 30
              ? Bitmap.CompressFormat.WEBP_LOSSLESS
              : Bitmap.CompressFormat.WEBP;
      if (!bitmap.compress(format, 92, output)) throw new IOException("图片压缩失败");
      output.getFD().sync();
      complete = true;
      return optimized.getAbsolutePath();
    } finally {
      bitmap.recycle();
      local.delete();
      if (!complete) optimized.delete();
    }
  }

  public static void exportPdf(NoteDocument document, OutputStream output) throws IOException {
    if (output == null) throw new IOException("无法打开导出位置");
    for (NoteDocument.Page source : document.pages)
      if (source.pdfPage >= 0
          && source.backgroundPath != null
          && !source.backgroundPath.isEmpty()) {
        VectorPdfExport.write(document, output);
        return;
      }
    PdfDocument pdf = new PdfDocument();
    try {
      for (int index = 0; index < document.pages.size(); index++) {
        NoteDocument.Page source = document.pages.get(index);
        android.graphics.RectF extent =
            source.infinite
                ? InfiniteCanvas.contentBounds(source)
                : new android.graphics.RectF(0, 0, source.width, source.height);
        // Document units are 0.15 mm: 1400 x 1980 is a physical A4 sheet.
        float pointsPerUnit = 0.15f * 72f / 25.4f;
        float scale = Math.min(pointsPerUnit, 4096f / Math.max(extent.width(), extent.height()));
        int width = Math.max(1, Math.round(extent.width() * scale));
        int height = Math.max(1, Math.round(extent.height() * scale));
        PdfDocument.Page page =
            pdf.startPage(new PdfDocument.PageInfo.Builder(width, height, index + 1).create());
        Canvas canvas = page.getCanvas();
        canvas.save();
        canvas.scale(scale, scale);
        canvas.translate(-extent.left, -extent.top);
        canvas.clipRect(extent);
        PageRenderer.draw(canvas, source, true);
        canvas.restore();
        pdf.finishPage(page);
      }
      pdf.writeTo(output);
      output.flush();
    } catch (RuntimeException error) {
      throw ioError("PDF 导出失败", error);
    } finally {
      pdf.close();
    }
  }

  public static void exportPng(NoteDocument.Page page, OutputStream output) throws IOException {
    if (output == null) throw new IOException("无法打开导出位置");
    android.graphics.RectF extent =
        page.infinite
            ? InfiniteCanvas.contentBounds(page)
            : new android.graphics.RectF(0, 0, page.width, page.height);
    float scale = Math.min(1.5f, 2560f / Math.max(extent.width(), extent.height()));
    Bitmap bitmap =
        Bitmap.createBitmap(
            Math.max(1, Math.round(extent.width() * scale)),
            Math.max(1, Math.round(extent.height() * scale)),
            Bitmap.Config.ARGB_8888);
    try {
      bitmap.eraseColor(Color.WHITE);
      Canvas canvas = new Canvas(bitmap);
      canvas.scale(scale, scale);
      canvas.translate(-extent.left, -extent.top);
      PageRenderer.draw(canvas, page, true);
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        throw new IOException("PNG 编码失败");
      output.flush();
    } catch (RuntimeException error) {
      throw ioError("图片导出失败", error);
    } finally {
      bitmap.recycle();
    }
  }

  public static void exportBackup(Context context, NoteDocument original, OutputStream output)
      throws IOException {
    if (output == null) throw new IOException("无法打开导出位置");
    try {
      NoteDocument document = original.copy();
      LinkedHashMap<String, File> assets = new LinkedHashMap<>();
      HashMap<String, String> paths = new HashMap<>();
      File mediaRoot = new File(context.getFilesDir(), "media").getCanonicalFile();
      if (hasPath(document.coverImagePath))
        document.coverImagePath = addAsset(mediaRoot, document.coverImagePath, paths, assets);
      for (NoteDocument.Page page : document.pages) {
        if (hasPath(page.backgroundPath))
          page.backgroundPath = addAsset(mediaRoot, page.backgroundPath, paths, assets);
        for (NoteDocument.Element element : page.elements) {
          if ("image".equals(element.kind) && hasPath(element.path)) {
            element.path = addAsset(mediaRoot, element.path, paths, assets);
          }
        }
      }
      for (NoteDocument.Recording recording : document.recordings)
        recording.path = addAsset(mediaRoot, recording.path, paths, assets);
      JSONObject manifest =
          new JSONObject()
              .put("format", "iko.notes.backup")
              .put("version", 2)
              .put("documentEntry", "document.json.gz");
      byte[] metadata = manifest.toString().getBytes(StandardCharsets.UTF_8);
      if (metadata.length > ArchivePolicy.MAX_MANIFEST_BYTES)
        throw new IOException("笔记备份清单过大，无法备份");
      byte[] documentJson = document.toJson().toString().getBytes(StandardCharsets.UTF_8);
      byte[] compressedDocument = DocumentStore.encodeStorage(documentJson);
      long total = metadata.length + compressedDocument.length;
      for (File file : assets.values()) {
        if (file.length() > ArchivePolicy.MAX_ASSET_BYTES)
          throw new IOException("附件超过 128 MB，无法备份");
        total += file.length();
      }
      if (total > ArchivePolicy.MAX_ARCHIVE_BYTES) throw new IOException("备份附件总量超过 256 MB");
      // Close the ZIP, but not the SAF output owned by the caller.
      ZipOutputStream zip =
          new ZipOutputStream(
              new java.io.FilterOutputStream(output) {
                @Override
                public void close() throws IOException {
                  flush();
                }
              });
      try (zip) {
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(metadata);
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("document.json.gz"));
        zip.write(compressedDocument);
        zip.closeEntry();
        for (Map.Entry<String, File> asset : assets.entrySet()) {
          zip.putNextEntry(new ZipEntry(asset.getKey()));
          try (InputStream input = new FileInputStream(asset.getValue())) {
            ArchivePolicy.copyBounded(input, zip, ArchivePolicy.MAX_ASSET_BYTES);
          }
          zip.closeEntry();
        }
        zip.finish();
      }
    } catch (Exception error) {
      throw ioError("备份导出失败", error);
    }
  }

  /** Recognize a renamed backup by its bounded manifest, never by arbitrary ZIP contents. */
  static boolean isBackup(Context context, Uri uri) throws IOException {
    try (InputStream input = context.getContentResolver().openInputStream(uri)) {
      if (input == null) return false;
      try (ZipInputStream zip = new ZipInputStream(input)) {
        long read = 0;
        for (int i = 0; i < 8; i++) {
          ZipEntry entry = zip.getNextEntry();
          if (entry == null) return false;
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          read += ArchivePolicy.copyBounded(zip, bytes, 1024 * 1024 - read);
          if ("manifest.json".equals(entry.getName()))
            return "iko.notes.backup"
                .equals(
                    new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()))
                        .optString("format"));
        }
      }
    } catch (java.util.zip.ZipException | org.json.JSONException notBackup) {
      return false;
    }
    return false;
  }

  public static NoteDocument importBackup(Context context, Uri uri) throws IOException {
    File staging = new File(context.getCacheDir(), "restore-" + UUID.randomUUID());
    if (!staging.mkdirs()) throw new IOException("无法创建临时恢复目录");
    List<File> created = new ArrayList<>();
    boolean complete = false;
    try {
      InputStream content = context.getContentResolver().openInputStream(uri);
      if (content == null) throw new IOException("无法打开备份文件");
      HashMap<String, File> stagedAssets = new HashMap<>();
      Set<String> entries = new HashSet<>();
      long total = 0;
      try (ZipInputStream zip = new ZipInputStream(content)) {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          String name = entry.getName();
          if (entry.isDirectory() || !ArchivePolicy.isSafeEntry(name) || !entries.add(name)) {
            throw new IOException("备份包含无效或重复文件");
          }
          if (entries.size() > ArchivePolicy.MAX_ENTRIES) throw new IOException("备份文件数量过多");
          File destination = new File(staging, name);
          File parent = destination.getParentFile();
          if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法写入恢复目录");
          long limit =
              "manifest.json".equals(name)
                  ? ArchivePolicy.MAX_MANIFEST_BYTES
                  : "document.json.gz".equals(name)
                      ? DocumentStore.MAX_FILE_BYTES
                      : ArchivePolicy.MAX_ASSET_BYTES;
          limit = Math.min(limit, ArchivePolicy.MAX_ARCHIVE_BYTES - total);
          try (OutputStream stream = new FileOutputStream(destination)) {
            total += ArchivePolicy.copyBounded(zip, stream, limit);
          }
          if (!"manifest.json".equals(name)) stagedAssets.put(name, destination);
          zip.closeEntry();
        }
      }
      File manifestFile = new File(staging, "manifest.json");
      if (!manifestFile.isFile()) throw new IOException("备份缺少 manifest.json");
      JSONObject manifest;
      try (InputStream input = new FileInputStream(manifestFile);
          ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
        ArchivePolicy.copyBounded(input, bytes, ArchivePolicy.MAX_MANIFEST_BYTES);
        manifest = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
      }
      if (!"iko.notes.backup".equals(manifest.optString("format"))) {
        throw new IOException("不支持此备份格式或版本");
      }
      JSONObject documentJson;
      int version = manifest.optInt("version");
      if (version == 1) {
        documentJson = manifest.optJSONObject("document");
        if (documentJson == null) throw new IOException("旧版备份缺少笔记数据");
      } else if (version == 2) {
        String documentEntry = manifest.optString("documentEntry");
        File storedDocument = stagedAssets.get(documentEntry);
        if (!"document.json.gz".equals(documentEntry) || storedDocument == null)
          throw new IOException("备份缺少压缩笔记数据");
        byte[] compressed;
        try (InputStream input = new FileInputStream(storedDocument);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
          ArchivePolicy.copyBounded(input, bytes, DocumentStore.MAX_FILE_BYTES);
          compressed = bytes.toByteArray();
        }
        byte[] expanded = DocumentStore.decodeStorage(compressed);
        documentJson = new JSONObject(new String(expanded, StandardCharsets.UTF_8));
      } else {
        throw new IOException("不支持此备份格式或版本");
      }
      NoteDocument document = NoteDocument.fromJson(documentJson);
      Map<String, String> restored = new HashMap<>();
      if (hasPath(document.coverImagePath))
        document.coverImagePath =
            restoreAsset(context, document.coverImagePath, stagedAssets, restored, created);
      for (NoteDocument.Page page : document.pages) {
        if (hasPath(page.backgroundPath))
          page.backgroundPath =
              restoreAsset(context, page.backgroundPath, stagedAssets, restored, created);
        for (NoteDocument.Element element : page.elements) {
          if (hasPath(element.path)) {
            if (!"image".equals(element.kind)) throw new IOException("备份附件类型不受支持");
            element.path = restoreAsset(context, element.path, stagedAssets, restored, created);
          }
        }
      }
      document.id = UUID.randomUUID().toString();
      for (NoteDocument.Recording recording : document.recordings)
        recording.path = restoreAsset(context, recording.path, stagedAssets, restored, created);
      document.trashed = false;
      document.modifiedAt = System.currentTimeMillis();
      complete = true;
      return document;
    } catch (Exception error) {
      throw ioError("备份恢复失败", error);
    } finally {
      deleteTemporaryTree(staging);
      if (!complete) for (File file : created) file.delete();
    }
  }

  private static String addAsset(
      File mediaRoot, String path, Map<String, String> paths, LinkedHashMap<String, File> assets)
      throws IOException {
    File file = new File(path).getCanonicalFile();
    if (!file.getPath().startsWith(mediaRoot.getPath() + File.separator) || !file.isFile()) {
      throw new IOException("笔记引用了缺失或不属于本应用的附件");
    }
    if (paths.containsKey(file.getPath())) return paths.get(file.getPath());
    if (assets.size() >= ArchivePolicy.MAX_ENTRIES - 2) throw new IOException("附件数量过多");
    String extension =
        file.getName().endsWith(".pdf") ? "pdf" : file.getName().endsWith(".m4a") ? "m4a" : "img";
    String entry =
        "assets/" + String.format(java.util.Locale.ROOT, "%04d", assets.size()) + "." + extension;
    paths.put(file.getPath(), entry);
    assets.put(entry, file);
    return entry;
  }

  private static String restoreAsset(
      Context context,
      String path,
      Map<String, File> assets,
      Map<String, String> restored,
      List<File> created)
      throws IOException {
    if (!ArchivePolicy.isSafeEntry(path)
        || !path.startsWith("assets/")
        || !assets.containsKey(path)) {
      throw new IOException("备份附件缺失或路径无效");
    }
    if (restored.containsKey(path)) return restored.get(path);
    File root = mediaDirectory(context);
    File file =
        new File(
            root,
            UUID.randomUUID()
                + (path.endsWith(".pdf") ? ".pdf" : path.endsWith(".m4a") ? ".m4a" : ".img"));
    created.add(file);
    try (InputStream input = new FileInputStream(assets.get(path));
        OutputStream output = new FileOutputStream(file)) {
      ArchivePolicy.copyBounded(input, output, ArchivePolicy.MAX_ASSET_BYTES);
    }
    restored.put(path, file.getAbsolutePath());
    return file.getAbsolutePath();
  }

  private static File copyFromUri(Context context, Uri uri, String extension, long limit)
      throws IOException {
    File destination = new File(mediaDirectory(context), UUID.randomUUID() + "." + extension);
    boolean complete = false;
    try {
      InputStream source = context.getContentResolver().openInputStream(uri);
      if (source == null) throw new IOException("无法打开所选文件");
      try (source;
          FileOutputStream output = new FileOutputStream(destination)) {
        long size = ArchivePolicy.copyBounded(source, output, limit);
        if (size == 0) throw new IOException("所选文件为空");
        output.getFD().sync();
      }
      complete = true;
      return destination;
    } finally {
      if (!complete) destination.delete();
    }
  }

  private static File mediaDirectory(Context context) throws IOException {
    File directory = new File(context.getFilesDir(), "media");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建本地附件目录");
    return directory;
  }

  private static String displayName(Context context, Uri uri, String fallback) {
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        String name = cursor.getString(0);
        if (name != null && !name.trim().isEmpty()) return name.replaceFirst("(?i)\\.pdf$", "");
      }
    } catch (Exception ignored) {
      /* Providers may not expose display names. */
    }
    return fallback;
  }

  private static boolean hasPath(String path) {
    return path != null && !path.trim().isEmpty();
  }

  private static IOException ioError(String message, Exception error) {
    if (error instanceof IOException) return (IOException) error;
    return new IOException(message, error);
  }

  private static void deleteTemporaryTree(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTemporaryTree(child);
    file.delete();
  }
}
