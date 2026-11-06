package app.iko.notes;

import java.util.ArrayDeque;
import java.util.Objects;

/** Bounded reading history; never alters page content or the independent ink undo history. */
public final class PageNavigation {
  public static final int MAX_LOCATIONS = 32;
  private String documentId;
  private final ArrayDeque<Location> sources = new ArrayDeque<>();

  /** Coordinates are paper units; zoom is relative to the saved width or whole-page fit mode. */
  public static final class Location {
    public final String pageId;
    public final float centerX, centerY, zoom;
    public final boolean widthFit;

    public Location(String pageId, float centerX, float centerY, float zoom) {
      this(pageId, centerX, centerY, zoom, false);
    }

    public Location(String pageId, float centerX, float centerY, float zoom, boolean widthFit) {
      if (pageId == null
          || !pageId.matches("[A-Za-z0-9_-]{1,100}")
          || !Float.isFinite(centerX)
          || !Float.isFinite(centerY)
          || !Float.isFinite(zoom)
          || zoom <= 0) throw new IllegalArgumentException("页面位置或缩放无效");
      this.pageId = pageId;
      this.centerX = centerX;
      this.centerY = centerY;
      this.zoom = zoom;
      this.widthFit = widthFit;
    }
  }

  /** Opening the same document preserves its history; changing or leaving it clears the stack. */
  public void switchDocument(String id) {
    if (Objects.equals(documentId, id)) return;
    documentId = id;
    sources.clear();
  }

  /** Return a current target index, or -1 if either endpoint/document is no longer valid. */
  public int open(String documentId, Location source, String targetPageId, NoteDocument note) {
    if (note == null || !Objects.equals(documentId, note.id) || source == null) return -1;
    int target = note.findPageIndex(targetPageId);
    if (target < 0 || note.findPageIndex(source.pageId) < 0) return -1;
    switchDocument(documentId);
    if (!source.pageId.equals(targetPageId)) {
      sources.addLast(source);
      while (sources.size() > MAX_LOCATIONS) sources.removeFirst();
    }
    return target;
  }

  /** Deleted source pages are skipped, so an old reference never redirects to a different page. */
  public Location back(NoteDocument note) {
    switchDocument(note == null ? null : note.id);
    if (note == null) return null;
    while (!sources.isEmpty()) {
      Location location = sources.removeLast();
      if (note.findPageIndex(location.pageId) >= 0) return location;
    }
    return null;
  }

  public boolean canBack(NoteDocument note) {
    if (note == null || !Objects.equals(documentId, note.id)) return false;
    for (Location location : sources) if (note.findPageIndex(location.pageId) >= 0) return true;
    return false;
  }
}
