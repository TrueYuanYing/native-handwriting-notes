package app.iko.notes;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.io.*;

/** Small, disposable thumbnails using the same cover artwork as the native shelf. IO thread only. */
final class NoteCoverThumbnail {
  static String render(Context context, NoteDocument note, File source, File allowedRoot)throws IOException {
    File directory=new File(context.getCacheDir(),"note-selection-covers");
    if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法准备封面缓存");
    if(!note.id.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("笔记ID无效");
    File target=new File(directory,note.id+"-"+source.lastModified()+"-"+source.length()+".png");
    if(target.isFile())return target.getAbsolutePath();
    Bitmap bitmap=Bitmap.createBitmap(120,160,Bitmap.Config.ARGB_8888);
    try {
      Canvas canvas=new Canvas(bitmap);
      CoverArtView art=new CoverArtView(context,note.coverPattern,CoverCatalog.color(note.cover));
      art.layout(0,0,120,160);art.draw(canvas);
      if(note.coverImagePath!=null&&!note.coverImagePath.isEmpty()) {
        File image=new File(note.coverImagePath).getCanonicalFile(),base=allowedRoot.getCanonicalFile();
        if(image.isFile()&&image.getPath().startsWith(base.getPath()+File.separator)) {
          BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(image.getPath(),options);
          int sample=1;while(options.outWidth/sample>480||options.outHeight/sample>640)sample*=2;
          options.inJustDecodeBounds=false;options.inSampleSize=sample;
          Bitmap photo=BitmapFactory.decodeFile(image.getPath(),options);
          if(photo!=null){canvas.drawBitmap(photo,null,new Rect(0,0,120,160),new Paint(Paint.FILTER_BITMAP_FLAG));photo.recycle();}
        }
      }
      File temp=new File(target.getPath()+".tmp");
      try(FileOutputStream out=new FileOutputStream(temp)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("封面暂不可用");out.getFD().sync();}
      if(!temp.renameTo(target)){temp.delete();throw new IOException("封面缓存暂不可用");}
      return target.getAbsolutePath();
    }finally{bitmap.recycle();}
  }
}
