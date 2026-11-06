import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../../core/theme/app_colors.dart';
import '../../services/icon/app_icon_service.dart';
import '../../services/resources/resource_packages.dart';
import '../../services/notes/notes_coordinator.dart';
import '../settings/storage_resources_screen.dart';
import 'notes_alert_geometry.dart';
import 'notes_live_text.dart';

Map<String, Object> notesHostStyle(BuildContext context, WidgetRef ref) {
  final theme = Theme.of(context), brightness = theme.brightness;
  final settings = ref.read(settingsProvider).valueOrNull;
  return {
    'iconMode': AppIconService.effectiveMode(settings?.iconMode ?? 'system',
        MediaQuery.platformBrightnessOf(context)),
    'dark': brightness == Brightness.dark,
    'background': theme.scaffoldBackgroundColor.toARGB32(),
    'surface': theme.colorScheme.surface.toARGB32(),
    'fill': AppColors.fill(brightness).toARGB32(),
    'text': theme.colorScheme.onSurface.toARGB32(),
    'muted': AppColors.secondary(brightness).toARGB32(),
    'line': AppColors.separator(brightness).toARGB32(),
    'selection': theme.colorScheme.secondary.toARGB32(),
    'onSelection': theme.colorScheme.onSecondary.toARGB32(),
    'action': theme.colorScheme.primary.toARGB32(),
    'onAction': theme.colorScheme.onPrimary.toARGB32(),
    'danger': theme.colorScheme.error.toARGB32(),
    'navIcons': [
      Icons.mic_none_rounded.codePoint,
      Icons.calendar_today_outlined.codePoint,
      Icons.book_rounded.codePoint,
      Icons.coffee_outlined.codePoint,
      Icons.settings_outlined.codePoint
    ],
  };
}

/// A retained native sibling view occupies only this measured window rectangle.
/// Pen MotionEvents stay in Android; this bridge sends layout changes, never ink samples.
class NotesPane extends ConsumerStatefulWidget {
  const NotesPane(
      {super.key,
      this.active = true,
      this.onClose,
      this.compact = false,
      this.visibilityRevision = 0});
  final bool active, compact;
  final int visibilityRevision;
  final VoidCallback? onClose;
  @override
  ConsumerState<NotesPane> createState() => _NotesPaneState();
}

class _NotesPaneState extends ConsumerState<NotesPane>
    with WidgetsBindingObserver {
  static int _nextSlot = 0;
  final int _slot = ++_nextSlot;
  final GlobalKey _bounds = GlobalKey();
  String? _signature, _error;
  bool _scheduled = false;
  int _revision = -1;
  late final NotesCoordinator _notes;
  ModalRoute<dynamic>? _route;

  bool get _routeSettled =>
      (_route?.isCurrent ?? true) &&
      (_route?.animation?.status ?? AnimationStatus.completed) ==
          AnimationStatus.completed &&
      (_route?.secondaryAnimation?.status ?? AnimationStatus.dismissed) ==
          AnimationStatus.dismissed;

  void _routeTransition(AnimationStatus status) {
    // The native sibling cannot follow Flutter's route transform or clipping.
    // Hide at transition start, then measure its new owner after the route settles.
    _signature = null;
    if (!_routeSettled) {
      unawaited(_notes.channel
          .invokeMethod<void>('hide', {'slot': _slot}).catchError((_) {}));
    }
    _schedule();
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    notesAlertGeometry.addListener(_schedule);
    _notes = ref.read(notesCoordinatorProvider);
    _notes.paneCloseHandlers[_slot] = () => widget.onClose?.call();
    Future.microtask(() => ref.read(resourcePackagesProvider).refresh());
  }

  @override
  void didChangeMetrics() => _schedule();
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    if (route != _route) {
      _route?.animation?.removeStatusListener(_routeTransition);
      _route?.secondaryAnimation?.removeStatusListener(_routeTransition);
      _route = route;
      _route?.animation?.addStatusListener(_routeTransition);
      _route?.secondaryAnimation?.addStatusListener(_routeTransition);
      _signature = null;
    }
    _schedule();
  }

  @override
  void didUpdateWidget(covariant NotesPane old) {
    super.didUpdateWidget(old);
    if (old.visibilityRevision != widget.visibilityRevision) _signature = null;
    _schedule();
  }

  void _schedule() {
    if (_scheduled || !mounted) return;
    _scheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scheduled = false;
      if (mounted) unawaited(_updateBounds());
    });
    WidgetsBinding.instance.ensureVisualUpdate();
  }

  Future<void> _updateBounds() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
    if (_error != null) return; // Retry only after the user requests it.
    final available =
        ref.read(resourcePackagesProvider)['notes']?.installed == true;
    final visible = widget.active && _routeSettled && available;
    final box = _bounds.currentContext?.findRenderObject() as RenderBox?;
    final ratio = MediaQuery.devicePixelRatioOf(context);
    final point =
        box?.hasSize == true ? box!.localToGlobal(Offset.zero) : Offset.zero;
    final size = box?.hasSize == true ? box!.size : Size.zero;
    final alertInset = (notesAlertGeometry.value - point.dy)
        .clamp(0.0, size.height)
        .toDouble();
    final settings = ref.read(settingsProvider).valueOrNull;
    final data = <String, Object>{
      'slot': _slot,
      'compact': widget.compact,
      'left': (point.dx * ratio).round(),
      'top': ((point.dy + alertInset) * ratio).round(),
      'width': (size.width * ratio).round(),
      'height': ((size.height - alertInset) * ratio).round(),
      'visible': visible,
      'theme': settings?.themeMode ?? 'system',
      'palette': settings?.colorPalette ?? 'ocean',
      'style': notesHostStyle(context, ref)
    };
    final signature = jsonEncode(data);
    if (signature == _signature) return;
    _signature = signature;
    try {
      await ref
          .read(notesCoordinatorProvider)
          .channel
          .invokeMethod<void>('show', data);
    } on MissingPluginException {
      /* The widget-test surface has no native editor. */
    } on PlatformException catch (failure) {
      if (mounted) setState(() => _error = failure.message ?? '笔记暂时无法打开，请重试。');
    }
  }

  @override
  void dispose() {
    notesAlertGeometry.removeListener(_schedule);
    WidgetsBinding.instance.removeObserver(this);
    _route?.animation?.removeStatusListener(_routeTransition);
    _route?.secondaryAnimation?.removeStatusListener(_routeTransition);
    final notes = _notes;
    notes.paneCloseHandlers.remove(_slot);
    unawaited(notes.channel
        .invokeMethod<void>('hide', {'slot': _slot}).catchError((_) {}));
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final revision = ref.watch(notesCoordinatorProvider).libraryRevision;
    if (revision != _revision) {
      _revision = revision;
      _signature = null;
    }
    final resources = ref.watch(resourcePackagesProvider),
        pack = resources['notes'];
    ModalRoute.of(
        context); // Route changes also hide the native region beneath Flutter dialogs.
    _schedule();
    if (!widget.active) return SizedBox.expand(key: _bounds);
    if (_error != null) {
      return Center(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
        Text(_error!),
        TextButton(
            onPressed: () {
              setState(() {
                _error = null;
                _signature = null;
              });
              _schedule();
            },
            child: const Text('重试'))
      ]));
    }
    if (pack?.installed != true) {
      return Center(
          child: SingleChildScrollView(
              child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 430),
                  child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(mainAxisSize: MainAxisSize.min, children: [
                        Icon(Icons.menu_book_outlined,
                            size: 42,
                            color: Theme.of(context).colorScheme.secondary),
                        const SizedBox(height: 18),
                        Text(
                            pack == null && resources.error == null
                                ? '正在读取笔记资源…'
                                : '把笔记留在身边',
                            style: Theme.of(context).textTheme.titleLarge,
                            textAlign: TextAlign.center),
                        const SizedBox(height: 10),
                        Text(
                            pack == null && resources.error == null
                                ? '首次检查后即可继续。'
                                : '安装笔记资源后，可选择已有笔记或新建笔记。你的原有笔记仍保留在本机。',
                            textAlign: TextAlign.center),
                        const SizedBox(height: 18),
                        if (pack != null)
                          ResourcePackageTile(pack: pack, highlighted: true)
                        else if (resources.error != null)
                          TextButton(
                              onPressed: () => resources.refresh(),
                              child: const Text('重试'))
                        else
                          const SizedBox(
                              width: 22,
                              height: 22,
                              child: CircularProgressIndicator(strokeWidth: 2)),
                      ])))));
    }
    return NotificationListener<SizeChangedLayoutNotification>(
        onNotification: (_) {
          _schedule();
          return false;
        },
        child: SizeChangedLayoutNotifier(child: SizedBox.expand(key: _bounds)));
  }
}

class NotesPage extends StatelessWidget {
  const NotesPage({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(title: const Text('笔记')),
      body: SafeArea(
          child: Column(children: [
        const NotesLiveTextStrip(),
        Expanded(child: NotesPane(onClose: () => Navigator.maybePop(context))),
      ])));
}
