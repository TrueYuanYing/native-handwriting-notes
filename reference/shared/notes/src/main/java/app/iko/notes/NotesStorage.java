package app.iko.notes;

import android.content.Context;
import java.io.*;
import java.util.*;
import org.json.*;

/** Explicit notebook files and seven-day trash; host recordings and external originals are excluded. */
public final class NotesStorage {
  public static final long RETENTION_MS=7L*24*60*60*1000;
  private NotesStorage() {}
  public static synchronized List<Map<String,Object>> list(Context context)throws Exception {
    prune(context,System.currentTimeMillis());
    DocumentStore store=new DocumentStore(context);List<Map<String,Object>> rows=new ArrayList<>();
    for(LibraryEntry entry:store.listEntries()){
      NoteDocument note=store.load(entry.id);Map<String,File> attachments=NoteFiles.dependencies(context.getFilesDir(),note.toJson());
      List<Map<String,Object>> files=new ArrayList<>();long bytes=store.fileSize(entry.id);
      files.add(file("笔记文档", "notes/"+entry.id+".json",bytes));
      for(Map.Entry<String,File> attachment:attachments.entrySet()){
        long size=attachment.getValue().length();bytes+=size;
        files.add(file(attachment.getValue().getName(),attachment.getKey().substring(6),size));
      }
      Map<String,Object> row=new LinkedHashMap<>();row.put("id",entry.id);row.put("title",entry.title);row.put("pages",entry.pageCount);
      try{row.put("coverPath",NoteCoverThumbnail.render(context,note,new File(context.getFilesDir(),"notes/"+note.id+".json"),context.getFilesDir()));}catch(IOException ignored){/* Missing previews never prevent file management. */}
      row.put("coverColor",note.cover);row.put("coverPattern",note.coverPattern);
      row.put("trashed",note.trashed);row.put("trashedAt",note.trashedAt);row.put("expiresAt",note.trashedAt>0?note.trashedAt+RETENTION_MS:0);
      row.put("bytes",bytes);row.put("files",files);rows.add(row);
    }
    return rows;
  }
  private static Map<String,Object> file(String name,String path,long bytes){Map<String,Object> out=new LinkedHashMap<>();out.put("name",name);out.put("path",path);out.put("bytes",bytes);return out;}
  public static synchronized void change(Context context,List<String> ids,String action)throws Exception {
    if(ids==null||ids.isEmpty()||ids.size()>10000)throw new IOException("请选择笔记");
    if(!Arrays.asList("trash","restore","delete").contains(action))throw new IOException("未知笔记操作");
    DocumentStore store=new DocumentStore(context);
    // Validate the complete selection before changing any document.
    for(String id:ids){NoteDocument note=store.load(id);if(action.equals("delete")&&!note.trashed)throw new IOException("请先将笔记移入回收站");}
    if(action.equals("delete")){purge(context,new HashSet<>(ids));return;}
    for(String id:ids){NoteDocument note=store.load(id);boolean trash=action.equals("trash");if(note.trashed==trash)continue;
      note.trashed=trash;note.trashedAt=trash?System.currentTimeMillis():0;store.save(note);}
  }
  public static synchronized boolean prune(Context context,long now)throws Exception {
    DocumentStore store=new DocumentStore(context);Set<String> expired=new HashSet<>();
    boolean changed=false;
    for(LibraryEntry entry:store.listEntries())if(entry.trashed){
      NoteDocument note=store.load(entry.id);
      if(note.trashedAt<=0){note.trashedAt=now;store.save(note);changed=true;}
      else if(now>=note.trashedAt && now-note.trashedAt>=RETENTION_MS)expired.add(note.id);
    }
    if(!expired.isEmpty())purge(context,expired);
    return changed||!expired.isEmpty();
  }
  private static void purge(Context context,Set<String> selected)throws Exception {
    DocumentStore store=new DocumentStore(context);Map<String,File> candidates=new HashMap<>(),retained=new HashMap<>();
    List<LibraryEntry> entries=store.listEntries();boolean complete=store.getWarnings().isEmpty();
    for(LibraryEntry entry:entries){
      try {NoteDocument note=store.load(entry.id);Map<String,File> refs=NoteFiles.dependencies(context.getFilesDir(),note.toJson());
        if(selected.contains(entry.id)){if(!note.trashed)throw new IOException("只能永久删除回收站内笔记");candidates.putAll(refs);}
        else retained.putAll(refs);
      }catch(Exception error){if(selected.contains(entry.id))throw error;complete=false;}
    }
    // Templates may refer to media also used by a deleted notebook.
    File templates=new File(context.getFilesDir(),"templates");
    try {protectTemplates(context.getFilesDir(),templates,retained);}catch(Exception error){complete=false;}
    for(String id:selected)store.delete(id);
    if(complete)for(Map.Entry<String,File> candidate:candidates.entrySet()){
      if(retained.containsKey(candidate.getKey())||candidate.getKey().startsWith("files/templates/"))continue;
      File file=candidate.getValue();if(file.getAbsoluteFile().equals(file.getCanonicalFile())&&file.isFile())file.delete();
    }
  }
  private static void protectTemplates(File root,File file,Map<String,File> retained)throws Exception {
    if(!file.exists())return;if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IOException("模板路径异常");
    if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("模板不可读");for(File child:children)protectTemplates(root,child,retained);}
    else if(file.getName().endsWith(".json"))retained.putAll(NoteFiles.dependencies(root,PlainNotesLibraryArchive.parseValue(PlainNotesLibrary.read(file,PlainNotesLibraryArchive.MAX_JSON_BYTES))));
  }
}
