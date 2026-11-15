package com.example.callbutton

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

object Bus {
    private val lines = ArrayList<String>()
    var onChange: (() -> Unit)? = null
    @Synchronized fun log(s: String) {
        lines.add(0, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + s)
        if (lines.size > 60) lines.removeAt(lines.size - 1)
        onChange?.invoke()
    }
    @Synchronized fun text(): String = lines.joinToString("\n\n")
}

val ANSWER_WORDS = listOf("answer", "accept", "opnemen", "aannemen", "beantwoorden", "رد")
val DECLINE_WORDS = listOf("decline", "reject", "weiger", "afwijzen", "رفض")
fun hit(t: CharSequence?, w: List<String>): Boolean {
    if (t == null) return false
    val s = t.toString().lowercase().replace(Regex("[\\u064B-\\u065F]"), "").trim()
    return w.any { if (it.length <= 3) s == it else s.contains(it) }
}

class MainActivity : Activity() {
    private lateinit var logView: TextView
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
        }
        fun btn(t: String, f: () -> Unit) {
            root.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        }
        btn("1. Meldingstoegang geven") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("2. Start luisteren") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            startForegroundService(Intent(this, HeadsetService::class.java))
        }
        btn("Test: knop 20 sec (zonder gesprek)") { HeadsetService.instance?.testMode() }
        btn("Test: neem automatisch op (30 sec)") {
            CallListener.autoUntil = SystemClock.uptimeMillis() + 30000
            Bus.log("Test actief: laat me nu bellen via WhatsApp")
        }
        btn("Stop") { stopService(Intent(this, HeadsetService::class.java)) }
        logView = TextView(this).apply { textSize = 14f }
        root.addView(ScrollView(this).apply { addView(logView) })
        setContentView(root)
        Bus.onChange = { runOnUiThread { logView.text = Bus.text() } }
        logView.text = Bus.text()
    }
    override fun onDestroy() { Bus.onChange = null; super.onDestroy() }
}

class HeadsetService : Service() {
    companion object { @Volatile var instance: HeadsetService? = null }

    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var lastPress = 0L
    private var track: AudioTrack? = null
    @Volatile private var running = true

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("ch", "Oordopjes", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ch").setContentTitle("CallButton wacht op een WhatsApp-gesprek")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)

        session = MediaSession(this, "CallButton")
        session.setCallback(object : MediaSession.Callback() {
            @Suppress("DEPRECATION")
            override fun onMediaButtonEvent(i: Intent): Boolean {
                val e: KeyEvent? = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                if (e != null) {
                    val down = e.action == KeyEvent.ACTION_DOWN
                    Bus.log("KNOP ONTVANGEN: " + KeyEvent.keyCodeToString(e.keyCode) + if (down) " (in)" else " (los)")
                    if (down && e.repeatCount == 0) press()
                }
                return true
            }
        })
        startSilence()
        Bus.log("Klaar. Muziekknoppen werken normaal. Bij een WhatsApp-gesprek neem ik de knop over.")
    }

    // Knop-modus: alleen AAN tijdens een gesprek, zodat muziek normaal blijft werken
    fun setCallMode(on: Boolean) {
        handler.post {
            try {
                if (on) {
                    session.setPlaybackState(
                        PlaybackState.Builder()
                            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                            .setState(PlaybackState.STATE_PLAYING, 0, 1f).build()
                    )
                    session.isActive = true
                    track?.play()
                    Bus.log("Knop-modus AAN")
                } else {
                    session.isActive = false
                    track?.pause()
                    Bus.log("Knop-modus UIT (muziekknoppen werken weer)")
                }
            } catch (e: Exception) {
                Bus.log("Fout: ${e.message}")
            }
        }
    }

    fun testMode() {
        setCallMode(true)
        handler.postDelayed({ if (CallListener.current == null) setCallMode(false) }, 20000)
    }

    private fun startSilence() {
        try {
            val rate = 8000
            val buf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
