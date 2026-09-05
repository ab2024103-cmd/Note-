package com.notepadpro.shared.editor

import com.notepadpro.shared.data.settings.SettingsRepository
import com.notepadpro.shared.domain.model.NoteDocument
import com.notepadpro.shared.platform.PlatformInfo
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorSessionAutosaveTest {
    private val debounceMs: Long
        get() = if (PlatformInfo.isLowMemoryDevice()) 1500L else 600L

    @Test
    fun autosavePersistsAfterDebounceWithoutCancellingItself() = runTest {
        val writes = mutableListOf<NoteDocument>()
        val session = session { doc ->
            currentCoroutineContext().ensureActive()
            writes += doc
            41L
        }
        val events = observeEvents(session)

        session.typeText("hi")
        assertEquals(SaveStatus.DIRTY, session.saveStatus.value)
        runCurrent()
        advanceTimeBy(debounceMs - 1)
        runCurrent()
        assertTrue(writes.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals("hi", writes.single().lines.single().plainText)
        assertNull(writes.single().id)
        assertEquals(41L, session.state.value.noteId)
        assertTrue(session.hasEverSaved)
        assertEquals(SaveStatus.SAVED, session.saveStatus.value)
        assertEquals(listOf(SessionEvent.DbSaved(41L, session.state.value.version)), events)

        advanceTimeBy(debounceMs * 3)
        runCurrent()
        assertEquals(1, writes.size)
    }

    @Test
    fun rapidTypingResetsTheDelayAndLaterSavesReuseTheNoteId() = runTest {
        val writes = mutableListOf<NoteDocument>()
        val session = session { doc ->
            writes += doc
            doc.id ?: 41L
        }

        session.typeText("h")
        runCurrent()
        advanceTimeBy(debounceMs / 2)
        session.typeText("hi")
        runCurrent()
        advanceTimeBy(debounceMs - 1)
        runCurrent()
        assertTrue(writes.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals("hi", writes.single().lines.single().plainText)

        session.typeText("hi again")
        elapseDebounce()
        assertEquals(listOf(null, 41L), writes.map { it.id })
        assertEquals(listOf("hi", "hi again"), writes.map { it.lines.single().plainText })
        assertEquals(SaveStatus.SAVED, session.saveStatus.value)
    }

    @Test
    fun explicitFlushCancelsOnlyThePendingDelay() = runTest {
        val writes = mutableListOf<NoteDocument>()
        val session = session { doc ->
            writes += doc
            41L
        }
        val events = observeEvents(session)

        session.typeText("hi")
        runCurrent()
        advanceTimeBy(debounceMs / 2)
        session.flushToDb()
        currentCoroutineContext().ensureActive()
        assertEquals(SaveStatus.SAVED, session.saveStatus.value)
        assertEquals(1, writes.size)

        advanceTimeBy(debounceMs * 2)
        runCurrent()
        session.flushToDb() // A clean document does not need another write.
        assertEquals(1, writes.size)
        assertEquals(listOf(SessionEvent.DbSaved(41L, session.state.value.version)), events)
    }

    @Test
    fun editsDuringASlowSaveDoNotCancelTheWriteOrLoseTheNewVersion() = runTest {
        val releaseWrite = CompletableDeferred<Unit>()
        val writes = mutableListOf<NoteDocument>()
        var attempts = 0
        val session = session { doc ->
            attempts++
            if (attempts == 1) releaseWrite.await()
            writes += doc
            doc.id ?: 41L
        }
        val events = observeEvents(session)

        session.typeText("hi")
        elapseDebounce()
        assertEquals(1, attempts)
        assertEquals(SaveStatus.SAVING, session.saveStatus.value)

        session.typeText("hi again")
        elapseDebounce() // Even an expired second delay must not cancel the write.
        assertEquals(1, attempts)
        assertTrue(writes.isEmpty())
        assertEquals(SaveStatus.SAVING, session.saveStatus.value)

        releaseWrite.complete(Unit)
        runCurrent()
        assertEquals("hi", writes.single().lines.single().plainText)
        assertEquals(41L, session.state.value.noteId)
        assertEquals(SaveStatus.DIRTY, session.saveStatus.value)

        elapseDebounce()
        assertEquals(listOf(null, 41L), writes.map { it.id })
        assertEquals(listOf("hi", "hi again"), writes.map { it.lines.single().plainText })
        assertEquals(SaveStatus.SAVED, session.saveStatus.value)
        assertTrue(events.all { it is SessionEvent.DbSaved })
    }

    @Test
    fun cancelledFlushPropagatesWithoutASaveErrorAndCanRetry() = runTest {
        var attempts = 0
        val session = session {
            attempts++
            if (attempts == 1) awaitCancellation()
            41L
        }
        val events = observeEvents(session)
        session.typeText("hi")
        var continuedAfterFlush = false
        val flush = launch {
            session.flushToDb()
            continuedAfterFlush = true
        }
        runCurrent()
        assertEquals(SaveStatus.SAVING, session.saveStatus.value)

        flush.cancelAndJoin()
        assertFalse(continuedAfterFlush)
        assertEquals(SaveStatus.DIRTY, session.saveStatus.value)
        assertNull(session.state.value.noteId)
        assertFalse(session.hasEverSaved)
        assertTrue(events.isEmpty())

        elapseDebounce()
        assertEquals(2, attempts)
        assertEquals(SaveStatus.SAVED, session.saveStatus.value)
        assertEquals(listOf(SessionEvent.DbSaved(41L, session.state.value.version)), events)
    }

    @Test
    fun genuineStorageFailureIsStillReportedAndCanBeRetried() = runTest {
        var attempts = 0
        val session = session {
            attempts++
            if (attempts == 1) error("Disk full")
            41L
        }
        val events = observeEvents(session)
        session.typeText("hi")

        elapseDebounce()
        assertEquals(SaveStatus.ERROR, session.saveStatus.value)
        assertEquals(listOf(SessionEvent.DbSaveFailed("Disk full")), events)
        assertNull(session.state.value.noteId)

        elapseDebounce()
        assertEquals(2, attempts)
        assertEquals(SaveStatus.SAVED, session.saveStatus.value)
        assertTrue(session.hasEverSaved)
        assertEquals(SessionEvent.DbSaved(41L, session.state.value.version), events.last())
    }

    @Test
    fun disposingAPendingAutosaveDoesNotWriteOrReportAnError() = runTest {
        var writes = 0
        val session = session {
            writes++
            41L
        }
        val events = observeEvents(session)
        session.typeText("hi")
        runCurrent()
        session.dispose()

        advanceTimeBy(debounceMs * 2)
        runCurrent()
        assertEquals(0, writes)
        assertTrue(events.isEmpty())
        assertFalse(session.hasEverSaved)
    }

    private fun TestScope.session(persist: suspend (NoteDocument) -> Long): EditorSession =
        EditorSession(
            scope = backgroundScope,
            settings = SettingsRepository(MapSettings(), Json),
            initial = null,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            onPersist = persist
        )

    private fun TestScope.observeEvents(session: EditorSession): List<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.events.collect { events += it }
        }
        return events
    }

    private fun EditorSession.typeText(text: String) {
        val lineId = state.value.lines.first().id
        applyTextChange(lineId, text, text.length, text.length)
    }

    private fun TestScope.elapseDebounce() {
        runCurrent()
        advanceTimeBy(debounceMs)
        runCurrent()
    }
}
