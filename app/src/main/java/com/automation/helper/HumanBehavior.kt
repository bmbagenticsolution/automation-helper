package com.automation.helper

import android.content.res.Resources
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Adds human-style randomness to taps, swipes, and inter-action delays so
 * the automation doesn't show a fingerprint to whatever app is being driven.
 *
 * Three intensity profiles:
 *   FAST   - light jitter, sub-second pauses (use when you actually need throughput)
 *   NORMAL - default; small jitter, occasional 5-15s "thinking" pauses
 *   SLOW   - heavy variation; pauses can stretch to 1-3 minutes, simulates a
 *            distracted human reading the screen between actions
 *
 * Nothing in here is cryptographically random; it just needs to look organic
 * to a developer watching telemetry.
 */
object HumanBehavior {

    enum class Profile { FAST, NORMAL, SLOW }

    @Volatile var enabled: Boolean = true
    @Volatile var profile: Profile = Profile.NORMAL

    // ---------- Coordinate / duration jitter ----------

    /** Returns the supplied (x, y) shifted by a few pixels in a random direction. */
    fun jitter(x: Float, y: Float): Pair<Float, Float> {
        if (!enabled) return x to y
        val radius = when (profile) {
            Profile.FAST -> 3f
            Profile.NORMAL -> 8f
            Profile.SLOW -> 14f
        }
        val dx = Random.nextFloat() * 2 * radius - radius
        val dy = Random.nextFloat() * 2 * radius - radius
        return (x + dx) to (y + dy)
    }

    /** A random tap-down duration in milliseconds. Real fingers don't all press for 60ms. */
    fun tapDurationMs(): Long {
        if (!enabled) return 60L
        return when (profile) {
            Profile.FAST -> Random.nextLong(40, 90)
            Profile.NORMAL -> Random.nextLong(70, 180)
            Profile.SLOW -> Random.nextLong(110, 260)
        }
    }

    /**
     * Build a slightly-curved, variable-length swipe path between two points
     * instead of a perfectly straight line. The curve is a quadratic bezier
     * with a random control point offset perpendicular to the swipe direction.
     */
    fun curvedSwipePath(x1: Float, y1: Float, x2: Float, y2: Float): Path {
        val path = Path()
        path.moveTo(x1, y1)
        if (!enabled) {
            path.lineTo(x2, y2)
            return path
        }
        // Perpendicular vector to the (x1,y1)->(x2,y2) line, normalized.
        val dx = x2 - x1
        val dy = y2 - y1
        val len = max(1f, kotlin.math.sqrt(dx * dx + dy * dy))
        val nx = -dy / len
        val ny = dx / len
        val maxOffset = when (profile) {
            Profile.FAST -> len * 0.04f
            Profile.NORMAL -> len * 0.10f
            Profile.SLOW -> len * 0.18f
        }
        val offset = (Random.nextFloat() * 2 - 1) * maxOffset
        val cx = (x1 + x2) / 2f + nx * offset
        val cy = (y1 + y2) / 2f + ny * offset
        path.quadTo(cx, cy, x2, y2)
        return path
    }

    /** A random swipe duration. Slower profiles produce longer drags. */
    fun swipeDurationMs(distancePx: Float): Long {
        if (!enabled) return 300L
        val base = (distancePx / 4f).toLong().coerceIn(150, 900)
        val jitter = when (profile) {
            Profile.FAST -> Random.nextLong(-50, 80)
            Profile.NORMAL -> Random.nextLong(-100, 250)
            Profile.SLOW -> Random.nextLong(-150, 500)
        }
        return max(100L, base + jitter)
    }

    // ---------- Inter-action delays ----------

    /**
     * The pause between two commands. Most pauses are short (200ms - 2s) but a
     * small fraction stretch into "thinking" pauses (5-15s) and on SLOW profile
     * occasionally into 1-3 minute "the user got distracted" pauses.
     */
    fun nextDelayMs(): Long {
        if (!enabled) return 350L
        val r = Random.nextFloat()
        return when (profile) {
            Profile.FAST -> when {
                r < 0.85f -> Random.nextLong(120, 600)
                r < 0.98f -> Random.nextLong(700, 2500)
                else -> Random.nextLong(3000, 6000)
            }
            Profile.NORMAL -> when {
                r < 0.65f -> Random.nextLong(400, 2000)
                r < 0.92f -> Random.nextLong(2000, 7000)
                r < 0.99f -> Random.nextLong(7000, 18000)
                else -> Random.nextLong(20_000, 45_000)
            }
            Profile.SLOW -> when {
                r < 0.45f -> Random.nextLong(800, 3500)
                r < 0.80f -> Random.nextLong(3500, 12_000)
                r < 0.95f -> Random.nextLong(12_000, 40_000)
                else -> Random.nextLong(60_000, 180_000) // 1-3 minute pause
            }
        }
    }

    // ---------- Autonomous "browse" filler actions ----------

    /**
     * Pick a random low-impact filler action that a real user might do while
     * exploring an app: small scroll up, small scroll down, longer scroll,
     * pause, occasional back-then-forward. Caller schedules the actual gesture.
     */
    sealed class Filler {
        data class Scroll(val direction: Direction, val magnitude: Float) : Filler()
        data class Pause(val ms: Long) : Filler()
        object PressBack : Filler()
        enum class Direction { UP, DOWN, LEFT, RIGHT }
    }

    fun pickFiller(): Filler {
        val r = Random.nextFloat()
        return when {
            r < 0.40f -> Filler.Scroll(Filler.Direction.DOWN, randomMagnitude())
            r < 0.65f -> Filler.Scroll(Filler.Direction.UP, randomMagnitude())
            r < 0.75f -> Filler.Scroll(
                if (Random.nextBoolean()) Filler.Direction.LEFT else Filler.Direction.RIGHT,
                randomMagnitude()
            )
            r < 0.95f -> Filler.Pause(nextDelayMs())
            else -> Filler.PressBack
        }
    }

    private fun randomMagnitude(): Float = 0.18f + Random.nextFloat() * 0.55f

    fun screenSize(): Pair<Int, Int> {
        val dm = Resources.getSystem().displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    fun coerceToScreen(x: Float, y: Float): Pair<Float, Float> {
        val (w, h) = screenSize()
        return min(w - 1f, max(0f, x)) to min(h - 1f, max(0f, y))
    }

    // Avoid a divide-by-zero or "all on top of each other" swipe.
    fun ensureMinDistance(x1: Float, y1: Float, x2: Float, y2: Float): Boolean =
        abs(x1 - x2) + abs(y1 - y2) >= 5f
}
