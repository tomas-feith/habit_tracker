package com.chainhabits.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Greens carry the app's identity and every "good day" in the mosaic.
val Green95 = Color(0xFFE8F6EE)
val Green90 = Color(0xFFD3EEE1)
val Green80 = Color(0xFF7FD1AE)
val Green40 = Color(0xFF1B7A57)
val Green30 = Color(0xFF235C46)
val Green10 = Color(0xFF06301F)

// Sand is the "missed once" warning: warm, quiet, never alarming.
val Sand80 = Color(0xFFE6D3A3)
val Sand60 = Color(0xFFC9AC63)
val Sand40 = Color(0xFF7A6524)

// Clay is reserved for a genuinely broken chain.
val Clay80 = Color(0xFFF0B0A0)
val Clay40 = Color(0xFF8C4030)

// Neutrals are warm rather than pure grey, so the surface does not read as clinical.
val Bone = Color(0xFFF7F5F1)
val BoneCard = Color(0xFFFFFFFF)
val Ink = Color(0xFF1A1C1A)
val InkSoft = Color(0xFF5C625E)

val Charcoal = Color(0xFF12140F)
val CharcoalCard = Color(0xFF1E211C)
val Chalk = Color(0xFFE6E8E2)
val ChalkSoft = Color(0xFF9BA39C)

/**
 * Colors for the mosaic cells.
 *
 * The single rule the whole visual language rests on: a solid cell always means "a good
 * day", for every habit type. Positive and negative habits differ in what produces a good
 * day, never in how the mosaic reads.
 */
@Immutable
data class MosaicColors(
    /** A good day: completed a positive habit, or stayed clean on a negative one. */
    val done: Color,
    /** Softer end of the chain gradient, so a long run has some life in it. */
    val doneSoft: Color,
    /** One miss, still recoverable. Deliberately quiet - this is noise, not failure. */
    val missedOnce: Color,
    /** The chain is broken: a second consecutive miss, or any miss on a strict habit. */
    val broken: Color,
    /** Habit wasn't scheduled this day (e.g. a weekly habit's off days). */
    val notScheduled: Color,
    /** Outline for today's cell, which is provisional until the day settles at midnight. */
    val todayOutline: Color,
    /** Background tint for the never-miss-twice banner. */
    val warningSurface: Color,
)

val LightMosaicColors =
    MosaicColors(
        done = Green40,
        doneSoft = Color(0xFF2E9970),
        missedOnce = Sand60,
        broken = Color(0xFFC0533C),
        notScheduled = Color(0xFFDDD9D1),
        todayOutline = Color(0xFF8C918B),
        warningSurface = Color(0xFFF6EEDC),
    )

val DarkMosaicColors =
    MosaicColors(
        done = Color(0xFF57C79B),
        doneSoft = Color(0xFF3FA47D),
        missedOnce = Color(0xFFB99A4E),
        broken = Color(0xFFE0705A),
        notScheduled = Color(0xFF32362F),
        todayOutline = Color(0xFF767C74),
        warningSurface = Color(0xFF2C2617),
    )
