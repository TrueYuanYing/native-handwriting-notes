package app.iko.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Same renderer as the real page; preview pressure/tilt is illustrative. */
final class BrushPreviewView extends View {
  private String tool = "pen", mode = "standard";
  private int color = 0xff272d3b;
  private float width = 4;
  private final Paint paper = new Paint(Paint.ANTI_ALIAS_FLAG);

  BrushPreviewView(Context context) {
    super(context);
    setContentDescription("当前笔型、笔宽与压力变化预览");
  }

  void setStyle(String tool, int color, float width, String mode) {
    this.tool = tool;
    this.color = color;
    this.width = width;
    this.mode = mode;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float radius = Ui.dp(getContext(), 8);
    paper.setColor(0xffe8edf1);
    canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paper);
    paper.setColor(0xffd5dee5);
    paper.setStrokeWidth(Ui.dp(getContext(), .5f));
    canvas.drawLine(
        Ui.dp(getContext(), 9),
        getHeight() * .72f,
        getWidth() - Ui.dp(getContext(), 9),
        getHeight() * .72f,
        paper);
    float factor = getWidth() / 420f;
    int save = canvas.save();
    canvas.translate(0, getHeight() / 2f);
    canvas.scale(factor, factor);
    NoteDocument.Stroke stroke = new NoteDocument.Stroke();
    stroke.tool = tool;
    stroke.brushVersion = 2;
    stroke.color = color;
    stroke.width = width * ("highlighter".equals(tool) ? 4 : 1);
    stroke.pressureMode = mode;
    for (int i = 0; i <= 60; i++) {
      float t = i / 60f;
      stroke.points.add(
          new NoteDocument.Point(
              28 + t * 364,
              (float) Math.sin(t * Math.PI * 3) * 12,
              .1f + (float) Math.sin(t * Math.PI) * .9f,
              .2f,
              i * 10));
    }
    PageRenderer.drawInk(canvas, stroke);
    canvas.restoreToCount(save);
  }
}
