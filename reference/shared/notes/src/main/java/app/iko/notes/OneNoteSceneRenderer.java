package app.iko.notes;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Draws actual OneNote geometry into PDF. Never substitutes recognition text for ink. */
final class OneNoteSceneRenderer {
  static final float HALF = 36f, INK = 72f / 2540f;
  static final int MAX_POINTS = 2_000_000;
  private int renderedStrokes,
      renderedImages,
      renderedEmbedded,
      renderedEmptyInk,
      renderedTables,
      renderedText,
      renderedMath,
      renderedLists,
      renderedTags,
      contentDepth,
      points;
  private long bitmapBytes;
  private final ArrayList<Bitmap> bitmaps = new ArrayList<>();
  private final ArrayList<DrawCommand> drawings = new ArrayList<>();
  private final LinkedHashMap<String, Integer> unsupported = new LinkedHashMap<>();
  private RectF bounds = new RectF();
  private final JSONArray sourceLayouts = new JSONArray(), tiles = new JSONArray();

  private interface Draw {
    void draw(Canvas canvas);
  }

  private static final class DrawCommand {
    final RectF bounds;
    final Draw draw;

    DrawCommand(RectF bounds, Draw draw) {
      this.bounds = new RectF(bounds);
      this.draw = draw;
    }
  }

  private void addDrawing(RectF bounds, Draw draw) {
    drawings.add(new DrawCommand(bounds, draw));
  }

  static JSONObject render(JSONObject scene, File destination) throws IOException {
    return render(scene, destination, true);
  }

  static JSONObject render(JSONObject scene, File destination, boolean continuous)
      throws IOException {
    if (scene.has("error")) throw new IOException("OneNote 本地解析失败：" + scene.optString("error"));
    OneNoteSceneRenderer renderer = new OneNoteSceneRenderer();
    try {
      renderer.write(scene, destination, continuous);
      return new JSONObject().put("sources", renderer.sourceLayouts).put("tiles", renderer.tiles);
    } catch (JSONException | IllegalArgumentException e) {
      throw new IOException("OneNote 页面数据无效", e);
    }
  }

  private void write(JSONObject scene, File destination, boolean continuous)
      throws IOException, JSONException {
    JSONArray warnings = scene.optJSONArray("warnings");
    JSONArray diagnostics = scene.optJSONArray("diagnostics");
    if (diagnostics != null) {
      if (warnings == null || diagnostics.length() != warnings.length())
        throw new IOException("OneNote 诊断完整性校验失败");
      int emptyDiagnostics = 0;
      for (int i = 0; i < diagnostics.length(); i++) {
        JSONObject diagnostic = diagnostics.getJSONObject(i);
        String code = diagnostic.optString("code");
        if (!diagnostic.optString("message").equals(warnings.getString(i)))
          throw new IOException("OneNote 诊断顺序校验失败");
        if (code.equals("empty_ink_data")) emptyDiagnostics++;
        if (!diagnostic.optString("severity").equals("info")
            || (!code.equals("default_paragraph_style") && !code.equals("empty_ink_data")))
          fail("解析诊断 " + code + "：" + diagnostic.optString("message"));
      }
      if (emptyDiagnostics != scene.getJSONObject("counts").optInt("emptyInk"))
        throw new IOException("OneNote 空墨迹容器诊断计数不一致");
    } else if (warnings != null)
      for (int i = 0; i < warnings.length(); i++) {
        String warning = warnings.getString(i);
        // This specific warning means a missing optional style. All other parser losses are fatal.
        if (!warning.equals("rich text has no paragraph style; using defaults")) fail("解析警告");
      }
    JSONArray pages = scene.getJSONArray("pages");
    if (pages.length() < 1 || pages.length() > 500) throw new IOException("OneNote 页数超出范围");
    int pdfPage = 0;
    boolean success = false;
    PdfDocument pdf = new PdfDocument();
    try {
      for (int n = 0; n < pages.length(); n++) {
        drawings.clear();
        bounds = new RectF();
        JSONObject page = pages.getJSONObject(n);
        JSONArray rules = page.optJSONArray("legacyRules");
        if (rules != null && !rules.isNull(0) && rules.optInt(0) != 0 && rules.optInt(0) != 2)
          fail("纸线类型");
        if (rules != null && !rules.isNull(3) && rules.optInt(3) != 0 && rules.optInt(3) != 3)
          fail("页边线类型");
        if (!page.isNull("ruleLines") && page.optInt("ruleLines") != 0) fail("新版纸线类型");
        JSONArray content = page.getJSONArray("contents");
        for (int j = 0; j < content.length(); j++) content(content.getJSONObject(j), 0, 0, 468);
        JSONObject title = page.optJSONObject("titleContent");
        if (title != null) {
          JSONArray origin = page.optJSONArray("marginOrigin");
          float titleX =
              (num(title, "x", 0) + (origin == null ? 0 : (float) origin.optDouble(0, 0))) * HALF;
          float ty =
              (num(title, "y", 0) + (origin == null ? 0 : (float) origin.optDouble(1, 0))) * HALF;
          JSONArray outlines = title.getJSONArray("contents");
          for (int j = 0; j < outlines.length(); j++) {
            JSONObject o = outlines.getJSONObject(j);
            ty +=
                items(
                    o.getJSONArray("items"),
                    titleX,
                    ty,
                    Math.max(468, bounds.right),
                    o.optJSONArray("indents"),
                    1,
                    0);
          }
        }
        if (drawings.isEmpty()) throw new IOException("OneNote 页面没有可渲染对象，未创建空白页");
        if (!unsupported.isEmpty()) throw unsupportedError();
        float left = Math.min(0, bounds.left), top = Math.min(0, bounds.top);
        float sourceWidth = num(page, "width", 17) * HALF;
        float bodyWidth = Math.max(300, Math.max(sourceWidth, bounds.right - left));
        finite(bodyWidth);
        float bodyHeight = bounds.bottom - top;
        // Fit the complete source width uniformly; only split vertically, as in 0.8.3.
        float scale =
            continuous
                ? Math.min(1, 12000f / Math.max(bodyWidth + 48, bodyHeight + 48))
                : Math.min(1, 547f / bodyWidth);
        float tileWidth = bodyWidth;
        float tileHeight = continuous ? bodyHeight : 794 / scale;
        int columns = Math.max(1, (int) Math.ceil(bodyWidth / tileWidth));
        int rows = Math.max(1, (int) Math.ceil(bodyHeight / tileHeight));
        if ((long) pdfPage + (long) rows * columns > NoteDocument.MAX_PAGES)
          throw new IOException("分页后超过 500 页，请选择无边记导入");
        int pdfWidth = continuous ? Math.max(72, (int) Math.ceil((tileWidth + 48) * scale)) : 595;
        int pdfHeight = continuous ? Math.max(72, (int) Math.ceil((tileHeight + 48) * scale)) : 842;
        sourceLayouts.put(
            new JSONObject()
                .put("sourcePage", n)
                .put("bounds", rectJson(bounds))
                .put("storedWidth", sourceWidth)
                .put("tileWidth", tileWidth)
                .put("scale", scale)
                .put("pagination", continuous ? "original-canvas" : "full-width-pages"));
        for (int row = 0; row < rows; row++) {
          for (int column = 0; column < columns; column++) {
            RectF extent =
                new RectF(
                    left + column * tileWidth,
                    top + row * tileHeight,
                    Math.min(left + bodyWidth, left + (column + 1) * tileWidth),
                    Math.min(bounds.bottom, top + (row + 1) * tileHeight));
            PdfDocument.Page out =
                pdf.startPage(
                    new PdfDocument.PageInfo.Builder(pdfWidth, pdfHeight, ++pdfPage).create());
            Canvas canvas = out.getCanvas();
            canvas.drawColor(Color.WHITE);
            canvas.save();
            canvas.scale(scale, scale);
            float margin = continuous ? 24 : 24 / scale;
            canvas.translate(margin - extent.left, margin - extent.top);
            canvas.clipRect(extent);
            paper(canvas, page, extent.left, extent.top, extent.width(), extent.height());
            for (DrawCommand command : drawings)
              if (RectF.intersects(command.bounds, extent)) command.draw.draw(canvas);
            canvas.restore();
            pdf.finishPage(out);
            tiles.put(
                new JSONObject()
                    .put("pdfPage", pdfPage)
                    .put("sourcePage", n)
                    .put("rect", rectJson(extent))
                    .put("scale", scale));
          }
        }
      }
      JSONObject expected = scene.getJSONObject("counts");
      if (renderedStrokes != expected.getInt("strokes")
          || renderedImages != expected.getInt("images")
          || renderedEmbedded != expected.getInt("embeddedInk")
          || renderedEmptyInk != expected.optInt("emptyInk")
          || renderedTables != expected.optInt("tables")
          || (expected.has("textBlocks") && renderedText != expected.optInt("textBlocks"))
          || (expected.has("math") && renderedMath != expected.optInt("math"))
          || (expected.has("lists") && renderedLists != expected.optInt("lists"))
          || (expected.has("tags") && renderedTags != expected.optInt("tags")))
        throw new IOException(
            "OneNote 完整性校验失败：墨迹 "
                + renderedStrokes
                + "/"
                + expected.optInt("strokes")
                + "，图片 "
                + renderedImages
                + "/"
                + expected.optInt("images")
                + "，嵌入墨迹 "
                + renderedEmbedded
                + "/"
                + expected.optInt("embeddedInk")
                + "，空墨迹容器 "
                + renderedEmptyInk
                + "/"
                + expected.optInt("emptyInk")
                + "，表格 "
                + renderedTables
                + "/"
                + expected.optInt("tables")
                + "，文本 "
                + renderedText
                + "/"
                + expected.optInt("textBlocks")
                + "，公式 "
                + renderedMath
                + "/"
                + expected.optInt("math")
                + "，列表 "
                + renderedLists
                + "/"
                + expected.optInt("lists")
                + "，标签 "
                + renderedTags
                + "/"
                + expected.optInt("tags"));
      try (FileOutputStream stream = new FileOutputStream(destination)) {
        pdf.writeTo(stream);
        stream.getFD().sync();
      }
      success = true;
    } finally {
      pdf.close();
      for (Bitmap bitmap : bitmaps) bitmap.recycle();
      bitmaps.clear();
      if (!success) destination.delete();
    }
  }

  private static JSONArray rectJson(RectF rect) throws JSONException {
    return new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom);
  }

  private void paper(Canvas c, JSONObject page, float x, float y, float w, float h) {
    if (!page.isNull("backgroundColor")) {
      long col = page.optLong("backgroundColor");
      Paint bg = new Paint();
      bg.setColor(Color.rgb((int) col & 255, (int) (col >> 8) & 255, (int) (col >> 16) & 255));
      c.drawRect(x, y, x + w, y + h, bg);
    }
    JSONArray legacy = page.optJSONArray("legacyRules");
    if (legacy == null || legacy.isNull(0)) return;
    // Only legacy horizontal pattern 2 was verified against the actual official XML.
    if (legacy.optInt(0) != 2) return;
    float gap = (float) legacy.optDouble(1, 0) * HALF;
    if (gap < 2 || gap > 200) return;
    Paint p = new Paint();
    p.setStrokeWidth(.4f);
    p.setColor((int) legacy.optLong(2, 0xffcaebfdL));
    for (float line = (float) Math.ceil(y / gap) * gap; line < y + h; line += gap)
      c.drawLine(x, line, x + w, line, p);
    if (legacy.optInt(3) == 3) {
      p.setColor((int) legacy.optLong(4, 0xffff5050L));
      p.setStrokeWidth(1);
      c.drawLine(0, y, 0, y + h, p);
    }
  }

  private float content(JSONObject o, float x, float y, float width)
      throws JSONException, IOException {
    if (++contentDepth > 64) throw new IOException("OneNote 内容嵌套过深");
    try {
      return contentBody(o, x, y, width);
    } finally {
      contentDepth--;
    }
  }

  private float contentBody(JSONObject o, float x, float y, float width)
      throws JSONException, IOException {
    switch (o.getString("type")) {
      case "outline":
        return items(
            o.getJSONArray("items"),
            x + num(o, "x", 0) * HALF,
            y + num(o, "y", 0) * HALF,
            num(o, "width", 13) * HALF,
            o.optJSONArray("indents"),
            1,
            0);
      case "text":
        return text(o, x, y, width);
      case "ink":
        ink(o, x, y, false, 0);
        return 0;
      case "image":
        return image(o, x, y);
      case "table":
        return table(o, x, y, width);
      default:
        fail(o.getString("type"));
        return 0;
    }
  }

  private float items(
      JSONArray es, float x, float y, float width, JSONArray indents, int level, int depth)
      throws JSONException, IOException {
    if (depth > 64) throw new IOException("OneNote 段落嵌套过深");
    float start = y;
    int listNumber = 0;
    String listFormat = "";
    for (int i = 0; i < es.length(); i++) {
      JSONObject e = es.getJSONObject(i);
      int next = level + e.optInt("level", 1);
      if (e.optString("type").equals("group")) {
        float ix = indent(indents, level, next);
        y += items(e.getJSONArray("children"), x + ix, y, width - ix, indents, next, depth + 1);
        continue;
      }
      JSONArray lists = e.optJSONArray("lists");
      if (e.optInt("listCount") > 0 && (lists == null || lists.length() != 1)) fail("多个或缺失的列表定义");
      if (lists != null && lists.length() == 1) {
        JSONObject list = lists.getJSONObject(0);
        String format = list.getJSONArray("format").toString();
        if (!format.equals(listFormat)) listNumber = 0;
        listNumber = list.isNull("restart") ? listNumber + 1 : list.getInt("restart");
        listFormat = format;
        marker(list, listNumber, x, y, num(e, "listSpacing", .2f) * HALF);
      } else {
        listNumber = 0;
        listFormat = "";
      }
      JSONArray cs = e.getJSONArray("contents");
      for (int j = 0; j < cs.length(); j++) y += content(cs.getJSONObject(j), x, y, width);
      JSONArray children = e.getJSONArray("children");
      float ix = indent(indents, level, next);
      y += items(children, x + ix, y, width - ix, indents, next, depth + 1);
    }
    return y - start;
  }

  private float indent(JSONArray a, int parent, int level) {
    float v = 0;
    for (int n = parent + 1; n <= level; n++) v += a == null ? .75f : (float) a.optDouble(n, .75);
    return v * HALF;
  }

  private void marker(JSONObject list, int number, float x, float y, float spacing)
      throws JSONException, IOException {
    JSONArray format = list.getJSONArray("format");
    String value;
    if (format.length() >= 2 && format.optInt(0) == 0xfffd) {
      int kind = format.getInt(1);
      if (number < 1 || number > 3999 || kind > 4) {
        fail("列表编号范围或格式");
        return;
      }
      if (kind == 0) value = Integer.toString(number);
      else if (kind <= 2) {
        int remaining = number;
        StringBuilder roman = new StringBuilder();
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        for (int i = 0; i < values.length; i++)
          while (remaining >= values[i]) {
            roman.append(symbols[i]);
            remaining -= values[i];
          }
        value = kind == 1 ? roman.toString() : roman.toString().toLowerCase(Locale.ROOT);
      } else {
        StringBuilder alphabet = new StringBuilder();
        for (int n = number; n > 0; n = (n - 1) / 26)
          alphabet.insert(0, (char) ('A' + (n - 1) % 26));
        value = kind == 3 ? alphabet.toString() : alphabet.toString().toLowerCase(Locale.ROOT);
      }
      for (int i = 2; i < format.length(); i++)
        value += new String(Character.toChars(format.getInt(i)));
    } else if (format.length() == 1) {
      int codepoint = format.getInt(0);
      String font = list.optString("font");
      if (font.equals("Wingdings") && codepoint == 0xa7) codepoint = 0x25aa;
      else if (font.startsWith("Wingdings")) {
        fail("未验证的 Wingdings 列表符号");
        return;
      }
      value = new String(Character.toChars(codepoint));
    } else {
      fail("列表符号格式");
      return;
    }
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor((int) list.optLong("color", Color.BLACK));
    paint.setTextSize(num(list, "size", 22) / 2);
    paint.setTypeface(Typeface.create("sans-serif", typefaceStyle(list)));
    float left = x - Math.max(2, spacing) - paint.measureText(value);
    float baseline = y - paint.ascent();
    final String markerValue = value;
    include(left, y, x, baseline + paint.descent());
    addDrawing(
        new RectF(left, y, x, baseline + paint.descent()),
        c -> c.drawText(markerValue, left, baseline, paint));
    renderedLists++;
  }

  /** Native boxes keep every cell's real rich text/ink/image objects and column widths. */
  private float table(JSONObject table, float x, float y, float availableWidth)
      throws JSONException, IOException {
    JSONArray rows = table.getJSONArray("rows"), widths = table.getJSONArray("columnWidths");
    int columns = table.getInt("columnCount");
    if (columns < 1
        || columns > 256
        || rows.length() != table.getInt("rowCount")
        || rows.length() > 2000
        || (long) rows.length() * columns > 20000) throw new IOException("OneNote 表格尺寸无效或超出限制");
    float tagInset = tags(table, x, y);
    x += tagInset;
    float[] columnWidths = new float[columns];
    for (int column = 0; column < columns; column++) {
      float width = (float) widths.optDouble(column, 0) * HALF;
      columnWidths[column] = finite(width > 0 ? width : Math.max(36, availableWidth / columns));
      if (columnWidths[column] < 5) throw new IOException("OneNote 表格列宽过小");
    }
    float startY = y;
    for (int rowIndex = 0; rowIndex < rows.length(); rowIndex++) {
      JSONArray cells = rows.getJSONArray(rowIndex);
      if (cells.length() != columns) throw new IOException("OneNote 表格单元格数与列数不一致");
      int rowDrawing = drawings.size();
      float rowHeight = 8, cellX = x;
      for (int column = 0; column < columns; column++) {
        JSONObject cell = cells.getJSONObject(column);
        float h =
            items(
                cell.getJSONArray("items"),
                cellX + 2,
                y + 2,
                columnWidths[column] - 4,
                cell.optJSONArray("indents"),
                0,
                0);
        rowHeight = Math.max(rowHeight, h + 4);
        cellX += columnWidths[column];
      }
      cellX = x;
      for (int column = 0; column < columns; column++) {
        JSONObject cell = cells.getJSONObject(column);
        RectF rect = new RectF(cellX, y, cellX + columnWidths[column], y + rowHeight);
        if (!cell.isNull("background")) {
          Paint background = new Paint();
          background.setColor((int) cell.getLong("background"));
          drawings.add(rowDrawing++, new DrawCommand(rect, c -> c.drawRect(rect, background)));
        }
        if (table.optBoolean("borders")) {
          Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
          border.setColor(0xffa3a3a3);
          border.setStyle(Paint.Style.STROKE);
          border.setStrokeWidth(.5f);
          addDrawing(rect, c -> c.drawRect(rect, border));
        }
        include(rect.left, rect.top, rect.right, rect.bottom);
        cellX += columnWidths[column];
      }
      y += rowHeight;
    }
    renderedTables++;
    return y - startY;
  }

  private float tags(JSONObject content, float x, float y) throws JSONException, IOException {
    JSONArray tags = content.optJSONArray("tags");
    if (tags == null) return 0;
    float inset = 0;
    for (int i = 0; i < tags.length(); i++) {
      JSONObject tag = tags.getJSONObject(i);
      String shape = tag.optString("shape");
      renderedTags++;
      if (shape.equals("NoIcon")) continue;
      int color =
          shape.startsWith("Blue")
              ? 0xff2468b4
              : shape.startsWith("Green") ? 0xff379346 : 0xffc79c00;
      Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      paint.setColor(color);
      paint.setStrokeWidth(.8f);
      RectF rect = new RectF(x + inset, y + 1, x + inset + 9, y + 10);
      if (shape.equals("BlueCheckBox")
          || shape.equals("GreenCheckBox")
          || shape.equals("YellowCheckBox")) {
        paint.setStyle(Paint.Style.STROKE);
        boolean checked = tag.optBoolean("completed");
        addDrawing(
            rect,
            c -> {
              c.drawRect(rect, paint);
              if (checked) {
                Path check = new Path();
                check.moveTo(rect.left + 1, rect.top + 4);
                check.lineTo(rect.left + 3.5f, rect.bottom - 1);
                check.lineTo(rect.right - 1, rect.top + 1);
                c.drawPath(check, paint);
              }
            });
      } else if (shape.equals("YellowStar")) {
        Path star = new Path();
        for (int point = 0; point < 10; point++) {
          double angle = -Math.PI / 2 + point * Math.PI / 5;
          float radius = point % 2 == 0 ? 5 : 2;
          float px = rect.centerX() + radius * (float) Math.cos(angle),
              py = rect.centerY() + radius * (float) Math.sin(angle);
          if (point == 0) star.moveTo(px, py);
          else star.lineTo(px, py);
        }
        star.close();
        addDrawing(rect, c -> c.drawPath(star, paint));
      } else {
        fail("标签图标 " + shape);
        continue;
      }
      include(rect.left, rect.top, rect.right, rect.bottom);
      inset += 13;
    }
    return inset;
  }

  private float text(JSONObject o, float x, float y, float width)
      throws JSONException, IOException {
    renderedText++;
    JSONArray math = o.optJSONArray("mathObjects");
    if (o.optInt("mathCount") > 0 && (math == null || math.length() != o.optInt("mathCount")))
      fail("缺失公式结构");
    if (math != null)
      for (int i = 0; i < math.length(); i++) {
        JSONObject m = math.getJSONObject(i);
        String type = m.optString("type");
        if ((!type.equals("SimpleText") && !type.equals("PlainText")) || m.optInt("arguments") != 0)
          fail("排版公式 " + type);
        renderedMath++;
      }
    float tagInset = tags(o, x, y);
    x += tagInset;
    width -= tagInset;
    JSONArray em = o.getJSONArray("embedded");
    if (em.length() > 0) {
      float top = y;
      int start = 0;
      for (int end = 0; end <= em.length(); end++) {
        if (end < em.length() && !em.getJSONObject(end).optString("type").equals("break")) continue;
        float height = 0;
        for (int k = start; k < end; k++) {
          JSONObject e = em.getJSONObject(k);
          JSONArray b = e.optJSONArray("displayBox");
          height = Math.max(height, b != null ? (float) b.optDouble(3) : num(e, "height", 0));
        }
        float cursor = x;
        for (int k = start; k < end; k++) {
          JSONObject e = em.getJSONObject(k);
          if (e.optString("type").equals("space")) {
            cursor += num(e, "width", 0) * HALF;
            continue;
          }
          JSONObject ink = e.getJSONObject("ink");
          JSONArray b = ink.optJSONArray("bbox");
          renderedEmbedded++;
          if (b == null) {
            fail("缺少嵌入墨迹边界");
            continue;
          }
          ink(
              ink,
              cursor - (float) b.getDouble(0) * INK,
              top - (float) b.getDouble(1) * INK,
              true,
              0);
          cursor += (float) b.getDouble(2) * INK;
        }
        top += height * HALF;
        start = end + 1;
      }
      return top - y;
    }
    String value = o.optString("text");
    if (value.isEmpty()) return 0;
    JSONObject style = o.getJSONObject("style");
    if (style.optBoolean("hidden")) return 0;
    TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    p.setTextSize(num(style, "size", 22) / 2);
    p.setColor((int) style.optLong("color", Color.BLACK));
    p.setTypeface(Typeface.create(style.optString("font", "sans-serif"), typefaceStyle(style)));
    p.setUnderlineText(style.optBoolean("underline"));
    p.setStrikeThruText(style.optBoolean("strike"));
    SpannableString styled = new SpannableString(value);
    JSONArray tagStyles = o.optJSONArray("tags");
    if (tagStyles != null)
      for (int i = 0; i < tagStyles.length(); i++) {
        JSONObject tag = tagStyles.getJSONObject(i);
        if (!tag.isNull("highlight"))
          span(styled, new BackgroundColorSpan((int) tag.getLong("highlight")), 0, value.length());
        if (!tag.isNull("color"))
          span(styled, new ForegroundColorSpan((int) tag.getLong("color")), 0, value.length());
      }
    if (!style.isNull("highlight"))
      span(styled, new BackgroundColorSpan((int) style.getLong("highlight")), 0, value.length());
    if (style.optBoolean("super")) span(styled, new SuperscriptSpan(), 0, value.length());
    if (style.optBoolean("sub")) span(styled, new SubscriptSpan(), 0, value.length());
    JSONArray runs = o.optJSONArray("runs"), indices = o.optJSONArray("indices");
    int runStart = 0;
    if (runs != null)
      for (int j = 0; j < runs.length(); j++) {
        JSONObject run = runs.getJSONObject(j);
        int end = indices != null && j < indices.length() ? indices.getInt(j) : value.length();
        if (end < runStart || end > value.length()) throw new IOException("OneNote 文本样式位置无效");
        if (end > runStart) {
          int type = typefaceStyle(run);
          if (type != 0) span(styled, new StyleSpan(type), runStart, end);
          if (!run.isNull("size"))
            span(
                styled,
                new AbsoluteSizeSpan(Math.max(1, Math.round(num(run, "size", 22) / 2))),
                runStart,
                end);
          if (!run.isNull("font"))
            span(styled, new TypefaceSpan(run.getString("font")), runStart, end);
          if (!run.isNull("color"))
            span(styled, new ForegroundColorSpan((int) run.getLong("color")), runStart, end);
          if (!run.isNull("highlight"))
            span(styled, new BackgroundColorSpan((int) run.getLong("highlight")), runStart, end);
          if (run.optBoolean("underline")) span(styled, new UnderlineSpan(), runStart, end);
          if (run.optBoolean("strike")) span(styled, new StrikethroughSpan(), runStart, end);
          if (run.optBoolean("super")) span(styled, new SuperscriptSpan(), runStart, end);
          if (run.optBoolean("sub")) span(styled, new SubscriptSpan(), runStart, end);
          if (run.optBoolean("hidden"))
            span(
                styled,
                new ReplacementSpan() {
                  @Override
                  public int getSize(
                      Paint paint,
                      CharSequence text,
                      int start,
                      int end,
                      Paint.FontMetricsInt metrics) {
                    return 0;
                  }

                  @Override
                  public void draw(
                      Canvas canvas,
                      CharSequence text,
                      int start,
                      int end,
                      float x,
                      int top,
                      int y,
                      int bottom,
                      Paint paint) {}
                },
                runStart,
                end);
        }
        runStart = end;
      }
    int layoutWidth = Math.max(1, Math.min(20000, Math.round(width)));
    String alignment = o.optString("alignment");
    Layout.Alignment align =
        alignment.equals("Center")
            ? Layout.Alignment.ALIGN_CENTER
            : alignment.equals("Right")
                ? Layout.Alignment.ALIGN_OPPOSITE
                : Layout.Alignment.ALIGN_NORMAL;
    StaticLayout layout =
        StaticLayout.Builder.obtain(styled, 0, styled.length(), p, layoutWidth)
            .setIncludePad(false)
            .setAlignment(align)
            .build();
    float before = num(o, "before", 0) * HALF, after = num(o, "after", 0) * HALF, dy = y + before;
    final float drawX = x;
    RectF textBounds = new RectF();
    for (int line = 0; line < layout.getLineCount(); line++) {
      RectF lineRect =
          new RectF(
              drawX + layout.getLineLeft(line),
              dy + layout.getLineTop(line),
              drawX + layout.getLineRight(line),
              dy + layout.getLineBottom(line));
      if (lineRect.width() > 0) {
        textBounds.union(lineRect);
      }
    }
    include(textBounds.left, textBounds.top, textBounds.right, textBounds.bottom);
    addDrawing(
        textBounds,
        c -> {
          c.save();
          c.translate(drawX, dy);
          layout.draw(c);
          c.restore();
        });
    return before + layout.getHeight() + after;
  }

  private static void span(SpannableString s, Object span, int start, int end) {
    s.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private static int typefaceStyle(JSONObject style) {
    if (style.optBoolean("bold"))
      return style.optBoolean("italic") ? Typeface.BOLD_ITALIC : Typeface.BOLD;
    return style.optBoolean("italic") ? Typeface.ITALIC : Typeface.NORMAL;
  }

  private void ink(JSONObject o, float x, float y, boolean embedded, int depth)
      throws JSONException, IOException {
    if (depth > 32) throw new IOException("OneNote 墨迹嵌套过深");
    if (!embedded) {
      x += num(o, "x", 0) * HALF;
      y += num(o, "y", 0) * HALF;
    }
    JSONArray children = o.getJSONArray("children");
    if (o.optBoolean("empty")) {
      if (children.length() != 0 || o.getJSONArray("strokes").length() != 0)
        throw new IOException("OneNote 空墨迹容器仍引用可见笔迹");
      renderedEmptyInk++;
    }
    for (int i = 0; i < children.length(); i++)
      ink(children.getJSONObject(i), x, y, embedded, depth + 1);
    JSONArray ss = o.getJSONArray("strokes");
    for (int i = 0; i < ss.length(); i++) {
      JSONObject s = ss.getJSONObject(i);
      JSONArray ps = s.getJSONArray("points");
      points += ps.length();
      if (s.optInt("tip", 0) != 0
          || (!s.isNull("height")
              && !s.isNull("width")
              && Math.abs(num(s, "height", 1) - num(s, "width", 1)) > .01f)) fail("非圆形笔尖");
      if (points > MAX_POINTS) throw new IOException("OneNote 墨迹超过 200 万点限制");
      if (ps.length() == 0) {
        fail("空墨迹路径");
        continue;
      }
      Path path = new Path();
      float sx = 0, sy = 0;
      for (int j = 0; j < ps.length(); j++) {
        JSONArray pt = ps.getJSONArray(j);
        float px = finite((float) pt.getDouble(0) * INK + x),
            py = finite((float) pt.getDouble(1) * INK + y);
        if (j == 0) {
          path.moveTo(px, py);
          sx = px;
          sy = py;
        } else path.lineTo(px, py);
      }
      if (ps.length() == 1) path.lineTo(sx + .01f, sy);
      long col = s.optLong("color", 0);
      int rgb = Color.rgb((int) col & 255, (int) (col >> 8) & 255, (int) (col >> 16) & 255);
      Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      paint.setColor(rgb);
      paint.setAlpha(255 - s.optInt("transparency", 0));
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(Math.max(.1f, num(s, "width", 1) * INK));
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      RectF r = new RectF();
      path.computeBounds(r, true);
      r.inset(-paint.getStrokeWidth(), -paint.getStrokeWidth());
      include(r.left, r.top, r.right, r.bottom);
      addDrawing(r, c -> c.drawPath(path, paint));
      renderedStrokes++;
    }
  }

  private float image(JSONObject o, float x, float y) throws JSONException, IOException {
    if (o.optInt("embedCount") > 0) fail("图片关联的网页或媒体对象");
    x += tags(o, x, y);
    if (!o.isNull("error")) {
      fail("缺失图片");
      return 0;
    }
    JSONArray data = o.getJSONArray("data");
    if (data.length() > 32 * 1024 * 1024) throw new IOException("OneNote 单张图片过大");
    byte[] bytes = new byte[data.length()];
    for (int j = 0; j < bytes.length; j++) bytes[j] = (byte) data.getInt(j);
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (options.outWidth <= 0
        || options.outHeight <= 0
        || options.outWidth > 20000
        || options.outHeight > 20000) {
      fail("不支持的图片格式");
      return 0;
    }
    BitmapFactory.Options decode = new BitmapFactory.Options();
    decode.inSampleSize = 1;
    while (Math.max(options.outWidth, options.outHeight) / decode.inSampleSize > 4096)
      decode.inSampleSize *= 2;
    long needed =
        (long) Math.ceil(options.outWidth / (double) decode.inSampleSize)
            * (long) Math.ceil(options.outHeight / (double) decode.inSampleSize)
            * 4;
    if (bitmapBytes + needed > 64L * 1024 * 1024) throw new IOException("OneNote 图片解码总量超过 64 MiB");
    Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decode);
    if (image == null) {
      fail("图片解码");
      return 0;
    }
    bitmaps.add(image);
    bitmapBytes += image.getAllocationByteCount();
    float ix = x + num(o, "x", 0) * HALF,
        iy = y + num(o, "y", 0) * HALF,
        w = num(o, "width", options.outWidth / 48f) * HALF,
        h = num(o, "height", options.outHeight / 48f) * HALF;
    RectF rect = new RectF(ix, iy, ix + w, iy + h);
    include(rect.left, rect.top, rect.right, rect.bottom);
    addDrawing(rect, c -> c.drawBitmap(image, null, rect, new Paint(Paint.FILTER_BITMAP_FLAG)));
    renderedImages++;
    return h;
  }

  private void include(float l, float t, float r, float b) throws IOException {
    finite(l);
    finite(t);
    finite(r);
    finite(b);
    bounds.union(l, t, r, b);
  }

  private static float finite(float v) throws IOException {
    if (!Float.isFinite(v) || Math.abs(v) > 1_000_000) throw new IOException("OneNote 坐标超出范围");
    return v;
  }

  private static float num(JSONObject o, String key, float fallback) {
    return o.isNull(key) ? fallback : (float) o.optDouble(key, fallback);
  }

  private void fail(String kind) {
    unsupported.put(kind, unsupported.getOrDefault(kind, 0) + 1);
  }

  private IOException unsupportedError() {
    return new IOException("该 OneNote 含尚未完整支持的对象，已保留源文件，未导入残缺页：" + unsupported);
  }
}
