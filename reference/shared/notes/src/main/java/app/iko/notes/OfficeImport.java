package app.iko.notes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Converts shared Office files into editable IKO text pages; source layout is intentionally
 * flattened.
 */
public final class OfficeImport {
  private static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;
  private static final int MAX_XML_BYTES = 12 * 1024 * 1024;
  private static final int PAGE_TEXT_LIMIT = 1800;
  private static final String REL_NS =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

  private OfficeImport() {}

  public static NoteDocument importDocument(String name, InputStream input) throws IOException {
    if (input == null) throw new IOException("无法打开共享文件");
    byte[] source = readBounded(input, MAX_INPUT_BYTES);
    String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
    NoteDocument document = NoteDocument.create(title(name));
    document.pages.clear();
    if (lower.endsWith(".docx")) importDocx(document, unzip(source));
    else if (lower.endsWith(".xlsx")) importXlsx(document, unzip(source));
    else if (lower.endsWith(".txt")
        || lower.endsWith(".md")
        || lower.endsWith(".html")
        || lower.endsWith(".htm")) {
      String text = new String(source, StandardCharsets.UTF_8);
      if (lower.endsWith(".html") || lower.endsWith(".htm"))
        text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString();
      addTextPages(document, text);
    } else if (lower.endsWith(".doc") || lower.endsWith(".xls")) {
      throw new IOException("旧版 .doc/.xls 是二进制格式；请从 QQ/微信用 Office 另存为 .docx/.xlsx 后再分享");
    } else if (lower.endsWith(".one") || lower.endsWith(".onepkg")) {
      throw new IOException("OneNote 请使用导入菜单的“OneNote 连续画布”入口，可在平板本地转换并书写批注");
    } else {
      throw new IOException("支持 .docx、.xlsx、.pdf、图片、.txt、.md 与 .html 文件");
    }
    document.modifiedAt = System.currentTimeMillis();
    return document;
  }

  private static void importDocx(NoteDocument note, Map<String, byte[]> entries)
      throws IOException {
    byte[] xml = entries.get("word/document.xml");
    if (xml == null) throw new IOException("Word 文件缺少 document.xml，可能已损坏");
    Document body = parseXml(xml);
    NodeList paragraphs = body.getElementsByTagNameNS("*", "p");
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < paragraphs.getLength(); i++) {
      Node paragraph = paragraphs.item(i);
      NodeList runs = ((Element) paragraph).getElementsByTagNameNS("*", "t");
      StringBuilder line = new StringBuilder();
      for (int k = 0; k < runs.getLength(); k++) line.append(runs.item(k).getTextContent());
      if (line.length() > 0) text.append(line);
      text.append('\n');
    }
    addTextPages(note, text.toString());
  }

  private static void importXlsx(NoteDocument note, Map<String, byte[]> entries)
      throws IOException {
    byte[] workbookBytes = entries.get("xl/workbook.xml");
    if (workbookBytes == null) throw new IOException("Excel 文件缺少 workbook.xml，可能已损坏");
    ArrayList<String> shared = new ArrayList<>();
    byte[] sharedBytes = entries.get("xl/sharedStrings.xml");
    if (sharedBytes != null) {
      NodeList values = parseXml(sharedBytes).getElementsByTagNameNS("*", "si");
      for (int i = 0; i < values.getLength(); i++) shared.add(values.item(i).getTextContent());
    }
    Document workbook = parseXml(workbookBytes);
    Map<String, String> targets = new HashMap<>();
    byte[] relBytes = entries.get("xl/_rels/workbook.xml.rels");
    if (relBytes != null) {
      NodeList relations = parseXml(relBytes).getElementsByTagNameNS("*", "Relationship");
      for (int i = 0; i < relations.getLength(); i++) {
        Element relation = (Element) relations.item(i);
        targets.put(
            relation.getAttribute("Id"), normalizeSheetTarget(relation.getAttribute("Target")));
      }
    }
    NodeList sheets = workbook.getElementsByTagNameNS("*", "sheet");
    if (sheets.getLength() == 0) {
      for (String path : entries.keySet())
        if (path.matches("xl/worksheets/sheet[0-9]+\\.xml"))
          addSheet(note, path, "工作表", entries, shared);
    } else {
      for (int i = 0; i < sheets.getLength(); i++) {
        Element sheet = (Element) sheets.item(i);
        String path = targets.get(sheet.getAttributeNS(REL_NS, "id"));
        if (path != null && entries.containsKey(path))
          addSheet(note, path, sheet.getAttribute("name"), entries, shared);
      }
    }
    if (note.pages.isEmpty()) throw new IOException("Excel 工作簿没有可读取的工作表");
  }

  private static void addSheet(
      NoteDocument note,
      String path,
      String sheetName,
      Map<String, byte[]> entries,
      List<String> shared)
      throws IOException {
    Document xml = parseXml(entries.get(path));
    NodeList rows = xml.getElementsByTagNameNS("*", "row");
    StringBuilder text = new StringBuilder(sheetName).append('\n');
    int cellsRead = 0;
    for (int i = 0; i < rows.getLength(); i++) {
      Element row = (Element) rows.item(i);
      NodeList cells = row.getElementsByTagNameNS("*", "c");
      Map<Integer, String> values = new java.util.TreeMap<>();
      int maxColumn = -1;
      for (int j = 0; j < cells.getLength(); j++) {
        Element cell = (Element) cells.item(j);
        int column = columnIndex(cell.getAttribute("r"));
        if (column < 0) throw new IOException("Excel 单元格位置无效或超出支持范围，无法完整导入工作表");
        if (column > 40) throw new IOException("当前每个工作表最多完整导入到 AO 列；请拆分工作表后重试");
        String type = cell.getAttribute("t");
        NodeList valueNodes = cell.getElementsByTagNameNS("*", "v");
        String value = valueNodes.getLength() == 0 ? "" : valueNodes.item(0).getTextContent();
        if ("s".equals(type) && !value.isEmpty()) {
          try {
            int sharedIndex = Integer.parseInt(value);
            value = sharedIndex >= 0 && sharedIndex < shared.size() ? shared.get(sharedIndex) : "";
          } catch (NumberFormatException ignored) {
            value = "";
          }
        } else if ("inlineStr".equals(type)) value = cell.getTextContent();
        values.put(column, value);
        maxColumn = Math.max(maxColumn, column);
        if (++cellsRead > 20000) throw new IOException("工作表超过 20,000 个单元格，请拆分后导入");
      }
      for (int column = 0; column <= maxColumn; column++) {
        if (column > 0) text.append("  |  ");
        text.append(values.getOrDefault(column, ""));
      }
      text.append('\n');
    }
    addTextPages(note, text.toString());
  }

  private static int columnIndex(String reference) {
    int result = 0, count = 0;
    for (int i = 0; i < reference.length(); i++) {
      char c = Character.toUpperCase(reference.charAt(i));
      if (c < 'A' || c > 'Z') break;
      result = result * 26 + c - 'A' + 1;
      if (++count > 3) return -1;
    }
    return count == 0 ? -1 : result - 1;
  }

  private static String normalizeSheetTarget(String target) {
    String path = target.replace('\\', '/');
    while (path.startsWith("/")) path = path.substring(1);
    if (path.startsWith("xl/")) return path;
    if (path.startsWith("worksheets/")) return "xl/" + path;
    return "xl/" + path;
  }

  private static void addTextPages(NoteDocument note, String text) throws IOException {
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
    if (normalized.isEmpty()) normalized = "（文件中没有可提取的文字）";
    int start = 0;
    while (start < normalized.length()) {
      int end = Math.min(normalized.length(), start + PAGE_TEXT_LIMIT);
      if (end < normalized.length()) {
        int boundary = normalized.lastIndexOf('\n', end);
        if (boundary > start + PAGE_TEXT_LIMIT / 2) end = boundary + 1;
      }
      NoteDocument.Page page = NoteDocument.newPage();
      page.paper = "blank";
      NoteDocument.Element element = new NoteDocument.Element();
      element.kind = "text";
      element.text = normalized.substring(start, end).trim();
      element.x = 110;
      element.y = 100;
      element.width = page.width - 220;
      element.height = page.height - 200;
      element.textSize = 28;
      element.color = 0xff252b38;
      page.elements.add(element);
      note.pages.add(page);
      if (note.pages.size() > NoteDocument.MAX_PAGES) throw new IOException("文件内容超过 500 页，请拆分后导入");
      start = end;
    }
  }

  private static Map<String, byte[]> unzip(byte[] source) throws IOException {
    LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
    int count = 0, total = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(source))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (++count > 512) throw new IOException("Office 文件条目过多");
        String name = entry.getName().replace('\\', '/');
        boolean relevant = !entry.isDirectory() && isRelevantOfficeXml(name);
        ByteArrayOutputStream output = relevant ? new ByteArrayOutputStream() : null;
        OutputStream sink = output == null ? discardStream() : output;
        long remaining = MAX_INPUT_BYTES - total;
        if (remaining <= 0) throw new IOException("Office 文件解压后过大");
        long copied = ArchivePolicy.copyBounded(zip, sink, remaining);
        total += (int) copied;
        if (relevant && copied > MAX_XML_BYTES) throw new IOException("Office XML 文件过大");
        if (relevant) entries.put(name, output.toByteArray());
        zip.closeEntry();
      }
    }
    return entries;
  }

  private static boolean isRelevantOfficeXml(String name) {
    return "word/document.xml".equals(name)
        || "xl/workbook.xml".equals(name)
        || "xl/_rels/workbook.xml.rels".equals(name)
        || "xl/sharedStrings.xml".equals(name)
        || name.matches("xl/worksheets/sheet[0-9]+\\.xml");
  }

  private static OutputStream discardStream() {
    return new OutputStream() {
      @Override
      public void write(int value) {}

      @Override
      public void write(byte[] buffer, int offset, int length) {}
    };
  }

  private static Document parseXml(byte[] xml) throws IOException {
    try {
      return RichTextImport.xml(xml);
    } catch (Exception error) {
      throw new IOException("Office 文件 XML 无法解析", error);
    }
  }

  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    try (input;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (count > limit - output.size()) throw new IOException("共享文件超过 32 MB");
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  private static String title(String filename) {
    if (filename == null || filename.trim().isEmpty()) return "导入的 Office 文档";
    int dot = filename.lastIndexOf('.');
    return dot > 0 ? filename.substring(0, dot) : filename;
  }
}
