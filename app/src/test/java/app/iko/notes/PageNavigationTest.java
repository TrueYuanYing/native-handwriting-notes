package app.iko.notes;

import static org.junit.Assert.*;

import org.junit.Test;

public class PageNavigationTest {
  @Test
  public void invalidViewportCannotEnterTheReadingHistory() {
    assertThrows(IllegalArgumentException.class, () -> new PageNavigation.Location("", 0, 0, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new PageNavigation.Location("bad/id", 0, 0, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new PageNavigation.Location("page", Float.NaN, 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PageNavigation.Location("page", 0, Float.POSITIVE_INFINITY, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new PageNavigation.Location("page", 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new PageNavigation.Location("page", 0, 0, Float.NaN));
  }

  @Test
  public void referencesAndReturnLocationsSurviveInsertedPages() {
    NoteDocument note = NoteDocument.create("课堂");
    NoteDocument.Page source = note.pages.get(0), target = NoteDocument.newPage();
    note.pages.add(target);
    PageNavigation navigation = new PageNavigation();
    PageNavigation.Location location = new PageNavigation.Location(source.id, 312, 488, 2.5f);

    assertEquals(1, navigation.open(note.id, location, target.id, note));
    note.pages.add(0, NoteDocument.newPage());
    assertEquals(2, note.findPageIndex(target.id));
    assertTrue(navigation.canBack(note));
    PageNavigation.Location restored = navigation.back(note);
    assertEquals(source.id, restored.pageId);
    assertEquals(1, note.findPageIndex(restored.pageId));
    assertEquals(312, restored.centerX, 0);
    assertEquals(488, restored.centerY, 0);
    assertEquals(2.5f, restored.zoom, 0);
    assertFalse(navigation.canBack(note));
  }

  @Test
  public void missingEndpointsAndSamePageLinksDoNotAddReturnEntries() {
    NoteDocument note = NoteDocument.create("课堂");
    NoteDocument.Page source = note.pages.get(0), target = NoteDocument.newPage();
    note.pages.add(target);
    PageNavigation navigation = new PageNavigation();
    PageNavigation.Location location = new PageNavigation.Location(source.id, 20, 30, 1);
    assertEquals(0, navigation.open(note.id, location, source.id, note));
    assertFalse(navigation.canBack(note));
    assertEquals(-1, navigation.open(note.id, location, "deleted-target", note));
    assertEquals(-1, navigation.open("another-note", location, target.id, note));
    assertEquals(
        -1,
        navigation.open(
            note.id, new PageNavigation.Location("deleted-source", 0, 0, 1), target.id, note));
    assertNull(navigation.back(note));
    assertEquals(1, navigation.open(note.id, location, target.id, note));
    note.pages.remove(target);
    assertEquals(-1, note.findPageIndex(target.id));
    assertEquals(-1, navigation.open(note.id, location, target.id, note));
    assertEquals(source.id, navigation.back(note).pageId);
    assertNull(navigation.back(note));
  }

  @Test
  public void returningSkipsADeletedIntermediateSource() {
    NoteDocument note = NoteDocument.create("课堂");
    NoteDocument.Page first = note.pages.get(0),
        middle = NoteDocument.newPage(),
        last = NoteDocument.newPage();
    note.pages.add(middle);
    note.pages.add(last);
    PageNavigation navigation = new PageNavigation();
    navigation.open(note.id, new PageNavigation.Location(first.id, 100, 200, 2), middle.id, note);
    navigation.open(note.id, new PageNavigation.Location(middle.id, 300, 400, 3), last.id, note);
    note.pages.remove(middle);
    assertEquals(first.id, navigation.back(note).pageId);
    assertNull(navigation.back(note));
  }

  @Test
  public void readingHistoryRetainsOnlyTheLatest32LocationsAndNeverCrossesDocuments() {
    NoteDocument note = NoteDocument.create("课堂");
    NoteDocument.Page first = note.pages.get(0), second = NoteDocument.newPage();
    note.pages.add(second);
    PageNavigation navigation = new PageNavigation();
    for (int i = 0; i < 40; i++) {
      String source = i % 2 == 0 ? first.id : second.id;
      String target = i % 2 == 0 ? second.id : first.id;
      navigation.open(note.id, new PageNavigation.Location(source, i, 100, 2), target, note);
    }
    navigation.switchDocument(note.id);
    assertEquals(39, navigation.back(note).centerX, 0);
    int returned = 1;
    PageNavigation.Location location, oldest = null;
    while ((location = navigation.back(note)) != null) {
      oldest = location;
      returned++;
    }
    assertEquals(32, returned);
    assertNotNull(oldest);
    assertEquals(8, oldest.centerX, 0);

    NoteDocument other = note.copy();
    other.id = "another-note";
    navigation.open(note.id, new PageNavigation.Location(first.id, 5, 7, 1), second.id, note);
    assertFalse(navigation.canBack(other));
    navigation.switchDocument(other.id);
    assertNull(navigation.back(other));
    navigation.switchDocument(note.id);
    assertNull(navigation.back(note));
    navigation.open(note.id, new PageNavigation.Location(first.id, 5, 7, 1), second.id, note);
    assertNull(navigation.back(other));
    assertNull(navigation.back(note));
  }
}
