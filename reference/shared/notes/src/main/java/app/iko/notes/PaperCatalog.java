package app.iko.notes;

/** Paper-only operations never touch ink, media or page identity. */
public final class PaperCatalog {
  private PaperCatalog() {}

  public static final String[] CATEGORIES = {"课堂", "数学", "计划", "练字"};
  public static final String[][] TEMPLATES = {
    {"blank", "空白", "课堂"}, {"lined", "横线", "课堂"},
    {"wide-lined", "宽横线", "课堂"}, {"dot", "点阵", "课堂"},
    {"cornell", "康奈尔", "课堂"}, {"two-column", "双栏", "课堂"},
    {"mistakes", "错题本", "课堂"}, {"lecture", "课堂提纲", "课堂"},
    {"grid", "方格", "数学"}, {"engineering", "工程格", "数学"},
    {"isometric", "等距格", "数学"}, {"axes", "坐标纸", "数学"},
    {"geometry", "几何推演", "数学"}, {"planner", "周计划", "计划"},
    {"weekly", "课表", "计划"}, {"checklist", "待办", "计划"},
    {"daily", "每日安排", "计划"}, {"monthly", "月历", "计划"},
    {"habit", "习惯打卡", "计划"}, {"square", "田字格", "练字"},
    {"rice", "米字格", "练字"}, {"english", "英文四线", "练字"},
    {"music", "五线谱", "练字"}, {"storyboard", "分镜", "练字"}
  };
  public static final String[] SIZES = {
    "A3", "A4", "A5", "A6", "B5", "Letter", "平板4:3", "平板16:10", "方形"
  };
  private static final float[][] DIMENSIONS = {
    {1980, 2800},
    {1400, 1980},
    {990, 1400},
    {700, 990},
    {1176, 1664},
    {1440, 1864},
    {1485, 1980},
    {1238, 1980},
    {1400, 1400}
  };
  public static final String[] TEXTURES = {"smooth", "cotton", "linen", "recycled", "kraft"};
  public static final String[] TEXTURE_LABELS = {"光滑", "棉纸", "亚麻", "再生", "牛皮"};
  public static final int[] COLORS = {
    0xfffaf9f6, 0xffffffff, 0xfffff4d9, 0xffedf5ef, 0xffedf3fc, 0xfff8edf3, 0xff292f3b, 0xff15242c
  };

  public static boolean hasContent(NoteDocument.Page p) {
    return !p.strokes.isEmpty() || !p.elements.isEmpty() || !p.backgroundPath.isEmpty();
  }

  public static void applyTemplate(NoteDocument.Page p, String id) {
    for (String[] template : TEMPLATES)
      if (template[0].equals(id)) {
        p.paper = id;
        return;
      }
  }

  /** False means a content page was protected from an implicit resize. */
  public static boolean applySize(NoteDocument.Page p, int index, boolean landscape) {
    if (hasContent(p) || index < 0 || index >= DIMENSIONS.length) return false;
    p.width = DIMENSIONS[index][landscape ? 1 : 0];
    p.height = DIMENSIONS[index][landscape ? 0 : 1];
    return true;
  }

  public static NoteDocument.Page newPageLike(NoteDocument.Page source) {
    NoteDocument.Page p = new NoteDocument.Page();
    p.paper = source.paper;
    p.texture = source.texture;
    p.color = source.color;
    p.width = source.width;
    p.height = source.height;
    p.lineSpacing = source.lineSpacing;
    p.infinite = source.infinite;
    return p;
  }
}
