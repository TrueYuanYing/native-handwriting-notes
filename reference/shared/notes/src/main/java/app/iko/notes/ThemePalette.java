package app.iko.notes;

/** IKO-compatible color families. Paper and ink colors are deliberately independent. */
final class ThemePalette {
  static final String[] IDS = {"blue", "forest", "ocean", "plum", "pink", "peach"};
  static final String[] NAMES = {"经典蓝", "森林", "海洋", "梅子", "樱粉", "蜜桃"};
  static final int[] LIGHT = {
    0xff466af0, 0xff237b58, 0xff087c98, 0xff8058ad, 0xffb94778, 0xffad5c28
  };
  static final int[] DARK = {
    0xff9ab3ff, 0xff9bdcaf, 0xff78d1e4, 0xffd6b3ec, 0xffedb3d1, 0xfff0c29b
  };

  static int index(String id) {
    for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
    return 0;
  }

  private ThemePalette() {}
}
