package app.iko.notes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned, renderer-independent notebook data. Coordinates are paper units. */
public class NoteDocument {
  public static final int VERSION = 3;
  public static final int MAX_PAGES = 500;
  public static final int MAX_POINTS = 1_000_000;
  public String id = UUID.randomUUID().toString();
  public String title = "未命名笔记";
  public String folder = "";
  public ArrayList<String> tags = new ArrayList<>();
  public String cover = "#86A3FF";
  public String coverPattern = "plain";
  public String coverImagePath = "";
  public long modifiedAt = System.currentTimeMillis();
  public int currentPage = 0;
  public boolean trashed = false;
  public long trashedAt = 0;
  public ArrayList<Page> pages = new ArrayList<>();
  public ArrayList<Recording> recordings = new ArrayList<>();
  public int recordingNumber = 0;
  private static final java.util.regex.Pattern RECORDING_NAME =
      java.util.regex.Pattern.compile("录音([1-9][0-9]{0,8})");

  public static NoteDocument create(String title) {
    NoteDocument note = new NoteDocument();
    note.title = title == null || title.trim().isEmpty() ? "未命名笔记" : title.trim();
    note.pages.add(newPage());
    return note;
  }

  public static Page newPage() {
    return new Page();
  }

  /** Resolve stable page identities after pages are inserted, copied or removed. */
  public int findPageIndex(String pageId) {
    if (pageId == null || pageId.isEmpty() || pages == null) return -1;
    for (int index = 0; index < pages.size(); index++) {
      Page page = pages.get(index);
      if (page != null && pageId.equals(page.id)) return index;
    }
    return -1;
  }

  public NoteDocument copy() {
    NoteDocument n = new NoteDocument();
    n.id = id;
    n.title = title;
    n.folder = folder;
    n.tags.addAll(tags);
    n.cover = cover;
    n.coverPattern = coverPattern;
    n.coverImagePath = coverImagePath;
    n.modifiedAt = modifiedAt;
    n.currentPage = currentPage;
    n.trashed = trashed;
    n.trashedAt = trashedAt;
    for (Page page : pages) n.pages.add(page.copy());
    for (Recording recording : recordings) n.recordings.add(recording.copy());
    n.recordingNumber = recordingNumber;
    return n;
  }

  public JSONObject toJson() throws JSONException {
    validate();
    JSONObject j = new JSONObject();
    j.put("schema", "iko.notes")
        .put("version", VERSION)
        .put("id", id)
        .put("title", title)
        .put("folder", folder)
        .put("tags", new JSONArray(tags))
        .put("cover", cover)
        .put("coverPattern", coverPattern)
        .put("coverImagePath", coverImagePath)
        .put("modifiedAt", modifiedAt)
        .put("currentPage", currentPage)
        .put("trashed", trashed)
        .put("trashedAt", trashedAt);
    JSONArray a = new JSONArray();
    for (Page page : pages) a.put(page.toJson());
    JSONArray audio = new JSONArray();
    for (Recording recording : recordings) audio.put(recording.toJson());
    return j.put("pages", a).put("recordings", audio).put("recordingNumber", recordingNumber);
  }

  public static NoteDocument fromJson(JSONObject j) throws JSONException {
    int version = j.optInt("version", -1);
    if (!"iko.notes".equals(j.optString("schema")) || version < 1 || version > VERSION)
      throw new JSONException("不支持的笔记格式或版本");
    NoteDocument n = new NoteDocument();
    n.id = string(j, "id", n.id, 100);
    if (!n.id.matches("[A-Za-z0-9_-]{1,100}")) throw new JSONException("笔记 ID 无效");
    n.title = string(j, "title", "未命名笔记", 500);
    n.folder = string(j, "folder", "", 200);
    n.cover = string(j, "cover", "#86A3FF", 200);
    n.coverPattern = string(j, "coverPattern", "plain", 40);
    n.coverImagePath = string(j, "coverImagePath", "", 4096);
    n.modifiedAt = Math.max(0, j.optLong("modifiedAt", 0));
    n.trashed = j.optBoolean("trashed", false);
    n.trashedAt = Math.max(0,j.optLong("trashedAt",0));
    JSONArray tags = j.optJSONArray("tags");
    if (tags != null) {
      if (tags.length() > 100) throw new JSONException("标签数量过多");
      for (int i = 0; i < tags.length(); i++) {
        String tag = tags.getString(i);
        if (tag.length() > 200) throw new JSONException("标签过长");
        n.tags.add(tag);
      }
    }
    JSONArray pages = j.getJSONArray("pages");
    if (pages.length() < 1 || pages.length() > MAX_PAGES) throw new JSONException("页数超出范围");
    int totalPoints = 0;
    HashSet<String> identities = new HashSet<>();
    for (int i = 0; i < pages.length(); i++) {
      Page page = Page.fromJson(pages.getJSONObject(i));
      if (!identities.add(page.id)) throw new JSONException("页面 ID 重复");
      for (Stroke s : page.strokes) {
        totalPoints += s.points.size();
        if (totalPoints > MAX_POINTS) throw new JSONException("笔迹采样点过多");
      }
      n.pages.add(page);
    }
    n.currentPage = Math.max(0, Math.min(n.pages.size() - 1, j.optInt("currentPage", 0)));
    JSONArray audio = j.optJSONArray("recordings");
    n.recordingNumber = j.optInt("recordingNumber", 0);
    if (audio != null) {
      if (audio.length() > 200) throw new JSONException("录音数量超过 200 段");
      for (int i = 0; i < audio.length(); i++)
        n.recordings.add(Recording.fromJson(audio.getJSONObject(i)));
    }
    n.syncRecordingNumber();
    for (Recording recording : n.recordings) {
      if (recording.title.equals("课堂录音")
          || recording.title.matches("课堂录音 · [0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}"))
        recording.title = "录音" + (++n.recordingNumber);
    }
    n.validate();
    return n;
  }

  /** Enforce the same bounds before committing an in-memory note and after parsing it. */
  public void validate() throws JSONException {
    if (recordingNumber < 0) throw new JSONException("录音序号无效");
    boundedString(id, "笔记 ID", 100);
    if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw new JSONException("笔记 ID 无效");
    boundedString(title, "标题", 500);
    boundedString(folder, "目录", 200);
    boundedString(cover, "封面", 200);
    boundedString(coverPattern, "封面样式", 40);
    if (!CoverCatalog.contains(coverPattern)) throw new JSONException("封面样式无效");
    boundedString(coverImagePath, "封面图片路径", 4096);
    if (tags == null || tags.size() > 100) throw new JSONException("最多支持 100 个标签（含收藏标记）");
    for (String tag : tags) boundedString(tag, "标签", 200);
    if (pages == null || pages.isEmpty() || pages.size() > MAX_PAGES)
      throw new JSONException("页数超出范围");
    if (currentPage < 0 || currentPage >= pages.size()) throw new JSONException("当前页面无效");
    HashSet<String> identities = new HashSet<>();
    long points = 0;
    for (Page page : pages) {
      if (page == null) throw new JSONException("页面为空");
      boundedString(page.id, "页面 ID", 100);
      if (!page.id.matches("[A-Za-z0-9_-]{1,100}") || !identities.add(page.id))
        throw new JSONException("页面 ID 无效或重复");
      boundedString(page.title, "页面标题", 500);
      boundedString(page.paper, "纸页模板", 40);
      boundedString(page.texture, "材质", 40);
      boundedString(page.backgroundPath, "背景路径", 4096);
      boundedNumber(page.width, "纸宽", 100, 20000);
      boundedNumber(page.height, "纸高", 100, 20000);
      boundedNumber(page.lineSpacing, "行距", 0, 240);
      if (page.pdfPage < -1 || page.pdfPage > 10000) throw new JSONException("PDF 页码无效");
      if (page.strokes == null || page.strokes.size() > 50000) throw new JSONException("单页笔迹过多");
      for (Stroke stroke : page.strokes) {
        if (stroke == null) throw new JSONException("笔迹为空");
        boundedString(stroke.id, "笔迹 ID", 100);
        boundedString(stroke.tool, "笔型", 40);
        if (!("smooth".equals(stroke.pathMode) || "linear".equals(stroke.pathMode)))
          throw new JSONException("笔迹路径模式无效");
        boundedNumber(stroke.width, "笔宽", .1f, 200);
        if (stroke.brushVersion < 1 || stroke.brushVersion > 2) throw new JSONException("笔触版本无效");
        if (!BrushStyle.validPressureMode(stroke.pressureMode)) throw new JSONException("压感档位无效");
        boundedNumber(stroke.pressureMin, "压感下限", 0, 2);
        boundedNumber(stroke.pressureMax, "压感上限", 0, 2);
        if (stroke.pressureMin >= stroke.pressureMax) throw new JSONException("压感范围无效");
        if (stroke.points == null || stroke.points.size() > 100000)
          throw new JSONException("单笔采样点过多");
        points += stroke.points.size();
        if (points > MAX_POINTS) throw new JSONException("笔记采样点过多，请拆分笔记");
        for (Point point : stroke.points) {
          if (point == null) throw new JSONException("采样点为空");
          boundedNumber(point.x, "x", -1000000, 1000000);
          boundedNumber(point.y, "y", -1000000, 1000000);
          boundedNumber(point.pressure, "压感", 0, 2);
          boundedNumber(point.tilt, "倾斜", 0, 3.2f);
          boundedNumber(point.widthScale, "残片笔宽比例", 0, 2);
          boundedNumber(point.alphaScale, "残片透明度比例", 0, 1);
          if (point.styleRun < 0 || point.styleRun > 100000) throw new JSONException("残片样式段无效");
          if (point.time < 0) throw new JSONException("采样时间无效");
        }
      }
      if (page.elements == null || page.elements.size() > 5000) throw new JSONException("单页文字图片过多");
      if (page.links == null || page.links.size() > 5000) throw new JSONException("单页目录链接过多");
      for (PageLink link : page.links) {
        if (link == null
            || link.targetPageId == null
            || !link.targetPageId.matches("[A-Za-z0-9_-]{1,100}"))
          throw new JSONException("目录链接目标无效");
        boundedNumber(link.x, "链接 x", 0, page.width);
        boundedNumber(link.y, "链接 y", 0, page.height);
        boundedNumber(link.width, "链接宽度", 0.01f, page.width);
        boundedNumber(link.height, "链接高度", 0.01f, page.height);
      }
      for (Element element : page.elements) {
        if (element == null
            || !("text".equals(element.kind)
                || "image".equals(element.kind)
                || "tape".equals(element.kind))) throw new JSONException("不支持的页面元素");
        boundedString(element.text, "文字", 100000);
        boundedString(element.path, "图片路径", 4096);
        boundedString(element.targetPageId, "引用目标 ID", 100);
        // A missing target is a broken reference, not an invalid notebook. Page deletion is
        // allowed.
        if (!element.targetPageId.isEmpty()
            && (!"text".equals(element.kind)
                || !element.targetPageId.matches("[A-Za-z0-9_-]{1,100}")))
          throw new JSONException("页面引用只支持文字，且目标 ID 必须有效");
        boundedNumber(element.x, "元素 x", -1000000, 1000000);
        boundedNumber(element.y, "元素 y", -1000000, 1000000);
        boundedNumber(element.width, "元素宽度", 1, 40000);
        boundedNumber(element.height, "元素高度", 1, 40000);
        boundedNumber(element.textSize, "字号", 4, 500);
        boundedNumber(element.rotation, "旋转角度", -360, 360);
        if (!("left".equals(element.textAlign)
            || "center".equals(element.textAlign)
            || "right".equals(element.textAlign))) throw new JSONException("文字对齐方式无效");
        if (!("plain".equals(element.pattern)
            || "stripes".equals(element.pattern)
            || "dots".equals(element.pattern))) throw new JSONException("胶带纹理无效");
      }
    }
    if (recordings == null || recordings.size() > 200) throw new JSONException("录音数量超过 200 段");
    HashSet<String> audioIds = new HashSet<>();
    for (Recording recording : recordings) {
      if (recording == null
          || !recording.id.matches("[A-Za-z0-9_-]{1,100}")
          || !audioIds.add(recording.id)) throw new JSONException("录音 ID 无效或重复");
      boundedString(recording.title, "录音标题", 500);
      boundedString(recording.path, "录音路径", 4096);
      boundedString(recording.pageId, "录音页面", 100);
      if (recording.durationMs < 0 || recording.durationMs > 86400000L || recording.createdAt < 0)
        throw new JSONException("录音时长无效");
    }
  }

  private static void boundedString(String value, String name, int max) throws JSONException {
    if (value == null || value.length() > max) throw new JSONException(name + "为空或长度超出范围");
  }

  /** Per-notebook sequence survives deletion, restart and full portable backups. */
  public String nextRecordingTitle() {
    syncRecordingNumber();
    return "录音" + (++recordingNumber);
  }

  private void syncRecordingNumber() {
    for (Recording recording : recordings) {
      java.util.regex.Matcher name = RECORDING_NAME.matcher(recording.title);
      if (name.matches())
        recordingNumber = Math.max(recordingNumber, Integer.parseInt(name.group(1)));
    }
  }

  private static void boundedNumber(float value, String name, float min, float max)
      throws JSONException {
    if (Float.isNaN(value) || Float.isInfinite(value) || value < min || value > max)
      throw new JSONException(name + "数值超出范围");
  }

  public static class Page {
    public String id = UUID.randomUUID().toString();
    public String title = "";
    public String paper = "dot", texture = "smooth";
    public int color = 0xfffaf9f6;
    public float width = 1400, height = 1980;
    public float lineSpacing = 0;
    public boolean infinite = false;
    public String backgroundPath = "";
    public int pdfPage = -1;
    public ArrayList<Stroke> strokes = new ArrayList<>();
    public ArrayList<Element> elements = new ArrayList<>();
    public ArrayList<PageLink> links = new ArrayList<>();

    public Page copy() {
      Page p = new Page();
      p.id = id;
      p.title = title;
      p.paper = paper;
      p.texture = texture;
      p.lineSpacing = lineSpacing;
      p.color = color;
      p.width = width;
      p.height = height;
      p.infinite = infinite;
      p.backgroundPath = backgroundPath;
      p.pdfPage = pdfPage;
      for (Stroke s : strokes) p.strokes.add(s.copy());
      for (Element e : elements) p.elements.add(e.copy());
      for (PageLink link : links) p.links.add(link.copy());
      return p;
    }

    public Page copyAsNew() {
      Page p = copy();
      p.id = UUID.randomUUID().toString();
      return p;
    }

    public JSONObject toJson() throws JSONException {
      JSONObject j =
          new JSONObject()
              .put("id", id)
              .put("title", title)
              .put("paper", paper)
              .put("texture", texture)
              .put("lineSpacing", lineSpacing)
              .put("color", color)
              .put("width", width)
              .put("height", height)
              .put("infinite", infinite)
              .put("backgroundPath", backgroundPath)
              .put("pdfPage", pdfPage);
      JSONArray ss = new JSONArray(), es = new JSONArray();
      for (Stroke s : strokes) ss.put(s.toJson());
      for (Element e : elements) es.put(e.toJson());
      JSONArray ls = new JSONArray();
      for (PageLink link : links) ls.put(link.toJson());
      return j.put("strokes", ss).put("elements", es).put("links", ls);
    }

    public static Page fromJson(JSONObject j) throws JSONException {
      Page p = new Page();
      p.id = string(j, "id", p.id, 100);
      if (!p.id.matches("[A-Za-z0-9_-]{1,100}")) throw new JSONException("页面 ID 无效");
      p.title = string(j, "title", "", 500);
      p.paper = string(j, "paper", "dot", 40);
      p.texture = string(j, "texture", "smooth", 40);
      p.lineSpacing = number(j, "lineSpacing", 0, 0, 240);
      p.color = j.optInt("color", 0xfffaf9f6);
      p.width = number(j, "width", 1400, 100, 20000);
      p.height = number(j, "height", 1980, 100, 20000);
      p.infinite = j.optBoolean("infinite", false);
      p.backgroundPath = string(j, "backgroundPath", "", 4096);
      p.pdfPage = j.optInt("pdfPage", -1);
      if (p.pdfPage < -1 || p.pdfPage > 10000) throw new JSONException("PDF 页码无效");
      JSONArray ss = j.optJSONArray("strokes"), es = j.optJSONArray("elements");
      if (ss != null) {
        if (ss.length() > 50000) throw new JSONException("单页笔迹过多");
        int points = 0;
        for (int i = 0; i < ss.length(); i++) {
          Stroke s = Stroke.fromJson(ss.getJSONObject(i));
          points += s.points.size();
          if (points > MAX_POINTS) throw new JSONException("单页采样点过多");
          p.strokes.add(s);
        }
      }
      if (es != null) {
        if (es.length() > 5000) throw new JSONException("单页元素过多");
        for (int i = 0; i < es.length(); i++) p.elements.add(Element.fromJson(es.getJSONObject(i)));
      }
      JSONArray ls = j.optJSONArray("links");
      if (ls != null) {
        if (ls.length() > 5000) throw new JSONException("单页目录链接过多");
        for (int i = 0; i < ls.length(); i++) p.links.add(PageLink.fromJson(ls.getJSONObject(i)));
      }
      return p;
    }
  }

  /** Immutable artwork hit areas are separate from selectable handwriting/text elements. */
  public static class PageLink {
    public String targetPageId = "";
    public float x, y, width, height;

    public PageLink copy() {
      PageLink link = new PageLink();
      link.targetPageId = targetPageId;
      link.x = x;
      link.y = y;
      link.width = width;
      link.height = height;
      return link;
    }

    public boolean contains(float px, float py) {
      return px >= x && py >= y && px <= x + width && py <= y + height;
    }

    JSONObject toJson() throws JSONException {
      return new JSONObject()
          .put("targetPageId", targetPageId)
          .put("x", x)
          .put("y", y)
          .put("width", width)
          .put("height", height);
    }

    static PageLink fromJson(JSONObject json) throws JSONException {
      PageLink link = new PageLink();
      link.targetPageId = string(json, "targetPageId", "", 100);
      link.x = number(json, "x", 0, 0, 20000);
      link.y = number(json, "y", 0, 0, 20000);
      link.width = number(json, "width", 1, .01f, 20000);
      link.height = number(json, "height", 1, .01f, 20000);
      return link;
    }
  }

  public static class Stroke {
    public String id = UUID.randomUUID().toString(), tool = "pen";
    public String pathMode = "smooth";
    public int color = 0xff202837;
    public float width = 4;
    public String pressureMode = "standard";
    public int brushVersion = 1;
    public float pressureMin = 0, pressureMax = 1;
    public ArrayList<Point> points = new ArrayList<>();

    public Stroke copy() {
      Stroke s = new Stroke();
      s.id = id;
      s.tool = tool;
      s.pathMode = pathMode;
      s.color = color;
      s.width = width;
      s.pressureMode = pressureMode;
      s.brushVersion = brushVersion;
      s.pressureMin = pressureMin;
      s.pressureMax = pressureMax;
      for (Point p : points) s.points.add(p.copy());
      return s;
    }

    public JSONObject toJson() throws JSONException {
      JSONArray ps = new JSONArray();
      for (Point p : points) ps.put(p.toJson());
      return new JSONObject()
          .put("id", id)
          .put("tool", tool)
          .put("pathMode", pathMode)
          .put("color", color)
          .put("width", width)
          .put("pressureMode", pressureMode)
          .put("brushVersion", brushVersion)
          .put("pressureMin", pressureMin)
          .put("pressureMax", pressureMax)
          .put("points", ps);
    }

    public static Stroke fromJson(JSONObject j) throws JSONException {
      Stroke s = new Stroke();
      s.id = string(j, "id", s.id, 100);
      s.tool = string(j, "tool", "pen", 40);
      s.pathMode = string(j, "pathMode", "smooth", 20);
      s.color = j.optInt("color", 0xff202837);
      s.width = number(j, "width", 4, .1f, 500);
      s.pressureMode = string(j, "pressureMode", "standard", 20);
      s.brushVersion = j.optInt("brushVersion", 1);
      s.pressureMin = number(j, "pressureMin", 0, 0, 2);
      s.pressureMax = number(j, "pressureMax", 1, 0, 2);
      JSONArray ps = j.getJSONArray("points");
      if (ps.length() > 100000) throw new JSONException("单笔采样点过多");
      for (int i = 0; i < ps.length(); i++) s.points.add(Point.fromJson(ps.getJSONObject(i)));
      return s;
    }
  }

  public static class Point {
    public float x, y, pressure = 1, tilt = 0;
    // Incoming linear segment style; zero widthScale means ordinary raw input.
    public float widthScale = 0, alphaScale = 1;
    public int styleRun;
    public long time;

    public Point() {}

    public Point(float x, float y, float pressure, float tilt, long time) {
      this.x = x;
      this.y = y;
      this.pressure = pressure;
      this.tilt = tilt;
      this.time = time;
    }

    public Point copy() {
      Point p = new Point(x, y, pressure, tilt, time);
      p.widthScale = widthScale;
      p.alphaScale = alphaScale;
      p.styleRun = styleRun;
      return p;
    }

    public JSONObject toJson() throws JSONException {
      JSONObject j =
          new JSONObject()
              .put("x", x)
              .put("y", y)
              .put("pressure", pressure)
              .put("tilt", tilt)
              .put("time", time);
      if (widthScale > 0)
        j.put("widthScale", widthScale).put("alphaScale", alphaScale).put("styleRun", styleRun);
      return j;
    }

    public static Point fromJson(JSONObject j) throws JSONException {
      Point p =
          new Point(
              number(j, "x", 0, -1000000, 1000000),
              number(j, "y", 0, -1000000, 1000000),
              number(j, "pressure", 1, 0, 2),
              number(j, "tilt", 0, 0, 3.2f),
              Math.max(0, j.optLong("time", 0)));
      p.widthScale = number(j, "widthScale", 0, 0, 2);
      p.alphaScale = number(j, "alphaScale", 1, 0, 1);
      p.styleRun = j.optInt("styleRun", 0);
      return p;
    }
  }

  public static class Element {
    public String kind = "text", text = "", path = "";
    public String targetPageId = "";
    public float x = 100, y = 100, width = 400, height = 100;
    public int color = 0xff202837;
    public float textSize = 36;

    /** Clockwise degrees around the element center. Original image assets stay unchanged. */
    public float rotation = 0;

    public boolean bold = false, revealed = false;
    public String textAlign = "left", pattern = "plain";

    public Element copy() {
      Element e = new Element();
      e.kind = kind;
      e.text = text;
      e.path = path;
      e.targetPageId = targetPageId;
      e.x = x;
      e.y = y;
      e.width = width;
      e.height = height;
      e.color = color;
      e.textSize = textSize;
      e.rotation = rotation;
      e.bold = bold;
      e.textAlign = textAlign;
      e.revealed = revealed;
      e.pattern = pattern;
      return e;
    }

    public JSONObject toJson() throws JSONException {
      return new JSONObject()
          .put("kind", kind)
          .put("text", text)
          .put("path", path)
          .put("targetPageId", targetPageId)
          .put("x", x)
          .put("y", y)
          .put("width", width)
          .put("height", height)
          .put("color", color)
          .put("textSize", textSize)
          .put("rotation", rotation)
          .put("bold", bold)
          .put("textAlign", textAlign)
          .put("revealed", revealed)
          .put("pattern", pattern);
    }

    public static Element fromJson(JSONObject j) throws JSONException {
      Element e = new Element();
      e.kind = string(j, "kind", "text", 20);
      if (!e.kind.equals("text") && !e.kind.equals("image") && !e.kind.equals("tape"))
        throw new JSONException("不支持的页面元素");
      e.text = string(j, "text", "", 100000);
      e.path = string(j, "path", "", 4096);
      e.targetPageId = string(j, "targetPageId", "", 100);
      e.x = number(j, "x", 100, -1000000, 1000000);
      e.y = number(j, "y", 100, -1000000, 1000000);
      e.width = number(j, "width", 400, 1, 40000);
      e.height = number(j, "height", 100, 1, 40000);
      e.color = j.optInt("color", 0xff202837);
      e.textSize = number(j, "textSize", 36, 4, 500);
      e.rotation = number(j, "rotation", 0, -360, 360);
      e.bold = j.optBoolean("bold", false);
      e.textAlign = string(j, "textAlign", "left", 10);
      e.revealed = j.optBoolean("revealed", false);
      e.pattern = string(j, "pattern", "plain", 20);
      return e;
    }
  }

  public static class Recording {
    public String id = UUID.randomUUID().toString(), pageId = "", title = "课堂录音", path = "";
    public long durationMs, createdAt = System.currentTimeMillis();

    public Recording copy() {
      Recording r = new Recording();
      r.id = id;
      r.pageId = pageId;
      r.title = title;
      r.path = path;
      r.durationMs = durationMs;
      r.createdAt = createdAt;
      return r;
    }

    JSONObject toJson() throws JSONException {
      return new JSONObject()
          .put("id", id)
          .put("pageId", pageId)
          .put("title", title)
          .put("path", path)
          .put("durationMs", durationMs)
          .put("createdAt", createdAt);
    }

    static Recording fromJson(JSONObject j) throws JSONException {
      Recording r = new Recording();
      r.id = string(j, "id", r.id, 100);
      r.pageId = string(j, "pageId", "", 100);
      r.title = string(j, "title", "课堂录音", 500);
      r.path = string(j, "path", "", 4096);
      r.durationMs = j.optLong("durationMs", 0);
      r.createdAt = j.optLong("createdAt", 0);
      return r;
    }
  }

  private static String string(JSONObject j, String key, String fallback, int max)
      throws JSONException {
    String value = j.optString(key, fallback);
    if (value.length() > max) throw new JSONException(key + " 长度超出范围");
    return value;
  }

  private static float number(JSONObject j, String key, float fallback, float min, float max)
      throws JSONException {
    double value = j.has(key) ? j.getDouble(key) : fallback;
    if (Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max)
      throw new JSONException(key + " 数值超出范围");
    return (float) value;
  }
}
