package app.iko.notes;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Framework-only, read-only provider. Exposes direct files in internal cache/share only. */
public final class ShareFileProvider extends ContentProvider {
  static final long MAX_BYTES = 1024L * 1024 * 1024;

  static File checkedFile(File cache, File file) throws IOException {
    File share = new File(cache, "share").getAbsoluteFile();
    File root = share.getCanonicalFile();
    File canonical = file.getCanonicalFile();
    // Flat directory and canonical equality reject traversal and symbolic links.
    if (!new File(cache.getCanonicalFile(), "share").equals(root)
        || !share.equals(file.getAbsoluteFile().getParentFile())
        || java.nio.file.Files.isSymbolicLink(file.toPath())
        || !root.equals(canonical.getParentFile())
        || !canonical.isFile()
        || canonical.length() <= 0
        || canonical.length() > MAX_BYTES) {
      throw new IOException("分享文件不可用或超出 cache/share 边界（最大 1 GB）");
    }
    return canonical;
  }

  public static Uri uriForFile(Context context, File file) throws IOException {
    File checked = checkedFile(context.getCacheDir(), file);
    return new Uri.Builder()
        .scheme("content")
        .authority(context.getPackageName() + ".share")
        .appendPath("share")
        .appendPath(checked.getName())
        .build();
  }

  private File resolve(Uri uri) throws FileNotFoundException {
    try {
      List<String> parts = uri.getPathSegments();
      if (!"content".equals(uri.getScheme())
          || !(getContext().getPackageName() + ".share").equals(uri.getAuthority())
          || parts.size() != 2
          || !"share".equals(parts.get(0))
          || parts.get(1).contains("/")
          || parts.get(1).contains("\\")
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IOException("Invalid share URI");
      }
      return checkedFile(
          getContext().getCacheDir(),
          new File(new File(getContext().getCacheDir(), "share"), parts.get(1)));
    } catch (IOException | IllegalArgumentException e) {
      throw new FileNotFoundException(e.getMessage());
    }
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Read-only share");
    return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public Cursor query(
      Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    try {
      File file = resolve(uri);
      String[] columns =
          projection == null
              ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
              : projection;
      MatrixCursor cursor = new MatrixCursor(columns, 1);
      Object[] row = new Object[columns.length];
      for (int i = 0; i < columns.length; i++) {
        if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
        else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
      }
      cursor.addRow(row);
      return cursor;
    } catch (FileNotFoundException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  @Override
  public String getType(Uri uri) {
    try {
      String name = resolve(uri).getName().toLowerCase(Locale.ROOT);
      if (name.endsWith(".pdf")) return "application/pdf";
      if (name.endsWith(".png")) return "image/png";
      if (name.endsWith(".webm")) return "audio/webm";
      if (name.endsWith(".m4a")) return "audio/mp4";
      return "application/octet-stream";
    } catch (FileNotFoundException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException("Read-only share");
  }

  @Override
  public int update(Uri uri, ContentValues values, String where, String[] args) {
    throw new UnsupportedOperationException("Read-only share");
  }

  @Override
  public int delete(Uri uri, String where, String[] args) {
    throw new UnsupportedOperationException("Read-only share");
  }
}
