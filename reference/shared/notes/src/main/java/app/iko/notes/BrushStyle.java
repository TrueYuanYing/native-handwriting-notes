package app.iko.notes;

/**
 * Per-stroke pressure response shared by screen, thumbnails and export. Raw samples stay intact.
 */
public final class BrushStyle {
  private BrushStyle() {}

  public static boolean validPressureMode(String mode) {
    return "standard".equals(mode)
        || "soft".equals(mode)
        || "firm".equals(mode)
        || "off".equals(mode);
  }

  private static float pressure(NoteDocument.Stroke stroke, NoteDocument.Point point) {
    if ("off".equals(stroke.pressureMode)) return 1;
    float value =
        Math.max(
            0,
            Math.min(
                1,
                (point.pressure - stroke.pressureMin) / (stroke.pressureMax - stroke.pressureMin)));
    if ("soft".equals(stroke.pressureMode)) return (float) Math.pow(value, .6);
    if ("firm".equals(stroke.pressureMode)) return (float) Math.pow(value, 1.7);
    return value;
  }

  public static float width(NoteDocument.Stroke stroke, NoteDocument.Point point) {
    if ("highlighter".equals(stroke.tool)
        || "fineliner".equals(stroke.tool)
        || "linepath".equals(stroke.tool)
        || InkGeometry.isShape(stroke.tool)) return stroke.width;
    if ("pen".equals(stroke.tool) && stroke.brushVersion >= 2) {
      if ("off".equals(stroke.pressureMode)) return stroke.width;
      return stroke.width * (.12f + 1.25f * (float) Math.pow(pressure(stroke, point), 1.35));
    }
    float pressure = Math.max(.05f, pressure(stroke, point));
    if ("brush".equals(stroke.tool))
      return stroke.width * (.12f + 1.55f * (float) Math.pow(pressure, 1.3));
    if ("ballpoint".equals(stroke.tool)) return stroke.width * (.8f + .2f * pressure);
    if ("pencil".equals(stroke.tool))
      return stroke.width * (.45f + .65f * pressure + Math.min(1, point.tilt) * .5f);
    return stroke.width * (.25f + .95f * (float) Math.sqrt(pressure));
  }

  public static int alpha(NoteDocument.Stroke stroke, NoteDocument.Point point) {
    int alpha = (stroke.color >>> 24) & 255;
    if ("highlighter".equals(stroke.tool)) return markerAlpha(stroke.color);
    return "pencil".equals(stroke.tool)
        ? (int) (alpha * (.2f + .65f * pressure(stroke, point)))
        : alpha;
  }

  public static int markerAlpha(int color) {
    return Math.round(((color >>> 24) & 255) * (80f / 255));
  }
}
