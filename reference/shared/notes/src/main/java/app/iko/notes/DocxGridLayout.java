package app.iko.notes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.xmlpull.v1.XmlPullParser;

/** Adjust the engine's CJK metric compatibility flag without rewriting source DOCX formatting. */
final class DocxGridLayout {
  private static final String WORD_NS =
      "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

  static boolean hasGrid(File source) throws IOException {
    try (ZipFile zip = new ZipFile(source);
        InputStream in = zip.getInputStream(zip.getEntry("word/document.xml"))) {
      XmlPullParser xml = android.util.Xml.newPullParser();
      xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
      xml.setInput(in, null);
      for (int event = xml.getEventType();
          event != XmlPullParser.END_DOCUMENT;
          event = xml.next()) {
        if (event == XmlPullParser.START_TAG
            && "docGrid".equals(xml.getName())
            && WORD_NS.equals(xml.getNamespace())) {
          String type = xml.getAttributeValue(WORD_NS, "type");
          if (type == null || !type.equals("default")) return true;
        }
      }
      return false;
    } catch (org.xmlpull.v1.XmlPullParserException e) {
      throw new IOException("Word 页面网格信息无效", e);
    }
  }

  static void correctMetrics(File original, File output) throws IOException {
    String styles;
    try (ZipFile zip = new ZipFile(original);
        InputStream in = zip.getInputStream(zip.getEntry("styles.xml"))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      NotebookArchive.copy(in, buffer, 8L * 1024 * 1024);
      styles = buffer.toString(StandardCharsets.UTF_8.name());
    }
    double pitch = gridPitch(styles);
    String content;
    try (ZipFile zip = new ZipFile(original);
        InputStream in = zip.getInputStream(zip.getEntry("content.xml"))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      NotebookArchive.copy(in, buffer, 32L * 1024 * 1024);
      content = buffer.toString(StandardCharsets.UTF_8.name());
    }
    Map<String, String> parents = new HashMap<>();
    Map<String, Boolean> snap = new HashMap<>();
    collectStyles(styles, parents, snap);
    collectStyles(content, parents, snap);
    try (ZipInputStream in = new ZipInputStream(new FileInputStream(original));
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        ZipEntry next = new ZipEntry(entry.getName());
        out.putNextEntry(next);
        if (entry.getName().equals("settings.xml")) {
          ByteArrayOutputStream buffer = new ByteArrayOutputStream();
          NotebookArchive.copy(in, buffer, 8L * 1024 * 1024);
          String xml = buffer.toString(StandardCharsets.UTF_8.name());
          xml =
              xml.replaceAll(
                  "(<config:config-item[^>]*config:name=\"MsWordCompGridMetrics\"[^>]*>)true(</config:config-item>)",
                  "$1false$2");
          out.write(xml.getBytes(StandardCharsets.UTF_8));
        } else if (pitch > 0
            && (entry.getName().equals("styles.xml") || entry.getName().equals("content.xml"))) {
          ByteArrayOutputStream buffer = new ByteArrayOutputStream();
          NotebookArchive.copy(in, buffer, 32L * 1024 * 1024);
          String xml = buffer.toString(StandardCharsets.UTF_8.name());
          // Writer multiplies the rounded grid height; Word uses the multiple as a minimum.
          Matcher paragraphs = STYLE.matcher(xml);
          StringBuffer corrected = new StringBuffer();
          while (paragraphs.find()) {
            String properties = paragraphs.group();
            Matcher spacing = Pattern.compile("fo:line-height=\"([0-9.]+)%\"").matcher(properties);
            String attributes = paragraphs.group(2);
            String name =
                "default-style".equals(paragraphs.group(1))
                    ? ""
                    : attribute(attributes, "style:name");
            if ("paragraph".equals(attribute(attributes, "style:family"))
                && participates(name, parents, snap)
                && spacing.find())
              properties =
                  spacing.replaceFirst(
                      "style:line-height-at-least=\""
                          + (pitch * Double.parseDouble(spacing.group(1)) / 100)
                          + "pt\"");
            paragraphs.appendReplacement(corrected, Matcher.quoteReplacement(properties));
          }
          paragraphs.appendTail(corrected);
          out.write(corrected.toString().getBytes(StandardCharsets.UTF_8));
        } else NotebookArchive.copy(in, out, 128L * 1024 * 1024);
        out.closeEntry();
        in.closeEntry();
      }
    }
  }

  private static final Pattern STYLE =
      Pattern.compile(
          "<style:(style|default-style)\\b([^>]*?)(?:/>|>(.*?)</style:\\1>)", Pattern.DOTALL);

  private static String attribute(String xml, String name) {
    Matcher value =
        Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(xml);
    return value.find() ? value.group(1) : null;
  }

  private static void collectStyles(
      String xml, Map<String, String> parents, Map<String, Boolean> snap) {
    Matcher styles = STYLE.matcher(xml);
    while (styles.find()) {
      if (!"paragraph".equals(attribute(styles.group(2), "style:family"))) continue;
      String name =
          "default-style".equals(styles.group(1)) ? "" : attribute(styles.group(2), "style:name");
      parents.put(name, attribute(styles.group(2), "style:parent-style-name"));
      String value =
          styles.group(3) == null ? null : attribute(styles.group(3), "style:snap-to-layout-grid");
      if (value != null) snap.put(name, Boolean.parseBoolean(value));
    }
  }

  private static boolean participates(
      String name, Map<String, String> parents, Map<String, Boolean> snap) {
    for (int remaining = parents.size() + 1;
        name != null && remaining-- > 0;
        name = parents.get(name)) if (snap.containsKey(name)) return snap.get(name);
    return snap.getOrDefault("", true);
  }

  private static double gridPitch(String styles) {
    Matcher layouts =
        Pattern.compile("<style:page-layout\\s[^>]*>(.*?)</style:page-layout>", Pattern.DOTALL)
            .matcher(styles);
    double pitch = 0;
    while (layouts.find()) {
      String layout = layouts.group(1);
      if (!layout.contains("style:layout-grid-mode=\"line\"")
          && !layout.contains("style:layout-grid-mode=\"both\"")) return 0;
      Matcher base =
          Pattern.compile("style:layout-grid-base-height=\"([0-9.]+)in\"").matcher(layout);
      if (!base.find()) return 0;
      double next = Double.parseDouble(base.group(1)) * 72;
      if (pitch > 0 && Math.abs(pitch - next) > .01) return 0;
      pitch = next;
    }
    return pitch;
  }
}
