package com.notepadpro.shared.editor

import com.notepadpro.shared.data.settings.SettingsRepository
import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor
import com.notepadpro.shared.domain.model.InlineSpan
import com.notepadpro.shared.domain.model.LineEnding
import com.notepadpro.shared.domain.model.ListType
import com.notepadpro.shared.domain.model.NoteDocument
import com.notepadpro.shared.domain.model.TextCodec
import com.notepadpro.shared.platform.AppDispatchers
import com.notepadpro.shared.platform.ClipboardBridge
import com.notepadpro.shared.platform.FileIO
import com.notepadpro.shared.platform.FilePickerBridge
import com.notepadpro.shared.platform.PlatformInfo
import com.notepadpro.shared.platform.currentTimeMillis
import com.notepadpro.shared.platform.randomLineId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Caret/selection inside one line (UI-agnostic). */
data class Caret(val start: Int, val end: Int) {
    val min: Int get() = if (start <= end) start else end
    val max: Int get() = if (start <= end) end else start
}

/** Snapshot of the whole document UI state, published per change. */
data class DocState(
    val version: Long = 0L,
    val noteId: Long? = null,
    val lines: List<EditorLine> = emptyList(),
    val numbers: Map<String, Int> = emptyMap(),
    val lineEnding: LineEnding = LineEnding.LF,
    val sourcePath: String? = null,
    val isPinned: Boolean = false,
    val activeLineId: String? = null,
    val caret: Caret? = null,
    val anchorLineId: String? = null,
    val focusLineId: String? = null,
    /** Set by ops that want the UI to move focus + caret (Enter, merge, undo). */
    val focusRequest: Pair<String, Caret>? = null,
    val empty: Boolean = true
) {
    val isMultiSelected: Boolean
        get() = anchorLineId != null && focusLineId != null && anchorLineId != focusLineId
}

enum class SaveStatus { CLEAN, DIRTY, SAVING, SAVED, ERROR }

sealed class SessionEvent {
    data class DbSaved(val noteId: Long?, val version: Long) : SessionEvent()
    data class DbSaveFailed(val message: String) : SessionEvent()
    data class FileSaved(val path: String, val asNewFile: Boolean) : SessionEvent()
    data class FileOpened(val path: String, val title: String) : SessionEvent()
    data class FileFailed(val message: String) : SessionEvent()
}

/**
 * One editor tab. Owns the document state, the bounded undo stack, the
 * debounced autosave pipeline and file import/export.
 *
 * Design notes (per the master spec):
 *  - Typing produces per-line "burst" undo entries (lightweight diffs: only
 *    touched lines are snapshotted), capped at PlatformInfo's limit and
 *    shrunk automatically on low-RAM devices.
 *  - Structural/formatting ops push full-document snapshots of *references*
 *    (immutable lines are shared, so this is cheap).
 *  - DB writes are debounced (600 ms normal / 1500 ms on low-memory devices)
 *    and always run on Dispatchers.IO.
 * All mutators must be called on the main dispatcher.
 */
class EditorSession(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    initial: NoteDocument?,
    private val onPersist: suspend (NoteDocument) -> Long
) {
    private val _state = MutableStateFlow(initialState(initial))
    val state: StateFlow<DocState> = _state

    private val _saveStatus = MutableStateFlow(SaveStatus.CLEAN)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SessionEvent> = _events

    /** True once the note row exists in the DB (autosave ran at least once). */
    var hasEverSaved: Boolean = false
        private set

    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<UndoEntry>()
    private val undoLimit: Int = PlatformInfo.recommendedUndoHistoryLimit()

    // ---- typing-burst bookkeeping (lightweight undo) ----
    private var burstActive = false
    private var burstLineId: String? = null
    private var burstBefore: EditorLine? = null
    private var burstCaret: Caret? = null
    private var burstJob: Job? = null

    private var lastSavedVersion: Long = _state.value.version
    private var saveJob: Job? = null
    private var saveRunning = false

    private var disposed = false

    private fun initialState(initial: NoteDocument?): DocState {
        val lines = initial?.lines?.takeIf { it.isNotEmpty() } ?: listOf(newBlankLine())
        return DocState(
            version = 1L,
            noteId = initial?.id,
            lines = lines,
            numbers = computeNumbers(lines),
            lineEnding = initial?.lineEnding ?: LineEnding.LF,
            sourcePath = initial?.sourcePath,
            isPinned = initial?.isPinned ?: false,
            caret = Caret(0, 0),
            empty = lines.all { it.isEmptyLine }
        )
    }

    private fun newBlankLine(): EditorLine = EditorLine.plain(randomLineId(), "")

    // ------------------------------------------------------------------
    // Core text editing
    // ------------------------------------------------------------------

    /** Single-line text change coming from a row's BasicTextField. */
    fun applyTextChange(lineId: String, newText: String, selStart: Int, selEnd: Int) {
        if (disposed) return
        if (newText.contains('\n')) {
            handleMultilineInput(lineId, newText)
            return
        }
        val state = _state.value
        val index = state.lines.indexOfFirst { it.id == lineId }
        if (index < 0) return
        val line = state.lines[index]
        val oldText = line.plainText
        if (oldText == newText) {
            // Caret-only move (also reports line focus).
            moveCaretOnly(lineId, selStart, selEnd)
            return
        }
        ensureBurst(line)
        val prefix = commonPrefixLen(oldText, newText)
        val suffix = guardedSuffixLen(oldText, newText, prefix)
        val newSpans = remapSpansForEdit(line.spans, oldText, newText, prefix, suffix)
        val newLine = line.copy(spans = newSpans)
        val out = state.lines.toMutableList()
        out[index] = newLine
        publish(out, activeLineId = lineId, caret = Caret(selStart, selEnd))
        scheduleBurstFlush()
    }

    private fun moveCaretOnly(lineId: String, selStart: Int, selEnd: Int) {
        _state.update { s ->
            val clearAnchor = s.anchorLineId != null && s.anchorLineId != s.activeLineId
            s.copy(
                activeLineId = lineId,
                caret = Caret(selStart, selEnd),
                focusLineId = lineId,
                anchorLineId = if (clearAnchor) null else s.anchorLineId
            )
        }
    }

    fun onLineFocused(lineId: String) {
        _state.update {
            it.copy(activeLineId = lineId, caret = it.caret ?: Caret(0, 0))
        }
    }

    private fun guardedSuffixLen(a: String, b: String, prefix: Int): Int {
        val max = minOf(a.length - prefix, b.length - prefix)
        var i = 0
        while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }

    /**
     * Paste / IME commit containing '\n': normalize to plain text lines and
     * explode into multiple EditorLines. Lines inherit list type/indent so
     * pasting inside a list keeps working like Enter.
     */
    private fun handleMultilineInput(lineId: String, newText: String) {
        val state = _state.value
        val index = state.lines.indexOfFirst { it.id == lineId }
        if (index < 0) return
        val line = state.lines[index]
        val oldText = line.plainText
        if (oldText.contains('\n')) return // invariant: lines never hold \n

        flushBurst()
        pushFullSnapshot()

        val prefix = commonPrefixLen(oldText, newText)
        val suffix = guardedSuffixLen(oldText, newText, prefix)
        // The '\n' can only live inside the inserted region (old text has none).
        val inserted = newText.substring(prefix, newText.length - suffix)
        val parts = inserted.split('\n')
        if (parts.size <= 1) return
        val firstPart = parts[0]
        val headLine = line.copy(
            spans = remapSpansForEdit(
                line.spans,
                oldText,
                oldText.substring(0, prefix) + firstPart + oldText.substring(oldText.length - suffix),
                prefix,
                suffix
            )
        )
        val tailLines = parts.drop(1).map { part ->
            EditorLine(
                id = randomLineId(),
                spans = if (part.isEmpty()) emptyList() else listOf(InlineSpan(part)),
                listType = line.listType,
                indent = line.indent
            )
        }
        val out = state.lines.toMutableList()
        out[index] = headLine
        out.addAll(index + 1, tailLines)
        val caretEnd = prefix + firstPart.length
        publish(
            out,
            activeLineId = headLine.id,
            caret = Caret(caretEnd, caretEnd),
            focus = headLine.id to Caret(caretEnd, caretEnd)
        )
    }

    // ------------------------------------------------------------------
    // Line navigation ops (Enter / Backspace / Delete / arrows)
    // ------------------------------------------------------------------

    fun insertLineBreak(lineId: String) {
        val state = _state.value
        val index = state.lines.indexOfFirst { it.id == lineId }
        if (index < 0) return
        val line = state.lines[index]
        val pos = if (state.activeLineId == lineId) (state.caret?.min ?: 0) else 0
        flushBurst()
        pushFullSnapshot()
        val (left, right) = splitLineAt(line, pos)
        val out = state.lines.toMutableList()
        out[index] = left
        out.add(index + 1, right)
        publish(out, activeLineId = right.id, caret = Caret(0, 0), focus = right.id to Caret(0, 0))
    }

    /** Backspace at start of a line: merge it into the previous line. */
    fun mergeWithPrevious(lineId: String): Boolean {
        val state = _state.value
        val index = state.lines.indexOfFirst { it.id == lineId }
        if (index <= 0) return false
        val prev = state.lines[index - 1]
        val cur = state.lines[index]
        flushBurst()
        pushFullSnapshot()
        val merged = mergeLines(prev, cur)
        val out = state.lines.toMutableList()
        out.removeAt(index)
        out[index - 1] = merged
        val caretPos = prev.plainText.length
        publish(out, activeLineId = prev.id, caret = Caret(caretPos, caretPos), focus = prev.id to Caret(caretPos, caretPos))
        return true
    }

    /** Delete at end of a line: merge the following line into it. */
    fun mergeWithNext(lineId: String): Boolean {
        val state = _state.value
        val index = state.lines.indexOfFirst { it.id == lineId }
        if (index < 0 || index >= state.lines.size - 1) return false
        val cur = state.lines[index]
        val next = state.lines[index + 1]
        flushBurst()
        pushFullSnapshot()
        val merged = mergeLines(cur, next)
        val out = state.lines.toMutableList()
        out.removeAt(index + 1)
        out[index] = merged
        val caretPos = cur.plainText.length
        publish(out, activeLineId = cur.id, caret = Caret(caretPos, caretPos), focus = cur.id to Caret(caretPos, caretPos))
        return true
    }

    /** Move focus one line up/down (Arrow keys); [extend] grows the selection. */
    fun moveActiveLine(delta: Int, extend: Boolean): Boolean {
        val state = _state.value
        val active = state.activeLineId ?: return false
        val index = state.lines.indexOfFirst { it.id == active }
        if (index < 0) return false
        val target = index + delta
        if (target < 0 || target >= state.lines.size) return false
        val targetLine = state.lines[target]
        val column = (state.caret?.min ?: 0)
        val caret = Caret(minOf(column, targetLine.plainText.length), minOf(column, targetLine.plainText.length))
        val newAnchor = if (extend) (state.anchorLineId ?: active) else targetLine.id
        _state.update {
            it.copy(
                activeLineId = targetLine.id,
                caret = caret,
                anchorLineId = newAnchor,
                focusLineId = targetLine.id,
                focusRequest = targetLine.id to caret
            )
        }
        return true
    }

    fun caretToLineStart(lineId: String) = moveCaretOnLine(lineId, 0)
    fun caretToLineEnd(lineId: String) {
        val line = _state.value.lines.firstOrNull { it.id == lineId } ?: return
        moveCaretOnLine(lineId, line.plainText.length)
    }

    private fun moveCaretOnLine(lineId: String, pos: Int) {
        _state.update { s ->
            val caret = Caret(pos, pos)
            s.copy(activeLineId = lineId, caret = caret, focusLineId = lineId, focusRequest = lineId to caret)
        }
    }

    // ------------------------------------------------------------------
    // Whole-line / range formatting ops
    // ------------------------------------------------------------------

    /** Lines covered by the current selection (anchor..focus), else active. */
    fun selectedLineIds(): List<String> {
        val s = _state.value
        val a = s.anchorLineId
        val f = s.focusLineId
        if (a == null || f == null || a == f) {
            return s.activeLineId?.let { listOf(it) } ?: emptyList()
        }
        val ia = s.lines.indexOfFirst { it.id == a }
        val ib = s.lines.indexOfFirst { it.id == f }
        if (ia < 0 || ib < 0) return s.activeLineId?.let { listOf(it) } ?: emptyList()
        val lo = minOf(ia, ib)
        val hi = maxOf(ia, ib)
        return s.lines.subList(lo, hi + 1).map { it.id }
    }

    fun selectAllLines() {
        _state.update { s ->
            if (s.lines.isEmpty()) s
            else s.copy(
                anchorLineId = s.lines.first().id,
                focusLineId = s.lines.last().id,
                activeLineId = s.lines.last().id,
                caret = Caret(0, 0)
            )
        }
    }

    fun selectSingleLine(lineId: String) {
        _state.update { s ->
            if (s.lines.none { it.id == lineId }) s
            else s.copy(anchorLineId = lineId, focusLineId = lineId)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(anchorLineId = null, focusLineId = null) }
    }

    private fun mapSelectedLines(mutate: (EditorLine) -> EditorLine) {
        val ids = selectedLineIds().toSet()
        if (ids.isEmpty()) return
        flushBurst()
        pushFullSnapshot()
        val s = _state.value
        val out = s.lines.map { if (it.id in ids) mutate(it) else it }
        publish(out, caret = s.caret)
        _state.update { it.copy(anchorLineId = null, focusLineId = null) }
    }

    fun setLineColor(color: HighlightColor?) = mapSelectedLines { setLineColor(it, color) }

    fun toggleList(type: ListType) = mapSelectedLines { toggleListType(it, type) }

    fun indentLines(delta: Int) = mapSelectedLines { changeIndent(it, delta) }

    fun toggleChecked(lineId: String) {
        val s = _state.value
        val index = s.lines.indexOfFirst { it.id == lineId }
        if (index < 0) return
        val line = s.lines[index]
        if (line.listType != ListType.CHECK) return
        flushBurst()
        pushFullSnapshot()
        val out = s.lines.toMutableList()
        out[index] = line.copy(checked = !line.checked)
        publish(out, activeLineId = lineId, caret = s.caret)
    }

    /**
     * Inline "Mark" action on the active line's text selection.
     * Cross-line selections fall back to whole-line highlight of every
     * selected line (same fallback rule as the original spec).
     */
    fun markInlineSelection(color: HighlightColor) {
        val s = _state.value
        val ids = selectedLineIds()
        if (ids.size > 1) {
            setLineColor(color)
            return
        }
        val lineId = s.activeLineId ?: return
        val caret = s.caret ?: Caret(0, 0)
        if (caret.max <= caret.min) {
            setLineColor(color) // no inline selection -> whole-line color fallback
            return
        }
        val line = s.lines.firstOrNull { it.id == lineId } ?: return
        val textLen = line.plainText.length
        if (caret.min >= textLen) return
        flushBurst()
        pushFullSnapshot()
        val newLine = markRange(line, caret.min, caret.max.coerceAtMost(textLen), color)
        val out = s.lines.map { if (it.id == lineId) newLine else it }
        publish(out, caret = s.caret)
    }

    /** Removes inline highlights inside the active line's selection. */
    fun clearInlineSelection() {
        val s = _state.value
        val lineId = s.activeLineId ?: return
        val caret = s.caret ?: Caret(0, 0)
        if (caret.max <= caret.min) return
        val line = s.lines.firstOrNull { it.id == lineId } ?: return
        flushBurst()
        pushFullSnapshot()
        val newLine = clearMarkRange(line, caret.min, caret.max.coerceAtMost(line.plainText.length))
        val out = s.lines.map { if (it.id == lineId) newLine else it }
        publish(out, caret = s.caret)
    }

    /** Removes whole-line colors, inline highlights and list formatting. */
    fun clearAllFormatting() = mapSelectedLines { line ->
        line.copy(
            lineColor = null,
            spans = line.spans.map { InlineSpan(it.text, false, it.highlightColor) },
            listType = ListType.NONE,
            indent = 0,
            checked = false
        )
    }

    // ------------------------------------------------------------------
    // Undo / Redo
    // ------------------------------------------------------------------

    private sealed class UndoEntry {
        /** Line snapshots captured when a typing burst began (per-line diff). */
        class Burst(val touched: Map<String, EditorLine>, val carets: Map<String, Caret>) : UndoEntry()

        /** Document snapshot pushed before structural/formatting ops. */
        class Full(
            val lines: List<EditorLine>,
            val activeLineId: String?,
            val caret: Caret?,
            val anchor: String?
        ) : UndoEntry()
    }

    private fun pushUndo(entry: UndoEntry) {
        undoStack.addLast(entry)
        while (undoStack.size > undoLimit) undoStack.removeFirst()
    }

    private fun pushRedo(entry: UndoEntry) {
        redoStack.addLast(entry)
        while (redoStack.size > undoLimit) redoStack.removeFirst()
    }

    private fun pushFullSnapshot() {
        val s = _state.value
        pushUndo(UndoEntry.Full(s.lines, s.activeLineId, s.caret, s.anchorLineId))
        redoStack.clear()
    }

    /** Open a burst for the line about to change (called before the edit). */
    private fun ensureBurst(line: EditorLine) {
        if (burstActive && burstLineId == line.id) return
        flushBurst()
        burstActive = true
        burstLineId = line.id
        burstBefore = line
        burstCaret = _state.value.caret?.takeIf { _state.value.activeLineId == line.id }
    }

    private fun scheduleBurstFlush() {
        burstJob?.cancel()
        burstJob = scope.launch {
            delay(900)
            flushBurst()
        }
    }

    /** Push the current typing burst as one undo entry. */
    private fun flushBurst() {
        burstJob?.cancel()
        burstJob = null
        if (!burstActive) return
        val before = burstBefore ?: run {
            burstActive = false
            burstLineId = null
            burstCaret = null
            return
        }
        burstActive = false
        burstLineId = null
        burstBefore = null
        val caret = burstCaret
        burstCaret = null
        pushUndo(
            UndoEntry.Burst(
                mapOf(before.id to before),
                if (caret != null) mapOf(before.id to caret) else emptyMap()
            )
        )
        redoStack.clear()
    }

    fun undo() {
        flushBurst()
        val entry = undoStack.removeLastOrNull() ?: return
        val s = _state.value
        when (entry) {
            is UndoEntry.Burst -> {
                val current = currentBurstLines(s, entry.touched.keys)
                applyBurstEntry(entry)
                pushRedo(UndoEntry.Burst(current, currentCaretMap(s, entry.touched.keys)))
            }
            is UndoEntry.Full -> {
                pushRedo(UndoEntry.Full(s.lines, s.activeLineId, s.caret, s.anchorLineId))
                applyFullEntry(entry)
            }
        }
    }

    fun redo() {
        val entry = redoStack.removeLastOrNull() ?: return
        val s = _state.value
        when (entry) {
            is UndoEntry.Burst -> {
                val current = currentBurstLines(s, entry.touched.keys)
                applyBurstEntry(entry)
                pushUndo(UndoEntry.Burst(current, currentCaretMap(s, entry.touched.keys)))
            }
            is UndoEntry.Full -> {
                pushUndo(UndoEntry.Full(s.lines, s.activeLineId, s.caret, s.anchorLineId))
                applyFullEntry(entry)
            }
        }
    }

    private fun currentBurstLines(s: DocState, ids: Set<String>): Map<String, EditorLine> {
        val map = linkedMapOf<String, EditorLine>()
        for (line in s.lines) if (line.id in ids) map[line.id] = line
        return map
    }

    private fun currentCaretMap(s: DocState, ids: Set<String>): Map<String, Caret> =
        if (s.activeLineId != null && s.activeLineId in ids && s.caret != null) {
            mapOf(s.activeLineId to s.caret!!)
        } else {
            emptyMap()
        }

    private fun applyBurstEntry(entry: UndoEntry.Burst) {
        flushBurst()
        val s = _state.value
        val out = s.lines.map { entry.touched[it.id] ?: it }
        val active = s.activeLineId
        val caret = if (active != null) entry.carets[active] else null
        publish(
            out,
            activeLineId = active,
            caret = caret ?: Caret(0, 0),
            focus = active?.let { it to (caret ?: Caret(0, 0)) },
            anchor = null
        )
    }

    private fun applyFullEntry(entry: UndoEntry.Full) {
        flushBurst()
        val active = entry.activeLineId ?: entry.lines.firstOrNull()?.id
        val caret = entry.caret ?: Caret(0, 0)
        publish(
            entry.lines,
            activeLineId = active,
            caret = caret,
            focus = active?.let { it to caret },
            anchor = entry.anchor
        )
    }

    // ------------------------------------------------------------------
    // State publishing + autosave
    // ------------------------------------------------------------------

    private fun publish(
        lines: List<EditorLine>,
        activeLineId: String? = null,
        caret: Caret? = null,
        focus: Pair<String, Caret>? = null,
        anchor: String? = null
    ) {
        _state.update {
            val active = activeLineId ?: it.activeLineId
            it.copy(
                version = it.version + 1,
                lines = lines,
                numbers = computeNumbers(lines),
                activeLineId = active,
                caret = caret ?: it.caret,
                focusLineId = focus?.first ?: it.focusLineId,
                anchorLineId = anchor ?: it.anchorLineId,
                focusRequest = focus ?: it.focusRequest,
                empty = lines.isEmpty() || lines.all { l -> l.isEmptyLine }
            )
        }
        markDirty()
    }

    private fun markDirty() {
        if (_saveStatus.value != SaveStatus.SAVING) _saveStatus.value = SaveStatus.DIRTY
        scheduleAutosave()
    }

    private fun scheduleAutosave() {
        if (disposed) return
        saveJob?.cancel()
        val debounce = if (PlatformInfo.isLowMemoryDevice()) 1500L else 600L
        saveJob = scope.launch {
            delay(debounce)
            flushToDb()
        }
    }

    /** Persist the current document to SQLite. Safe to call from anywhere. */
    suspend fun flushToDb() {
        saveJob?.cancel()
        val s = _state.value
        if (s.version == lastSavedVersion) return
        if (saveRunning) return // running save re-schedules when done
        saveRunning = true
        _saveStatus.value = SaveStatus.SAVING
        val versionToSave = s.version
        val snapshot = s
        try {
            val noteId = withContext(AppDispatchers.io) {
                onPersist(
                    NoteDocument(
                        id = snapshot.noteId,
                        title = TextCodec.titleFromLines(snapshot.lines),
                        lines = snapshot.lines,
                        isPinned = snapshot.isPinned,
                        sourcePath = snapshot.sourcePath,
                        lineEnding = snapshot.lineEnding,
                        createdAt = 0L,
                        modifiedAt = currentTimeMillis()
                    )
                )
            }
            _state.update { it.copy(noteId = noteId) }
            if (versionToSave >= lastSavedVersion) lastSavedVersion = versionToSave
            hasEverSaved = true
            _saveStatus.value = SaveStatus.SAVED
            _events.tryEmit(SessionEvent.DbSaved(noteId, versionToSave))
        } catch (t: Throwable) {
            _saveStatus.value = SaveStatus.ERROR
            _events.tryEmit(SessionEvent.DbSaveFailed(t.message ?: "Database error"))
        } finally {
            saveRunning = false
            if (_state.value.version != lastSavedVersion && !disposed) scheduleAutosave()
        }
    }

    // ------------------------------------------------------------------
    // File save / open (native dialogs via FilePickerBridge)
    // ------------------------------------------------------------------

    /** Ctrl+S: writes back to sourcePath if present, otherwise Save-As. */
    suspend fun requestFileSave() {
        val path = _state.value.sourcePath
        if (path != null && !path.startsWith("content://")) {
            writeToFile(path)
            _events.tryEmit(SessionEvent.FileSaved(path, false))
        } else {
            requestSaveAs()
        }
    }

    suspend fun requestSaveAs() {
        try {
            val picked = FilePickerBridge.pickSaveLocation(suggestedName()) ?: return
            val text = TextCodec.joinLines(_state.value.lines, _state.value.lineEnding)
            withContext(AppDispatchers.io) { picked.writeText(text) }
            _state.update { it.copy(sourcePath = picked.displayName) }
            settings.addRecentFile(picked.displayName)
            flushToDb()
            _events.tryEmit(SessionEvent.FileSaved(picked.displayName, true))
        } catch (t: Throwable) {
            _events.tryEmit(SessionEvent.FileFailed(t.message ?: "Save failed"))
        }
    }

    /** Native open dialog; replaces this session's document. */
    suspend fun importFromFile(): Boolean {
        try {
            val picked = FilePickerBridge.pickOpenFile() ?: return false
            val raw = withContext(AppDispatchers.io) { picked.readText() }
            val lineEnding = TextCodec.detectLineEnding(raw)
            val rawLines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val lines = rawLines.map { EditorLine.plain(randomLineId(), it) }
            flushBurst()
            pushFullSnapshot()
            publish(
                lines.ifEmpty { listOf(newBlankLine()) },
                caret = Caret(0, 0)
            )
            _state.update {
                it.copy(
                    lineEnding = lineEnding,
                    sourcePath = picked.displayName,
                    anchorLineId = null,
                    focusLineId = null
                )
            }
            settings.addRecentFile(picked.displayName)
            _events.tryEmit(SessionEvent.FileOpened(picked.displayName, TextCodec.titleFromLines(lines)))
            return true
        } catch (t: Throwable) {
            _events.tryEmit(SessionEvent.FileFailed(t.message ?: "Open failed"))
            return false
        }
    }

    private fun suggestedName(): String {
        val title = TextCodec.titleFromLines(_state.value.lines)
            .replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(40)
        return "${title.ifBlank { "Untitled" }}.txt"
    }

    private suspend fun writeToFile(path: String) {
        val text = TextCodec.joinLines(_state.value.lines, _state.value.lineEnding)
        withContext(AppDispatchers.io) { FileIO.writeText(path, text) }
    }

    fun copyDocumentText() {
        val text = TextCodec.joinLines(_state.value.lines, LineEnding.LF)
        if (text.isNotEmpty()) ClipboardBridge.copy(text)
    }

    fun copyText(text: String) {
        if (text.isNotEmpty()) ClipboardBridge.copy(text)
    }

    fun setPinned(pinned: Boolean) {
        _state.update { it.copy(version = it.version + 1, isPinned = pinned) }
        markDirty()
    }

    fun setLineEnding(lineEnding: LineEnding) {
        _state.update { it.copy(version = it.version + 1, lineEnding = lineEnding) }
        markDirty()
    }

    /**
     * External full-document replacement (find & replace all / replace one).
     * Pushed through the same snapshot/undo pipeline as regular edits.
     */
    fun replaceAllLinesExternal(newLines: List<EditorLine>) {
        flushBurst()
        pushFullSnapshot()
        val s = _state.value
        val active = s.activeLineId?.takeIf { id -> newLines.any { it.id == id } }
        val caret = active?.let { s.caret } ?: Caret(0, 0)
        publish(
            newLines,
            activeLineId = active,
            caret = caret,
            anchor = null
        )
    }

    /** Consumed by EditorScreen after it handled a focus request. */
    fun consumeFocusRequest(): Pair<String, Caret>? {
        val s = _state.value
        val req = s.focusRequest ?: return null
        _state.update { it.copy(focusRequest = null) }
        return req
    }

    /** True when the document holds no text and has no DB row yet. */
    fun isPristineUnsaved(): Boolean =
        !hasEverSaved && _state.value.noteId == null && _state.value.lines.all { it.isEmptyLine }

    fun dispose() {
        if (disposed) return
        disposed = true
        burstJob?.cancel()
        saveJob?.cancel()
    }
}
