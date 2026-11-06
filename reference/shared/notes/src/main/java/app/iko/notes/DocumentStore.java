package app.iko.notes;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.json.JSONException;
import org.json.JSONObject;

/** Caller schedules disk operations off the UI thread. AtomicFile retains the last good commit. */
public final class DocumentStore {
  private final File directory;
  private final File indexDirectory;
  public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
  static final int MAX_UNCOMPRESSED_BYTES = 128 * 1024 * 1024;
  private final ArrayList<String> warnings = new ArrayList<>();

  public DocumentStore(Context context) {
    directory = new File(context.getFilesDir(), "notes");
    indexDirectory = new File(context.getCacheDir(), "library-index");
  }

  /** Fast shelf scan; full documents are read only to rebuild missing or outdated cache entries. */
  synchronized List<LibraryEntry> listEntries() throws IOException {
    ensureDirectory();
    warnings.clear();
    File[] files =
        directory.listFiles((dir, name) -> name.endsWith(".json") || name.endsWith(".json.bak"));
    if (files == null) throw new IOException("无法读取笔记目录");
    ArrayList<LibraryEntry> entries = new ArrayList<>();
    HashSet<String> seen = new HashSet<>();
    for (File file : files) {
      String name = file.getName();
      String id = name.substring(0, name.length() - (name.endsWith(".bak") ? 9 : 5));
      if (!seen.add(id)) continue;
      try {
        File source = path(id);
        LibraryEntry entry = null;
        if (!new File(source.getPath() + ".bak").exists()) {
          try (FileInputStream in =
              new AtomicFile(new File(indexDirectory, id + ".json")).openRead()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
              if (buffer.size() + read > 256 * 1024) throw new IOException("书库索引超过限制");
              buffer.write(chunk, 0, read);
            }
            byte[] bytes = buffer.toByteArray();
            {
              LibraryEntry cached =
                  LibraryEntry.read(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
              if (id.equals(cached.id)
                  && source.isFile()
                  && cached.fileBytes == source.length()
                  && cached.fileModified == source.lastModified()) entry = cached;
            }
          } catch (IOException | JSONException ignored) {
            /* Rebuild this disposable index from its original. */
          }
        }
        if (entry == null) {
          NoteDocument note = load(id);
          entry = LibraryEntry.from(note, source.lastModified(), source.length());
          cacheEntry(entry);
        }
        entries.add(entry);
      } catch (IOException e) {
        warnings.add(name + "：" + e.getMessage());
      }
    }
    entries.sort(Comparator.comparingLong((LibraryEntry e) -> e.modifiedAt).reversed());
    return entries;
  }

  private void cacheEntry(LibraryEntry entry) {
    AtomicFile file = new AtomicFile(new File(indexDirectory, entry.id + ".json"));
    FileOutputStream out = null;
    try {
      if (!indexDirectory.isDirectory() && !indexDirectory.mkdirs()) return;
      byte[] json = entry.json().toString().getBytes(StandardCharsets.UTF_8);
      out = file.startWrite();
      out.write(json);
      file.finishWrite(out);
    } catch (IOException | JSONException e) {
      if (out != null) file.failWrite(out);
      // Metadata is a rebuildable cache; a successful original save must remain successful.
    }
  }

  public synchronized List<NoteDocument> list() throws IOException {
    ensureDirectory();
    warnings.clear();
    File[] files =
        directory.listFiles((dir, name) -> name.endsWith(".json") || name.endsWith(".json.bak"));
    if (files == null) throw new IOException("无法读取笔记目录");
    ArrayList<NoteDocument> notes = new ArrayList<>();
    HashSet<String> loaded = new HashSet<>();
    for (File file : files) {
      String name = file.getName(),
          id = name.substring(0, name.length() - (name.endsWith(".bak") ? 9 : 5));
      if (!loaded.add(id)) continue;
      try {
        notes.add(load(id));
      } catch (IOException ex) {
        warnings.add(name + "：" + ex.getMessage());
      }
    }
    notes.sort(Comparator.comparingLong((NoteDocument n) -> n.modifiedAt).reversed());
    return notes;
  }

  /** The library can remain usable while surfacing files that need recovery to the user. */
  public synchronized List<String> getWarnings() {
    return new ArrayList<>(warnings);
  }

  public synchronized NoteDocument load(String id) throws IOException {
    AtomicFile file = new AtomicFile(path(id));
    try (FileInputStream input = file.openRead();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count, total = 0;
      while ((count = input.read(buffer)) != -1) {
        total += count;
        if (total > MAX_FILE_BYTES) throw new IOException("笔记超过 32 MB，无法载入");
        out.write(buffer, 0, count);
      }
      byte[] encoded = decodeStorage(out.toByteArray());
      NoteDocument note =
          NoteDocument.fromJson(new JSONObject(new String(encoded, StandardCharsets.UTF_8)));
      if (!id.equals(note.id)) throw new IOException("笔记 ID 与文件不匹配");
      return note;
    } catch (JSONException | RuntimeException ex) {
      throw new IOException("笔记格式损坏：" + id, ex);
    }
  }

  public synchronized void save(NoteDocument note) throws IOException {
    ensureDirectory();
    if(note.trashed && note.trashedAt<=0)note.trashedAt=System.currentTimeMillis();
    if(!note.trashed)note.trashedAt=0;
    byte[] content;
    try {
      content = note.toJson().toString().getBytes(StandardCharsets.UTF_8);
    } catch (JSONException | RuntimeException ex) {
      throw new IOException("无法编码笔记", ex);
    }
    byte[] compressed = encodeStorage(content);
    AtomicFile atomic = new AtomicFile(path(note.id));
    FileOutputStream output = null;
    try {
      output = atomic.startWrite();
      output.write(compressed);
      atomic.finishWrite(output);
    } catch (IOException | RuntimeException ex) {
      if (output != null) atomic.failWrite(output);
      throw new IOException("笔记保存失败，上一版本仍保留", ex);
    }
    File source = path(note.id);
    cacheEntry(LibraryEntry.from(note, source.lastModified(), source.length()));
  }

  static byte[] encodeStorage(byte[] json) throws IOException {
    if (json == null || json.length > MAX_UNCOMPRESSED_BYTES)
      throw new IOException("笔记原始数据超过 128 MB，无法保存");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(json.length, MAX_FILE_BYTES));
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(json);
    }
    byte[] encoded = bytes.toByteArray();
    if (encoded.length > MAX_FILE_BYTES) throw new IOException("压缩后笔记仍超过 32 MB，无法保存");
    return encoded;
  }

  static byte[] decodeStorage(byte[] stored) throws IOException {
    if (stored == null) throw new IOException("笔记文件为空");
    boolean gzip = stored.length >= 2 && (stored[0] & 255) == 0x1f && (stored[1] & 255) == 0x8b;
    if (!gzip) {
      if (stored.length > MAX_FILE_BYTES) throw new IOException("旧版笔记超过 32 MB，无法载入");
      return stored;
    }
    try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(stored));
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (read > MAX_UNCOMPRESSED_BYTES - output.size())
          throw new IOException("解压后笔记超过 128 MB，无法载入");
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  public synchronized long fileSize(String id) throws IOException {
    File file = path(id);
    return file.isFile() ? file.length() : 0;
  }
  synchronized void delete(String id) throws IOException {
    File source=path(id);new AtomicFile(source).delete();
    if(source.exists() || new File(source+".bak").exists())throw new IOException("无法删除笔记文件");
    new AtomicFile(new File(indexDirectory,id+".json")).delete();
  }

  private File path(String id) throws IOException {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) throw new IOException("笔记 ID 无效");
    return new File(directory, id + ".json");
  }

  private void ensureDirectory() throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法建立笔记目录");
  }
}
