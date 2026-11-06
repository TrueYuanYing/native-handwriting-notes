package app.iko.notes;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class StrokeEraserTest {
  @Test
  public void eraserCutsTheVisibleMidpointCurveRatherThanItsControlPolygon() {
    NoteDocument.Stroke source = stroke(0, 0, 200, 0);
    source.points.add(new NoteDocument.Point(200, 100, .8f, .7f, 2100));
    List<NoteDocument.Stroke> result = StrokeEraser.erase(source, 175, 25, 175, 25, 2);

    assertEquals(2, result.size());
    assertNotSame(source, result.get(0));
    assertEquals("linear", result.get(0).pathMode);
    assertTrue(last(result.get(0)).x < 175);
    assertTrue(result.get(1).points.get(0).y > 25);
    for (NoteDocument.Stroke fragment : result) {
      for (int i = 1; i < fragment.points.size(); i++) {
        NoteDocument.Point a = fragment.points.get(i - 1), b = fragment.points.get(i);
        assertTrue(InkGeometry.segmentDistance(175, 25, a.x, a.y, b.x, b.y) >= 2.99f);
        // Invert an exact coordinate of the known final quadratic independently of subdivision.
        float x = (a.x + b.x) / 2, y = (a.y + b.y) / 2;
        if (x <= 100) assertEquals(0, y, .0001f);
        else {
          double t = y < 25 ? 1 - Math.sqrt((200 - x) / 100) : Math.sqrt(y / 100);
          double expectedX = 100 + 200 * t - 100 * t * t, expectedY = 100 * t * t;
          assertTrue(Math.hypot(x - expectedX, y - expectedY) <= InkGeometry.RENDER_ERROR);
        }
      }
    }
    assertEquals(3, source.points.size());
  }

  @Test
  public void secondEraseKeepsSurvivingCurveGeometryInsteadOfSmoothingItAgain() {
    NoteDocument.Stroke source = stroke(0, 0, 200, 0);
    source.points.add(new NoteDocument.Point(200, 100, .8f, .7f, 2100));
    List<NoteDocument.Stroke> first = StrokeEraser.erase(source, 175, 25, 175, 25, 2);
    NoteDocument.Stroke retained = first.get(1);
    int originalSize = retained.points.size();
    NoteDocument.Point originalFirst = retained.points.get(0).copy();
    assertEquals(originalSize, InkGeometry.renderedPoints(retained).size());
    assertSame(retained, StrokeEraser.erase(retained, 10, 90, 10, 90, 1).get(0));

    List<NoteDocument.Stroke> second = StrokeEraser.erase(retained, 196, 64, 196, 64, 2);
    assertEquals(2, second.size());
    assertEquals("linear", second.get(0).pathMode);
    assertEquals("linear", second.get(1).pathMode);
    assertEquals(originalFirst.x, second.get(0).points.get(0).x, 0);
    assertEquals(originalFirst.y, second.get(0).points.get(0).y, 0);
    assertEquals(originalSize, retained.points.size());
    assertEquals("smooth", source.pathMode);
    assertSame(source, StrokeEraser.erase(source, 200, 0, 200, 0, 2).get(0));
  }

  @Test
  public void sparseStrokeIsCutBetweenSamplesWithoutJoiningTheGap() {
    NoteDocument.Stroke source = stroke(0, 0, 100, 0);
    List<NoteDocument.Stroke> result = StrokeEraser.erase(source, 50, -20, 50, 20, 10);

    assertEquals(2, result.size());
    assertEquals(0, result.get(0).points.get(0).x, .001f);
    assertEquals(39, last(result.get(0)).x, .001f);
    assertEquals(61, result.get(1).points.get(0).x, .001f);
    assertEquals(100, last(result.get(1)).x, .001f);
    assertNotEquals(source.id, result.get(0).id);
    assertNotEquals(result.get(0).id, result.get(1).id);
    assertEquals(2, source.points.size());
    assertEquals(100, source.points.get(1).x, 0);
  }

  @Test
  public void smallEraserStillCutsLongSegmentsWithoutLosingNumericalPrecision() {
    NoteDocument.Stroke source = stroke(-1_000_000, 0, 1_000_000, 0);
    source.width = .1f;
    List<NoteDocument.Stroke> result = StrokeEraser.erase(source, 0, 0, 0, 0, .1f);

    assertEquals(2, result.size());
    assertEquals(-.15f, last(result.get(0)).x, .000001f);
    assertEquals(.15f, result.get(1).points.get(0).x, .000001f);
  }

  @Test
  public void diagonalSweepClipsItsRoundEndAndAngledBodyInEitherDirection() {
    NoteDocument.Stroke source = stroke(-10, 0, 20, 0);
    List<NoteDocument.Stroke> forward = StrokeEraser.erase(source, 0, 0, 10, 10, 1);
    List<NoteDocument.Stroke> reverse = StrokeEraser.erase(source, 10, 10, 0, 0, 1);

    assertEquals(2, forward.size());
    assertEquals(-2, last(forward.get(0)).x, .00001f);
    assertEquals(2.8284271f, forward.get(1).points.get(0).x, .00001f);
    assertEquals(last(forward.get(0)).x, last(reverse.get(0)).x, .00001f);
    assertEquals(forward.get(1).points.get(0).x, reverse.get(1).points.get(0).x, .00001f);
  }

  @Test
  public void missedStrokeAndSinglePointTangencyKeepTheOriginalIdentity() {
    NoteDocument.Stroke missed = stroke(0, 0, 100, 0);
    assertSame(missed, StrokeEraser.erase(missed, -20, -5, -20, 5, 1).get(0));
    NoteDocument.Stroke tangent = stroke(-10, 2, 10, 2);
    assertSame(tangent, StrokeEraser.erase(tangent, 0, 0, 0, 0, 1).get(0));
    assertEquals(2, tangent.points.size());
  }

  @Test
  public void survivingHighlighterKeepsItsStyleAndInterpolatedSamplesWithoutAliasing() {
    NoteDocument.Stroke source = stroke(0, 0, 100, 0);
    source.tool = "highlighter";
    source.color = 0x408899aa;
    source.pressureMode = "soft";
    source.pressureMin = .1f;
    source.pressureMax = 1.5f;
    List<NoteDocument.Stroke> result = StrokeEraser.erase(source, 50, -20, 50, 20, 10);

    NoteDocument.Stroke left = result.get(0);
    assertEquals("highlighter", left.tool);
    assertEquals(0x408899aa, left.color);
    assertEquals(2, left.width, 0);
    assertEquals("soft", left.pressureMode);
    assertEquals(.1f, left.pressureMin, 0);
    assertEquals(1.5f, left.pressureMax, 0);
    assertEquals(.434f, last(left).pressure, .00001f);
    assertEquals(.334f, last(left).tilt, .00001f);
    assertEquals(490, last(left).time);
    assertEquals(710, result.get(1).points.get(0).time);
    left.points.get(0).x = 7;
    assertEquals(0, source.points.get(0).x, 0);
  }

  @Test
  public void fullyCoveredStrokeAndVisibleDotDisappearWhileMissedDotSurvives() {
    NoteDocument.Stroke source = stroke(0, 0, 100, 0);
    assertTrue(StrokeEraser.erase(source, 0, 0, 100, 0, 2).isEmpty());

    NoteDocument.Stroke dot = stroke(5, 5, 5, 5);
    dot.points.remove(1);
    assertTrue(StrokeEraser.erase(dot, 5, 5, 5, 5, 1).isEmpty());
    assertSame(dot, StrokeEraser.erase(dot, 10, 10, 10, 10, 1).get(0));
    assertEquals(1, dot.points.size());
  }

  @Test
  public void partiallyErasedRectangleRetainsItsCornersAsFixedWidthPolyline() {
    NoteDocument.Stroke source = stroke(0, 0, 100, 100);
    source.tool = "rectangle";
    source.pressureMode = "firm";
    List<NoteDocument.Stroke> result = StrokeEraser.erase(source, 50, -20, 50, 20, 10);

    assertEquals(2, result.size());
    assertEquals("linepath", result.get(1).tool);
    assertEquals("off", result.get(1).pressureMode);
    assertEquals(2, result.get(1).width, 0);
    assertEquals(61, result.get(1).points.get(0).x, .001f);
    assertEquals(100, result.get(1).points.get(1).x, .001f);
    assertEquals(0, result.get(1).points.get(1).y, .001f);
    assertEquals(100, result.get(1).points.get(2).y, .001f);
    assertEquals("rectangle", source.tool);
    assertEquals("firm", source.pressureMode);
    assertEquals(2, source.points.size());
  }

  @Test
  public void sweepAcrossRepeatedPointsLeavesOnlyTheTwoDisconnectedOuterPieces() {
    NoteDocument.Stroke source = stroke(0, 0, 50, 0);
    source.points.add(new NoteDocument.Point(50, 0, .8f, .7f, 1200));
    source.points.add(new NoteDocument.Point(100, 0, .8f, .7f, 2200));
    List<NoteDocument.Stroke> result = StrokeEraser.erase(source, 50, 0, 50, 0, 10);

    assertEquals(2, result.size());
    assertEquals(39, last(result.get(0)).x, .001f);
    assertEquals(61, result.get(1).points.get(0).x, .001f);
    assertEquals(100, last(result.get(1)).x, .001f);
  }

  private static NoteDocument.Stroke stroke(float ax, float ay, float bx, float by) {
    NoteDocument.Stroke source = new NoteDocument.Stroke();
    source.tool = "ballpoint";
    source.width = 2;
    source.pressureMode = "off";
    source.points.add(new NoteDocument.Point(ax, ay, .2f, .1f, 100));
    source.points.add(new NoteDocument.Point(bx, by, .8f, .7f, 1100));
    return source;
  }

  private static NoteDocument.Point last(NoteDocument.Stroke stroke) {
    return stroke.points.get(stroke.points.size() - 1);
  }
}
