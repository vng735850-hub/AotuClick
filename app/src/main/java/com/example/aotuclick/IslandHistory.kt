package com.example.autoclick

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Lưu tối đa 100 thông báo gần nhất đã hiện trên island để xem lại sau khi thông báo gốc bị ẩn. */
object IslandHistory {
    class Entry(val pkg: String, val app: String, val title: String, val text: String, val time: Long)

    private const val MAX = 100
    private val list = ArrayList<Entry>()
    private val intents = HashMap<Long, PendingIntent>()
    private var sp: SharedPreferences? = null

    @Synchronized
    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("island_history", Context.MODE_PRIVATE)
        sp = s
        try {
            val arr = JSONArray(s.getString("h", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Entry(
                        o.optString("p"), o.optString("a"), o.optString("t"),
                        o.optString("x"), o.optLong("ts")
                    )
                )
            }
        } catch (e: Throwable) {
            list.clear()
        }
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("p", e.pkg)
            o.put("a", e.app)
            o.put("t", e.title)
            o.put("x", e.text)
            o.put("ts", e.time)
            arr.put(o)
        }
        sp?.edit()?.putString("h", arr.toString())?.apply()
    }

    @Synchronized
    fun add(pkg: String, app: String, title: String, text: String, pi: PendingIntent?) {
        var t = System.currentTimeMillis()
        while (intents.containsKey(t) || list.any { it.time == t }) t++
        list.add(0, Entry(pkg, app, title, text, t))
        if (pi != null) intents[t] = pi
        while (list.size > MAX) {
            val r = list.removeAt(list.size - 1)
            intents.remove(r.time)
        }
        save()
    }

    @Synchronized
    fun snapshot(): List<Entry> = ArrayList(list)

    @Synchronized
    fun clear() {
        list.clear()
        intents.clear()
        save()
    }

    /** Mở lại thông báo (nếu còn) hoặc mở app gốc. */
    fun open(ctx: Context, e: Entry) {
        val pi = synchronized(this) { intents[e.time] }
        if (pi != null) {
            try {
                pi.send()
                return
            } catch (t: Throwable) {
            }
        }
        val li = ctx.packageManager.getLaunchIntentForPackage(e.pkg)
        if (li != null) {
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(li)
            } catch (t: Throwable) {
                Toast.makeText(ctx, "Không mở được app", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(ctx, "Không tìm thấy app", Toast.LENGTH_SHORT).show()
        }
    }
}
