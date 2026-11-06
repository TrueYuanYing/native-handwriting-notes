package app.iko.notes;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** Paper-space behavior used by both image editing and mixed lasso operations. */
public class ObjectTransformTest {
  @Test
  public void rotatedElementSurvivesCopySaveAndLegacyDefaults() throws Exception {
    NoteDocument note = NoteDocument.create("旋转保存");
    NoteDocument.Element image = new NoteDocument.Element();
    image.kind = "image";
    image.path = "/original.png";
    image.rotation = 37.5f;
    note.pages.get(0).elements.add(image);
    assertEquals(37.5f, note.copy().pages.get(0).elements.get(0).rotation, 0);
    JSONObject saved = note.toJson();
    assertEquals(37.5f, NoteDocument.fromJson(saved).pages.get(0).elements.get(0).rotation, 0);
    saved.getJSONArray("pages").getJSONObject(0).getJSONArray("elements")
        .getJSONObject(0).remove("rotation");
    assertEquals(0, NoteDocument.fromJson(saved).pages.get(0).elements.get(0).rotation, 0);
    image.rotation = Float.NaN;
    assertThrows(org.json.JSONException.class, note::validate);
  }

  @Test
  public void rotatedHitTestUsesTheOrientedRectangle() {
    NoteDocument.Element image = new NoteDocument.Element();
    image.x = image.y = 100;
    image.width = 200;
    image.height = 40;
    image.rotation = 90;
    ObjectTransform.Bounds bounds = ObjectTransform.bounds(image);
    assertEquals(180, bounds.left, .001f);
    assertEquals(20, bounds.top, .001f);
    assertEquals(220, bounds.right, .001f);
    assertEquals(220, bounds.bottom, .001f);
    assertTrue(ObjectTransform.contains(image, 200, 30));
    assertFalse(ObjectTransform.contains(image, 105, 105));
  }

  @Test
  public void mixedTransformKeepsOriginalAssetsAndPressureAndRejectsInvalidPreview() {
    NoteDocument.Element image = new NoteDocument.Element();
    image.kind = "image";
    image.path = "/original.png";
    image.x = 100;
    image.y = 100;
    image.width = 100;
    image.height = 40;
    NoteDocument.Element transformed = image.copy();
    ObjectTransform.applyElement(image, transformed, 100, 100, 2, 90, 220, 30);
    assertEquals(180, transformed.x, .001f);
    assertEquals(190, transformed.y, .001f);
    assertEquals(200, transformed.width, .001f);
    assertEquals(90, transformed.rotation, .001f);
    assertEquals("/original.png", transformed.path);
    assertEquals(100, image.x, 0);
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    stroke.width = 4;
    stroke.points.add(new NoteDocument.Point(110, 100, .37f, .21f, 123));
    stroke.points.get(0).widthScale = .8f;
    stroke.points.get(0).alphaScale = .7f;
    NoteDocument.Stroke moved = stroke.copy();
    ObjectTransform.applyStroke(stroke, moved, 100, 100, 2, 90, 20, 30);
    assertEquals(120, moved.points.get(0).x, .001f);
    assertEquals(150, moved.points.get(0).y, .001f);
    assertEquals(8, moved.width, 0);
    assertEquals(.37f, moved.points.get(0).pressure, 0);
    assertEquals(.8f, moved.points.get(0).widthScale, 0);
    assertEquals(.7f, moved.points.get(0).alphaScale, 0);
    assertEquals(123, moved.points.get(0).time);
    NoteDocument.Page page = NoteDocument.newPage();
    assertTrue(ObjectTransform.isValid(transformed, page));
    transformed.rotation = Float.POSITIVE_INFINITY;
    assertFalse(ObjectTransform.isValid(transformed, page));
    transformed.rotation = 0;
    transformed.x = -1;
    assertFalse(ObjectTransform.isValid(transformed, page));
    page.infinite = true;
    assertTrue(ObjectTransform.isValid(transformed, page));
    transformed.width = 40001;
    assertFalse(ObjectTransform.isValid(transformed, page));
  }

  @Test
  public void rotatingShapesTransformsTheirRenderedOutlineInsteadOfRebuildingAxisAlignedShapes() {
    for (String tool : new String[] {"rectangle", "ellipse", "arrow"}) {
      NoteDocument.Stroke source = new NoteDocument.Stroke();
      source.tool = tool;
      source.width = 4;
      source.points.add(new NoteDocument.Point(100, 100, .2f, .1f, 10));
      source.points.add(new NoteDocument.Point(200, 150, .7f, .2f, 20));
      java.util.List<NoteDocument.Point> original = InkGeometry.outline(source);
      NoteDocument.Stroke result = source.copy();
      ObjectTransform.applyStroke(source, result, 0, 0, 2, 90, 0, 0);
      assertEquals("linepath", result.tool);
      assertEquals(original.size(), result.points.size());
      assertEquals(-original.get(0).y * 2, result.points.get(0).x, .001f);
      assertEquals(original.get(0).x * 2, result.points.get(0).y, .001f);
      assertEquals(8, result.width, 0);
      ObjectTransform.applyStroke(source, result, 0, 0, 1, 0, 0, 0);
      assertEquals("arrow".equals(tool) ? "linepath" : tool, result.tool);
    }
  }
}
