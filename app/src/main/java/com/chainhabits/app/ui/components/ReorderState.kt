package com.chainhabits.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * Long-press drag reordering for a [androidx.compose.foundation.lazy.LazyColumn].
 *
 * The gesture detector lives on each **item**, not on the list. Putting it on the list
 * means competing with the list's own scroll gesture for the same pointer stream, and the
 * scroll wins the drag once the long press releases its claim.
 *
 * Items are tracked by **key**, not index: the list reorders underneath the finger while
 * the drag is in progress, so an index captured at drag start would point at the wrong
 * row a moment later.
 */
class ReorderState internal constructor(
    val listState: LazyListState,
    private val onMove: (fromKey: Long, toKey: Long) -> Unit,
    private val onDrop: () -> Unit,
) {
    /** Key of the row currently being dragged, or null. */
    var draggingKey: Long? by mutableStateOf(null)
        private set

    /** How far the dragged row has travelled from its resting place, in pixels. */
    var draggingOffset: Float by mutableFloatStateOf(0f)
        private set

    private val itemInfo
        get() = listState.layoutInfo.visibleItemsInfo

    fun onDragStart(key: Long) {
        draggingKey = key
        draggingOffset = 0f
    }

    fun onDrag(delta: Float) {
        val key = draggingKey ?: return
        draggingOffset += delta

        val dragged = itemInfo.firstOrNull { it.key == key } ?: return
        val middle = dragged.offset + draggingOffset + dragged.size / 2f

        val target =
            itemInfo.firstOrNull { other ->
                other.key is Long &&
                    other.key != key &&
                    middle.toInt() in other.offset..(other.offset + other.size)
            } ?: return

        onMove(key, target.key as Long)
        // The rows have swapped places, so shift the visual offset by the same amount to
        // keep the dragged row sitting under the finger instead of jumping.
        draggingOffset += dragged.offset - target.offset
    }

    fun onDragEnd() {
        draggingKey = null
        draggingOffset = 0f
        onDrop()
    }
}

/**
 * Remembers the drag state for a list.
 *
 * The callbacks are read through [rememberUpdatedState] rather than captured: the state
 * survives recomposition (it is keyed only on [listState]), so a directly captured lambda
 * would be the one from first composition forever. Harmless while the callers only close
 * over a view model, and a silent, very hard to find bug the moment one closes over
 * anything that changes.
 */
@Composable
fun rememberReorderState(
    listState: LazyListState,
    onMove: (fromKey: Long, toKey: Long) -> Unit,
    onDrop: () -> Unit,
): ReorderState {
    val currentMove by rememberUpdatedState(onMove)
    val currentDrop by rememberUpdatedState(onDrop)
    return remember(listState) {
        ReorderState(
            listState = listState,
            onMove = { from, to -> currentMove(from, to) },
            onDrop = { currentDrop() },
        )
    }
}

/**
 * Makes one row draggable. Handles the gesture, the lift and the follow-the-finger offset.
 */
fun Modifier.reorderableItem(
    state: ReorderState,
    key: Long,
): Modifier {
    val dragging = state.draggingKey == key
    return this
        .zIndex(if (dragging) 1f else 0f)
        .graphicsLayer { translationY = if (dragging) state.draggingOffset else 0f }
        .pointerInput(key) {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.onDragStart(key) },
                onDrag = { change, dragAmount ->
                    change.consume()
                    state.onDrag(dragAmount.y)
                },
                onDragEnd = { state.onDragEnd() },
                onDragCancel = { state.onDragEnd() },
            )
        }
}
