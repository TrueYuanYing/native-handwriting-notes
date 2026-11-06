package app.iko.notes;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * Semantic editable import, with original image bytes; does not pretend to be Word's layout engine.
 */
final class RichTextImport {
  static final class Result {
    final NoteDocument document;
    final LinkedHashSet<String> warnings = new LinkedHashSet<>();

    Result(NoteDocument doc) {
      document = doc;
    }

    String description() {
      return warnings.isEmpty() ? "已导入为可编辑图文笔记" : String.join("；", warnings);
    }
  }

  private final Result result;
  private NoteDocument.Page page;
  private float y = 80;

  private RichTextImport(String name) {
    result = new Result(NoteDocument.create(name.replaceFirst("\\.[^.]+$", "")));
    page = result.document.pages.get(0);
    page.paper = "blank";
  }

  static NoteDocument importDocument(Context context, Uri uri, String name) throws IOException {
    return importWithReport(context, uri, name).document;
  }

  static Result importWithReport(Context context, Uri uri, String name) throws IOException {
    File source = NotebookArchive.retain(context, uri, name);
    RichTextImport r = new RichTextImport(name);
    try {
      if (name.toLowerCase(Locale.ROOT).endsWith(".docx")) {
        try (NotebookArchive archive =
            new NotebookArchive(source, new File(context.getFilesDir(), "media"))) {
          r.word(archive);
        }
      } else {
        if (source.length() > 8 * 1024 * 1024) throw new IOException("文本文件超过 8 MB");
        r.markdown(new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
      }
      r.result.document.tags.add("导入图文");
      r.result.document.validate();
      return r.result;
    } catch (Exception e) {
      throw new IOException("图文导入失败：" + e.getMessage() + "。原件已保留", e);
    }
  }

  private void next() throws IOException {
    if (result.document.pages.size() >= NoteDocument.MAX_PAGES) throw new IOException("导入页数超过限制");
    page = NoteDocument.newPage();
    page.paper = "blank";
    result.document.pages.add(page);
    y = 80;
  }

  private static float height(String text, float size, float width) {
    TextPaint paint = new TextPaint(3);
    paint.setTextSize(size);
    return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, (int) width))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(true)
        .setLineSpacing(4, 1.1f)
        .build()
        .getHeight();
  }

  private void text(String text, float size) throws IOException {
    if (text.isEmpty()) {
      y += 12;
      return;
    }
    float h = height(text, size, 1240);
    if (h > 1760) {
      int cut = Math.max(1, text.length() / 2);
      text(text.substring(0, cut), size);
      text(text.substring(cut), size);
      return;
    }
    if (y + h > 1900) next();
    addText(text, size, 80, y, 1240, h);
    y += h + 16;
  }

  private void addText(String text, float size, float x, float top, float width, float height) {
    NoteDocument.Element e = new NoteDocument.Element();
    e.text = text;
    e.textSize = size;
    e.x = x;
    e.y = top;
    e.width = width;
    e.height = height;
    page.elements.add(e);
  }

  private void row(List<String> cells, float size) throws IOException {
    if (cells.isEmpty()) return;
    float width = 1240f / cells.size(), h = 40;
    for (String cell : cells) h = Math.max(h, height(cell, size, width - 16) + 12);
    if (h > 1760) {
      for (String cell : cells) text(cell, size);
      return;
    }
    if (y + h > 1900) next();
    for (int i = 0; i < cells.size(); i++)
      addText(cells.get(i), size, 80 + i * width, y, width - 16, h);
    y += h + 8;
  }

  private void markdown(String source) throws IOException {
    boolean code = false;
    for (String raw : source.replace("\r", "").split("\n", -1)) {
      if (raw.stripLeading().startsWith("```") || raw.stripLeading().startsWith("~~~")) {
        code = !code;
        continue;
      }
      if (code) {
        text(raw, 25);
        continue;
      }
      if (raw.matches("\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*")) continue;
      if (raw.trim().startsWith("|") && raw.trim().endsWith("|")) {
        row(
            Arrays.asList(raw.trim().substring(1, raw.trim().length() - 1).split("\\|", -1))
                .stream()
                .map(String::trim)
                .collect(java.util.stream.Collectors.toList()),
            30);
        continue;
      }
      Matcher image = Pattern.compile("!\\[([^]]*)\\]\\(([^)]+)\\)").matcher(raw);
      if (image.find()) {
        result.warnings.add("Markdown 外部图片未自动下载，已保留链接文字");
        text(raw, 28);
        continue;
      }
      Matcher heading = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(raw);
      if (heading.matches())
        text(heading.group(2), Math.max(32, 60 - heading.group(1).length() * 5));
      else text(raw.replaceFirst("^\\s*[-*+]\\s+", "• "), 30);
    }
    result.warnings.add("Markdown 公式保留可编辑源代码，未执行 HTML 或外部资源");
  }

  static org.w3c.dom.Document xml(byte[] bytes) throws Exception {
    String markup =
        new String(bytes, StandardCharsets.UTF_8).replace("\u0000", "").toUpperCase(Locale.ROOT);
    if (markup.contains("<!DOCTYPE") || markup.contains("<!ENTITY"))
      throw new IOException("文档 XML 不允许 DTD 或自定义实体");
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setExpandEntityReferences(false);
    javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
    builder.setEntityResolver(
        (publicId, systemId) -> {
          throw new org.xml.sax.SAXException("不允许读取外部 XML 资源");
        });
    return builder.parse(new ByteArrayInputStream(bytes));
  }

  private static String attr(Element e, String name) {
    if (e.hasAttribute(name)) return e.getAttribute(name);
    NamedNodeMap attrs = e.getAttributes();
    for (int i = 0; i < attrs.getLength(); i++)
      if (name.equals(attrs.item(i).getLocalName())) return attrs.item(i).getNodeValue();
    return "";
  }

  private void word(NotebookArchive a) throws Exception {
    org.w3c.dom.Document doc = xml(a.read("word/document.xml"));
    Map<String, String> media = new HashMap<>();
    if (a.has("word/_rels/document.xml.rels")) {
      NodeList rels =
          xml(a.read("word/_rels/document.xml.rels")).getElementsByTagNameNS("*", "Relationship");
      for (int i = 0; i < rels.getLength(); i++) {
        Element e = (Element) rels.item(i);
        if (!"External".equals(attr(e, "TargetMode"))) {
          java.nio.file.Path path =
              java.nio.file.Paths.get("word").resolve(attr(e, "Target")).normalize();
          media.put(attr(e, "Id"), path.toString().replace('\\', '/'));
        }
      }
    }
    Node body = doc.getElementsByTagNameNS("*", "body").item(0);
    for (Node n = body.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (!(n instanceof Element)) continue;
      Element e = (Element) n;
      if ("p".equals(e.getLocalName())) paragraph(e, a, media);
      else if ("tbl".equals(e.getLocalName())) {
        List<Node> rows = new ArrayList<>();
        for (Node row = e.getFirstChild(); row != null; row = row.getNextSibling())
          if ("tr".equals(row.getLocalName())) rows.add(row);
        for (int i = 0; i < rows.size(); i++) {
          List<String> cells = new ArrayList<>();
          for (Node cell = rows.get(i).getFirstChild(); cell != null; cell = cell.getNextSibling())
            if ("tc".equals(cell.getLocalName())) cells.add(content(cell));
          row(cells, 28);
          for (Node cell = rows.get(i).getFirstChild(); cell != null; cell = cell.getNextSibling())
            if (cell instanceof Element && "tc".equals(cell.getLocalName()))
              images((Element) cell, a, media);
        }
      } else if (!"sectPr".equals(e.getLocalName())) result.warnings.add("部分 Word 扩展对象未转换，原件已保留");
    }
    if (doc.getElementsByTagNameNS("*", "oMath").getLength() > 0)
      result.warnings.add("Word 公式保留可编辑文字，复杂排版需以 Word 或原 PDF 为准");
    for (String kind : new String[] {"pict", "chart", "OLEObject", "altChunk", "hdr", "ftr"})
      if (doc.getElementsByTagNameNS("*", kind).getLength() > 0)
        result.warnings.add("Word 旧式绘图、图表、嵌入文件及页眉页脚尚未转换，原件已保留");
    if (a.names.stream()
        .anyMatch(path -> path.startsWith("word/header") || path.startsWith("word/footer")))
      result.warnings.add("Word 页眉页脚尚未转换，原件已保留");
    result.warnings.add("已保留标题、段落、表格和原图；Word 字体及浮动排版按 IKO 重新排页");
  }

  private static String content(Node n) {
    StringBuilder b = new StringBuilder();
    if ("t".equals(n.getLocalName())) return n.getTextContent();
    if ("tab".equals(n.getLocalName())) return "  ";
    if ("br".equals(n.getLocalName())) return "\n";
    for (Node child = n.getFirstChild(); child != null; child = child.getNextSibling())
      b.append(content(child));
    return b.toString();
  }

  private void paragraph(Element e, NotebookArchive a, Map<String, String> media) throws Exception {
    NodeList br = e.getElementsByTagNameNS("*", "br");
    for (int i = 0; i < br.getLength(); i++)
      if ("page".equals(attr((Element) br.item(i), "type"))) {
        next();
        break;
      }
    float size = 30;
    NodeList styles = e.getElementsByTagNameNS("*", "pStyle");
    if (styles.getLength() > 0
        && attr((Element) styles.item(0), "val").toLowerCase(Locale.ROOT).contains("heading"))
      size = 52;
    text(content(e).trim(), size);
    images(e, a, media);
  }

  private void images(Element e, NotebookArchive a, Map<String, String> media) throws Exception {
    NodeList images = e.getElementsByTagNameNS("*", "blip");
    for (int i = 0; i < images.getLength(); i++) {
      String path = media.get(attr((Element) images.item(i), "embed"));
      if (path == null || !a.has(path)) {
        result.warnings.add("存在未载入的 Word 外链图片");
        continue;
      }
      File original = a.asset(path, ".img");
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(original.getPath(), o);
      if (o.outWidth <= 0 || o.outHeight <= 0) {
        result.warnings.add("部分图片编码无法显示，原件已保留");
        continue;
      }
      float width = Math.min(900, o.outWidth * 2f), h = width * o.outHeight / o.outWidth;
      if (h > 1600) {
        width *= 1600 / h;
        h = 1600;
      }
      if (y + h > 1900) next();
      NoteDocument.Element image = new NoteDocument.Element();
      image.kind = "image";
      image.path = original.getPath();
      image.x = 80;
      image.y = y;
      image.width = width;
      image.height = h;
      page.elements.add(image);
      y += h + 24;
    }
  }
}
