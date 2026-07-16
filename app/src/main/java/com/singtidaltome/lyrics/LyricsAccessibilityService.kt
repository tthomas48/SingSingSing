package com.singtidaltome.lyrics

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.singtidaltome.SingAlongApp

/**
 * Clicks Tidal TV's `lyricsButton` when requested.
 * Confirmed during investigation: `com.tidal.android.resources.widget.LyricsButton` / `app:id/lyricsButton`.
 */
class LyricsAccessibilityService : AccessibilityService(), LyricsOpener {
    override fun onServiceConnected() {
        instance = this
        SingAlongApp.instance.partySession.attachLyricsOpener(this)
        Log.i(TAG, "Lyrics accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive; clicks are requested explicitly.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun openLyricsBestEffort() {
        val root = rootInActiveWindow ?: return
        val button = findByViewId(root, LYRICS_VIEW_ID)
            ?: findByText(root, "Lyrics")
        if (button != null) {
            val clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "Clicked lyrics button success=$clicked")
            return
        }
        Log.w(TAG, "lyricsButton not found in active window")
    }

    private fun findByViewId(node: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val matches = node.findAccessibilityNodeInfosByViewId(viewId)
        return matches.firstOrNull()
    }

    private fun findByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val matches = node.findAccessibilityNodeInfosByText(text)
        return matches.firstOrNull { it.isClickable } ?: matches.firstOrNull()?.parent
    }

    companion object {
        private const val TAG = "LyricsA11y"
        private const val LYRICS_VIEW_ID = "com.aspiro.tidal:id/lyricsButton"

        @Volatile
        var instance: LyricsAccessibilityService? = null
            private set
    }
}
