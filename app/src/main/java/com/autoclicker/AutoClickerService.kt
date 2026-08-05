package com.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.autoclicker.databinding.OverlayControlBinding

/**
 * 自动点击核心服务。
 * v6 修复：
 *  - 新增最小化按钮（"—"），点击后收起到最近边缘变成半透明小图标
 *  - 新增周期性轮询（每2秒主动扫描一次），解决部分弹窗不触发无障碍事件的问题
 *  - ACTION_CLICK 失败时自动 fallback 到坐标点击（获取节点 bounds 中心点）
 *  - 命中关键字时 Toast 反馈
 *  - 扩大事件监听范围
 */
class AutoClickerService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayBinding: OverlayControlBinding
    private var markerView: android.view.View? = null
    private var markerParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private val lastClick = mutableMapOf<String, Long>()

    private var markerX = 0
    private var markerY = 0
    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    private var overlayParams: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var collapseSide = 0
    private var panelDownX = 0f
    private var panelDownY = 0f
    private var panelMoved = false
    private var panelDragOffsetX = 0
    private var panelDragOffsetY = 0
    private var naturalW = 0
    private var naturalH = 0

    /** 周期性轮询：每2秒主动扫描一次屏幕文字 */
    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                if (!ScenarioStore.isStopped(this@AutoClickerService) &&
                    !ScenarioStore.isPaused(this@AutoClickerService)
                ) {
                    scanAndClick()
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, 2000L)
        }
    }

    companion object {
        private const val COOLDOWN_MS = 1500L
        private const val EDGE_THRESHOLD_DP = 40
        private const val PEEK_DP = 40
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
        startTimedLoop()
        handler.postDelayed(pollRunnable, 2000L)
        toast("自动点击器已启动 v6")
    }

    private fun setupOverlay() {
        val inflater = LayoutInflater.from(this)
        overlayBinding = OverlayControlBinding.inflate(inflater)

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        overlayParams!!.gravity = Gravity.TOP or Gravity.START
        overlayParams!!.x = 0
        overlayParams!!.y = 120
        windowManager.addView(overlayBinding.root, overlayParams)

        markerView = inflater.inflate(R.layout.overlay_marker, null)
        markerParams = WindowManager.LayoutParams(
            48.dpToPx(), 48.dpToPx(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        markerParams!!.gravity = Gravity.TOP or Gravity.START
        markerParams!!.x = resources.displayMetrics.widthPixels / 2 - 24.dpToPx()
        markerParams!!.y = resources.displayMetrics.heightPixels / 2 - 24.dpToPx()
        markerX = markerParams!!.x + 24.dpToPx()
        markerY = markerParams!!.y + 24.dpToPx()
        windowManager.addView(markerView, markerParams)

        markerView!!.setOnTouchListener { v, event ->
            val p = v.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    dragOffsetX = event.rawX.toInt() - p.x
                    dragOffsetY = event.rawY.toInt() - p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        p.x = event.rawX.toInt() - dragOffsetX
                        p.y = event.rawY.toInt() - dragOffsetY
                        markerX = p.x + v.width / 2
                        markerY = p.y + v.height / 2
                        windowManager.updateViewLayout(v, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { dragging = false; true }
                else -> false
            }
        }

        // ── 按钮 ──

        overlayBinding.btnSaveCoord.setOnClickListener {
            ScenarioStore.savePicked(this@AutoClickerService, markerX, markerY)
            toast("已保存坐标: ($markerX, $markerY)")
        }

        overlayBinding.btnToggleTimed.setOnClickListener {
            val enabled = !ScenarioStore.isTimedEnabled(this@AutoClickerService)
            ScenarioStore.setTimedEnabled(this@AutoClickerService, enabled)
            updateTimedButton()
            toast(if (enabled) "定时点击已开启" else "定时点击已关闭")
        }

        overlayBinding.btnPause.setOnClickListener {
            val paused = !ScenarioStore.isPaused(this@AutoClickerService)
            ScenarioStore.setPaused(this@AutoClickerService, paused)
            overlayBinding.btnPause.text = if (paused) "继续" else "暂停"
            updateStatus()
        }

        overlayBinding.btnStop.setOnClickListener {
            val stopped = !ScenarioStore.isStopped(this@AutoClickerService)
            ScenarioStore.setStopped(this@AutoClickerService, stopped)
            overlayBinding.btnStop.text = if (stopped) "▶ 开始" else "停止"
            updateStatus()
        }

        overlayBinding.btnExit.setOnClickListener {
            try {
                runCatching { windowManager.removeView(overlayBinding.root) }
                runCatching { if (markerView != null) windowManager.removeView(markerView) }
                handler.removeCallbacksAndMessages(null)
            } catch (_: Exception) {}
            disableSelf()
            toast("已完全关闭")
        }

        overlayBinding.btnSettings.setOnClickListener {
            val intent = android.content.Intent(this@AutoClickerService, MainActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        /** — 最小化按钮（v6新增） */
        overlayBinding.btnMinimize.setOnClickListener {
            minimizeToEdge()
        }

        setupPanelDrag()
        setupResize()

        updateTimedButton()
        updateStopButton()
        updateStatus()
    }

    // ── UI 更新 ──

    private fun updateTimedButton() {
        val enabled = ScenarioStore.isTimedEnabled(this)
        val sec = ScenarioStore.getInterval(this) / 1000
        overlayBinding.btnToggleTimed.text =
            if (enabled) "定时:开 (${sec}s)" else "定时:关"
    }

    private fun updateStatus(extra: String = "") {
        val base = when {
            ScenarioStore.isStopped(this) -> "已停止"
            ScenarioStore.isPaused(this) -> "已暂停"
            else -> "运行中"
        }
        overlayBinding.tvStatus.text = "自动点击器：$base$extra"
    }

    private fun updateStopButton() {
        val stopped = ScenarioStore.isStopped(this)
        overlayBinding.btnStop.text = if (stopped) "▶ 开始" else "停止"
    }

    // ── 最小化功能（v6新增）──

    private fun minimizeToEdge() {
        val p = overlayParams ?: return
        val screenW = resources.displayMetrics.widthPixels
        val pw = panelW()
        val centerX = p.x + pw / 2
        if (centerX < screenW / 2) {
            collapsed = true; collapseSide = -1
            p.x = -(pw - PEEK_DP.dpToPx())
        } else {
            collapsed = true; collapseSide = 1
            p.x = screenW - PEEK_DP.dpToPx()
        }
        windowManager.updateViewLayout(overlayBinding.root, p)
        toast("已最小化，点击图标展开")
    }

    // ── 面板拖动 + 靠边隐藏 ──

    private fun setupPanelDrag() {
        val handle = overlayBinding.dragHandle
        handle.setOnTouchListener { _, e ->
            val p = overlayParams ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (collapsed) { expandPanel(); true }
                    else {
                        panelDownX = e.rawX; panelDownY = e.rawY
                        panelMoved = false
                        panelDragOffsetX = e.rawX.toInt() - p.x
                        panelDragOffsetY = e.rawY.toInt() - p.y
                        true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (collapsed) return@setOnTouchListener true
                    val dx = e.rawX - panelDownX
                    val dy = e.rawY - panelDownY
                    if (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6) panelMoved = true
                    if (panelMoved) {
                        val screenW = resources.displayMetrics.widthPixels
                        val screenH = resources.displayMetrics.heightPixels
                        val pw = panelW(); val ph = panelH()
                        p.x = (e.rawX.toInt() - panelDragOffsetX).coerceIn(0, (screenW - pw).coerceAtLeast(0))
                        p.y = (e.rawY.toInt() - panelDragOffsetY).coerceIn(0, (screenH - ph).coerceAtLeast(0))
                        windowManager.updateViewLayout(overlayBinding.root, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!collapsed && panelMoved) snapToEdge()
                    panelMoved = false
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val p = overlayParams ?: return
        val screenW = resources.displayMetrics.widthPixels
        val pw = panelW()
        val threshold = EDGE_THRESHOLD_DP.dpToPx()
        when {
            p.x <= threshold -> { collapsed = true; collapseSide = -1; p.x = -(pw - PEEK_DP.dpToPx()) }
            p.x + pw >= screenW - threshold -> { collapsed = true; collapseSide = 1; p.x = screenW - PEEK_DP.dpToPx() }
        }
        windowManager.updateViewLayout(overlayBinding.root, p)
    }

    private fun expandPanel() {
        val p = overlayParams ?: return
        val screenW = resources.displayMetrics.widthPixels
        val pw = panelW()
        p.x = if (collapseSide == -1) 0 else (screenW - pw).coerceAtLeast(0)
        collapsed = false; collapseSide = 0
        windowManager.updateViewLayout(overlayBinding.root, p)
    }

    private fun panelW() = if ((overlayParams?.width ?: 0) > 0) overlayParams!!.width else overlayBinding.root.width
    private fun panelH() = if ((overlayParams?.height ?: 0) > 0) overlayParams!!.height else overlayBinding.root.height

    // ── 面板缩放 ──

    private fun setupResize() {
        val handle = overlayBinding.resizeHandle
        var startX = 0f; var startY = 0f; var baseW = 0; var baseH = 0
        handle.setOnTouchListener { _, e ->
            val p = overlayParams ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (naturalW <= 0) { naturalW = overlayBinding.root.width.coerceAtLeast(200); naturalH = overlayBinding.root.height.coerceAtLeast(200) }
                    baseW = if (p.width > 0) p.width else overlayBinding.root.width
                    baseH = if (p.height > 0) p.height else overlayBinding.root.height
                    startX = e.rawX; startY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = ((e.rawX - startX) + (e.rawY - startY)) / 2f
                    val minW = (naturalW * 0.6f).toInt().coerceAtLeast(120); val maxW = (naturalW * 2.5f).toInt()
                    val minH = (naturalH * 0.6f).toInt().coerceAtLeast(120); val maxH = (naturalH * 2.5f).toInt()
                    p.width = (baseW + moved).toInt().coerceIn(minW, maxW)
                    p.height = (baseH + moved).toInt().coerceIn(minH, maxH)
                    windowManager.updateViewLayout(overlayBinding.root, p); true
                }
                else -> false
            }
        }
    }

    // ───────────────────────────────────────────────
    //  核心文字匹配与点击（v6 大幅增强）
    // ───────────────────────────────────────────────

    /** 统一扫描方法（供事件驱动 + 周期轮询调用） */
    private fun scanAndClick() {
        if (ScenarioStore.isStopped(this)) return
        if (ScenarioStore.isPaused(this)) return
        val root = rootInActiveWindow ?: return
        try {
            val scenarios = ScenarioStore.load(this).filter { it.enabled && it.matchText.isNotBlank() }
            for (s in scenarios) {
                val node = findNodeByText(root, s.matchText)
                if (node != null) {
                    val now = System.currentTimeMillis()
                    val last = lastClick[s.id] ?: 0
                    if (now - last >= COOLDOWN_MS) {
                        lastClick[s.id] = now
                        val clicked = performClick(node, s)
                        if (clicked) {
                            updateStatus(" · 命中「${s.name}」✓")
                            toast("命中「${s.name}」并点击成功！")
                        }
                    }
                    node.recycle()
                }
            }
        } finally { root.recycle() }
    }

    /**
     * 执行点击。v6：ACTION_CLICK 失败时 fallback 到坐标点击。
     */
    private fun performClick(node: AccessibilityNodeInfo, s: Scenario): Boolean {
        return when (s.action) {
            "coord" -> {
                if (s.clickX > 0 && s.clickY > 0) { tap(s.clickX, s.clickY); true }
                else { tapNodeOrFallback(node) }
            }
            "node" -> tapNodeOrFallback(node)
            else -> tapNodeOrFallback(node)
        }
    }

    /**
     * ACTION_CLICK → 父节点 → bounds中心 dispatchGesture（三重fallback）
     */
    private fun tapNodeOrFallback(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val parent = node.parent
        if (parent != null) {
            val pc = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            parent.recycle()
            if (pc) return true
        }
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.centerX() > 0 && bounds.centerY() > 0) {
            tap(bounds.centerX(), bounds.centerY())
            return true
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (ScenarioStore.isStopped(this)) return
        if (ScenarioStore.isPaused(this)) return
        if (event == null) return
        val et = event.eventType
        // v6：扩大事件监听范围
        if (et != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            et != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            et != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            et != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            et != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            et != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            et != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            et != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        scanAndClick()
    }

    /** 递归搜索含指定文字的节点（text / contentDescription / hintText） */
    private fun findNodeByText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val needle = text.lowercase()
        if (node.text?.toString()?.lowercase()?.contains(needle) == true) return node
        if (node.contentDescription?.toString()?.lowercase()?.contains(needle) == true) return node
        val hint = node.hintText?.toString()
        if (hint != null && hint.lowercase().contains(needle)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text)
            if (found != null) { if (found !== child) child.recycle(); return found }
            child.recycle()
        }
        return null
    }

    // ── 定时循环 ──

    private fun startTimedLoop() {
        val runnable = object : Runnable {
            override fun run() {
                if (!ScenarioStore.isStopped(this@AutoClickerService) &&
                    !ScenarioStore.isPaused(this@AutoClickerService) &&
                    ScenarioStore.isTimedEnabled(this@AutoClickerService)
                ) {
                    val (x, y) = ScenarioStore.getTimedCoord(this@AutoClickerService)
                    if (x > 0 && y > 0) tap(x, y)
                }
                handler.postDelayed(this, ScenarioStore.getInterval(this@AutoClickerService).toLong())
            }
        }
        handler.postDelayed(runnable, ScenarioStore.getInterval(this).toLong())
    }

    private fun tap(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (x <= 0 || y <= 0) return
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ── 工具方法 ──

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onInterrupt() { toast("自动点击器被系统中断") }

    override fun onDestroy() {
        super.onDestroy()
        try { runCatching { windowManager.removeView(overlayBinding.root) }
             runCatching { if (markerView != null) windowManager.removeView(markerView) }
        } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }
}
