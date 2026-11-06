package app.iko.notes;

import java.io.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Standard ZIP transport. It stages and verifies every member before library installation. */
public final class PlainNotesLibraryArchive {
  static final long MAX_BYTES=8L*1024*1024*1024, MAX_FILE_BYTES=1024L*1024*1024;
  static final int MAX_FILES=50000, MAX_JSON_BYTES=128*1024*1024;
  private PlainNotesLibraryArchive() {}

  public static boolean isLegacy(File file)throws IOException {
    byte[] magic={'I','K','O','B','A','C','K','U','P',2};
    try(InputStream in=new FileInputStream(file)){for(byte b:magic)if(in.read()!=(b&255))return false;return true;}
  }

  public static Map<String,String> write(File payload,File output)throws IOException {
    File root=payload.getCanonicalFile();
    if(output.getCanonicalPath().startsWith(root.getPath()+File.separator))throw new IOException("导出目标不能位于笔记库内");
    Map<String,String> hashes=new TreeMap<>();JSONArray manifest=new JSONArray();long[] total={0};
    boolean success=false;
    try(ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
      out.setLevel(Deflater.BEST_SPEED);
      append(root,root,out,hashes,manifest,total);
      validateIdentity(new File(root,"identity.json"));
      out.putNextEntry(new ZipEntry("manifest.json"));
      out.write(new JSONObject().put("format","ikonoteslib").put("version",1).put("files",manifest).toString().getBytes(StandardCharsets.UTF_8));out.closeEntry();out.finish();success=true;
    }catch(JSONException error){throw new IOException("笔记库清单无效",error);}
    finally{if(!success)output.delete();}
    return hashes;
  }
  private static void append(File root,File item,ZipOutputStream out,Map<String,String> hashes,JSONArray manifest,long[] total)throws IOException,JSONException {
    cancelled();if(!item.getAbsoluteFile().equals(item.getCanonicalFile()))throw new IOException("不能导出链接文件");
    if(item.isDirectory()){
      File[] children=item.listFiles();if(children==null)throw new IOException("无法读取笔记资料目录");Arrays.sort(children);
      for(File child:children)append(root,child,out,hashes,manifest,total);
      return;
    }
    if(!item.isFile())throw new IOException("不支持的笔记文件");
    String name=root.toURI().relativize(item.toURI()).getPath();checkName(name);
    long size=item.length();if(hashes.size()>=MAX_FILES||size>limit(name)||(total[0]+=size)>MAX_BYTES)throw new IOException("笔记库超过支持的大小");
    MessageDigest digest=digest();out.putNextEntry(new ZipEntry(name));
    long actual;try(InputStream in=new FileInputStream(item)){actual=copy(in,out,digest,size);}
    out.closeEntry();if(actual!=size)throw new IOException("笔记资料正在修改，请保存完成后重试");
    String sha=hex(digest.digest());hashes.put(name,sha);manifest.put(new JSONObject().put("path",name).put("bytes",size).put("sha256",sha));
  }

  public static Map<String,String> read(File input,File destination)throws IOException {
    if(destination.exists())throw new IOException("迁移暂存目录已存在");
    if(!destination.mkdirs())throw new IOException("无法创建迁移暂存目录");
    boolean success=false;Map<String,String> hashes=new TreeMap<>();Map<String,Long> lengths=new TreeMap<>();
    try(ZipFile zip=new ZipFile(input)) {
      JSONObject manifest=null;long total=0;Set<String> seen=new HashSet<>();
      Enumeration<? extends ZipEntry> entries=zip.entries();Set<File> destinations=new HashSet<>();
      while(entries.hasMoreElements()) {
        cancelled();ZipEntry entry=entries.nextElement();String name=entry.getName();
        if(!seen.add(name)||seen.size()>MAX_FILES+1||entry.isDirectory())throw new IOException("笔记库包含重复或过多文件");
        if("manifest.json".equals(name)){
          try(InputStream in=zip.getInputStream(entry)){manifest=parseObject(readBytes(in,16*1024*1024));}continue;
        }
        checkName(name);long size=entry.getSize(),compressed=entry.getCompressedSize();
        if(size<0||size>limit(name)||(total+=size)>MAX_BYTES||compressed<0||(size>1024*1024&&size/Math.max(1,compressed)>1000))throw new IOException("笔记库文件大小或压缩比无效");
        File target=new File(destination,name);if(!target.getCanonicalPath().startsWith(destination.getCanonicalPath()+File.separator)||!destinations.add(target.getCanonicalFile()))throw new IOException("笔记库路径无效或重复");
        if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("无法创建迁移目录");
        MessageDigest digest=digest();long actual;
        try(InputStream in=zip.getInputStream(entry);OutputStream out=new FileOutputStream(target)){actual=copy(in,out,digest,size);}
        if(actual!=size)throw new IOException("笔记库文件长度不符");hashes.put(name,hex(digest.digest()));lengths.put(name,size);
      }
      if(manifest==null||!"ikonoteslib".equals(manifest.getString("format"))||manifest.getInt("version")!=1)throw new IOException("笔记库清单缺失或版本不支持");
      JSONArray expected=manifest.getJSONArray("files");Map<String,String> expectedHashes=new TreeMap<>();
      if(expected.length()!=hashes.size())throw new IOException("笔记库文件数量不符");
      for(int i=0;i<expected.length();i++){
        JSONObject member=expected.getJSONObject(i);String path=member.getString("path"),sha=member.getString("sha256");checkName(path);
        Object bytes=member.get("bytes");
        if(!(bytes instanceof Number)||((Number)bytes).doubleValue()!=((Number)bytes).longValue()||!lengths.containsKey(path)||lengths.get(path)!=((Number)bytes).longValue()||!sha.matches("[0-9a-f]{64}")||expectedHashes.put(path,sha)!=null)throw new IOException("笔记库清单成员无效");
      }
      if(!hashes.equals(expectedHashes))throw new IOException("笔记库文件校验失败");validateIdentity(new File(destination,"identity.json"));
      success=true;return hashes;
    }catch(JSONException error){throw new IOException("笔记库JSON无效",error);}
    finally{if(!success)erase(destination);}
  }
  static void checkName(String name)throws IOException {
    if(name.length()>4096||name.startsWith("/")||name.contains("\\")||name.contains(":")||name.contains("\u0000"))throw new IOException("笔记库路径无效");
    for(String part:name.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("笔记库路径无效");
    if(name.equals("identity.json")||name.equals("notes-preferences.json"))return;
    if(!name.startsWith("files/"))throw new IOException("笔记库包含非笔记资料");
    String relative=name.substring(6);
    for(String root:new String[]{"notes/","media/","templates/","imports/","onenote-local/","import-reports/","notes_imports/"})if(relative.startsWith(root))return;
    throw new IOException("笔记库包含不支持的目录");
  }
  static long limit(String name){return name.equals("identity.json")||name.equals("notes-preferences.json")?4*1024*1024:name.startsWith("files/notes/")?32*1024*1024:MAX_FILE_BYTES;}
  static JSONObject json(File file,int max)throws IOException {try(InputStream in=new FileInputStream(file)){return parseObject(readBytes(in,max));}}
  static JSONObject parseObject(byte[] bytes)throws IOException {
    Object result=parseValue(bytes);if(!(result instanceof JSONObject))throw new IOException("笔记JSON结构无效");return (JSONObject)result;
  }
  static Object parseValue(byte[] bytes)throws IOException {
    try {
      String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
      new JsonSyntax(value).check();
      JSONTokener tokener=new JSONTokener(value);Object result=tokener.nextValue();if(!(result instanceof JSONObject)&&!(result instanceof JSONArray)||tokener.nextClean()!=0)throw new IOException("笔记JSON结构无效");return result;
    }catch(JSONException|CharacterCodingException error){throw new IOException("笔记JSON无效",error);}
  }
  /** Android's JSONTokener accepts comments/single quotes; validate standard JSON before using it. */
  private static final class JsonSyntax {
    final String text;int at;
    JsonSyntax(String text){this.text=text;}
    void check()throws IOException,JSONException{value(0);space();if(at!=text.length())bad();}
    void space(){while(at<text.length()&&" \r\n\t".indexOf(text.charAt(at))>=0)at++;}
    boolean take(char c){space();if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
    void value(int depth)throws IOException,JSONException {
      if(depth>128)throw new IOException("笔记JSON嵌套过深");space();if(at>=text.length())bad();char c=text.charAt(at);
      if(c=='{'){
        at++;Set<String> keys=new HashSet<>();if(take('}'))return;
        do{space();int start=at;string();String key=(String)new JSONTokener(text.substring(start,at)).nextValue();if(!keys.add(key)||!take(':'))bad();value(depth+1);if(take('}'))return;}while(take(','));bad();
      }else if(c=='['){at++;if(take(']'))return;do{value(depth+1);if(take(']'))return;}while(take(','));bad();}
      else if(c=='"')string();
      else if(c=='t')literal("true");else if(c=='f')literal("false");else if(c=='n')literal("null");
      else {
        int start=at;while(at<text.length()&&"0123456789+-.eE".indexOf(text.charAt(at))>=0)at++;
        if(start==at||!text.substring(start,at).matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))bad();
      }
    }
    void literal(String word)throws IOException {if(!text.startsWith(word,at))bad();at+=word.length();}
    void string()throws IOException {
      if(at>=text.length()||text.charAt(at++)!='"')bad();
      while(at<text.length()){
        char c=text.charAt(at++);if(c=='"')return;if(c<32)bad();
        if(c=='\\'){
          if(at>=text.length())bad();char escape=text.charAt(at++);
          if(escape=='u'){for(int i=0;i<4;i++){if(at>=text.length()||Character.digit(text.charAt(at++),16)<0)bad();}}
          else if("\"\\/bfnrt".indexOf(escape)<0)bad();
        }
      }
      bad();
    }
    void bad()throws IOException{throw new IOException("笔记JSON语法或重复字段无效");}
  }
  private static void validateIdentity(File file)throws IOException {
    try{JSONObject identity=json(file,1024*1024);if(!"app.iko.notes".equals(identity.getString("package"))||!"notes-library".equals(identity.getString("scope"))||!"ikonoteslib".equals(identity.getString("format"))||identity.getInt("version")!=1)throw new IOException("请选择完整的IKO笔记库");}
    catch(JSONException error){throw new IOException("笔记库身份无效",error);}
  }
  static byte[] readBytes(InputStream in,int max)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();copy(in,out,null,max);return out.toByteArray();}
  static long copy(InputStream in,OutputStream out,MessageDigest digest,long max)throws IOException {byte[] b=new byte[65536];int n;long size=0;while((n=in.read(b))!=-1){cancelled();if((size+=n)>max)throw new IOException("笔记库文件超过长度限制");out.write(b,0,n);if(digest!=null)digest.update(b,0,n);}return size;}
  static void cancelled()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("笔记传输已取消");}
  static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
  static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
  static void erase(File file){try{if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))return;if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)erase(child);}file.delete();}catch(IOException ignored){}}
}
