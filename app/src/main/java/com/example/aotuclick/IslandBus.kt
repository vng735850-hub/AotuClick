package com.example.autoclick

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.widget.Toast

/**
 * Cầu nối giữa các nguồn dữ liệu (thông báo, nhạc, cuộc gọi, hẹn giờ) và viên thuốc Dynamic Island.
 * Viên thuốc nằm trong IslandService (hoặc đường vòng); nguồn dữ liệu nằm trong IslandNotificationListener.
 */
object IslandBus {
    /** Viên thuốc đang hiển thị (null khi Dynamic Island tắt). */
    @Volatile var view: DynamicIslandView? = null

    // Cuộc gọi hiện tại
    @Volatile var callKey: String? = null
    @Volatile var answerAction: Notification.Action? = null
    @Volatile var declineAction: Notification.Action? = null
    @Volatile var endAction: Notification.Action? = null

    // Thông báo vừa hiện: chạm để mở
    @Volatile var noteIntent: PendingIntent? = null

    // Trình phát nhạc đang điều khiển
    @Volatile var mediaController: MediaController? = null

    fun active(): Boolean = view != null && ClickStore.islandOn

    fun clearCall() {
        callKey = null
        answerAction = null
        declineAction = null
        endAction = null
    }

    fun startTimer(seconds: Int) {
        view?.setTimer(seconds, seconds, true)
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
            "timerDone" -> {
                try {
                    val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
                } catch (e: Throwable) {
                }
                view?.showNotice("Hẹn giờ", "Đã hết giờ", "")
            }
        }
    }
}
