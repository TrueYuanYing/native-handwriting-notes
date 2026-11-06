package com.classmate.classmate

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Stable file entry: launcher icon aliases may be disabled independently. */
class NotesImportActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val incoming=intent
        val uri=sharedFileUri(incoming)
        if(Build.VERSION.SDK_INT<29 || uri?.scheme!="content") {
            Toast.makeText(this,if(Build.VERSION.SDK_INT<29) "笔记文件导入需要 Android 10 或以上" else "无法读取文件，请在文件管理中重新分享原文件",Toast.LENGTH_LONG).show()
            finish();return
        }
        val target=normalizedFileIntent(incoming,uri).setClass(this,MainActivity::class.java).apply {
            // Reuse the existing Flutter owner even when Notes is currently above it.
            flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION
            clipData=ClipData.newRawUri("IKO import",uri)
        }
        try {
            val existing=getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
                val info=it.taskInfo
                info.baseIntent.component?.packageName==packageName && info.baseActivity?.className!=javaClass.name
            }
            if(existing!=null)existing.startActivity(this,target,null)
            else startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }catch(_:Exception){Toast.makeText(this,"暂时无法打开 IKO，请保留原文件后重试",Toast.LENGTH_LONG).show()}
        finish()
    }
}

internal fun sharedFileUri(intent: Intent?): Uri? {
    if(intent==null)return null
    if(intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND)return null
    val uri=when(intent.action) {
        Intent.ACTION_VIEW->intent.data
        Intent.ACTION_SEND->if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)
            else { @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri) }
        else->null
    }
    return uri ?: intent.clipData?.takeIf { it.itemCount>0 }?.getItemAt(0)?.uri ?: intent.data
}

/** External apps may use data, EXTRA_STREAM or ClipData. Forward only file payloads,
 * never their arbitrary extras (which could override host theme or note selection). */
internal fun normalizedFileIntent(source: Intent, uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW).setDataAndType(uri,source.type).apply {
        clipData=ClipData.newRawUri("IKO import",uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
