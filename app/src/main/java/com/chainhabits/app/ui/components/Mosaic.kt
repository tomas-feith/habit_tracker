package com.chainhabits.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chainhabits.app.domain.Cell
import com.chainhabits.app.domain.CellState
import com.chainhabits.app.ui.theme.MosaicColors
import com.chainhabits.app.ui.theme.MosaicTheme

private const val DAYS_PER_WEEK = 7

/**
 * A horizontal run of mosaic cells, oldest on the left and now on the right.
 *
 * Consecutive good periods are drawn as **one unbroken bar** rather than separate squares.
 * The habit is "don't break the chain", so the chain should look like a chain: an intact
 * run reads as a single length, and a miss punches a visible gap through it. Discrete
 * per-day cells live on the detail screen, where counting matters more than feeling.
 */
@Composable
fun MosaicStrip(
    cells: List<Cell>,
    modifier: Modifier = Modifier,
    cellHeight: Dp = 18.dp,
    gap: Dp = 3.dp,
) {
    val colors = MosaicTheme.colors
    // A bare Canvas is invisible to screen readers, which would hide the app's entire
    // progress display. Summarise it instead of reading out hundreds of cells.
    val description = remember(cells) { summarise(cells) }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(cellHeight)
                .semantics { contentDescription = description },
    ) {
        if (cells.isEmpty()) return@Canvas
        val gapPx = gap.toPx()
        val slot = size.width / cells.size
        val width = (slot - gapPx).coerceAtLeast(1f)
        val radius = CornerRadius(size.height * 0.35f)

        var i = 0
        while (i < cells.size) {
            if (cells[i].state == CellState.DONE) {
                // Extend across every following good period so the run becomes one bar.
                var end = i
                while (end + 1 < cells.size && cells[end + 1].state == CellState.DONE) end++

                val left = i * slot
                val right = end * slot + width
                drawRoundRect(
                    brush =
                        Brush.horizontalGradient(
                            colors = listOf(colors.doneSoft, colors.done),
                            startX = left,
                            endX = right,
                        ),
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                    cornerRadius = radius,
                )
                i = end + 1
            } else {
                drawCell(cells[i].state, colors, Offset(i * slot, 0f), Size(width, size.height))
                i++
            }
        }
    }
}

/** A single cell, drawn the same way everywhere so the language stays consistent. */
@Composable
fun MosaicCell(
    state: CellState,
    modifier: Modifier = Modifier,
    cellSize: Dp = 14.dp,
) {
    val colors = MosaicTheme.colors
    Canvas(modifier = modifier.size(cellSize)) {
        drawCell(state, colors, Offset.Zero, size)
    }
}

private fun DrawScope.drawCell(
    state: CellState,
    colors: MosaicColors,
    topLeft: Offset,
    size: Size,
) {
    val radius = CornerRadius(size.minDimension * 0.32f)
    when (state) {
        CellState.DONE -> {
            drawRoundRect(colors.done, topLeft, size, radius)
        }

        CellState.BROKEN -> {
            drawRoundRect(colors.broken, topLeft, size, radius)
        }

        // Deliberately quiet: an isolated miss is noise, not failure.
        CellState.MISSED_ONCE -> {
            drawRoundRect(
                color = colors.missedOnce,
                topLeft = topLeft,
                size = size,
                cornerRadius = radius,
                style = Stroke(width = size.minDimension * 0.18f),
            )
        }

        // Carries no judgement - a weekly habit shouldn't look like six daily failures.
        CellState.NOT_SCHEDULED -> {
            val thickness = size.height * 0.14f
            val inset = size.width * 0.22f
            drawRoundRect(
                color = colors.notScheduled,
                topLeft = Offset(topLeft.x + inset, topLeft.y + (size.height - thickness) / 2f),
                size = Size(size.width - inset * 2, thickness),
                cornerRadius = CornerRadius(thickness / 2f),
            )
        }

        // The current period, still open. Outlined so "settled" reads apart from "in play".
        CellState.PENDING -> {
            drawRoundRect(
                color = colors.todayOutline,
                topLeft = topLeft,
                size = size,
                cornerRadius = radius,
                style = Stroke(width = size.minDimension * 0.14f),
            )
        }
    }
}

/** A spoken summary of a run of cells, for screen readers. */
private fun summarise(cells: List<Cell>): String {
    if (cells.isEmpty()) return "No history yet"

    val done = cells.count { it.state == CellState.DONE }
    val missed =
        cells.count {
            it.state == CellState.MISSED_ONCE || it.state == CellState.BROKEN
        }
    val judged = done + missed
    if (judged == 0) return "Nothing recorded yet over the last ${cells.size} periods"

    return "$done good and $missed missed over the last $judged recorded periods"
}

/**
 * Progress toward a times-per-week quota, as filled pips.
 *
 * Only shown when the target is above one; a once-a-week habit gets a plain check
 * instead, since a single pip conveys nothing a checkbox doesn't.
 */
@Composable
fun QuotaPips(
    done: Int,
    target: Int,
    modifier: Modifier = Modifier,
    pipSize: Dp = 10.dp,
) {
    val colors = MosaicTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(target) { i ->
            Canvas(Modifier.size(pipSize)) {
                if (i < done) {
                    drawCircle(colors.done)
                } else {
                    drawCircle(
                        color = colors.notScheduled,
                        style = Stroke(width = size.minDimension * 0.16f),
                    )
                }
            }
        }
        // Anything past the quota is a bonus, not a problem - show it, don't punish it.
        repeat((done - target).coerceIn(0, 3)) {
            Canvas(Modifier.size(pipSize)) {
                drawCircle(colors.done.copy(alpha = 0.35f))
            }
        }
    }
}

/**
 * A full-year heatmap: seven rows (Mon..Sun) by however many week columns fit.
 *
 * Kept as discrete cells rather than a connected chain - this screen is for counting and
 * scanning, where the home strip is for feeling the streak.
 */
@Composable
fun YearMosaic(
    cells: List<Cell>,
    modifier: Modifier = Modifier,
    cellSize: Dp = 12.dp,
    gap: Dp = 2.dp,
) {
    val colors = MosaicTheme.colors
    if (cells.isEmpty()) return

    // Pad the front so the first column starts on a Monday.
    val leading =
        (
            cells
                .first()
                .date.dayOfWeek.value + 6
        ) % DAYS_PER_WEEK
    val weeks = (leading + cells.size + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK
    val description = remember(cells) { summarise(cells) }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(cellSize * DAYS_PER_WEEK + gap * (DAYS_PER_WEEK - 1))
                .semantics { contentDescription = description },
    ) {
        val gapPx = gap.toPx()
        val side =
            ((size.width - gapPx * (weeks - 1)) / weeks - gapPx)
                .coerceIn(2f, cellSize.toPx())

        cells.forEachIndexed { i, cell ->
            val slot = leading + i
            val col = slot / DAYS_PER_WEEK
            val row = slot % DAYS_PER_WEEK
            drawCell(
                state = cell.state,
                colors = colors,
                topLeft = Offset(col * (side + gapPx), row * (side + gapPx)),
                size = Size(side, side),
            )
        }
    }
}

/** Legend explaining the cell language, shown once on the detail screen. */
@Composable
fun MosaicLegend(
    modifier: Modifier = Modifier,
    strict: Boolean,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(CellState.DONE, "Good")
        if (!strict) LegendItem(CellState.MISSED_ONCE, "Missed once")
        LegendItem(CellState.BROKEN, if (strict) "Slip" else "Chain broken")
        LegendItem(CellState.NOT_SCHEDULED, "Off")
    }
}

@Composable
private fun LegendItem(
    state: CellState,
    label: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MosaicCell(state, cellSize = 10.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
