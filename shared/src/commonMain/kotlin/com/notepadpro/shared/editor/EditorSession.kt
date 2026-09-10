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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
    private val ioDispatcher: CoroutineDispatcher = AppDispatchers.io,
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
    // Only the debounce delay is cancellable by subsequent edits/explicit flushes.
    // Once it expires, the DB write must be allowed to finish.
    private var autosaveDelayJob: Job? = null
    private var saveRunning = false

    private var disposed = false

    /** Reading is a view preference, not a document edit or an undo entry. */
    var isReadOnly: Boolean = false
        private set

    fun setReadOnly(readOnly: Boolean) {
        if (isReadOnly == readOnly) return
        flushBurst()
        isReadOnly = readOnly
    }

    // Only the active row's measured wrap range is needed. Never persist visual
    // line breaks: they change with window width, font size and word wrapping.
    private data class VisualLine(val id: String, val text: String, val range: Caret)
    private var visualLine: VisualLine? = null

    fun onVisualLineChanged(lineId: String, start: Int, end: Int) {
        val state = _state.value
        if (state.activeLineId != lineId) return
        val text = state.lines.firstOrNull { it.id == lineId }?.plainText ?: return
        visualLine = VisualLine(lineId, text, Caret(start.coerceIn(0, text.length), end.coerceIn(0, text.length)))
    }

    fun canMoveToAdjacentParagraph(delta: Int): Boolean {
        val state = _state.value
        val line = state.lines.firstOrNull { it.id == state.activeLineId } ?: return false
        val visual = visualLine?.takeIf { it.id == line.id && it.text == line.plainText } ?: return true
        return if (delta < 0) visual.range.min == 0 else visual.range.max == line.plainText.length
    }

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

    /** Text/selection change from a row; normalize clipboard line endings first. */
    fun applyTextChange(lineId: String, newText: String, selStart: Int, selEnd: Int) {
        if (disposed) return
        if (isReadOnly) {
            val text = _state.value.lines.firstOrNull { it.id == lineId }?.plainText ?: return
            if (newText == text) moveCaretOnly(lineId, selStart.coerceIn(0, text.length), selEnd.coerceIn(0, text.length))
            return
        }
        val text = TextCodec.normalizeLineEndings(newText)
        fun offset(rawOffset: Int): Int = if (text == newText) rawOffset.coerceIn(0, text.length)
            else TextCodec.normalizeLineEndings(newText.take(rawOffset.coerceIn(0, newText.length))).length
        val start = offset(selStart)
        val end = offset(selEnd)
        if ('\n' in text) {
            handleMultilineInput(lineId, text, end)
            return
        }
        val state = _state.value
        val index = state.lines.indexOfFirst { it.id == lineId }
        if (index < 0) return
        val line = state.lines[index]
        val oldText = line.plainText
        if (oldText == text) {
            moveCaretOnly(lineId, start, end)
            return
        }
        ensureBurst(line)
        val prefix = commonPrefixLen(oldText, text)
        val suffix = guardedSuffixLen(oldText, text, prefix)
        val newSpans = remapSpansForEdit(line.spans, oldText, text, prefix, suffix)
        val out = state.lines.toMutableList()
        out[index] = line.copy(spans = newSpans)
        publish(out, activeLineId = lineId, caret = Caret(start, end))
        scheduleBurstFlush()
    }

    private fun moveCaretOnly(lineId: String, selStart: Int, selEnd: Int) {
        _state.update { s ->
            s.copy(
                activeLineId = lineId,
                caret = Caret(selStart, selEnd),
                focusLineId = lineId,
                anchorLineId = null,
                numbers = if (s.activeLineId == lineId) s.numbers else computeNumbers(s.lines, lineId)
            )
        }
    }

    fun onLineFocused(lineId: String) {
        _state.update { s ->
            if (s.activeLineId == lineId || s.lines.none { it.id == lineId }) s
            else s.copy(activeLineId = lineId, caret = Caret(0, 0), numbers = computeNumbers(s.lines, lineId))
        }
    }

    private fun guardedSuffixLen(a: String, b: String, prefix: Int): Int {
        val max = minOf(a.length - prefix, b.length - prefix)
        var i = 0
        while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }

    /** Preserve prefix/suffix text and rich spans when a paste creates real lines. */
    private fun handleMultilineInput(lineId: String, newText: String, selectionEnd: Int) {
        val state = _state.value
        val index = state.lines.indexOfFirst { it.id == lineId }
        if (index < 0) return
        val line = state.lines[index]
        val oldText = line.plainText
        val prefix = commonPrefixLen(oldText, newText)
        val suffix = guardedSuffixLen(oldText, newText, prefix)
        val spans = remapSpansForEdit(line.spans, oldText, newText, prefix, suffix)
        val inserted = splitRichLines(line, spans)
        var row = 0
        var column = selectionEnd.coerceIn(0, newText.length)
        while (row < inserted.lastIndex && column > inserted[row].plainText.length) {
            column -= inserted[row].plainText.length + 1
            row++
        }
        val target = inserted[row]
        val caret = Caret(column, column)
        flushBurst()
        pushFullSnapshot()
        val out = state.lines.toMutableList()
        out.removeAt(index)
        out.addAll(index, inserted)
        publish(out, activeLineId = target.id, caret = caret, focus = target.id to caret)
    }

    // ------------------------------------------------------------------
    // Line navigation ops (Enter / Backspace / Delete / arrows)
    // ------------------------------------------------------------------

    fun insertLineBreak(lineId: String) {
        if (disposed || isReadOnly) return
        val state = _state.value
        val index = state.lines.indexOfFirst { it.id == lineId }
        if (index < 0) return
        val line = state.lines[index]
        if (line.listType != ListType.NONE && line.plainText.isBlank()) {
            // Enter on an empty list item exits the list instead of adding
            // another empty numbered row indefinitely.
            flushBurst()
            pushFullSnapshot()
            val out = state.lines.toMutableList()
            out[index] = line.copy(listType = ListType.NONE, indent = 0, checked = false)
            publish(out, activeLineId = lineId, caret = Caret(0, 0), focus = lineId to Caret(0, 0))
            return
        }
        val text = line.plainText
        val selection = state.caret?.takeIf { state.activeLineId == lineId } ?: Caret(0, 0)
        val start = selection.min.coerceIn(0, text.length)
        val end = selection.max.coerceIn(start, text.length)
        handleMultilineInput(lineId, text.take(start) + "\n" + text.substring(end), start + 1)
    }

    /** Backspace at start of a line: merge it into the previous line. */
    fun mergeWithPrevious(lineId: String): Boolean {
        if (disposed || isReadOnly) return false
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
        if (disposed || isReadOnly) return false
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
        val newAnchor = if (extend) (state.anchorLineId ?: active) else null
        _state.update {
            it.copy(
                activeLineId = targetLine.id,
                caret = caret,
                anchorLineId = newAnchor,
                focusLineId = targetLine.id,
                focusRequest = targetLine.id to caret,
                numbers = computeNumbers(state.lines, targetLine.id)
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
            s.copy(activeLineId = lineId, caret = caret, focusLineId = lineId, focusRequest = lineId to caret,
                numbers = computeNumbers(s.lines, lineId))
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
                caret = Caret(0, 0),
                numbers = computeNumbers(s.lines, s.lines.last().id)
            )
        }
    }

    fun selectSingleLine(lineId: String) {
        _state.update { s ->
            if (s.lines.none { it.id == lineId }) s
            else s.copy(activeLineId = lineId, anchorLineId = lineId, focusLineId = lineId,
                numbers = computeNumbers(s.lines, lineId))
        }
    }

    fun clearSelection() {
        _state.update { it.copy(anchorLineId = null, focusLineId = null) }
    }

    private fun mapSelectedLines(mutate: (EditorLine) -> EditorLine) {
        if (disposed || isReadOnly) return
        val ids = selectedLineIds().toSet()
        if (ids.isEmpty()) return
        flushBurst()
        pushFullSnapshot()
        val s = _state.value
        val out = s.lines.map { if (it.id in ids) mutate(it) else it }
        publish(out, caret = s.caret)
        _state.update { it.copy(anchorLineId = null, focusLineId = null) }
    }

    /** Color selected characters, otherwise only the caret's measured visual line. */
    fun setLineColor(color: HighlightColor?) {
        if (_state.value.anchorLineId != null) setParagraphColor(color)
        else colorTextRange(color, includeParagraphColor = true)
    }

    /** Explicit whole-paragraph/gutter-selection action, separate from text coloring. */
    fun setParagraphColor(color: HighlightColor?) = mapSelectedLines { setLineColor(it, color) }

    fun toggleList(type: ListType) {
        val state = _state.value
        val ids = selectedLineIds().toSet()
        val selected = state.lines.filter { it.id in ids }
        if (selected.isEmpty()) return
        val items = selected.filter { it.plainText.isNotBlank() || selected.size == 1 }
        val remove = type == ListType.NONE || (items.isNotEmpty() && items.all { it.listType == type })
        mapSelectedLines { line ->
            val target = if (remove || (selected.size > 1 && line.plainText.isBlank())) ListType.NONE else type
            line.copy(listType = target, checked = false, indent = if (target == ListType.NONE) 0 else line.indent)
        }
    }

    fun indentLines(delta: Int) = mapSelectedLines { changeIndent(it, delta) }

    fun toggleChecked(lineId: String) {
        if (disposed || isReadOnly) return
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

    fun markInlineSelection(color: HighlightColor) = colorTextRange(color, includeParagraphColor = false)

    fun clearInlineSelection() = colorTextRange(null, includeParagraphColor = false)

    private fun colorTextRange(color: HighlightColor?, includeParagraphColor: Boolean) {
        if (disposed || isReadOnly) return
        val state = _state.value
        if (state.anchorLineId != null) {
            mapSelectedLines { line ->
                if (color == null) clearMarkRange(line, 0, line.plainText.length)
                else markRange(line, 0, line.plainText.length, color)
            }
            return
        }
        val line = state.lines.firstOrNull { it.id == state.activeLineId } ?: return
        val text = line.plainText
        val selection = state.caret?.takeIf { it.min != it.max }
            ?: visualLine?.takeIf { it.id == line.id && it.text == text }?.range
            ?: return // No measured row: never accidentally paint a whole paragraph.
        val start = selection.min.coerceIn(0, text.length)
        val end = selection.max.coerceIn(start, text.length)
        if (start == end) return
        // Old whole-paragraph backgrounds must not keep showing through a
        // partially cleared range. Preserve their color outside the edited range.
        val paragraphColor = line.lineColor
        val base = if (includeParagraphColor && paragraphColor != null) {
            line.copy(lineColor = null, spans = line.spans.map { span ->
                if (span.highlighted) span else span.copy(highlighted = true, highlightColor = paragraphColor)
            })
        } else line
        val colored = if (color == null) clearMarkRange(base, start, end) else markRange(base, start, end, color)
        if (colored == line) return
        flushBurst()
        pushFullSnapshot()
        publish(state.lines.map { if (it.id == line.id) colored else it }, caret = state.caret)
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
        if (disposed || isReadOnly) return
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
        if (disposed || isReadOnly) return
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
                numbers = computeNumbers(lines, active),
                activeLineId = active,
                caret = caret ?: it.caret,
                focusLineId = focus?.first ?: it.focusLineId,
                anchorLineId = anchor,
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
        autosaveDelayJob?.cancel()
        val debounce = if (PlatformInfo.isLowMemoryDevice()) 1500L else 600L
        autosaveDelayJob = scope.launch {
            delay(debounce)
            // Detach before flushing: flushToDb cancels pending delays, and
            // cancelling this coroutine here would cancel its own DB write.
            autosaveDelayJob = null
            flushToDb()
        }
    }

    /** Persist the current document to SQLite. Safe to call from anywhere. */
    suspend fun flushToDb() {
        autosaveDelayJob?.cancel()
        autosaveDelayJob = null
        val s = _state.value
        if (s.version == lastSavedVersion) return
        if (saveRunning) return // running save re-schedules when done
        saveRunning = true
        _saveStatus.value = SaveStatus.SAVING
        val versionToSave = s.version
        val snapshot = s
        try {
            val noteId = withContext(ioDispatcher) {
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
            _saveStatus.value = if (_state.value.version == lastSavedVersion) {
                SaveStatus.SAVED
            } else {
                SaveStatus.DIRTY
            }
            _events.tryEmit(SessionEvent.DbSaved(noteId, versionToSave))
        } catch (cancelled: CancellationException) {
            // Cancellation is control flow, not a storage failure. Keep the
            // document dirty so an active session can retry without an alert.
            _saveStatus.value = SaveStatus.DIRTY
            throw cancelled
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            _events.tryEmit(SessionEvent.FileFailed(t.message ?: "Save failed"))
        }
    }

    /** Native open dialog; replaces this session's document. */
    suspend fun importFromFile(): Boolean {
        // A fresh reading tab may load a file; never replace an existing note
        // while the document is read-only.
        if (disposed || (isReadOnly && !isPristineUnsaved())) return false
        try {
            val picked = FilePickerBridge.pickOpenFile() ?: return false
            if (disposed || (isReadOnly && !isPristineUnsaved())) return false
            val raw = withContext(AppDispatchers.io) { picked.readText() }
            if (disposed || (isReadOnly && !isPristineUnsaved())) return false
            val lineEnding = TextCodec.detectLineEnding(raw)
            val rawLines = TextCodec.normalizeLineEndings(raw).split('\n')
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        if (disposed || isReadOnly) return
        _state.update { it.copy(version = it.version + 1, lineEnding = lineEnding) }
        markDirty()
    }

    /**
     * External full-document replacement (find & replace all / replace one).
     * Pushed through the same snapshot/undo pipeline as regular edits.
     */
    fun replaceAllLinesExternal(newLines: List<EditorLine>) {
        if (disposed || isReadOnly) return
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
        autosaveDelayJob?.cancel()
    }
}
