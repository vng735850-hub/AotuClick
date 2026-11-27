package com.example.autoclick

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** Các kiểu thao tác. */
enum class ActionType(val label: String, val color: Int) {
    TAP("Chạm", Color.rgb(229, 57, 53)),
    DOUBLE("Chạm đúp", Color.rgb(251, 140, 0)),
    HOLD("Giữ", Color.rgb(142, 36, 170)),
    SWIPE("Kéo", Color.rgb(67, 160, 71))
}

/**
 * Một nút (bước) trong kịch bản.
 * x, y   : tâm điểm bắt đầu (toạ độ màn hình)
 * x2, y2 : điểm kết thúc (chỉ dùng cho SWIPE)
 * delayMs: độ trễ SAU khi thực hiện nút này, trước khi sang nút kế tiếp
 * durationMs: thời gian giữ / kéo (0 = mặc định: Giữ 1000ms, Kéo 400ms)
 */
data class ClickAction(
    var type: ActionType = ActionType.TAP,
    var x: Float = 0f,
    var y: Float = 0f,
    var x2: Float = 0f,
    var y2: Float = 0f,
    var delayMs: Long = 500L,
    var durationMs: Long = 0L
) {
    fun effectiveDuration(): Long = when {
        durationMs > 0 -> durationMs
        type == ActionType.HOLD -> 1000L
        type == ActionType.SWIPE -> 400L
        else -> 40L
    }
}

/**
 * Một kịch bản: tên + danh sách nút + điều kiện dừng riêng.
 * loops       : số vòng lặp (0 = vô hạn)
 * stopMinutes : tự dừng sau số phút này (0 = không giới hạn)
 * stopTaps    : tự dừng sau số lần thao tác này (0 = không giới hạn)
 */
class Profile(
    var name: String,
    var loops: Int = 0,
    var stopMinutes: Int = 0,
    var stopTaps: Int = 0,
    val actions: MutableList<ClickAction> = mutableListOf()
)

/** Kho dữ liệu dùng chung giữa Activity và Service, lưu vào SharedPreferences. */
object ClickStore {
    val profiles = mutableListOf<Profile>()
    var activeIndex = 0

    // Cài đặt chung
    var defaultDelay = 500L  // độ trễ mặc định cho nút mới
    var panelAlpha = 90      // độ đậm bảng nổi (30..100)
    var jitterDelayPct = 0   // ngẫu nhiên độ trễ ± % (0 = tắt)
    var jitterPx = 0         // lệch vị trí ngẫu nhiên ± px (0 = tắt)

    private const val PREF = "nvpklick"
    private var loaded = false

    /** Kịch bản đang chọn (luôn có ít nhất một kịch bản). */
    fun current(): Profile {
        if (profiles.isEmpty()) profiles.add(Profile("Kịch bản 1"))
        if (activeIndex < 0 || activeIndex >= profiles.size) activeIndex = 0
        return profiles[activeIndex]
    }

    // Truy cập nhanh tới dữ liệu của kịch bản đang chọn
    val actions: MutableList<ClickAction>
        get() = current().actions

    var loops: Int
        get() = current().loops
        set(v) { current().loops = v }

    var stopMinutes: Int
        get() = current().stopMinutes
        set(v) { current().stopMinutes = v }

    var stopTaps: Int
        get() = current().stopTaps
        set(v) { current().stopTaps = v }

    fun selectProfile(i: Int) {
        if (i >= 0 && i < profiles.size) activeIndex = i
    }

    fun addProfile(name: String) {
        profiles.add(Profile(name))
        activeIndex = profiles.size - 1
    }

    fun duplicateActive() {
        val c = current()
        val copy = Profile(
            c.name + " (bản sao)",
            c.loops,
            c.stopMinutes,
            c.stopTaps,
            c.actions.map { it.copy() }.toMutableList()
        )
        profiles.add(copy)
        activeIndex = profiles.size - 1
    }

    /** Xoá kịch bản đang chọn. Nếu chỉ còn một kịch bản thì làm trống nó. */
    fun deleteActive() {
        if (profiles.size <= 1) {
            current().actions.clear()
            return
        }
        profiles.removeAt(activeIndex)
        activeIndex = activeIndex.coerceIn(0, profiles.size - 1)
    }

    // ---------- Lưu / tải ----------

    private fun parseActions(arr: JSONArray): MutableList<ClickAction> {
        val list = mutableListOf<ClickAction>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                ClickAction(
                    type = try {
                        ActionType.valueOf(o.getString("type"))
                    } catch (e: Exception) {
                        ActionType.TAP
                    },
                    x = o.optDouble("x").toFloat(),
                    y = o.optDouble("y").toFloat(),
                    x2 = o.optDouble("x2").toFloat(),
                    y2 = o.optDouble("y2").toFloat(),
                    delayMs = o.optLong("delay", 500L),
                    durationMs = o.optLong("duration", 0L)
                )
            )
        }
        return list
    }

    private fun actionsToJson(list: List<ClickAction>): JSONArray {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("type", a.type.name)
                    .put("x", a.x.toDouble())
                    .put("y", a.y.toDouble())
                    .put("x2", a.x2.toDouble())
                    .put("y2", a.y2.toDouble())
                    .put("delay", a.delayMs)
                    .put("duration", a.durationMs)
            )
        }
        return arr
    }

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        defaultDelay = p.getLong("defaultDelay", 500L)
        panelAlpha = p.getInt("panelAlpha", 90).coerceIn(30, 100)
        jitterDelayPct = p.getInt("jitterDelayPct", 0).coerceIn(0, 100)
        jitterPx = p.getInt("jitterPx", 0).coerceIn(0, 100)

        profiles.clear()
        activeIndex = 0
        try {
            val raw = p.getString("profiles", null)
            if (raw != null) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    profiles.add(
                        Profile(
                            name = o.optString("name", "Kịch bản ${i + 1}"),
                            loops = o.optInt("loops", 0),
                            stopMinutes = o.optInt("stopMinutes", 0),
                            stopTaps = o.optInt("stopTaps", 0),
                            actions = parseActions(o.optJSONArray("actions") ?: JSONArray())
                        )
                    )
                }
                activeIndex = p.getInt("active", 0)
            } else {
                // Chuyển dữ liệu từ v1.0 (chỉ có một danh sách nút)
                profiles.add(
                    Profile(
                        name = "Kịch bản 1",
                        loops = p.getInt("loops", 0),
                        actions = parseActions(JSONArray(p.getString("actions", "[]")))
                    )
                )
            }
        } catch (e: Exception) {
            profiles.clear()
        }
        current()
    }

    fun save(ctx: Context) {
        val arr = JSONArray()
        profiles.forEach { pr ->
            arr.put(
                JSONObject()
                    .put("name", pr.name)
                    .put("loops", pr.loops)
                    .put("stopMinutes", pr.stopMinutes)
                    .put("stopTaps", pr.stopTaps)
                    .put("actions", actionsToJson(pr.actions))
            )
        }
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong("defaultDelay", defaultDelay)
            .putInt("panelAlpha", panelAlpha)
            .putInt("jitterDelayPct", jitterDelayPct)
            .putInt("jitterPx", jitterPx)
            .putString("profiles", arr.toString())
            .putInt("active", activeIndex)
            .apply()
    }
}