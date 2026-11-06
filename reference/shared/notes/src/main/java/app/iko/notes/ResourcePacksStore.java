package app.iko.notes;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.json.*;

/** Private, pinned resource data only. Targets are fixed here, never supplied by an archive. */
public final class ResourcePacksStore {
  public interface Progress { void update(String state, long received, long total, String message); }
  public interface LinkCheck { void check(File file) throws IOException; }
  private final File files, data, management;
  private final Map<String,JSONObject> packs = new LinkedHashMap<>();
  private final Map<String,Long> configuredFontHashes = new HashMap<>();
  private final String catalogHash;
  private final LinkCheck linkCheck;

  public ResourcePacksStore(File files, File data, JSONObject catalog) throws IOException {
    this(files,data,catalog,file -> {});
  }
  public ResourcePacksStore(File files, File data, JSONObject catalog,LinkCheck linkCheck) throws IOException {
    this.linkCheck=linkCheck;
    this.files=files.getCanonicalFile();this.data=data.getCanonicalFile();
    management=new File(this.files,"resource-packs");
    try {
      if(catalog.getInt("schema")!=1)throw new IOException("资源目录版本不支持");
      JSONArray list=catalog.getJSONArray("packs");
      if(list.length()>7)throw new IOException("资源目录过大");
      for(int i=0;i<list.length();i++) {
        JSONObject pack=list.getJSONObject(i);String id=pack.getString("id");
        if(!Arrays.asList("notes","asr-zh","asr-en","asr-auto","focus-mado","focus-haijian","focus-flight").contains(id) || packs.containsKey(id))throw new IOException("未知或重复资源包");
        if(id.startsWith("focus-") && pack.has("legacyDirectory") && !pack.getString("legacyDirectory").matches(id.substring(6)+"-[0-9a-f]{64}"))throw new IOException("场景目录无效");
        if(!pack.getString("version").matches("[A-Za-z0-9._-]{1,60}"))throw new IOException("资源版本不安全");
        long bytes=pack.getLong("downloadBytes");
        if(bytes<=0 || bytes>2L*1024*1024*1024)throw new IOException("资源包大小越界");
        requireHash(pack.getString("archiveSha256"));
        if(!pack.getString("archive").matches("[A-Za-z0-9._-]{1,120}\\.zip"))throw new IOException("资源文件名无效");
        Map<String,JSONObject> entries=entries(pack);long total=0;
        for(JSONObject entry:entries.values()) { long size=entry.getLong("bytes");if(size<0 || size>1024L*1024*1024)throw new IOException("资源文件大小越界");total=Math.addExact(total,size);requireHash(entry.getString("sha256")); }
        if(total!=pack.getLong("installedBytes"))throw new IOException("资源总大小不匹配");
        packs.put(id,pack);
      }
      catalogHash=sha256(new ByteArrayInputStream(catalog.toString().getBytes(StandardCharsets.UTF_8)),1024*1024);
    } catch(JSONException|ArithmeticException e) { throw new IOException("资源目录损坏",e); }
  }

  /** Only the deterministic, app-generated font configuration may differ from upstream bytes. */
  public void allowConfiguredFontHash(String hash,long bytes) throws IOException {requireHash(hash);if(bytes<0 || bytes>64*1024)throw new IOException("字体配置大小无效");configuredFontHashes.put(hash,bytes);}
  private boolean configuredFont(String id,String path){return id.equals("notes") && path.equals("etc/fonts/fonts.conf");}
  public Set<String> ids(){return Collections.unmodifiableSet(packs.keySet());}
  public JSONObject pack(String id) throws IOException {JSONObject result=packs.get(id);if(result==null)throw new IOException("未知资源包");return result;}
  public File directory(String id) throws IOException {
    JSONObject pack=pack(id);
    if(id.startsWith("focus-"))return new File(files,"study-packs/"+pack.optString("legacyDirectory",id+"-"+pack.optString("version")));
    return id.equals("notes")?new File(files,"office-engine/"+pack.optString("version")):new File(data,"app_flutter/sherpa_models/"+id.substring(4));
  }
  public File libraryDirectory(String id) throws IOException { return new File(directory(id),"lib"); }
  public void recoverInterrupted(String id)throws IOException {
    try(Lease ignored=lease(id,false)){recover(id);deleteTree(work(id));}
  }
  private File work(String id) throws IOException {pack(id);return new File(management,"staging/"+id);}
  private File receipt(String id) throws IOException {pack(id);return new File(management,"receipts/"+id+".json");}

  public static final class Lease implements Closeable {
    private final RandomAccessFile file;private final FileLock lock;
    Lease(RandomAccessFile file,FileLock lock){this.file=file;this.lock=lock;}
    public void close() throws IOException { try{lock.release();}finally{file.close();} }
  }
  /** :office keeps a shared lease until process death; mutations require an exclusive lease. */
  public Lease lease(String id,boolean shared) throws IOException {
    pack(id);File lockFile=new File(management,"locks/"+id+".lock");mkdir(lockFile.getParentFile());
    rejectLink(lockFile);RandomAccessFile file=new RandomAccessFile(lockFile,"rw");
    try { FileLock lock=file.getChannel().tryLock(0,Long.MAX_VALUE,shared);
      if(lock==null)throw new IOException("资源正在使用或安装，请稍后重试");return new Lease(file,lock);
    }catch(IOException|OverlappingFileLockException e){file.close();throw new IOException("资源正在使用或安装，请稍后重试",e);}
  }

  /** Receipt is private app metadata; changed size/mtime invalidates it and triggers full SHA. */
  public boolean installed(String id) throws IOException {
    try(Lease ignored=lease(id,true)) {return verify(id,true);}
  }
  public boolean verify(String id,boolean useReceipt) throws IOException {
    File home=directory(id);Map<String,JSONObject> expected=entries(pack(id));
    if(!home.isDirectory())return false;
    JSONObject stamps=new JSONObject();
    try {
      for(Map.Entry<String,JSONObject> entry:expected.entrySet()) {
        File file=child(home,entry.getKey());
        if(!file.isFile() || (file.length()!=entry.getValue().getLong("bytes") && !(configuredFont(id,entry.getKey()) && configuredFontHashes.containsValue(file.length()))))return false;
        stamps.put(entry.getKey(),file.length()+":"+file.lastModified());
      }
      if(useReceipt && receipt(id).isFile()) {
        JSONObject saved=new JSONObject(readText(receipt(id),1024*1024));
        if(catalogHash.equals(saved.optString("catalog")) && stamps.toString().equals(saved.optString("stamps")))return true;
      }
      for(Map.Entry<String,JSONObject> entry:expected.entrySet()) {
        File file=child(home,entry.getKey());String hash;
        try(InputStream in=new FileInputStream(file)){hash=sha256(in,file.length());}
        if(!hash.equals(entry.getValue().getString("sha256")) && !(configuredFont(id,entry.getKey()) && configuredFontHashes.containsKey(hash)))return false;
      }
      File receipt=receipt(id);mkdir(receipt.getParentFile());
      writeAtomic(receipt,new JSONObject().put("catalog",catalogHash).put("stamps",stamps.toString()).toString().getBytes(StandardCharsets.UTF_8));
      return true;
    }catch(JSONException e){throw new IOException("资源校验记录损坏",e);}
  }

  /** Adopt an existing unpack directory and only copy missing native libs when old APK provides them. */
  public void adoptOfficeLibraries(File oldNativeDirectory) throws IOException {
    if(!packs.containsKey("notes") || !directory("notes").isDirectory())return;
    try(Lease ignored=lease("notes",false)) {
      for(Map.Entry<String,JSONObject> entry:entries(pack("notes")).entrySet()) {
        if(!entry.getKey().startsWith("lib/"))continue;
        File destination=child(directory("notes"),entry.getKey());if(destination.isFile())continue;
        File source=new File(oldNativeDirectory,entry.getKey().substring(4));
        if(!source.isFile() || source.length()!=entry.getValue().optLong("bytes"))continue;
        String hash;try(InputStream in=new FileInputStream(source)){hash=sha256(in,source.length());}
        if(!hash.equals(entry.getValue().optString("sha256")))continue;
        mkdir(destination.getParentFile());
        try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(destination)) {copy(in,out,source.length(),null,null,"checking");out.getFD().sync();}
        if(!destination.setWritable(false,false))throw new IOException("无法保护旧版文档引擎库");
      }
    }
  }

  public void install(String id,InputStream source,AtomicBoolean cancelled,Progress progress) throws IOException {
    try(Lease ignored=lease(id,false)) {
      recover(id);File work=work(id);deleteTree(work);mkdir(work);
      File archive=new File(work,"download.part"),stage=new File(work,"expanded");JSONObject pack=pack(id);
      try {
        long expected=pack.getLong("downloadBytes");emit(progress,"downloading",0,expected,"正在接收资源");
        try(FileOutputStream out=new FileOutputStream(archive)) {
          if(copy(source,out,expected,cancelled,progress,"downloading")!=expected)throw new IOException("下载中断或资源大小不匹配");out.getFD().sync();
        }
        checkCancelled(cancelled);emit(progress,"verifying",expected,expected,"校验资源完整性");
        String hash;try(InputStream in=new FileInputStream(archive)){hash=sha256(in,expected);}
        if(!hash.equals(pack.getString("archiveSha256")))throw new IOException("资源包 SHA-256 不匹配");
        Map<String,JSONObject> expectedFiles=entries(pack);validateCentralDirectory(archive,expectedFiles);
        mkdir(stage);Set<String> seen=new HashSet<>();long expanded=0;
        emit(progress,"installing",0,pack.getLong("installedBytes"),"正在安装资源");
        try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))) {
          ZipEntry next;
          while((next=zip.getNextEntry())!=null) {
            checkCancelled(cancelled);String name=next.getName();JSONObject meta=expectedFiles.get(name);
            if(next.isDirectory() || meta==null || !seen.add(name))throw new IOException("资源包含未知或重复路径");
            File target=child(stage,name);mkdir(target.getParentFile());
            try(FileOutputStream out=new FileOutputStream(target)) {
              long count=copy(zip,out,meta.getLong("bytes"),cancelled,null,"installing");
              if(count!=meta.getLong("bytes"))throw new IOException("资源展开大小不匹配");out.getFD().sync();expanded+=count;
            }
            String digest;try(InputStream in=new FileInputStream(target)){digest=sha256(in,target.length());}
            if(!digest.equals(meta.getString("sha256")))throw new IOException("资源文件校验失败："+name);
            if(name.startsWith("lib/") && !target.setWritable(false,false))throw new IOException("无法保护引擎库文件");
            emit(progress,"installing",expanded,pack.getLong("installedBytes"),"正在安装资源");zip.closeEntry();
          }
        }
        if(seen.size()!=expectedFiles.size())throw new IOException("资源包缺少文件");
        checkCancelled(cancelled);File target=directory(id),old=new File(work,"previous");mkdir(target.getParentFile());rejectLink(target);
        if(target.exists() && !target.renameTo(old))throw new IOException("无法保留旧资源版本");
        if(!stage.renameTo(target)) {if(old.exists() && !old.renameTo(target))throw new IOException("资源切换失败，旧资源待恢复");throw new IOException("无法切换新资源版本");}
        receipt(id).delete();
        if(!verify(id,false))throw new IOException("安装后资源校验失败");
        deleteTree(old);emit(progress,"installed",expected,expected,"资源已就绪");
      }catch(JSONException e){throw new IOException("资源目录损坏",e);}
      finally {recover(id);deleteTree(work);}
    }
  }

  /** Never scans user notebooks, media, classroom, credentials or recordings. */
  public void remove(String id) throws IOException {
    try(Lease ignored=lease(id,false)) {recover(id);deleteTree(directory(id));receipt(id).delete();deleteTree(work(id));}
  }
  private void recover(String id) throws IOException {
    File old=new File(work(id),"previous"),target=directory(id);
    if(old.exists() && !target.exists()) {mkdir(target.getParentFile());if(!old.renameTo(target))throw new IOException("旧资源恢复失败");}
  }
  private static void emit(Progress p,String state,long got,long total,String message){if(p!=null)p.update(state,got,total,message);}
  private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedIOException {if(Thread.currentThread().isInterrupted() || (cancelled!=null && cancelled.get()))throw new InterruptedIOException("资源安装已取消");}
  private static long copy(InputStream in,OutputStream out,long max,AtomicBoolean cancel,Progress progress,String state)throws IOException {
    byte[] bytes=new byte[64*1024];long total=0,last=0;int count;
    while((count=in.read(bytes))!=-1){checkCancelled(cancel);if(count==0)continue;if(count>max-total)throw new IOException("资源超过可信大小上限");out.write(bytes,0,count);total+=count;
      long now=System.currentTimeMillis();if(now-last>150){emit(progress,state,total,max,"正在接收资源");last=now;}}
    checkCancelled(cancel);return total;
  }
  public static String sha256(InputStream in,long max)throws IOException {
    try {MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[64*1024];long total=0;int count;
      while((count=in.read(bytes))!=-1){if(count>max-total)throw new IOException("文件超过可信大小");total+=count;digest.update(bytes,0,count);}
      StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
  }
  private static void requireHash(String hash)throws IOException{if(!hash.matches("[0-9a-f]{64}"))throw new IOException("资源校验值无效");}
  private static Map<String,JSONObject> entries(JSONObject pack)throws IOException {
    Map<String,JSONObject> entries=new LinkedHashMap<>();
    try {JSONArray files=pack.getJSONArray("files");if(files.length()==0 || files.length()>4096)throw new IOException("资源文件数量越界");
      for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);String path=file.getString("path");safePath(path);if(entries.put(path,file)!=null)throw new IOException("资源路径重复");}
      return entries;
    }catch(JSONException e){throw new IOException("资源文件目录损坏",e);}
  }
  private static void safePath(String name)throws IOException {
    if(name.length()==0 || name.length()>240 || name.startsWith("/") || name.indexOf('\\')>=0 || name.indexOf(':')>=0 || name.indexOf('\0')>=0)throw new IOException("资源路径不安全");
    for(String part:name.split("/",-1))if(part.isEmpty() || part.equals(".") || part.equals(".."))throw new IOException("资源路径不安全");
  }
  private File child(File root,String path)throws IOException{safePath(path);File file=new File(root,path);rejectLink(file);if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("资源路径越界");return file;}
  private void rejectLink(File file)throws IOException {linkCheck.check(file);if(!file.getAbsolutePath().equals(file.getCanonicalPath()))throw new IOException("资源路径包含符号链接");}
  private void mkdir(File file)throws IOException {rejectLink(file);if(!file.isDirectory() && !file.mkdirs())throw new IOException("无法准备资源目录");}
  private void deleteTree(File file)throws IOException {
    rejectLink(file);if(!file.exists())return;
    if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("无法读取资源目录");for(File child:children)deleteTree(child);}
    // Android permits unlinking read-only files; Windows JVM checks need the write bit restored.
    if(!file.delete()){file.setWritable(true,true);if(!file.delete())throw new IOException("无法删除程序资源");}
  }
  private void writeAtomic(File target,byte[] bytes)throws IOException {
    File temp=new File(target.getPath()+".tmp");rejectLink(temp);try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
    if(target.exists() && !target.delete())throw new IOException("无法更新资源记录");if(!temp.renameTo(target))throw new IOException("无法保存资源记录");
  }
  private String readText(File file,int limit)throws IOException {
    rejectLink(file);ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){copy(in,out,limit,null,null,"");}return new String(out.toByteArray(),StandardCharsets.UTF_8);
  }
  private static int u16(byte[] a,int p){return(a[p]&255)|((a[p+1]&255)<<8);}
  private static long u32(byte[] a,int p){return(u16(a,p)&65535L)|((long)u16(a,p+2)<<16);}
  /** ZipInputStream omits Unix file type. Inspect central metadata before any extraction. */
  private static void validateCentralDirectory(File archive,Map<String,JSONObject> expected)throws IOException {
    try(RandomAccessFile zip=new RandomAccessFile(archive,"r")) {
      int length=(int)Math.min(65557,zip.length());byte[] tail=new byte[length];zip.seek(zip.length()-length);zip.readFully(tail);int eocd=-1;
      for(int i=length-22;i>=0;i--)if(u32(tail,i)==0x06054b50L && i+22+u16(tail,i+20)==length){eocd=i;break;}
      if(eocd<0 || u16(tail,eocd+4)!=0 || u16(tail,eocd+6)!=0 || u16(tail,eocd+8)!=u16(tail,eocd+10))throw new IOException("资源ZIP目录无效");
      int count=u16(tail,eocd+10);long start=u32(tail,eocd+16),size=u32(tail,eocd+12);
      if(count!=expected.size() || start+size!=zip.length()-length+eocd)throw new IOException("资源ZIP文件数量无效");
      zip.seek(start);Set<String> names=new HashSet<>();
      for(int i=0;i<count;i++) {
        byte[] header=new byte[46];zip.readFully(header);if(u32(header,0)!=0x02014b50L)throw new IOException("资源ZIP目录损坏");
        int flags=u16(header,8),method=u16(header,10),nameLength=u16(header,28),extra=u16(header,30),comment=u16(header,32),mode=(int)(u32(header,38)>>>16)&0170000;
        if((flags&1)!=0 || (method!=0 && method!=8) || nameLength>240 || nameLength==0 || (mode!=0 && mode!=0100000))throw new IOException("资源ZIP包含加密、链接或非文件项");
        byte[] nameBytes=new byte[nameLength];zip.readFully(nameBytes);String name=new String(nameBytes,StandardCharsets.UTF_8);safePath(name);
        JSONObject entry=expected.get(name);if(entry==null || !names.add(name) || u32(header,24)!=entry.optLong("bytes"))throw new IOException("资源ZIP包含未知、重复或超长文件");
        zip.seek(zip.getFilePointer()+extra+comment);
      }
      if(zip.getFilePointer()!=start+size)throw new IOException("资源ZIP目录长度不匹配");
    }
  }
}
