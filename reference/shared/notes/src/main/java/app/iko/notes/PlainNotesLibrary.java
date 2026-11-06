package app.iko.notes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Library-only payload preparation; never reads host preferences, credentials or public files. */
final class PlainNotesLibrary {
  static final String LOGICAL="iko-notes-file:";
  static final String[] ROOTS={"notes","media","templates","imports","onenote-local","import-reports","notes_imports"};
  private PlainNotesLibrary() {}

  static Map<String,Object> prepareExport(File source,File payload,JSONObject preferences)throws Exception {
    return prepareExport(source,payload,preferences,null);
  }
  static Map<String,Object> prepareExport(File source,File payload,JSONObject preferences,Set<String> selected)throws Exception {
    File root=source.getCanonicalFile();TreeMap<String,File> members=new TreeMap<>();
    for(String name:ROOTS){File directory=new File(root,name);if(directory.exists())collect(root,directory,members);}
    members=select(root,members,selected);
    Map<String,String> paths=new HashMap<>();
    for(Map.Entry<String,File> entry:members.entrySet()){
      String member=entry.getKey(),logical=LOGICAL+member;
      paths.put(entry.getValue().getAbsolutePath(),logical);paths.put(entry.getValue().getCanonicalPath(),logical);paths.put(member,logical);
    }
    Map<File,String> snapshots=new LinkedHashMap<>();long bytes=0;int notes=0,pages=0;
    for(Map.Entry<String,File> entry:members.entrySet()){
      PlainNotesLibraryArchive.cancelled();File input=entry.getValue();String member=entry.getKey();
      String before=sha(input);snapshots.put(input,before);bytes+=input.length();
      if(bytes>PlainNotesLibraryArchive.MAX_BYTES||members.size()>PlainNotesLibraryArchive.MAX_FILES)throw new IOException("笔记库过大");
      File output=new File(payload,member);if(!output.getParentFile().isDirectory()&&!output.getParentFile().mkdirs())throw new IOException("无法准备导出目录");
      if(member.startsWith("files/notes/")){
        if(!member.matches("files/notes/[A-Za-z0-9_-]{1,100}\\.json"))throw new IOException("笔记仍有待保存文件，请完成保存后重试");
        byte[] raw=DocumentStore.decodeStorage(read(input,DocumentStore.MAX_FILE_BYTES));JSONObject document=PlainNotesLibraryArchive.parseObject(raw);
        NoteDocument checked=NoteDocument.fromJson(document);String id=member.substring("files/notes/".length(),member.length()-5);
        if(!id.equals(checked.id))throw new IOException("笔记ID与文件名不一致");notes++;pages+=checked.pages.size();
        relocate(document,paths,root);write(output,DocumentStore.encodeStorage(document.toString().getBytes(StandardCharsets.UTF_8)));
      }else if(member.endsWith(".json")){
        Object json=PlainNotesLibraryArchive.parseValue(read(input,PlainNotesLibraryArchive.MAX_JSON_BYTES));relocate(json,paths,root);write(output,json.toString().getBytes(StandardCharsets.UTF_8));
      }else try(InputStream in=new FileInputStream(input);OutputStream out=new FileOutputStream(output)){PlainNotesLibraryArchive.copy(in,out,null,PlainNotesLibraryArchive.MAX_FILE_BYTES);}
      if(!before.equals(sha(input)))throw new IOException("资料正在修改，请保存完成后重试");
    }
    TreeMap<String,File> after=new TreeMap<>();for(String name:ROOTS){File dir=new File(root,name);if(dir.exists())collect(root,dir,after);}after=select(root,after,selected);
    if(!after.keySet().equals(members.keySet()))throw new IOException("笔记库正在修改，请保存完成后重试");
    for(Map.Entry<File,String> snapshot:snapshots.entrySet())if(!snapshot.getValue().equals(sha(snapshot.getKey())))throw new IOException("笔记库正在修改，请保存完成后重试");
    payload.mkdirs();write(new File(payload,"identity.json"),new JSONObject().put("package","app.iko.notes").put("scope","notes-library").put("format","ikonoteslib").put("version",1).put("paths","logical").toString().getBytes(StandardCharsets.UTF_8));
    write(new File(payload,"notes-preferences.json"),preferences.toString().getBytes(StandardCharsets.UTF_8));
    Map<String,Object> result=new LinkedHashMap<>();result.put("notes",notes);result.put("pages",pages);result.put("files",members.size()+2);result.put("bytes",bytes);result.put("format","ikonoteslib");result.put("version",1);return result;
  }
  private static TreeMap<String,File> select(File root,TreeMap<String,File> all,Set<String> selected)throws Exception {
    if(selected==null)return all;
    if(selected.isEmpty())throw new IOException("请至少选择一本笔记");
    TreeMap<String,File> result=new TreeMap<>();
    for(String id:selected){
      if(!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("笔记 ID 无效");
      String member="files/notes/"+id+".json";File file=all.get(member);
      if(file==null)throw new IOException("选中的笔记已变更，请重新选择");
      result.put(member,file);
      JSONObject note=PlainNotesLibraryArchive.parseObject(DocumentStore.decodeStorage(read(file,DocumentStore.MAX_FILE_BYTES)));
      NoteDocument.fromJson(note);result.putAll(NoteFiles.dependencies(root,note));
    }
    for(Map.Entry<String,File> entry:all.entrySet())if(entry.getKey().startsWith("files/templates/"))result.put(entry.getKey(),entry.getValue());
    // Imported metadata may itself reference media. Follow only the selected dependency closure.
    Set<String> read=new HashSet<>();boolean changed;
    do {changed=false;for(String member:new ArrayList<>(result.keySet())){
      if(!member.startsWith("files/notes/")&&member.endsWith(".json")&&read.add(member)){
        Object json=PlainNotesLibraryArchive.parseValue(PlainNotesLibrary.read(result.get(member),PlainNotesLibraryArchive.MAX_JSON_BYTES));
        int before=result.size();result.putAll(NoteFiles.dependencies(root,json));changed|=result.size()!=before;
      }
    }}while(changed);
    return result;
  }
  private static void collect(File root,File file,Map<String,File> members)throws IOException {
    PlainNotesLibraryArchive.cancelled();if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IOException("不能导出链接资料");
    if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("无法读取笔记目录");for(File child:children)collect(root,child,members);}
    else if(file.isFile()){String name="files/"+root.toURI().relativize(file.toURI()).getPath();PlainNotesLibraryArchive.checkName(name);if(file.length()>PlainNotesLibraryArchive.limit(name))throw new IOException("笔记文件过大");members.put(name,file);}
    else throw new IOException("不支持的笔记资料类型");
  }
  static void relocate(Object json,Map<String,String> paths,File sourceRoot)throws IOException,JSONException {
    if(json instanceof JSONObject){JSONObject object=(JSONObject)json;ArrayList<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);for(String key:keys){Object value=object.get(key);if(value instanceof String){if(NoteFiles.pathField(key))object.put(key,path((String)value,paths,sourceRoot));}else relocate(value,paths,sourceRoot);}}
    else if(json instanceof JSONArray){JSONArray array=(JSONArray)json;for(int i=0;i<array.length();i++){Object value=array.get(i);if(!(value instanceof String))relocate(value,paths,sourceRoot);}}
  }
  private static String path(String value,Map<String,String> paths,File sourceRoot)throws IOException {
    if(paths.containsKey(value))return paths.get(value);
    // Canonical roots can differ in case on Windows, while Android roots remain case-sensitive.
    if(sourceRoot!=null && new File(value).isAbsolute()) {
      String canonical=new File(value).getCanonicalPath();
      if(paths.containsKey(canonical))return paths.get(canonical);
      value=canonical;
    }
    String member=null;
    if(value.startsWith(LOGICAL))member=value.substring(LOGICAL.length());
    else if(sourceRoot!=null&&value.startsWith(sourceRoot.getAbsolutePath()+File.separator))member="files/"+value.substring(sourceRoot.getAbsolutePath().length()+1).replace(File.separatorChar,'/');
    else if(value.matches("^/data/(user/[0-9]+|data)/[^/]+/files/.*"))member="files/"+value.substring(value.indexOf("/files/")+7);
    if(member==null)return value;
    if(!paths.containsKey(member))throw new IOException("引用的笔记资料未包含在库中："+new File(member).getName());return paths.get(member);
  }
  static boolean preferenceAllowed(String key){
    return Arrays.asList("fingerDrawing","penInputPolicy","eraserWidth","eraserMode","stylusProfile","sidePrimary","sideSecondary","lastWritingPen","lastGeometry","recentInkColors","continuousPages","focusAutoAdvance","oneNoteInfinite","toolbarStyle").contains(key)
        ||key.startsWith("penPreset:")||key.startsWith("penStyle:")||key.startsWith("penProfile:")||key.startsWith("mapping:")||key.startsWith("opened:");
  }
  static byte[] read(File file,int max)throws IOException {try(InputStream in=new FileInputStream(file)){return PlainNotesLibraryArchive.readBytes(in,max);}}
  static void write(File file,byte[] bytes)throws IOException {try(OutputStream out=new FileOutputStream(file)){out.write(bytes);}}
  private static String sha(File file)throws IOException {java.security.MessageDigest digest=PlainNotesLibraryArchive.digest();try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){PlainNotesLibraryArchive.cancelled();digest.update(b,0,n);}}return PlainNotesLibraryArchive.hex(digest.digest());}
}
