package app.iko.notes;

/** One display mapping for library badges and document properties. */
final class NoteTypes {
  static String label(NoteDocument note) {
    return label(note.pages.size() == 1 && note.pages.get(0).infinite);
  }

  static String label(boolean infinite) {
    return infinite ? "无边记" : "分页笔记";
  }

  private NoteTypes() {}
}
