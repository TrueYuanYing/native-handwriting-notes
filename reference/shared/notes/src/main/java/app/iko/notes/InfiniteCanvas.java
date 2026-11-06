package app.iko.notes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/** Infinite space has no backing sheet. Only the clipped viewport receives a paper pattern. */
final class InfiniteCanvas {
  private static final ThreadLocal<float[]> DOTS = ThreadLocal.withInitial(() -> new float[65536]);

  static RectF contentBounds(NoteDocument.Page page) {
    RectF bounds = new RectF();
    boolean found = false;
    if (page.backgroundPath != null && !page.backgroundPath.isEmpty()) {
      bounds.set(0, 0, page.width, page.height);
      found = true;
    }
    for (NoteDocument.Stroke stroke : page.strokes)
      for (NoteDocument.Point point : InkGeometry.outline(stroke)) {
        float pad =
            Math.max(
                stroke.width * 2 + 32, "arrow".equals(stroke.tool) ? stroke.width * 6 + 32 : 0);
        if (!found) {
          bounds.set(point.x - pad, point.y - pad, point.x + pad, point.y + pad);
          found = true;
        } else bounds.union(point.x - pad, point.y - pad, point.x + pad, point.y + pad);
      }
    for (NoteDocument.Element element : page.elements) {
      ObjectTransform.Bounds elementBounds = ObjectTransform.bounds(element);
      if (!found) {
        bounds.set(
            elementBounds.left - 32,
            elementBounds.top - 32,
            elementBounds.right + 32,
            elementBounds.bottom + 32);
        found = true;
      } else
        bounds.union(
            elementBounds.left - 32,
            elementBounds.top - 32,
            elementBounds.right + 32,
            elementBounds.bottom + 32);
    }
    if (!found) bounds.set(-page.width / 2, -page.height / 2, page.width / 2, page.height / 2);
    if (bounds.width() < 100) bounds.inset(-(100 - bounds.width()) / 2, 0);
    if (bounds.height() < 100) bounds.inset(0, -(100 - bounds.height()) / 2);
    return bounds;
  }

  static void drawPaper(Canvas canvas, NoteDocument.Page page) {
    canvas.drawColor(page.color);
    if ("blank".equals(page.paper)) return;
    Rect clip = canvas.getClipBounds();
    float spacing = page.lineSpacing > 0 ? page.lineSpacing : 40;
    int densityLimit = "lined".equals(page.paper) || "grid".equals(page.paper) ? 160 : 96;
    while (clip.width() / spacing > densityLimit || clip.height() / spacing > densityLimit)
      spacing *= 2;
    Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    ink.setColor(
        (android.graphics.Color.red(page.color)
                    + android.graphics.Color.green(page.color)
                    + android.graphics.Color.blue(page.color))
                < 380
            ? 0x44889aa8
            : 0x33919aaa);
    ink.setStrokeWidth(Math.max(.7f, spacing / 80));
    float left = (float) Math.floor(clip.left / spacing) * spacing,
        top = (float) Math.floor(clip.top / spacing) * spacing;
    boolean lines = "lined".equals(page.paper) || "grid".equals(page.paper);
    if (!lines) {
      float[] points = DOTS.get();
      int count = 0;
      for (float y = top; y <= clip.bottom; y += spacing)
        for (float x = left; x <= clip.right; x += spacing) {
          points[count++] = x;
          points[count++] = y;
        }
      ink.setStrokeCap(Paint.Cap.ROUND);
      ink.setStrokeWidth(2 * Math.max(.8f, spacing / 38));
      canvas.drawPoints(points, 0, count, ink);
      return;
    }
    for (float y = top; y <= clip.bottom; y += spacing) {
      if (lines) canvas.drawLine(clip.left, y, clip.right, y, ink);
      else
        for (float x = left; x <= clip.right; x += spacing)
          canvas.drawCircle(x, y, Math.max(.8f, spacing / 38), ink);
    }
    if ("grid".equals(page.paper))
      for (float x = left; x <= clip.right; x += spacing)
        canvas.drawLine(x, clip.top, x, clip.bottom, ink);
  }
}
