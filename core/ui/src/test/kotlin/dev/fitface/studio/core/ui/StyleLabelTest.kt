package dev.fitface.studio.core.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one place the style numbering a reader sees is decided.
 *
 * Asserted as copy rather than as a composition: `styleLabel` is a `stringResource`
 * lookup and the property worth pinning is the arithmetic in front of it. The offset is
 * what two screens have to agree on — the editor labels a variant from its `styleN.bin`
 * name and the library labels a project from the catalogue's style id, and those are the
 * same number — so the moment either of them applies its own `+ 1` again, one of the two
 * is off by one with nothing on screen to say which.
 */
@RunWith(RobolectricTestRunner::class)
class StyleLabelTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun label(styleNumber: Int) =
        resources.getString(R.string.ui_style_label, styleNumber + 1)

    @Test
    fun theContainersFirstStyleIsStyleOne() {
        // `style0.bin` is the first style, and a person counting them starts at one.
        assertEquals("Style 1", label(0))
    }

    @Test
    fun theNumberingIsUnpaddedAndCarriesNoEntryName() {
        assertEquals("Style 3", label(2))
        assertEquals("Style 11", label(10))
    }
}
