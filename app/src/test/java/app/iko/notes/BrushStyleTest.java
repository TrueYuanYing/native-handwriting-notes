package app.iko.notes;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class BrushStyleTest {
  @Test
  public void newFountainPenHasClearLightHeavyContrastWithoutChangingLegacyInk() throws Exception {
    NoteDocument.Stroke original = new NoteDocument.Stroke();
    original.tool = "pen";
    original.width = 4;
    NoteDocument.Point light = new NoteDocument.Point(0, 0, .2f, 0, 0),
        heavy = new NoteDocument.Point(100, 0, .8f, 0, 10);
    original.points.add(light);
    original.points.add(heavy);
    JSONObject json = original.toJson();
    json.put("brushVersion", 2);
    NoteDocument.Stroke modern = NoteDocument.Stroke.fromJson(json);
    assertTrue(
        "New fountain ink should have at least threefold contrast at 20% vs 80% pressure",
        BrushStyle.width(modern, heavy) > BrushStyle.width(modern, light) * 3);
    assertEquals(
        4 * (.25f + .95f * (float) Math.sqrt(.2f)), BrushStyle.width(original, light), .00001f);
    NoteDocument.Stroke reopened = NoteDocument.Stroke.fromJson(modern.toJson());
    assertEquals(BrushStyle.width(modern, light), BrushStyle.width(reopened, light), 0);
    modern.pressureMode = "off";
    assertEquals(4, BrushStyle.width(modern, light), 0);
    assertEquals(4, BrushStyle.width(modern, heavy), 0);
    java.util.List<NoteDocument.Stroke> fragments =
        StrokeEraser.erase(reopened, 50, -20, 50, 20, 3);
    for (NoteDocument.Stroke fragment : fragments)
      assertEquals(2, fragment.toJson().getInt("brushVersion"));
  }

  @Test
  public void finelinerIsConstantWhileBrushHasADeeperPressureRange() throws Exception {
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    NoteDocument.Point light = new NoteDocument.Point(0, 0, .1f, 0, 0);
    NoteDocument.Point heavy = new NoteDocument.Point(100, 0, 1, 0, 10);
    stroke.tool = "fineliner";
    assertEquals(stroke.width, BrushStyle.width(stroke, light), 0);
    assertEquals(stroke.width, BrushStyle.width(stroke, heavy), 0);
    stroke.tool = "brush";
    assertTrue(BrushStyle.width(stroke, heavy) > BrushStyle.width(stroke, light) * 5);
    assertEquals(
        "brush", PenPreset.fromJson(new PenPreset("brush", 0xff223344, 4, "firm").toJson()).tool);
    assertEquals(
        "fineliner",
        PenPreset.fromJson(new PenPreset("fineliner", 0xff223344, 4, "off").toJson()).tool);
  }

  @Test
  public void markerOpacityRespondsAcrossTheWholeAlphaRange() {
    assertEquals(80, BrushStyle.markerAlpha(0xff112233));
    assertEquals(40, BrushStyle.markerAlpha(0x80112233));
    assertEquals(0, BrushStyle.markerAlpha(0x00112233));
  }

  @Test
  public void pressurePreferenceIsSavedWithEachStrokeAndOldNotesKeepTheirStyle() throws Exception {
    NoteDocument note = NoteDocument.create("Pressure");
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    stroke.pressureMode = "firm";
    stroke.pressureMin = .1f;
    stroke.pressureMax = 2f;
    stroke.points.add(new NoteDocument.Point(10, 20, .9f, .2f, 20));
    note.pages.get(0).strokes.add(stroke);
    NoteDocument.Stroke restored = NoteDocument.fromJson(note.toJson()).pages.get(0).strokes.get(0);
    assertEquals("firm", restored.pressureMode);
    assertEquals(.1f, restored.pressureMin, .0001f);
    assertEquals(2f, restored.pressureMax, .0001f);
    assertEquals(.9f, restored.points.get(0).pressure, .0001f);
    assertEquals("firm", restored.copy().pressureMode);
    JSONObject legacy = stroke.toJson();
    legacy.remove("pressureMode");
    legacy.remove("pressureMin");
    legacy.remove("pressureMax");
    NoteDocument.Stroke old = NoteDocument.Stroke.fromJson(legacy);
    assertEquals("standard", old.pressureMode);
    assertEquals(0f, old.pressureMin, 0f);
    assertEquals(1f, old.pressureMax, 0f);
  }

  @Test
  public void pressureOffKeepsPencilWidthAndOpacityWhileCurvesFollowUserPreference() {
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    stroke.tool = "pencil";
    NoteDocument.Point light = new NoteDocument.Point(10, 10, .2f, .3f, 1);
    NoteDocument.Point heavy = new NoteDocument.Point(10, 10, .9f, .3f, 2);
    stroke.pressureMode = "off";
    assertEquals(BrushStyle.width(stroke, light), BrushStyle.width(stroke, heavy), 0f);
    assertEquals(BrushStyle.alpha(stroke, light), BrushStyle.alpha(stroke, heavy));
    stroke.pressureMode = "standard";
    float standard = BrushStyle.width(stroke, light);
    stroke.pressureMode = "soft";
    assertTrue(BrushStyle.width(stroke, light) > standard);
    stroke.pressureMode = "firm";
    assertTrue(BrushStyle.width(stroke, light) < standard);
    stroke.pressureMode = "standard";
    stroke.pressureMax = 2;
    assertEquals(4f * (.45f + .65f * .45f + .3f * .5f), BrushStyle.width(stroke, heavy), .0001f);
  }
}
