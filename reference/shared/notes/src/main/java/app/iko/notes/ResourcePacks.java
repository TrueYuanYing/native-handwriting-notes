package app.iko.notes;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Downloadable, APK-pinned resource packs. Call blocking methods on a background thread. */
public final class ResourcePacks {
  public static final String DEFAULT_BASE_URL="https://example.invalid/iko/v1/";
  public interface Listener {void event(Map<String,Object> event);}
  private static ResourcePacks instance;
  private final Context context;
  private final ResourcePacksStore store;
  private final String baseUrl;
  private final ConcurrentMap<String,Task> tasks=new ConcurrentHashMap<>();
  private final ConcurrentMap<String,Map<String,Object>> events=new ConcurrentHashMap<>();
  private final Set<String> recovered=ConcurrentHashMap.newKeySet();
  private final Set<String> adoptionAttempted=ConcurrentHashMap.newKeySet();
  private final Set<String> activeScenes=ConcurrentHashMap.newKeySet();
  private final Map<String,ResourcePacksStore.Lease> sceneLocks=new HashMap<>();
  private final Map<String,Integer> sceneOwners=new HashMap<>();

  public static synchronized ResourcePacks get(Context context)throws IOException {
    if(instance==null)instance=new ResourcePacks(context.getApplicationContext());return instance;
  }
  public ResourcePacks(Context context)throws IOException {
    this.context=context;
    try {
      byte[] bytes;
      try(InputStream in=context.getAssets().open("resource-packs/catalog.json")){bytes=readBounded(in,1024*1024);}
      JSONObject catalog=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
      store=new ResourcePacksStore(context.getFilesDir(),new File(context.getApplicationInfo().dataDir),catalog,ResourcePacks::rejectAndroidLinks);
      // A build property can point a release to another official host; downloaded catalogs are never read.
      baseUrl=BuildConfig.RESOURCE_PACK_BASE_URL.isEmpty()?catalog.optString("baseUrl",DEFAULT_BASE_URL):BuildConfig.RESOURCE_PACK_BASE_URL;
      URL base=new URL(baseUrl);if(!base.getProtocol().equals("https") || !baseUrl.endsWith("/") || base.getUserInfo()!=null || base.getQuery()!=null || base.getRef()!=null)throw new IOException("资源下载地址必须为 HTTPS 目录");
      try(InputStream in=context.getAssets().open("resource-packs/office-fonts.conf")) {
        byte[] template=readBounded(in,64*1024);
        for(File home:new File[]{store.directory("notes"),new File(context.getFilesDir(),"office-engine/"+store.pack("notes").optString("version"))}) {
          byte[] configured=OfficeFonts.configuration(template,home);
          store.allowConfiguredFontHash(ResourcePacksStore.sha256(new ByteArrayInputStream(configured),configured.length),configured.length);
        }
      }
    }catch(JSONException e){throw new IOException("应用资源目录无效",e);}
  }
  private static final class Task {
    final AtomicBoolean cancelled;volatile Call connection;volatile InputStream source;
    Task(AtomicBoolean cancelled){this.cancelled=cancelled;}
    void cancel(){cancelled.set(true);Call c=connection;if(c!=null)c.cancel();InputStream in=source;if(in!=null)try{in.close();}catch(IOException ignored){}}
  }
  public boolean busy(){return !tasks.isEmpty();}
  public List<Map<String,Object>> list()throws IOException {
    List<Map<String,Object>> result=new ArrayList<>();for(String id:store.ids())if(available(id))result.add(status(id));return result;
  }
  private boolean available(String id){return !context.getPackageName().contains("publicbeta") || !id.startsWith("focus-") || id.equals("focus-mado");}
  public Map<String,Object> status(String id)throws IOException {
    if(!available(id))throw new IOException("此场景尚未开放");
    JSONObject pack=store.pack(id);boolean supported=!id.equals("notes") || officeSupported();boolean installed=activeScenes.contains(id);
    if(!tasks.containsKey(id) && !installed) {
      if(!recovered.contains(id))try{store.recoverInterrupted(id);recovered.add(id);}catch(IOException ignored){/* A different process may have a live lease. */}
      if(id.equals("notes") && supported && adoptionAttempted.add(id))try{store.adoptOfficeLibraries(new File(context.getApplicationInfo().nativeLibraryDir));}catch(IOException ignored){/* :office may already own its verified libraries. */}
      try{installed=store.installed(id);}catch(IOException busy){/* Report active pack state without touching its files. */}
    }
    Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);result.put("title",pack.optString("title"));result.put("version",pack.optString("version"));
    result.put("downloadBytes",pack.optLong("downloadBytes"));result.put("installedBytes",pack.optLong("installedBytes"));result.put("installed",installed);
    result.put("supported",supported);result.put("path",store.directory(id).getAbsolutePath());result.put("archive",pack.optString("archive"));result.put("sha256",pack.optString("archiveSha256"));
    result.put("state",installed?"installed":"not_installed");result.put("received",0L);result.put("total",pack.optLong("downloadBytes"));result.put("progress",installed?1.0:0.0);
    if(events.containsKey(id))result.putAll(events.get(id));
    if(installed && !tasks.containsKey(id)){result.put("state","installed");result.put("progress",1.0);}
    if(!supported)result.put("message","文档排版引擎需要 arm64 与 Android 10 或以上；其他功能仍可使用");
    return result;
  }
  public Map<String,Object> download(String id,Listener listener)throws IOException {
    return download(id,new AtomicBoolean(),listener);
  }
  public Map<String,Object> download(String id,AtomicBoolean cancelled,Listener listener)throws IOException {
    Task task=begin(id,cancelled);
    try {
      event(id,"checking",0,store.pack(id).optLong("downloadBytes"),"检查本机资源",listener);
      if(store.installed(id)){event(id,"installed",1,1,"已采用本机原资源",listener);return completedStatus(id,task);}
      URL url=new URL(baseUrl+store.pack(id).optString("archive"));
      Call connection=ResourceHttp.CLIENT.newCall(new Request.Builder().url(url)
          .header("Accept-Encoding","identity").header("User-Agent","IKO/0.3.2 (Android; ResourcePacks)").build());
      task.connection=connection;
      if(task.cancelled.get())throw new InterruptedIOException("下载已取消");
      try(Response response=connection.execute()) {
      int code=response.code();
      if(code!=200)throw new IOException(code==404?"资源尚未发布，可从文件安装可信资源包":"资源下载失败（HTTP "+code+"）");
      ResponseBody body=response.body();if(body==null)throw new IOException("资源服务没有返回文件");
      long expected=store.pack(id).optLong("downloadBytes"),length=body.contentLength();
      if(length>=0 && length!=expected)throw new IOException("下载资源长度与应用目录不匹配");
      try(InputStream in=body.byteStream()){task.source=in;store.install(id,in,task.cancelled,(state,got,total,message)->event(id,state,got,total,message,listener));}
      }
      return completedStatus(id,task);
    }catch(IOException e){failed(id,task,e,listener);throw e;}
    finally{tasks.remove(id,task);}
  }
  public Map<String,Object> installFile(String id,InputStream source,Listener listener)throws IOException {
    return installFile(id,source,new AtomicBoolean(),listener);
  }
  public Map<String,Object> installFile(String id,InputStream source,AtomicBoolean cancelled,Listener listener)throws IOException {
    Task task=begin(id,cancelled);task.source=source;
    try {store.install(id,source,task.cancelled,(state,got,total,message)->event(id,state,got,total,message,listener));return completedStatus(id,task);}
    catch(IOException e){failed(id,task,e,listener);throw e;}
    finally{tasks.remove(id,task);}
  }
  private Map<String,Object> completedStatus(String id,Task task)throws IOException {tasks.remove(id,task);return status(id);}
  private Task begin(String id,AtomicBoolean cancelled)throws IOException {
    if(!available(id))throw new IOException("此场景尚未开放");
    store.pack(id);if(id.equals("notes") && !officeSupported())throw new IOException("此设备不支持 arm64 文档排版引擎");
    if(cancelled.get() || Thread.currentThread().isInterrupted())throw new InterruptedIOException("资源操作已取消");
    Task task=new Task(cancelled);if(tasks.putIfAbsent(id,task)!=null)throw new IOException("该资源包任务尚未完成");return task;
  }
  public void cancel(String id)throws IOException {store.pack(id);Task task=tasks.get(id);if(task!=null)task.cancel();}
  public Map<String,Object> remove(String id,Listener listener)throws IOException {
    if(tasks.containsKey(id))throw new IOException("请先取消该资源包下载并等待结束");
    store.remove(id);event(id,"removed",0,store.pack(id).optLong("downloadBytes"),"程序资源已删除，用户文件保留",listener);return status(id);
  }
  private void failed(String id,Task task,IOException error,Listener listener){event(id,task.cancelled.get()?"cancelled":"error",0,storePackBytes(id),task.cancelled.get()?"下载已取消，可重新开始":error.getMessage(),listener);}
  private long storePackBytes(String id){try{return store.pack(id).optLong("downloadBytes");}catch(IOException e){return 0;}}
  private void event(String id,String state,long received,long total,String message,Listener listener) {
    Map<String,Object> event=new LinkedHashMap<>();event.put("id",id);event.put("state",state);event.put("received",received);event.put("total",total);event.put("progress",total>0?Math.min(1.0,(double)received/total):0.0);event.put("message",message==null?"":message);
    events.put(id,event);if(listener!=null)listener.event(event);
  }
  private static byte[] readBounded(InputStream in,int limit)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int count;while((count=in.read(b))!=-1){if(count>limit-out.size())throw new IOException("应用资源目录过大");out.write(b,0,count);}return out.toByteArray();}
  /** Public API available since Android 21; also rejects dangling links on Android 24/25. */
  private static void rejectAndroidLinks(File file)throws IOException {
    for(File cursor=file.getAbsoluteFile();cursor!=null;cursor=cursor.getParentFile()) {
      try {if(OsConstants.S_ISLNK(Os.lstat(cursor.getPath()).st_mode))throw new IOException("资源路径包含符号链接");}
      catch(ErrnoException e){if(e.errno!=OsConstants.ENOENT)throw new IOException("无法验证资源文件路径",e);}
    }
  }
  public static boolean officeSupported(){return Build.VERSION.SDK_INT>=29 && android.os.Process.is64Bit();}
  public synchronized Closeable acquireScene(String id)throws IOException {
    if(!id.startsWith("focus-") || !available(id))throw new IOException("此场景尚未开放");
    if(sceneLocks.containsKey(id)){sceneOwners.put(id,sceneOwners.get(id)+1);return sceneRelease(id);}
    ResourcePacksStore.Lease lease=store.lease(id,true);
    try{if(!store.verify(id,true))throw new IOException("请先安装场景资源");activeScenes.add(id);sceneLocks.put(id,lease);sceneOwners.put(id,1);return sceneRelease(id);}
    catch(IOException e){lease.close();throw e;}
  }
  private Closeable sceneRelease(String id){AtomicBoolean released=new AtomicBoolean();return ()->{synchronized(ResourcePacks.this){
    if(!released.compareAndSet(false,true))return;
    int count=sceneOwners.get(id)-1;if(count>0){sceneOwners.put(id,count);return;}
    ResourcePacksStore.Lease lease=sceneLocks.remove(id);sceneOwners.remove(id);
    try{lease.close();}finally{activeScenes.remove(id);}
  }};}
  public static final class OfficeLease implements Closeable {
    public final File home,libraries;private final ResourcePacksStore.Lease lease;
    OfficeLease(File home,ResourcePacksStore.Lease lease){this.home=home;this.libraries=new File(home,"lib");this.lease=lease;}
    public void close()throws IOException{lease.close();}
  }
  public static OfficeLease acquireOffice(Context context)throws IOException {
    ResourcePacks resources=get(context);if(!officeSupported())throw new IOException("文档排版需要 arm64 与 Android 10 或以上");
    if(resources.adoptionAttempted.add("notes"))resources.store.adoptOfficeLibraries(new File(context.getApplicationInfo().nativeLibraryDir));
    ResourcePacksStore.Lease lease=resources.store.lease("notes",true);
    try {if(!resources.store.verify("notes",true))throw new IOException("请在设置的存储与资源中安装笔记文档排版引擎");return new OfficeLease(resources.store.directory("notes"),lease);}
    catch(IOException e){lease.close();throw e;}
  }
}
