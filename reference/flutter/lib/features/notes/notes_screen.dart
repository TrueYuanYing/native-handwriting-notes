import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../../services/notes/notes_coordinator.dart';
import 'notes_pane.dart';
import 'note_export_picker.dart';
import '../../services/notes/note_library_files.dart';

Future<void> openIkoNotes(
  BuildContext context,
  WidgetRef ref, {
  String? noteId,
  String? pageId,
}) async {
  final settings = ref.read(settingsProvider).valueOrNull;
  try {
    await ref
        .read(notesCoordinatorProvider)
        .open(
          noteId: noteId,
          pageId: pageId,
          theme: settings?.themeMode ?? 'system',
          palette: settings?.colorPalette ?? 'ocean',
          style: notesHostStyle(context, ref),
        );
  } catch (_) {
    if (context.mounted) {
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('笔记暂时无法打开'),
          content: const Text('请稍后重试，当前听写与课堂数据保留。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('知道了'),
            ),
          ],
        ),
      );
    }
  }
}

class NotesScreen extends ConsumerWidget {
  const NotesScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(title: const Text('笔记导入与导出')),
    body: Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 640),
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            const SizedBox(height: 16),
            Icon(
              Icons.menu_book_outlined,
              size: 48,
              color: Theme.of(context).colorScheme.secondary,
            ),
            const SizedBox(height: 24),
            Text('笔记导入与导出', style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),
            const Text('保留可编辑笔记、手写、媒体与笔设置。'),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: () => _transfer(context, ref, true),
              icon: const Icon(Icons.ios_share_rounded),
              label: const Padding(
                padding: EdgeInsets.all(14),
                child: Text('选择笔记导出'),
              ),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: () => _transfer(context, ref, false),
              icon: const Icon(Icons.drive_folder_upload_outlined),
              label: const Padding(
                padding: EdgeInsets.all(14),
                child: Text('导入笔记库'),
              ),
            ),
            const SizedBox(height: 16),
            const Text(
              '导入 .ikonoteslib 文件，同 ID 的已有笔记不会被覆盖。原独立版笔记与文件保留。仅旧版已加密文件需要其原有密码。',
            ),
          ],
        ),
      ),
    ),
  );
  Future<void> _transfer(
    BuildContext context,
    WidgetRef ref,
    bool exporting, {
    String? legacyPassword,
  }) async {
    List<String>? selected;
    await ref.read(classroomRecordingProvider).settled;
    if (!context.mounted) return;
    if (exporting) {
      try {
        ref.invalidate(noteLibraryFilesProvider);
        final notes = await ref.read(noteLibraryFilesProvider.future);
        if (!context.mounted) return;
        selected = await Navigator.of(context).push<List<String>>(
          MaterialPageRoute(
            builder: (_) => NoteExportPicker(
              notes: notes.where((note) => !note.trashed).toList(),
            ),
          ),
        );
        if (selected == null || selected.isEmpty || !context.mounted) return;
      } catch (_) {
        if (context.mounted) {
          ScaffoldMessenger.of(context)
              .showSnackBar(const SnackBar(content: Text('笔记列表暂时无法读取，请稍后重试。')));
        }
        return;
      }
    }
    final navigator = Navigator.of(context, rootNavigator: true);
    final progress = DialogRoute<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => PopScope(
        canPop: false,
        child: AlertDialog(
          content: Row(
            children: [
              const SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              const SizedBox(width: 18),
              Expanded(child: Text(exporting ? '正在准备导出笔记库…' : '正在校验并导入笔记库…')),
            ],
          ),
        ),
      ),
    );
    unawaited(navigator.push(progress));
    Object? failure;
    Object? result;
    try {
      final notes = ref.read(notesCoordinatorProvider);
      result = exporting
          ? await notes.exportLibrary(selected)
          : await notes.channel.invokeMapMethod<String, dynamic>(
              'previewLibrary',
              legacyPassword == null ? null : {'password': legacyPassword},
            );
    } catch (error) {
      failure = error;
    } finally {
      if (progress.isActive) navigator.removeRoute(progress);
    }
    if (!context.mounted) return;
    if (failure is PlatformException && failure.code == 'legacy_password') {
      final input = TextEditingController();
      final password = await showDialog<String>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('旧版加密文件'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('这个旧文件已加密。请输入该文件原有密码，然后重新选择文件；新导出的笔记库不需要密码。'),
              const SizedBox(height: 16),
              TextField(
                controller: input,
                obscureText: true,
                autocorrect: false,
                enableSuggestions: false,
                decoration: const InputDecoration(labelText: '旧文件原有密码'),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, input.text),
              child: const Text('重新选择文件'),
            ),
          ],
        ),
      );
      input.dispose();
      if (password != null && context.mounted) {
        await _transfer(context, ref, false, legacyPassword: password);
      }
      return;
    }
    if (!exporting &&
        failure == null &&
        result is Map &&
        result['token'] is String) {
      final channel = ref.read(notesCoordinatorProvider).channel;
      try {
        final preview = [
          for (final row in result['notes'] as List)
            NoteLibraryFile.fromMap(row as Map),
        ];
        final ids = await Navigator.of(context).push<List<String>>(
          MaterialPageRoute(
            builder: (_) => NoteExportPicker(notes: preview, importing: true),
          ),
        );
        if (ids == null || ids.isEmpty) return;
        if (!context.mounted) return;
        final applying = DialogRoute<void>(
          context: context,
          barrierDismissible: false,
          builder: (_) => const PopScope(
            canPop: false,
            child: AlertDialog(
              content: Row(
                children: [
                  CircularProgressIndicator(),
                  SizedBox(width: 20),
                  Expanded(child: Text('正在导入所选笔记…')),
                ],
              ),
            ),
          ),
        );
        unawaited(navigator.push(applying));
        try {
          result = await channel.invokeMapMethod<String, dynamic>(
            'finishImport',
            {'token': result['token'], 'noteIds': ids},
          );
        } catch (error) {
          failure = error;
        } finally {
          if (applying.isActive) navigator.removeRoute(applying);
        }
      } finally {
        await channel.invokeMethod<void>('cancelImport').catchError((_) {});
      }
      if (!context.mounted) return;
    }
    if (result == null && failure == null) return;
    final message = failure is PlatformException
        ? failure.message
        : failure?.toString();
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          failure != null
              ? '操作未完成'
              : exporting
              ? '导出完成'
              : '导入完成',
        ),
        content: Text(
          failure != null
              ? (message ?? '请结束课堂并检查文件和剩余空间后重试。原笔记保留。')
              : exporting
              ? '已保存所选笔记及附件。'
              : '笔记库已导入，原文件保留。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('知道了'),
          ),
        ],
      ),
    );
  }
}

class ClassroomNoteLinks extends ConsumerWidget {
  const ClassroomNoteLinks({super.key, required this.sessionId});
  final String sessionId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final visits = ref.watch(notesCoordinatorProvider).forSession(sessionId);
    if (visits.isEmpty) return const SizedBox.shrink();
    final notes = <String, List<NoteVisit>>{};
    for (final visit in visits) {
      notes.putIfAbsent(visit.noteId, () => []).add(visit);
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('这节课使用的笔记', style: Theme.of(context).textTheme.titleMedium),
          for (final rows in notes.values)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.menu_book_outlined),
              title: Text(rows.last.title),
              subtitle: Text(
                '累计停留 ${(rows.fold<int>(0, (sum, row) => sum + row.durationMs) / 60000).toStringAsFixed(1)} 分钟 · ${rows.length} 个访问片段',
              ),
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: () => openIkoNotes(
                context,
                ref,
                noteId: rows.last.noteId,
                pageId: rows.last.pageId,
              ),
            ),
        ],
      ),
    );
  }
}
