package app.iko.notes;

import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.LayerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.util.Matrix;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Import source PDF page resources and paths, then add Android's vector annotation layer. */
final class VectorPdfExport {
  static void write(NoteDocument document, OutputStream output) throws IOException {
    File overlay = File.createTempFile("iko-vector-", ".pdf");
    LinkedHashMap<String, PDDocument> sources = new LinkedHashMap<>();
    ArrayList<RectF> extents = new ArrayList<>();
    ArrayList<int[]> sizes = new ArrayList<>();
    try {
      PdfDocument annotations = new PdfDocument();
      try {
        for (int n = 0; n < document.pages.size(); n++) {
          NoteDocument.Page source = document.pages.get(n);
          RectF extent =
              source.infinite
                  ? InfiniteCanvas.contentBounds(source)
                  : new RectF(0, 0, source.width, source.height);
          float scale =
              Math.min(.15f * 72 / 25.4f, 4096f / Math.max(extent.width(), extent.height()));
          int width = Math.max(1, Math.round(extent.width() * scale));
          int height = Math.max(1, Math.round(extent.height() * scale));
          extents.add(extent);
          sizes.add(new int[] {width, height});
          PdfDocument.Page page =
              annotations.startPage(
                  new PdfDocument.PageInfo.Builder(width, height, n + 1).create());
          Canvas canvas = page.getCanvas();
          canvas.scale(width / extent.width(), height / extent.height());
          canvas.translate(-extent.left, -extent.top);
          canvas.clipRect(extent);
          if (source.pdfPage < 0) PageRenderer.drawBackground(canvas, source, true);
          PageRenderer.drawForeground(canvas, source);
          annotations.finishPage(page);
        }
        try (FileOutputStream stream = new FileOutputStream(overlay)) {
          annotations.writeTo(stream);
        }
      } finally {
        annotations.close();
      }
      try (PDDocument annotationsPdf =
              PDDocument.load(overlay, MemoryUsageSetting.setupMixed(8 * 1024 * 1024));
          PDDocument result = new PDDocument(MemoryUsageSetting.setupMixed(16 * 1024 * 1024))) {
        LayerUtility layers = new LayerUtility(result);
        LinkedHashMap<String, PDFormXObject> backgrounds = new LinkedHashMap<>();
        for (int n = 0; n < document.pages.size(); n++) {
          NoteDocument.Page source = document.pages.get(n);
          int[] size = sizes.get(n);
          PDPage page = new PDPage(new PDRectangle(size[0], size[1]));
          result.addPage(page);
          try (PDPageContentStream stream = new PDPageContentStream(result, page)) {
            if (source.pdfPage >= 0
                && source.backgroundPath != null
                && !source.backgroundPath.isEmpty()) {
              PDDocument original = sources.get(source.backgroundPath);
              if (original == null) {
                original =
                    PDDocument.load(
                        new File(source.backgroundPath),
                        MemoryUsageSetting.setupMixed(8 * 1024 * 1024));
                sources.put(source.backgroundPath, original);
              }
              if (source.pdfPage >= original.getNumberOfPages())
                throw new IOException("原PDF页码无效，未导出残缺内容");
              String key = source.backgroundPath + "#" + source.pdfPage;
              PDFormXObject form = backgrounds.get(key);
              if (form == null) {
                form = layers.importPageAsForm(original, source.pdfPage);
                backgrounds.put(key, form);
              }
              RectF extent = extents.get(n);
              float sx = size[0] / extent.width(), sy = size[1] / extent.height();
              // PDF uses bottom-left coordinates; paper uses top-left coordinates.
              place(
                  stream,
                  form,
                  -extent.left * sx,
                  size[1] - (source.height - extent.top) * sy,
                  source.width * sx,
                  source.height * sy);
            }
            if (source.pdfPage < 0 || !source.strokes.isEmpty() || !source.elements.isEmpty())
              place(stream, layers.importPageAsForm(annotationsPdf, n), 0, 0, size[0], size[1]);
          }
        }
        result.save(output);
        output.flush();
      }
    } catch (RuntimeException error) {
      throw new IOException("矢量PDF导出失败：" + error.getMessage(), error);
    } finally {
      for (PDDocument source : sources.values())
        try {
          source.close();
        } catch (IOException ignored) {
        }
      overlay.delete();
    }
  }

  private static void place(
      PDPageContentStream stream, PDFormXObject form, float x, float y, float width, float height)
      throws IOException {
    PDRectangle box = form.getBBox();
    Matrix matrix = form.getMatrix();
    float left = Float.POSITIVE_INFINITY,
        top = left,
        right = Float.NEGATIVE_INFINITY,
        bottom = right;
    for (float px : new float[] {box.getLowerLeftX(), box.getUpperRightX()})
      for (float py : new float[] {box.getLowerLeftY(), box.getUpperRightY()}) {
        PointF point = matrix.transformPoint(px, py);
        left = Math.min(left, point.x);
        top = Math.min(top, point.y);
        right = Math.max(right, point.x);
        bottom = Math.max(bottom, point.y);
      }
    float sx = width / (right - left), sy = height / (bottom - top);
    stream.saveGraphicsState();
    stream.addRect(x, y, width, height);
    stream.clip();
    stream.transform(new Matrix(sx, 0, 0, sy, x - left * sx, y - top * sy));
    stream.drawForm(form);
    stream.restoreGraphicsState();
  }

  private VectorPdfExport() {}
}
