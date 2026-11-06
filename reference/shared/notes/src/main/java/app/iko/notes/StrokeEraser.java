package app.iko.notes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure, analytic clipping of sampled ink against a swept circular eraser. */
public final class StrokeEraser {
  private static final double EPS = 1e-10;

  private StrokeEraser() {}

  /**
   * Return independent retained fragments, or the original reference when nothing was erased.
   * Radius is in paper units. Each sample segment uses the larger of its endpoint brush widths, so
   * the eraser also removes visible ink whose centerline lies just outside the circular tip.
   */
  public static List<NoteDocument.Stroke> erase(
      NoteDocument.Stroke source, float ax, float ay, float bx, float by, float radius) {
    return erase(source, ax, ay, bx, by, radius, InkGeometry.renderedPoints(source));
  }

  static List<NoteDocument.Stroke> erase(
      NoteDocument.Stroke source,
      float ax,
      float ay,
      float bx,
      float by,
      float radius,
      List<NoteDocument.Point> points) {
    if (!Float.isFinite(ax)
        || !Float.isFinite(ay)
        || !Float.isFinite(bx)
        || !Float.isFinite(by)
        || !Float.isFinite(radius)
        || radius < 0) throw new IllegalArgumentException("Invalid eraser sweep");
    if (points.isEmpty()) return Collections.singletonList(source);
    boolean shape = InkGeometry.isShape(source.tool);
    if (points.size() == 1) {
      NoteDocument.Point point = points.get(0);
      double hitRadius = radius + visibleWidth(source, point, shape) / 2;
      return InkGeometry.segmentDistance(point.x, point.y, ax, ay, bx, by) <= hitRadius
          ? Collections.emptyList()
          : Collections.singletonList(source);
    }

    ArrayList<NoteDocument.Stroke> fragments = new ArrayList<>();
    ArrayList<NoteDocument.Point> current = new ArrayList<>();
    current.add(points.get(0));
    boolean changed = false;
    double sweepLeft = Math.min(ax, bx), sweepRight = Math.max(ax, bx);
    double sweepTop = Math.min(ay, by), sweepBottom = Math.max(ay, by);
    for (int i = 1; i < points.size(); i++) {
      NoteDocument.Point a = points.get(i - 1), b = points.get(i);
      if (current.isEmpty()) current.add(a);
      double hitRadius =
          radius
              + (b.widthScale > 0
                      ? source.width * b.widthScale
                      : Math.max(visibleWidth(source, a, shape), visibleWidth(source, b, shape)))
                  / 2;
      // Most sampled segments of a long stroke are far from this local sweep. Reject them before
      // allocating intersection intervals or evaluating circle quadratics; retain the exact same
      // analytic capsule test and interpolation for every potentially visible hit.
      if (Math.max(a.x, b.x) < sweepLeft - hitRadius
          || Math.min(a.x, b.x) > sweepRight + hitRadius
          || Math.max(a.y, b.y) < sweepTop - hitRadius
          || Math.min(a.y, b.y) > sweepBottom + hitRadius) {
        append(current, b);
        continue;
      }
      double[] cut = capsuleIntersection(a, b, ax, ay, bx, by, hitRadius);
      if (cut == null) {
        append(current, b);
        continue;
      }
      changed = true;
      if (cut[0] > EPS) append(current, interpolate(a, b, cut[0]));
      finish(source, shape, current, fragments);
      current = new ArrayList<>();
      if (cut[1] < 1 - EPS) {
        append(current, interpolate(a, b, cut[1]));
        append(current, b);
      }
    }
    if (!changed) return Collections.singletonList(source);
    finish(source, shape, current, fragments);
    return fragments;
  }

  private static float visibleWidth(
      NoteDocument.Stroke source, NoteDocument.Point point, boolean shape) {
    return shape || "linepath".equals(source.tool) ? source.width : BrushStyle.width(source, point);
  }

  private static void append(List<NoteDocument.Point> points, NoteDocument.Point next) {
    if (!points.isEmpty()) {
      NoteDocument.Point previous = points.get(points.size() - 1);
      if (previous.x == next.x
          && previous.y == next.y
          && previous.pressure == next.pressure
          && previous.tilt == next.tilt
          && previous.widthScale == next.widthScale
          && previous.alphaScale == next.alphaScale
          && previous.styleRun == next.styleRun
          && previous.time == next.time) return;
    }
    points.add(next);
  }

  private static void finish(
      NoteDocument.Stroke source,
      boolean shape,
      List<NoteDocument.Point> points,
      List<NoteDocument.Stroke> fragments) {
    if (points.size() < 2) return;
    NoteDocument.Stroke fragment = new NoteDocument.Stroke();
    fragment.tool = shape ? "linepath" : source.tool;
    fragment.pathMode = "linear";
    fragment.color = source.color;
    fragment.width = source.width;
    fragment.pressureMode = shape ? "off" : source.pressureMode;
    fragment.brushVersion = source.brushVersion;
    fragment.pressureMin = source.pressureMin;
    fragment.pressureMax = source.pressureMax;
    for (NoteDocument.Point point : points) fragment.points.add(point.copy());
    fragments.add(fragment);
  }

  private static NoteDocument.Point interpolate(
      NoteDocument.Point a, NoteDocument.Point b, double t) {
    NoteDocument.Point point =
        new NoteDocument.Point(
            (float) (a.x + (b.x - (double) a.x) * t),
            (float) (a.y + (b.y - (double) a.y) * t),
            (float) (a.pressure + (b.pressure - (double) a.pressure) * t),
            (float) (a.tilt + (b.tilt - (double) a.tilt) * t),
            Math.round((1 - t) * a.time + t * b.time));
    // Every subsegment of one rendered quadratic keeps that quadratic's original style.
    point.widthScale = b.widthScale;
    point.alphaScale = b.alphaScale;
    point.styleRun = b.styleRun;
    return point;
  }

  /** A capsule is the union of its rectangular body and circular ends; it is convex. */
  private static double[] capsuleIntersection(
      NoteDocument.Point a,
      NoteDocument.Point b,
      double ax,
      double ay,
      double bx,
      double by,
      double radius) {
    double[] interval = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
    double vx = b.x - (double) a.x, vy = b.y - (double) a.y;
    double ex = bx - ax, ey = by - ay;
    double length = Math.hypot(ex, ey);
    circleInterval(interval, a.x - ax, a.y - ay, vx, vy, radius);
    if (length > EPS) {
      circleInterval(interval, a.x - bx, a.y - by, vx, vy, radius);
      double ux = ex / length, uy = ey / length;
      double[] body = {0, 1};
      if (slab(body, (a.x - ax) * ux + (a.y - ay) * uy, vx * ux + vy * uy, 0, length)
          && slab(body, -(a.x - ax) * uy + (a.y - ay) * ux, -vx * uy + vy * ux, -radius, radius))
        merge(interval, body[0], body[1]);
    }
    // A tangent of zero length removes no visible path and must not create a spurious break.
    return interval[1] - interval[0] > EPS ? interval : null;
  }

  private static void circleInterval(
      double[] interval, double x, double y, double vx, double vy, double radius) {
    double quadratic = vx * vx + vy * vy;
    double constant = x * x + y * y - radius * radius;
    if (quadratic <= EPS) {
      if (constant <= 0) merge(interval, 0, 1);
      return;
    }
    double center = -(x * vx + y * vy) / quadratic;
    double cross = x * vy - y * vx;
    double remaining = radius * radius - cross * cross / quadratic;
    if (remaining < 0) return;
    double extent = Math.sqrt(remaining / quadratic);
    merge(interval, center - extent, center + extent);
  }

  private static boolean slab(
      double[] interval, double start, double delta, double low, double high) {
    if (Math.abs(delta) <= EPS) return start >= low && start <= high;
    double a = (low - start) / delta, b = (high - start) / delta;
    interval[0] = Math.max(interval[0], Math.min(a, b));
    interval[1] = Math.min(interval[1], Math.max(a, b));
    return interval[0] <= interval[1];
  }

  private static void merge(double[] interval, double low, double high) {
    low = Math.max(0, low);
    high = Math.min(1, high);
    if (low > high) return;
    interval[0] = Math.min(interval[0], low);
    interval[1] = Math.max(interval[1], high);
  }
}
