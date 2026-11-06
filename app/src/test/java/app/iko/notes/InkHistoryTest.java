package app.iko.notes;

import static org.junit.Assert.*;

import org.junit.Test;

public class InkHistoryTest {
  @Test
  public void tapeVisibilityHistoryMixesWithInkAndElementChangesWithoutCopyingInk() {
    InkHistory history = new InkHistory();
    NoteDocument.Page page = NoteDocument.newPage();
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    stroke.points.add(new NoteDocument.Point(100, 200, .7f, 0, 1));
    page.strokes.add(stroke);
    NoteDocument.Element tape = new NoteDocument.Element();
    tape.kind = "tape";
    page.elements.add(tape);
    history.recordTapeVisibility(page);
    tape.revealed = true;
    assertTrue(history.undo(page));
    assertTrue(history.restoredTapeOnly());
    assertFalse(tape.revealed);
    assertSame(stroke, page.strokes.get(0));
    assertTrue(history.redo(page));
    assertTrue(tape.revealed);
    history.record(page);
    page.elements.add(new NoteDocument.Element());
    stroke.width = 12;
    assertTrue(history.undo(page));
    assertFalse(history.restoredTapeOnly());
    assertEquals(1, page.elements.size());
    assertEquals(4, page.strokes.get(0).width, 0);
    assertTrue(page.elements.get(0).revealed);
    assertTrue(history.undo(page));
    assertFalse(page.elements.get(0).revealed);
    assertEquals(1, page.strokes.size());
    assertTrue(history.redo(page));
    assertTrue(history.redo(page));
    assertEquals(2, page.elements.size());
    assertEquals(12, page.strokes.get(0).width, 0);
    assertTrue(page.elements.get(0).revealed);
  }

  @Test
  public void paperSpacingUndoRedoRestoresDefaultAndCustomWithoutChangingInk() {
    InkHistory history = new InkHistory();
    NoteDocument.Page page = NoteDocument.newPage();
    String identity = page.id;
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    stroke.points.add(new NoteDocument.Point(1350, 1900, .5f, 0, 1));
    page.strokes.add(stroke);
    history.record(page);
    page.lineSpacing = 72;
    assertTrue(history.undo(page));
    assertEquals(0, page.lineSpacing, 0);
    assertEquals(identity, page.id);
    assertEquals(1350, page.strokes.get(0).points.get(0).x, 0);
    assertTrue(history.redo(page));
    assertEquals(72, page.lineSpacing, 0);
    history.record(page);
    page.lineSpacing = 96;
    assertTrue(history.undo(page));
    assertEquals(72, page.lineSpacing, 0);
    assertTrue(history.redo(page));
    assertEquals(96, page.lineSpacing, 0);
    assertEquals(identity, page.id);
    assertEquals(1, page.strokes.size());
    assertEquals(1900, page.strokes.get(0).points.get(0).y, 0);
  }

  @Test
  public void undoAndRedoStayWithTheirPageAfterSwitching() {
    InkHistory history = new InkHistory();
    NoteDocument.Page a = NoteDocument.newPage(), b = NoteDocument.newPage();
    history.record(a);
    NoteDocument.Element text = new NoteDocument.Element();
    text.text = "第一章";
    a.elements.add(text);
    history.record(b);
    b.paper = "music";
    assertTrue(history.undo(a));
    assertTrue(a.elements.isEmpty());
    assertEquals("music", b.paper);
    assertTrue(history.redo(a));
    assertEquals("第一章", a.elements.get(0).text);
    assertTrue(history.undo(b));
    assertEquals("dot", b.paper);
    assertEquals("第一章", a.elements.get(0).text);
  }
}
