package app.iko.notes;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Session history, isolated by page id, including page properties and elements. */
public final class InkHistory {
  private final Map<String, ArrayDeque<State>> past = new HashMap<>();
  private final Map<String, ArrayDeque<State>> future = new HashMap<>();
  private boolean restoredTapeOnly;
  private static final int MAX_STATES = 32;
  private static final int MAX_SNAPSHOT_POINTS = 1_500_000;

  private static final class State {
    final NoteDocument.Page snapshot;
    final boolean[] tapeVisibility;

    State(NoteDocument.Page snapshot, boolean[] tapeVisibility) {
      this.snapshot = snapshot;
      this.tapeVisibility = tapeVisibility;
    }

    static State tape(NoteDocument.Page page) {
      boolean[] visibility = new boolean[page.elements.size()];
      for (int i = 0; i < visibility.length; i++) visibility[i] = page.elements.get(i).revealed;
      return new State(null, visibility);
    }

    State captureCurrent(NoteDocument.Page page) {
      return tapeVisibility == null ? new State(page.copy(), null) : tape(page);
    }

    void apply(NoteDocument.Page page) {
      if (tapeVisibility == null) restore(page, snapshot);
      else
        for (int i = 0; i < tapeVisibility.length; i++)
          page.elements.get(i).revealed = tapeVisibility[i];
    }

    int pointCount() {
      int count = 0;
      if (snapshot != null)
        for (NoteDocument.Stroke stroke : snapshot.strokes) count += stroke.points.size();
      return count;
    }
  }

  static final class Checkpoint {
    final ArrayDeque<State> past, future;

    Checkpoint(ArrayDeque<State> past, ArrayDeque<State> future) {
      this.past = past == null ? new ArrayDeque<>() : new ArrayDeque<>(past);
      this.future = future == null ? new ArrayDeque<>() : new ArrayDeque<>(future);
    }
  }

  Checkpoint checkpoint(NoteDocument.Page page) {
    return new Checkpoint(past.get(page.id), future.get(page.id));
  }

  void restoreCheckpoint(NoteDocument.Page page, Checkpoint checkpoint) {
    past.put(page.id, new ArrayDeque<>(checkpoint.past));
    future.put(page.id, new ArrayDeque<>(checkpoint.future));
  }

  public void clear() {
    past.clear();
    future.clear();
  }

  public void record(NoteDocument.Page before) {
    recordSnapshot(before.copy());
  }

  /** Takes ownership of an already detached snapshot; avoids copying every ink point twice. */
  void recordSnapshot(NoteDocument.Page before) {
    past.computeIfAbsent(before.id, k -> new ArrayDeque<>()).addLast(new State(before, null));
    future.remove(before.id);
    trim();
  }

  /** Visibility never changes geometry; history stores only these booleans, not all ink points. */
  void recordTapeVisibility(NoteDocument.Page page) {
    past.computeIfAbsent(page.id, k -> new ArrayDeque<>()).addLast(State.tape(page));
    future.remove(page.id);
    trim();
  }

  boolean restoredTapeOnly() {
    return restoredTapeOnly;
  }

  public boolean canUndo(NoteDocument.Page page) {
    return page != null && past.containsKey(page.id) && !past.get(page.id).isEmpty();
  }

  public boolean canRedo(NoteDocument.Page page) {
    return page != null && future.containsKey(page.id) && !future.get(page.id).isEmpty();
  }

  public boolean undo(NoteDocument.Page page) {
    if (!canUndo(page)) return false;
    State state = past.get(page.id).removeLast();
    future.computeIfAbsent(page.id, k -> new ArrayDeque<>()).addLast(state.captureCurrent(page));
    state.apply(page);
    restoredTapeOnly = state.tapeVisibility != null;
    return true;
  }

  public boolean redo(NoteDocument.Page page) {
    if (!canRedo(page)) return false;
    State state = future.get(page.id).removeLast();
    past.computeIfAbsent(page.id, k -> new ArrayDeque<>()).addLast(state.captureCurrent(page));
    state.apply(page);
    restoredTapeOnly = state.tapeVisibility != null;
    return true;
  }

  public static void restore(NoteDocument.Page target, NoteDocument.Page source) {
    NoteDocument.Page p = source.copy();
    target.title = p.title;
    target.paper = p.paper;
    target.texture = p.texture;
    target.lineSpacing = p.lineSpacing;
    target.color = p.color;
    target.width = p.width;
    target.height = p.height;
    target.infinite = p.infinite;
    target.backgroundPath = p.backgroundPath;
    target.pdfPage = p.pdfPage;
    target.strokes = p.strokes;
    target.elements = p.elements;
    target.links = p.links;
  }

  private void trim() {
    int points = 0, count = 0;
    for (ArrayDeque<State> stack : past.values())
      for (State state : stack) {
        count++;
        points += state.pointCount();
      }
    while (count > MAX_STATES || points > MAX_SNAPSHOT_POINTS) {
      boolean removed = false;
      for (ArrayDeque<State> stack : past.values()) {
        if (stack.isEmpty()) continue;
        State state = stack.removeFirst();
        count--;
        points -= state.pointCount();
        removed = true;
        break;
      }
      if (!removed) break;
    }
  }
}
