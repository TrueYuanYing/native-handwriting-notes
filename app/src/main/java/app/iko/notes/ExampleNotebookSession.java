package app.iko.notes;

/** Example-only restore/save boundary; does not modify the ink or document algorithms. */
final class ExampleNotebookSession {
    interface Storage {
        String lastId();
        NoteDocument load(String id) throws Exception;
        void save(NoteDocument note) throws Exception;
        void remember(String id) throws Exception;
    }
    private final Storage storage;
    private boolean ready;
    ExampleNotebookSession(Storage storage) {this.storage=storage;}
    NoteDocument open() throws Exception {
        ready=false;
        String id=storage.lastId();
        NoteDocument note=id==null ? NoteDocument.create("合成示例") : storage.load(id);
        if (note==null) throw new IllegalStateException("saved_document_missing");
        ready=true;
        return note;
    }
    boolean ready() {return ready;}
    void save(NoteDocument snapshot) throws Exception {
        if (!ready) throw new IllegalStateException("restore_required_before_save");
        storage.save(snapshot);
        storage.remember(snapshot.id);
    }
}
