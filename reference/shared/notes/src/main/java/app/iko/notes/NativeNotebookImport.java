package app.iko.notes;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Local, experimental native import. Recovery reports and original archives are kept together. */
final class NativeNotebookImport {
  static final class Result {
    final ArrayList<NoteDocument> documents = new ArrayList<>();
    final LinkedHashSet<String> warnings = new LinkedHashSet<>();
    String sourcePath;
    private int pointCount;

    void reservePoints(int count) throws IOException {
      if (count < 0 || count > NoteDocument.MAX_POINTS - pointCount)
        throw new IOException("原笔迹超过一百万采样点，请拆分笔记导入");
      pointCount += count;
    }

    String description() {
      int pages = 0, strokes = 0, images = 0;
      for (NoteDocument n : documents)
        for (NoteDocument.Page p : n.pages) {
          pages++;
          strokes += p.strokes.size();
          images += p.elements.size();
        }
      return "已恢复 "
          + documents.size()
          + " 本 / "
          + pages
          + " 页，"
          + strokes
          + " 条可编辑矢量笔迹，"
          + images
          + " 个图片对象。"
          + (warnings.isEmpty() ? "" : "\n\n导入范围：\n" + String.join("\n", warnings))
          + "\n\n源文件已在应用内保留。";
    }
  }

  static boolean supports(String name) {
    return name.toLowerCase(Locale.ROOT).matches(".*\\.(nebo|goodnotes|flex|flx)$");
  }

  static Result read(Context context, Uri uri, String name) throws IOException {
    File source = NotebookArchive.retain(context, uri, name);
    Result r = new Result();
    r.sourcePath = source.getPath();
    try (NotebookArchive a =
        new NotebookArchive(source, new File(context.getFilesDir(), "media"))) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (lower.endsWith(".nebo")) r.documents.add(nebo(a, name, r));
      else if (lower.endsWith(".goodnotes")) r.documents.add(goodnotes(a, name, r));
      else if (lower.endsWith(".flx")) r.documents.add(flex(a, r));
      else {
        for (String entry : a.names)
          if (entry.toLowerCase(Locale.ROOT).endsWith(".flx")) {
            if (r.documents.size() >= 100) throw new IOException("备份中的笔记超过 100 本，请拆分导入");
            File inner = a.asset(entry, ".flx");
            try (NotebookArchive b = new NotebookArchive(inner, a.mediaDirectory)) {
              r.documents.add(flex(b, r));
            }
          }
      }
      if (r.documents.isEmpty()) throw new IOException("归档未包含可识别的笔记");
      for (NoteDocument n : r.documents) n.validate();
      Files.write(
          new File(source.getParentFile(), "import-report.txt").toPath(),
          r.description().getBytes(StandardCharsets.UTF_8));
      return r;
    } catch (Exception e) {
      throw new IOException("原生文件导入未完成：" + e.getMessage() + "。原件已保留；未保存残缺笔记", e);
    }
  }

  private static NoteDocument empty(String name) {
    NoteDocument n = NoteDocument.create(name.replaceFirst("\\.[^.]+$", ""));
    n.pages.clear();
    return n;
  }

  private static NoteDocument.Page page(float w, float h) throws IOException {
    if (!Float.isFinite(w) || !Float.isFinite(h) || w < 100 || h < 100 || w > 20000 || h > 20000)
      throw new IOException("不支持的原页面尺寸");
    NoteDocument.Page p = NoteDocument.newPage();
    p.paper = "blank";
    p.width = w;
    p.height = h;
    p.color = 0xffffffff;
    return p;
  }

  private static void addPage(NoteDocument n, NoteDocument.Page p) throws IOException {
    if (n.pages.size() >= NoteDocument.MAX_PAGES) throw new IOException("页数超过限制");
    n.pages.add(p);
  }

  private static NoteDocument.Stroke stroke(int color, float width, String tool) {
    NoteDocument.Stroke s = new NoteDocument.Stroke();
    s.color = color;
    s.width = Math.max(.1f, Math.min(200, width));
    s.tool = tool;
    s.pressureMode = "off";
    s.pathMode = "linear";
    return s;
  }

  private static final float MM = 1400f / 210;

  private static NoteDocument nebo(NotebookArchive a, String name, Result r) throws Exception {
    JSONObject rel = a.json("rel.json").getJSONObject("pages");
    NoteDocument n = empty(name);
    Iterator<String> ids = rel.keys();
    while (ids.hasNext()) {
      String id = ids.next(), prefix = "pages/" + id + "/";
      JSONArray extent = a.json(prefix + "meta.json").optJSONArray("pageExtent");
      if (extent == null || extent.length() != 4) throw new IOException("Nebo 页面缺少范围");
      float x0 = (float) extent.getDouble(0), y0 = (float) extent.getDouble(1);
      NoteDocument.Page p =
          page((float) extent.getDouble(2) * MM, (float) extent.getDouble(3) * MM);
      if (a.has(prefix + "ink.bink")) bink(a.read(prefix + "ink.bink"), p, x0, y0, r);
      addPage(n, p);
    }
    r.warnings.add("Nebo：恢复 BINK v5 笔迹、颜色、笔宽和压感；文字识别、公式、图表及嵌入对象尚未转换，请核对原件。");
    n.tags.add("Nebo 实验导入");
    return n;
  }

  private static void bink(
      byte[] bytes, NoteDocument.Page page, float originX, float originY, Result report)
      throws Exception {
    NotebookWire.Cursor c = new NotebookWire.Cursor(bytes);
    if (!Arrays.equals(c.bytes(5), new byte[] {66, 73, 78, 75, 0}) || c.b.getInt() != 5)
      throw new IOException("只支持已验证的 BINK v5");
    c.u8();
    c.b.getInt();
    int channels = c.count(32);
    for (int i = 0; i < channels; i++) {
      c.string(64);
      c.bytes(4);
      if (c.b.getInt() != 0) c.string(64);
    }
    c.bytes(c.count(65536));
    c.b.getInt();
    c.b.getInt();
    c.b.getInt();
    c.u8();
    int count = c.count(50000);
    LinkedHashMap<Integer, NoteDocument.Stroke> records = new LinkedHashMap<>();
    for (int rec = 0; rec < count; rec++) {
      int flags = c.b.getInt();
      if (flags == -1) continue;
      if (flags != 0x80000000) throw new IOException("未知 BINK 笔迹记录");
      long time = c.b.getLong() / 1000;
      float x = c.b.getFloat() - originX, y = c.b.getFloat() - originY;
      c.b.getInt();
      c.u16();
      int points = c.count(100000);
      report.reservePoints(points);
      short[] dx = new short[points], dy = new short[points];
      for (int i = 0; i < points; i++) dx[i] = c.b.getShort();
      for (int i = 0; i < points; i++) dy[i] = c.b.getShort();
      NoteDocument.Stroke s = stroke(0xff202837, .25f * MM, "pen");
      for (int i = 0; i < points; i++) {
        x += dx[i] / 500f;
        y += dy[i] / 500f;
        s.points.add(new NoteDocument.Point(x * MM, y * MM, c.u8() / 255f, 0, time));
      }
      records.put(rec, s);
    }
    c.b.getInt();
    int tags = c.count(100000);
    c.u8();
    HashMap<Integer, Float> sensitivities = new HashMap<>();
    for (int i = 0; i < tags; i++) {
      if (i > 0) {
        c.b.getInt();
        c.b.getInt();
        c.b.getInt();
      }
      String tag = c.string(256);
      int groups = c.count(100000);
      ArrayList<int[]> spans = new ArrayList<>();
      for (int j = 0; j < groups; j++) {
        c.u16();
        int fromPoint = c.u16(), first = c.b.getInt();
        c.bytes(2);
        int toPoint = c.u16(), last = c.b.getInt();
        spans.add(new int[] {Math.min(first, last), Math.max(first, last)});
      }
      String text = c.string(65536);
      for (Map.Entry<Integer, NoteDocument.Stroke> item : records.entrySet()) {
        boolean covered = false;
        for (int[] span : spans)
          if (item.getKey() >= span[0] && item.getKey() <= span[1]) {
            covered = true;
            break;
          }
        if (!covered) continue;
        NoteDocument.Stroke s = item.getValue();
        if (tag.equals("HIGHLIGHT_STROKES")) s.tool = "highlighter";
        Matcher brush = Pattern.compile("^(?:pen|brush)-(\\d{3,4})$").matcher(tag);
        if (brush.matches()) s.width = Float.parseFloat(brush.group(1)) / 100 * MM;
        if (tag.equals(".STYLE")) {
          Matcher color =
              Pattern.compile("color:\\s*#([0-9a-fA-F]{6})([0-9a-fA-F]{2})?").matcher(text);
          if (color.find())
            s.color =
                (int) Long.parseLong(color.group(1), 16)
                    | ((color.group(2) == null ? 255 : Integer.parseInt(color.group(2), 16)) << 24);
          Matcher sensitivity =
              Pattern.compile("-myscript-pen-pressure-sensitivity:\\s*([\\d.]+)").matcher(text);
          if (sensitivity.find())
            sensitivities.put(item.getKey(), Float.parseFloat(sensitivity.group(1)));
        }
      }
    }
    for (Map.Entry<Integer, NoteDocument.Stroke> item : records.entrySet()) {
      NoteDocument.Stroke s = item.getValue();
      float min = 1, max = 0;
      for (NoteDocument.Point p : s.points) {
        min = Math.min(min, p.pressure);
        max = Math.max(max, p.pressure);
      }
      float base = s.width,
          sensitivity = sensitivities.getOrDefault(item.getKey(), .8f),
          maxWidth = base;
      if (!s.tool.equals("highlighter") && max - min > 1.5f / 255) {
        for (NoteDocument.Point p : s.points)
          maxWidth = Math.max(maxWidth, base * (1 + sensitivity * 2.43f * (p.pressure - .29f)));
        s.width = Math.max(.1f, maxWidth);
        for (NoteDocument.Point p : s.points)
          p.widthScale =
              Math.max(.001f, base * (1 + sensitivity * 2.43f * (p.pressure - .29f)) / s.width);
      }
      if (!s.points.isEmpty()) page.strokes.add(s);
    }
  }

  private static NoteDocument flex(NotebookArchive a, Result report) throws Exception {
    JSONObject info = a.json("info");
    NoteDocument n = empty(info.optString("name", "Flexcil 导入"));
    JSONArray pages = new JSONArray(new String(a.read("pages.index"), StandardCharsets.UTF_8));
    HashMap<String, String> pdfs = new HashMap<>();
    for (int index = 0; index < pages.length(); index++) {
      JSONObject item = pages.getJSONObject(index), frame = item.getJSONObject("frame");
      float w = (float) frame.getDouble("width"),
          h = (float) frame.getDouble("height"),
          scale = 1400 / w;
      NoteDocument.Page p = page(1400, h * scale);
      String key = item.getString("key");
      JSONObject attachment = item.optJSONObject("attachmentPage");
      if (attachment != null) {
        String path = "attachment/PDF/" + attachment.getString("file");
        if (!pdfs.containsKey(path)) pdfs.put(path, a.asset(path, ".pdf").getPath());
        p.backgroundPath = pdfs.get(path);
        p.pdfPage = attachment.getInt("index");
      }
      String prefix = "objects/" + key;
      JSONArray images = array(a, prefix + ".images");
      for (int j = 0; j < images.length(); j++) {
        JSONObject image = images.getJSONObject(j), f = image.getJSONObject("frame");
        String imageMember = "attachment/image/" + image.getString("key");
        if (!a.has(imageMember)) {
          report.warnings.add(
              "Flexcil：原归档第 "
                  + (index + 1)
                  + " 页缺少图片附件 "
                  + image.getString("key")
                  + "；该对象未恢复，原件已保留。");
          continue;
        }
        NoteDocument.Element e = new NoteDocument.Element();
        e.kind = "image";
        e.path = a.asset(imageMember, ".img").getPath();
        e.x = (float) f.getDouble("x") * p.width;
        e.y = (float) f.getDouble("y") * p.height;
        e.width = (float) f.getDouble("width") * p.width;
        e.height = (float) f.getDouble("height") * p.height;
        e.rotation =
            ObjectTransform.normalizeRotation((float) Math.toDegrees(image.optDouble("rotate", 0)));
        JSONObject crop = image.optJSONObject("cropBox");
        if (crop != null
            && (Math.abs(crop.optDouble("width", 1) - 1) > .001
                || Math.abs(crop.optDouble("height", 1) - 1) > .001))
          throw new IOException("此 Flexcil 图片包含尚未支持的裁剪，未导入残缺图像");
        p.elements.add(e);
      }
      JSONArray drawings = array(a, prefix + ".drawings");
      for (int j = 0; j < drawings.length(); j++) flexInk(drawings.getJSONObject(j), p, report);
      JSONArray shapes = array(a, prefix + ".shapes");
      if (shapes.length() > 0)
        report.warnings.add("Flexcil：部分几何图形尚未转换（" + shapes.length() + " 个），请与原本核对。");
      if (array(a, prefix + ".maskings").length() > 0) report.warnings.add("Flexcil：原擦除蒙版尚未转换。");
      if (p.elements.size() > 0 && p.strokes.size() > 0)
        report.warnings.add("Flexcil：图片与手写笔迹分别保留，跨对象的原始堆叠顺序可能不同。");
      if (item.optDouble("rotate", 0) != 0) report.warnings.add("Flexcil：页面旋转设置未恢复。");
      addPage(n, p);
    }
    n.tags.add("Flexcil 实验导入");
    return n;
  }

  private static JSONArray array(NotebookArchive a, String name) throws Exception {
    return a.has(name)
        ? new JSONArray(new String(a.read(name), StandardCharsets.UTF_8))
        : new JSONArray();
  }

  private static void flexInk(JSONObject item, NoteDocument.Page page, Result report)
      throws Exception {
    if (item.optInt("type", 1) != 1) {
      report.warnings.add("Flexcil：存在未支持的绘制对象。");
      return;
    }
    byte[] data = android.util.Base64.decode(item.getString("points"), android.util.Base64.DEFAULT);
    NotebookWire.Cursor c = new NotebookWire.Cursor(data);
    int count = c.count(100000);
    if (data.length != 4 + count * 12) throw new IOException("未验证的 Flexcil 点流版本");
    report.reservePoints(count);
    JSONObject start = item.getJSONObject("start");
    float sx = (float) start.getDouble("x"), sy = (float) start.getDouble("y");
    NoteDocument.Stroke s = stroke((int) item.optLong("strokeColor", 0xff000000L), 2, "pen");
    float cumulativeX = sx, cumulativeY = sy;
    ArrayList<float[]> offsets = new ArrayList<>();
    boolean absolute = false;
    for (int i = 0; i < count; i++) {
      float dx = c.b.getFloat(), dy = c.b.getFloat(), force = c.b.getFloat();
      offsets.add(new float[] {dx, dy, force});
      cumulativeX += dx;
      cumulativeY += dy;
      if (cumulativeX < -.2 || cumulativeX > 1.2 || cumulativeY < -.2 || cumulativeY > 1.2)
        absolute = true;
    }
    cumulativeX = sx;
    cumulativeY = sy;
    for (float[] o : offsets) {
      cumulativeX = absolute ? sx + o[0] : cumulativeX + o[0];
      cumulativeY = absolute ? sy + o[1] : cumulativeY + o[1];
      s.points.add(
          new NoteDocument.Point(cumulativeX * page.width, cumulativeY * page.height, 1, 0, 0));
    }
    JSONObject scale = item.optJSONObject("scale");
    float factor =
        scale == null ? 1 : (float) (scale.optDouble("x", 1) + scale.optDouble("y", 1)) / 2;
    s.width = Math.max(.1f, Math.min(200, 2 * factor * page.width / 768));
    float degrees = (float) Math.toDegrees(item.optDouble("rotate", 0));
    if (degrees != 0) {
      NoteDocument.Stroke copy = s.copy();
      ObjectTransform.applyStroke(s, copy, sx * page.width, sy * page.height, 1, degrees, 0, 0);
      s = copy;
    }
    if (!s.points.isEmpty()) page.strokes.add(s);
    report.warnings.add("Flexcil：手写点按已验证结构恢复为矢量；原笔触、虚线及压力渲染仍为实验适配。");
  }

  private static NoteDocument goodnotes(NotebookArchive a, String name, Result report)
      throws Exception {
    NoteDocument n = empty(name);
    ArrayList<String> members = new ArrayList<>();
    for (String path : a.names)
      if (path.startsWith("notes/") && path.length() > 6) members.add(path.substring(6));
    Collections.sort(members);
    if (members.isEmpty()) throw new IOException("缺少 Goodnotes 页面记录");
    HashMap<String, float[]> papers = new HashMap<>();
    LinkedHashMap<String, GnPage> indexed = new LinkedHashMap<>();
    if (a.has("index.notes.pb"))
      for (byte[] rec : NotebookWire.records(a.read("index.notes.pb"))) {
        Map<Integer, Object> f = NotebookWire.fields(rec);
        String member = NotebookWire.text(f, 2);
        if (!member.startsWith("notes/") || !a.has(member))
          throw new IOException("Goodnotes 页面索引缺少原始页面");
        GnPage model = new GnPage(member.substring(6));
        if (indexed.put(gnPageKey(NotebookWire.text(f, 1)), model) != null)
          throw new IOException("Goodnotes 页面版本标识冲突");
      }
    if (indexed.isEmpty()) {
      for (String member : members) indexed.put(gnPageKey(member), new GnPage(member));
      report.warnings.add("Goodnotes：缺少页面索引，按容器页面标识排序。");
    }
    if (a.has("index.events.pb"))
      for (byte[] rec : NotebookWire.records(a.read("index.events.pb"))) {
        Map<Integer, Object> f = NotebookWire.fields(rec);
        if (f.containsKey(2)) {
          Map<Integer, Object> b = NotebookWire.fields(NotebookWire.data(f, 2));
          if (b.containsKey(8)) {
            Map<Integer, Object> dims = NotebookWire.fields(NotebookWire.data(b, 8));
            papers.put(
                NotebookWire.text(b, 2),
                new float[] {
                  NotebookWire.number(dims, 1, 595.28f), NotebookWire.number(dims, 2, 841.89f)
                });
          }
        }
        for (int type : new int[] {54, 55, 56}) {
          if (!f.containsKey(type)) continue;
          Map<Integer, Object> b = NotebookWire.fields(NotebookWire.data(f, type));
          GnPage model = indexed.get(gnPageKey(NotebookWire.text(b, 2)));
          if (model == null) continue;
          if (type == 56) {
            model.deleted = true;
            continue;
          }
          if (type == 54)
            model.paper = NotebookWire.text(NotebookWire.fields(NotebookWire.data(b, 3)), 1);
          long time = b.get(14) instanceof Number ? ((Number) b.get(14)).longValue() : 0;
          if (time >= model.orderTime) {
            model.order =
                NotebookWire.text(NotebookWire.fields(NotebookWire.data(b, type == 54 ? 4 : 3)), 1);
            model.orderTime = time;
          }
        }
      }
    ArrayList<GnPage> order = new ArrayList<>();
    for (GnPage model : indexed.values()) if (!model.deleted) order.add(model);
    if (order.isEmpty()) throw new IOException("Goodnotes 未包含有效页面");
    order.sort(Comparator.comparing((GnPage x) -> x.order.isEmpty()).thenComparing(x -> x.order));
    for (GnPage model : order)
      if (model.order.isEmpty()) report.warnings.add("Goodnotes：部分页面未记录页序，保留索引顺序。");
    int unsupported = 0;
    for (GnPage model : order) {
      float[] dims = papers.get(model.paper);
      if (dims == null) {
        dims = new float[] {595.28f, 841.89f};
        report.warnings.add("Goodnotes：部分纸张尺寸未解码，采用 A4 并扩展至笔迹范围。");
      }
      float scale = 1400 / dims[0];
      NoteDocument.Page page = page(1400, dims[1] * scale);
      List<byte[]> records = NotebookWire.records(a.read("notes/" + model.member));
      HashMap<String, Boolean> erased = new HashMap<>();
      for (byte[] rec : records) {
        Map<Integer, Object> f = NotebookWire.fields(rec);
        String id = NotebookWire.text(f, 1);
        if (id.length() == 36) erased.put(id, NotebookWire.number(f, 3, 0) == 1);
      }
      for (byte[] rec : records) {
        Map<Integer, Object> top = NotebookWire.fields(rec);
        if (!top.containsKey(7)) {
          if (!top.containsKey(1) && !top.containsKey(9)) unsupported++;
          continue;
        }
        Map<Integer, Object> f = NotebookWire.fields(NotebookWire.data(top, 7));
        if (Boolean.TRUE.equals(erased.get(NotebookWire.text(f, 1)))) continue;
        if (!f.containsKey(2)) {
          unsupported++;
          continue;
        }
        byte[] blob = NotebookWire.appleLz4(NotebookWire.data(f, 2));
        ArrayList<float[]> points = gnPath(NotebookWire.tpl(blob));
        if (points.isEmpty()) {
          unsupported++;
          continue;
        }
        report.reservePoints(points.size());
        Map<Integer, Object> color = NotebookWire.fields(NotebookWire.data(f, 4));
        int argb =
            Color.argb(
                Math.round(255 * NotebookWire.number(color, 4, 1)),
                Math.round(255 * NotebookWire.number(color, 1, 0)),
                Math.round(255 * NotebookWire.number(color, 2, 0)),
                Math.round(255 * NotebookWire.number(color, 3, 0)));
        boolean highlight = NotebookWire.number(f, 5, 0) == 1;
        String tool =
            highlight ? "highlighter" : NotebookWire.number(f, 3, 0) == 5 ? "pencil" : "pen";
        Map<Integer, Object> move = NotebookWire.fields(NotebookWire.data(f, 6));
        float dx = NotebookWire.number(move, 1, 0),
            dy = NotebookWire.number(move, 2, 0),
            width = .1f;
        for (float[] p : points) width = Math.max(width, p[2] * scale);
        NoteDocument.Stroke s = stroke(argb, width, tool);
        for (float[] p : points) {
          if (Math.abs(p[0]) < .01 && Math.abs(p[1]) < .01) {
            if (!s.points.isEmpty()) page.strokes.add(s);
            s = stroke(argb, width, tool);
            continue;
          }
          NoteDocument.Point point =
              new NoteDocument.Point((p[0] + dx) * scale, (p[1] + dy) * scale, 1, 0, 0);
          point.widthScale = Math.max(.001f, Math.min(2, p[2] * scale / s.width));
          s.points.add(point);
          page.width = Math.max(page.width, point.x + 12 * scale);
          page.height = Math.max(page.height, point.y + 12 * scale);
        }
        if (!s.points.isEmpty()) page.strokes.add(s);
      }
      addPage(n, page);
    }
    report.warnings.add(
        "Goodnotes：实验恢复基础笔迹及原笔宽；文字框、贴纸、背景附件、几何图形、擦除剪口和录音尚未转换。请核对原本后使用（未恢复记录 " + unsupported + "）。");
    n.tags.add("Goodnotes 实验导入");
    return n;
  }

  private static final class GnPage {
    final String member;
    String paper = "", order = "";
    long orderTime = Long.MIN_VALUE;
    boolean deleted;

    GnPage(String member) {
      this.member = member;
    }
  }

  private static String gnPageKey(String uuid) {
    // Verified Goodnotes revision IDs differ in the final UUID component.
    return (uuid.length() == 36 ? uuid.substring(0, 32) : uuid).toUpperCase(Locale.ROOT);
  }

  private static ArrayList<float[]> gnPath(List<NotebookWire.Section> sections) throws IOException {
    ArrayList<float[]> out = new ArrayList<>();
    boolean tilt = false;
    float width = 1;
    boolean flagsRead = false;
    for (NotebookWire.Section s : sections) {
      if (!flagsRead && s.array && !s.structs && s.type.equals("v")) {
        for (float f : s.values) if (((int) f & 4) != 0) tilt = true;
        flagsRead = true;
      }
      if (!s.array && s.type.equals("u")) width = s.values[0];
    }
    for (NotebookWire.Section s : sections)
      if (s.array && !s.structs && s.type.equals("u") && s.values.length >= 9) {
        float[] v = s.values;
        boolean pairs = v.length % 9 == 0;
        if (pairs && !tilt)
          for (int i = 0; i < v.length; i += 9)
            if (Math.abs(v[i + 6]) > .01 || Math.abs(v[i + 7]) > .01) pairs = false;
        if (pairs) {
          for (int i = 0; i < v.length; i += 9) {
            out.add(new float[] {v[i], v[i + 1], v[i + 2]});
            out.add(new float[] {v[i + 3], v[i + 4], v[i + 5]});
          }
          return out;
        }
        if (!tilt && v.length % 3 == 0) {
          for (int i = 0; i < v.length; i += 3) out.add(new float[] {v[i], v[i + 1], v[i + 2]});
          return out;
        }
      }
    NotebookWire.Section best = null;
    for (NotebookWire.Section s : sections)
      if (s.structs && s.values.length > 0 && (s.width == 2 || s.width == 4 || s.width == 11))
        if (best == null || s.values.length > best.values.length) best = s;
    if (best != null) {
      float[] v = best.values;
      int stride = best.width, x = stride == 11 ? 1 : 0, y = x + 1;
      for (int i = 0; i < v.length; i += stride) out.add(new float[] {v[i + x], v[i + y], width});
      if (stride == 4) out.add(new float[] {v[v.length - 2], v[v.length - 1], width});
      if (stride == 11) out.add(new float[] {v[v.length - 5], v[v.length - 4], width});
    }
    for (float[] p : out)
      if (!Float.isFinite(p[0]) || !Float.isFinite(p[1]) || p[2] <= 0 || p[2] > 200)
        throw new IOException("未知 Goodnotes 点布局");
    return out;
  }

  private NativeNotebookImport() {}
}
