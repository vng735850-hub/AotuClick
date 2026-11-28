package com.example.autoclick

import android.content.Context
import android.content.SharedPreferences

/** Cài đặt của Dynamic Island: vị trí, kích thước, thời gian, bật tắt từng loại, chế độ theo app. */
object IslandPrefs {
    const val MODE_ISLAND = 0   // hiện trên island, ẩn thông báo gốc
    const val MODE_BOTH = 1     // hiện trên island, giữ thông báo gốc
    const val MODE_KEEP = 2     // chỉ ở thanh thông báo, không hiện island
    const val MODE_BLOCK = 3    // chặn hẳn

    private var sp: SharedPreferences? = null
    private val modes = HashMap<String, Int>()

    @Synchronized
    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("island_settings", Context.MODE_PRIVATE)
        sp = s
        val raw = s.getString("modes", "") ?: ""
        for (part in raw.split(";")) {
            val i = part.indexOf('=')
            if (i > 0) modes[part.substring(0, i)] = part.substring(i + 1).toIntOrNull() ?: 0
        }
    }

    private fun gi(k: String, d: Int): Int = sp?.getInt(k, d) ?: d
    private fun gb(k: String, d: Boolean): Boolean = sp?.getBoolean(k, d) ?: d
    private fun pi(k: String, v: Int) {
        sp?.edit()?.putInt(k, v)?.apply()
    }
    private fun pb(k: String, v: Boolean) {
        sp?.edit()?.putBoolean(k, v)?.apply()
    }

    var offsetDp: Int
        get() = gi("offset", 0)
        set(v) { pi("offset", v) }
    var scalePct: Int
        get() = gi("scale", 100)
        set(v) { pi("scale", v) }
    var noticeSec: Int
        get() = gi("noticeSec", 5)
        set(v) { pi("noticeSec", v) }

    var alwaysShow: Boolean
        get() = gb("always", true)
        set(v) { pb("always", v) }
    var vibrate: Boolean
        get() = gb("vibrate", false)
        set(v) { pb("vibrate", v) }
    var showCall: Boolean
        get() = gb("showCall", true)
        set(v) { pb("showCall", v) }
    var showMusic: Boolean
        get() = gb("showMusic", true)
        set(v) { pb("showMusic", v) }
    var showNotif: Boolean
        get() = gb("showNotif", true)
        set(v) { pb("showNotif", v) }
    var showBattery: Boolean
        get() = gb("showBattery", true)
        set(v) { pb("showBattery", v) }
    var showClick: Boolean
        get() = gb("showClick", true)
        set(v) { pb("showClick", v) }
    var showProgress: Boolean
        get() = gb("showProgress", true)
        set(v) { pb("showProgress", v) }
    var showNav: Boolean
        get() = gb("showNav", true)
        set(v) { pb("showNav", v) }

    @Synchronized
    fun modeOf(pkg: String): Int = modes[pkg] ?: MODE_ISLAND

    @Synchronized
    fun setMode(pkg: String, m: Int) {
        if (m == MODE_ISLAND) modes.remove(pkg) else modes[pkg] = m
        val sb = StringBuilder()
        for ((k, v) in modes) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(k).append('=').append(v)
        }
        sp?.edit()?.putString("modes", sb.toString())?.apply()
    }

    fun modeLabel(m: Int): String = when (m) {
        MODE_BOTH -> "Island + giữ thông báo gốc"
        MODE_KEEP -> "Chỉ thanh thông báo (không hiện island)"
        MODE_BLOCK -> "Chặn hẳn"
        else -> "Island, ẩn thông báo gốc"
    }
}
