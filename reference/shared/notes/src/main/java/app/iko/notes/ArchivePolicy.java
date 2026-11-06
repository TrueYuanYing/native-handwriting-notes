package app.iko.notes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Limits the portable document format to a manifest and flat, bounded assets. */
public final class ArchivePolicy {
  /** Version-1 backups stored the full JSON note in this manifest entry. */
  public static final long MAX_MANIFEST_BYTES = 16L * 1024 * 1024;
  public static final long MAX_ASSET_BYTES = 128L * 1024 * 1024;
  public static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
  public static final int MAX_ENTRIES = 1024;

  private ArchivePolicy() {}

  public static boolean isSafeEntry(String name) {
    return "manifest.json".equals(name)
        || "document.json.gz".equals(name)
        || (name != null && name.matches("assets/[A-Za-z0-9_-]{1,80}\\.[A-Za-z0-9]{1,8}"));
  }

  public static long copyBounded(InputStream source, OutputStream target, long limit)
      throws IOException {
    byte[] buffer = new byte[32 * 1024];
    long total = 0;
    int count;
    while ((count = source.read(buffer)) != -1) {
      total += count;
      if (total > limit) throw new IOException("文件超过允许大小，未导入");
      target.write(buffer, 0, count);
    }
    return total;
  }
}
