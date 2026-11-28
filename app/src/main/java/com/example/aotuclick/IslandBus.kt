package com.example.autoclick

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.widget.Toast

/**
 * Cầu nối giữa các nguồn dữ liệu (thông báo, nhạc, cuộc gọi, hẹn giờ, auto-click) và viên thuốc Dynamic Island.
 * Viên thuốc nằm trong IslandService (hoặc đường vòng); nguồn dữ liệu nằm trong IslandNotificationListener.
 */
object IslandBus {
    /** Viên thuốc đang hiển thị (null khi Dynamic Island tắt). */
    @Volatile var view: DynamicIslandView? = null
    @Volatile var appCtx: Context? = null

    // Cuộc gọi hiện tại
    @Volatile var callKey: String? = null
    @Volatile var answerAction: Notification.Action? = null
    @Volatile var declineAction: Notification.Action? = null
    @Volatile var endAction: Notification.Action? = null

    // Thông báo vừa hiện: chạm để mở, nút trả lời nhanh
    @Volatile var noteIntent: PendingIntent? = null
    @Volatile var replyAction: Notification.Action? = null
    @Volatile var replyLabel: String = ""

    // Trình phát nhạc đang điều khiển
    @Volatile var mediaController: MediaController? = null

    // Thông báo tiến trình / chỉ đường đang theo dõi
    @Volatile var progKey: String? = null
    @Volatile var navKey: String? = null

    private val main = Handler(Looper.getMainLooper())

    fun active(): Boolean = view != null && ClickStore.islandOn

    fun clearCall() {
        callKey = null
        answerAction = null
        declineAction = null
        endAction = null
    }

    /** Đẩy lại mọi trạng thái đang có lên viên thuốc (khi island vừa bật hoặc đổi cài đặt). */
    fun refresh() {
        pushTimer()
        clickPush()
    }

    // ---------- Hẹn giờ và bấm giờ (nhiều cái cùng lúc) ----------
    private class TimerItem(
        val id: Int,
        val countUp: Boolean,
        val total: Int,
        var ms: Long,
        var running: Boolean,
        val label: String
    )

    private val timers = ArrayList<TimerItem>()
    private var nextId = 1
    private var lastTick = 0L
    private var ticking = false
    private var shownId = -1

    private val timerTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val dt = now - lastTick
            lastTick = now
            val finished = ArrayList<TimerItem>()
            for (t in timers) {
                if (!t.running) continue
                if (t.countUp) {
                    t.ms += dt
                } else {
                    t.ms -= dt
                    if (t.ms <= 0L) finished.add(t)
                }
            }
            for (t in finished) timers.remove(t)
            for (t in finished) timerDone(t)
            pushTimer()
            if (timers.isNotEmpty()) {
                main.postDelayed(this, 1000L)
            } else {
                ticking = false
            }
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        lastTick = SystemClock.elapsedRealtime()
        main.removeCallbacks(timerTick)
        main.postDelayed(timerTick, 1000L)
    }

    fun startTimer(seconds: Int) {
        val t = TimerItem(nextId++, false, seconds, seconds * 1000L, true, "")
        timers.add(t)
        shownId = t.id
        startTicking()
        pushTimer()
        view?.focusTimer()
    }

    fun startStopwatch() {
        val t = TimerItem(nextId++, true, 0, 0L, true, "")
        timers.add(t)
        shownId = t.id
        startTicking()
        pushTimer()
        view?.focusTimer()
    }

    private fun timerDone(t: TimerItem) {
        vibratePattern(longArrayOf(0, 400, 200, 400, 200, 400))
        view?.showNotice("Hẹn giờ", "Đã hết giờ", t.label)
    }

    fun pushTimer() {
        val v = view ?: return
        if (timers.isEmpty()) {
            shownId = -1
            v.clearTimer()
            return
        }
        var best: TimerItem? = timers.firstOrNull { it.id == shownId }
        if (best == null) {
            for (t in timers) {
                if (t.running && !t.countUp) {
                    val cur = best
                    if (cur == null || t.ms < cur.ms) best = t
                }
            }
        }
        val b = best ?: timers[0]
        shownId = b.id
        val value = if (b.countUp) (b.ms / 1000L).toInt() else ((b.ms + 999L) / 1000L).toInt()
        v.setTimer(b.total, value, b.running, b.countUp, timers.size)
    }

    // ---------- Trạng thái auto-click ----------
    @Volatile private var clickOn = false
    private var clickStart = 0L

    private val clickTick = object : Runnable {
        override fun run() {
            if (!clickOn) return
            clickPush()
            main.postDelayed(this, 1000L)
        }
    }

    fun clickPush() {
        val v = view ?: return
        if (!clickOn || !IslandPrefs.showClick) {
            v.clearClick()
            return
        }
        val svc = AutoClickService.instance
        val sec = ((SystemClock.elapsedRealtime() - clickStart) / 1000L).toInt()
        v.setClick(svc?.tapCount ?: 0, sec, svc?.isPaused ?: false)
    }

    /** FloatingService gọi khi bắt đầu hoặc dừng chạy kịch bản. */
    fun clickRunning(r: Boolean) {
        clickOn = r
        main.post {
            main.removeCallbacks(clickTick)
            if (r) {
                clickStart = SystemClock.elapsedRealtime()
                main.post(clickTick)
            } else {
                view?.clearClick()
            }
        }
    }

    // ---------- Rung ----------
    private fun vibratePattern(p: LongArray) {
        val c = appCtx ?: return
        try {
            val vib = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(VibrationEffect.createWaveform(p, -1))
        } catch (e: Throwable) {
        }
    }

    fun vibrateNote() {
        if (!IslandPrefs.vibrate) return
        val c = appCtx ?: return
        try {
            val vib = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Throwable) {
        }
    }

    private fun send(a: Notification.Action?): Boolean {
        val pi = a?.actionIntent ?: return false
        return try {
            pi.send()
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    /** Xử lý nút bấm từ viên thuốc. */
    fun onAction(ctx: Context, id: String) {
        when (id) {
            "accept" -> {
                if (!send(answerAction)) {
                    try {
                        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                        tm.acceptRingingCall()
                    } catch (e: Throwable) {
                        toast(ctx, "Không nghe máy được. Hãy cấp quyền Điện thoại cho Nvp Klick.")
                    }
                }
            }
            "decline", "end" -> {
                val a = if (id == "decline") declineAction else endAction
                if (!send(a)) {
                    var ok = false
                    if (Build.VERSION.SDK_INT >= 28) {
                        try {
                            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                            ok = tm.endCall()
                        } catch (e: Throwable) {
                            ok = false
                        }
                    }
                    if (!ok) toast(ctx, "Máy này không cho từ chối / kết thúc cuộc gọi từ island.")
                }
            }
            "play" -> {
                val mc = mediaController ?: return
                try {
                    val st = mc.playbackState?.state
                    if (st == PlaybackState.STATE_PLAYING) mc.transportControls.pause() else mc.transportControls.play()
                } catch (e: Throwable) {
                }
            }
            "prev" -> try { mediaController?.transportControls?.skipToPrevious() } catch (e: Throwable) { }
            "next" -> try { mediaController?.transportControls?.skipToNext() } catch (e: Throwable) { }
            "open" -> {
                try {
                    noteIntent?.send()
                } catch (e: Throwable) {
                }
            }
            "reply" -> {
                try {
                    val i = Intent(ctx, ReplyActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                } catch (e: Throwable) {
                    toast(ctx, "Không mở được hộp trả lời")
                }
            }
            "timerPause", "timerResume" -> {
                val t = timers.firstOrNull { it.id == shownId }
                if (t != null) t.running = (id == "timerResume")
                pushTimer()
            }
            "timerCancel" -> {
                timers.removeAll { it.id == shownId }
                shownId = -1
                pushTimer()
            }
            "clickStop" -> {
                try { AutoClickService.instance?.stop() } catch (e: Throwable) { }
            }
            "clickPause" -> {
                val fs = FloatingService.instance
                if (fs != null) {
                    fs.togglePause()
                } else {
                    val svc = AutoClickService.instance
                    if (svc != null) {
                        if (svc.isPaused) svc.resume() else svc.pause()
                    }
                }
            }
        }
    }
}
