package app.iko.notes;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** System ACTION_SEND only: recipient selection and confirmation belong to the receiving app. */
public final class MainlandShare {
  private MainlandShare() {}

  public static final String BACKUP_MIME = "application/octet-stream";
  private static final String[][] APPS = {
    {"微信", "com.tencent.mm"}, {"QQ", "com.tencent.mobileqq"},
    {"WPS", "cn.wps.moffice_eng"}, {"钉钉", "com.alibaba.android.rimet"},
    {"飞书", "com.ss.android.lark"}, {"企业微信", "com.tencent.wework"}
  };

  public static final class Target {
    public final String label;

    /** null denotes the Android Sharesheet. */
    public final String packageName;

    private Target(String label, String packageName) {
      this.label = label;
      this.packageName = packageName;
    }
  }

  /** Only installed apps with an exported receiver for this MIME are offered. */
  public static List<Target> targets(Context context, String mime) {
    List<Target> result = new ArrayList<>();
    for (String[] app : APPS) {
      Intent intent = new Intent(Intent.ACTION_SEND).setType(mime).setPackage(app[1]);
      for (ResolveInfo info : context.getPackageManager().queryIntentActivities(intent, 0)) {
        if (info.activityInfo != null
            && info.activityInfo.exported
            && info.activityInfo.enabled
            && info.activityInfo.applicationInfo.enabled) {
          result.add(new Target(app[0], app[1]));
          break;
        }
      }
    }
    result.add(new Target("系统更多", null));
    return result;
  }

  /** Call after background export completes. May throw IOException or ActivityNotFoundException. */
  public static void share(Activity activity, File file, String mime, Target target)
      throws IOException {
    Uri uri = ShareFileProvider.uriForFile(activity, file);
    Intent send =
        new Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, file.getName())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    send.setClipData(ClipData.newRawUri(file.getName(), uri));
    if (target != null && target.packageName != null) {
      send.setPackage(target.packageName);
      activity.startActivity(send);
    } else {
      activity.startActivity(Intent.createChooser(send, "分享笔记"));
    }
  }

  public static void shareFiles(Activity activity, List<File> files, String mime, String packageName)
      throws IOException {
    if (files.isEmpty()) throw new IOException("请选择至少一段录音");
    ArrayList<Uri> uris = new ArrayList<>();
    for (File file : files) uris.add(ShareFileProvider.uriForFile(activity, file));
    Intent send = new Intent(files.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE)
        .setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
    else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
    ClipData data = ClipData.newRawUri(files.get(0).getName(), uris.get(0));
    for (int i = 1; i < uris.size(); i++) data.addItem(new ClipData.Item(uris.get(i)));
    send.setClipData(data);
    if (packageName != null) {
      if (!packageName.equals("com.tencent.mm") && !packageName.equals("com.tencent.mobileqq"))
        throw new IOException("不支持该分享目标");
      send.setPackage(packageName);
      activity.startActivity(send);
    } else activity.startActivity(Intent.createChooser(send, "分享课堂录音"));
  }
}
