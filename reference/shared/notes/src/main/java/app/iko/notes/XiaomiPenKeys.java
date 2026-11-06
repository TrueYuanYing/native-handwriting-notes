package app.iko.notes;

/** Older Xiaomi pens expose their buttons through a separate Bluetooth keyboard device. */
final class XiaomiPenKeys {
  static boolean isPenKeyboard(String name, int vendor, int product) {
    return "Xiaomi Smart Pen Keyboard".equals(name) || (vendor == 0x1915 && product == 0x4d81);
  }

  static String preference(String name, int vendor, int product, int keyCode) {
    if (!isPenKeyboard(name, vendor, product)) return null;
    if (keyCode == 93) return "sidePrimary"; // KEYCODE_PAGE_DOWN
    if (keyCode == 92) return "sideSecondary"; // KEYCODE_PAGE_UP
    return null;
  }

  private XiaomiPenKeys() {}
}
