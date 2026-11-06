package app.iko.notes;

import java.util.*;
import org.json.*;

/** Shelf metadata never masquerades as an editable document. */
final class LibraryEntry {
  final String id, title, folder, cover, coverPattern, coverImagePath;
  final List<String> tags;
  final int pageCount;
  final float firstWidth, firstHeight;
  final boolean trashed, infinite, hasPreview;
  final long modifiedAt, fileModified, fileBytes, trashedAt;

  private LibraryEntry(JSONObject o) throws JSONException {
    id = o.getString("id");
    title = o.getString("title");
    folder = o.getString("folder");
    cover = o.getString("cover");
    coverPattern = o.getString("coverPattern");
    coverImagePath = o.getString("coverImagePath");
    pageCount = o.getInt("pageCount");
    firstWidth = (float) o.getDouble("firstWidth");
    firstHeight = (float) o.getDouble("firstHeight");
    trashed = o.getBoolean("trashed");
    trashedAt = o.optLong("trashedAt",0);
    infinite = o.getBoolean("infinite");
    hasPreview = o.getBoolean("hasPreview");
    modifiedAt = o.getLong("modifiedAt");
    fileModified = o.getLong("fileModified");
    fileBytes = o.getLong("fileBytes");
    ArrayList<String> values = new ArrayList<>();
    JSONArray array = o.getJSONArray("tags");
    for (int n = 0; n < array.length(); n++) values.add(array.getString(n));
    tags = Collections.unmodifiableList(values);
    if (o.getInt("schema") != 1
        || pageCount < 1
        || pageCount > NoteDocument.MAX_PAGES
        || !Float.isFinite(firstWidth)
        || !Float.isFinite(firstHeight)
        || firstWidth <= 0
        || firstHeight <= 0) throw new JSONException("Invalid library metadata");
  }

  static LibraryEntry from(NoteDocument note, long fileModified, long fileBytes) {
    NoteDocument.Page page = note.pages.get(0);
    try {
      return new LibraryEntry(
          new JSONObject()
              .put("schema", 1)
              .put("id", note.id)
              .put("title", note.title)
              .put("folder", note.folder == null ? "" : note.folder)
              .put("cover", note.cover)
              .put("coverPattern", note.coverPattern)
              .put("coverImagePath", note.coverImagePath == null ? "" : note.coverImagePath)
              .put("pageCount", note.pages.size())
              .put("firstWidth", page.width)
              .put("firstHeight", page.height)
              .put("trashed", note.trashed)
              .put("trashedAt", note.trashedAt)
              .put("infinite", note.pages.size() == 1 && page.infinite)
              .put(
                  "hasPreview",
                  !page.strokes.isEmpty()
                      || !page.elements.isEmpty()
                      || page.backgroundPath != null && !page.backgroundPath.isEmpty())
              .put("modifiedAt", note.modifiedAt)
              .put("fileModified", fileModified)
              .put("fileBytes", fileBytes)
              .put("tags", new JSONArray(note.tags)));
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  static LibraryEntry read(JSONObject o) throws JSONException {
    return new LibraryEntry(o);
  }

  JSONObject json() throws JSONException {
    return new JSONObject()
        .put("schema", 1)
        .put("id", id)
        .put("title", title)
        .put("folder", folder)
        .put("cover", cover)
        .put("coverPattern", coverPattern)
        .put("coverImagePath", coverImagePath)
        .put("pageCount", pageCount)
        .put("firstWidth", firstWidth)
        .put("firstHeight", firstHeight)
        .put("trashed", trashed)
        .put("trashedAt", trashedAt)
        .put("infinite", infinite)
        .put("hasPreview", hasPreview)
        .put("modifiedAt", modifiedAt)
        .put("fileModified", fileModified)
        .put("fileBytes", fileBytes)
        .put("tags", new JSONArray(tags));
  }

  String version() {
    return id + "-" + fileModified + "-" + fileBytes + "-" + modifiedAt;
  }

  boolean sameCard(LibraryEntry other) {
    return version().equals(other.version())
        && title.equals(other.title)
        && folder.equals(other.folder)
        && cover.equals(other.cover)
        && coverPattern.equals(other.coverPattern)
        && coverImagePath.equals(other.coverImagePath)
        && tags.equals(other.tags)
        && pageCount == other.pageCount
        && firstWidth == other.firstWidth
        && firstHeight == other.firstHeight
        && hasPreview == other.hasPreview
        && trashed == other.trashed
        && infinite == other.infinite;
  }
}
