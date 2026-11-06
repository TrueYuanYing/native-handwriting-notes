package app.iko.notes;

import android.content.SharedPreferences;

/** Reading progress is local preference data, separate from the document and undo history. */
final class ReadingPositionStore {
  static void save(SharedPreferences prefs, String id, PageNavigation.Location location) {
    String key = "reading:" + id + ":";
    prefs
        .edit()
        .putString(key + "page", location.pageId)
        .putFloat(key + "x", location.centerX)
        .putFloat(key + "y", location.centerY)
        .putFloat(key + "zoom", location.zoom)
        .putBoolean(key + "width", location.widthFit)
        .apply();
  }

  static PageNavigation.Location read(SharedPreferences prefs, String id) {
    String key = "reading:" + id + ":";
    String page = prefs.getString(key + "page", null);
    if (page == null) return null;
    return new PageNavigation.Location(
        page,
        prefs.getFloat(key + "x", 0),
        prefs.getFloat(key + "y", 0),
        prefs.getFloat(key + "zoom", 1),
        prefs.getBoolean(key + "width", true));
  }

  private ReadingPositionStore() {}
}
