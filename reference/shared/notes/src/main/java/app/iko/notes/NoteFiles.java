package app.iko.notes;

import java.io.*;
import java.util.*;
import org.json.*;

/** Resolves only existing files inside the app's notebook roots, never host or external files. */
final class NoteFiles {
  private NoteFiles() {}
  static boolean pathField(String key){return Arrays.asList("coverImagePath","backgroundPath","path").contains(key);}
  static Map<String,File> dependencies(File files, Object json) throws IOException, JSONException {
    File root=files.getCanonicalFile();Map<String,File> found=new LinkedHashMap<>();collect(root,json,found);
    Set<String> read=new HashSet<>();boolean changed;
    do {changed=false;for(String member:new ArrayList<>(found.keySet()))if(member.endsWith(".json")&&read.add(member)){
      int count=found.size();collect(root,PlainNotesLibraryArchive.parseValue(PlainNotesLibrary.read(found.get(member),PlainNotesLibraryArchive.MAX_JSON_BYTES)),found);changed|=found.size()!=count;
    }}while(changed);
    return found;
  }
  private static void collect(File root,Object value,Map<String,File> found)throws IOException,JSONException {
    if(value instanceof JSONObject){JSONObject o=(JSONObject)value;Iterator<String> keys=o.keys();while(keys.hasNext()){
      String key=keys.next();Object child=o.get(key);
      if(child instanceof String){if(pathField(key))collect(root,child,found);}else collect(root,child,found);
    }}
    else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)if(!(a.get(i) instanceof String))collect(root,a.get(i),found);}
    else if(value instanceof String){
      String path=(String)value;
      if(path.startsWith(PlainNotesLibrary.LOGICAL+"files/"))path=new File(root,path.substring((PlainNotesLibrary.LOGICAL+"files/").length())).getPath();
      File file=new File(path);if(!file.isAbsolute()||!file.exists())return;
      File resolved=file.getCanonicalFile();if(!resolved.getPath().startsWith(root.getPath()+File.separator))return;
      String relative=root.toURI().relativize(resolved.toURI()).getPath();
      String first=relative.split("/",2)[0];
      if(!Arrays.asList(PlainNotesLibrary.ROOTS).contains(first)||first.equals("notes"))return;
      // Android may expose the same private root via /data/user/0 and /data/data.
      // Canonical ancestry above is authoritative; traversal below it is checked in add().
      if(resolved.isFile())add(root,resolved,found);
    }
  }
  private static void add(File root,File file,Map<String,File> found)throws IOException {
    if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IOException("资料路径包含链接");
    if(file.isFile())found.put("files/"+root.toURI().relativize(file.toURI()).getPath(),file);
  }
}
