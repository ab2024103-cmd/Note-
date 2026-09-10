package com.notepadpro.shared.editor

import com.notepadpro.shared.data.settings.SettingsRepository
import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.HighlightColor
import com.notepadpro.shared.domain.model.LineEnding
import com.notepadpro.shared.domain.model.ListType
import com.notepadpro.shared.domain.model.NoteDocument
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingModeTest {
    @Test
    fun contentMutationPathsAreBlockedButTextSelectionStillWorks() = runTest {
        val session = session(listOf(EditorLine.plain("a", "first").copy(listType = ListType.CHECK), EditorLine.plain("b", "second")))
        val before = session.state.value
        session.setReadOnly(true)
        session.applyTextChange("a", "first", 1, 4)
        assertEquals(Caret(1, 4), session.state.value.caret)
        session.applyTextChange("a", "changed\npasted", 14, 14)
        session.insertLineBreak("a")
        assertFalse(session.mergeWithPrevious("b"))
        assertFalse(session.mergeWithNext("a"))
        session.setLineColor(HighlightColor.PINK)
        session.setParagraphColor(HighlightColor.BLUE)
        session.markInlineSelection(HighlightColor.YELLOW)
        session.clearInlineSelection()
        session.toggleChecked("a")
        session.selectAllLines()
        session.toggleList(ListType.NUMBER)
        session.indentLines(1)
        session.clearAllFormatting()
        session.setLineEnding(LineEnding.CRLF)
        session.replaceAllLinesExternal(listOf(EditorLine.plain("x", "replacement")))
        session.undo()
        session.redo()
        assertEquals(before.lines, session.state.value.lines)
        assertEquals(before.version, session.state.value.version)
        assertEquals(before.lineEnding, session.state.value.lineEnding)
        assertEquals(SaveStatus.CLEAN, session.saveStatus.value)
    }

    @Test
    fun returningToEditRestoresEditingAndPreservesThePreReadingUndoHistory() = runTest {
        val session = session(listOf(EditorLine.plain("a", "before")))
        session.applyTextChange("a", "after", 5, 5)
        session.setReadOnly(true)
        session.undo()
        assertEquals("after", session.state.value.lines.single().plainText)
        session.setReadOnly(false)
        session.undo()
        assertEquals("before", session.state.value.lines.single().plainText)
        session.applyTextChange("a", "editable", 8, 8)
        assertEquals("editable", session.state.value.lines.single().plainText)
    }

    @Test
    fun pendingEditsStillSaveWhileReading() = runTest {
        var saved: NoteDocument? = null
        val session = EditorSession(backgroundScope, SettingsRepository(MapSettings(), Json),
            NoteDocument(lines = listOf(EditorLine.plain("a", "before"))),
            ioDispatcher = StandardTestDispatcher(testScheduler), onPersist = { saved = it; 1L })
        session.applyTextChange("a", "after", 5, 5)
        session.setReadOnly(true)
        session.flushToDb()
        assertEquals("after", saved?.lines?.single()?.plainText)
        assertEquals(SaveStatus.SAVED, session.saveStatus.value)
    }

    @Test
    fun findStillWorksButReplaceDoesNotChangeAReadOnlyNote() = runTest {
        val session = session(listOf(EditorLine.plain("a", "cat cat")))
        val find = FindReplaceController(backgroundScope, StandardTestDispatcher(testScheduler))
        find.setSession(session)
        find.setOpen(true)
        find.setQuery("cat")
        runCurrent(); advanceTimeBy(200); runCurrent()
        session.setReadOnly(true)
        find.next()
        assertEquals(1, find.state.value.currentIndex)
        find.setReplacement("dog")
        find.replaceCurrent()
        find.replaceAll()
        assertEquals("cat cat", session.state.value.lines.single().plainText)
        assertEquals(0, find.state.value.replacedCount)
    }

    @Test
    fun readingPreferencePersistsWithoutChangingWrapOrFontPreferences() {
        val storage = MapSettings()
        val settings = SettingsRepository(storage, Json)
        assertFalse(settings.readingMode)
        settings.fontSizeSp = 18f
        settings.readingMode = true
        val reopened = SettingsRepository(storage, Json)
        assertTrue(reopened.readingMode)
        assertEquals(18f, reopened.fontSizeSp)
        assertTrue(reopened.wordWrap)
    }

    private fun TestScope.session(lines: List<EditorLine>) = EditorSession(backgroundScope,
        SettingsRepository(MapSettings(), Json), NoteDocument(lines = lines),
        ioDispatcher = StandardTestDispatcher(testScheduler), onPersist = { 1L })
}
