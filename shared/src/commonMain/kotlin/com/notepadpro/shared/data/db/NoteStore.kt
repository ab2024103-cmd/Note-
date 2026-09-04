package com.notepadpro.shared.data.db

import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.LineEnding
import com.notepadpro.shared.domain.model.NoteDocument
import com.notepadpro.shared.domain.model.NoteRow
import com.notepadpro.shared.domain.model.TextCodec
import com.notepadpro.shared.platform.AppDispatchers
import com.notepadpro.shared.platform.createDatabaseDriver
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Thin typed access to the SQLDelight-generated [NoteDatabase].
 * All queries run off the main thread. `lines_json` holds the serialized
 * List<EditorLine>; no HTML is ever stored.
 */
class NoteStore private constructor(
    private val db: NoteDatabase,
    private val json: Json
) {

    /** Column mapper for `SELECT * FROM note` rows (see Note.sq). */
    private val toNoteRow: (Long, String, String, Long, String?, String, Long, Long) -> NoteRow =
        { id, title, linesJson, isPinned, _sourcePath, _lineEnding, createdAt, modifiedAt ->
            val titleText = title.ifBlank { "Untitled" }
            NoteRow(
                id = id,
                title = titleText,
                preview = previewFromJson(linesJson, titleText),
                isPinned = isPinned == 1L,
                modifiedAt = modifiedAt,
                createdAt = createdAt
            )
        }

    suspend fun getAll(): List<NoteRow> = withContext(AppDispatchers.io) {
        db.noteQueries.selectAll(toNoteRow).executeAsList()
    }

    suspend fun search(query: String): List<NoteRow> = withContext(AppDispatchers.io) {
        db.noteQueries.searchByTitleOrContent(query.trim(), toNoteRow).executeAsList()
    }

    suspend fun getDocument(id: Long): NoteDocument? = withContext(AppDispatchers.io) {
        db.noteQueries.selectById(id) { noteId, title, linesJson, isPinned, sourcePath, lineEnding, createdAt, modifiedAt ->
            NoteDocument(
                id = noteId,
                title = title.ifBlank { "Untitled" },
                lines = runCatching {
                    json.decodeFromString<List<EditorLine>>(linesJson)
                }.getOrDefault(emptyList()),
                isPinned = isPinned == 1L,
                sourcePath = sourcePath,
                lineEnding = runCatching { LineEnding.valueOf(lineEnding) }.getOrDefault(LineEnding.LF),
                createdAt = createdAt,
                modifiedAt = modifiedAt
            )
        }.executeAsOneOrNull()
    }

    /**
     * Insert a new note and return its generated id, or update an existing one.
     * The document is serialized here, lazily, right before the actual DB
     * write - never on every keystroke.
     */
    suspend fun saveDocument(doc: NoteDocument): Long = withContext(AppDispatchers.io) {
        val now = System.currentTimeMillis()
        val linesJson = json.encodeToString<List<EditorLine>>(doc.lines)
        val id = doc.id
        if (id == null) {
            db.noteQueries.insertNote(
                title = doc.title.ifBlank { TextCodec.titleFromLines(doc.lines) },
                lines_json = linesJson,
                is_pinned = if (doc.isPinned) 1L else 0L,
                source_path = doc.sourcePath,
                line_ending = doc.lineEnding.name,
                created_at = if (doc.createdAt == 0L) now else doc.createdAt,
                modified_at = now
            )
            db.noteQueries.lastInsertRowId().executeAsOne()
        } else {
            db.noteQueries.updateNote(
                title = doc.title.ifBlank { TextCodec.titleFromLines(doc.lines) },
                lines_json = linesJson,
                is_pinned = if (doc.isPinned) 1L else 0L,
                source_path = doc.sourcePath,
                line_ending = doc.lineEnding.name,
                modified_at = now,
                id = id
            )
            id
        }
    }

    suspend fun deleteNote(id: Long) = withContext(AppDispatchers.io) {
        db.noteQueries.deleteNote(id)
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(AppDispatchers.io) {
        db.noteQueries.setPinned(if (pinned) 1L else 0L, id)
    }

    companion object {
        fun create(json: Json = Json { ignoreUnknownKeys = true }): NoteStore {
            val driver = createDatabaseDriver()
            return NoteStore(NoteDatabase(driver), json)
        }
    }
}

/**
 * Reads the first line's text out of the JSON blob cheaply WITHOUT decoding
 * the whole document (a huge note must never be decoded per sidebar refresh).
 * The kotlinx-serialization field order of EditorLine is deterministic
 * (id, spans[{text,..}], ...), so the first `"text":"` value is the first
 * span of the first line. Escapes are unescaped best-effort.
 */
private fun previewFromJson(linesJson: String, fallback: String): String {
    if (linesJson.isBlank()) return fallback
    val idx = linesJson.indexOf("\"text\":\"")
    if (idx < 0) return fallback
    var i = idx + 8
    val sb = StringBuilder()
    var closed = false
    while (i < linesJson.length && sb.length < 160) {
        val c = linesJson[i]
        when {
            c == '\\' -> {
                i++
                if (i >= linesJson.length) break
                when (val e = linesJson[i]) {
                    'n' -> sb.append(' ')
                    't' -> sb.append(' ')
                    'r' -> {}
                    'u' -> i += 4
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(e)
                }
            }
            c == '"' -> {
                closed = true
                break
            }
            else -> sb.append(c)
        }
        i++
    }
    return if (!closed && sb.isEmpty()) fallback else sb.toString().ifBlank { fallback }
}
