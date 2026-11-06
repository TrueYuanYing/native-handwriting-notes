import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../asr/live_session_controller.dart';
import '../recording/classroom_recording.dart';
import '../alert/system_alert_service.dart';
import '../asr/start_capture.dart';
import '../../data/models/class_session.dart';

class NoteVisit {
  const NoteVisit({
    required this.sessionId,
    required this.noteId,
    required this.pageId,
    required this.title,
    required this.startedAt,
    required this.durationMs,
  });
  final String sessionId, noteId, pageId, title;
  final DateTime startedAt;
  final int durationMs;
  Map<String, dynamic> toJson() => {
    'sessionId': sessionId,
    'noteId': noteId,
    'pageId': pageId,
    'title': title,
    'startedAt': startedAt.toIso8601String(),
    'durationMs': durationMs,
  };
  factory NoteVisit.fromJson(Map<String, dynamic> j) => NoteVisit(
    sessionId: j['sessionId'] as String,
    noteId: j['noteId'] as String,
    pageId: j['pageId'] as String,
    title: j['title'] as String,
    startedAt: DateTime.parse(j['startedAt'] as String),
    durationMs: j['durationMs'] as int,
  );
}

/// The complete visit ledger is independent of text/recording chunks and survives app restarts.
class NotesCoordinator extends ChangeNotifier {
  NotesCoordinator({
    this.channel = const MethodChannel('com.iko.study/notes'),
    int Function()? elapsedMs,
  }) : _elapsedMs = elapsedMs ?? (() => _clock.elapsedMilliseconds) {
    ready = _load();
    channel.setMethodCallHandler(_native);
  }
  final MethodChannel channel;
  static final Stopwatch _clock = Stopwatch()..start();
  final int Function() _elapsedMs;
  late final Future<void> ready;
  SharedPreferences? _prefs;
  LiveSessionController? _live;
  ClassroomRecording? _recording;
  StreamSubscription? _states;
  Timer? _checkpoint;
  Future<void> _writes = Future.value();
  final List<NoteVisit> visits = [];
  String? _noteId, _pageId, _title, _sessionId;
  int? _began;
  DateTime? _wall;
  bool _visible = false, _disposed = false;
  Future<dynamic>? _recordCommand;
  Future<void> Function()? openArchives;
  void Function(int)? selectTab;
  void Function(String)? selectDestination;
  Future<void> Function()? openIncomingFile;
  bool _openingIncoming = false;
  final Map<int, VoidCallback> paneCloseHandlers = {};
  int libraryRevision = 0;
  Future<void> Function()? openWorkspace;

  Future<void> checkIncomingFile() async {
    if (_openingIncoming || openIncomingFile == null) return;
    _openingIncoming = true;
    try {
      if (await channel.invokeMethod<bool>('checkIncomingFile') != true) return;
      await openIncomingFile?.call();
    } on MissingPluginException catch (_) {
      /* Non-Android test surfaces. */
    } on PlatformException catch (_) {
      /* Existing page remains available. */
    } finally {
      _openingIncoming = false;
    }
  }

  Future<void> _load() async {
    _prefs = await SharedPreferences.getInstance();
    if (_disposed) return;
    final original = _prefs!.getString('classroom_note_visits_v1') ?? '[]';
    try {
      final loaded = (jsonDecode(original) as List)
          .map(
            (row) => NoteVisit.fromJson(Map<String, dynamic>.from(row as Map)),
          )
          .toList();
      visits.addAll(loaded);
    } catch (_) {
      // Preserve the damaged value before any new visit can replace the ledger.
      if (!await _prefs!.setString(
        'classroom_note_visits_recovery_${DateTime.now().microsecondsSinceEpoch}',
        original,
      )) {
        throw StateError('笔记关联记录无法保留，请先备份后重试');
      }
    }
    notifyListeners();
  }

  void bind(LiveSessionController live, ClassroomRecording recording) {
    if (identical(_live, live)) return;
    _close();
    unawaited(_states?.cancel());
    _recording?.removeListener(_publish);
    _live = live;
    _recording = recording;
    _states = live.state.listen((_) {
      _syncSession();
      _publish();
    });
    recording.addListener(_publish);
    _syncSession();
    _publish();
  }

  Future<void> open({
    String? noteId,
    String? pageId,
    String theme = 'system',
    String palette = 'ocean',
    Map<String, Object>? style,
  }) async {
    await ready;
    await channel.invokeMethod<void>('open', {
      'noteId': noteId,
      'pageId': pageId,
      'theme': theme,
      'palette': palette,
      'style': style,
    });
    libraryRevision++;
    notifyListeners();
    await openWorkspace?.call();
  }

  Future<int?> importLibrary([String? password]) async {
    if (_live?.current.isRunning == true || _recording?.listening == true) {
      throw StateError('请结束课堂后迁移笔记库');
    }
    final result = await channel.invokeMapMethod<String, dynamic>(
      'importLibrary',
      password == null ? null : {'password': password},
    );
    return result?['notes'] as int?;
  }

  Future<Map<String, dynamic>?> exportLibrary([List<String>? noteIds]) async {
    if (_live?.current.isRunning == true || _recording?.listening == true) {
      throw StateError('请结束课堂后导出笔记库');
    }
    return channel.invokeMapMethod<String, dynamic>(
      'exportLibrary',
      noteIds == null ? null : {'noteIds': noteIds},
    );
  }

  Future<dynamic> _native(MethodCall call) async {
    await ready;
    if (call.method == 'paneClosed') {
      final slot = (call.arguments as Map?)?['slot'];
      paneCloseHandlers[slot]?.call();
      return null;
    }
    if (call.method == 'libraryChanged') {
      libraryRevision++;
      notifyListeners();
      return null;
    }
    if (call.method == 'incomingFile') {
      await checkIncomingFile();
      return null;
    }
    if (call.method == 'visit') {
      visibility(Map<String, dynamic>.from(call.arguments as Map));
      return null;
    }
    if (call.method != 'command') throw MissingPluginException();
    final name = (call.arguments as Map)['name'];
    if (name == 'selectTab') {
      final index = ((call.arguments as Map)['args'] as Map?)?['index'];
      if (index is int && index >= 0 && index < 5 && index != 2) {
        selectTab?.call(index);
      }
      return <String, Object?>{};
    }
    if (name == 'libraryClosed') {
      selectTab?.call(0);
      return <String, Object?>{};
    }
    if (name == 'openArchives') {
      await openArchives?.call();
      return <String, Object?>{};
    }
    if (name == 'pauseRecording') {
      await _recording?.setEnabled(false);
      await _recording?.settled;
      _publish();
      return <String, Object?>{};
    }
    if (name == 'stopRecordingClassroom') {
      if (_live?.recordingOnly != true) {
        return {'error': '听写课堂请返回听写页结束，录音开关不会停止听写。'};
      }
      await _live!.stop();
      await _recording?.settled;
      _publish();
      return <String, Object?>{};
    }
    if (name == 'startRecording') {
      return _recordCommand ??= _startRecording(
        Map<String, dynamic>.from(
          (call.arguments as Map)['args'] as Map? ?? {},
        ),
      ).whenComplete(() => _recordCommand = null);
    }
    return {'error': '暂不支持该操作'};
  }

  Future<dynamic> _startRecording(Map<String, dynamic> args) async {
    final live = _live, recording = _recording;
    if (live == null || recording == null) return {'error': '课堂尚未就绪，请稍后重试'};
    final standalone = !live.current.isRunning;
    try {
      if (!standalone && live.recordingOnly && !recording.recording) {
        final note = args['noteId'] as String? ?? _noteId,
            page = args['pageId'] as String? ?? _pageId;
        if (note != null && page != null) {
          recording.noteOwner = {'noteId': note, 'pageId': page};
        }
      }
      if (standalone) {
        final note = args['noteId'] as String? ?? _noteId,
            page = args['pageId'] as String? ?? _pageId;
        recording.noteOwner = note != null && page != null
            ? {'noteId': note, 'pageId': page}
            : null;
        await recording.setEnabled(true);
        await startCapture(
          requestPermission: live.audio.requestPermission,
          startForeground: SystemAlertService.startForegroundCapture,
          startSession: live.startRecordingOnly,
          stopForeground: SystemAlertService.stopForegroundCapture,
          stopSession: live.stop,
        );
      }
      if (_recording?.available != true) return {'error': '系统增强暂不提供录音，原听写继续。'};
      await recording.setEnabled(true);
      await recording.settled;
      _publish();
      if (recording.error != null && standalone) await live.stop();
      return recording.error == null
          ? <String, Object?>{}
          : {'error': recording.error};
    } catch (_) {
      if (standalone) await live.stop();
      return {'error': '录音未能启动，请检查麦克风权限后重试。已有课堂录音保留。'};
    }
  }

  void visibility(Map<String, dynamic> event) {
    _close();
    _visible = event['visible'] == true;
    _noteId = event['noteId'] as String?;
    _pageId = event['pageId'] as String?;
    _title = event['title'] as String?;
    _begin();
  }

  void _syncSession() {
    final id =
        _live?.current.isRunning == true &&
            !(_live?.recordingOnly == true && _recording?.noteOwner != null)
        ? _live?.activeSession?.id
        : null;
    updateClassroom(id);
  }

  void updateClassroom(String? id) {
    if (id == _sessionId) return;
    _close();
    _sessionId = id;
    _begin();
  }

  void _begin() {
    if (!_visible || _sessionId == null || _noteId == null || _disposed) return;
    _began = _elapsedMs();
    _wall = DateTime.now();
    _checkpoint ??= Timer.periodic(const Duration(seconds: 15), (_) {
      _close();
      _begin();
    });
  }

  void _close() {
    final start = _began;
    _began = null;
    _checkpoint?.cancel();
    _checkpoint = null;
    if (start == null || _sessionId == null || _noteId == null) return;
    final ms = (_elapsedMs() - start).clamp(0, 24 * 60 * 60 * 1000);
    if (ms > 0) {
      visits.add(
        NoteVisit(
          sessionId: _sessionId!,
          noteId: _noteId!,
          pageId: _pageId ?? '',
          title: _title ?? '笔记',
          startedAt: _wall!,
          durationMs: ms,
        ),
      );
      final snapshot = jsonEncode(visits.map((v) => v.toJson()).toList());
      _writes = _writes.catchError((Object _) {}).then((_) async {
        await ready;
        if (await _prefs!.setString('classroom_note_visits_v1', snapshot) !=
            true) {
          throw StateError('笔记停留记录保存失败，请检查存储空间');
        }
      });
      // Expose failure through settled without an unhandled asynchronous error.
      unawaited(_writes.catchError((Object _) {}));
      if (!_disposed) notifyListeners();
    }
  }

  List<NoteVisit> forSession(String id) =>
      visits.where((v) => v.sessionId == id).toList(growable: false);
  Future<void> get settled => _writes;
  void _publish() {
    if (_disposed) return;
    final live = _live;
    final recording = _recording;
    final event = AlertEventMerger.latestForDisplay(live?.current.alerts ?? []);
    final showAlert =
        event != null &&
        !event.isCompleted &&
        DateTime.now().difference(event.timestamp).abs() <
            const Duration(seconds: 12);
    unawaited(
      channel
          .invokeMethod<void>('state', {
            'running': live?.current.isRunning == true,
            'sessionId': live?.activeSession?.id,
            'recordingOnly': live?.recordingOnly == true,
            'noteRecording':
                live?.recordingOnly == true && recording?.noteOwner != null,
        'noteRecordingOwnerId':recording?.noteOwner?['noteId'],
            'recording': recording?.recording == true,
            'durationMs': recording?.duration.inMilliseconds ?? 0,
            'alertText': showAlert ? (event.question ?? event.triggerText) : '',
            'alertLevel': showAlert
                ? (event.type == AlertType.rollCall ? 'red' : 'yellow')
                : '',
            'error': recording?.error,
          })
          .catchError((Object _) {}),
    );
  }

  @override
  void dispose() {
    _disposed = true;
    _close();
    _checkpoint?.cancel();
    unawaited(_states?.cancel());
    _recording?.removeListener(_publish);
    channel.setMethodCallHandler(null);
    super.dispose();
  }
}
