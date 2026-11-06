package app.iko.notes;

import java.util.Locale;

/**
 * Brand families are evidence labels; all drawing continues through Android's actual pen events.
 */
final class StylusProfiles {
  static final String[] IDS = {
    "auto", "android", "xiaomi", "huawei", "honor", "oppo", "vivo", "lenovo"
  };
  static final String[] LABELS = {
    "自动识别",
    "通用 Android / USI",
    "小米 · Smart / Focus Pen",
    "华为 · M-Pencil",
    "荣耀 · Magic-Pencil",
    "OPPO / 一加手写笔",
    "vivo / iQOO Pencil",
    "联想 / 小新 / 拯救者"
  };

  static String identify(String name, boolean penSource, int vendor, int product) {
    if (XiaomiPenKeys.isPenKeyboard(name, vendor, product)) return "xiaomi";
    if (!penSource) return "android";
    String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
    if (n.contains("xiaomi") || n.contains("focus pen") || n.contains("小米")) return "xiaomi";
    if (n.contains("huawei") || n.contains("m-pencil") || n.contains("华为")) return "huawei";
    if (n.contains("honor") || n.contains("magic-pencil") || n.contains("荣耀")) return "honor";
    if (n.contains("oppo") || n.contains("oneplus") || n.contains("一加")) return "oppo";
    if (n.contains("vivo") || n.contains("iqoo")) return "vivo";
    if (n.contains("lenovo")
        || n.contains("xiaoxin")
        || n.contains("precision pen")
        || n.contains("tab pen")
        || n.contains("小新")) return "lenovo";
    return "android";
  }

  static String label(String id) {
    for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return LABELS[i];
    return LABELS[0];
  }

  static String standardButton(int keyCode) {
    return keyCode == 308 ? "sidePrimary" : keyCode == 309 ? "sideSecondary" : null;
  }

  static int buttonBit(String side) {
    return "sidePrimary".equals(side) ? 32 : 64;
  }

  private StylusProfiles() {}
}
