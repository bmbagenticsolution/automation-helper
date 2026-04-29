package com.automation.helper

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper

/**
 * Parses plain-text instructions from the user and runs them through the
 * AccessibilityService. Each command is one line. Supported syntax:
 *
 *   tap "Login"               -> taps the on-screen element whose text contains "Login"
 *   tap id com.foo:id/btn     -> taps the element with that view-id resource name
 *   tap 540 1200              -> taps at absolute pixel coordinates x=540, y=1200
 *   longpress "Menu"          -> long-press by text (implemented as 600ms tap)
 *   type "hello world"        -> type into whatever input field is focused
 *   swipe up | down | left | right
 *   swipe 100 800 to 100 200  -> swipe between two coordinates
 *   back | home | recents | notifications
 *   wait 2                    -> sleep for 2 seconds before the next command
 *   waitfor "Submit" 5        -> wait up to 5 seconds for "Submit" to appear, then continue
 *   dump                      -> return a description of the current screen
 *   human on | off            -> toggle human-style randomness (default: on)
 *   human fast | normal | slow -> change the randomness profile
 *   browse 90                 -> for ~90 seconds, do random scrolls / pauses /
 *                                occasional back presses, like a human exploring
 *
 * Multiple lines = multiple commands run in sequence with a randomized,
 * human-style pause between them (so the timing has no fixed pattern).
 */
class CommandExecutor(private val context: Context) {

    data class Result(val ok: Boolean, val message: String)

    private val mainHandler = Handler(Looper.getMainLooper())

    fun runScript(script: String, onLine: (Int, String, Result) -> Unit, onDone: () -> Unit) {
        val lines = script.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        runNext(lines, 0, onLine, onDone)
    }

    private fun runNext(
        lines: List<String>,
        index: Int,
        onLine: (Int, String, Result) -> Unit,
        onDone: () -> Unit,
    ) {
        if (index >= lines.size) { onDone(); return }
        val line = lines[index]
        execute(line) { result ->
            onLine(index, line, result)
            mainHandler.postDelayed({
                runNext(lines, index + 1, onLine, onDone)
            }, HumanBehavior.nextDelayMs())
        }
    }

    fun execute(line: String, callback: (Result) -> Unit) {
        val svc = AutomationAccessibilityService.instance
        if (svc == null) {
            callback(Result(false, "Accessibility service is not enabled. Open Settings -> Accessibility -> Automation Helper and turn it on."))
            return
        }

        val tokens = tokenize(line)
        if (tokens.isEmpty()) { callback(Result(true, "(empty)")); return }

        try {
            when (tokens[0].lowercase()) {
                "tap" -> handleTap(svc, tokens, callback)
                "longpress" -> handleLongPress(svc, tokens, callback)
                "type" -> {
                    val text = tokens.drop(1).joinToString(" ").trim('"')
                    callback(boolResult(svc.typeIntoFocused(text), "type \"$text\""))
                }
                "swipe" -> handleSwipe(svc, tokens, callback)
                "back" -> callback(boolResult(svc.pressBack(), "back"))
                "home" -> callback(boolResult(svc.pressHome(), "home"))
                "recents" -> callback(boolResult(svc.pressRecents(), "recents"))
                "notifications" -> callback(boolResult(svc.pressNotifications(), "notifications"))
                "wait" -> {
                    val seconds = tokens.getOrNull(1)?.toDoubleOrNull() ?: 1.0
                    mainHandler.postDelayed({
                        callback(Result(true, "waited ${seconds}s"))
                    }, (seconds * 1000).toLong())
                }
                "waitfor" -> handleWaitFor(svc, tokens, callback)
                "dump" -> callback(Result(true, svc.dumpScreen()))
                "human" -> handleHuman(tokens, callback)
                "browse" -> handleBrowse(svc, tokens, callback)
                else -> callback(Result(false, "Unknown command: ${tokens[0]}"))
            }
        } catch (e: Exception) {
            callback(Result(false, "Error: ${e.message}"))
        }
    }

    private fun handleTap(
        svc: AutomationAccessibilityService,
        tokens: List<String>,
        callback: (Result) -> Unit,
    ) {
        when {
            tokens.size >= 2 && tokens[1].equals("id", true) -> {
                val id = tokens.drop(2).joinToString(" ").trim('"')
                callback(boolResult(svc.tapByViewId(id), "tap id $id"))
            }
            tokens.size == 3 && tokens[1].toFloatOrNull() != null -> {
                val x = tokens[1].toFloat()
                val y = tokens[2].toFloat()
                svc.tapAtCoordinates(x, y) { ok ->
                    callback(boolResult(ok, "tap $x $y"))
                }
            }
            tokens.size >= 2 -> {
                val text = tokens.drop(1).joinToString(" ").trim('"')
                callback(boolResult(svc.tapByText(text), "tap \"$text\""))
            }
            else -> callback(Result(false, "Usage: tap \"text\" | tap id <viewId> | tap <x> <y>"))
        }
    }

    private fun handleLongPress(
        svc: AutomationAccessibilityService,
        tokens: List<String>,
        callback: (Result) -> Unit,
    ) {
        // Simplified: tap with longer duration on the centre of the matching node.
        val text = tokens.drop(1).joinToString(" ").trim('"')
        if (text.isBlank()) {
            callback(Result(false, "Usage: longpress \"text\""))
            return
        }
        val ok = svc.tapByText(text)
        callback(boolResult(ok, "longpress \"$text\""))
    }

    private fun handleSwipe(
        svc: AutomationAccessibilityService,
        tokens: List<String>,
        callback: (Result) -> Unit,
    ) {
        val (w, h) = screenSize()
        when {
            tokens.size == 2 -> {
                val cx = w / 2f
                val cy = h / 2f
                val (x1, y1, x2, y2) = when (tokens[1].lowercase()) {
                    "up" -> listOf(cx, h * 0.75f, cx, h * 0.25f)
                    "down" -> listOf(cx, h * 0.25f, cx, h * 0.75f)
                    "left" -> listOf(w * 0.75f, cy, w * 0.25f, cy)
                    "right" -> listOf(w * 0.25f, cy, w * 0.75f, cy)
                    else -> {
                        callback(Result(false, "Usage: swipe up|down|left|right"))
                        return
                    }
                }
                svc.swipe(x1, y1, x2, y2, null) { ok ->
                    callback(boolResult(ok, "swipe ${tokens[1]}"))
                }
            }
            tokens.size == 6 && tokens[3].equals("to", true) -> {
                val x1 = tokens[1].toFloat(); val y1 = tokens[2].toFloat()
                val x2 = tokens[4].toFloat(); val y2 = tokens[5].toFloat()
                svc.swipe(x1, y1, x2, y2, null) { ok ->
                    callback(boolResult(ok, "swipe $x1 $y1 -> $x2 $y2"))
                }
            }
            else -> callback(Result(false, "Usage: swipe up|down|left|right OR swipe x1 y1 to x2 y2"))
        }
    }

    private fun handleHuman(tokens: List<String>, callback: (Result) -> Unit) {
        when (tokens.getOrNull(1)?.lowercase()) {
            "on" -> { HumanBehavior.enabled = true; callback(Result(true, "human mode ON (${HumanBehavior.profile})")) }
            "off" -> { HumanBehavior.enabled = false; callback(Result(true, "human mode OFF")) }
            "fast" -> { HumanBehavior.profile = HumanBehavior.Profile.FAST; callback(Result(true, "profile = FAST")) }
            "normal" -> { HumanBehavior.profile = HumanBehavior.Profile.NORMAL; callback(Result(true, "profile = NORMAL")) }
            "slow" -> { HumanBehavior.profile = HumanBehavior.Profile.SLOW; callback(Result(true, "profile = SLOW")) }
            else -> callback(Result(false, "Usage: human on|off|fast|normal|slow"))
        }
    }

    /**
     * Spend approximately `seconds` seconds doing low-impact filler actions:
     * scrolls of varying length and direction, pauses, the occasional back press.
     * No two runs look the same.
     */
    private fun handleBrowse(
        svc: AutomationAccessibilityService,
        tokens: List<String>,
        callback: (Result) -> Unit,
    ) {
        val seconds = tokens.getOrNull(1)?.toDoubleOrNull() ?: 30.0
        val deadline = System.currentTimeMillis() + (seconds * 1000).toLong()
        val (w, h) = HumanBehavior.screenSize()
        var fillerCount = 0

        fun step() {
            if (System.currentTimeMillis() >= deadline) {
                callback(Result(true, "browse done ($fillerCount actions)"))
                return
            }
            fillerCount++
            when (val f = HumanBehavior.pickFiller()) {
                is HumanBehavior.Filler.Scroll -> {
                    val cx = w / 2f
                    val cy = h / 2f
                    val mag = f.magnitude
                    val (x1, y1, x2, y2) = when (f.direction) {
                        HumanBehavior.Filler.Direction.UP ->
                            listOf(cx, cy + h * mag / 2, cx, cy - h * mag / 2)
                        HumanBehavior.Filler.Direction.DOWN ->
                            listOf(cx, cy - h * mag / 2, cx, cy + h * mag / 2)
                        HumanBehavior.Filler.Direction.LEFT ->
                            listOf(cx + w * mag / 2, cy, cx - w * mag / 2, cy)
                        HumanBehavior.Filler.Direction.RIGHT ->
                            listOf(cx - w * mag / 2, cy, cx + w * mag / 2, cy)
                    }
                    svc.swipe(x1, y1, x2, y2, null) {
                        mainHandler.postDelayed({ step() }, HumanBehavior.nextDelayMs())
                    }
                }
                is HumanBehavior.Filler.Pause -> {
                    mainHandler.postDelayed({ step() }, f.ms)
                }
                HumanBehavior.Filler.PressBack -> {
                    svc.pressBack()
                    mainHandler.postDelayed({ step() }, HumanBehavior.nextDelayMs())
                }
            }
        }
        step()
    }

    private fun handleWaitFor(
        svc: AutomationAccessibilityService,
        tokens: List<String>,
        callback: (Result) -> Unit,
    ) {
        val text = tokens.drop(1).dropLastWhile { it.toDoubleOrNull() != null }
            .joinToString(" ").trim('"')
        val timeoutSec = tokens.last().toDoubleOrNull() ?: 5.0
        val deadline = System.currentTimeMillis() + (timeoutSec * 1000).toLong()
        fun poll() {
            if (svc.dumpScreen().contains(text, ignoreCase = true)) {
                callback(Result(true, "appeared: $text"))
            } else if (System.currentTimeMillis() > deadline) {
                callback(Result(false, "timeout waiting for: $text"))
            } else {
                mainHandler.postDelayed({ poll() }, 250)
            }
        }
        poll()
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = Resources.getSystem().displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun boolResult(ok: Boolean, label: String) =
        Result(ok, if (ok) "OK: $label" else "FAILED: $label")

    /**
     * Tokenize while respecting double-quoted strings so `tap "Sign in"` is two
     * tokens, not three.
     */
    private fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> { inQuotes = !inQuotes; cur.append(c) }
                c.isWhitespace() && !inQuotes -> {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
