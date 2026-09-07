package com.notepadpro.shared.editor

import com.notepadpro.shared.FindUiState
import com.notepadpro.shared.domain.model.TextCodec
import com.notepadpro.shared.platform.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reactive find/replace state, independent of Compose and the database. Main-thread API. */
class FindReplaceController(
    private val scope: CoroutineScope,
    private val searchDispatcher: CoroutineDispatcher = AppDispatchers.default
) {
    private val _state = MutableStateFlow(FindUiState())
    val state: StateFlow<FindUiState> = _state
    private var session: EditorSession? = null
    private var open = false
    private var documentJob: Job? = null
    private var searchJob: Job? = null
    private var requestedVersion: Long? = null
    private var matchedVersion: Long? = null
    private data class Anchor(val lineId: String, val lineIndex: Int, val start: Int)
    private var anchor: Anchor? = null

    fun setSession(value: EditorSession?) {
        if (session === value) return
        documentJob?.cancel()
        session = value
        requestedVersion = null
        matchedVersion = null
        refresh(resetAnchor = true)
        if (value != null) documentJob = scope.launch {
            value.state.map { it.version }.distinctUntilChanged().drop(1).collect { version ->
                if (session === value && open && version != requestedVersion) refresh()
            }
        }
    }

    fun setOpen(value: Boolean) {
        if (open == value) return
        open = value
        refresh(resetAnchor = true)
    }

    fun setQuery(value: String) {
        if (_state.value.query == value) return
        _state.update { it.copy(query = value) }
        refresh(resetAnchor = true)
    }

    fun setReplacement(value: String) {
        _state.update { it.copy(replaceQuery = value) }
    }

    fun setCaseSensitive(value: Boolean) {
        if (_state.value.caseSensitive == value) return
        _state.update { it.copy(caseSensitive = value) }
        refresh(resetAnchor = true)
    }

    fun next() = move(1)
    fun previous() = move(-1)

    private fun move(delta: Int) {
        val state = _state.value
        if (state.matches.isEmpty() || state.searching) return
        val index = (state.currentIndex + delta + state.matches.size) % state.matches.size
        val match = state.matches[index]
        anchor = Anchor(match.lineId, match.lineIndex, match.start)
        _state.update { it.copy(currentIndex = index) }
    }

    private fun refresh(resetAnchor: Boolean = false, immediate: Boolean = false) {
        searchJob?.cancel()
        if (resetAnchor) anchor = null
        val target = session
        val query = _state.value.query
        val matchCase = _state.value.caseSensitive
        requestedVersion = target?.state?.value?.version
        matchedVersion = null
        val search = open && target != null && query.isNotEmpty()
        _state.update {
            it.copy(matches = emptyList(), currentIndex = 0, searching = search,
                replacedCount = if (resetAnchor) 0 else it.replacedCount)
        }
        if (!search || target == null) return
        searchJob = scope.launch {
            if (!immediate) delay(180)
            val snapshot = target.state.value
            requestedVersion = snapshot.version
            val matches = withContext(searchDispatcher) {
                FindReplaceEngine.findAll(snapshot.lines, query, matchCase)
            }
            // Never publish results from another tab, query or document revision.
            if (session !== target || !open || _state.value.query != query ||
                _state.value.caseSensitive != matchCase || target.state.value.version != snapshot.version
            ) return@launch
            val position = anchor
            val lineIndex = position?.let { p ->
                snapshot.lines.indexOfFirst { it.id == p.lineId }.takeIf { it >= 0 } ?: p.lineIndex
            }
            val index = if (position == null || lineIndex == null) 0 else {
                matches.indexOfFirst { it.lineIndex > lineIndex || (it.lineIndex == lineIndex && it.start >= position.start) }
                    .coerceAtLeast(0)
            }
            val current = matches.getOrNull(index)
            anchor = current?.let { Anchor(it.lineId, it.lineIndex, it.start) }
            matchedVersion = snapshot.version
            _state.update { it.copy(matches = matches, currentIndex = index, searching = false) }
        }
    }

    fun replaceCurrent() {
        val target = session ?: return
        val state = _state.value
        if (!open || state.searching) return
        val match = state.matches.getOrNull(state.currentIndex) ?: return
        val snapshot = target.state.value
        if (snapshot.version != matchedVersion) {
            refresh()
            return
        }
        val replacement = TextCodec.normalizeLineEndings(state.replaceQuery)
        val (lines, caret) = FindReplaceEngine.replaceOne(snapshot.lines, match, replacement)
        val targetIndex = match.lineIndex + replacement.count { it == '\n' }
        val targetLine = lines.getOrNull(targetIndex) ?: return
        // Skip newly inserted text when moving to the next original occurrence.
        anchor = Anchor(targetLine.id, targetIndex, caret)
        if (lines != snapshot.lines) target.replaceAllLinesExternal(lines)
        _state.update { it.copy(replacedCount = it.replacedCount + 1) }
        refresh(immediate = true)
    }

    fun replaceAll() {
        val target = session ?: return
        val state = _state.value
        if (!open || state.searching || state.matches.isEmpty()) return
        val snapshot = target.state.value
        if (snapshot.version != matchedVersion) {
            refresh()
            return
        }
        val (lines, count) = FindReplaceEngine.replaceAll(snapshot.lines, state.query, state.replaceQuery, state.caseSensitive)
        if (count == 0) return
        if (lines != snapshot.lines) target.replaceAllLinesExternal(lines)
        anchor = null
        _state.update { it.copy(replacedCount = it.replacedCount + count) }
        refresh(immediate = true)
    }
}
