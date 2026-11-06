package app.iko.notes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;

/** One-shot, user-approved Android screen capture for inserting a visual reference into a page. */
public final class ScreenCaptureService extends Service {
  public static final String ACTION_CAPTURED = "app.iko.notes.SCREEN_CAPTURED";
  public static final String ACTION_FAILED = "app.iko.notes.SCREEN_CAPTURE_FAILED";
  public static final String BROADCAST_PERMISSION = "app.iko.notes.permission.RECEIVE_CAPTURE_EVENTS";
  private static final String CHANNEL = "iko_screen_capture";
  private static final int NOTIFICATION_ID = 6302;
  private final Handler main = new Handler(Looper.getMainLooper());
  private MediaProjection projection;
  private VirtualDisplay display;
  private ImageReader reader;
  private boolean finished;
  private long captureAfterElapsedMs;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || finished) {
      stopSelf();
      return START_NOT_STICKY;
    }
    createChannel();
    Notification notification =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("IKO 正在截取屏幕")
            .setContentText("截屏只会在本机保存到当前笔记")
            .setOngoing(true)
            .build();
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    else startForeground(NOTIFICATION_ID, notification);
    int resultCode = intent.getIntExtra("resultCode", 0);
    Intent resultData =
        Build.VERSION.SDK_INT >= 33
            ? intent.getParcelableExtra("resultData", Intent.class)
            : intent.getParcelableExtra("resultData");
    try {
      MediaProjectionManager manager =
          (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
      projection = manager.getMediaProjection(resultCode, resultData);
      projection.registerCallback(
          new MediaProjection.Callback() {
            @Override
            public void onStop() {
              finish(null, "系统已停止屏幕截取");
            }
          },
          main);
      DisplayMetrics metrics = new DisplayMetrics();
      WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
      windowManager.getDefaultDisplay().getRealMetrics(metrics);
      int width = Math.max(1, metrics.widthPixels), height = Math.max(1, metrics.heightPixels);
      reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
      reader.setOnImageAvailableListener(this::captureAvailable, main);
      // Android's consent surface can remain in the projection for its exit animation. Wait for
      // the activity the user chose to become visible before accepting the first frame.
      captureAfterElapsedMs = SystemClock.elapsedRealtime() + 900;
      display =
          projection.createVirtualDisplay(
              "IKO page screenshot",
              width,
              height,
              metrics.densityDpi,
              DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
              reader.getSurface(),
              null,
              main);
    } catch (Exception error) {
      finish(null, error.getMessage());
    }
    return START_NOT_STICKY;
  }

  private void captureAvailable(ImageReader source) {
    if (finished) return;
    Image image = null;
    Bitmap full = null;
    try {
      image = source.acquireLatestImage();
      if (image == null) return;
      if (SystemClock.elapsedRealtime() < captureAfterElapsedMs) return;
      Image.Plane plane = image.getPlanes()[0];
      ByteBuffer buffer = plane.getBuffer();
      int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
      int paddedWidth = rowStride / pixelStride;
      full = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
      full.copyPixelsFromBuffer(buffer);
      Bitmap cropped =
          paddedWidth == image.getWidth()
              ? full
              : Bitmap.createBitmap(full, 0, 0, image.getWidth(), image.getHeight());
      if (cropped != full) full.recycle();
      full = null;
      Bitmap scaled = cropped;
      int largestEdge = Math.max(cropped.getWidth(), cropped.getHeight());
      if (largestEdge > 2400) {
        float scale = 2400f / largestEdge;
        scaled =
            Bitmap.createScaledBitmap(
                cropped,
                Math.max(1, Math.round(cropped.getWidth() * scale)),
                Math.max(1, Math.round(cropped.getHeight() * scale)),
                true);
      }
      File media = new File(getFilesDir(), "media");
      if (!media.isDirectory() && !media.mkdirs()) throw new java.io.IOException("无法保存截屏");
      Bitmap.CompressFormat format =
          Build.VERSION.SDK_INT >= 30
              ? Bitmap.CompressFormat.WEBP_LOSSLESS
              : Bitmap.CompressFormat.PNG;
      String extension = Build.VERSION.SDK_INT >= 30 ? ".webp" : ".png";
      File output = new File(media, "screen-" + UUID.randomUUID() + extension);
      try (FileOutputStream stream = new FileOutputStream(output)) {
        if (!scaled.compress(format, 100, stream))
          throw new java.io.IOException("图片编码失败");
        stream.getFD().sync();
      } finally {
        if (scaled != cropped) scaled.recycle();
        cropped.recycle();
      }
      finish(output.getAbsolutePath(), null);
    } catch (Exception error) {
      finish(null, error.getMessage());
    } finally {
      if (image != null) image.close();
      if (full != null && !full.isRecycled()) full.recycle();
    }
  }

  private void finish(String path, String reason) {
    if (finished) return;
    finished = true;
    if (reader != null) {
      reader.setOnImageAvailableListener(null, null);
      reader.close();
      reader = null;
    }
    if (display != null) {
      display.release();
      display = null;
    }
    if (projection != null) {
      MediaProjection current = projection;
      projection = null;
      try {
        current.stop();
      } catch (RuntimeException ignored) {
      }
    }
    if (path != null)
      sendBroadcast(
          new Intent(ACTION_CAPTURED).setPackage(getPackageName()).putExtra("path", path),
          getPackageName() + ".permission.RECEIVE_CAPTURE_EVENTS");
    else if (reason != null)
      sendBroadcast(
          new Intent(ACTION_FAILED)
              .setPackage(getPackageName())
              .putExtra("reason", reason),
          getPackageName() + ".permission.RECEIVE_CAPTURE_EVENTS");
    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
    else stopForeground(true);
    stopSelf();
  }

  private void createChannel() {
    if (Build.VERSION.SDK_INT < 26) return;
    NotificationManager manager = getSystemService(NotificationManager.class);
    if (manager != null)
      manager.createNotificationChannel(
          new NotificationChannel(CHANNEL, "笔记截屏", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public void onDestroy() {
    finish(null, null);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
