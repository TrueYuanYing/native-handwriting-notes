package app.iko.notes;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Local archive reader. Generated asset names never use untrusted ZIP paths. */
final class NotebookArchive implements AutoCloseable {
  final File directory, source, mediaDirectory;
  final ZipFile zip;
  final List<String> names = new ArrayList<>();
  private final Map<String, File> extracted = new HashMap<>();

  NotebookArchive(File source, File mediaDirectory) throws IOException {
    this.source = source;
    directory = source.getParentFile();
    this.mediaDirectory = mediaDirectory;
    if (!mediaDirectory.isDirectory() && !mediaDirectory.mkdirs())
      throw new IOException("无法创建资源目录");
    zip = new ZipFile(source);
    try {
      long total = 0;
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry e = entries.nextElement();
        if (e.isDirectory()) continue;
        if (e.getSize() < 0 || e.getSize() > 128L * 1024 * 1024 || names.size() >= 10000)
          throw new IOException("压缩包条目超过导入限制");
        total += e.getSize();
        if (total > 512L * 1024 * 1024) throw new IOException("解压后文件超过 512 MB");
        names.add(e.getName());
      }
    } catch (IOException | RuntimeException error) {
      zip.close();
      throw error;
    }
  }

  static File retain(Context context, Uri uri, String name) throws IOException {
    File dir = new File(context.getFilesDir(), "imports/" + UUID.randomUUID());
    if (!dir.mkdirs()) throw new IOException("无法创建导入目录");
    String suffix =
        name.contains(".")
            ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT)
            : ".bin";
    if (!suffix.matches("\\.[a-z0-9]{1,12}")) suffix = ".bin";
    File file = new File(dir, "source" + suffix);
    try (InputStream in = context.getContentResolver().openInputStream(uri);
        OutputStream out = new FileOutputStream(file)) {
      copy(in, out, 256L * 1024 * 1024);
    }
    return file;
  }

  byte[] read(String name) throws IOException {
    ZipEntry e = zip.getEntry(name);
    if (e == null) throw new IOException("文件缺少条目：" + name);
    try (InputStream in = zip.getInputStream(e);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      copy(in, out, 128L * 1024 * 1024);
      return out.toByteArray();
    }
  }

  boolean has(String name) {
    return zip.getEntry(name) != null;
  }

  JSONObject json(String name) throws IOException, JSONException {
    return new JSONObject(new String(read(name), StandardCharsets.UTF_8));
  }

  File asset(String name, String suffix) throws IOException {
    if (extracted.containsKey(name)) return extracted.get(name);
    File file = new File(mediaDirectory, UUID.randomUUID() + suffix);
    ZipEntry e = zip.getEntry(name);
    if (e == null) throw new IOException("缺少原始资源：" + name);
    try (InputStream in = zip.getInputStream(e);
        OutputStream out = new FileOutputStream(file)) {
      copy(in, out, 128L * 1024 * 1024);
    }
    extracted.put(name, file);
    return file;
  }

  static void copy(InputStream in, OutputStream out, long limit) throws IOException {
    if (in == null) throw new IOException("无法打开源文件");
    byte[] b = new byte[16384];
    long size = 0;
    int n;
    while ((n = in.read(b)) != -1) {
      size += n;
      if (size > limit) throw new IOException("文件超过导入大小限制");
      out.write(b, 0, n);
    }
  }

  @Override
  public void close() throws IOException {
    zip.close();
  }
}
