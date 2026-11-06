package app.iko.notes;

import android.content.Context;
import android.net.Uri;
import com.iko.integration.BackupArchive;
import org.json.*;
import java.io.*;
import java.util.*;

/** Verify an archive before offering notebook covers; no user-library writes before confirmation. */
public final class PreparedNotesImport implements AutoCloseable {
  public final String token=UUID.randomUUID().toString();
  private final Context context;
  private final File home,stage;
  private PreparedNotesImport(Context context)throws IOException {
    this.context=context;home=new File(context.getCacheDir(),"notes-import-preview/"+token);stage=new File(home,"verified");
    if(!home.mkdirs())throw new IOException("无法准备导入预览");
  }
  public static PreparedNotesImport prepare(Context context,Uri uri,char[] password)throws Exception {
    PreparedNotesImport preview=new PreparedNotesImport(context);boolean success=false;
    try {
      File archive=new File(preview.home,"input.ikonoteslib");
      try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(archive)){
        if(in==null)throw new IOException("文件不可读取");PlainNotesLibraryArchive.copy(in,out,null,PlainNotesLibraryArchive.MAX_BYTES);
      }
      boolean legacy=PlainNotesLibraryArchive.isLegacy(archive);
      if(legacy&&password.length==0)throw new NotesLibraryMigration.LegacyPasswordRequiredException();
      Map<String,String> entries=legacy?BackupArchive.read(archive,preview.stage,password):PlainNotesLibraryArchive.read(archive,preview.stage);
      JSONObject identity=PlainNotesLibraryArchive.json(new File(preview.stage,"identity.json"),1024*1024);
      if(!"app.iko.notes".equals(identity.optString("package"))||!"notes-library".equals(identity.optString("scope")))throw new IOException("请选择 IKO 笔记库文件");
      Map<String,String> paths=new HashMap<>();
      for(String member:entries.keySet())if(member.startsWith("files/")){
        String absolute=new File(preview.stage,member).getAbsolutePath();paths.put(member,absolute);paths.put(PlainNotesLibrary.LOGICAL+member,absolute);
      }
      for(String member:entries.keySet())if(member.startsWith("files/")&&member.endsWith(".json")){
        File file=new File(preview.stage,member);boolean note=member.startsWith("files/notes/");
        byte[] raw=PlainNotesLibrary.read(file,note?DocumentStore.MAX_FILE_BYTES:PlainNotesLibraryArchive.MAX_JSON_BYTES);
        Object json=PlainNotesLibraryArchive.parseValue(note?DocumentStore.decodeStorage(raw):raw);
        PlainNotesLibrary.relocate(json,paths,null);
        byte[] bytes=json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        PlainNotesLibrary.write(file,note?DocumentStore.encodeStorage(bytes):bytes);
      }
      preview.rows();success=true;return preview;
    }finally{if(!success)preview.close();}
  }
  public List<Map<String,Object>> rows()throws Exception {
    List<Map<String,Object>> rows=new ArrayList<>();File[] files=new File(stage,"files/notes").listFiles((dir,name)->name.endsWith(".json"));
    if(files==null)throw new IOException("文件中没有可导入的笔记");
    for(File file:files){
      NoteDocument note=NoteDocument.fromJson(PlainNotesLibraryArchive.parseObject(DocumentStore.decodeStorage(PlainNotesLibrary.read(file,DocumentStore.MAX_FILE_BYTES))));
      if(!file.getName().equals(note.id+".json"))throw new IOException("笔记身份不匹配");
      Map<String,Object> row=new LinkedHashMap<>();row.put("id",note.id);row.put("title",note.title);row.put("pages",note.pages.size());row.put("bytes",file.length());row.put("files",Collections.emptyList());row.put("expiresAt",0);row.put("trashed",note.trashed);
      row.put("exists",new File(context.getFilesDir(),"notes/"+note.id+".json").exists());
      try{row.put("coverPath",NoteCoverThumbnail.render(context,note,file,new File(stage,"files")));}catch(IOException ignored){}
      rows.add(row);
    }
    return rows;
  }
  public int install(Set<String> selected)throws Exception {
    if(selected==null||selected.isEmpty())throw new IOException("请至少选择一本笔记");
    File payload=new File(home,"selected"),archive=new File(home,"selected.ikonoteslib");
    File settings=new File(stage,"notes-preferences.json");
    JSONObject preferences=settings.isFile()?PlainNotesLibraryArchive.json(settings,4*1024*1024):new JSONObject();
    PlainNotesLibrary.prepareExport(new File(stage,"files"),payload,preferences,selected);
    PlainNotesLibraryArchive.write(payload,archive);
    return NotesLibraryMigration.importLibrary(context,Uri.fromFile(archive));
  }
  @Override public void close(){BackupArchive.erase(home);}
}
