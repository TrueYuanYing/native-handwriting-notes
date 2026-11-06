package app.iko.notes;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Private, reusable originals. Disk/network/render operations belong on a background thread. */
public final class TemplateLibrary {
  public static final long MAX_BYTES = 32L * 1024 * 1024;

  private TemplateLibrary() {}

  public static final class Entry {
    public final String id, title, author, source, terms, termsUrl, description, category;
    public final String mimeType, fileName, sha256, downloadUrl;
    public final int pageCount, previewPage;
    public final boolean imported;
    public final long bytes;

    private Entry(JSONObject data, boolean imported) throws IOException {
      this.imported = imported;
      id = data.optString("id");
      fileName = data.optString("fileName");
      sha256 = data.optString("sha256");
      if (!id.matches("[a-zA-Z0-9_-]{1,100}")
          || !fileName.matches("[a-zA-Z0-9_.-]{1,120}")
          || fileName.equals(".")
          || fileName.equals("..")
          || !sha256.matches("[a-f0-9]{64}")) throw new IOException("模板目录信息无效");
      title = data.optString("title", "我的模板");
      author = data.optString("author", "用户导入");
      source = data.optString("source");
      terms = data.optString("terms");
      termsUrl = data.optString("termsUrl");
      description = data.optString("description");
      category = imported ? "我的模板" : data.optString("category", "手账");
      mimeType = data.optString("mimeType");
      downloadUrl = imported ? "" : data.optString("downloadUrl");
      pageCount = data.optInt("pageCount", 1);
      previewPage = Math.max(0, Math.min(pageCount - 1, data.optInt("previewPage")));
      bytes = data.optLong("bytes");
      if (pageCount < 1 || pageCount > NoteDocument.MAX_PAGES || bytes < 1 || bytes > MAX_BYTES)
        throw new IOException("模板大小或页数超出支持范围");
    }

    public boolean isPdf() {
      return "application/pdf".equals(mimeType);
    }
  }

  /** Includes sourced catalog entries even before their private-use originals are downloaded. */
  public static List<Entry> list(Context context) throws IOException {
    ArrayList<Entry> result = new ArrayList<>();
    try (InputStream input = context.getAssets().open("templates/manifest.json")) {
      JSONArray entries = new JSONObject(readText(input)).getJSONArray("entries");
      for (int i = 0; i < entries.length(); i++)
        result.add(new Entry(entries.getJSONObject(i), false));
    } catch (JSONException error) {
      throw new IOException("模板来源目录无法读取", error);
    }
    File[] metadata =
        directory(context, "imported").listFiles((dir, name) -> name.endsWith(".json"));
    if (metadata != null) {
      Arrays.sort(metadata, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
      for (File item : metadata) {
        try (InputStream input = new FileInputStream(item)) {
          Entry entry = new Entry(new JSONObject(readText(input)), true);
          if (file(context, entry).isFile()) result.add(entry);
        } catch (JSONException | IOException ignored) {
          // One interrupted or damaged personal import must not hide the rest of the library.
        }
      }
    }
    return result;
  }

  /** Returns the private original's location; isFile() tells callers whether it is available. */
  public static File file(Context context, Entry entry) throws IOException {
    return new File(directory(context, entry.imported ? "imported" : "seed"), entry.fileName);
  }

  /**
   * Downloads from the recorded original source, checks the recorded hash, then installs
   * atomically.
   */
  public static File download(Context context, Entry entry) throws IOException {
    File target = file(context, entry);
    if (target.isFile()) {
      verify(target, entry.sha256);
      return target;
    }
    if (entry.imported || entry.downloadUrl.isEmpty()) throw new IOException("请重新导入此模板");
    File temporary = File.createTempFile("download-", ".part", target.getParentFile());
    try {
      URL address = new URL(entry.downloadUrl);
      for (int redirect = 0; redirect <= 5; redirect++) {
        if (!"https".equalsIgnoreCase(address.getProtocol())) throw new IOException("仅支持 HTTPS 来源");
        HttpURLConnection connection = (HttpURLConnection) address.openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "IKO-Notes/0.7 personal-template-download");
        try {
          int code = connection.getResponseCode();
          if (code >= 300 && code < 400) {
            String location = connection.getHeaderField("Location");
            if (location == null || redirect == 5) throw new IOException("来源重定向失败，请访问原站");
            address = new URL(address, location);
            continue;
          }
          if (code != 200) throw new IOException("来源暂时无法下载（HTTP " + code + "），可访问原站");
          if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("模板超过 32 MiB");
          try (InputStream input = connection.getInputStream()) {
            copy(input, temporary);
          }
          verify(temporary, entry.sha256);
          inspect(temporary);
          install(temporary, target);
          return target;
        } finally {
          connection.disconnect();
        }
      }
      throw new IOException("来源重定向过多，请访问原站");
    } finally {
      temporary.delete();
    }
  }

  /** Copies the selected URI now. Reuse does not depend on a provider or a persisted URI grant. */
  public static Entry saveImported(Context context, Uri uri) throws IOException {
    File folder = directory(context, "imported");
    File temporary = File.createTempFile("import-", ".part", folder);
    try {
      try (InputStream input = context.getContentResolver().openInputStream(uri)) {
        if (input == null) throw new IOException("无法打开所选模板");
        copy(input, temporary);
      }
      Info info = inspect(temporary);
      String digest = hash(temporary);
      JSONObject data = new JSONObject();
      data.put("id", "user-" + digest);
      data.put("title", displayName(context, uri));
      data.put("author", "用户导入");
      data.put("terms", "自行导入的个人模板，请遵守原作者的使用条款。");
      data.put("description", info.pdf ? "PDF 内页可单独选择作为纸张。" : "图片原件已保存，可离线重复使用。");
      data.put("fileName", digest + (info.pdf ? ".pdf" : ".img"));
      data.put("mimeType", info.pdf ? "application/pdf" : "image/*");
      data.put("sha256", digest);
      data.put("pageCount", info.pages);
      data.put("bytes", temporary.length());
      Entry entry = new Entry(data, true);
      File target = file(context, entry);
      if (!target.isFile()) install(temporary, target);
      else verify(target, digest);
      File metadata = new File(folder, entry.id + ".json");
      File staged = File.createTempFile("metadata-", ".part", folder);
      try {
        try (FileOutputStream output = new FileOutputStream(staged)) {
          output.write(data.toString().getBytes(StandardCharsets.UTF_8));
          output.getFD().sync();
        }
        install(staged, metadata);
      } finally {
        staged.delete();
      }
      return entry;
    } catch (JSONException error) {
      throw new IOException("无法保存模板信息", error);
    } finally {
      temporary.delete();
    }
  }

  /** One content-addressed media copy is shared by all pages, and accepted by existing backups. */
  public static NoteDocument.Page createPage(Context context, Entry entry, int pageIndex)
      throws IOException {
    File original = checkedFile(context, entry);
    checkPage(entry, pageIndex);
    float width, height;
    if (entry.isPdf()) {
      try (ParcelFileDescriptor descriptor =
              ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY);
          PdfRenderer renderer = new PdfRenderer(descriptor);
          PdfRenderer.Page source = renderer.openPage(pageIndex)) {
        width = source.getWidth();
        height = source.getHeight();
      } catch (RuntimeException error) {
        throw new IOException("无法读取所选 PDF 内页", error);
      }
    } else {
      Info image = inspect(original);
      width = image.width;
      height = image.height;
    }
    String sharedPath = shareOriginal(context, entry, original).getAbsolutePath();
    return paper(width, height, sharedPath, entry.isPdf() ? pageIndex : -1);
  }

  private static File shareOriginal(Context context, Entry entry, File original)
      throws IOException {
    File media = new File(context.getFilesDir(), "media");
    if (!media.isDirectory() && !media.mkdirs()) throw new IOException("无法创建笔记附件目录");
    File shared = new File(media, "template-" + entry.sha256 + (entry.isPdf() ? ".pdf" : ".img"));
    synchronized (TemplateLibrary.class) {
      if (shared.isFile()) verify(shared, entry.sha256);
      else {
        File staged = File.createTempFile("template-", ".part", media);
        try {
          try (InputStream input = new FileInputStream(original)) {
            copy(input, staged);
          }
          install(staged, shared);
        } finally {
          staged.delete();
        }
      }
    }
    return shared;
  }

  private static NoteDocument.Page paper(float width, float height, String path, int pdfPage)
      throws IOException {
    if (width <= 0
        || height <= 0
        || height / width > 12000f / 200f
        || width / height > 12000f / 200f) throw new IOException("模板长宽比例超出纸张支持范围");
    NoteDocument.Page page = NoteDocument.newPage();
    page.paper = "blank";
    page.texture = "smooth";
    // Preserve aspect ratio and keep both dimensions inside the existing page validator's limits.
    float scale = Math.min(1400f / width, 12000f / Math.max(width, height));
    if (Math.min(width, height) * scale < 200) scale = 200f / Math.min(width, height);
    page.width = width * scale;
    page.height = height * scale;
    page.backgroundPath = path;
    page.pdfPage = pdfPage;
    return page;
  }

  /** One renderer and one PDF copy per notebook, regardless of its page count. */
  public static NoteDocument createNotebook(Context context, Entry entry) throws IOException {
    NoteDocument note = NoteDocument.create(entry.title);
    if (!entry.isPdf()) note.pages.set(0, createPage(context, entry, 0));
    else {
      File original = checkedFile(context, entry);
      String path = shareOriginal(context, entry, original).getAbsolutePath();
      note.pages.clear();
      try (ParcelFileDescriptor descriptor =
              ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY);
          PdfRenderer renderer = new PdfRenderer(descriptor)) {
        if (renderer.getPageCount() != entry.pageCount) throw new IOException("模板页数与原作不一致");
        for (int i = 0; i < entry.pageCount; i++) {
          try (PdfRenderer.Page source = renderer.openPage(i)) {
            NoteDocument.Page page = paper(source.getWidth(), source.getHeight(), path, i);
            page.title = "第 " + (i + 1) + " 页";
            note.pages.add(page);
          }
        }
        if (android.os.Build.VERSION.SDK_INT >= 35) {
          for (int i = 0; i < entry.pageCount; i++) {
            try (PdfRenderer.Page source = renderer.openPage(i)) {
              importLinks(source, note, i);
            }
          }
        }
      } catch (RuntimeException error) {
        throw new IOException("整本 PDF 读取失败", error);
      }
    }
    File cover = new File(context.getFilesDir(), "media/template-cover-" + entry.sha256 + ".webp");
    if (!cover.isFile()) {
      Bitmap image = preview(context, entry, 0, 420);
      try (FileOutputStream output = new FileOutputStream(cover)) {
        Bitmap.CompressFormat format =
            android.os.Build.VERSION.SDK_INT >= 30
                ? Bitmap.CompressFormat.WEBP_LOSSLESS
                : Bitmap.CompressFormat.WEBP;
        if (!image.compress(format, 100, output)) throw new IOException("封面保存失败");
      } finally {
        image.recycle();
      }
    }
    note.coverImagePath = cover.getAbsolutePath();
    return note;
  }

  @android.annotation.TargetApi(35)
  private static void importLinks(PdfRenderer.Page source, NoteDocument note, int index) {
    NoteDocument.Page page = note.pages.get(index);
    float scale = page.width / source.getWidth();
    for (android.graphics.pdf.content.PdfPageGotoLinkContent content : source.getGotoLinks()) {
      int target = content.getDestination().getPageNumber();
      if (target < 0 || target >= note.pages.size()) continue;
      for (android.graphics.RectF bounds : content.getBounds()) {
        android.graphics.RectF area = new android.graphics.RectF(bounds);
        if (!area.intersect(0, 0, source.getWidth(), source.getHeight()) || area.isEmpty())
          continue;
        NoteDocument.PageLink link = new NoteDocument.PageLink();
        link.x = area.left * scale;
        link.y = area.top * scale;
        link.width = area.width() * scale;
        link.height = area.height() * scale;
        link.targetPageId = note.pages.get(target).id;
        page.links.add(link);
      }
    }
  }

  /** Renders the actual original page, never a synthetic replacement. Caller owns the bitmap. */
  public static Bitmap preview(Context context, Entry entry, int pageIndex, int maxWidth)
      throws IOException {
    File original = checkedFile(context, entry);
    checkPage(entry, pageIndex);
    int targetWidth = Math.max(60, Math.min(1024, maxWidth));
    if (entry.isPdf()) {
      try (ParcelFileDescriptor descriptor =
              ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY);
          PdfRenderer renderer = new PdfRenderer(descriptor);
          PdfRenderer.Page page = renderer.openPage(pageIndex)) {
        float scale = Math.min((float) targetWidth / page.getWidth(), 1600f / page.getHeight());
        Bitmap bitmap =
            Bitmap.createBitmap(
                Math.max(1, Math.round(page.getWidth() * scale)),
                Math.max(1, Math.round(page.getHeight() * scale)),
                Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return bitmap;
      } catch (RuntimeException error) {
        throw new IOException("PDF 预览失败，文件可能损坏或需要密码", error);
      }
    }
    BitmapFactory.Options bounds = imageBounds(original);
    BitmapFactory.Options options = new BitmapFactory.Options();
    while (Math.max(bounds.outWidth, bounds.outHeight) / Math.max(1, options.inSampleSize) > 1600)
      options.inSampleSize = Math.max(1, options.inSampleSize) * 2;
    Bitmap bitmap = BitmapFactory.decodeFile(original.getAbsolutePath(), options);
    if (bitmap == null) throw new IOException("图片预览失败");
    float scale =
        Math.min(1, Math.min((float) targetWidth / bitmap.getWidth(), 1600f / bitmap.getHeight()));
    if (scale < 1) {
      Bitmap resized =
          Bitmap.createScaledBitmap(
              bitmap,
              Math.max(1, Math.round(bitmap.getWidth() * scale)),
              Math.max(1, Math.round(bitmap.getHeight() * scale)),
              true);
      if (resized != bitmap) bitmap.recycle();
      return resized;
    }
    return bitmap;
  }

  private static File checkedFile(Context context, Entry entry) throws IOException {
    File result = file(context, entry);
    if (!result.isFile()) throw new IOException("尚未下载此模板，请先从来源下载");
    verify(result, entry.sha256);
    return result;
  }

  private static void checkPage(Entry entry, int index) throws IOException {
    if (index < 0 || index >= entry.pageCount || (!entry.isPdf() && index != 0))
      throw new IOException("模板页码无效");
  }

  private static final class Info {
    boolean pdf;
    int pages = 1, width, height;
  }

  private static Info inspect(File file) throws IOException {
    if (file.length() < 1 || file.length() > MAX_BYTES) throw new IOException("模板应为 1 byte–32 MiB");
    byte[] header = new byte[5];
    boolean pdf;
    try (InputStream input = new FileInputStream(file)) {
      pdf =
          input.read(header) == 5 && "%PDF-".equals(new String(header, StandardCharsets.US_ASCII));
    }
    Info info = new Info();
    info.pdf = pdf;
    if (pdf) {
      try (ParcelFileDescriptor descriptor =
              ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
          PdfRenderer renderer = new PdfRenderer(descriptor)) {
        info.pages = renderer.getPageCount();
        if (info.pages < 1 || info.pages > NoteDocument.MAX_PAGES)
          throw new IOException("支持 1–" + NoteDocument.MAX_PAGES + " 页 PDF 模板");
        try (PdfRenderer.Page first = renderer.openPage(0)) {
          info.width = first.getWidth();
          info.height = first.getHeight();
        }
      } catch (RuntimeException error) {
        throw new IOException("无法读取 PDF，请检查文件是否损坏或需要密码", error);
      }
    } else {
      BitmapFactory.Options bounds = imageBounds(file);
      info.width = bounds.outWidth;
      info.height = bounds.outHeight;
      BitmapFactory.Options sample = new BitmapFactory.Options();
      sample.inSampleSize = 1;
      while (Math.max(info.width, info.height) / sample.inSampleSize > 1600)
        sample.inSampleSize *= 2;
      Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), sample);
      if (decoded == null) throw new IOException("无法解码图片模板");
      decoded.recycle();
    }
    return info;
  }

  private static BitmapFactory.Options imageBounds(File file) throws IOException {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
    if (bounds.outWidth <= 0
        || bounds.outHeight <= 0
        || bounds.outWidth > 30000
        || bounds.outHeight > 30000
        || !("image/png".equals(bounds.outMimeType)
            || "image/jpeg".equals(bounds.outMimeType)
            || "image/webp".equals(bounds.outMimeType)))
      throw new IOException("请选择 PDF、PNG、JPEG 或 WebP 模板");
    return bounds;
  }

  private static File directory(Context context, String subdirectory) throws IOException {
    File directory = new File(context.getFilesDir(), "templates/" + subdirectory);
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建个人模板目录");
    return directory;
  }

  private static void copy(InputStream input, File destination) throws IOException {
    try (FileOutputStream output = new FileOutputStream(destination)) {
      if (ArchivePolicy.copyBounded(input, output, MAX_BYTES) == 0) throw new IOException("模板文件为空");
      output.getFD().sync();
    }
  }

  private static void install(File staged, File destination) throws IOException {
    try {
      Files.move(
          staged.toPath(),
          destination.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String hash(File file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = new FileInputStream(file)) {
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
      }
      StringBuilder result = new StringBuilder(64);
      for (byte value : digest.digest())
        result.append(String.format(Locale.ROOT, "%02x", value & 255));
      return result.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IOException("SHA256 不可用", impossible);
    }
  }

  private static void verify(File file, String expected) throws IOException {
    if (file.length() > MAX_BYTES || !hash(file).equals(expected))
      throw new IOException("模板校验失败，文件可能发生变化，请从原站重新导入");
  }

  private static String readText(InputStream input) throws IOException {
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    ArchivePolicy.copyBounded(input, output, 1024 * 1024);
    return output.toString(StandardCharsets.UTF_8.name());
  }

  private static String displayName(Context context, Uri uri) {
    String name = uri.getLastPathSegment();
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
    } catch (Exception ignored) {
      /* Some document providers have no display name. */
    }
    if (name == null || name.trim().isEmpty()) return "我的模板";
    return name.substring(0, Math.min(120, name.length())).replaceAll("[\\r\\n\\t]", " ");
  }
}
