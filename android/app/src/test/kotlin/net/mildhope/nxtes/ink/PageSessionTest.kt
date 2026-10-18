package net.mildhope.nxtes.ink

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.InputTool
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSessionTest {
    private val saves = mutableListOf<Map<String, List<String>>>()
    private val onDisk = mutableMapOf("p1" to listOf(stroke("old")))

    private fun stroke(id: String) = InkStroke(
        id = id, pen = Pen.BALLPOINT, color = 0, size = 1f, tool = InputTool.STYLUS, noiseSeed = 0, created = 0,
        x = floatArrayOf(0f), y = floatArrayOf(0f), t = intArrayOf(0),
    )

    private fun TestScope.session(): PageSession {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return PageSession(
            load = { PageContent(strokes = onDisk[it].orEmpty()) },
            save = { pages -> saves += pages.mapValues { (_, content) -> content.strokes.map { it.id } } },
            scope = backgroundScope,
            io = dispatcher,
        )
    }

    private fun PageSession.ids(page: String) = strokes(page).map { it.id }

    @Test
    fun undoAndRedoRestoreEachStep() = runTest {
        val ink = session()
        ink.add("p1", stroke("a"))
        ink.add("p1", stroke("b"))

        ink.undo()
        assertEquals(listOf("old", "a"), ink.ids("p1"))
        ink.undo()
        assertEquals(listOf("old"), ink.ids("p1"))
        assertFalse(ink.canUndo)

        ink.redo()
        assertEquals(listOf("old", "a"), ink.ids("p1"))
        assertTrue(ink.canRedo)
    }

    @Test
    fun changesInOneGroupUndoTogetherAcrossPages() = runTest {
        val ink = session()
        val gesture = Any()
        ink.replace("p1", emptyList(), gesture)
        ink.replace("p2", listOf(stroke("x")), gesture)
        ink.replace("p1", listOf(stroke("y")), gesture)

        ink.undo()

        assertEquals(listOf("old"), ink.ids("p1"))
        assertEquals(emptyList<String>(), ink.ids("p2"))
        assertFalse(ink.canUndo)
    }

    @Test
    fun newEditClearsRedo() = runTest {
        val ink = session()
        ink.add("p1", stroke("a"))
        ink.undo()
        ink.add("p1", stroke("b"))

        assertFalse(ink.canRedo)
    }

    @Test
    fun saveWaitsForEditsToSettleAndWritesOnce() = runTest {
        val ink = session()
        ink.add("p1", stroke("a"))
        advanceTimeBy(500)
        ink.add("p1", stroke("b"))
        advanceTimeBy(900)
        runCurrent()
        assertTrue(saves.isEmpty())

        advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf(mapOf("p1" to listOf("old", "a", "b"))), saves)
    }

    @Test
    fun backgroundLoadDoesNotOverwriteEarlierEdits() = runTest {
        val ink = session()
        ink.add("p1", stroke("a"))

        ink.ensureLoaded(listOf("p1"))

        assertEquals(listOf("old", "a"), ink.ids("p1"))
    }
}
