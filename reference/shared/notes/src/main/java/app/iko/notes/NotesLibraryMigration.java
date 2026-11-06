package app.iko.notes;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import com.iko.integration.BackupArchive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Explicit whole-library migration. Stable IDs, editable data and owned media are retained. */
public final class NotesLibraryMigration {
  private NotesLibraryMigration() {}

  /** Legacy encrypted archives require a user-supplied old password; new exports never do. */
  public static final class LegacyPasswordRequiredException extends IOException {
    public LegacyPasswordRequiredException(){super("这是旧版加密笔记库，请填写该文件原有的密码");}
  }
  public static int importLibrary(Context context,Uri uri)throws Exception{return importLibrary(context,uri,new char[0]);}

  /** Caller must flush and await every note/media/template writer, then hold its transfer busy guard. */
  public static synchronized Map<String,Object> exportLibrary(Context context,Uri outputUri)throws Exception {
    return exportLibrary(context,outputUri,null);
  }
  public static synchronized Map<String,Object> exportLibrary(Context context,Uri outputUri,Set<String> selected)throws Exception {
    recoverInterrupted(context);
    File scratch=new File(context.getCacheDir(),"notes-transfer-"+UUID.randomUUID());
    if(!scratch.mkdirs())throw new IOException("无法准备笔记导出");
    try {
      JSONObject preferences=encodePreferences(context.getSharedPreferences("iko_input",0).getAll());
      for(String key:keys(preferences))if(!PlainNotesLibrary.preferenceAllowed(key))preferences.remove(key);
      File payload=new File(scratch,"payload"),archive=new File(scratch,"library.ikonoteslib");
      Map<String,Object> statistics=PlainNotesLibrary.prepareExport(context.getFilesDir(),payload,preferences,selected);
      PlainNotesLibraryArchive.write(payload,archive);PlainNotesLibraryArchive.cancelled();
      try(InputStream in=new FileInputStream(archive);OutputStream out=context.getContentResolver().openOutputStream(outputUri,"wt")){
        if(out==null)throw new IOException("无法写入笔记库导出文件");PlainNotesLibraryArchive.copy(in,out,null,PlainNotesLibraryArchive.MAX_BYTES);out.flush();
      }
      statistics.put("bytes",archive.length());return statistics;
    }finally{BackupArchive.erase(scratch);}
  }

  public static synchronized int importLibrary(Context context, Uri uri, char[] password) throws Exception {
    if(password==null)password=new char[0];
    recoverInterrupted(context);
    File scratch = new File(context.getCacheDir(), "notes-transfer-" + UUID.randomUUID());
    if (!scratch.mkdirs()) throw new IOException("无法准备笔记迁移");
    File archive = new File(scratch,"input.ikonoteslib");
    List<File> installed = new ArrayList<>();
    File importedRoot = null;
    boolean success = false;
    boolean preferencesChanged = false;
    File journal=new File(context.getFilesDir(),"notes_migrations/pending.json");
    android.content.SharedPreferences prefs=context.getSharedPreferences("iko_input",0);
    Map<String,?> previous = new HashMap<>(prefs.getAll());
    try {
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      try(InputStream input=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(archive)) {
        if(input==null)throw new IOException("无法读取迁移文件");
        byte[] buffer=new byte[65536];long total=0;int count;
        while((count=input.read(buffer))!=-1){PlainNotesLibraryArchive.cancelled();if((total+=count)>8L*1024*1024*1024)throw new IOException("笔记库过大");out.write(buffer,0,count);digest.update(buffer,0,count);}
      }
      String hash=hex(digest.digest());
      File receipt = new File(context.getFilesDir(),"notes_migrations/"+hash+".json");
      if(receipt.isFile()){success=true;return new JSONObject(read(receipt,1024*1024)).getInt("notes");}
      File stage=new File(scratch,"verified");
      boolean legacy=PlainNotesLibraryArchive.isLegacy(archive);
      if(legacy&&password.length==0)throw new LegacyPasswordRequiredException();
      Map<String,String> files=legacy?BackupArchive.read(archive,stage,password):PlainNotesLibraryArchive.read(archive,stage);
      for(String member:files.keySet()){
        PlainNotesLibraryArchive.checkName(member);
        if(member.startsWith("files/notes/")&&!member.matches("files/notes/[A-Za-z0-9_-]{1,100}\\.json"))throw new IOException("笔记文件名无效");
      }
      JSONObject identity=PlainNotesLibraryArchive.json(new File(stage,"identity.json"),1024*1024);
      if(!"app.iko.notes".equals(identity.getString("package")) || !"notes-library".equals(identity.getString("scope")))throw new IOException("请选择独立笔记的完整迁移文件");
      File stagedNotes=new File(stage,"files/notes");
      File[] source=stagedNotes.listFiles((dir,name)->name.endsWith(".json"));
      if(source==null)source=new File[0];
      File mediaRoot=new File(context.getFilesDir(),"notes_imports/"+hash.substring(0,20));
      if(mediaRoot.exists())throw new IOException("上次迁移尚未完成，请先保留完整备份再检查");
      importedRoot = mediaRoot;
      Map<String,String> paths=new HashMap<>();
      Set<String> reusedTemplates=new HashSet<>();
      for(String member:files.keySet()){
        if(!member.startsWith("files/") || member.startsWith("files/notes/"))continue;
        String relative=member.substring(6);
        PlainNotesLibraryArchive.checkName(member);
        if(!(relative.startsWith("media/") || relative.startsWith("templates/") || relative.startsWith("imports/") || relative.startsWith("onenote-local/") || relative.startsWith("import-reports/") || relative.startsWith("notes_imports/")))throw new IOException("不支持的笔记资源目录");
        File target=relative.startsWith("templates/")?new File(context.getFilesDir(),relative):new File(mediaRoot,relative);
        safeTarget(context.getFilesDir(),target);
        if(target.exists()){
          if(!relative.startsWith("templates/")||!target.isFile())throw new IOException("已有同名资源，迁移不会覆盖原文件");
          reusedTemplates.add(member);
        }
        paths.put(member,target.getAbsolutePath());
        paths.put(PlainNotesLibrary.LOGICAL+member,target.getAbsolutePath());
        paths.put("/data/user/0/app.iko.notes/"+member,target.getAbsolutePath());
        paths.put("/data/data/app.iko.notes/"+member,target.getAbsolutePath());
      }
      File generated=new File(scratch,"generated");generated.mkdirs();
      ArrayList<String> ids=new ArrayList<>();
      for(File file:source){String member="files/notes/"+file.getName();String target=new File(context.getFilesDir(),"notes/"+file.getName()).getAbsolutePath();paths.put(member,target);paths.put(PlainNotesLibrary.LOGICAL+member,target);}
      for(File file:source){
        PlainNotesLibraryArchive.cancelled();
        String name=file.getName();String id=name.substring(0,name.length()-5);
        if(!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("笔记ID无效");
        File noteTarget=new File(context.getFilesDir(),"notes/"+name);safeTarget(context.getFilesDir(),noteTarget);
        if(noteTarget.exists())throw new IOException("已有相同ID的笔记，原笔记保留；可改用单本备份恢复为副本");
        byte[] raw=DocumentStore.decodeStorage(bytes(file,DocumentStore.MAX_FILE_BYTES));
        JSONObject document=PlainNotesLibraryArchive.parseObject(raw);
        relocate(document,paths);
        NoteDocument checked=NoteDocument.fromJson(document);
        if(!id.equals(checked.id))throw new IOException("笔记ID与文件不一致");
        File output=new File(generated,name);
        try(OutputStream out=new FileOutputStream(output)){out.write(DocumentStore.encodeStorage(document.toString().getBytes(StandardCharsets.UTF_8)));}
        ids.add(id);
      }
      // Validate typed preferences and JSON resources before touching the real library.
      File preferenceFile=new File(stage,"notes-preferences.json");
      JSONObject values=preferenceFile.isFile()?PlainNotesLibraryArchive.json(preferenceFile,4*1024*1024):new JSONObject();
      decodePreferences(values);
      for(String member:files.keySet())if(!member.startsWith("files/notes/")&&paths.containsKey(member)&&member.endsWith(".json")){
        File input=new File(stage,member);Object json=PlainNotesLibraryArchive.parseValue(bytes(input,128*1024*1024));relocate(json,paths);
        PlainNotesLibrary.write(input,json.toString().getBytes(StandardCharsets.UTF_8));
      }
      PlainNotesLibraryArchive.cancelled();
      Map<String,File> stagedReferences=new HashMap<>();
      for(String member:files.keySet())if(paths.containsKey(member))stagedReferences.put(paths.get(member),new File(stage,member));
      for(String member:reusedTemplates)if(!sameTemplate(new File(stage,member),new File(paths.get(member)),stagedReferences,context.getFilesDir(),stage,0,new HashSet<>()))throw new IOException("已有不同内容的同名模板，原模板保留；请调整模板名称后重试");
      // Everything has parsed and validated before adding any real library file.
      JSONArray planned=new JSONArray();
      for(String member:files.keySet()){
        PlainNotesLibraryArchive.cancelled();
        if(reusedTemplates.contains(member))continue;
        String target=paths.get("/data/user/0/app.iko.notes/"+member);
        if(target!=null)planned.put(context.getFilesDir().toPath().relativize(new File(target).toPath()).toString().replace(File.separatorChar,'/'));
      }
      for(String id:ids)planned.put("notes/"+id+".json");
      writeAtomic(journal,new JSONObject().put("receipt",hash+".json").put("files",planned).put("preferences",encodePreferences(previous)));
      for(String member:files.keySet()){
        PlainNotesLibraryArchive.cancelled();
        if(reusedTemplates.contains(member))continue;
        String target=paths.get("/data/user/0/app.iko.notes/"+member);
        if(target==null)continue;
        File destination=new File(target);destination.getParentFile().mkdirs();
        if(destination.exists())throw new IOException("迁移目标已存在");
        if(!destination.createNewFile())throw new IOException("迁移目标已存在，原文件保留");
        installed.add(destination);
        File input=new File(stage,member);
        copy(input,destination);
      }
      for(String id:ids){PlainNotesLibraryArchive.cancelled();File target=new File(context.getFilesDir(),"notes/"+id+".json");target.getParentFile().mkdirs();if(!target.createNewFile())throw new IOException("已有相同笔记，原文件保留");installed.add(target);copy(new File(generated,id+".json"),target);}
      if(preferenceFile.isFile()){
        android.content.SharedPreferences.Editor editor=prefs.edit();
        for(String key:keys(values)){
          if(!PlainNotesLibrary.preferenceAllowed(key))continue;
          preferencesChanged=true;
          JSONObject item=values.getJSONObject(key);Object value=item.get("value");
          switch(item.getString("type")){
            case "boolean":editor.putBoolean(key,(Boolean)value);break;
            case "int":editor.putInt(key,((Number)value).intValue());break;
            case "long":editor.putLong(key,((Number)value).longValue());break;
            case "float":editor.putFloat(key,((Number)value).floatValue());break;
            case "set":{JSONArray a=(JSONArray)value;Set<String>s=new HashSet<>();for(int i=0;i<a.length();i++)s.add(a.getString(i));editor.putStringSet(key,s);break;}
            default:editor.putString(key,value.toString());
          }
        }
        if(!editor.commit())throw new IOException("笔记偏好保存未确认");
      }
      receipt.getParentFile().mkdirs();
      AtomicFile atomic=new AtomicFile(receipt);FileOutputStream out=atomic.startWrite();
      try{out.write(new JSONObject().put("notes",ids.size()).put("source","0.8.12").put("ids",new JSONArray(ids)).toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}
      catch(Exception error){atomic.failWrite(out);throw error;}
      success=true;return ids.size();
    }finally{
      Arrays.fill(password,'\0');BackupArchive.erase(scratch);
      if(!success){
        boolean rolledBack=true;for(File file:installed)if(file.exists()&&!file.delete())rolledBack=false;
        if(importedRoot!=null){BackupArchive.erase(importedRoot);if(importedRoot.exists())rolledBack=false;}
        if(preferencesChanged)restorePreferences(prefs,previous);
        if(!rolledBack)throw new IOException("迁移回滚尚未完成，恢复记录已保留；请保留备份后重试");
      }
      journal.delete();
    }
  }

  /** Roll back an interrupted import before the book library can be edited. */
  public static synchronized void recoverInterrupted(Context context)throws Exception {
    File base=context.getFilesDir(),journal=new File(base,"notes_migrations/pending.json");
    if(!journal.isFile())return;
    JSONObject data=PlainNotesLibraryArchive.json(journal,16*1024*1024);
    String receiptName=data.getString("receipt");
    if(!receiptName.matches("[0-9a-f]{64}\\.json"))throw new IOException("迁移恢复记录无效");
    if(!new File(base,"notes_migrations/"+receiptName).isFile()){
      JSONArray paths=data.getJSONArray("files");
      for(int i=0;i<paths.length();i++){
        String path=paths.getString(i);
        if(!(path.startsWith("notes/")||path.startsWith("templates/")||path.startsWith("notes_imports/")))throw new IOException("迁移恢复目录无效");
        File file=new File(base,path);
        if(!file.getCanonicalPath().startsWith(base.getCanonicalPath()+File.separator)||path.contains(".."))throw new IOException("迁移恢复路径无效");
        if(file.isFile()&&!file.delete())throw new IOException("迁移恢复尚未完成，请保留备份后重试");
      }
      File imported=new File(base,"notes_imports/"+receiptName.substring(0,20));
      BackupArchive.erase(imported);
      if(imported.exists())throw new IOException("迁移资源恢复尚未完成");
      restorePreferences(context.getSharedPreferences("iko_input",0),decodePreferences(data.getJSONObject("preferences")));
    }
    if(!journal.delete())throw new IOException("无法完成迁移恢复");
  }
  private static JSONObject encodePreferences(Map<String,?> values)throws JSONException {
    JSONObject out=new JSONObject();
    for(Map.Entry<String,?>e:values.entrySet()){
      Object v=e.getValue();String type=v instanceof Boolean?"boolean":v instanceof Integer?"int":v instanceof Long?"long":v instanceof Float?"float":v instanceof Set?"set":"string";
      out.put(e.getKey(),new JSONObject().put("type",type).put("value",v instanceof Set?new JSONArray((Set<?>)v):v));
    }
    return out;
  }
  private static Map<String,?> decodePreferences(JSONObject values)throws JSONException {
    Map<String,Object>out=new HashMap<>();
    for(String k:keys(values)){
      JSONObject item=values.getJSONObject(k);Object value=item.get("value");
      switch(item.getString("type")){
        case "boolean":if(!(value instanceof Boolean))throw new JSONException("笔记偏好类型无效");break;
        case "string":if(!(value instanceof String))throw new JSONException("笔记偏好类型无效");break;
        case "int":if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue())throw new JSONException("笔记偏好类型无效");value=((Number)value).intValue();break;
        case "long":if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).longValue())throw new JSONException("笔记偏好类型无效");value=((Number)value).longValue();break;
        case "float":if(!(value instanceof Number)||!Float.isFinite(((Number)value).floatValue()))throw new JSONException("笔记偏好类型无效");value=((Number)value).floatValue();break;
        case "set":if(!(value instanceof JSONArray))throw new JSONException("笔记偏好类型无效");JSONArray a=(JSONArray)value;Set<String>s=new HashSet<>();for(int i=0;i<a.length();i++){if(!(a.get(i) instanceof String))throw new JSONException("笔记偏好类型无效");s.add(a.getString(i));}value=s;break;
        default:throw new JSONException("不支持的笔记偏好类型");
      }
      out.put(k,value);
    }
    return out;
  }
  private static void writeAtomic(File file,JSONObject data)throws IOException {
    if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new IOException("无法保存迁移恢复记录");
    AtomicFile atomic=new AtomicFile(file);FileOutputStream out=atomic.startWrite();
    try{out.write(data.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}
    catch(IOException error){atomic.failWrite(out);throw error;}
  }
  private static void restorePreferences(android.content.SharedPreferences prefs,Map<String,?> previous){
    android.content.SharedPreferences.Editor edit=prefs.edit().clear();
    for(Map.Entry<String,?>e:previous.entrySet()){Object v=e.getValue();String k=e.getKey();if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);else if(v instanceof Integer)edit.putInt(k,(Integer)v);else if(v instanceof Long)edit.putLong(k,(Long)v);else if(v instanceof Float)edit.putFloat(k,(Float)v);else if(v instanceof Set)edit.putStringSet(k,(Set<String>)v);else if(v instanceof String)edit.putString(k,(String)v);}
    if(!edit.commit())throw new IllegalStateException("原笔记设置恢复未确认");
  }
  private static List<String> keys(JSONObject object){ArrayList<String> result=new ArrayList<>();object.keys().forEachRemaining(result::add);return result;}
  private static void relocate(Object json,Map<String,String>paths)throws JSONException,IOException{
    PlainNotesLibrary.relocate(json,paths,null);
  }
  private static void safeTarget(File base,File target)throws IOException {
    File root=base.getCanonicalFile();File relative=new File(root,base.toPath().toAbsolutePath().relativize(target.toPath().toAbsolutePath()).toString());
    if(!relative.getCanonicalPath().startsWith(root.getPath()+File.separator)||!relative.getAbsoluteFile().equals(relative.getCanonicalFile()))throw new IOException("笔记目标包含链接或越界路径");
  }
  private static String read(File file,int max)throws IOException{return new String(bytes(file,max),StandardCharsets.UTF_8);}
  private static byte[] bytes(File file,int max)throws IOException{if(file.length()>max)throw new IOException("笔记文件过大");try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>max)throw new IOException("笔记文件过大");out.write(buffer,0,n);}return out.toByteArray();}}
  private static void copy(File from,File to)throws IOException{try(InputStream in=new FileInputStream(from);OutputStream out=new FileOutputStream(to)){PlainNotesLibraryArchive.copy(in,out,null,PlainNotesLibraryArchive.MAX_FILE_BYTES);}}
  private static boolean sameBytes(File a,File b)throws IOException{
    if(a.length()!=b.length())return false;
    try(InputStream left=new FileInputStream(a);InputStream right=new FileInputStream(b)){
      byte[] x=new byte[65536],y=new byte[65536];int count;
      while((count=left.read(x))!=-1){int read=0;while(read<count){int n=right.read(y,read,count-read);if(n<0)return false;read+=n;}
        for(int i=0;i<count;i++)if(x[i]!=y[i])return false;
      }return right.read()==-1;
    }
  }
  /** Relocation changes paths between batches; compare owned attachment contents as well as JSON. */
  private static boolean sameTemplate(File a,File b,Map<String,File> refs,File app,File stage,int depth,Set<String> visited)throws Exception {
    if(sameBytes(a,b))return true;
    if(depth>12||!a.getName().endsWith(".json")||!b.getName().endsWith(".json")||!visited.add(a.getCanonicalPath()+"\n"+b.getCanonicalPath()))return false;
    return sameTemplateValue(PlainNotesLibraryArchive.parseValue(bytes(a,PlainNotesLibraryArchive.MAX_JSON_BYTES)),PlainNotesLibraryArchive.parseValue(bytes(b,PlainNotesLibraryArchive.MAX_JSON_BYTES)),null,refs,app,stage,depth+1,visited);
  }
  private static boolean sameTemplateValue(Object a,Object b,String field,Map<String,File> refs,File app,File stage,int depth,Set<String> visited)throws Exception {
    if(depth>32)return false;
    if(a instanceof JSONObject&&b instanceof JSONObject){JSONObject x=(JSONObject)a,y=(JSONObject)b;
      if(!new HashSet<>(keys(x)).equals(new HashSet<>(keys(y))))return false;
      for(String key:keys(x))if(!sameTemplateValue(x.get(key),y.get(key),key,refs,app,stage,depth+1,visited))return false;return true;
    }
    if(a instanceof JSONArray&&b instanceof JSONArray){JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;
      for(int i=0;i<x.length();i++)if(!sameTemplateValue(x.get(i),y.get(i),null,refs,app,stage,depth+1,visited))return false;return true;
    }
    if(Objects.equals(a,b))return true;
    if(a instanceof String&&b instanceof String&&field!=null&&NoteFiles.pathField(field)){
      File x=refs.get(a),y=new File((String)b);if(x==null)x=new File((String)a);
      x=x.getCanonicalFile();y=y.getCanonicalFile();String appRoot=app.getCanonicalPath()+File.separator,stageRoot=stage.getCanonicalPath()+File.separator;
      if(!x.isFile()||!y.isFile()||!(x.getPath().startsWith(appRoot)||x.getPath().startsWith(stageRoot))||!y.getPath().startsWith(appRoot))return false;
      return sameTemplate(x,y,refs,app,stage,depth,visited);
    }
    return false;
  }
  private static String hex(byte[] value){StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
