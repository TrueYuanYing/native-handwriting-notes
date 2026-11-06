package app.iko.notes;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class PaperCatalogTest {
  @Test public void paperParametersRoundTripAndOldNotesKeepDefaultSpacing() throws Exception {
    NoteDocument note=NoteDocument.create("纸张");NoteDocument.Page p=note.pages.get(0);
    p.paper="rice";p.texture="linen";p.color=0xffedf5ef;p.lineSpacing=72;
    PaperCatalog.applySize(p,0,true);
    NoteDocument.Page saved=NoteDocument.fromJson(note.toJson()).pages.get(0);
    assertEquals("rice",saved.paper);assertEquals("linen",saved.texture);assertEquals(p.color,saved.color);
    assertEquals(72,saved.lineSpacing,0);assertEquals(2800,saved.width,0);assertEquals(1980,saved.height,0);
    assertEquals(72,note.copy().pages.get(0).lineSpacing,0);
    JSONObject json=note.toJson();json.getJSONArray("pages").getJSONObject(0).remove("lineSpacing");
    assertEquals(0,NoteDocument.fromJson(json).pages.get(0).lineSpacing,0);
    p.lineSpacing=Float.NaN;assertThrows(org.json.JSONException.class,note::toJson);
  }
  @Test public void templatesNeverChangeInkAndContentLocksSize() {
    NoteDocument.Page p=new NoteDocument.Page();NoteDocument.Stroke stroke=new NoteDocument.Stroke();
    stroke.points.add(new NoteDocument.Point(1350,1900,.5f,0,1));p.strokes.add(stroke);
    for(String[] template:PaperCatalog.TEMPLATES){
      PaperCatalog.applyTemplate(p,template[0]);assertEquals(template[0],p.paper);
      assertSame(stroke,p.strokes.get(0));assertEquals(1350,stroke.points.get(0).x,0);
      assertFalse(PaperCatalog.applySize(p,3,false));assertEquals(1400,p.width,0);assertEquals(1980,p.height,0);
    }
    assertTrue(PaperCatalog.TEMPLATES.length>=20);
  }
  @Test public void inheritedPageIsEmptyWithNewIdentityAndSamePaper() {
    NoteDocument.Page p=new NoteDocument.Page();p.paper="cornell";p.lineSpacing=60;p.texture="cotton";
    p.backgroundPath="lesson.pdf";p.pdfPage=2;p.elements.add(new NoteDocument.Element());
    NoteDocument.Page next=PaperCatalog.newPageLike(p);
    assertNotEquals(p.id,next.id);assertEquals(p.paper,next.paper);assertEquals(60,next.lineSpacing,0);
    assertEquals(p.texture,next.texture);assertTrue(next.strokes.isEmpty());assertTrue(next.elements.isEmpty());
    assertEquals("",next.backgroundPath);assertEquals(-1,next.pdfPage);
  }
  @Test public void mediaAlsoLocksResizeAndEverySizeSupportsOrientation(){
    NoteDocument.Page p=new NoteDocument.Page();
    for(int i=0;i<PaperCatalog.SIZES.length;i++){
      assertTrue(PaperCatalog.applySize(p,i,false));float w=p.width,h=p.height;
      assertTrue(w<=h);assertTrue(PaperCatalog.applySize(p,i,true));assertEquals(h,p.width,0);assertEquals(w,p.height,0);
    }
    p.backgroundPath="image.png";assertFalse(PaperCatalog.applySize(p,0,false));
  }
}
