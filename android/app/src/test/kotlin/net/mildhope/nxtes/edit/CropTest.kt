package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.Picture
import org.junit.Assert.assertEquals
import org.junit.Test

class CropTest {
    private val whole = Picture("p", "blob", cx = 100f, cy = 100f, width = 200f, height = 100f)

    @Test
    fun croppingKeepsTheKeptPartWhereItWas() {
        val right = Crop.apply(whole, 0.5f, 0f, 1f, 1f)

        assertEquals(150f, right.cx, 0.001f)
        assertEquals(100f, right.width, 0.001f)
        assertEquals(Crop.frame(whole), Crop.frame(right))
    }

    @Test
    fun turnedPicturesCropAlongTheirOwnAxes() {
        val turned = whole.copy(rotation = 90f)
        val right = Crop.apply(turned, 0.5f, 0f, 1f, 1f)

        assertEquals(100f, right.cx, 0.001f)
        assertEquals(150f, right.cy, 0.001f)
    }

    @Test
    fun freeformCropsToThePathsBox() {
        val cut = Crop.freeform(whole, listOf(0.25f, 0.5f, 0.75f, 0.5f, 0.5f, 1f))

        assertEquals(0.25f to 0.75f, cut.cropLeft to cut.cropRight)
        assertEquals(0.5f to 1f, cut.cropTop to cut.cropBottom)
        assertEquals(listOf(0f, 0f, 1f, 0f, 0.5f, 1f), cut.outline)
        assertEquals(100f, cut.width, 0.001f)
    }
}
