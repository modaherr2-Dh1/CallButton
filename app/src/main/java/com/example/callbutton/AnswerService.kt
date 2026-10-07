package com.example.callbutton

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AnswerService : AccessibilityService() {
    companion object {
        @Volatile var instance: AnswerService? = null
        fun click(answer: Boolean): Boolean = instance?.doClick(answer) ?: false
    }

    override fun onServiceConnected() { instance = this; Bus.log("Toegankelijkheid actief") }
    override fun onUnbind(i: Intent?): Boolean { instance = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun doClick(answer: Boolean): Boolean {
        val words = if (answer) ANSWER_WORDS else DECLINE_WORDS
        val seen = ArrayList<String>()
        for (w in windows) {
            val root = w.root ?: continue
            if (find(root, words, seen)) {
                Bus.log("Via scherm geklikt: " + if (answer) "opnemen" else "weigeren")
                return true
            }
        }
        Bus.log("Geen knop op scherm gevonden. Zichtbaar: " + seen.take(15).joinToString(" | "))
        return false
    }

    private fun find(n: AccessibilityNodeInfo, words: List<String>, seen: MutableList<String>): Boolean {
        val t = (n.text ?: n.contentDescription)?.toString()
        if (t != null) {
            if (t.length < 40) seen.add(t)
            if (hit(t, words)) {
                var c: AccessibilityNodeInfo? = n
                while (c != null) {
                    if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    c = c.parent
                }
            }
        }
        for (i in 0 until n.childCount) {
            val ch = n.getChild(i) ?: continue
            if (find(ch, words, seen)) return true
        }
        return false
    }
}
