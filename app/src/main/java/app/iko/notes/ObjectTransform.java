package app.iko.notes;

import java.util.ArrayList;
import java.util.List;

/** Shared, renderer-independent similarity transforms in paper coordinates. */
public final class ObjectTransform {
  private ObjectTransform() {}

  public static final class Bounds {
    public final float left, top, right, bottom;

    public Bounds(float left, float top, float right, float bottom) {
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
    }

    public float width() {
      return right - left;
    }

    public float height() {
      return bottom - top;
    }

    public float centerX() {
      return (left + right) / 2;
    }

    public float centerY() {
      return (top + bottom) / 2;
    }
  }

  public static float normalizeRotation(float degrees) {
    if (!Float.isFinite(degrees)) return degrees;
    float value = degrees % 360;
    return value < -180 ? value + 360 : value > 180 ? value - 360 : value;
  }

  /** Four clockwise corners, beginning at the element's local top left. */
  public static float[] corners(NoteDocument.Element element) {
    float[] result = {
      element.x,
      element.y,
      element.x + element.width,
      element.y,
      element.x + element.width,
      element.y + element.height,
      element.x,
      element.y + element.height
    };
    float cx = element.x + element.width / 2, cy = element.y + element.height / 2;
    for (int n = 0; n < result.length; n += 2) {
      float x = result[n], y = result[n + 1];
      result[n] = mapX(x, y, cx, cy, 1, element.rotation, 0);
      result[n + 1] = mapY(x, y, cx, cy, 1, element.rotation, 0);
    }
    return result;
  }

  public static Bounds bounds(NoteDocument.Element element) {
    float[] corners = corners(element);
    float left = corners[0], right = left, top = corners[1], bottom = top;
    for (int n = 2; n < corners.length; n += 2) {
      left = Math.min(left, corners[n]);
      top = Math.min(top, corners[n + 1]);
      right = Math.max(right, corners[n]);
      bottom = Math.max(bottom, corners[n + 1]);
    }
    return new Bounds(left, top, right, bottom);
  }

  /** Inverse rotation avoids the empty corners of the axis-aligned bounding rectangle. */
  public static boolean contains(NoteDocument.Element element, float x, float y) {
    float cx = element.x + element.width / 2, cy = element.y + element.height / 2;
    float localX = mapX(x, y, cx, cy, 1, -element.rotation, 0);
    float localY = mapY(x, y, cx, cy, 1, -element.rotation, 0);
    return localX >= element.x
        && localX <= element.x + element.width
        && localY >= element.y
        && localY <= element.y + element.height;
  }

  public static void applyElement(
      NoteDocument.Element source,
      NoteDocument.Element target,
      float pivotX,
      float pivotY,
      float scale,
      float degrees,
      float dx,
      float dy) {
    float cx = source.x + source.width / 2, cy = source.y + source.height / 2;
    float width = source.width * scale, height = source.height * scale;
    target.x = mapX(cx, cy, pivotX, pivotY, scale, degrees, dx) - width / 2;
    target.y = mapY(cx, cy, pivotX, pivotY, scale, degrees, dy) - height / 2;
    target.width = width;
    target.height = height;
    target.rotation = normalizeRotation(source.rotation + degrees);
    target.textSize = "text".equals(source.kind) ? source.textSize * scale : source.textSize;
  }

  public static void applyStroke(
      NoteDocument.Stroke source,
      NoteDocument.Stroke target,
      float pivotX,
      float pivotY,
      float scale,
      float degrees,
      float dx,
      float dy) {
    // Axis-aligned primitives cannot represent an arbitrary rotated rectangle/ellipse. Expand the
    // same outline used by the renderer; freehand samples keep pressure, tilt and fragment style.
    boolean expand =
        InkGeometry.isShape(source.tool)
            && (Math.abs(normalizeRotation(degrees)) > .0001f || GeometryPresets.hasArrow(source.tool));
    List<NoteDocument.Point> points = expand ? InkGeometry.outline(source) : source.points;
    ArrayList<NoteDocument.Point> transformed = new ArrayList<>(points.size());
    for (NoteDocument.Point point : points) {
      NoteDocument.Point copy = point.copy();
      copy.x = mapX(point.x, point.y, pivotX, pivotY, scale, degrees, dx);
      copy.y = mapY(point.x, point.y, pivotX, pivotY, scale, degrees, dy);
      transformed.add(copy);
    }
    target.width = source.width * scale;
    target.points = transformed;
    target.tool = source.tool;
    target.pathMode = source.pathMode;
    if (expand) {
      target.tool = "linepath";
      target.pathMode = "linear";
    }
  }

  public static boolean isValid(NoteDocument.Element element, NoteDocument.Page page) {
    if (element == null
        || page == null
        || !("text".equals(element.kind) || "image".equals(element.kind))
        || !between(element.x, -1000000, 1000000)
        || !between(element.y, -1000000, 1000000)
        || !between(element.width, 1, 40000)
        || !between(element.height, 1, 40000)
        || !between(element.textSize, 4, 500)
        || !between(element.rotation, -360, 360)) return false;
    Bounds bounds = bounds(element);
    return page.infinite || onPage(bounds, page);
  }

  public static boolean isValid(NoteDocument.Stroke stroke, NoteDocument.Page page) {
    if (stroke == null
        || page == null
        || !between(stroke.width, .1f, 200)
        || stroke.points == null
        || stroke.points.size() > 100000) return false;
    for (NoteDocument.Point point : stroke.points)
      if (!between(point.x, -1000000, 1000000) || !between(point.y, -1000000, 1000000))
        return false;
    if (!page.infinite) {
      float pad = stroke.width / 2;
      for (NoteDocument.Point point : InkGeometry.outline(stroke))
        if (point.x - pad < -.01f
            || point.x + pad > page.width + .01f
            || point.y - pad < -.01f
            || point.y + pad > page.height + .01f) return false;
    }
    return true;
  }

  private static boolean onPage(Bounds bounds, NoteDocument.Page page) {
    return bounds.left >= -.01f
        && bounds.top >= -.01f
        && bounds.right <= page.width + .01f
        && bounds.bottom <= page.height + .01f;
  }

  private static boolean between(float value, float min, float max) {
    return Float.isFinite(value) && value >= min && value <= max;
  }

  public static float mapX(
      float x, float y, float pivotX, float pivotY, float scale, float degrees, float dx) {
    double radians = Math.toRadians(degrees);
    return (float)
        (pivotX
            + scale * ((x - pivotX) * Math.cos(radians) - (y - pivotY) * Math.sin(radians))
            + dx);
  }

  public static float mapY(
      float x, float y, float pivotX, float pivotY, float scale, float degrees, float dy) {
    double radians = Math.toRadians(degrees);
    return (float)
        (pivotY
            + scale * ((x - pivotX) * Math.sin(radians) + (y - pivotY) * Math.cos(radians))
            + dy);
  }
}
