package app.iko.notes;

/** Small original stationery designs. IDs are persisted in notebook archives. */
final class CoverCatalog {
  static final String[] IDS = {
    "plain",
    "duo",
    "dots",
    "stripes",
    "flowers",
    "stars",
    "moon",
    "clouds",
    "grid",
    "hills",
    "geometry",
    "label",
    "spine"
  };
  static final String[] NAMES = {
    "素笺", "双色", "圆点", "细条", "小花", "星屑", "月夜", "轻云", "软格", "山丘", "几何", "纸签", "书脊"
  };
  static final String[] GROUPS = {"简约", "自然", "图形"};
  static final int[] COLORS = {
    0xffa8bce5, 0xffbcb0dc, 0xffe7b8c8, 0xffacd1c0, 0xffe7c396, 0xffb2cbd0
  };
  static final String[] COLOR_NAMES = {"雾蓝", "芋紫", "樱粉", "薄荷", "燕麦", "青灰"};

  static boolean contains(String id) {
    for (String value : IDS) if (value.equals(id)) return true;
    return false;
  }

  static int group(String id) {
    if (id.equals("flowers")
        || id.equals("stars")
        || id.equals("moon")
        || id.equals("clouds")
        || id.equals("hills")) return 1;
    if (id.equals("dots") || id.equals("grid") || id.equals("geometry") || id.equals("stripes"))
      return 2;
    return 0;
  }

  static int color(String value) {
    try {
      return android.graphics.Color.parseColor(value);
    } catch (IllegalArgumentException | NullPointerException ignored) {
      return COLORS[0];
    }
  }

  private CoverCatalog() {}
}
