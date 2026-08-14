package com.tsfeith.habits.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

val Green80 = Color(0xFF7FD1AE)
val Green40 = Color(0xFF1B7A57)
val Sand80 = Color(0xFFE6D3A3)
val Sand40 = Color(0xFF7A6524)
val Clay80 = Color(0xFFF0B0A0)
val Clay40 = Color(0xFF8C4030)

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
    /** One miss, still recoverable. Deliberately quiet - this is noise, not failure. */
    val missedOnce: Color,
    /** The chain is broken: a second consecutive miss, or any miss on a strict habit. */
    val broken: Color,
    /** Habit wasn't scheduled this day (e.g. a weekly habit's off days). */
    val notScheduled: Color,
    /** Outline for today's cell, which is provisional until the day settles at midnight. */
    val todayOutline: Color,
)

val LightMosaicColors = MosaicColors(
    done = Color(0xFF1B7A57),
    missedOnce = Color(0xFFD9C48A),
    broken = Color(0xFFC0533C),
    notScheduled = Color(0xFFE4E4E0),
    todayOutline = Color(0xFF3A3A38),
)

val DarkMosaicColors = MosaicColors(
    done = Color(0xFF5FC49B),
    missedOnce = Color(0xFF9A8447),
    broken = Color(0xFFE0705A),
    notScheduled = Color(0xFF33332F),
    todayOutline = Color(0xFFE8E8E4),
)
