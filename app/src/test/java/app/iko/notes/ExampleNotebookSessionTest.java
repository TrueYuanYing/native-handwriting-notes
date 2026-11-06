package app.iko.notes;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExampleNotebookSessionTest {
    static class MemoryStorage implements ExampleNotebookSession.Storage {
        String lastId; NoteDocument saved; int writes; boolean failRead;
        public String lastId() {return lastId;}
        public NoteDocument load(String id) throws Exception {
            if (failRead) throw new Exception("synthetic_read_failure");
            return saved.copy();
        }
        public void save(NoteDocument note) {saved=note.copy();writes++;}
        public void remember(String id) {lastId=id;}
    }
    @Test public void reopeningRestoresTheSavedDocumentAndStroke() throws Exception {
        MemoryStorage storage=new MemoryStorage();
        ExampleNotebookSession first=new ExampleNotebookSession(storage);
        NoteDocument note=first.open();
        NoteDocument.Stroke stroke=new NoteDocument.Stroke();
        stroke.points.add(new NoteDocument.Point(12,34,.5f,0,1));
        note.pages.get(0).strokes.add(stroke);
        first.save(note);
        NoteDocument reopened=new ExampleNotebookSession(storage).open();
        assertEquals(note.id,reopened.id);
        assertEquals(1,reopened.pages.get(0).strokes.size());
        assertEquals(12,reopened.pages.get(0).strokes.get(0).points.get(0).x,0);
    }
    @Test public void failedRestoreCannotSaveABlankReplacement() throws Exception {
        MemoryStorage storage=new MemoryStorage();storage.lastId="saved-id";storage.failRead=true;
        ExampleNotebookSession session=new ExampleNotebookSession(storage);
        try {session.open();fail("read should fail");} catch (Exception expected) {}
        assertFalse(session.ready());
        try {session.save(NoteDocument.create("blank"));fail("save should be blocked");}
        catch (IllegalStateException expected) {}
        assertEquals(0,storage.writes);
    }
}
