package app.iko.notes;

import org.json.JSONException;
import org.json.JSONObject;

/** A local pen-box entry, independent from the appearance of already committed ink. */
public final class PenPreset {
  public final String tool, pressureMode;
  public final int color;
  public final float width;

  public PenPreset(String tool, int color, float width, String pressureMode) {
    this.tool = tool;
    this.color = color;
    this.width = width;
    this.pressureMode = pressureMode;
  }

  public static boolean isPen(String tool) {
    return "pen".equals(tool)
        || "ballpoint".equals(tool)
        || "pencil".equals(tool)
        || "brush".equals(tool)
        || "fineliner".equals(tool)
        || "highlighter".equals(tool);
  }

  public JSONObject toJson() throws JSONException {
    return new JSONObject()
        .put("tool", tool)
        .put("color", color)
        .put("width", width)
        .put("pressureMode", pressureMode);
  }

  public static PenPreset fromJson(JSONObject json) throws JSONException {
    String tool = json.getString("tool"), mode = json.getString("pressureMode");
    float width = (float) json.getDouble("width");
    if (!isPen(tool)
        || !BrushStyle.validPressureMode(mode)
        || !Float.isFinite(width)
        || width < .5f
        || width > 100) throw new JSONException("笔预设参数无效");
    return new PenPreset(tool, json.optInt("color", 0xff272d3b), width, mode);
  }
}
