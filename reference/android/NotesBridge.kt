package com.classmate.classmate

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.iko.notes.NotesHost
import app.iko.notes.NotesLibraryMigration
import app.iko.notes.NoteRecordingInbox
import app.iko.notes.PreparedNotesImport
import app.iko.notes.NotesStorage
import app.iko.notes.NotesTrashJobService
import org.json.JSONObject
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/** Native ink stays in this window; only bounds, commands and visit metadata cross the bridge. */
class NotesBridge(private val activity: Activity, messenger: BinaryMessenger) : NotesHost.Client {
    private val channel = MethodChannel(messenger,"com.iko.study/notes")
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var slot: Int? = null
    private var pendingOpen: Intent? = null
    private var lastStyle: String? = null
    private var incoming: Intent? = null
    private var transferReply: MethodChannel.Result? = null
    private var password = charArrayOf()
    private var exportSelection: Set<String>? = null
    private var previewRequested=false
    private var preparedImport:PreparedNotesImport?=null
    @Volatile private var transferring = false
    private var closed = false
    val workspace = NotesWorkspaceHost(activity) {
        channel.invokeMethod("paneClosed", mapOf("slot" to slot))
    }
    val busy: Boolean get() = transferring || transferReply != null || preparedImport != null || workspace.isBusy()
    private fun configuration(args: Map<*,*>?): Intent = Intent().apply {
        putExtra("compactWorkspace",args?.get("compact") == true)
        putExtra("hostTheme", args?.get("theme") as? String ?: "system")
        putExtra("hostPalette", args?.get("palette") as? String ?: "ocean")
        (args?.get("style") as? Map<*,*>)?.let { putExtra("hostStyle",JSONObject(it).toString()) }
    }
    init {
        NotesTrashJobService.schedule(activity)
        NotesHost.client = this
        channel.setMethodCallHandler { call, result ->
            try {
                when(call.method) {
                    "open" -> {
                        pendingOpen = configuration(call.arguments as? Map<*,*>).apply {
                            putExtra("noteId",call.argument<String>("noteId"))
                            putExtra("pageId",call.argument<String>("pageId"))
                            putExtra("fitRequestedPage",call.argument<String>("noteId") != null)
                        }
                        result.success(null)
                    }
                    "show" -> {
                        val visible = call.argument<Boolean>("visible") == true
                        val requested = call.argument<Int>("slot") ?: 0
                        if (!visible && slot != requested) { result.success(null); return@setMethodCallHandler }
                        if (visible) {
                            if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("笔记需要 Android 10 或以上")
                            if (transferring || transferReply != null) throw IllegalStateException("笔记库正在导入或导出，请稍候")
                            val intent = configuration(call.arguments as? Map<*,*>)
                            val style = intent.extras.toString()
                            val mustOpen = !workspace.isCreated || pendingOpen != null || incoming != null || style != lastStyle
                            pendingOpen?.let { intent.putExtras(it) }
                            incoming?.let {
                                // Setting type separately clears Intent.data on Android.
                                intent.setDataAndType(it.data,it.type)
                                intent.clipData=it.clipData
                                intent.action=it.action
                            }
                            if(mustOpen) {
                                workspace.setBounds(call.argument<Int>("left") ?: 0,call.argument<Int>("top") ?: 0,
                                    call.argument<Int>("width") ?: 0,call.argument<Int>("height") ?: 0,false)
                                workspace.open(intent); lastStyle = style
                                pendingOpen = null; incoming = null
                            }
                            slot = requested
                        }
                        workspace.setBounds(call.argument<Int>("left") ?: 0,call.argument<Int>("top") ?: 0,
                            call.argument<Int>("width") ?: 0,call.argument<Int>("height") ?: 0,visible)
                        result.success(null)
                    }
                    "hideWorkspace" -> { workspace.setVisible(false); result.success(null) }
                    "hide" -> { if (slot == call.argument<Int>("slot")) workspace.setVisible(false); result.success(null) }
                    "releaseWorkspace" -> {
                        if (busy) throw IllegalStateException("笔记正在处理文件，请稍后重试")
                        workspace.setVisible(false)
                        workspace.flush { error ->
                            if (error != null) result.error("busy",error,null)
                            else { workspace.disposeWorkspace(); result.success(null); channel.invokeMethod("libraryChanged",null) }
                        }
                    }
                    "checkIncomingFile" -> result.success(incoming != null)
                    "state" -> {
                        @Suppress("UNCHECKED_CAST") val state = call.arguments as? Map<String,Any?> ?: emptyMap()
                        NotesHost.state = state; result.success(null)
                    }
                    "cancelImport" -> {
                        val prepared=preparedImport;preparedImport=null
                        io.execute { prepared?.close();main.post { if(!closed)result.success(null) } }
                    }
                    "finishImport" -> {
                        if(transferring || transferReply != null || workspace.isBusy() || NotesHost.state["running"]==true || NotesHost.recording())throw IllegalStateException("请结束课堂和笔记文件任务后导入")
                        val prepared=preparedImport ?: throw IllegalStateException("请选择笔记库文件")
                        if(call.argument<String>("token")!=prepared.token)throw IllegalStateException("导入预览已过期")
                        val ids=call.argument<List<String>>("noteIds")?.toSet() ?: emptySet()
                        transferring=true;workspace.setVisible(false)
                        workspace.flush { error ->
                            if(error!=null){transferring=false;result.error("busy",error,null)}
                            else { workspace.disposeWorkspace();io.execute {
                                try{val count=prepared.install(ids);main.post{if(!closed){result.success(mapOf("notes" to count));channel.invokeMethod("libraryChanged",null)}}}
                                catch(error:Exception){main.post{if(!closed)result.error("transfer",error.message,null)}}
                                finally{prepared.close();preparedImport=null;transferring=false}
                            } }
                        }
                    }
                    "previewLibrary", "importLibrary", "exportLibrary" -> {
                        if (busy || NotesHost.state["running"] == true || NotesHost.recording()) throw IllegalStateException("请结束课堂和笔记文件任务后再导入或导出")
                        workspace.setVisible(false)
                        transferReply = result
                        password = (call.argument<String>("password") ?: "").toCharArray()
                        exportSelection = call.argument<List<String>>("noteIds")?.toSet()
                        previewRequested=call.method=="previewLibrary"
                        workspace.flush { error ->
                            if (error != null) { transferReply = null; password.fill('\u0000'); result.error("busy",error,null) }
                            else try {
                                if(NoteRecordingInbox.hasPending(activity))workspace.disposeWorkspace()
                                val exporting = call.method == "exportLibrary"
                                activity.startActivityForResult(Intent(if (exporting) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE); type = if(exporting) "application/zip" else "*/*"
                                    if(exporting) putExtra(Intent.EXTRA_TITLE,"IKO-Notes-${java.time.LocalDate.now()}.ikonoteslib")
                                }, if(exporting) 8234 else 8231)
                            } catch (failure: Exception) { transferReply = null; password.fill('\u0000'); result.error("transfer","无法打开文件选择器",null) }
                        }
                    }
                    "libraryFiles", "manageNotes" -> {
                        if (busy) throw IllegalStateException("笔记文件任务尚未结束")
                        if (call.method == "manageNotes" && (NotesHost.state["running"] == true || NotesHost.recording()))
                            throw IllegalStateException("请结束课堂录音后再整理笔记")
                        workspace.setVisible(false)
                        transferring = true
                        val action = call.argument<String>("action") ?: ""
                        val ids = call.argument<List<String>>("noteIds") ?: emptyList()
                        workspace.flush { error ->
                            if(error != null) { transferring=false; result.error("busy",error,null) }
                            else {
                                // Retired editors must not later write an old copy over a trash/restore change.
                                if(call.method == "manageNotes" || NoteRecordingInbox.hasPending(activity)) workspace.disposeWorkspace()
                                io.execute {
                                    try {
                                        NoteRecordingInbox.attachSaved(activity)
                                        if(call.method == "manageNotes") NotesStorage.change(activity,ids,action)
                                        val rows=NotesStorage.list(activity)
                                        main.post { if(!closed) { result.success(rows); channel.invokeMethod("libraryChanged",null) } }
                                    } catch(failure:Exception){ main.post { if(!closed) result.error("notes_storage",failure.message,null) } }
                                    finally { transferring=false }
                                }
                            }
                        }
                    }
                    else -> result.notImplemented()
                }
            } catch (error: Exception) { result.error("notes",error.message ?: "笔记操作未完成",null) }
        }
    }
    fun acceptIncoming(intent:Intent?) {
        val uri=sharedFileUri(intent)
        if(uri?.scheme != "content" || intent==null) return
        incoming = normalizedFileIntent(intent,uri)
        main.post { if(!closed) channel.invokeMethod("incomingFile",null) }
    }
    override fun visit(visit: MutableMap<String,Any>?) { if(visit != null) main.post { if(!closed) channel.invokeMethod("visit",visit) } }
    override fun command(origin: Activity?,name: String?,args: MutableMap<String,Any>?,reply: java.util.function.Consumer<MutableMap<String,Any>>?) {
        main.post {
            var answered = false
            val timeout = Runnable { if(!answered) { answered=true; reply?.accept(mutableMapOf("error" to "操作超时，请检查课堂状态")) } }
            main.postDelayed(timeout,15000)
            channel.invokeMethod("command",mapOf("name" to name,"args" to args),object:MethodChannel.Result {
                override fun success(result:Any?) { if(answered)return; answered=true; main.removeCallbacks(timeout)
                    @Suppress("UNCHECKED_CAST") val value = (result as? Map<String,Any>)?.toMutableMap() ?: mutableMapOf()
                    reply?.accept(value)
                }
                override fun error(code:String,message:String?,details:Any?) { success(mapOf("error" to (message ?: "操作失败"))) }
                override fun notImplemented() { error("unavailable","请返回 IKO 重试",null) }
            })
        }
    }
    fun onResult(code:Int,resultCode:Int,intent:Intent?):Boolean {
        if(code != 8231 && code != 8234) return workspace.onActivityResult(code,resultCode,intent)
        val reply = transferReply ?: return true
        transferReply = null
        val uri = intent?.data
        if(resultCode != Activity.RESULT_OK || uri == null) { password.fill('\u0000'); exportSelection=null; reply.success(null); return true }
        val key = password; password = charArrayOf(); transferring = true
        val selection=exportSelection;exportSelection=null
        val preview=previewRequested;previewRequested=false
        io.execute {
            try {
                NoteRecordingInbox.attachSaved(activity)
                val value = if(code == 8234) NotesLibraryMigration.exportLibrary(activity,uri,selection)
                    else if(preview) {
                        preparedImport?.close()
                        val prepared=PreparedNotesImport.prepare(activity,uri,key);preparedImport=prepared
                        mapOf("token" to prepared.token,"notes" to prepared.rows())
                    } else mapOf("notes" to NotesLibraryMigration.importLibrary(activity,uri,key))
                main.post { if(!closed) {
                    if(code == 8231) workspace.disposeWorkspace()
                    reply.success(value); channel.invokeMethod("libraryChanged",null)
                } }
            } catch(error: Exception) { main.post { if(!closed) reply.error(
                if(error is NotesLibraryMigration.LegacyPasswordRequiredException) "legacy_password" else "transfer",
                error.message ?: "笔记库操作未完成，原数据保留",null) } }
            finally { key.fill('\u0000'); transferring=false }
        }
        return true
    }
    fun dispose() {
        closed = true; workspace.dispose()
        if(NotesHost.client === this) NotesHost.client = null
        transferReply?.error("closed","页面已关闭，请重试",null); transferReply=null; password.fill('\u0000')
        io.execute { preparedImport?.close();preparedImport=null }
        io.shutdown(); channel.setMethodCallHandler(null)
    }
}
