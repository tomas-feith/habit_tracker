package com.chainhabits.app.widget

import androidx.glance.color.ColorProvider
import com.chainhabits.app.ui.theme.BoneCard
import com.chainhabits.app.ui.theme.Chalk
import com.chainhabits.app.ui.theme.ChalkSoft
import com.chainhabits.app.ui.theme.CharcoalCard
import com.chainhabits.app.ui.theme.DarkMosaicColors
import com.chainhabits.app.ui.theme.Ink
import com.chainhabits.app.ui.theme.InkSoft
import com.chainhabits.app.ui.theme.LightMosaicColors

/**
 * The widget's palette, taken from the app's own theme.
 *
 * Glance cannot read a Compose `MaterialTheme`: it renders to `RemoteViews` in the
 * launcher's process, where the app's composition does not exist. Each colour is therefore
 * a day/night [ColorProvider] resolved by the system, built from the same source values the
 * app uses so the two cannot drift apart.
 */
internal object WidgetColors {
    val surface = ColorProvider(day = BoneCard, night = CharcoalCard)
    val ink = ColorProvider(day = Ink, night = Chalk)
    val inkSoft = ColorProvider(day = InkSoft, night = ChalkSoft)

    val done = ColorProvider(day = LightMosaicColors.done, night = DarkMosaicColors.done)
    val missedOnce =
        ColorProvider(day = LightMosaicColors.missedOnce, night = DarkMosaicColors.missedOnce)
    val broken = ColorProvider(day = LightMosaicColors.broken, night = DarkMosaicColors.broken)
    val tileEmpty =
        ColorProvider(day = LightMosaicColors.notScheduled, night = DarkMosaicColors.notScheduled)
}
