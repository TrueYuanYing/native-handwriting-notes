package app.iko.notes;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;

/** One local recording or playback session. The Activity explicitly ends it when leaving. */
final class NoteAudio {
  private final Context context;
  private final Runnable changed;
  private final Consumer<String> error;
  private MediaRecorder recorder;
  private MediaPlayer player;
  private NoteDocument.Recording recording;
  private String playingId;
  private boolean paused, prepared;
  private long elapsed, started;
  private final AudioManager audioManager;
  private AudioFocusRequest audioFocus;

  NoteAudio(Context context, Runnable changed, Consumer<String> error) {
    this.context = context;
    this.changed = changed;
    this.error = error;
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
  }

  boolean recording() {
    return recorder != null;
  }

  boolean paused() {
    return paused;
  }

  boolean hasPlayback() {
    return player != null;
  }

  long elapsedMs() {
    return elapsed + (recorder != null && !paused ? SystemClock.elapsedRealtime() - started : 0);
  }

  void start(String pageId) throws IOException {
    if (context.getPackageName().startsWith("com.iko.study")) throw new IOException("请使用 IKO 共用课堂录音");
    if (recorder != null) return;
    stopPlayback();
    File directory = new File(context.getFilesDir(), "media");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法保存录音");
    File file = new File(directory, "recording-" + UUID.randomUUID() + ".m4a");
    MediaRecorder next =
        android.os.Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
    try {
      next.setAudioSource(MediaRecorder.AudioSource.MIC);
      next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      next.setAudioChannels(1);
      next.setAudioSamplingRate(44100);
      next.setAudioEncodingBitRate(64000);
      next.setMaxFileSize(ArchivePolicy.MAX_ASSET_BYTES - 1024 * 1024);
      next.setMaxDuration(8 * 60 * 60 * 1000);
      next.setOutputFile(file.getAbsolutePath());
      next.setOnErrorListener((r, what, extra) -> error.accept("录音被系统中断，请停止并检查录音"));
      next.setOnInfoListener(
          (r, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
              error.accept("录音已达到容量上限，请停止保存并开始下一段");
          });
      next.prepare();
      next.start();
      recorder = next;
      recording = new NoteDocument.Recording();
      recording.path = file.getAbsolutePath();
      recording.pageId = pageId;
      elapsed = 0;
      started = SystemClock.elapsedRealtime();
      paused = false;
      changed.run();
    } catch (IOException | RuntimeException failure) {
      next.release();
      file.delete();
      throw new IOException("无法开始录音，请检查麦克风权限或是否被其他应用占用", failure);
    }
  }

  void togglePause() {
    if (recorder == null) return;
    if (paused) {
      recorder.resume();
      started = SystemClock.elapsedRealtime();
    } else {
      recorder.pause();
      elapsed += SystemClock.elapsedRealtime() - started;
    }
    paused = !paused;
    changed.run();
  }

  NoteDocument.Recording stop() throws IOException {
    if (recorder == null) return null;
    MediaRecorder closing = recorder;
    NoteDocument.Recording result = recording;
    result.durationMs = elapsedMs();
    recorder = null;
    recording = null;
    paused = false;
    try {
      try {
        closing.stop();
      } catch (RuntimeException interrupted) {
        // A system/file-size interruption can already have finalized a valid clip.
        // Validate the actual media before deciding whether any audio was lost.
      }
      closing.release();
      try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
        metadata.setDataSource(result.path);
        String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (duration != null) result.durationMs = Long.parseLong(duration);
      }
      if (result.durationMs < 1 || new File(result.path).length() < 100)
        throw new IOException("录音过短，没有可保存的声音");
      return result;
    } catch (IOException | RuntimeException failure) {
      new File(result.path).delete();
      throw new IOException("录音未能完成，可能录制时间过短或麦克风被中断", failure);
    } finally {
      changed.run();
    }
  }

  void play(NoteDocument.Recording clip) throws IOException {
    if(NotesHost.recording())throw new IOException("请先暂停录音保存，再回放音频");
    if (recorder != null) throw new IOException("先停止录音再播放");
    if (clip.id.equals(playingId) && player != null && prepared) {
      if (player.isPlaying()) player.pause();
      else player.start();
      changed.run();
      return;
    }
    stopPlayback();
    AudioAttributes attributes =
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    audioFocus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(
                focus -> {
                  if (focus < 0 && player != null && prepared) {
                    player.pause();
                    changed.run();
                  }
                })
            .build();
    if (audioManager.requestAudioFocus(audioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      audioManager.abandonAudioFocusRequest(audioFocus);
      audioFocus = null;
      throw new IOException("声音正在被其他应用使用，请稍后重试");
    }
    MediaPlayer next = new MediaPlayer();
    player = next;
    playingId = clip.id;
    try {
      next.setAudioAttributes(attributes);
      next.setDataSource(clip.path);
      next.setOnPreparedListener(
          p -> {
            if (player == p) {
              prepared = true;
              p.start();
              changed.run();
            }
          });
      next.setOnCompletionListener(
          p -> {
            if (player == p) {
              stopPlayback();
              changed.run();
            }
          });
      next.setOnErrorListener(
          (p, what, extra) -> {
            if (player == p) {
              stopPlayback();
              error.accept("录音暂时无法播放，请检查音频文件");
              changed.run();
            }
            return true;
          });
      next.prepareAsync();
    } catch (IOException | RuntimeException failure) {
      stopPlayback();
      throw new IOException("无法打开这段录音", failure);
    }
    changed.run();
  }

  boolean isPlaying(String id) {
    return id.equals(playingId) && player != null && prepared && player.isPlaying();
  }

  int position(String id) {
    return id.equals(playingId) && player != null && prepared ? player.getCurrentPosition() : 0;
  }

  void seek(String id, int millis) {
    if (id.equals(playingId) && player != null && prepared) player.seekTo(Math.max(0, millis));
  }

  void stopPlayback() {
    MediaPlayer closing = player;
    player = null;
    prepared = false;
    playingId = null;
    if (closing != null) closing.release();
    if (audioFocus != null) audioManager.abandonAudioFocusRequest(audioFocus);
    audioFocus = null;
  }
}
