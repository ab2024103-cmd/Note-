package com.notepadpro.shared.editor

import com.notepadpro.shared.data.settings.SettingsRepository
import com.notepadpro.shared.domain.model.EditorLine
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
class FindReplaceControllerTest {
    @Test
    fun navigationWrapsAndMatchCaseRecomputesResults() = runTest {
        val session = session("cat CAT cat")
        val find = finder(session, "cat")
        settle()
        assertEquals(3, find.state.value.matches.size)
        find.previous()
        assertEquals(2, find.state.value.currentIndex)
        find.next()
        assertEquals(0, find.state.value.currentIndex)
        find.setCaseSensitive(true)
        settle()
        assertEquals(2, find.state.value.matches.size)
    }

    @Test
    fun replaceCurrentRefreshesOffsetsAndMovesToTheNextOccurrence() = runTest {
        val session = session("cat cat cat")
        val find = finder(session, "cat")
        settle()
        find.next()
        find.setReplacement("lion")
        find.replaceCurrent()
        settle()
        assertEquals("cat lion cat", session.state.value.lines.single().plainText)
        assertEquals(9, find.state.value.matches[find.state.value.currentIndex].start)
        find.replaceCurrent()
        settle()
        assertEquals("cat lion lion", session.state.value.lines.single().plainText)
        assertEquals(0, find.state.value.currentIndex)
        assertEquals(2, find.state.value.replacedCount)
        session.undo()
        settle()
        assertEquals("cat lion cat", session.state.value.lines.single().plainText)
        assertEquals(2, find.state.value.matches.size)
    }

    @Test
    fun replaceAllRecountsSearchTextThatAlsoOccursInsideTheReplacement() = runTest {
        val session = session("cat cat")
        val find = finder(session, "cat")
        settle()
        find.setReplacement("catcat")
        find.replaceAll()
        settle()
        assertEquals("catcat catcat", session.state.value.lines.single().plainText)
        assertEquals(2, find.state.value.replacedCount)
        assertEquals(4, find.state.value.matches.size)
    }

    @Test
    fun editsWhileOpenRefreshMatchesAndBlockStaleReplacement() = runTest {
        val session = session("cat")
        val find = finder(session, "cat")
        settle()
        session.applyTextChange("p", "dog cat", 7, 7)
        find.setReplacement("lion")
        find.replaceCurrent() // Before the document collector runs: must not use old offsets.
        assertEquals("dog cat", session.state.value.lines.single().plainText)
        settle()
        assertEquals(4, find.state.value.matches.single().start)
        find.replaceCurrent()
        settle()
        assertEquals("dog lion", session.state.value.lines.single().plainText)
    }

    @Test
    fun changingTabsCannotReplaceTextInThePreviouslySearchedNote() = runTest {
        val first = session("cat first")
        val second = session("second cat")
        val find = finder(first, "cat")
        settle()
        find.setSession(second)
        find.setReplacement("dog")
        find.replaceCurrent()
        assertEquals("second cat", second.state.value.lines.single().plainText)
        settle()
        find.replaceCurrent()
        settle()
        assertEquals("second dog", second.state.value.lines.single().plainText)
        assertEquals("cat first", first.state.value.lines.single().plainText)
    }

    @Test
    fun closeCancelsPendingSearchAndReopeningRestoresTheQuery() = runTest {
        val find = finder(session("cat cat"), "cat")
        runCurrent()
        find.setOpen(false)
        settle()
        assertFalse(find.state.value.searching)
        assertTrue(find.state.value.matches.isEmpty())
        find.setOpen(true)
        settle()
        assertEquals("cat", find.state.value.query)
        assertEquals(2, find.state.value.matches.size)
    }

    @Test
    fun clearingTheQueryCancelsOldResultsImmediately() = runTest {
        val find = finder(session("cat cat"), "cat")
        runCurrent()
        find.setQuery("")
        settle()
        assertTrue(find.state.value.matches.isEmpty())
        assertFalse(find.state.value.searching)
    }

    private fun TestScope.session(text: String) = EditorSession(
        backgroundScope, SettingsRepository(MapSettings(), Json),
        NoteDocument(lines = listOf(EditorLine.plain("p", text))),
        ioDispatcher = StandardTestDispatcher(testScheduler), onPersist = { 1L }
    )

    private fun TestScope.finder(session: EditorSession, query: String) =
        FindReplaceController(backgroundScope, StandardTestDispatcher(testScheduler)).also {
            it.setSession(session)
            it.setOpen(true)
            it.setQuery(query)
        }

    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
    }
}
