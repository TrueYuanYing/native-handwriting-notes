package app.iko.notes;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class NoteDocumentTest {
  @Test
  public void fragmentPathModeSurvivesCopyAndSaveWhileOlderInkDefaultsToSmooth() throws Exception {
    NoteDocument note = NoteDocument.create("局部擦除路径");
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    stroke.pathMode = "linear";
    stroke.points.add(new NoteDocument.Point(1, 2, .4f, .2f, 30));
    stroke.points.get(0).widthScale = .83f;
    stroke.points.get(0).alphaScale = .5f;
    stroke.points.get(0).styleRun = 7;
    note.pages.get(0).strokes.add(stroke);
    assertEquals("linear", note.copy().pages.get(0).strokes.get(0).pathMode);
    JSONObject encoded = note.toJson();
    assertEquals("linear", NoteDocument.fromJson(encoded).pages.get(0).strokes.get(0).pathMode);
    NoteDocument.Point copied = note.copy().pages.get(0).strokes.get(0).points.get(0);
    NoteDocument.Point saved =
        NoteDocument.fromJson(encoded).pages.get(0).strokes.get(0).points.get(0);
    assertEquals(.83f, copied.widthScale, 0);
    assertEquals(.5f, copied.alphaScale, 0);
    assertEquals(.83f, saved.widthScale, 0);
    assertEquals(.5f, saved.alphaScale, 0);
    assertEquals(7, copied.styleRun);
    assertEquals(7, saved.styleRun);
    encoded
        .getJSONArray("pages")
        .getJSONObject(0)
        .getJSONArray("strokes")
        .getJSONObject(0)
        .remove("pathMode");
    assertEquals("smooth", NoteDocument.fromJson(encoded).pages.get(0).strokes.get(0).pathMode);
    stroke.pathMode = "unknown";
    assertThrows(org.json.JSONException.class, note::toJson);
    encoded
        .getJSONArray("pages")
        .getJSONObject(0)
        .getJSONArray("strokes")
        .getJSONObject(0)
        .put("pathMode", "unknown");
    assertThrows(org.json.JSONException.class, () -> NoteDocument.fromJson(encoded));
  }

  @Test
  public void directoryAndReferencesSurviveSavingCopyingAndHistory() throws Exception {
    NoteDocument note = NoteDocument.create("课程目录");
    NoteDocument.Page source = note.pages.get(0), target = NoteDocument.newPage();
    target.title = "第一章 力与运动";
    note.pages.add(target);
    NoteDocument.Element reference = new NoteDocument.Element();
    reference.text = "查看第一章";
    reference.targetPageId = target.id;
    source.elements.add(reference);

    JSONObject encoded = note.toJson();
    assertEquals(NoteDocument.VERSION, encoded.getInt("version"));
    NoteDocument restored = NoteDocument.fromJson(new JSONObject(encoded.toString()));
    assertEquals("第一章 力与运动", restored.pages.get(1).title);
    assertEquals(target.id, restored.pages.get(0).elements.get(0).targetPageId);
    assertEquals(target.id, restored.copy().pages.get(0).elements.get(0).targetPageId);
    NoteDocument.Page duplicated = restored.pages.get(0).copyAsNew();
    assertNotEquals(source.id, duplicated.id);
    assertEquals(target.id, duplicated.elements.get(0).targetPageId);

    InkHistory history = new InkHistory();
    history.record(target);
    target.title = "修订章节名";
    assertTrue(history.undo(target));
    assertEquals("第一章 力与运动", target.title);
    assertTrue(history.redo(target));
    assertEquals("修订章节名", target.title);
  }

  @Test
  public void versionOneNotesUpgradeWithoutLosingOldInkOrText() throws Exception {
    JSONObject legacy =
        new JSONObject(
            "{\"schema\":\"iko.notes\",\"version\":1,\"id\":\"legacy-note\","
                + "\"title\":\"旧课堂\",\"pages\":[{\"id\":\"legacy-page\","
                + "\"strokes\":[{\"id\":\"legacy-stroke\",\"tool\":\"pen\",\"width\":3,"
                + "\"points\":[{\"x\":13,\"y\":27,\"pressure\":0.37,\"tilt\":0.15,\"time\":55}]}],"
                + "\"elements\":[{\"kind\":\"text\",\"text\":\"旧内容\"}]}]}");
    NoteDocument restored = NoteDocument.fromJson(legacy);
    assertEquals("", restored.pages.get(0).title);
    assertEquals("", restored.pages.get(0).elements.get(0).targetPageId);
    assertEquals("旧内容", restored.pages.get(0).elements.get(0).text);
    assertEquals(.37f, restored.pages.get(0).strokes.get(0).points.get(0).pressure, .0001f);
    assertEquals(NoteDocument.VERSION, restored.toJson().getInt("version"));
    legacy.put("version", 0);
    assertThrows(org.json.JSONException.class, () -> NoteDocument.fromJson(legacy));
  }

  @Test
  public void brokenTextReferencesRemainSaveableButMalformedMetadataIsRejected() throws Exception {
    NoteDocument note = NoteDocument.create("引用边界");
    NoteDocument.Page page = note.pages.get(0);
    page.title = "章".repeat(500);
    NoteDocument.Element reference = new NoteDocument.Element();
    reference.text = "目标已删除也保留来源文字";
    reference.targetPageId = "deleted-page";
    page.elements.add(reference);
    assertEquals(
        "deleted-page",
        NoteDocument.fromJson(note.toJson()).pages.get(0).elements.get(0).targetPageId);
    page.title += "章";
    assertThrows(org.json.JSONException.class, note::toJson);
    page.title = "";
    reference.targetPageId = "x".repeat(101);
    assertThrows(org.json.JSONException.class, note::toJson);
    reference.targetPageId = "invalid/page";
    assertThrows(org.json.JSONException.class, note::toJson);
    reference.targetPageId = "deleted-page";
    reference.kind = "image";
    assertThrows(org.json.JSONException.class, note::toJson);
  }

  @Test
  public void backupRoundTripRetainsPageInkAndImportedElements() throws Exception {
    NoteDocument note = NoteDocument.create("物理课堂");
    NoteDocument.Page page = note.pages.get(0);
    page.paper = "cornell";
    page.texture = "cotton";
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    stroke.tool = "pen";
    stroke.width = 7;
    stroke.color = 0xff173572;
    stroke.points.add(new NoteDocument.Point(12, 31, .37f, .19f, 1234567));
    page.strokes.add(stroke);
    NoteDocument.Element image = new NoteDocument.Element();
    image.kind = "image";
    image.path = "/private/files/assets/image.png";
    image.x = 40;
    image.width = 300;
    page.elements.add(image);
    NoteDocument restored = NoteDocument.fromJson(new JSONObject(note.toJson().toString()));
    assertEquals("物理课堂", restored.title);
    assertEquals("cornell", restored.pages.get(0).paper);
    assertEquals("cotton", restored.pages.get(0).texture);
    NoteDocument.Point point = restored.pages.get(0).strokes.get(0).points.get(0);
    assertEquals(.37f, point.pressure, .0001f);
    assertEquals(.19f, point.tilt, .0001f);
    assertEquals(1234567, point.time);
    assertEquals("/private/files/assets/image.png", restored.pages.get(0).elements.get(0).path);
    restored.pages.get(0).strokes.get(0).points.get(0).x = 99;
    assertEquals(12, note.pages.get(0).strokes.get(0).points.get(0).x, 0);
  }

  @Test
  public void rejectsUnknownVersionAndDuplicatePageIdentity() throws Exception {
    NoteDocument note = NoteDocument.create("测试");
    JSONObject newer = note.toJson();
    newer.put("version", 99);
    assertThrows(org.json.JSONException.class, () -> NoteDocument.fromJson(newer));
    JSONObject duplicate = note.toJson();
    duplicate.getJSONArray("pages").put(duplicate.getJSONArray("pages").getJSONObject(0));
    assertThrows(org.json.JSONException.class, () -> NoteDocument.fromJson(duplicate));
  }

  @Test
  public void encodingRejectsTagOverflowInsteadOfProducingAnUnreadableSave() throws Exception {
    NoteDocument note = NoteDocument.create("标签边界");
    for (int i = 0; i < 100; i++) note.tags.add("标签" + i);
    assertEquals(100, NoteDocument.fromJson(note.toJson()).tags.size());
    note.tags.add("收藏");
    assertThrows(org.json.JSONException.class, note::toJson);
  }
}
