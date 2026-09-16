package app.amphora.gamesession.wineandroid

import android.content.Intent

/**
 * Debug-only sibling z-order dump / force-top for HA262 overlap eye-check.
 *
 * Honored only when the process is FLAG_DEBUGGABLE.
 * [WineAndroidSessionActivity] applies via cold start / onNewIntent (relay).
 *
 * - [EXTRA_DUMP_ZORDER]: `--ez … true` → log all sibling stacks (top-first).
 * - [EXTRA_ZORDER_TOP_HWND]: `--ei … N` → reorder hwnd to HWND_TOP under its
 *   parent, sync bringChildToFront, then dump that stack (and all stacks).
 *
 * Host-only smoke helpers; does not invent guest SWP_* traffic.
 */
object WineAndroidDebugZOrderInject {
    const val EXTRA_DUMP_ZORDER = "app.amphora.debug.DUMP_ZORDER"
    const val EXTRA_ZORDER_TOP_HWND = "app.amphora.debug.ZORDER_TOP_HWND"

    /**
     * null = absent / not debuggable; true/false when present and debuggable.
     * Only `true` schedules a dump (false is a no-op honor of the extra).
     */
    fun dumpRequestedIfDebuggable(
        present: Boolean,
        value: Boolean,
        debuggable: Boolean,
    ): Boolean? {
        if (!debuggable || !present) return null
        return value
    }

    fun dumpRequestedFromIntent(intent: Intent?, debuggable: Boolean): Boolean? {
        if (intent == null || !intent.hasExtra(EXTRA_DUMP_ZORDER)) {
            return dumpRequestedIfDebuggable(
                present = false,
                value = false,
                debuggable = debuggable,
            )
        }
        return dumpRequestedIfDebuggable(
            present = true,
            value = intent.getBooleanExtra(EXTRA_DUMP_ZORDER, false),
            debuggable = debuggable,
        )
    }

    /**
     * null = absent / not debuggable; otherwise the hwnd to force to HWND_TOP
     * (0 is allowed but usually meaningless — treated as concrete inject).
     */
    fun zOrderTopHwndIfDebuggable(
        present: Boolean,
        value: Int,
        debuggable: Boolean,
    ): Int? {
        if (!debuggable || !present) return null
        return value
    }

    fun zOrderTopHwndFromIntent(intent: Intent?, debuggable: Boolean): Int? {
        if (intent == null || !intent.hasExtra(EXTRA_ZORDER_TOP_HWND)) {
            return zOrderTopHwndIfDebuggable(
                present = false,
                value = 0,
                debuggable = debuggable,
            )
        }
        return zOrderTopHwndIfDebuggable(
            present = true,
            value = intent.getIntExtra(EXTRA_ZORDER_TOP_HWND, 0),
            debuggable = debuggable,
        )
    }

    fun relayDump(dumpPresent: Boolean, dumpValue: Boolean): Boolean? =
        if (dumpPresent) dumpValue else null

    fun relayZOrderTop(topPresent: Boolean, topValue: Int): Int? =
        if (topPresent) topValue else null

    /**
     * One log line for a parent sibling stack (top-first).
     * Visible hwnds marked with `*`; hidden unmarked.
     */
    fun formatStackLine(
        parentKey: Int,
        topFirst: List<Int>,
        visible: (Int) -> Boolean,
    ): String {
        val body =
            if (topFirst.isEmpty()) {
                "(empty)"
            } else {
                topFirst.joinToString(separator = ",") { hwnd ->
                    val mark = if (visible(hwnd)) "*" else ""
                    "0x${hwnd.toString(16)}$mark"
                }
            }
        return "zorder dump parentKey=$parentKey topFirst=[$body]"
    }

    /**
     * True when a stack has ≥2 visible hwnds — useful for overlap eye-check
     * logging without spamming single-child syncs.
     */
    fun hasOverlapCandidates(
        topFirst: List<Int>,
        visible: (Int) -> Boolean,
    ): Boolean = topFirst.count(visible) >= 2

    fun formatSyncLine(
        parentKey: Int,
        topFirst: List<Int>,
        bringOrder: List<Int>,
        reason: String,
    ): String {
        val top =
            topFirst.joinToString(separator = ",") { "0x${it.toString(16)}" }
        val bring =
            bringOrder.joinToString(separator = ",") { "0x${it.toString(16)}" }
        return "zorder sync reason=$reason parentKey=$parentKey " +
            "topFirst=[$top] bring=[$bring]"
    }
}
