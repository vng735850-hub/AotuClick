package com.example.autoclick

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * Đọc thông báo, nhạc và cuộc gọi rồi đẩy lên Dynamic Island.
 * Cần người dùng bật "Truy cập thông báo" cho Nvp Klick. Thông báo thường (tin nhắn...) sẽ bị ẩn khỏi thanh thông báo
 * sau khi đã hiện trên island; thông báo đang chạy (nhạc, tải xuống...) được giữ nguyên.
 */
class IslandNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: IslandNotificationListener? = null

        fun isEnabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return s.contains(ctx.packageName)
        }

        private val answerRe = Regex("answer|accept|pick up|trả lời|nghe máy|nhận", RegexOption.IGNORE_CASE)
        private val declineRe = Regex("decline|reject|dismiss|từ chối|hủy", RegexOption.IGNORE_CASE)
        private val endRe = Regex("hang up|end call|end|disconnect|kết thúc|cúp|ngắt|decline", RegexOption.IGNORE_CASE)
        private val genericRe = Regex("incoming|ongoing|calling|cuộc gọi|đang gọi", RegexOption.IGNORE_CASE)
    }

    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var curController: MediaController? = null
    private var tm: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null
    private var lastCallState = TelephonyManager.CALL_STATE_IDLE

    private val ctrlCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            pushMedia()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            pushMedia()
        }

        override fun onSessionDestroyed() {
            pickController(msm?.let { safeSessions(it) })
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    private fun listenerComponent() = ComponentName(this, IslandNotificationListener::class.java)

    private fun safeSessions(m: MediaSessionManager): List<MediaController>? = try {
        m.getActiveSessions(listenerComponent())
    } catch (e: Throwable) {
        null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        try {
            val m = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm = m
            m.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(), main)
            pickController(safeSessions(m))
        } catch (e: Throwable) {
        }
        startPhoneListener()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cleanup()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Throwable) { }
        try { curController?.unregisterCallback(ctrlCb) } catch (e: Throwable) { }
        curController = null
        IslandBus.mediaController = null
        try { phoneListener?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) } } catch (e: Throwable) { }
        phoneListener = null
        if (instance === this) instance = null
    }

    // ---------- Nhạc ----------
    private fun pickController(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (best?.sessionToken != curController?.sessionToken) {
            try { curController?.unregisterCallback(ctrlCb) } catch (e: Throwable) { }
            curController = best
            IslandBus.mediaController = best
            if (best != null) {
                try { best.registerCallback(ctrlCb, main) } catch (e: Throwable) { }
            }
        }
        pushMedia()
    }

    /** Đẩy trạng thái nhạc hiện tại lên island (gọi lại khi island vừa bật). */
    fun pushMedia() {
        val v = IslandBus.view ?: return
        val c = curController
        if (c == null) {
            v.clearMusic()
            return
        }
        val st = c.playbackState
        val state = st?.state ?: PlaybackState.STATE_NONE
        if (state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED) {
            v.clearMusic()
            return
        }
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        if (title.isEmpty()) {
            v.clearMusic()
            return
        }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val len = ((md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000L).toInt()
        val pos = ((st?.position ?: 0L) / 1000L).toInt()
        v.setMusic(title, artist, state == PlaybackState.STATE_PLAYING, pos, if (len > 0) len else 0)
    }

    // ---------- Cuộc gọi (trạng thái máy) ----------
    fun startPhoneListener() {
        if (phoneListener != null) return
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        try {
            val t = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm = t
            val l = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    onCallState(state, phoneNumber)
                }
            }
            phoneListener = l
            t.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Throwable) {
            phoneListener = null
        }
    }

    private fun onCallState(state: Int, number: String?) {
        lastCallState = state
        val v = IslandBus.view ?: return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING ->
                v.setCall(1, number ?: "", 0)
            TelephonyManager.CALL_STATE_OFFHOOK ->
                v.setCall(2, "", 0)
            else -> {
                IslandBus.clearCall()
                v.setCall(0, "", 0)
            }
        }
    }

    // ---------- Thông báo ----------
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!IslandBus.active()) return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        val cat = n.category
        if (cat == Notification.CATEGORY_CALL) {
            handleCall(sbn, n)
            return
        }
        if (cat == Notification.CATEGORY_TRANSPORT) return
        if (cat == Notification.CATEGORY_PROGRESS || cat == Notification.CATEGORY_SERVICE) return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras
        if (ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Throwable) {
            sbn.packageName
        }
        val pi = n.contentIntent
        main.post {
            IslandBus.noteIntent = pi
            IslandBus.view?.showNotice(app, title, text)
        }
        // Ẩn thông báo gốc, chỉ hiện trên island
        try { cancelNotification(sbn.key) } catch (e: Throwable) { }
    }

    private fun handleCall(sbn: StatusBarNotification, n: Notification) {
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val ringingGuess = !chrono && n.fullScreenIntent != null
        val st = when (lastCallState) {
            TelephonyManager.CALL_STATE_RINGING -> 1
            TelephonyManager.CALL_STATE_OFFHOOK -> 2
            else -> if (ringingGuess) 1 else 2
        }
        val name = if (title.isNotEmpty() && !genericRe.containsMatchIn(title)) title
        else if (text.isNotEmpty()) text else title

        // Gắn các nút của thông báo gốc vào island
        val list = n.actions
        var answer: Notification.Action? = null
        var decline: Notification.Action? = null
        var end: Notification.Action? = null
        if (list != null) {
            if (st == 1) {
                for (a in list) {
                    val t = a.title?.toString() ?: ""
                    if (answer == null && answerRe.containsMatchIn(t)) answer = a
                    else if (decline == null && declineRe.containsMatchIn(t)) decline = a
                }
                if (answer == null && decline == null && list.size >= 2) {
                    decline = list[0]
                    answer = list[1]
                }
            } else {
                for (a in list) {
                    val t = a.title?.toString() ?: ""
                    if (end == null && endRe.containsMatchIn(t)) end = a
                }
            }
        }
        val secs = if (st == 2 && chrono) {
            ((System.currentTimeMillis() - n.`when`) / 1000L).toInt().coerceAtLeast(0)
        } else 0
        main.post {
            IslandBus.callKey = sbn.key
            if (st == 1) {
                IslandBus.answerAction = answer
                IslandBus.declineAction = decline
            } else {
                IslandBus.endAction = end
            }
            IslandBus.view?.setCall(st, name, secs)
        }
        // Chỉ ẩn thông báo cuộc gọi khi đã theo dõi được trạng thái máy (nếu không sẽ mất tín hiệu kết thúc cuộc gọi)
        if (phoneListener != null) {
            try { cancelNotification(sbn.key) } catch (e: Throwable) { }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (phoneListener == null && sbn.key == IslandBus.callKey) {
            main.post {
                IslandBus.clearCall()
                IslandBus.view?.setCall(0, "", 0)
            }
        }
    }
}
