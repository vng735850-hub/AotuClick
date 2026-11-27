package com.example.autoclick

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val cBg = Color.rgb(15, 17, 23)
    private val cCard = Color.rgb(26, 29, 38)
    private val cField = Color.rgb(38, 42, 54)
    private val cText = Color.WHITE
    private val cSub = Color.rgb(150, 156, 170)
    private val cGreen = Color.rgb(46, 160, 67)
    private val cRed = Color.rgb(211, 47, 47)
    private val cBlue = Color.rgb(37, 99, 235)
    private val cGray = Color.rgb(55, 62, 80)

    private lateinit var startBtn: IconView
    private lateinit var startLabel: TextView
    private lateinit var overlayDot: View
    private lateinit var accDot: View
    private lateinit var overlayBtn: TextView
    private lateinit var accBtn: TextView
    private lateinit var profileName: TextView
    private lateinit var profileInfo: TextView
    private lateinit var loopsInput: EditText
    private lateinit var stopMinInput: EditText
    private lateinit var stopTapsInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var jDelayInput: EditText
    private lateinit var jPxInput: EditText
    private lateinit var alphaLabel: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- Các hàm dựng giao diện ----------

    private fun roundBg(color: Int, radiusDp: Int, strokeColor: Int, strokeDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(radiusDp).toFloat()
        d.setColor(color)
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor)
        return d
    }

    private fun ovalBg(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        return d
    }

    private fun makeCard(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = roundBg(cCard, 18, 0, 0)
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, dp(12))
        c.layoutParams = lp
        return c
    }

    private fun textView(t: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = sizeSp
        v.setTextColor(color)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun pill(t: String, bg: Int, onClick: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.WHITE)
        v.textSize = 13f
        v.gravity = Gravity.CENTER
        v.setPadding(dp(14), dp(8), dp(14), dp(8))
        v.background = roundBg(bg, 18, 0, 0)
        v.setOnClickListener { onClick() }
        return v
    }

    /** Nút bo tròn chia đều chiều ngang trong một hàng. */
    private fun weightPill(t: String, bg: Int, onClick: () -> Unit): TextView {
        val v = pill(t, bg, onClick)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(3), dp(10), dp(3), 0)
        v.layoutParams = lp
        v.setPadding(dp(6), dp(10), dp(6), dp(10))
        return v
    }

    private fun numberInput(value: String): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(value)
        e.setTextColor(cText)
        e.textSize = 16f
        e.background = roundBg(cField, 10, 0, 0)
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(4), 0, 0)
        e.layoutParams = lp
        return e
    }

    private fun addLabeled(parent: LinearLayout, label: String, input: EditText) {
        val l = textView(label, 13f, cSub, false)
        l.setPadding(0, dp(12), 0, 0)
        parent.addView(l)
        parent.addView(input)
    }

    private fun sectionLabel(t: String): TextView {
        val v = textView(t, 12f, cBlue, true)
        v.setPadding(0, dp(14), 0, 0)
        return v
    }

    private fun permRow(title: String, dot: View, btn: TextView): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, dp(6))

        val dlp = LinearLayout.LayoutParams(dp(10), dp(10))
        dlp.setMargins(0, 0, dp(12), 0)
        dot.layoutParams = dlp
        row.addView(dot)

        val tv = textView(title, 14f, cText, false)
        tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(tv)

        row.addView(btn)
        return row
    }

    private fun versionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ---------- Vòng đời ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClickStore.ensureLoaded(this)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(40), dp(18), dp(24))

        // Tiêu đề
        root.addView(textView("Nvp Klick", 28f, cText, true))
        val ver = versionName()
        val subText = if (ver.isNotEmpty()) {
            "Tự động chạm · giữ · kéo  ·  v$ver"
        } else {
            "Tự động chạm · giữ · kéo"
        }
        val sub = textView(subText, 14f, cSub, false)
        sub.setPadding(0, dp(2), 0, dp(18))
        root.addView(sub)

        // Thẻ nút Bắt đầu
        val startCard = makeCard()
        startCard.gravity = Gravity.CENTER_HORIZONTAL
        startBtn = IconView(this, IconKind.PLAY)
        val slp = LinearLayout.LayoutParams(dp(112), dp(112))
        slp.setMargins(0, dp(6), 0, dp(10))
        startBtn.layoutParams = slp
        startBtn.setOnClickListener { onStartClicked() }
        startLabel = textView("", 14f, cSub, false)
        startLabel.gravity = Gravity.CENTER
        startCard.addView(startBtn)
        startCard.addView(startLabel)
        root.addView(startCard)

        // Thẻ quyền
        val permCard = makeCard()
        val permTitle = textView("Quyền cần thiết", 16f, cText, true)
        permTitle.setPadding(0, 0, 0, dp(6))
        permCard.addView(permTitle)

        overlayDot = View(this)
        overlayBtn = pill("Cấp quyền", cBlue) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        permCard.addView(permRow("Hiển thị trên ứng dụng khác", overlayDot, overlayBtn))

        accDot = View(this)
        accBtn = pill("Bật", cBlue) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permCard.addView(permRow("Dịch vụ Trợ năng (Accessibility)", accDot, accBtn))
        root.addView(permCard)

        // Thẻ kịch bản
        val profCard = makeCard()
        profCard.addView(textView("Kịch bản", 16f, cText, true))
        profileName = textView("", 18f, cText, true)
        profileName.setPadding(0, dp(6), 0, 0)
        profCard.addView(profileName)
        profileInfo = textView("", 13f, cSub, false)
        profCard.addView(profileInfo)

        val rowA = LinearLayout(this)
        rowA.orientation = LinearLayout.HORIZONTAL
        rowA.addView(weightPill("Chọn", cBlue) { chooseProfile() })
        rowA.addView(weightPill("Mới", cGreen) { newProfile() })
        rowA.addView(weightPill("Nhân đôi", cGray) { duplicateProfile() })
        profCard.addView(rowA)

        val rowB = LinearLayout(this)
        rowB.orientation = LinearLayout.HORIZONTAL
        rowB.addView(weightPill("Đổi tên", cGray) { renameProfile() })
        rowB.addView(weightPill("Xoá", cRed) { deleteProfile() })
        profCard.addView(rowB)
        root.addView(profCard)

        // Thẻ cài đặt
        val setCard = makeCard()
        setCard.addView(textView("Cài đặt", 16f, cText, true))

        setCard.addView(sectionLabel("THEO KỊCH BẢN"))
        loopsInput = numberInput(ClickStore.loops.toString())
        addLabeled(setCard, "Số vòng lặp (0 = lặp vô hạn)", loopsInput)
        stopMinInput = numberInput(ClickStore.stopMinutes.toString())
        addLabeled(setCard, "Tự dừng sau (phút) (0 = không giới hạn)", stopMinInput)
        stopTapsInput = numberInput(ClickStore.stopTaps.toString())
        addLabeled(setCard, "Tự dừng sau số lần thao tác (0 = không giới hạn)", stopTapsInput)

        setCard.addView(sectionLabel("CHUNG"))
        delayInput = numberInput(ClickStore.defaultDelay.toString())
        addLabeled(setCard, "Độ trễ mặc định cho nút mới (ms)", delayInput)
        jDelayInput = numberInput(ClickStore.jitterDelayPct.toString())
        addLabeled(setCard, "Ngẫu nhiên độ trễ ± (%) (0 = tắt)", jDelayInput)
        jPxInput = numberInput(ClickStore.jitterPx.toString())
        addLabeled(setCard, "Lệch vị trí chạm ngẫu nhiên ± (px) (0 = tắt)", jPxInput)

        alphaLabel = textView("", 13f, cSub, false)
        alphaLabel.setPadding(0, dp(12), 0, dp(4))
        setCard.addView(alphaLabel)

        val seek = SeekBar(this)
        seek.max = 70
        seek.progress = ClickStore.panelAlpha - 30
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                ClickStore.panelAlpha = p + 30
                updateAlphaLabel()
                FloatingService.instance?.applyAlpha()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                ClickStore.save(this@MainActivity)
            }
        })
        setCard.addView(seek)
        updateAlphaLabel()
        root.addView(setCard)

        // Nút xoá tất cả nút của kịch bản
        val clearBtn = pill("Xoá tất cả nút của kịch bản này", Color.TRANSPARENT) {
            ClickStore.actions.clear()
            ClickStore.save(this@MainActivity)
            FloatingService.instance?.refresh()
            refreshProfileUi()
            Toast.makeText(this@MainActivity, "Đã xoá tất cả nút", Toast.LENGTH_SHORT).show()
        }
        clearBtn.background = roundBg(Color.TRANSPARENT, 18, cRed, 1)
        clearBtn.setTextColor(cRed)
        clearBtn.setPadding(dp(14), dp(12), dp(14), dp(12))
        val clp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        clp.setMargins(0, 0, 0, dp(12))
        clearBtn.layoutParams = clp
        root.addView(clearBtn)

        // Hướng dẫn
        val helpCard = makeCard()
        val helpTitle = textView("Cách dùng", 16f, cText, true)
        helpTitle.setPadding(0, 0, 0, dp(6))
        helpCard.addView(helpTitle)
        helpCard.addView(
            textView(
                "1. Cấp 2 quyền ở trên (hiển thị trên ứng dụng khác và Trợ năng).\n" +
                    "2. Nhấn nút ▶ lớn để mở bảng điều khiển nổi.\n" +
                    "3. Trên bảng nổi: ＋ thêm nút chạm, ➜ thêm nút kéo, － xoá nút cuối, ⚙ mở cài đặt, ˄ thu nhỏ thành bong bóng (chạm bong bóng để mở lại), ✕ đóng.\n" +
                    "4. Kéo từng nút số tới vị trí cần chạm. Chạm vào nút (không kéo) để chọn kiểu Chạm / Chạm đúp / Giữ / Kéo, chỉnh độ trễ, đổi thứ tự hoặc xoá.\n" +
                    "5. Kiểu Kéo có thêm chấm xanh ▸ là điểm kết thúc.\n" +
                    "6. Nhấn nút ▶ trên bảng nổi để chạy, nhấn lại để dừng. Ô số dưới nút ▶ hiện thời gian đã chạy (giờ:phút:giây) và số lần thao tác (×N).\n" +
                    "7. Mỗi kịch bản có danh sách nút riêng và điều kiện tự dừng riêng (số vòng, số phút, số lần thao tác).\n" +
                    "8. Thông báo của app có nút Dừng, Mở bảng và Tắt để điều khiển nhanh khi bảng đang thu nhỏ.",
                13f, cSub, false
            )
        )
        root.addView(helpCard)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(cBg)
        scroll.addView(root)
        setContentView(scroll)

        refreshProfileUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (::profileName.isInitialized) refreshProfileUi()
    }

    override fun onPause() {
        super.onPause()
        if (::jPxInput.isInitialized) saveSettings()
    }

    // ---------- Kịch bản ----------

    private fun refreshProfileUi() {
        val c = ClickStore.current()
        profileName.text = c.name
        profileInfo.text = "Kịch bản ${ClickStore.activeIndex + 1}/${ClickStore.profiles.size} · ${c.actions.size} nút"
    }

    /** Nạp lại các ô nhập theo kịch bản đang chọn. */
    private fun loadFields() {
        loopsInput.setText(ClickStore.loops.toString())
        stopMinInput.setText(ClickStore.stopMinutes.toString())
        stopTapsInput.setText(ClickStore.stopTaps.toString())
        delayInput.setText(ClickStore.defaultDelay.toString())
        jDelayInput.setText(ClickStore.jitterDelayPct.toString())
        jPxInput.setText(ClickStore.jitterPx.toString())
    }

    private fun afterProfileChange() {
        ClickStore.save(this)
        FloatingService.instance?.refresh()
        loadFields()
        refreshProfileUi()
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this)
        input.setText(initial)
        input.isSingleLine = true
        input.setTextColor(Color.WHITE)
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Lưu") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) onOk(n)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun chooseProfile() {
        saveSettings()
        val names = ClickStore.profiles.map { it.name + " (" + it.actions.size + " nút)" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Chọn kịch bản")
            .setItems(names) { _, which ->
                ClickStore.selectProfile(which)
                afterProfileChange()
            }
            .show()
    }

    private fun newProfile() {
        saveSettings()
        askName("Kịch bản mới", "Kịch bản ${ClickStore.profiles.size + 1}") { n ->
            ClickStore.addProfile(n)
            afterProfileChange()
        }
    }

    private fun duplicateProfile() {
        saveSettings()
        ClickStore.duplicateActive()
        afterProfileChange()
    }

    private fun renameProfile() {
        askName("Đổi tên kịch bản", ClickStore.current().name) { n ->
            ClickStore.current().name = n
            afterProfileChange()
        }
    }

    private fun deleteProfile() {
        val only = ClickStore.profiles.size <= 1
        val msg = if (only) {
            "Đây là kịch bản duy nhất, các nút sẽ được xoá hết nhưng kịch bản vẫn giữ lại."
        } else {
            "Xoá kịch bản \"" + ClickStore.current().name + "\"?"
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Xoá kịch bản")
            .setMessage(msg)
            .setPositiveButton("Xoá") { _, _ ->
                ClickStore.deleteActive()
                afterProfileChange()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ---------- Xử lý ----------

    private fun updateAlphaLabel() {
        alphaLabel.text = "Độ đậm bảng điều khiển nổi: ${ClickStore.panelAlpha}%"
    }

    private fun intOf(e: EditText, max: Int): Int =
        e.text.toString().toIntOrNull()?.coerceIn(0, max) ?: 0

    private fun saveSettings() {
        ClickStore.loops = intOf(loopsInput, 1_000_000)
        ClickStore.stopMinutes = intOf(stopMinInput, 100_000)
        ClickStore.stopTaps = intOf(stopTapsInput, 100_000_000)
        ClickStore.defaultDelay = delayInput.text.toString().toLongOrNull()?.coerceAtLeast(0L) ?: 500L
        ClickStore.jitterDelayPct = intOf(jDelayInput, 100)
        ClickStore.jitterPx = intOf(jPxInput, 100)
        ClickStore.save(this)
    }

    private fun onStartClicked() {
        saveSettings()
        if (FloatingService.instance != null) {
            stopService(Intent(this, FloatingService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({ refreshStatus() }, 300L)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Hãy cấp quyền hiển thị trên ứng dụng khác trước", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        startForegroundService(Intent(this, FloatingService::class.java))
        moveTaskToBack(true)
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accOk = AutoClickService.instance != null
        overlayDot.background = ovalBg(if (overlayOk) cGreen else cRed)
        accDot.background = ovalBg(if (accOk) cGreen else cRed)
        overlayBtn.text = if (overlayOk) "Đã cấp" else "Cấp quyền"
        accBtn.text = if (accOk) "Đã bật" else "Bật"

        val on = FloatingService.instance != null
        startBtn.kind = if (on) IconKind.STOP else IconKind.PLAY
        startBtn.background = ovalBg(if (on) cRed else cGreen)
        startBtn.invalidate()
        startLabel.text = if (on) {
            "Bảng điều khiển đang bật\nNhấn để tắt"
        } else {
            "Nhấn để mở bảng điều khiển nổi"
        }
    }
}