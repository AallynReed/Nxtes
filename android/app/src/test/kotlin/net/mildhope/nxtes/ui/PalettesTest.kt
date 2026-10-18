package net.mildhope.nxtes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PalettesTest {
    private var stored: String? = null
    private var ids = 0

    private fun palettes() = Palettes({ stored }, { stored = it }, { "p${++ids}" })

    @Test
    fun palettesKeepOpaqueColorsOnceAndSurviveRestarts() {
        val first = palettes()
        val palette = first.add("Mine", listOf(0x80FF0000.toInt()))
        first.addColor(palette.id, 0xFF00FF00.toInt())
        first.addColor(palette.id, 0x2200FF00)
        first.removeColor(palette.id, 0xFFFF0000.toInt())
        first.rename(palette.id, "Greens")

        val again = palettes()
        assertEquals(listOf(Palette("p1", "Greens", listOf(0xFF00FF00.toInt()))), again.saved)
        assertEquals("p1", again.shown?.id)
    }

    @Test
    fun deletingTheShownPaletteShowsTheBuiltInColors() {
        val p = palettes()
        val palette = p.add("Gone")
        p.delete(palette.id)

        assertEquals(emptyList<Palette>(), p.saved)
        assertNull(p.shownId)
        assertNull(palettes().shownId)
    }

    @Test
    fun hexCodesNeedSixDigits() {
        assertEquals(0xFF1E4FD8.toInt(), parseHex("#1e4fd8"))
        assertEquals(0xFF1E4FD8.toInt(), parseHex("1E4FD8"))
        assertNull(parseHex("#1E4FD"))
        assertNull(parseHex("#GGGGGG"))
    }
}
