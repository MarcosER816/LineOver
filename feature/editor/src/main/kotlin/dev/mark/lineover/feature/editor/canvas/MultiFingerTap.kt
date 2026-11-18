package dev.mark.lineover.feature.editor.canvas

import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Recognises a two- or three-finger tap: every finger down and up again within
 * [maxMillis] of the first one touching, none moving more than [slopPx] (10 dp). A pinch,
 * a pan or a slow press is never a tap, so zooming cannot undo by accident.
 */
class MultiFingerTap(
    private val slopPx: Float,
    private val maxMillis: Long = MAX_MILLIS,
) {
    private var downTime = 0L
    private var fingers = 0
    private var valid = false
    private val startX = FloatArray(MAX_POINTERS)
    private val startY = FloatArray(MAX_POINTERS)

    /** Feed every event; returns the finger count (2 or 3) when the last finger lifts a tap, else 0. */
    fun onTouchEvent(event: MotionEvent): Int {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                fingers = 1
                valid = true
                remember(event, event.actionIndex)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                fingers = maxOf(fingers, event.pointerCount)
                if (fingers > MAX_FINGERS || event.eventTime - downTime > maxMillis) valid = false
                remember(event, event.actionIndex)
            }

            MotionEvent.ACTION_MOVE -> {
                if (valid && moved(event)) valid = false
            }

            MotionEvent.ACTION_UP -> {
                val tap = valid && fingers >= MIN_FINGERS && event.eventTime - downTime < maxMillis
                valid = false
                return if (tap) fingers else 0
            }

            MotionEvent.ACTION_CANCEL -> {
                valid = false
            }
        }
        return 0
    }

    /** True when a finger has gone further than the slop from where it touched down. */
    private fun moved(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { i ->
            val id = event.getPointerId(i)
            id < MAX_POINTERS && hypot(event.getX(i) - startX[id], event.getY(i) - startY[id]) > slopPx
        }

    private fun remember(
        event: MotionEvent,
        index: Int,
    ) {
        val id = event.getPointerId(index)
        if (id >= MAX_POINTERS) {
            valid = false
            return
        }
        startX[id] = event.getX(index)
        startY[id] = event.getY(index)
    }

    companion object {
        const val MAX_MILLIS = 250L
        const val SLOP_DP = 10f
        private const val MIN_FINGERS = 2
        private const val MAX_FINGERS = 3
        private const val MAX_POINTERS = 10
    }
}
