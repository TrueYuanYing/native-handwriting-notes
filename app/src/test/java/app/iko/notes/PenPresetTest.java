package app.iko.notes;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class PenPresetTest {
  @Test
  public void savedPresetKeepsAllSettingsAndRejectsUnsupportedTool() throws Exception {
    PenPreset preset = new PenPreset("pencil", 0xff234567, 2.4f, "off");
    PenPreset restored = PenPreset.fromJson(new JSONObject(preset.toJson().toString()));
    assertEquals("pencil", restored.tool);
    assertEquals(0xff234567, restored.color);
    assertEquals(2.4f, restored.width, 0);
    assertEquals("off", restored.pressureMode);
    try {
      PenPreset.fromJson(
          new JSONObject().put("tool", "eraser").put("width", 4).put("pressureMode", "standard"));
      fail("A pen preset must not silently become an eraser");
    } catch (org.json.JSONException expected) {
    }
  }
}
