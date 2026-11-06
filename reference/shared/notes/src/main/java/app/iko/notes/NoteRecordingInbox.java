package app.iko.notes;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.util.*;

/** Persistent handoff from the shared encoder to the original notebook save queue. */
public final class NoteRecordingInbox {
  private static final Set<String> active=new HashSet<>();
  private static File home(Context context){return new File(context.getFilesDir(),"note-recording-inbox");}
  public static File audioDirectory(Context context){return new File(context.getFilesDir(),"media/note-recordings");}
  public static synchronized void write(Context context,String ownerJson,File audio,String id,long duration,long created,boolean complete)throws Exception {
    JSONObject owner=new JSONObject(ownerJson);String note=owner.getString("noteId"),page=owner.getString("pageId");
    if(!note.matches("[A-Za-z0-9_-]{1,100}")||!page.matches("[A-Za-z0-9_-]{1,100}")||!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("笔记录音归属无效");
    File file=audio.getCanonicalFile(),directory=audioDirectory(context).getCanonicalFile();
    if(!file.getParentFile().equals(directory))throw new IOException("录音路径无效");
    NoteDocument.Recording clip=new NoteDocument.Recording();clip.id=id;clip.pageId=page;clip.path=file.getPath();clip.durationMs=duration;clip.createdAt=created;
    File inbox=home(context);if(!inbox.isDirectory()&&!inbox.mkdirs())throw new IOException("录音待保存目录不可用");
    AtomicFile target=new AtomicFile(new File(inbox,id+".json"));FileOutputStream out=target.startWrite();
    try{out.write(new JSONObject().put("noteId",note).put("complete",complete).put("recording",clip.toJson()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));target.finishWrite(out);}
    catch(Exception error){target.failWrite(out);throw error;}
    if(complete)active.remove(id);else active.add(id);
  }
  public static synchronized void finished(String id){active.remove(id);}
  private static Set<String> entries(Context context){
    Set<String> names=new LinkedHashSet<>();File[] files=home(context).listFiles();if(files!=null)for(File file:files){String name=file.getName();
      if(name.endsWith(".bak")||name.endsWith(".new"))name=name.substring(0,name.length()-4);
      if(name.matches("[A-Za-z0-9_-]{1,100}\\.json"))names.add(name);
    }
    for(Iterator<String> iterator=names.iterator();iterator.hasNext();){
      String name=iterator.next();
      try{
        JSONObject item=read(context,name),clip=item.getJSONObject("recording");
        String id=clip.getString("id"),note=item.getString("noteId"),page=clip.getString("pageId");
        if(!id.matches("[A-Za-z0-9_-]{1,100}")||!note.matches("[A-Za-z0-9_-]{1,100}")
            ||!page.matches("[A-Za-z0-9_-]{1,100}")||!(item.get("complete") instanceof Boolean)
            ||!name.equals(id+".json")||active.contains(id))continue;
        File audio=new File(clip.getString("path")).getCanonicalFile();
        if(audio.getParentFile().equals(audioDirectory(context).getCanonicalFile())&&!audio.exists()){
          // Zero-sample stops already removed the audio; prune only their stale handoff.
          new AtomicFile(new File(home(context),name)).delete();iterator.remove();
        }
      }catch(Exception ignored){/* Keep unknown, malformed and existing audio handoffs for recovery. */}
    }
    return names;
  }
  private static JSONObject read(Context context,String name)throws Exception {
    File base=new File(home(context),name),pending=new File(base.getPath()+".new");
    if(!base.exists()&&!new File(base.getPath()+".bak").exists()&&pending.isFile()){
      JSONObject recovered=PlainNotesLibraryArchive.json(pending,16384);
      if(recovered.has("noteId")&&recovered.has("recording")&&!pending.renameTo(base))throw new IOException("录音恢复元数据暂不可用");
    }
    try(InputStream in=new AtomicFile(new File(home(context),name)).openRead()){
      return PlainNotesLibraryArchive.parseObject(PlainNotesLibraryArchive.readBytes(in,16384));
    }
  }
  public static synchronized void checkCapacity(Context context,String ownerJson)throws Exception {
    String note=new JSONObject(ownerJson).getString("noteId");if(!note.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("笔记ID无效");
    Set<String> ids=new HashSet<>();File source=new File(context.getFilesDir(),"notes/"+note+".json");
    if(source.isFile())for(NoteDocument.Recording clip:new DocumentStore(context).load(note).recordings)ids.add(clip.id);
    for(String name:entries(context))try{JSONObject item=read(context,name);if(note.equals(item.optString("noteId")))ids.add(item.getJSONObject("recording").getString("id"));}catch(Exception ignored){}
    if(ids.size()>=200)throw new IOException("这本笔记已有200段录音，请换一本继续；已有音频已保留");
  }
  static synchronized List<NoteDocument.Recording> pending(Context context,String id)throws Exception {
    List<NoteDocument.Recording> clips=new ArrayList<>();
    for(String name:entries(context)){
      try{
        JSONObject item=read(context,name);
        if(!id.equals(item.optString("noteId")))continue;
        NoteDocument.Recording clip=NoteDocument.Recording.fromJson(item.getJSONObject("recording"));
        if(active.contains(clip.id))continue;
        File audio=new File(clip.path).getCanonicalFile();
        if(!clip.id.matches("[A-Za-z0-9_-]{1,100}")||!audio.getParentFile().equals(audioDirectory(context).getCanonicalFile())||!audio.isFile())continue;
        if(!item.optBoolean("complete"))clip.title="中断录音（原文件）";
        if(audio.length()>0)clips.add(clip);
      }catch(Exception ignored){/* Preserve malformed handoffs for recovery; never discard audio. */}
    }
    clips.sort(Comparator.comparingLong(a->a.createdAt));return clips;
  }
  static synchronized void acknowledge(Context context,NoteDocument saved)throws Exception {
    for(NoteDocument.Recording clip:pending(context,saved.id))for(NoteDocument.Recording existing:saved.recordings)
      if(existing.id.equals(clip.id)){new AtomicFile(new File(home(context),clip.id+".json")).delete();break;}
  }
  public static synchronized boolean hasPending(Context context){return !entries(context).isEmpty();}
  /** Caller has hidden and flushed/disposed any retained editor before library transfers. */
  public static synchronized void attachSaved(Context context)throws Exception {
    Set<String> notes=new HashSet<>();for(String name:entries(context))try{notes.add(read(context,name).getString("noteId"));}catch(Exception ignored){}
    DocumentStore store=new DocumentStore(context);
    for(String id:notes){List<NoteDocument.Recording> clips=pending(context,id);if(clips.isEmpty())continue;
      NoteDocument note=store.load(id);boolean changed=false;
      for(NoteDocument.Recording clip:clips){boolean exists=false;for(NoteDocument.Recording old:note.recordings)if(old.id.equals(clip.id)){exists=true;break;}
        if(!exists){if(note.recordings.size()>=200)throw new IOException("笔记录音需要恢复，请先整理该笔记的录音");
          clip.title=note.nextRecordingTitle()+(clip.title.startsWith("中断")?"（中断原文件）":"");note.recordings.add(clip);changed=true;}}
      if(changed)store.save(note);acknowledge(context,store.load(id));
    }
  }
}
