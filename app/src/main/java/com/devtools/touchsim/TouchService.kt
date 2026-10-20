package com.devtools.touchsim

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/** Settings of one floating button. type: 0 = tap, 1 = continuous gyroscope-style drag. */
data class Cfg(
    var type: Int = 0,
    var size: Int = 64,      // dp
    var color: Int = Color.RED,
    var alpha: Int = 70,     // percent
    var cps: Int = 5,        // taps per second
    var dist: Int = 200,     // drag distance in dp
    var speed: Int = 300,    // drag speed in dp/s
    var dir: Int = 0,        // 0 up, 1 down, 2 left, 3 right
    var boost: Int = 100     // gyroscope speed boost, 0..500 %
)

class SimButton(var cfg: Cfg) {
    lateinit var view: TextView
    lateinit var lp: WindowManager.LayoutParams
    var running = false
    var tapTask: Runnable? = null
    var stroke: StrokeDescription? = null
    var curX = 0f
    var curY = 0f
}

class TouchService : AccessibilityService() {

    companion object {
        var instance: TouchService? = null
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val buttons = mutableListOf<SimButton>()
    private var plus: TextView? = null
    private var plusLp: WindowManager.LayoutParams? = null

    // ------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showControls()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopAll()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideControls()
        instance = null
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayParams(sizePx: Int) = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Makes a floating view draggable; short tap = onClick, long press = onLong. */
    private fun attach(v: View, lp: WindowManager.LayoutParams, onClick: () -> Unit, onLong: () -> Unit) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longTask = Runnable {
            if (!moved) {
                longFired = true
                onLong()
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    longFired = false
                    h.postDelayed(longTask, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - downX
                    val my = e.rawY - downY
                    if (!moved && (abs(mx) > slop || abs(my) > slop)) {
                        moved = true
                        h.removeCallbacks(longTask)
                    }
                    if (moved) {
                        lp.x = (startX + mx).toInt()
                        lp.y = (startY + my).toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    h.removeCallbacks(longTask)
                    if (!moved && !longFired) onClick()
                }
                MotionEvent.ACTION_CANCEL -> h.removeCallbacks(longTask)
            }
            true
        }
    }

    // ------------------------------------------------------------ main "+" bubble

    fun showControls() {
        if (plus != null) return
        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            text = "+"
            textSize = 26f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF333333.toInt())
                setStroke(dp(2), Color.WHITE)
            }
            alpha = 0.85f
        }
        val lp = overlayParams(dp(52)).apply {
            x = dp(16)
            y = dp(100)
        }
        attach(tv, lp,
            onClick = {
                if (buttons.any { it.running }) stopAll() else showDialog(null)
            },
            onLong = {})
        wm.addView(tv, lp)
        plus = tv
        plusLp = lp
    }

    fun hideControls() {
        stopAll()
        buttons.toList().forEach { wm.removeView(it.view) }
        buttons.clear()
        plus?.let { wm.removeView(it) }
        plus = null
        plusLp = null
    }

    private fun updatePlus() {
        plus?.text = if (buttons.any { it.running }) "■" else "+"
    }

    // ------------------------------------------------------------ simulated buttons

    private fun style(b: SimButton) {
        val c = b.cfg
        b.view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c.color)
            setStroke(dp(2), Color.WHITE)
        }
        b.view.alpha = c.alpha / 100f
        b.view.text = if (c.type == 0) "◉" else "⇅"
        val lum = Color.red(c.color) * 0.299 + Color.green(c.color) * 0.587 + Color.blue(c.color) * 0.114
        b.view.setTextColor(if (lum > 140) Color.BLACK else Color.WHITE)
        b.view.textSize = c.size / 3f
        b.lp.width = dp(c.size)
        b.lp.height = dp(c.size)
        if (b.view.isAttachedToWindow) wm.updateViewLayout(b.view, b.lp)
    }

    private fun addButton(c: Cfg) {
        val b = SimButton(c)
        b.view = TextView(this).apply { gravity = Gravity.CENTER }
        b.lp = overlayParams(dp(c.size)).apply {
            x = dp(90)
            y = dp(160) + dp(40) * buttons.size
        }
        style(b)
        attach(b.view, b.lp, onClick = { toggle(b) }, onLong = { showDialog(b) })
        wm.addView(b.view, b.lp)
        buttons.add(b)
    }

    private fun removeButton(b: SimButton) {
        stop(b)
        wm.removeView(b.view)
        buttons.remove(b)
        updatePlus()
    }

    private fun toggle(b: SimButton) {
        if (b.running) stop(b) else start(b)
    }

    private fun start(b: SimButton) {
        if (b.running) return
        b.running = true
        // While running, the button lets touches pass through to the app underneath.
        b.lp.flags = b.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(b.view, b.lp)
        updatePlus()

        val loc = IntArray(2)
        b.view.getLocationOnScreen(loc)
        val cx = loc[0] + b.view.width / 2f
        val cy = loc[1] + b.view.height / 2f

        if (b.cfg.type == 0) startTap(b, cx, cy) else startDrag(b, cx, cy)
    }

    private fun stop(b: SimButton) {
        if (!b.running) return
        b.running = false
        b.tapTask?.let { h.removeCallbacks(it) }
        b.tapTask = null

        // Release the held finger for the drag mode.
        val prev = b.stroke
        if (prev != null) {
            val p = Path().apply { moveTo(b.curX, b.curY) }
            try {
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(prev.continueStroke(p, 0, 10, false))
                        .build(),
                    null, null
                )
            } catch (_: Exception) {
            }
            b.stroke = null
        }

        b.lp.flags = b.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (b.view.isAttachedToWindow) wm.updateViewLayout(b.view, b.lp)
        updatePlus()
    }

    private fun stopAll() {
        buttons.toList().forEach { stop(it) }
    }

    // ------------------------------------------------------------ tap mode

    private fun startTap(b: SimButton, x: Float, y: Float) {
        val delay = (1000L / b.cfg.cps.coerceAtLeast(1)).coerceAtLeast(35L)
        val task = object : Runnable {
            override fun run() {
                if (!b.running) return
                tap(x, y)
                h.postDelayed(this, delay)
            }
        }
        b.tapTask = task
        h.post(task)
    }

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(StrokeDescription(p, 0, 30)).build(),
            null, null
        )
    }

    // ------------------------------------------------------------ continuous drag ("gyroscope") mode

    private fun startDrag(b: SimButton, cx: Float, cy: Float) {
        val dist = b.cfg.dist * resources.displayMetrics.density
        val (ux, uy) = when (b.cfg.dir) {
            0 -> 0f to -1f
            1 -> 0f to 1f
            2 -> -1f to 0f
            else -> 1f to 0f
        }
        b.stroke = null
        b.curX = cx
        b.curY = cy
        dragStep(b, cx, cy, cx + ux * dist, cy + uy * dist, true)
    }

    /**
     * One back-and-forth segment of a single, never-lifted finger.
     * Each segment continues the previous stroke so the touch stays pressed.
     */
    private fun dragStep(b: SimButton, sx: Float, sy: Float, ex: Float, ey: Float, forward: Boolean) {
        if (!b.running) return
        val fromX = if (forward) sx else ex
        val fromY = if (forward) sy else ey
        val toX = if (forward) ex else sx
        val toY = if (forward) ey else sy

        val boost = b.cfg.boost
        val stationary = boost <= 0
        val path = Path().apply { moveTo(fromX, fromY) }
        val duration: Long
        if (stationary) {
            // 0 % = pure hold, no movement.
            duration = 300L
        } else {
            val speedPx = b.cfg.speed * resources.displayMetrics.density * boost / 100f
            val distPx = hypot(ex - sx, ey - sy)
            duration = (distPx / speedPx * 1000f).toLong().coerceIn(10L, 60000L)
            path.lineTo(toX, toY)
        }

        val prev = b.stroke
        val s = if (prev == null) StrokeDescription(path, 0, duration, true)
        else prev.continueStroke(path, 0, duration, true)
        b.stroke = s

        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    b.curX = if (stationary) fromX else toX
                    b.curY = if (stationary) fromY else toY
                    dragStep(b, sx, sy, ex, ey, if (stationary) forward else !forward)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    h.post { if (b.running) stop(b) }
                }
            },
            null
        )
        if (!ok) stop(b)
    }

    // ------------------------------------------------------------ config dialog

    private fun seekRow(
        ctx: Context, parent: LinearLayout, label: String,
        min: Int, max: Int, init: Int, unit: String
    ): () -> Int {
        val tv = TextView(ctx)
        val sb = SeekBar(ctx)
        sb.max = max - min
        sb.progress = init.coerceIn(min, max) - min
        fun upd() {
            tv.text = "$label: ${sb.progress + min}$unit"
        }
        upd()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = upd()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(tv)
        parent.addView(sb)
        return { sb.progress + min }
    }

    private fun showDialog(existing: SimButton?) {
        val c = existing?.cfg?.copy() ?: Cfg()
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val pad = dp(16)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Type
        val typeGroup = RadioGroup(ctx)
        val rbTap = RadioButton(ctx).apply {
            text = "نقر (ضغط متكرر)"
            id = View.generateViewId()
        }
        val rbDrag = RadioButton(ctx).apply {
            text = "محاكاة سحب الجيروسكوب"
            id = View.generateViewId()
        }
        typeGroup.addView(rbTap)
        typeGroup.addView(rbDrag)
        typeGroup.check(if (c.type == 0) rbTap.id else rbDrag.id)
        col.addView(typeGroup)

        // Common: size, transparency, color
        val getSize = seekRow(ctx, col, "حجم الزر", 30, 150, c.size, " dp")
        val getAlpha = seekRow(ctx, col, "الوضوح (الشفافية)", 20, 100, c.alpha, "%")

        col.addView(TextView(ctx).apply { text = "اللون" })
        val palette = intArrayOf(
            Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
            Color.CYAN, Color.MAGENTA, Color.WHITE, 0xFFFF8800.toInt()
        )
        val swatches = mutableListOf<View>()
        fun refreshSwatches() {
            swatches.forEachIndexed { i, v ->
                val selected = palette[i] == c.color
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette[i])
                    setStroke(if (selected) dp(4) else dp(1), if (selected) Color.DKGRAY else Color.LTGRAY)
                }
            }
        }
        val colorRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        palette.forEachIndexed { i, colr ->
            val sw = View(ctx)
            sw.layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(3), dp(6), dp(3), dp(6)) }
            sw.setOnClickListener {
                c.color = colr
                refreshSwatches()
            }
            swatches.add(sw)
            colorRow.addView(sw)
        }
        refreshSwatches()
        col.addView(colorRow)

        // Tap options
        val tapGroup = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val getCps = seekRow(ctx, tapGroup, "سرعة النقر", 1, 30, c.cps, " نقرة/ثانية")
        col.addView(tapGroup)

        // Drag options
        val dragGroup = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val getDist = seekRow(ctx, dragGroup, "مسافة السحب", 50, 1000, c.dist, " dp")
        val getSpeed = seekRow(ctx, dragGroup, "سرعة السحب", 20, 2000, c.speed, " dp/ث")
        dragGroup.addView(TextView(ctx).apply { text = "اتجاه السحب" })
        val dirGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val dirNames = arrayOf("↑", "↓", "←", "→")
        val dirIds = dirNames.map { name ->
            val rb = RadioButton(ctx).apply {
                text = name
                id = View.generateViewId()
            }
            dirGroup.addView(rb)
            rb.id
        }
        dirGroup.check(dirIds[c.dir.coerceIn(0, 3)])
        dragGroup.addView(dirGroup)
        val getBoost = seekRow(ctx, dragGroup, "زيادة سرعة الجيروسكوب", 0, 500, c.boost, "%")
        col.addView(dragGroup)

        fun updateVisibility() {
            val isTap = typeGroup.checkedRadioButtonId == rbTap.id
            tapGroup.visibility = if (isTap) View.VISIBLE else View.GONE
            dragGroup.visibility = if (isTap) View.GONE else View.VISIBLE
        }
        typeGroup.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        updateVisibility()

        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "إضافة زر" else "تعديل الزر")
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("حفظ") { _, _ ->
                c.type = if (typeGroup.checkedRadioButtonId == rbTap.id) 0 else 1
                c.size = getSize()
                c.alpha = getAlpha()
                c.cps = getCps()
                c.dist = getDist()
                c.speed = getSpeed()
                c.dir = dirIds.indexOf(dirGroup.checkedRadioButtonId).coerceAtLeast(0)
                c.boost = getBoost()
                if (existing == null) {
                    addButton(c)
                } else {
                    stop(existing)
                    existing.cfg = c
                    style(existing)
                }
            }
            .setNegativeButton("إلغاء", null)
        if (existing != null) {
            builder.setNeutralButton("حذف") { _, _ -> removeButton(existing) }
        }
        val dialog = builder.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
    }
}
