package app.iko.notes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Paper-space grid used to keep eraser hit testing local on pages with many strokes. */
final class StrokeSpatialIndex {
  private static final float CELL_SIZE = 256f;
  private static final long MAX_STROKE_CELLS = 256;
  private static final long MAX_QUERY_CELLS = 4096;

  private final Map<Long, ArrayList<NoteDocument.Stroke>> cells = new HashMap<>();
  private final IdentityHashMap<NoteDocument.Stroke, long[]> memberships = new IdentityHashMap<>();
  private final IdentityHashMap<NoteDocument.Stroke, float[]> strokeBounds =
      new IdentityHashMap<>();
  private final ArrayList<NoteDocument.Stroke> overflow = new ArrayList<>();
  private final ArrayList<NoteDocument.Stroke> all = new ArrayList<>();
  private final IdentityHashMap<NoteDocument.Stroke, Integer> paintOrder = new IdentityHashMap<>();
  private int nextOrder;

  StrokeSpatialIndex(List<NoteDocument.Stroke> strokes) {
    for (NoteDocument.Stroke stroke : strokes) add(stroke);
  }

  void add(NoteDocument.Stroke stroke) {
    if (stroke == null || memberships.containsKey(stroke)) return;
    all.add(stroke);
    paintOrder.put(stroke, nextOrder++);
    float[] bounds = bounds(stroke);
    strokeBounds.put(stroke, bounds);
    if (bounds == null) {
      memberships.put(stroke, new long[0]);
      return;
    }
    int left = cell(bounds[0]), top = cell(bounds[1]);
    int right = cell(bounds[2]), bottom = cell(bounds[3]);
    long count = ((long) right - left + 1) * ((long) bottom - top + 1);
    if (count > MAX_STROKE_CELLS) {
      overflow.add(stroke);
      memberships.put(stroke, null);
      return;
    }
    long[] keys = new long[(int) count];
    int offset = 0;
    for (int y = top; y <= bottom; y++) {
      for (int x = left; x <= right; x++) {
        long key = key(x, y);
        keys[offset++] = key;
        cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(stroke);
      }
    }
    memberships.put(stroke, keys);
  }

  void remove(NoteDocument.Stroke stroke) {
    if (!memberships.containsKey(stroke)) return;
    long[] keys = memberships.remove(stroke);
    strokeBounds.remove(stroke);
    all.remove(stroke);
    paintOrder.remove(stroke);
    if (keys == null) {
      overflow.remove(stroke);
      return;
    }
    for (long key : keys) {
      ArrayList<NoteDocument.Stroke> bucket = cells.get(key);
      if (bucket == null) continue;
      bucket.remove(stroke);
      if (bucket.isEmpty()) cells.remove(key);
    }
  }

  List<NoteDocument.Stroke> query(float ax, float ay, float bx, float by, float radius) {
    float pad = Math.max(0, radius);
    int left = cell(Math.min(ax, bx) - pad), top = cell(Math.min(ay, by) - pad);
    int right = cell(Math.max(ax, bx) + pad), bottom = cell(Math.max(ay, by) + pad);
    long count = ((long) right - left + 1) * ((long) bottom - top + 1);
    if (count > MAX_QUERY_CELLS) return intersecting(all, ax, ay, bx, by, pad);
    LinkedHashSet<NoteDocument.Stroke> result = new LinkedHashSet<>(overflow);
    for (int y = top; y <= bottom; y++) {
      for (int x = left; x <= right; x++) {
        List<NoteDocument.Stroke> bucket = cells.get(key(x, y));
        if (bucket != null) result.addAll(bucket);
      }
    }
    return intersecting(result, ax, ay, bx, by, pad);
  }

  /** Cached conservative visible bounds, valid until this stroke is removed/replaced. */
  List<NoteDocument.Stroke> queryInPaintOrder(float left, float top, float right, float bottom) {
    List<NoteDocument.Stroke> visible = query(left, top, right, bottom, 0);
    visible.sort(java.util.Comparator.comparingInt(paintOrder::get));
    return visible;
  }

  /** Cached conservative visible bounds, valid until this stroke is removed/replaced. */
  float[] getBounds(NoteDocument.Stroke stroke) {
    return strokeBounds.get(stroke);
  }

  private List<NoteDocument.Stroke> intersecting(
      Iterable<NoteDocument.Stroke> candidates, float ax, float ay, float bx, float by, float pad) {
    float left = Math.min(ax, bx) - pad, top = Math.min(ay, by) - pad;
    float right = Math.max(ax, bx) + pad, bottom = Math.max(ay, by) + pad;
    ArrayList<NoteDocument.Stroke> result = new ArrayList<>();
    for (NoteDocument.Stroke stroke : candidates) {
      float[] b = strokeBounds.get(stroke);
      if (b != null && b[0] <= right && b[2] >= left && b[1] <= bottom && b[3] >= top)
        result.add(stroke);
    }
    return result;
  }

  private static float[] bounds(NoteDocument.Stroke stroke) {
    if (stroke.points.isEmpty()) return null;
    float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
    float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
    float half = stroke.width * .5f;
    for (NoteDocument.Point point : InkGeometry.outline(stroke)) {
      float width =
          point.widthScale > 0 ? stroke.width * point.widthScale : BrushStyle.width(stroke, point);
      half = Math.max(half, width * .5f);
      left = Math.min(left, point.x);
      top = Math.min(top, point.y);
      right = Math.max(right, point.x);
      bottom = Math.max(bottom, point.y);
    }
    if ("arrow".equals(stroke.tool)) half += Math.max(20, stroke.width * 5);
    // Quadratic control points are inside the convex hull of their input points.
    return new float[] {left - half, top - half, right + half, bottom + half};
  }

  private static int cell(float coordinate) {
    return (int) Math.floor(coordinate / CELL_SIZE);
  }

  private static long key(int x, int y) {
    return ((long) x << 32) ^ (y & 0xffffffffL);
  }
}
