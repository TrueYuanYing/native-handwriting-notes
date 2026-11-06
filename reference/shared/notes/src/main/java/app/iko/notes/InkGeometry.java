package app.iko.notes;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry shared by drawing, whole-stroke erasure and lasso selection. */
public final class InkGeometry {
  /** Maximum paper-space centerline error, including float-coordinate rounding. */
  public static final float RENDER_ERROR = .2f;

  public static final int MAX_RENDERED_POINTS = 100000;
  private static final double CURVE_FLATNESS = .1;

  private InkGeometry() {}

  public static float segmentDistance(float x, float y, float ax, float ay, float bx, float by) {
    float dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
    float t = length == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / length));
    return (float) Math.hypot(x - ax - t * dx, y - ay - t * dy);
  }

  public static boolean hitStroke(NoteDocument.Stroke s, float x, float y, float radius) {
    return hitStroke(s, x, y, x, y, radius);
  }

  public static boolean hitStroke(
      NoteDocument.Stroke s, float ax, float ay, float bx, float by, float radius) {
    List<NoteDocument.Point> points = renderedPoints(s);
    boolean shape = isShape(s.tool) || "linepath".equals(s.tool);
    if (points.size() == 1) {
      NoteDocument.Point point = points.get(0);
      float hit = radius + (shape ? s.width : BrushStyle.width(s, point)) / 2;
      return segmentDistance(point.x, point.y, ax, ay, bx, by) <= hit;
    }
    for (int i = 1; i < points.size(); i++) {
      NoteDocument.Point a = points.get(i - 1), b = points.get(i);
      float width =
          shape
              ? s.width
              : b.widthScale > 0
                  ? s.width * b.widthScale
                  : Math.max(BrushStyle.width(s, a), BrushStyle.width(s, b));
      if (segmentSegmentDistance(ax, ay, bx, by, a.x, a.y, b.x, b.y) <= radius + width / 2)
        return true;
    }
    return false;
  }

  public static float segmentSegmentDistance(
      float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy) {
    if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0;
    return Math.min(
        Math.min(segmentDistance(ax, ay, cx, cy, dx, dy), segmentDistance(bx, by, cx, cy, dx, dy)),
        Math.min(segmentDistance(cx, cy, ax, ay, bx, by), segmentDistance(dx, dy, ax, ay, bx, by)));
  }

  private static boolean segmentsIntersect(
      float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy) {
    float a = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    float b = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax);
    float c = (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx);
    float d = (dx - cx) * (by - cy) - (dy - cy) * (bx - cx);
    return ((a <= 0 && b >= 0) || (a >= 0 && b <= 0))
        && ((c <= 0 && d >= 0) || (c >= 0 && d <= 0));
  }

  public static boolean isShape(String tool) {
    return GeometryPresets.contains(tool);
  }

  public static boolean inside(float x, float y, List<NoteDocument.Point> polygon) {
    boolean inside = false;
    for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
      NoteDocument.Point a = polygon.get(i), b = polygon.get(j);
      if (segmentDistance(x, y, a.x, a.y, b.x, b.y) < .001f) return true;
      if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x)
        inside = !inside;
    }
    return inside;
  }

  public static boolean selectedByLasso(
      NoteDocument.Stroke stroke, List<NoteDocument.Point> polygon) {
    if (polygon.size() < 3) return false;
    List<NoteDocument.Point> points = outline(stroke);
    for (NoteDocument.Point p : points) if (inside(p.x, p.y, polygon)) return true;
    for (int i = 1; i < points.size(); i++) {
      NoteDocument.Point a = points.get(i - 1), b = points.get(i);
      for (int k = 0; k < polygon.size(); k++)
        if (intersect(a, b, polygon.get(k), polygon.get((k + 1) % polygon.size()))) return true;
    }
    return false;
  }

  public static boolean selectedByLasso(NoteDocument.Element e, List<NoteDocument.Point> polygon) {
    NoteDocument.Stroke rect = new NoteDocument.Stroke();
    rect.tool = "linepath";
    float[] corners = ObjectTransform.corners(e);
    for (int n = 0; n <= 4; n++)
      rect.points.add(new NoteDocument.Point(corners[(n % 4) * 2], corners[(n % 4) * 2 + 1], 1, 0, 0));
    if (selectedByLasso(rect, polygon)) return true;
    for (NoteDocument.Point p : polygon)
      if (ObjectTransform.contains(e, p.x, p.y)) return true;
    return false;
  }

  public static void move(NoteDocument.Stroke stroke, float dx, float dy) {
    for (NoteDocument.Point p : stroke.points) {
      p.x += dx;
      p.y += dy;
    }
  }

  /**
   * The actual midpoint-quadratic centerline used by PageRenderer. Subdivision is selected per
   * quadratic from its second difference, with a .1 paper-unit analytic bound and headroom for
   * float rounding. Linear fragments are returned directly, so repeated erasure never smooths them
   * again.
   */
  public static List<NoteDocument.Point> renderedPoints(NoteDocument.Stroke stroke) {
    if (stroke.points.size() < 2
        || isShape(stroke.tool)
        || "linepath".equals(stroke.tool)
        || "linear".equals(stroke.pathMode)) return outline(stroke);
    ArrayList<NoteDocument.Point> out = new ArrayList<>();
    out.add(stroke.points.get(0).copy());
    for (int i = 1; i < stroke.points.size(); i++) {
      NoteDocument.Point control = stroke.points.get(i - 1), next = stroke.points.get(i);
      NoteDocument.Point start = i > 1 ? midpoint(stroke.points.get(i - 2), control) : control;
      NoteDocument.Point end = i < stroke.points.size() - 1 ? midpoint(control, next) : next;
      double deviation =
          Math.hypot(start.x - 2d * control.x + end.x, start.y - 2d * control.y + end.y) / 4;
      // A monotonic straight quadratic needs no subdivision despite its nonuniform parameter speed.
      if (segmentDistance(control.x, control.y, start.x, start.y, end.x, end.y) == 0) deviation = 0;
      int pieces = 1;
      while (deviation > CURVE_FLATNESS) {
        pieces *= 2;
        deviation /= 4;
      }
      if (out.size() + pieces > MAX_RENDERED_POINTS)
        throw new IllegalArgumentException("曲线采样点超出容量，请拆分笔迹后继续操作");
      float widthScale =
          (BrushStyle.width(stroke, control) + BrushStyle.width(stroke, next)) / (2 * stroke.width);
      int baseAlpha = (stroke.color >>> 24) & 255;
      float alphaScale =
          baseAlpha == 0
              ? 1
              : ((BrushStyle.alpha(stroke, control) + BrushStyle.alpha(stroke, next)) / 2)
                  / (float) baseAlpha;
      for (int step = 1; step <= pieces; step++) {
        double t = (double) step / pieces, u = 1 - t;
        NoteDocument.Point sample =
            new NoteDocument.Point(
                (float) (u * u * start.x + 2 * u * t * control.x + t * t * end.x),
                (float) (u * u * start.y + 2 * u * t * control.y + t * t * end.y),
                (float)
                    (u * u * start.pressure + 2 * u * t * control.pressure + t * t * end.pressure),
                (float) (u * u * start.tilt + 2 * u * t * control.tilt + t * t * end.tilt),
                Math.round(u * u * start.time + 2 * u * t * control.time + t * t * end.time));
        sample.widthScale = widthScale;
        sample.alphaScale = alphaScale;
        sample.styleRun = i;
        out.add(sample);
      }
    }
    return out;
  }

  private static NoteDocument.Point midpoint(NoteDocument.Point a, NoteDocument.Point b) {
    return new NoteDocument.Point(
        (a.x + b.x) / 2,
        (a.y + b.y) / 2,
        (a.pressure + b.pressure) / 2,
        (a.tilt + b.tilt) / 2,
        Math.round(a.time * .5 + b.time * .5));
  }

  private static float cross(NoteDocument.Point a, NoteDocument.Point b, NoteDocument.Point p) {
    return (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x);
  }

  private static boolean intersect(
      NoteDocument.Point a, NoteDocument.Point b, NoteDocument.Point c, NoteDocument.Point d) {
    if (Math.max(a.x, b.x) < Math.min(c.x, d.x)
        || Math.max(c.x, d.x) < Math.min(a.x, b.x)
        || Math.max(a.y, b.y) < Math.min(c.y, d.y)
        || Math.max(c.y, d.y) < Math.min(a.y, b.y)) return false;
    return cross(a, b, c) * cross(a, b, d) <= 0 && cross(c, d, a) * cross(c, d, b) <= 0;
  }

  /** Shapes are stored as endpoints, and expanded consistently for hit testing. */
  public static List<NoteDocument.Point> outline(NoteDocument.Stroke s) {
    if (!isShape(s.tool) || s.points.size() < 2) return s.points;
    NoteDocument.Point a = s.points.get(0), b = s.points.get(s.points.size() - 1);
    return GeometryPresets.outline(s.tool, a, b, s.width);
  }
}
