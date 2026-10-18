package net.mildhope.nxtes.edit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.InputTool
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.ink.PageSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTextTest {
    private fun stroke(id: String, pen: Pen = Pen.BALLPOINT) = InkStroke(
        id = id, pen = pen, color = 0, size = 1f, tool = InputTool.STYLUS, noiseSeed = 0, created = 0,
        x = floatArrayOf(0f, 10f), y = floatArrayOf(0f, 10f), t = intArrayOf(0, 10),
    )

    private fun TestScope.session() = PageSession(
        load = { PageContent() },
        save = {},
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
    )

    private fun texts(ink: PageSession) = ink.content("p").objects.map { (it as TextBox).text }

    /** Reads every batch as its stroke ids; joins on to the box before, when there is one. */
    private fun TestScope.auto(ink: PageSession, read: suspend (List<InkStroke>, String) -> String = { s, _ -> s.joinToString("") { it.id } }) = AutoText(
        scope = backgroundScope,
        session = ink,
        read = read,
        place = { _, _, text, previous -> previous?.copy(text = "${previous.text} $text") ?: TextBox("box", text, 0f, 0f, 10f, 10f) },
    )

    private fun PageSession.write(auto: AutoText, id: String, pen: Pen = Pen.BALLPOINT) {
        val s = stroke(id, pen)
        add("p", s)
        auto.written("p", s)
    }

    @Test
    fun writingTurnsIntoTextOnlyAfterThePause() = runTest {
        val ink = session()
        val auto = auto(ink)
        ink.write(auto, "a")
        advanceTimeBy(AutoText.PAUSE_MS - 100)
        ink.write(auto, "b")
        advanceTimeBy(AutoText.PAUSE_MS - 100)
        runCurrent()
        assertEquals(2, ink.strokes("p").size)
        advanceTimeBy(200)
        runCurrent()
        assertTrue(ink.strokes("p").isEmpty())
        assertEquals(listOf("ab"), texts(ink))
    }

    @Test
    fun aPenOnThePageHoldsItBack() = runTest {
        val ink = session()
        val auto = auto(ink)
        ink.write(auto, "a")
        auto.pressed(true)
        advanceTimeBy(AutoText.PAUSE_MS * 3)
        runCurrent()
        assertEquals(1, ink.strokes("p").size)
        auto.pressed(false)
        advanceTimeBy(AutoText.PAUSE_MS + 100)
        runCurrent()
        assertEquals(listOf("a"), texts(ink))
    }

    @Test
    fun writingThatCarriesOnJoinsTheBoxBefore() = runTest {
        val ink = session()
        val auto = auto(ink)
        ink.write(auto, "a")
        advanceTimeBy(AutoText.PAUSE_MS + 100)
        ink.write(auto, "b")
        advanceTimeBy(AutoText.PAUSE_MS + 100)
        runCurrent()
        assertEquals(listOf("a b"), texts(ink))
        ink.undo()
        assertEquals(listOf("a"), texts(ink))
        assertEquals(listOf("b"), ink.strokes("p").map { it.id })
    }

    @Test
    fun writingErasedWhileItsReadStaysErased() = runTest {
        val ink = session()
        val reading = CompletableDeferred<String>()
        val auto = auto(ink) { _, _ -> reading.await() }
        ink.write(auto, "a")
        advanceTimeBy(AutoText.PAUSE_MS + 100)
        runCurrent()
        ink.replace("p", emptyList())
        reading.complete("a")
        runCurrent()
        assertTrue(ink.content("p").objects.isEmpty())
    }

    @Test
    fun highlightsAreLeftAlone() = runTest {
        val ink = session()
        val auto = auto(ink)
        ink.write(auto, "h", Pen.HIGHLIGHTER)
        advanceTimeBy(AutoText.PAUSE_MS * 2)
        runCurrent()
        assertEquals(1, ink.strokes("p").size)
        assertTrue(ink.content("p").objects.isEmpty())
    }
}
