package app.iko.notes;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class InkGeometryTest {
  @Test
  public void renderedGeometryFollowsTheVisibleQuadraticWithinPaperTolerance() {
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    stroke.points.add(new NoteDocument.Point(0, 0, .2f, .1f, 100));
    stroke.points.add(new NoteDocument.Point(200, 0, .5f, .3f, 1100));
    stroke.points.add(new NoteDocument.Point(200, 100, .8f, .7f, 2100));
    java.util.List<NoteDocument.Point> rendered = InkGeometry.renderedPoints(stroke);

    // The final quadratic starts at (100,0), controls at (200,0), and ends at (200,100).
    for (int i = 0; i <= 1000; i++) {
      float t = i / 1000f;
      float x = 100 + 200 * t - 100 * t * t, y = 100 * t * t;
      float distance = Float.POSITIVE_INFINITY;
      for (int k = 1; k < rendered.size(); k++) {
        NoteDocument.Point a = rendered.get(k - 1), b = rendered.get(k);
        distance = Math.min(distance, InkGeometry.segmentDistance(x, y, a.x, a.y, b.x, b.y));
      }
      assertTrue("Quadratic deviated by " + distance, distance <= InkGeometry.RENDER_ERROR);
    }
    long previous = -1;
    for (NoteDocument.Point point : rendered) {
      assertTrue(point.time >= previous);
      assertTrue(point.pressure >= .2f && point.pressure <= .8f);
      assertTrue(point.tilt >= .1f && point.tilt <= .7f);
      previous = point.time;
    }
    assertEquals(100, rendered.get(0).time);
    assertEquals(2100, rendered.get(rendered.size() - 1).time);
  }

  @Test
  public void linearFragmentIsNeverSmoothedAgainAndExcessiveGeometryIsBounded() {
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    stroke.pathMode = "linear";
    stroke.points.add(new NoteDocument.Point(0, 0, 1, 0, 0));
    stroke.points.add(new NoteDocument.Point(200, 0, 1, 0, 1));
    stroke.points.add(new NoteDocument.Point(200, 100, 1, 0, 2));
    java.util.List<NoteDocument.Point> rendered = InkGeometry.renderedPoints(stroke);
    assertEquals(3, rendered.size());
    assertEquals(200, rendered.get(1).x, 0);
    assertEquals(0, rendered.get(1).y, 0);

    stroke.pathMode = "smooth";
    stroke.points.clear();
    for (int i = 0; i < 1000; i++)
      stroke.points.add(new NoteDocument.Point(i % 2 == 0 ? -1000000 : 1000000, i, 1, 0, i));
    assertThrows(IllegalArgumentException.class, () -> InkGeometry.renderedPoints(stroke));
    assertEquals(1000, stroke.points.size());
  }

  @Test
  public void eraserHitsSparseSegmentBetweenSamples() {
    NoteDocument.Stroke s = new NoteDocument.Stroke();
    s.width = 2;
    s.points.add(new NoteDocument.Point(0, 0, 1, 0, 0));
    s.points.add(new NoteDocument.Point(100, 0, 1, 0, 1));
    assertTrue(InkGeometry.hitStroke(s, 50, 3, 3));
    assertFalse(InkGeometry.hitStroke(s, 50, 12, 3));
  }

  @Test
  public void lassoSelectsCrossingStrokeThenMovesItsOriginalSamples() {
    NoteDocument.Stroke s = new NoteDocument.Stroke();
    s.points.add(new NoteDocument.Point(-10, 5, .4f, .2f, 1));
    s.points.add(new NoteDocument.Point(20, 5, .8f, .3f, 2));
    java.util.List<NoteDocument.Point> box =
        Arrays.asList(
            new NoteDocument.Point(0, 0, 1, 0, 0), new NoteDocument.Point(10, 0, 1, 0, 0),
            new NoteDocument.Point(10, 10, 1, 0, 0), new NoteDocument.Point(0, 10, 1, 0, 0));
    assertTrue(InkGeometry.selectedByLasso(s, box));
    InkGeometry.move(s, 7, 3);
    assertEquals(-3, s.points.get(0).x, 0);
    assertEquals(8, s.points.get(0).y, 0);
    assertEquals(.4f, s.points.get(0).pressure, .0001f);
  }
}
