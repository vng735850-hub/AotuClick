package com.example.autoclick

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity() {

    companion object {
        private const val REQ_EXPORT = 101
        private const val REQ_IMPORT = 102
    }

    // Màu nền/chữ đổi theo chế độ sáng/tối (AppTheme); màu nút giữ cố định
    private val cBg: Int get() = AppTheme.bg
    private val cCard: Int get() = AppTheme.card
    private val cField: Int get() = AppTheme.field
    private val cText: Int get() = AppTheme.text
    private val cSub: Int get() = AppTheme.sub
    private val cGreen = Color.rgb(46, 160, 67)
    private val cRed = Color.rgb(211, 47, 47)
    private val cBlue = Color.rgb(37, 99, 235)
    private val cGray: Int get() = AppTheme.gray
    private val cPink = Color.rgb(216, 27, 96)
    private val cOff = Color.rgb(84, 94, 110)

    private lateinit var container: FrameLayout
    private lateinit var themeSwitch: DayNightSwitch
    private val pageScrolls = arrayOfNulls<ScrollView>(3)
    private lateinit var navBar: NotchTabBar
    private var currentTab = 0

    private lateinit var modeBtn1: TextView
    private lateinit var modeBtn2: TextView
    private lateinit var overlayDot: View
    private lateinit var accDot: View
    private lateinit var overlayBtn: TextView
    private lateinit var accBtn: TextView
    private lateinit var batDot: View
    private lateinit var batBtn: TextView
    private lateinit var undoBtn: TextView

    // Trạng thái trống của trang Kịch bản (thư mục nuốt giấy)
    private lateinit var profCardV: LinearLayout
    private lateinit var clearBtnV: TextView
    private lateinit var emptyWrap: FrameLayout
    private lateinit var emptyUndo: TextView
    private var emptyArt: EmptyFolderView? = null
    private var emptyFly: FlyPaperView? = null
    private var emptyImportBtn: View? = null
    private var lastBlank: Boolean? = null
    private var swallowing = false
    private val ui = Handler(Looper.getMainLooper())
    private val springI = PathInterpolator(.34f, 1.45f, .64f, 1f)
    private lateinit var startDelayInput: EditText
    private lateinit var startAtInput: EditText
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

    private fun textInput(value: String, hint: String): EditText {
        val e = numberInput(value)
        e.inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        e.hint = hint
        e.setHintTextColor(cSub)
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
        currentTab = (savedInstanceState?.getInt("tab", 0) ?: 0).coerceIn(0, 2)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Nút ngày–đêm ở góc trên phải (giữ nguyên một đối tượng để hiệu ứng không bị ngắt khi vẽ lại màu)
        themeSwitch = DayNightSwitch(this)
        themeSwitch.setChecked(ClickStore.darkMode, false)
        themeSwitch.onChanged = { dark -> onThemeToggled(dark) }

        // Thanh tab dưới: Trang chủ · Kịch bản · Cài đặt (giữ một đối tượng để hiệu ứng không bị ngắt)
        navBar = NotchTabBar(this, listOf("Trang chủ", "Kịch bản", "Cài đặt"))
        navBar.onSelect = { i -> showTab(i) }

        container = FrameLayout(this)
        setContentView(container)
        buildUi()

        // Ẩn thanh tab khi bàn phím mở để không chiếm chỗ ô nhập
        container.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            container.getWindowVisibleDisplayFrame(r)
            val screenH = container.rootView.height
            val keyboardOpen = screenH > 0 && screenH - r.bottom > screenH * 0.15
            val want = if (keyboardOpen) View.GONE else View.VISIBLE
            if (navBar.visibility != want) navBar.visibility = want
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", currentTab)
    }

    /** Chuyển trang theo tab: lưu ô nhập, đóng bàn phím rồi chỉ hiện trang được chọn. */
    private fun showTab(i: Int) {
        if (i == currentTab) return
        saveSettings()
        currentTab = i
        currentFocus?.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        for (k in pageScrolls.indices) {
            pageScrolls[k]?.visibility = if (k == i) View.VISIBLE else View.GONE
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        window.decorView.systemUiVisibility = if (AppTheme.dark) {
            0
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    /** Đổi chế độ sáng/tối: lưu, đổi màu bảng nổi, dựng lại màn hình với màu mới. */
    private fun onThemeToggled(dark: Boolean) {
        saveSettings()
        ClickStore.darkMode = dark
        ClickStore.save(this)
        FloatingService.instance?.applyTheme()
        buildUi()
    }

    private fun buildUi() {
        val keepScrolls = IntArray(3) { pageScrolls[it]?.scrollY ?: 0 }
        applySystemBars()
        container.setBackgroundColor(cBg)

        // Khung chính: tiêu đề cố định ở trên, các trang cuộn ở giữa, thanh tab ở dưới
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        fun newPage(): LinearLayout {
            val p = LinearLayout(this)
            p.orientation = LinearLayout.VERTICAL
            p.setPadding(dp(18), dp(4), dp(18), dp(12))
            return p
        }
        val home = newPage()
        val scripts = newPage()
        val settings = newPage()

        // Tiêu đề (trái) + nút ngày–đêm (góc trên phải)
        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        titleCol.addView(textView("Nvp Klick", 28f, cText, true))
        val ver = versionName()
        val subText = if (ver.isNotEmpty()) {
            "Tự động chạm · giữ · kéo  ·  v$ver"
        } else {
            "Tự động chạm · giữ · kéo"
        }
        val sub = textView(subText, 14f, cSub, false)
        sub.setPadding(0, dp(2), 0, 0)
        titleCol.addView(sub)

        (themeSwitch.parent as? ViewGroup)?.removeView(themeSwitch)
        val swLp = LinearLayout.LayoutParams(dp(88), dp(37))
        swLp.setMargins(dp(12), dp(4), 0, 0)
        themeSwitch.layoutParams = swLp

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.setPadding(dp(18), dp(40), dp(18), dp(12))
        header.addView(titleCol)
        header.addView(themeSwitch)

        // Thẻ chế độ 1 mục tiêu
        val card1 = makeCard()
        card1.addView(cardTitle("CHẾ ĐỘ 1 MỤC TIÊU"))
        card1.addView(menuRow(MenuGlyph.GEAR, "Cài đặt") { goTab(2) })
        modeBtn1 = modeButton(1)
        card1.addView(modeBtn1)
        home.addView(card1)

        // Thẻ chế độ nhiều mục tiêu
        val card2 = makeCard()
        card2.addView(cardTitle("CHẾ ĐỘ NHIỀU MỤC TIÊU"))
        card2.addView(menuRow(MenuGlyph.GEAR, "Cài đặt") { goTab(2) })
        card2.addView(menuRow(MenuGlyph.INFO, "Hướng dẫn") { showGuide() })
        card2.addView(menuRow(MenuGlyph.WRENCH, "Quản lý cấu hình") { goTab(1) })
        modeBtn2 = modeButton(2)
        card2.addView(modeBtn2)
        home.addView(card2)

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

        batDot = View(this)
        batBtn = pill("Mở", cBlue) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Máy không mở được màn hình này", Toast.LENGTH_SHORT).show()
            }
        }
        permCard.addView(permRow("Không tối ưu pin (chạy nền lâu)", batDot, batBtn))
        permCard.addView(
            textView(
                "Chạy nền lâu: chọn \"Không tối ưu\" cho Nvp Klick. Máy Xiaomi/Oppo/Vivo/Samsung: vào Cài đặt > Pin > Ứng dụng này, bật \"Tự khởi chạy\" và \"Không hạn chế nền\".",
                12f, cSub, false
            )
        )
        home.addView(permCard)

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
        undoBtn = weightPill("Hoàn tác", cBlue) {
            ClickStore.undo()
            afterProfileChange()
            Toast.makeText(this@MainActivity, "Đã hoàn tác", Toast.LENGTH_SHORT).show()
        }
        rowB.addView(undoBtn)
        profCard.addView(rowB)

        val rowC = LinearLayout(this)
        rowC.orientation = LinearLayout.HORIZONTAL
        rowC.addView(weightPill("Xuất file JSON", cGray) { exportProfiles() })
        rowC.addView(weightPill("Nhập file JSON", cGray) { importProfiles() })
        profCard.addView(rowC)
        scripts.addView(profCard)
        profCardV = profCard

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

        setCard.addView(sectionLabel("HẸN GIỜ & PHÍM TẮT"))
        startDelayInput = numberInput(ClickStore.startDelaySec.toString())
        addLabeled(setCard, "Đếm ngược trước khi chạy (giây) (0 = chạy ngay)", startDelayInput)
        startAtInput = textInput(ClickStore.startAt, "vd 08:00:00")
        addLabeled(setCard, "Hẹn giờ bắt đầu lúc HH:mm:ss (để trống = không hẹn; nếu giờ đã qua thì chờ tới ngày mai)", startAtInput)

        val hotkey = CheckBox(this)
        hotkey.text = "Phím giảm âm lượng để chạy/dừng (cần bảng nổi đang mở)"
        hotkey.setTextColor(cText)
        hotkey.textSize = 13f
        hotkey.setPadding(0, dp(12), 0, 0)
        hotkey.isChecked = ClickStore.volumeHotkey
        hotkey.setOnCheckedChangeListener { _, checked ->
            ClickStore.volumeHotkey = checked
            ClickStore.save(this@MainActivity)
        }
        setCard.addView(hotkey)

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
        settings.addView(setCard)

        // Nút xoá tất cả nút của kịch bản
        val clearBtn = pill("Xoá tất cả nút của kịch bản này", Color.TRANSPARENT) {
            ClickStore.snapshot()
            ClickStore.actions.clear()
            ClickStore.save(this@MainActivity)
            FloatingService.instance?.refresh()
            refreshProfileUi()
            Toast.makeText(this@MainActivity, "Đã xoá tất cả nút (có thể Hoàn tác)", Toast.LENGTH_SHORT).show()
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
        scripts.addView(clearBtn)
        clearBtnV = clearBtn
        scripts.addView(buildEmptyCard(), 0)

        val pages = arrayOf(home, scripts, settings)
        val frame = FrameLayout(this)
        for (i in pages.indices) {
            val sc = ScrollView(this)
            sc.setBackgroundColor(cBg)
            sc.addView(pages[i])
            sc.visibility = if (i == currentTab) View.VISIBLE else View.GONE
            frame.addView(
                sc,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            pageScrolls[i] = sc
            val keep = keepScrolls[i]
            if (keep > 0) sc.post { sc.scrollTo(0, keep) }
        }

        (navBar.parent as? ViewGroup)?.removeView(navBar)
        col.addView(header)
        col.addView(
            frame,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        col.addView(
            navBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.removeAllViews()
        container.addView(
            col,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        navBar.setSelectedIndex(currentTab)
        navBar.applyTheme()

        refreshProfileUi()
        refreshStatus()
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
        undoBtn.visibility = if (ClickStore.hasUndo) View.VISIBLE else View.GONE
        profileInfo.text = "Kịch bản ${ClickStore.activeIndex + 1}/${ClickStore.profiles.size} · ${c.actions.size} nút"
        updateEmptyState(true)
    }

    // ---------- Trạng thái trống: thư mục nuốt giấy ----------

    private val animOn: Boolean get() = ValueAnimator.areAnimatorsEnabled()

    private fun buildEmptyCard(): FrameLayout {
        lastBlank = null
        swallowing = false
        val wrap = FrameLayout(this)
        wrap.clipChildren = false
        val wlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        wlp.setMargins(0, 0, 0, dp(12))
        wrap.layoutParams = wlp

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.clipChildren = false
        card.background = roundBg(cCard, 18, 0, 0)
        card.setPadding(dp(16), dp(22), dp(16), dp(20))

        val art = EmptyFolderView(this)
        emptyArt = art
        card.addView(art, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val title = textView("Chưa có kịch bản", 18f, cText, true)
        title.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tlp.topMargin = dp(12)
        card.addView(title, tlp)

        val sub = textView("Nhập file JSON hoặc tạo kịch bản đầu tiên", 13.5f, cSub, false)
        sub.gravity = Gravity.CENTER
        val slp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        slp.topMargin = dp(6)
        card.addView(sub, slp)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.clipChildren = false
        val newBtn = tplButton("Tạo mới", Color.rgb(61, 139, 255), IconKind.DOC) { v ->
            if (swallowing) return@tplButton
            askName("Kịch bản mới", ClickStore.DEFAULT_NAME) { n ->
                saveSettings()
                swallow(v, Color.rgb(61, 139, 255)) {
                    ClickStore.current().name = n
                    ClickStore.userMade = true
                    afterProfileChange()
                }
            }
        }
        val impBtn = tplButton("Nhập JSON", Color.rgb(255, 138, 61), IconKind.IMPORT) {
            if (!swallowing) importProfiles()
        }
        emptyImportBtn = impBtn
        row.addView(newBtn)
        row.addView(impBtn)
        val rlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rlp.topMargin = dp(18)
        card.addView(row, rlp)

        // Sau khi xoá hết, vẫn hoàn tác được (nút Hoàn tác ở thẻ Kịch bản đang ẩn)
        emptyUndo = pill("Hoàn tác lần xoá vừa rồi", cBlue) {
            ClickStore.undo()
            afterProfileChange()
            Toast.makeText(this@MainActivity, "Đã hoàn tác", Toast.LENGTH_SHORT).show()
        }
        emptyUndo.visibility = View.GONE
        val ulp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        ulp.topMargin = dp(14)
        card.addView(emptyUndo, ulp)

        wrap.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        val fly = FlyPaperView(this)
        emptyFly = fly
        wrap.addView(fly, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        emptyWrap = wrap
        return wrap
    }

    /** Nút mẫu: chạm giữ thì thư mục hé mở (như hover), nhả ra thì bấm. */
    private fun tplButton(label: String, color: Int, kind: IconKind, onClick: (View) -> Unit): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER_VERTICAL
        b.setPadding(dp(8), 0, dp(14), 0)
        b.background = roundBg(cField, 13, 0, 0)
        val ic = IconView(this, kind)
        ic.background = roundBg(color, 8, 0, 0)
        val ilp = LinearLayout.LayoutParams(dp(26), dp(26))
        ilp.rightMargin = dp(7)
        b.addView(ic, ilp)
        b.addView(textView(label, 13f, cSub, true))
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
        blp.setMargins(dp(3), 0, dp(3), 0)
        b.layoutParams = blp
        b.isClickable = true
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    emptyArt?.setHover(true)
                    v.animate().scaleX(.94f).scaleY(.94f).setDuration(120L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    emptyArt?.setHover(false)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(350L).setInterpolator(springI).start()
                }
            }
            false
        }
        b.setOnClickListener { onClick(it) }
        return b
    }

    /**
     * Tờ giấy bay từ nút vào thư mục, thư mục nuốt rồi mới chạy onDone (tạo kịch bản).
     * Tắt hoạt ảnh trong hệ thống (giảm chuyển động) thì chạy onDone ngay.
     */
    private fun swallow(from: View, color: Int, delayMs: Long = 0L, onDone: () -> Unit) {
        val art = emptyArt
        val fly = emptyFly
        if (swallowing) return
        if (art == null || fly == null || !::emptyWrap.isInitialized ||
            emptyWrap.visibility != View.VISIBLE || !animOn
        ) {
            onDone()
            return
        }
        swallowing = true
        ui.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val wl = IntArray(2)
            val fl = IntArray(2)
            val al = IntArray(2)
            emptyWrap.getLocationInWindow(wl)
            from.getLocationInWindow(fl)
            art.getLocationInWindow(al)
            val x0 = fl[0] - wl[0] + from.width / 2f
            val y0 = fl[1] - wl[1] + from.height / 2f
            val x1 = al[0] - wl[0] + art.width / 2f
            val y1 = al[1] - wl[1] + art.height * 0.3f
            art.setHover(false)
            art.setEating(true)
            fly.launch(x0, y0, x1 - x0, y1 - y0, color) {}
            ui.postDelayed({
                art.setEating(false)
                art.gulp()
            }, 700L)
            ui.postDelayed({
                swallowing = false
                onDone()
            }, 1180L)
        }, delayMs)
    }

    private fun applyBlank(blank: Boolean) {
        emptyWrap.visibility = if (blank) View.VISIBLE else View.GONE
        profCardV.visibility = if (blank) View.GONE else View.VISIBLE
        clearBtnV.visibility = if (blank) View.GONE else View.VISIBLE
    }

    /** Chưa có kịch bản → thẻ thư mục trống; có rồi → thẻ Kịch bản như cũ (có chuyển cảnh). */
    private fun updateEmptyState(animate: Boolean) {
        if (!::emptyWrap.isInitialized || !::profCardV.isInitialized || !::clearBtnV.isInitialized) return
        emptyUndo.visibility = if (ClickStore.hasUndo) View.VISIBLE else View.GONE
        if (swallowing) return
        val blank = ClickStore.isBlank()
        if (lastBlank == blank) return
        val first = lastBlank == null
        lastBlank = blank

        val all = listOf<View>(emptyWrap, profCardV, clearBtnV)
        all.forEach {
            it.animate().cancel()
            it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f; it.translationY = 0f
        }
        if (first || !animate || !animOn) {
            applyBlank(blank)
            return
        }
        if (blank) {
            // Danh sách thu lại, thư mục rơi xuống rồi nằm yên (xoá hết)
            listOf<View>(profCardV, clearBtnV).forEach { v ->
                v.animate().alpha(0f).setDuration(200L).withEndAction {
                    if (lastBlank == true) { v.visibility = View.GONE; v.alpha = 1f }
                }.start()
            }
            ui.postDelayed({
                if (lastBlank != true || isFinishing || isDestroyed) return@postDelayed
                applyBlank(true)
                emptyWrap.alpha = 0f
                emptyWrap.animate().alpha(1f).setDuration(300L).start()
                emptyArt?.drop()
            }, 160L)
        } else {
            // Thư mục mờ đi, rồi mục đầu tiên hiện ra thành danh sách
            emptyWrap.animate().alpha(0f).scaleX(.94f).scaleY(.94f).setDuration(220L).withEndAction {
                if (lastBlank != false) return@withEndAction
                applyBlank(false)
                emptyWrap.alpha = 1f; emptyWrap.scaleX = 1f; emptyWrap.scaleY = 1f
                profCardV.alpha = 0f
                profCardV.translationY = -dp(14).toFloat()
                profCardV.scaleX = .97f; profCardV.scaleY = .97f
                profCardV.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(550L).setInterpolator(springI).start()
                clearBtnV.alpha = 0f
                clearBtnV.animate().alpha(1f).setDuration(300L).setStartDelay(200L).start()
            }.start()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Nạp lại các ô nhập theo kịch bản đang chọn. */
    private fun loadFields() {
        loopsInput.setText(ClickStore.loops.toString())
        stopMinInput.setText(ClickStore.stopMinutes.toString())
        stopTapsInput.setText(ClickStore.stopTaps.toString())
        delayInput.setText(ClickStore.defaultDelay.toString())
        jDelayInput.setText(ClickStore.jitterDelayPct.toString())
        jPxInput.setText(ClickStore.jitterPx.toString())
        startDelayInput.setText(ClickStore.startDelaySec.toString())
        startAtInput.setText(ClickStore.startAt)
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
        input.setTextColor(cText)
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input)
        AlertDialog.Builder(this, AppTheme.alertTheme)
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
        AlertDialog.Builder(this, AppTheme.alertTheme)
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
            ClickStore.userMade = true
            afterProfileChange()
        }
    }

    private fun deleteProfile() {
        val only = ClickStore.profiles.size <= 1
        val msg = if (only) {
            "Đây là kịch bản duy nhất, xoá xong app sẽ về trạng thái chưa có kịch bản (có thể Hoàn tác)."
        } else {
            "Xoá kịch bản \"" + ClickStore.current().name + "\"?"
        }
        AlertDialog.Builder(this, AppTheme.alertTheme)
            .setTitle("Xoá kịch bản")
            .setMessage(msg)
            .setPositiveButton("Xoá") { _, _ ->
                ClickStore.deleteActive()
                afterProfileChange()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ---------- Xuất / nhập file kịch bản ----------

    private fun exportProfiles() {
        saveSettings()
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/json"
        i.putExtra(Intent.EXTRA_TITLE, "nvpklick-kichban.json")
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun importProfiles() {
        saveSettings()
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, REQ_IMPORT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            if (requestCode == REQ_EXPORT) {
                val bytes = ClickStore.exportJson(this).toByteArray(Charsets.UTF_8)
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                Toast.makeText(this, "Đã xuất file kịch bản", Toast.LENGTH_SHORT).show()
            } else if (requestCode == REQ_IMPORT) {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val wasBlank = lastBlank == true
                val n = ClickStore.importJson(this, text)
                val applyImport = {
                    afterProfileChange()
                    Toast.makeText(this, "Đã nhập $n kịch bản", Toast.LENGTH_SHORT).show()
                }
                val btn = emptyImportBtn
                if (wasBlank && n > 0 && btn != null) {
                    swallow(btn, Color.rgb(255, 138, 61), 350L) { applyImport() }
                } else {
                    applyImport()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi đọc/ghi file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Xử lý ----------

    private fun setPanelMode(m: Int) {
        if (ClickStore.panelMode == m) return
        ClickStore.panelMode = m
        ClickStore.save(this)
        FloatingService.instance?.applyMode()
        refreshStatus()
    }

    private fun cardTitle(t: String): TextView {
        val v = textView(t, 16f, cText, true)
        v.setPadding(0, 0, 0, dp(6))
        return v
    }

    /** Một dòng menu: biểu tượng xám bên trái, chữ bên phải, chạm để mở. */
    private fun menuRow(kind: Int, label: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))
        val icon = MenuGlyph(this, kind)
        icon.layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        row.addView(icon)
        val tv = textView(label, 16f, cText, false)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(16), 0, 0, 0)
        tv.layoutParams = lp
        row.addView(tv)
        row.setOnClickListener { onClick() }
        return row
    }

    /** Nút lớn màu hồng BẬT / TẮT của từng chế độ. */
    private fun modeButton(mode: Int): TextView {
        val b = TextView(this)
        b.text = "BẬT"
        b.setTextColor(Color.WHITE)
        b.textSize = 16f
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.gravity = Gravity.CENTER
        b.background = roundBg(cPink, 8, 0, 0)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        lp.setMargins(0, dp(8), 0, dp(4))
        b.layoutParams = lp
        b.setOnClickListener { onModeButton(mode) }
        return b
    }

    /** Chuyển sang tab khác (thanh tab dưới cũng trượt theo). */
    private fun goTab(i: Int) {
        navBar.selectTab(i)
    }

    private fun showGuide() {
        AlertDialog.Builder(this, AppTheme.alertTheme)
            .setTitle("Hướng dẫn")
            .setMessage(guideText())
            .setPositiveButton("Đóng", null)
            .show()
    }

    private fun guideText(): String =
        "1. Cấp 2 quyền ở Trang chủ (hiển thị trên ứng dụng khác và Trợ năng).\n" +
            "2. Nhấn nút BẬT ở Trang chủ (Chế độ 1 mục tiêu hoặc Chế độ nhiều mục tiêu) để mở bảng điều khiển nổi; nhấn TẮT để đóng.\n" +
            "3. Trên bảng nổi: ＋ thêm nút chạm, ➜ thêm nút kéo, － xoá nút cuối, ⚙ mở cài đặt, ˄ thu nhỏ thành bong bóng (chạm bong bóng để mở lại), ✕ đóng.\n" +
            "4. Kéo từng nút số tới vị trí cần chạm. Chạm vào nút (không kéo) để chọn kiểu Chạm / Chạm đúp / Giữ / Kéo, chỉnh độ trễ, đổi thứ tự hoặc xoá.\n" +
            "5. Kiểu Kéo: chấm số N là điểm bắt đầu, chấm P là điểm dừng; có dây nối và mũi tên chỉ hướng từ N tới P.\n" +
            "6. Nhấn nút ▶ trên bảng nổi để chạy, nhấn lại để dừng. Ô số dưới nút ▶ hiện thời gian đã chạy (giờ:phút:giây) và số lần thao tác (×N).\n" +
            "7. Mỗi kịch bản có danh sách nút riêng và điều kiện tự dừng riêng (số vòng, số phút, số lần thao tác).\n" +
            "8. Thông báo của app có nút Dừng, Mở bảng và Tắt để điều khiển nhanh khi bảng đang thu nhỏ.\n" +
            "9. Nút ⏸ trên bảng nổi để tạm dừng / chạy tiếp. Có thể đặt đếm ngược hoặc hẹn giờ ở phần Cài đặt; màn hình được giữ sáng trong lúc chờ và chạy.\n" +
            "10. Nhóm lặp: mở nút cuối của nhóm, điền nhóm bắt đầu từ nút số mấy và số lần lặp.\n" +
            "11. Có thể thêm ô \"Nvp Klick\" vào Cài đặt nhanh để bật bảng và chạy/dừng."

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
        ClickStore.startDelaySec = intOf(startDelayInput, 3600)
        ClickStore.startAt = startAtInput.text.toString().trim()
        ClickStore.save(this)
    }

    private fun onModeButton(m: Int) {
        saveSettings()
        val svc = FloatingService.instance
        if (svc != null) {
            if (ClickStore.panelMode == m) {
                svc.requestQuit()
                Handler(Looper.getMainLooper()).postDelayed({ refreshStatus() }, 300L)
            } else {
                setPanelMode(m)
            }
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
        ClickStore.panelMode = m
        ClickStore.save(this)
        startForegroundService(Intent(this, FloatingService::class.java))
        moveTaskToBack(true)
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accOk = AutoClickService.instance != null
        overlayDot.background = ovalBg(if (overlayOk) cGreen else cRed)
        accDot.background = ovalBg(if (accOk) cGreen else cRed)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batOk = pm.isIgnoringBatteryOptimizations(packageName)
        batDot.background = ovalBg(if (batOk) cGreen else cRed)
        batBtn.text = if (batOk) "Đã tắt" else "Mở"
        overlayBtn.text = if (overlayOk) "Đã cấp" else "Cấp quyền"
        accBtn.text = if (accOk) "Đã bật" else "Bật"

        val on = FloatingService.instance != null
        val m = ClickStore.panelMode
        val on1 = on && m == 1
        val on2 = on && m == 2
        modeBtn1.text = if (on1) "TẮT" else "BẬT"
        modeBtn1.background = roundBg(if (on1) cOff else cPink, 8, 0, 0)
        modeBtn2.text = if (on2) "TẮT" else "BẬT"
        modeBtn2.background = roundBg(if (on2) cOff else cPink, 8, 0, 0)
    }
}

/** Biểu tượng cho các dòng menu ở Trang chủ (bánh răng, chữ i, cờ lê), vẽ bằng code trên lưới 24x24. */
class MenuGlyph(context: Context, private val kind: Int) : View(context) {

    companion object {
        const val GEAR = 0
        const val INFO = 1
        const val WRENCH = 2
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val tint = if (AppTheme.dark) Color.rgb(160, 166, 180) else Color.rgb(158, 158, 158)
        val cut = AppTheme.card
        canvas.save()
        canvas.scale(width / 24f, height / 24f)
        paint.style = Paint.Style.FILL
        paint.color = tint
        when (kind) {
            GEAR -> {
                path.reset()
                path.fillType = Path.FillType.EVEN_ODD
                var first = true
                for (i in 0 until 8) {
                    val base = Math.toRadians((i * 45).toDouble())
                    val angles = doubleArrayOf(
                        base - Math.toRadians(13.0), base - Math.toRadians(9.0),
                        base + Math.toRadians(9.0), base + Math.toRadians(13.0)
                    )
                    val radii = floatArrayOf(7.6f, 10f, 10f, 7.6f)
                    for (k in 0 until 4) {
                        val px = 12f + radii[k] * cos(angles[k]).toFloat()
                        val py = 12f + radii[k] * sin(angles[k]).toFloat()
                        if (first) {
                            path.moveTo(px, py)
                            first = false
                        } else {
                            path.lineTo(px, py)
                        }
                    }
                }
                path.close()
                path.addCircle(12f, 12f, 3.4f, Path.Direction.CW)
                canvas.drawPath(path, paint)
            }
            INFO -> {
                canvas.drawCircle(12f, 12f, 10f, paint)
                paint.color = cut
                canvas.drawCircle(12f, 7.6f, 1.5f, paint)
                canvas.drawRoundRect(10.8f, 10.4f, 13.2f, 17.6f, 1f, 1f, paint)
            }
            else -> {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 3.6f
                canvas.drawLine(5.4f, 18.6f, 13.8f, 10.2f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(16.4f, 7.6f, 4.6f, paint)
                paint.color = cut
                path.reset()
                path.moveTo(17.4f, 6.6f)
                path.lineTo(19.4f, 0.8f)
                path.lineTo(23.2f, 4.6f)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        canvas.restore()
    }
}
