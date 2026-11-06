package app.iko.notes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class StrokeSpatialIndexTest {
  @Test
  public void sameGridCellStillRejectsInkOutsideTheActualEraserBounds() {
    NoteDocument.Stroke hit = stroke(20, 40, 200, 40);
    NoteDocument.Stroke miss = stroke(20, 200, 200, 200);
    StrokeSpatialIndex index = new StrokeSpatialIndex(List.of(hit, miss));

    assertTrue(index.query(100, 38, 102, 42, 3).contains(hit));
    assertFalse("coarse-grid misses must not run analytic clipping",
        index.query(100, 38, 102, 42, 3).contains(miss));
  }
  @Test
  public void localEraseQueryIncludesEveryVisibleHitButSkipsDistantInk() {
    NoteDocument.Stroke near = stroke(-300, -2, 300, -2);
    NoteDocument.Stroke crossingCellEdge = stroke(250, 40, 270, 40);
    NoteDocument.Stroke far = stroke(1800, 0, 1900, 0);
    NoteDocument.Stroke longOverflow = stroke(-100_000, 38, 100_000, 38);
    StrokeSpatialIndex index =
        new StrokeSpatialIndex(List.of(near, crossingCellEdge, far, longOverflow));

    List<NoteDocument.Stroke> candidates = index.query(255, -10, 265, 45, 4);

    assertTrue(candidates.contains(near));
    assertTrue(candidates.contains(crossingCellEdge));
    assertTrue(candidates.contains(longOverflow));
    assertFalse(candidates.contains(far));
    for (NoteDocument.Stroke stroke : List.of(near, crossingCellEdge, far, longOverflow)) {
      if (InkGeometry.hitStroke(stroke, 255, -10, 265, 45, 4))
        assertTrue("query omitted visible ink", candidates.contains(stroke));
    }
  }

  @Test
  public void removedAndAddedInkTracksCurrentPageContents() {
    NoteDocument.Stroke first = stroke(0, 0, 20, 0);
    NoteDocument.Stroke second = stroke(500, 0, 520, 0);
    StrokeSpatialIndex index = new StrokeSpatialIndex(new ArrayList<>(List.of(first)));

    index.remove(first);
    index.add(second);

    assertFalse(index.query(0, 0, 20, 0, 1).contains(first));
    assertTrue(index.query(500, 0, 520, 0, 1).contains(second));
    assertSame(second, index.query(500, 0, 520, 0, 1).get(0));
  }

  @Test
  public void arrowHeadExtensionRemainsInItsSpatialIndexCells() {
    NoteDocument.Stroke arrow = stroke(80, 250, 100, 250);
    arrow.tool = "arrow";
    StrokeSpatialIndex index = new StrokeSpatialIndex(List.of(arrow));

    List<NoteDocument.Stroke> candidates = index.query(82, 258.7f, 82, 258.7f, 1);

    assertTrue(InkGeometry.hitStroke(arrow, 82, 258.7f, 1));
    assertTrue("spatial query omitted the visible arrowhead", candidates.contains(arrow));
  }

  private static NoteDocument.Stroke stroke(float x1, float y1, float x2, float y2) {
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    stroke.width = 2;
    stroke.points.add(new NoteDocument.Point(x1, y1, 1, 0, 0));
    stroke.points.add(new NoteDocument.Point(x2, y2, 1, 0, 10));
    return stroke;
  }
}
