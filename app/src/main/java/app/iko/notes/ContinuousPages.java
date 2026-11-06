package app.iko.notes;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;

/** Shared paper coordinates for a vertical stack; no views or bitmaps per document page. */
final class ContinuousPages {
  private final List<RectF> pages = new ArrayList<>();
  float width, height;

  void update(NoteDocument document) {
    pages.clear();
    width = 0;
    height = 0;
    for (NoteDocument.Page page : document.pages) width = Math.max(width, page.width);
    for (NoteDocument.Page page : document.pages) {
      float x = (width - page.width) / 2;
      pages.add(new RectF(x, height, x + page.width, height + page.height));
      height += page.height + 32;
    }
    height -= 32;
  }

  RectF page(int index) {
    return pages.get(index);
  }

  int at(float x, float y, boolean nearest) {
    int low = 0, high = pages.size() - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      RectF p = pages.get(middle);
      if (y < p.top) high = middle - 1;
      else if (y > p.bottom) low = middle + 1;
      else return nearest || x >= p.left && x <= p.right ? middle : -1;
    }
    if (!nearest) return -1;
    if (low >= pages.size()) return pages.size() - 1;
    if (high < 0) return 0;
    return y - pages.get(high).bottom < pages.get(low).top - y ? high : low;
  }

  int count() {
    return pages.size();
  }
}
