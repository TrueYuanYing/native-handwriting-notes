import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/providers.dart';

class NoteLibraryFile {
  NoteLibraryFile.fromMap(Map<dynamic, dynamic> map)
      : id = map['id'] as String,
        title = map['title'] as String,
        coverPath = map['coverPath'] as String? ?? '',
        exists = map['exists'] == true,
        pages = (map['pages'] as num).toInt(),
        bytes = (map['bytes'] as num).toInt(),
        trashed = map['trashed'] == true,
        expiresAt = DateTime.fromMillisecondsSinceEpoch(
            (map['expiresAt'] as num).toInt()),
        files = [
          for (final file in map['files'] as List)
            Map<String, dynamic>.from(file as Map)
        ];
  final String id, title, coverPath;
  final bool exists;
  final int pages, bytes;
  final bool trashed;
  final DateTime expiresAt;
  final List<Map<String, dynamic>> files;
}

final noteLibraryFilesProvider =
    FutureProvider<List<NoteLibraryFile>>((ref) async {
  final rows = await ref
      .read(notesCoordinatorProvider)
      .channel
      .invokeListMethod<dynamic>('libraryFiles');
  return [for (final row in rows ?? []) NoteLibraryFile.fromMap(row as Map)];
});
