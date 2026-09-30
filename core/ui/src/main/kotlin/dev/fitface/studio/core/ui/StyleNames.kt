package dev.fitface.studio.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * What a numbered style is called on screen, for every module that shows one.
 *
 * It lives here rather than in either feature module because both of them name the same
 * colourway and must not word it differently. The library labels a project from the
 * catalogue's `styleId` and the editor labels a variant from its `styleN.bin` entry name;
 * those are the same number, so a reader moving between the two screens has to see the
 * same words. Assembled twice, they had already drifted — the editor showed the raw
 * `style0` and the library a zero-padded `style 01` — which is the same failure as the two
 * library pages laying their controls out with two copies of one composable.
 *
 * [styleNumber] is the **container's own** number, counted from zero: the `N` in
 * `styleN.bin`, which is also the catalogue's `FaceStyleOption.id`. The one-based offset a
 * reader sees is applied here and nowhere else, so callers pass the number they hold and
 * do not each decide what to add to it.
 */
@Composable
fun styleLabel(styleNumber: Int): String =
    stringResource(R.string.ui_style_label, styleNumber + 1)
