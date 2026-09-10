package com.notepadpro.shared

import com.notepadpro.shared.data.db.NoteStore
import com.notepadpro.shared.data.settings.SettingsRepository
import com.notepadpro.shared.data.settings.ThemeMode
import com.notepadpro.shared.domain.model.NoteRow
import com.notepadpro.shared.domain.model.NoteDocument
import com.notepadpro.shared.editor.EditorSession
import com.notepadpro.shared.editor.FindMatch
import com.notepadpro.shared.editor.FindReplaceController
import com.notepadpro.shared.editor.SessionEvent
import com.notepadpro.shared.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A tab = an editor session (possibly bound to a DB note row). */
data class Tab(
    val localId: Long,
    val session: EditorSession,
    val createdAt: Long
)

/** Settings that the UI reads reactively (persisted via SettingsRepository). */
data class UiPrefs(
    val fontSizeSp: Float,
    val wordWrap: Boolean,
    val themeMode: ThemeMode,
    val reduceMotion: Boolean,
    val readingMode: Boolean = false
)

/** App-level UI switches (panel visibility etc.). */
data class AppUiState(
    val sidebarOpen: Boolean = false,
    val findOpen: Boolean = false,
    val replaceMode: Boolean = false,
    val findFocusRequest: Long = 0,
    val extractOpen: Boolean = false,
    val settingsOpen: Boolean = false,
    val aboutOpen: Boolean = false,
    val loading: Boolean = true,
    val searchQuery: String = ""
)

/** Find UI state shared across tabs (applies to the active tab). */
data class FindUiState(
    val query: String = "",
    val replaceQuery: String = "",
    val caseSensitive: Boolean = false,
    val matches: List<FindMatch> = emptyList(),
    val currentIndex: Int = 0,
    val replacedCount: Int = 0,
    val searching: Boolean = false
)

/**
 * Root application model: owns the notes repository, the settings, the tab
 * list and the app-wide UI state. One instance per process/window.
 */
class AppCore(
    val scope: CoroutineScope,
    val settings: SettingsRepository
) {
    private val store = NoteStore.create()

    private val _notes = MutableStateFlow<List<NoteRow>>(emptyList())
    val notes: StateFlow<List<NoteRow>> = _notes.asStateFlow()

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<Long?>(null)
    val activeTabId: StateFlow<Long?> = _activeTabId.asStateFlow()

    val activeSession: EditorSession?
        get() = _tabs.value.firstOrNull { it.localId == _activeTabId.value }?.session

    private val _ui = MutableStateFlow(AppUiState())
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()

    private val findController = FindReplaceController(scope)
    val find: StateFlow<FindUiState> = findController.state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var nextTabId: Long = 1L
    private var sessionPersistJob: Job? = null
    private var initialized = false

    // ------------------------------------------------------------------
    // UI preferences (mirrored to SettingsRepository on change)
    // ------------------------------------------------------------------

    private val _prefs = MutableStateFlow(
        UiPrefs(
            fontSizeSp = settings.fontSizeSp,
            wordWrap = settings.wordWrap,
            themeMode = settings.themeMode,
            reduceMotion = settings.reduceMotion,
            readingMode = settings.readingMode
        )
    )
    val prefs: StateFlow<UiPrefs> = _prefs.asStateFlow()

    fun setFontSize(delta: Float) {
        val newValue = (_prefs.value.fontSizeSp + delta).coerceIn(10f, 26f)
        settings.fontSizeSp = newValue
        _prefs.update { it.copy(fontSizeSp = newValue) }
    }

    fun setWordWrap(enabled: Boolean) {
        settings.wordWrap = enabled
        _prefs.update { it.copy(wordWrap = enabled) }
    }

    fun setReadingMode(reading: Boolean) {
        settings.readingMode = reading
        _tabs.value.forEach { it.session.setReadOnly(reading) }
        _prefs.update { it.copy(readingMode = reading) }
        if (reading) _ui.update { it.copy(replaceMode = false) }
    }

    fun setThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        _prefs.update { it.copy(themeMode = mode) }
    }

    fun setReduceMotion(enabled: Boolean) {
        settings.reduceMotion = enabled
        _prefs.update { it.copy(reduceMotion = enabled) }
    }

    fun init() {
        if (initialized) return
        initialized = true
        scope.launch {
            runCatching {
                val saved = settings.getTabSession()
                var openedAny = false
                for (id in saved) {
                    val doc = store.getDocument(id)
                    if (doc != null) {
                        openTabInternal(doc, activate = false)
                        openedAny = true
                    }
                }
                if (!openedAny) openTabInternal(null, activate = true)
                else activateFirstTab()
            }.onFailure {
                openTabInternal(null, activate = true)
            }
            refreshNotes()
            _ui.update { it.copy(loading = false) }
        }
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    fun newTab() {
        if (_ui.value.loading) return
        openTabInternal(null, activate = true)
        _ui.update { it.copy(findOpen = false, extractOpen = false) }
    }

    private fun openTabInternal(doc: NoteDocument?, activate: Boolean): EditorSession {
        val session = EditorSession(
            scope = scope,
            settings = settings,
            initial = doc,
            onPersist = { d -> store.saveDocument(d) }
        )
        session.setReadOnly(_prefs.value.readingMode)
        val tab = Tab(localId = nextTabId++, session = session, createdAt = currentTimeMillis())
        _tabs.update { it + tab }
        if (activate) {
            _activeTabId.value = tab.localId
            _ui.update { it.copy(findOpen = false, extractOpen = false) }
        }
        // Event fan-out for this session
        scope.launch {
            session.events.collect { event ->
                when (event) {
                    is SessionEvent.DbSaved -> {
                        refreshNotes()
                        persistTabSession()
                    }
                    is SessionEvent.DbSaveFailed ->
                        _messages.tryEmit("Save failed: ${event.message}")
                    is SessionEvent.FileFailed ->
                        _messages.tryEmit(event.message)
                    is SessionEvent.FileSaved ->
                        _messages.tryEmit(if (event.asNewFile) "Saved to ${event.path}" else "Saved")
                    is SessionEvent.FileOpened ->
                        _messages.tryEmit("Opened ${event.path}")
                }
            }
        }
        return session
    }

    fun openNote(row: NoteRow) {
        val existing = _tabs.value.firstOrNull { it.session.state.value.noteId == row.id }
        if (existing != null) {
            _activeTabId.value = existing.localId
            return
        }
        scope.launch {
            val doc = store.getDocument(row.id)
            if (doc != null) {
                openTabInternal(doc, activate = true)
                persistTabSession()
            } else {
                _messages.tryEmit("Note not found")
            }
        }
    }

    fun closeTab(tabId: Long) {
        val tabsNow = _tabs.value
        val tab = tabsNow.firstOrNull { it.localId == tabId } ?: return
        scope.launch {
            // Discard pristine unsaved empty notes; flush the rest.
            if (tab.session.isPristineUnsaved()) {
                // nothing to save
            } else {
                tab.session.flushToDb()
            }
            tab.session.dispose()
            val remaining = _tabs.value.filterNot { it.localId == tabId }
            val wasActive = _activeTabId.value == tabId
            _tabs.value = remaining
            if (wasActive) {
                _activeTabId.value = remaining.firstOrNull()?.localId ?: openFallbackTab()
            }
            persistTabSession()
            refreshNotes()
        }
    }

    private fun openFallbackTab(): Long? {
        if (_tabs.value.isNotEmpty()) return _tabs.value.first().localId
        openTabInternal(null, activate = true)
        return _tabs.value.firstOrNull()?.localId
    }

    fun activateTab(tabId: Long) {
        if (_tabs.value.any { it.localId == tabId }) {
            _activeTabId.value = tabId
            _ui.update { it.copy(findOpen = false, extractOpen = false) }
            persistTabSession()
        }
    }

    private fun activateFirstTab() {
        _activeTabId.value = _tabs.value.firstOrNull()?.localId
    }

    // ------------------------------------------------------------------
    // Notes sidebar
    // ------------------------------------------------------------------

    fun refreshNotes() {
        scope.launch {
            runCatching {
                val q = _ui.value.searchQuery
                _notes.value = if (q.isBlank()) store.getAll() else store.search(q)
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun onSearchQueryChanged(q: String) {
        _ui.update { it.copy(searchQuery = q) }
        // debounce handled by a collector started once
    }

    private var searchCollectorStarted = false

    private fun ensureSearchCollector() {
        if (searchCollectorStarted) return
        searchCollectorStarted = true
        scope.launch {
            _ui.map { it.searchQuery }
                .distinctUntilChanged()
                .debounce(250)
                .collect { refreshNotes() }
        }
    }

    fun setSidebarOpen(open: Boolean) = _ui.update { it.copy(sidebarOpen = open) }
    fun toggleSidebar() = _ui.update { it.copy(sidebarOpen = !it.sidebarOpen) }

    fun setFindOpen(open: Boolean, replaceMode: Boolean = false) {
        _ui.update { it.copy(findOpen = open, replaceMode = replaceMode && !_prefs.value.readingMode,
            findFocusRequest = if (open) it.findFocusRequest + 1 else it.findFocusRequest) }
        findController.setSession(activeSession)
        findController.setOpen(open)
    }

    fun setExtractOpen(open: Boolean) = _ui.update { it.copy(extractOpen = open) }
    fun setSettingsOpen(open: Boolean) = _ui.update { it.copy(settingsOpen = open) }
    fun setAboutOpen(open: Boolean) = _ui.update { it.copy(aboutOpen = open) }

    fun onBackPressed() {
        val u = _ui.value
        when {
            u.settingsOpen -> setSettingsOpen(false)
            u.aboutOpen -> setAboutOpen(false)
            u.sidebarOpen && !isWide -> setSidebarOpen(false)
            u.findOpen -> setFindOpen(false)
            u.extractOpen && !isWide -> setExtractOpen(false)
            else -> Unit
        }
    }

    var isWide = false

    // ------------------------------------------------------------------
    // Note actions
    // ------------------------------------------------------------------

    fun togglePinned(row: NoteRow) {
        scope.launch {
            runCatching { store.setPinned(row.id, !row.isPinned) }
                .onSuccess { refreshNotes() }
                .onFailure { _messages.tryEmit("Could not update pin") }
        }
    }

    fun deleteNote(row: NoteRow, closeOpenTab: Boolean = true) {
        scope.launch {
            runCatching { store.deleteNote(row.id) }
                .onSuccess {
                    refreshNotes()
                    // DB row deleted; the disk file (sourcePath) is intentionally untouched.
                    if (closeOpenTab) {
                        val tab = _tabs.value.firstOrNull { it.session.state.value.noteId == row.id }
                        if (tab != null) closeTab(tab.localId)
                    }
                    persistTabSession()
                }
                .onFailure { _messages.tryEmit("Could not delete note") }
        }
    }

    fun requestOpenFile() {
        val reading = _prefs.value.readingMode
        val previousTab = _activeTabId.value
        val session = if (reading) openTabInternal(null, activate = true) else activeSession ?: return
        scope.launch {
            if (session.importFromFile()) {
                refreshNotes()
                persistTabSession()
            } else if (reading && session.isPristineUnsaved()) {
                val tab = _tabs.value.firstOrNull { it.session === session }
                if (tab != null) {
                    if (_activeTabId.value == tab.localId && previousTab != null) activateTab(previousTab)
                    closeTab(tab.localId)
                }
            }
        }
    }

    fun requestSave() {
        val session = activeSession ?: return
        scope.launch { session.requestFileSave() }
    }

    fun requestSaveAs() {
        val session = activeSession ?: return
        scope.launch { session.requestSaveAs() }
    }

    // ------------------------------------------------------------------
    // Find & Replace (applies to active tab)
    // ------------------------------------------------------------------

    fun onFindQueryChanged(q: String) = findController.setQuery(q)
    fun onReplaceQueryChanged(q: String) = findController.setReplacement(q)
    fun onCaseSensitiveChanged(v: Boolean) = findController.setCaseSensitive(v)
    fun nextMatch() = findController.next()
    fun prevMatch() = findController.previous()
    fun replaceCurrent() = findController.replaceCurrent()
    fun replaceAll() = findController.replaceAll()

    // ------------------------------------------------------------------
    // Session persistence / shutdown
    // ------------------------------------------------------------------

    private fun persistTabSession() {
        sessionPersistJob?.cancel()
        sessionPersistJob = scope.launch {
            kotlinx.coroutines.delay(400)
            val ids = _tabs.value.mapNotNull { it.session.state.value.noteId }
            settings.setTabSession(ids)
        }
    }

    /** Flush all dirty sessions (used on window close / app stop). */
    suspend fun flushAll() {
        _tabs.value.forEach { tab ->
            runCatching { tab.session.flushToDb() }
        }
        val ids = _tabs.value.mapNotNull { it.session.state.value.noteId }
        settings.setTabSession(ids)
    }

    fun shutdown() {
        scope.launch { flushAll() }
        // Give the save a short window before the process exits (desktop).
    }

    fun showMessage(text: String) {
        _messages.tryEmit(text)
    }

    fun setUiWide(wide: Boolean) {
        isWide = wide
        if (wide) _ui.update { it.copy(sidebarOpen = false) }
    }

    init {
        ensureSearchCollector()
        scope.launch {
            combine(_tabs, _activeTabId, _ui.map { it.findOpen }.distinctUntilChanged()) { tabs, id, open ->
                tabs.firstOrNull { it.localId == id }?.session to open
            }.distinctUntilChanged().collect { (session, open) ->
                findController.setSession(session)
                findController.setOpen(open)
            }
        }
    }
}
