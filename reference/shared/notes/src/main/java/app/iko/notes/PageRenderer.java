package app.iko.notes;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.graphics.text.LineBreaker;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;

/** All drawing is in document coordinates. Paper and texture artwork is procedural and original. */
public final class PageRenderer {
  private static final PdfDetailCache PDF_DETAILS = new PdfDetailCache();
  // Three visible/adjacent 2048px square pages fit without evict/reload oscillation.
  private static final int CACHE_BYTES = 48 * 1024 * 1024;
  private static final PageMediaCache<MediaKey, Bitmap> MEDIA =
      new PageMediaCache<>(
          CACHE_BYTES,
          Executors.newSingleThreadExecutor(),
          key -> decode(key.path, key.pdfPage),
          Bitmap::getAllocationByteCount);

  /** Cancel when a page leaves the visible/prefetch range or its view is detached. */
  public interface MediaRequest {
    void cancel();
  }

  private static final class MediaKey {
    final String path;
    final int pdfPage;

    MediaKey(String path, int pdfPage) {
      this.path = path;
      this.pdfPage = pdfPage;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof MediaKey)) return false;
      MediaKey key = (MediaKey) other;
      return pdfPage == key.pdfPage && path.equals(key.path);
    }

    @Override
    public int hashCode() {
      return 31 * path.hashCode() + pdfPage;
    }
  }

  private static final class InkTools {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
  }

  private static final ThreadLocal<InkTools> INK_TOOLS = ThreadLocal.withInitial(InkTools::new);

  private PageRenderer() {}

  public static void draw(Canvas canvas, NoteDocument.Page page, boolean includePaper) {
    int save = canvas.save();
    if (page.infinite) canvas.clipRect(InfiniteCanvas.contentBounds(page));
    else canvas.clipRect(0, 0, page.width, page.height);
    drawBackground(canvas, page, includePaper);
    drawForeground(canvas, page);
    canvas.restoreToCount(save);
  }

  public static void drawBackground(Canvas canvas, NoteDocument.Page page, boolean includePaper) {
    drawBackground(canvas, page, includePaper, page);
  }

  static void drawBackground(
      Canvas canvas, NoteDocument.Page page, boolean includePaper, Object viewportOwner) {
    if (includePaper) drawPaper(canvas, page);
    if (page.backgroundPath != null && !page.backgroundPath.isEmpty()) {
      Bitmap background = media(page.backgroundPath, page.pdfPage);
      if (background != null) {
        int imageLayer =
            page.pdfPage < 0 && background.hasAlpha()
                ? canvas.saveLayer(0, 0, page.width, page.height, null)
                : -1;
        canvas.drawBitmap(
            background, null, new RectF(0, 0, page.width, page.height), bitmapPaint());
        PDF_DETAILS.draw(
            canvas,
            page,
            viewportOwner,
            Math.min(background.getWidth() / page.width, background.getHeight() / page.height));
        if (imageLayer >= 0) canvas.restoreToCount(imageLayer);
      } else
        unavailable(
            canvas,
            20,
            50,
            MEDIA.failed(new MediaKey(page.backgroundPath, page.pdfPage))
                ? "资料暂时无法载入，重新打开此页可重试"
                : "资料载入中…");
    }
  }

  static void releaseBackground(Object viewportOwner) {
    PDF_DETAILS.release(viewportOwner);
  }

  public static void drawForeground(Canvas canvas, NoteDocument.Page page) {
    drawElements(canvas, page);
    for (NoteDocument.Stroke stroke : page.strokes) drawInk(canvas, stroke);
    drawTapes(canvas, page, null);
  }

  static void drawTapes(Canvas canvas, NoteDocument.Page page, NoteDocument.Element excluded) {
    for (NoteDocument.Element element : page.elements) {
      if (!"tape".equals(element.kind) || element == excluded) continue;
      if (element.rotation == 0) {
        if (!canvas.quickReject(
            element.x,
            element.y,
            element.x + element.width,
            element.y + element.height,
            Canvas.EdgeType.AA)) drawElement(canvas, element);
        continue;
      }
      ObjectTransform.Bounds bounds = ObjectTransform.bounds(element);
      if (!canvas.quickReject(
          bounds.left, bounds.top, bounds.right, bounds.bottom, Canvas.EdgeType.AA))
        drawElement(canvas, element);
    }
  }

  static void drawElements(Canvas canvas, NoteDocument.Page page) {
    drawElements(canvas, page, null);
  }

  static void drawElements(Canvas canvas, NoteDocument.Page page, NoteDocument.Element excluded) {
    drawElements(canvas, page, excluded, page);
  }

  static void drawElements(
      Canvas canvas, NoteDocument.Page page, NoteDocument.Element excluded, Object viewportOwner) {
    if (page.elements.isEmpty()) {
      PDF_DETAILS.retainImages(viewportOwner, java.util.Collections.emptySet());
      return;
    }
    java.util.Set<NoteDocument.Element> visibleImages = new java.util.HashSet<>();
    for (NoteDocument.Element element : page.elements) {
      if (element == excluded || "tape".equals(element.kind)) continue;
      ObjectTransform.Bounds bounds = ObjectTransform.bounds(element);
      if (!canvas.quickReject(
          bounds.left, bounds.top, bounds.right, bounds.bottom, Canvas.EdgeType.AA)) {
        if ("image".equals(element.kind)) visibleImages.add(element);
        drawElement(canvas, element, viewportOwner);
      }
    }
    PDF_DETAILS.retainImages(viewportOwner, visibleImages);
  }

  public static void drawPaper(Canvas canvas, NoteDocument.Page page) {
    drawPaper(canvas, page, 1);
  }

  /** Same paper geometry; thumbnail hairlines remain visible after downscaling. */
  static void drawPaper(Canvas canvas, NoteDocument.Page page, float lineWeight) {
    if (page.infinite) {
      InfiniteCanvas.drawPaper(canvas, page);
      return;
    }
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(page.color);
    canvas.drawRect(0, 0, page.width, page.height, p);
    drawTexture(canvas, page, p);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(lineWeight);
    boolean darkPaper =
        Color.red(page.color) + Color.green(page.color) + Color.blue(page.color) < 330;
    p.setColor(darkPaper ? Color.argb(100, 165, 182, 202) : Color.argb(43, 89, 104, 121));
    float w = page.width,
        h = page.height,
        margin = Math.min(70, Math.min(w, h) * .07f),
        step = page.lineSpacing > 0 ? page.lineSpacing : 48;
    switch (page.paper) {
      case "two-column":
      case "lecture":
      case "mistakes":
        {
          float split = "lecture".equals(page.paper) ? w * .3f : w / 2;
          canvas.drawLine(split, margin, split, h - margin, p);
          for (float y = margin + 70; y < h - margin; y += step) {
            if (!"mistakes".equals(page.paper) || ((int) ((y - margin) / step)) % 5 != 0)
              canvas.drawLine(margin, y, w - margin, y, p);
            else {
              p.setStrokeWidth(3 * lineWeight);
              canvas.drawLine(margin, y, w - margin, y, p);
              p.setStrokeWidth(1 * lineWeight);
            }
          }
          canvas.drawLine(margin, margin + 70, w - margin, margin + 70, p);
          break;
        }
      case "axes":
      case "geometry":
        {
          float unit = page.lineSpacing > 0 ? page.lineSpacing : 40;
          for (float x = margin; x < w - margin; x += unit)
            canvas.drawLine(x, margin, x, h - margin, p);
          for (float y = margin; y < h - margin; y += unit)
            canvas.drawLine(margin, y, w - margin, y, p);
          p.setStrokeWidth(3 * lineWeight);
          if ("axes".equals(page.paper)) {
            canvas.drawLine(w / 2, margin, w / 2, h - margin, p);
            canvas.drawLine(margin, h / 2, w - margin, h / 2, p);
            canvas.drawLine(w / 2, margin, w / 2 - 12, margin + 22, p);
            canvas.drawLine(w / 2, margin, w / 2 + 12, margin + 22, p);
            canvas.drawLine(w - margin, h / 2, w - margin - 22, h / 2 - 12, p);
            canvas.drawLine(w - margin, h / 2, w - margin - 22, h / 2 + 12, p);
          } else {
            canvas.drawLine(margin, h * .6f, w - margin, h * .6f, p);
            canvas.drawLine(w * .65f, margin, w * .65f, h - margin, p);
          }
          break;
        }
      case "daily":
      case "monthly":
      case "habit":
        {
          int columns = "monthly".equals(page.paper) ? 7 : "habit".equals(page.paper) ? 8 : 2;
          int rows = "monthly".equals(page.paper) ? 6 : "habit".equals(page.paper) ? 12 : 10;
          float top = margin + 90;
          canvas.drawRect(margin, top, w - margin, h - margin, p);
          for (int i = 1; i < columns; i++) {
            float x = margin + (w - 2 * margin) * i / columns;
            canvas.drawLine(x, top, x, h - margin, p);
          }
          for (int i = 1; i < rows; i++) {
            float y = top + (h - margin - top) * i / rows;
            canvas.drawLine(margin, y, w - margin, y, p);
          }
          p.setStyle(Paint.Style.FILL);
          p.setTextSize(25);
          canvas.drawText(
              "monthly".equals(page.paper)
                  ? "月份 / MONTH"
                  : "habit".equals(page.paper) ? "习惯 / HABIT" : "日期 / TODAY",
              margin,
              margin + 40,
              p);
          break;
        }
      case "rice":
        {
          float cell = page.lineSpacing > 0 ? page.lineSpacing : 100;
          for (float y = margin; y + cell < h - margin; y += cell)
            for (float x = margin; x + cell < w - margin; x += cell) {
              p.setAlpha(60);
              canvas.drawRect(x, y, x + cell, y + cell, p);
              p.setAlpha(28);
              canvas.drawLine(x, y, x + cell, y + cell, p);
              canvas.drawLine(x + cell, y, x, y + cell, p);
              canvas.drawLine(x + cell / 2, y, x + cell / 2, y + cell, p);
              canvas.drawLine(x, y + cell / 2, x + cell, y + cell / 2, p);
            }
          break;
        }
      case "english":
        {
          float gap = page.lineSpacing > 0 ? page.lineSpacing : 24;
          for (float y = margin; y + gap * 3 < h - margin; y += gap * 6)
            for (int i = 0; i < 4; i++)
              canvas.drawLine(margin, y + i * gap, w - margin, y + i * gap, p);
          break;
        }
      case "blank":
        break;
      case "lined":
      case "wide-lined":
        step = page.lineSpacing > 0 ? page.lineSpacing : "wide-lined".equals(page.paper) ? 80 : 48;
        for (float y = margin; y < h - margin; y += step)
          canvas.drawLine(margin, y, w - margin, y, p);
        break;
      case "grid":
      case "engineering":
        step = page.lineSpacing > 0 ? page.lineSpacing : "engineering".equals(page.paper) ? 20 : 40;
        for (float x = margin; x < w - margin; x += step)
          canvas.drawLine(x, margin, x, h - margin, p);
        for (float y = margin; y < h - margin; y += step)
          canvas.drawLine(margin, y, w - margin, y, p);
        if ("engineering".equals(page.paper)) {
          p.setColor(Color.argb(75, 72, 115, 145));
          for (float x = margin; x < w - margin; x += 100)
            canvas.drawLine(x, margin, x, h - margin, p);
          for (float y = margin; y < h - margin; y += 100)
            canvas.drawLine(margin, y, w - margin, y, p);
        }
        break;
      case "cornell":
        for (float y = margin + 80; y < h - 300; y += step)
          canvas.drawLine(margin, y, w - margin, y, p);
        p.setStrokeWidth(2 * lineWeight);
        canvas.drawLine(340, margin + 80, 340, h - 300, p);
        canvas.drawLine(margin, h - 300, w - margin, h - 300, p);
        canvas.drawLine(margin, margin + 80, w - margin, margin + 80, p);
        break;
      case "square":
        float cellSize = page.lineSpacing > 0 ? page.lineSpacing : 100;
        for (float y = margin; y + cellSize < h - margin; y += cellSize)
          for (float x = margin; x + cellSize < w - margin; x += cellSize) {
            p.setAlpha(55);
            canvas.drawRect(x, y, x + cellSize, y + cellSize, p);
            p.setAlpha(25);
            canvas.drawLine(x + cellSize / 2, y, x + cellSize / 2, y + cellSize, p);
            canvas.drawLine(x, y + cellSize / 2, x + cellSize, y + cellSize / 2, p);
          }
        break;
      case "music":
        float musicGap = page.lineSpacing > 0 ? page.lineSpacing : 20;
        for (float y = 120; y + musicGap * 4 < h - margin; y += musicGap * 10.5f) {
          for (int i = 0; i < 5; i++)
            canvas.drawLine(margin, y + i * musicGap, w - margin, y + i * musicGap, p);
          canvas.drawLine(margin, y, margin, y + musicGap * 4, p);
          canvas.drawLine(w - margin, y, w - margin, y + musicGap * 4, p);
        }
        break;
      case "planner":
        p.setStrokeWidth(2 * lineWeight);
        canvas.drawRect(margin, 140, w - margin, h - margin, p);
        for (int i = 1; i < 8; i++) {
          float y = 140 + (h - margin - 140) * i / 8;
          canvas.drawLine(margin, y, w - margin, y, p);
        }
        canvas.drawLine(w * .56f, 140, w * .56f, h - margin, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(27);
        p.setColor(0xff929da8);
        String[] labels = {
          "周一 MON", "周二 TUE", "周三 WED", "周四 THU", "周五 FRI", "周六 SAT", "周日 SUN", "备忘 NOTES"
        };
        for (int i = 0; i < 8; i++)
          canvas.drawText(labels[i], margin + 18, 180 + (h - margin - 140) * i / 8, p);
        break;
      case "checklist":
        p.setColor(Color.argb(55, 89, 104, 121));
        p.setStrokeWidth(1.5f * lineWeight);
        for (float y = 150; y < h - margin; y += page.lineSpacing > 0 ? page.lineSpacing : 58) {
          canvas.drawCircle(margin + 10, y - 5, 8, p);
          canvas.drawLine(margin + 42, y, w - margin, y, p);
        }
        break;
      case "weekly":
        p.setStrokeWidth(2 * lineWeight);
        canvas.drawRect(margin, 140, w - margin, h - margin, p);
        for (int i = 1; i < 7; i++)
          canvas.drawLine(
              margin + (w - 2 * margin) * i / 7,
              140,
              margin + (w - 2 * margin) * i / 7,
              h - margin,
              p);
        for (int i = 1; i < 10; i++)
          canvas.drawLine(
              margin,
              140 + (h - margin - 140) * i / 10,
              w - margin,
              140 + (h - margin - 140) * i / 10,
              p);
        break;
      case "storyboard":
        p.setStrokeWidth(2 * lineWeight);
        float frameWidth = (w - 2 * margin - 48) / 2;
        float frameHeight = Math.min(440, (h - 2 * margin - 64) / 3);
        for (int row = 0; row < 3; row++)
          for (int column = 0; column < 2; column++) {
            float left = margin + column * (frameWidth + 48);
            float top = margin + row * (frameHeight + 32);
            canvas.drawRoundRect(
                new RectF(left, top, left + frameWidth, top + frameHeight), 8, 8, p);
          }
        break;
      case "isometric":
        float isoStep = page.lineSpacing > 0 ? page.lineSpacing : 60;
        float rise = isoStep * .866f;
        for (float y = margin; y < h - margin; y += rise)
          canvas.drawLine(margin, y, w - margin, y, p);
        int clip = canvas.save();
        canvas.clipRect(margin, margin, w - margin, h - margin);
        for (float x = -h; x < w + h; x += isoStep) {
          canvas.drawLine(x, margin, x + (h - 2 * margin) / 1.732f, h - margin, p);
          canvas.drawLine(x, margin, x - (h - 2 * margin) / 1.732f, h - margin, p);
        }
        canvas.restoreToCount(clip);
        break;
      case "dot":
      default:
        p.setStyle(Paint.Style.FILL);
        p.setColor(darkPaper ? Color.argb(125, 175, 190, 208) : Color.argb(65, 95, 109, 124));
        for (float y = margin; y < h - margin; y += page.lineSpacing > 0 ? page.lineSpacing : 40)
          for (float x = margin; x < w - margin; x += page.lineSpacing > 0 ? page.lineSpacing : 40)
            canvas.drawCircle(x, y, Math.max(1.6f, lineWeight * .65f), p);
    }
  }

  private static void drawTexture(Canvas c, NoteDocument.Page page, Paint p) {
    if ("smooth".equals(page.texture)) return;
    Random random = new Random(28326);
    p.setStyle(Paint.Style.FILL);
    if ("kraft".equals(page.texture)) {
      p.setColor(Color.argb(49, 163, 105, 43));
      c.drawRect(0, 0, page.width, page.height, p);
    } else if ("recycled".equals(page.texture)) {
      p.setColor(Color.argb(22, 105, 104, 83));
      c.drawRect(0, 0, page.width, page.height, p);
    }
    int count = (int) Math.min(6000, page.width * page.height / 650);
    for (int i = 0; i < count; i++) {
      float x = random.nextFloat() * page.width, y = random.nextFloat() * page.height;
      p.setColor(Color.argb("cotton".equals(page.texture) ? 14 : 22, 109, 89, 64));
      if ("linen".equals(page.texture)) c.drawLine(x, y, x + 8, y + 1, p);
      else c.drawCircle(x, y, .35f + random.nextFloat() * 1.2f, p);
    }
    if ("linen".equals(page.texture)) {
      p.setColor(Color.argb(12, 97, 90, 77));
      p.setStrokeWidth(.6f);
      for (float x = 0; x < page.width; x += 9) c.drawLine(x, 0, x, page.height, p);
      for (float y = 0; y < page.height; y += 9) c.drawLine(0, y, page.width, y, p);
    }
  }

  public static void drawInk(Canvas canvas, NoteDocument.Stroke stroke) {
    if (stroke.points.isEmpty()) return;
    InkTools tools = INK_TOOLS.get();
    Paint p = tools.paint;
    p.reset();
    p.setAntiAlias(true);
    p.setColor(stroke.color);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
    p.setStyle(Paint.Style.STROKE);
    List<NoteDocument.Point> points = InkGeometry.outline(stroke);
    float minX = Float.POSITIVE_INFINITY,
        minY = Float.POSITIVE_INFINITY,
        maxX = Float.NEGATIVE_INFINITY,
        maxY = Float.NEGATIVE_INFINITY;
    for (NoteDocument.Point point : points) {
      minX = Math.min(minX, point.x);
      minY = Math.min(minY, point.y);
      maxX = Math.max(maxX, point.x);
      maxY = Math.max(maxY, point.y);
    }
    // Midpoint quadratic segments stay inside the convex hull of their control points.
    if (canvas.quickReject(
        minX - stroke.width,
        minY - stroke.width,
        maxX + stroke.width,
        maxY + stroke.width,
        Canvas.EdgeType.AA)) return;
    if (InkGeometry.isShape(stroke.tool)
        || "linepath".equals(stroke.tool)
        || "highlighter".equals(stroke.tool)) {
      p.setStrokeWidth(stroke.width);
      if ("highlighter".equals(stroke.tool)) {
        p.setAlpha(BrushStyle.markerAlpha(stroke.color));
      }
      Path path = tools.path;
      path.reset();
      path.moveTo(points.get(0).x, points.get(0).y);
      for (int i = 1; i < points.size(); i++) {
        NoteDocument.Point previous = points.get(i - 1), next = points.get(i);
        if ("highlighter".equals(stroke.tool) && !"linear".equals(stroke.pathMode)) {
          float endX = i < points.size() - 1 ? (previous.x + next.x) / 2 : next.x;
          float endY = i < points.size() - 1 ? (previous.y + next.y) / 2 : next.y;
          path.quadTo(previous.x, previous.y, endX, endY);
        } else path.lineTo(next.x, next.y);
      }
      if (points.size() == 1) {
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(points.get(0).x, points.get(0).y, stroke.width / 2, p);
      } else canvas.drawPath(path, p);
      return;
    }
    if (points.size() == 1) {
      p.setStyle(Paint.Style.FILL);
      NoteDocument.Point first = points.get(0);
      p.setAlpha(alpha(stroke, first));
      canvas.drawCircle(first.x, first.y, inkWidth(stroke, first) / 2, p);
      return;
    }
    // Uniform-opacity pens share one compositing group. Applying alpha to every round-capped
    // segment separately darkens their overlapping joins, even along a straight stroke.
    boolean compositeAlpha = !"pencil".equals(stroke.tool) && Color.alpha(stroke.color) < 255;
    int layer =
        compositeAlpha
            ? canvas.saveLayerAlpha(
                minX - stroke.width - 2,
                minY - stroke.width - 2,
                maxX + stroke.width + 2,
                maxY + stroke.width + 2,
                Color.alpha(stroke.color))
            : -1;
    try {
      if ("linear".equals(stroke.pathMode)) {
        Path path = tools.path;
        path.reset();
        float previousWidth = -1;
        int previousAlpha = -1;
        int previousRun = -1;
        for (int i = 1; i < points.size(); i++) {
          NoteDocument.Point a = points.get(i - 1), b = points.get(i);
          float width =
              b.widthScale > 0
                  ? stroke.width * b.widthScale
                  : (inkWidth(stroke, a) + inkWidth(stroke, b)) / 2;
          int opacity =
              compositeAlpha
                  ? 255
                  : b.widthScale > 0
                      ? Math.round(Color.alpha(stroke.color) * b.alphaScale)
                      : (alpha(stroke, a) + alpha(stroke, b)) / 2;
          if (width != previousWidth || opacity != previousAlpha || b.styleRun != previousRun) {
            if (previousWidth >= 0) canvas.drawPath(path, p);
            path.reset();
            path.moveTo(a.x, a.y);
            p.setStrokeWidth(width);
            p.setAlpha(opacity);
            previousWidth = width;
            previousAlpha = opacity;
            previousRun = b.styleRun;
          }
          path.lineTo(b.x, b.y);
        }
        canvas.drawPath(path, p);
        return;
      }
      // Adjacent quadratic segments share midpoints; pressure changes width without sharp angular
      // joins.
      for (int i = 1; i < points.size(); i++) {
        drawInkSegment(canvas, stroke, i, i < points.size() - 1, compositeAlpha);
      }
    } finally {
      if (layer >= 0) canvas.restoreToCount(layer);
    }
  }

  /** One stable midpoint segment, also used to append active ink without replaying old samples. */
  public static void drawInkSegment(
      Canvas canvas, NoteDocument.Stroke stroke, int index, boolean midpoint, boolean opaque) {
    if (index < 1 || index >= stroke.points.size()) return;
    NoteDocument.Point previous = stroke.points.get(index - 1), next = stroke.points.get(index);
    NoteDocument.Point before = index > 1 ? stroke.points.get(index - 2) : previous;
    float startX = index > 1 ? (before.x + previous.x) / 2 : previous.x;
    float startY = index > 1 ? (before.y + previous.y) / 2 : previous.y;
    float endX = midpoint ? (previous.x + next.x) / 2 : next.x,
        endY = midpoint ? (previous.y + next.y) / 2 : next.y;
    InkTools tools = INK_TOOLS.get();
    Paint p = tools.paint;
    p.reset();
    p.setAntiAlias(true);
    p.setColor(stroke.color);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
    boolean marker = "highlighter".equals(stroke.tool);
    p.setStrokeWidth(
        marker ? stroke.width : (inkWidth(stroke, previous) + inkWidth(stroke, next)) / 2);
    p.setAlpha(opaque ? 255 : (alpha(stroke, previous) + alpha(stroke, next)) / 2);
    Path segment = tools.path;
    segment.reset();
    segment.moveTo(startX, startY);
    segment.quadTo(previous.x, previous.y, endX, endY);
    canvas.drawPath(segment, p);
  }

  private static float inkWidth(NoteDocument.Stroke s, NoteDocument.Point point) {
    return BrushStyle.width(s, point);
  }

  private static int alpha(NoteDocument.Stroke s, NoteDocument.Point point) {
    return BrushStyle.alpha(s, point);
  }

  static void drawElement(Canvas c, NoteDocument.Element e) {
    drawElement(c, e, e);
  }

  static void drawElement(Canvas c, NoteDocument.Element e, Object viewportOwner) {
    int rotationSave = c.save();
    c.rotate(e.rotation, e.x + e.width / 2, e.y + e.height / 2);
    try {
      if ("image".equals(e.kind)) {
        Bitmap image = media(e.path, -1);
        if (image != null) {
          int imageLayer =
              image.hasAlpha() ? c.saveLayer(e.x, e.y, e.x + e.width, e.y + e.height, null) : -1;
          c.drawBitmap(
              image, null, new RectF(e.x, e.y, e.x + e.width, e.y + e.height), bitmapPaint());
          c.translate(e.x, e.y);
          PDF_DETAILS.drawImage(
              c,
              e,
              viewportOwner,
              Math.min(image.getWidth() / e.width, image.getHeight() / e.height));
          if (imageLayer >= 0) c.restoreToCount(imageLayer);
        } else
          unavailable(
              c,
              e.x,
              e.y + 30,
              MEDIA.failed(new MediaKey(e.path, -1)) ? "图片暂时无法载入，重新打开此页可重试" : "图片载入中…");
      } else if ("tape".equals(e.kind)) {
        TapeArt.draw(c, e);
      } else {
        StaticLayout layout = layoutText(e);
        int clip = c.save();
        c.translate(e.x, e.y);
        c.clipRect(0, 0, e.width, Math.max(e.height, layout.getHeight()));
        layout.draw(c);
        c.restoreToCount(clip);
      }
    } finally {
      c.restoreToCount(rotationSave);
    }
  }

  private static StaticLayout layoutText(NoteDocument.Element element) {
    TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(element.color);
    paint.setTextSize(element.textSize);
    paint.setTypeface(
        android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            element.bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
    paint.setUnderlineText(!element.targetPageId.isEmpty());
    return StaticLayout.Builder.obtain(
            element.text, 0, element.text.length(), paint, Math.max(1, (int) element.width))
        .setIncludePad(false)
        .setAlignment(
            "center".equals(element.textAlign)
                ? Layout.Alignment.ALIGN_CENTER
                : "right".equals(element.textAlign)
                    ? Layout.Alignment.ALIGN_OPPOSITE
                    : Layout.Alignment.ALIGN_NORMAL)
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build();
  }

  /** Text is fitted using the exact layout used for screen and exported pages. */
  public static boolean fitTextToPage(NoteDocument.Page page, NoteDocument.Element element) {
    float height = Math.max(1, layoutText(element).getHeight());
    if (height > (page.infinite ? 40000 : page.height - 40)) return false;
    element.height = height;
    if (!page.infinite) element.y = Math.max(20, Math.min(element.y, page.height - height - 20));
    return true;
  }

  private static Paint bitmapPaint() {
    return new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  }

  private static void unavailable(Canvas c, float x, float y, String message) {
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(0xff929ba8);
    p.setTextSize(25);
    c.drawText(message, x, y, p);
  }

  /** One completion per page attempt; callbacks may run on either the caller or decoder thread. */
  public static void prepare(NoteDocument.Page page, Runnable ready) {
    prepareCancelable(page, ready);
  }

  static MediaRequest prepareCancelable(NoteDocument.Page page, Runnable ready) {
    PageMediaCache.Request request = MEDIA.request(mediaKeys(page), ready, false);
    return request::cancel;
  }

  static boolean preparationFailed(NoteDocument.Page page) {
    for (MediaKey key : mediaKeys(page)) if (MEDIA.failed(key)) return true;
    return false;
  }

  /**
   * Observe all media transitions while this page is visible, including reload after eviction.
   * Every subscriber is retained independently; callers must cancel when the page leaves view.
   */
  public static MediaRequest subscribe(NoteDocument.Page page, Runnable ready) {
    List<MediaRequest> details = new ArrayList<>();
    details.add(PDF_DETAILS.subscribe(page, ready));
    for (NoteDocument.Element element : page.elements)
      if ("image".equals(element.kind) && !element.path.isEmpty())
        details.add(PDF_DETAILS.subscribeImage(element, ready));
    PageMediaCache.Request request = MEDIA.request(mediaKeys(page), ready, true);
    return () -> {
      for (MediaRequest detail : details) detail.cancel();
      request.cancel();
    };
  }

  private static List<MediaKey> mediaKeys(NoteDocument.Page page) {
    ArrayList<MediaKey> keys = new ArrayList<>();
    if (page.backgroundPath != null && !page.backgroundPath.isEmpty())
      keys.add(new MediaKey(page.backgroundPath, page.pdfPage));
    for (NoteDocument.Element element : page.elements)
      if ("image".equals(element.kind) && element.path != null && !element.path.isEmpty())
        keys.add(new MediaKey(element.path, -1));
    return keys;
  }

  private static Bitmap media(String path, int pdfPage) {
    MediaKey key = new MediaKey(path, pdfPage);
    Bitmap bitmap = MEDIA.peek(key);
    if (bitmap != null) return bitmap;
    // Export/thumbnail workers join any in-flight decode; UI rendering never opens a file.
    if (Looper.myLooper() != Looper.getMainLooper()) return MEDIA.loadBlocking(key);
    MEDIA.reloadIfObserved(key);
    return null;
  }

  private static Bitmap decode(String path, int pdfPage) {
    Bitmap bitmap = null;
    try {
      File file = new File(path);
      if (!file.isAbsolute() || !file.isFile()) throw new IOException("缺少本地资料");
      if (pdfPage >= 0) {
        try (ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer renderer = new PdfRenderer(descriptor)) {
          if (pdfPage >= renderer.getPageCount()) throw new IOException("PDF 页码超出范围");
          try (PdfRenderer.Page page = renderer.openPage(pdfPage)) {
            float ratio = Math.min(2048f / page.getWidth(), 2048f / page.getHeight());
            bitmap =
                Bitmap.createBitmap(
                    Math.max(1, (int) (page.getWidth() * ratio)),
                    Math.max(1, (int) (page.getHeight() * ratio)),
                    Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
          }
        }
      } else {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("图片格式无效");
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 2048
            || options.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null) throw new IOException("无法解码图片");
      }
    } catch (IOException | RuntimeException | OutOfMemoryError error) {
      // Failed PDF render can still have allocated a bitmap; do not keep that allocation alive.
      if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
      return null;
    }
    return bitmap;
  }
}
