package app.iko.notes;

import android.app.Activity;
import android.os.SystemClock;
import java.util.*;
import java.util.function.Consumer;

/** A narrow control bridge; ink input and rendering never cross it. */
public final class NotesHost {
  public interface Client {
    void command(Activity activity, String name, Map<String,Object> args, Consumer<Map<String,Object>> reply);
    void visit(Map<String,Object> visit);
  }
  public static volatile Client client;
  public static volatile Map<String,Object> state = Collections.emptyMap();
  private static final Set<Object> visible = Collections.newSetFromMap(new IdentityHashMap<>());
  public static boolean attached() { return client != null; }
  public static synchronized boolean isVisible() { return !visible.isEmpty(); }
  public static void request(Activity activity,String name,Consumer<Map<String,Object>> reply) {
    request(activity,name,Collections.emptyMap(),reply);
  }
  public static void request(Activity activity,String name,Map<String,Object> arguments,Consumer<Map<String,Object>> reply) {
    Client current=client;
    if(current==null) { reply.accept(Collections.singletonMap("error","笔记宿主已关闭，请返回 IKO"));return; }
    current.command(activity,name,arguments,reply);
  }
  public static void visibility(Activity activity,boolean shown,NoteDocument note,String pageId) {
    visibility(activity, activity, shown, note, pageId);
  }
  public static void visibility(Object workspace, Activity activity, boolean shown, NoteDocument note, String pageId) {
    synchronized (NotesHost.class) { if(shown) visible.add(workspace); else visible.remove(workspace); }
    Client current=client;if(current==null)return;
    Map<String,Object> args=new HashMap<>();
    args.put("workspaceId", Integer.toHexString(System.identityHashCode(workspace)));
    args.put("visible",shown && note!=null);args.put("monotonicMs",SystemClock.elapsedRealtime());
    args.put("wallMs",System.currentTimeMillis());
    if(note!=null){args.put("noteId",note.id);args.put("title",note.title);args.put("pageId",pageId==null?"":pageId);}
    current.visit(args);
  }
  public static boolean recording() { return Boolean.TRUE.equals(state.get("recording")); }
  public static long durationMs() { Object value=state.get("durationMs");return value instanceof Number?((Number)value).longValue():0; }
}
