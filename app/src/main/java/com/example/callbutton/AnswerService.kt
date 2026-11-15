package com.example.callbutton

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AnswerService : AccessibilityService() {
    companion object {
        @Volatile var instance: AnswerService? = null
        private var lastDump = 0L
        private val h = Handler(Looper.getMainLooper())

        fun click(answer: Boolean): Boolean = instance?.doClick(answer) ?: false

        fun scheduleDump() {
            val now = System.currentTimeMillis()
            if (now - lastDump < 5000) return
            lastDump = now
            if (instance == null) {
                Bus.log("LET OP: Toegankelijkheid staat UIT voor CallButton")
                return
            }
            h.postDelayed({ instance?.dump() }, 1500)
        }
    }

    override fun onServiceConnected() { instance = this; Bus.log("Toegankelijkheid actief") }
    override fun onUnbind(i: Intent?): Boolean { instance = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // Vangt knoppen van de oordopjes op, ook tijdens het overgaan van een gesprek
    override fun onKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 &&
            (k == KeyEvent.KEYCODE_HEADSETHOOK || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
             k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            Bus.log("KNOP (toegankelijkheid): " + KeyEvent.keyCodeToString(k))
            HeadsetService.instance?.onHeadsetKey()
        }
        return false
    }

    private fun walk(n: AccessibilityNodeInfo, f: (AccessibilityNodeInfo) -> Unit) {
        f(n)
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, f)
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, words: List<String>): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { n ->
            if (found == null && (hit(n.text ?: n.contentDescription, words) || hit(n.viewIdResourceName, words))) found = n
        }
        return found
    }

    fun dump() {
        val out = ArrayList<String>()
        var answerFound = false
        var declineFound = false
        for (w in windows) {
            val root = w.root ?: continue
            if (findNode(root, ANSWER_WORDS) != null) answerFound = true
            if (findNode(root, DECLINE_WORDS) != null) declineFound = true
            walk(root) { n ->
                val t = (n.text ?: n.contentDescription)?.toString()
                val id = n.viewIdResourceName?.substringAfter(":id/")
                if (t != null || (id != null && n.isClickable)) {
                    out.add("${t ?: "-"}[${id ?: "-"}]" + if (n.isClickable) "*" else "")
                }
            }
        }
        Bus.log("SCHERM-SCAN: opnemen-knop " + (if (answerFound) "GEVONDEN" else "NIET gevonden") +
                ", weigeren-knop " + (if (declineFound) "GEVONDEN" else "NIET gevonden"))
        Bus.log("Op het scherm (* = aanklikbaar): " + out.take(30).joinToString(" | "))
    }

    private fun doClick(answer: Boolean): Boolean {
        val words = if (answer) ANSWER_WORDS else DECLINE_WORDS
        val what = if (answer) "opnemen" else "weigeren"
        for (w in windows) {
            val root = w.root ?: continue
            val target = findNode(root, words) ?: continue
            var c: AccessibilityNodeInfo? = target
            while (c != null) {
                if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Bus.log("Geklikt op knop ($what)")
                    verify(answer)
                    return true
                }
                c = c.parent
            }
            val r = Rect()
            target.getBoundsInScreen(r)
            if (r.width() > 0 && tap(r.exactCenterX(), r.exactCenterY())) {
                Bus.log("Getikt op schermpositie ($what): ${r.centerX()},${r.centerY()}")
                verify(answer)
                return true
            }
        }
        Bus.log("Knop voor $what NIET gevonden op het scherm")
        return false
    }

    private fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 80)).build()
        return dispatchGesture(g, null, null)
    }

    private fun verify(answer: Boolean) {
        h.postDelayed({
            val acts = CallListener.current?.notification?.actions
            val stillRinging = acts?.any { hit(it.title, ANSWER_WORDS) } == true
            Bus.log(
                if (!stillRinging) "BEVESTIGD: het gesprek is " + (if (answer) "opgenomen" else "geweigerd")
                else "NIET bevestigd: het gesprek gaat nog steeds over"
            )
        }, 2500)
    }
}
