package app.iko.notes;

import java.util.ArrayList;
import java.util.List;

/** Shared vector outlines for drawing, selection, erasing and shape thumbnails. */
final class GeometryPresets {
  static final String[][] GROUPS = {
    {"line", "arrow", "double-arrow"},
    {"rectangle", "rounded-rectangle", "square", "ellipse", "circle"},
    {
      "triangle",
      "right-triangle",
      "diamond",
      "parallelogram",
      "trapezoid",
      "pentagon",
      "hexagon",
      "star"
    },
    {"axes", "sine", "parabola", "arc"}
  };
  static final String[] GROUP_NAMES = {"连线", "基础", "多边形", "函数"};

  static boolean contains(String tool) {
    switch (tool) {
      case "line":
      case "arrow":
      case "double-arrow":
      case "rectangle":
      case "rounded-rectangle":
      case "square":
      case "ellipse":
      case "circle":
      case "triangle":
      case "right-triangle":
      case "diamond":
      case "parallelogram":
      case "trapezoid":
      case "pentagon":
      case "hexagon":
      case "star":
      case "axes":
      case "sine":
      case "parabola":
      case "arc":
        return true;
      default:
        return false;
    }
  }

  static String name(String id) {
    switch (id) {
      case "line":
        return "直线";
      case "arrow":
        return "箭头";
      case "double-arrow":
        return "双向箭头";
      case "rectangle":
        return "矩形";
      case "rounded-rectangle":
        return "圆角矩形";
      case "square":
        return "正方形";
      case "ellipse":
        return "椭圆";
      case "circle":
        return "圆";
      case "triangle":
        return "三角形";
      case "right-triangle":
        return "直角三角形";
      case "diamond":
        return "菱形";
      case "parallelogram":
        return "平行四边形";
      case "trapezoid":
        return "梯形";
      case "pentagon":
        return "五边形";
      case "hexagon":
        return "六边形";
      case "star":
        return "五角星";
      case "axes":
        return "坐标轴";
      case "sine":
        return "正弦曲线";
      case "parabola":
        return "抛物线";
      case "arc":
        return "圆弧";
      default:
        return "几何";
    }
  }

  static boolean hasArrow(String id) {
    return id.equals("arrow") || id.equals("double-arrow") || id.equals("axes");
  }

  static List<NoteDocument.Point> outline(
      String id, NoteDocument.Point a, NoteDocument.Point b, float width) {
    ArrayList<NoteDocument.Point> out = new ArrayList<>();
    float l = Math.min(a.x, b.x), t = Math.min(a.y, b.y);
    float w = Math.abs(b.x - a.x), h = Math.abs(b.y - a.y);
    if (id.equals("square") || id.equals("circle")) {
      w = h = Math.min(w, h);
      l = b.x < a.x ? a.x - w : a.x;
      t = b.y < a.y ? a.y - h : a.y;
    }
    float r = l + w, bottom = t + h, cx = l + w / 2, cy = t + h / 2;
    switch (id) {
      case "rectangle":
      case "square":
        polygon(out, l, t, r, t, r, bottom, l, bottom);
        break;
      case "rounded-rectangle":
        float radius = Math.min(w, h) * .18f;
        for (int corner = 0; corner < 4; corner++) {
          float x = corner == 0 || corner == 3 ? r - radius : l + radius;
          float y = corner < 2 ? bottom - radius : t + radius;
          for (int n = 0; n <= 8; n++) {
            double angle = (corner * 90 + n * 90f / 8) * Math.PI / 180;
            add(out, x + radius * (float) Math.cos(angle), y + radius * (float) Math.sin(angle));
          }
        }
        close(out);
        break;
      case "ellipse":
      case "circle":
      case "arc":
        for (int n = 0; n <= 128; n++) {
          double angle = n * Math.PI * (id.equals("arc") ? 1 : 2) / 128;
          add(out, cx + w / 2 * (float) Math.cos(angle), cy - h / 2 * (float) Math.sin(angle));
        }
        break;
      case "triangle":
        polygon(out, cx, t, r, bottom, l, bottom);
        break;
      case "right-triangle":
        polygon(out, l, t, l, bottom, r, bottom);
        break;
      case "diamond":
        polygon(out, cx, t, r, cy, cx, bottom, l, cy);
        break;
      case "parallelogram":
        polygon(out, l + w * .25f, t, r, t, l + w * .75f, bottom, l, bottom);
        break;
      case "trapezoid":
        polygon(out, l + w * .2f, t, l + w * .8f, t, r, bottom, l, bottom);
        break;
      case "pentagon":
      case "hexagon":
      case "star":
        int sides = id.equals("pentagon") ? 5 : id.equals("hexagon") ? 6 : 10;
        for (int n = 0; n < sides; n++) {
          double angle = -Math.PI / 2 + n * Math.PI * 2 / sides;
          float inner = id.equals("star") && n % 2 == 1 ? .43f : 1;
          add(
              out,
              cx + w / 2 * inner * (float) Math.cos(angle),
              cy + h / 2 * inner * (float) Math.sin(angle));
        }
        close(out);
        break;
      case "sine":
      case "parabola":
        for (int n = 0; n <= 96; n++) {
          float x = n / 96f;
          float y =
              id.equals("sine")
                  ? .5f - .5f * (float) Math.sin(x * Math.PI * 2)
                  : 1 - 4 * (x - .5f) * (x - .5f);
          add(out, l + w * x, t + h * y);
        }
        break;
      case "axes":
        add(out, l, cy);
        add(out, r, cy);
        arrow(out, l, cy, r, cy, Math.min(w, h) * .12f);
        add(out, cx, cy);
        add(out, cx, bottom);
        add(out, cx, t);
        arrow(out, cx, bottom, cx, t, Math.min(w, h) * .12f);
        break;
      default:
        add(out, a.x, a.y);
        add(out, b.x, b.y);
        if (hasArrow(id)) {
          float size =
              id.equals("arrow")
                  ? Math.max(20, width * 5)
                  : Math.min((float) Math.hypot(w, h) * .28f, Math.max(20, width * 5));
          arrow(out, a.x, a.y, b.x, b.y, size);
          if (id.equals("double-arrow")) {
            add(out, a.x, a.y);
            arrow(out, b.x, b.y, a.x, a.y, size);
          }
        }
    }
    return out;
  }

  private static void arrow(
      List<NoteDocument.Point> out, float ax, float ay, float bx, float by, float size) {
    double angle = Math.atan2(by - ay, bx - ax);
    add(out, bx - size * (float) Math.cos(angle - .45), by - size * (float) Math.sin(angle - .45));
    add(out, bx, by);
    add(out, bx - size * (float) Math.cos(angle + .45), by - size * (float) Math.sin(angle + .45));
    add(out, bx, by);
  }

  private static void polygon(List<NoteDocument.Point> out, float... xy) {
    for (int n = 0; n < xy.length; n += 2) add(out, xy[n], xy[n + 1]);
    close(out);
  }

  private static void close(List<NoteDocument.Point> out) {
    add(out, out.get(0).x, out.get(0).y);
  }

  private static void add(List<NoteDocument.Point> out, float x, float y) {
    out.add(new NoteDocument.Point(x, y, 1, 0, 0));
  }
}
