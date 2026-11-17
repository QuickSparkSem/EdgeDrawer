package com.edgedrawer.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.exp

object EdgeIndexSlots {
    val COLORS: List<AppColorFilter> = AppColorFilter.entries.toList()
    val LETTERS: List<Char> = ('A'..'Z').toList() + listOf('★')
    val TOTAL_SLOTS: Int = COLORS.size + LETTERS.size
    val RECENT_SLOT_INDEX: Int = TOTAL_SLOTS - 1

    fun isColorSlot(slot: Int): Boolean = slot in COLORS.indices
    fun getColor(slot: Int): AppColorFilter = COLORS[slot.coerceIn(0, COLORS.lastIndex)]
    fun getLetter(slot: Int): Char = LETTERS[(slot - COLORS.size).coerceIn(0, LETTERS.lastIndex)]
}

object EdgeBarMetrics {
    data class Layout(
        val edgeMarginPx: Float,
        val indexWidthPx: Float,
        val barTopY: Float,
        val barBottomY: Float,
        val colorsTopY: Float,
        val lettersTopY: Float,
        val lettersHeightPx: Float,
        val colorSlotHeightPx: Float,
        val letterSlotHeightPx: Float
    )

    fun compute(screenW: Float, screenH: Float, density: Float, prefs: PrefsManager): Layout {
        val idxScale = prefs.indexScalePercent / 100f
        val edgeMargin = prefs.triggerEdgeOffsetDp * density
        val indexWidth = (prefs.triggerWidthDp * density).coerceAtLeast(20f * density) * idxScale
        val pad = 6f * density

        val baseLettersH = screenH * (prefs.barHeightPercent / 100f)
        val extraLetterSpace = prefs.letterSpacingExtraDp * density * EdgeIndexSlots.LETTERS.size
        val rawLetterSlotH = ((baseLettersH + extraLetterSpace - pad * 2f) / EdgeIndexSlots.LETTERS.size).coerceAtLeast(9f * density) * idxScale
        val rawColorSlotH = (rawLetterSlotH * 1.12f + prefs.colorDotSpacingExtraDp * density).coerceAtLeast(11f * density)

        val rawTotalH = pad * 2f + rawColorSlotH * EdgeIndexSlots.COLORS.size + rawLetterSlotH * EdgeIndexSlots.LETTERS.size
        val fit = ((screenH - 32f * density) / rawTotalH).coerceAtMost(1f)
        val colorSlotH = rawColorSlotH * fit
        val letterSlotH = rawLetterSlotH * fit

        val colorsH = colorSlotH * EdgeIndexSlots.COLORS.size
        val lettersH = letterSlotH * EdgeIndexSlots.LETTERS.size
        val totalBarH = pad * 2f + colorsH + lettersH

        val topSafe = 24f * density
        val span = (screenH - topSafe - 14f * density - totalBarH).coerceAtLeast(0f)
        val barTop = topSafe + span * (prefs.verticalPositionPercent / 100f)

        return Layout(edgeMargin, indexWidth, barTop, barTop + totalBarH, barTop + pad, barTop + pad + colorsH, lettersH + pad, colorSlotH, letterSlotH)
    }
}

class EdgeTriggerView(context: Context, private val prefs: PrefsManager) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun getSlotAtY(localY: Float): Int {
        val usableH = (height - dp(6f)).coerceAtLeast(1f)
        val idx = ((localY.coerceIn(0f, usableH - 0.1f) / usableH) * EdgeIndexSlots.LETTERS.size).toInt()
        return EdgeIndexSlots.COLORS.size + idx.coerceIn(0, EdgeIndexSlots.LETTERS.lastIndex)
    }

    override fun onDraw(canvas: Canvas) {
        if (prefs.isTriggerHidden || prefs.triggerAlphaPercent <= 0 || width <= 0 || height <= 0) return
        val usableH = (height - dp(6f)).coerceAtLeast(1f)
        val stepH = usableH / EdgeIndexSlots.LETTERS.size
        val alpha = ((prefs.triggerAlphaPercent / 100f) * 255).toInt().coerceIn(0, 255)
        val col = prefs.triggerCustomColor

        paint.color = Color.argb(alpha, Color.red(col), Color.green(col), Color.blue(col))
        val shadow = prefs.triggerShadowDepthPercent / 100f
        if (shadow > 0.01f) {
            val sCol = if (Color.luminance(col) < 0.25f) Color.argb((230 * shadow).toInt(), 255, 255, 255) else Color.argb((245 * shadow).toInt(), 0, 0, 0)
            val dx = (if (prefs.isRightSide) -1f else 1f) * dp(0.8f + 2.6f * shadow)
            paint.setShadowLayer(dp(1.5f + 5.5f * shadow), dx, dp(0.8f + 2.2f * shadow), sCol)
        } else {
            paint.clearShadowLayer()
        }

        val idxScale = prefs.indexScalePercent / 100f
        paint.textSize = (stepH * 0.76f).coerceIn(dp(7f), dp(12.5f) * idxScale)
        val anchorX = if (prefs.isRightSide) width - dp(2.2f) else dp(2.2f)
        val skewX = if (prefs.isRightSide) 0.14f else -0.14f
        val textOffset = (paint.descent() + paint.ascent()) / 2f

        for (i in EdgeIndexSlots.LETTERS.indices) {
            val ch = EdgeIndexSlots.LETTERS[i]
            canvas.save()
            canvas.translate(anchorX, stepH * (i + 0.5f))
            canvas.skew(skewX, 0f)
            canvas.drawText(if (ch == '★') "↓" else ch.toString(), 0f, -textOffset, paint)
            canvas.restore()
        }
    }
}

class EdgeDrawerView(
    context: Context,
    private val repository: AppRepository,
    private val prefs: PrefsManager,
    private val onLaunchApp: (AppEntry, LaunchWindowMode) -> Unit,
    private val onToggleSide: () -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {

    companion object {
        private val WINDOW_OPTIONS = listOf(
            LaunchWindowMode.SPLIT_TOP,
            LaunchWindowMode.POPUP,
            LaunchWindowMode.SPLIT_BOTTOM,
            LaunchWindowMode.APP_INFO
        )
    }

    private sealed class ListItem {
        data class Header(val letter: Char) : ListItem()
        data class App(val entry: AppEntry) : ListItem()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scroller = OverScroller(context, DecelerateInterpolator(1.6f))
    private var velocityTracker: VelocityTracker? = null

    private var selectedLetter = '★'
    private var activeColorFilter = AppColorFilter.ALL
    private var lastTouchedSlot = EdgeIndexSlots.RECENT_SLOT_INDEX
    private var isContinuousAZMode = false
    private var displayedItems: List<ListItem> = emptyList()
    private val letterStartIdx = HashMap<Char, Int>()

    private var hoveredAppIndex = -1
    private var lastAnimatedAppIndex = -1
    private var currentHoverScale = 1.0f
    private var scrollOffsetY = 0f
    private var fingerY = -1f
    private var isTouchingIndex = false
    private var openProgress = 0f

    private var isMenuVisible = false
    private var menuAppIndex = -1
    private var hoveredMenuIndex = -1
    private var menuProgress = 0f
    private var holdX = 0f
    private var holdY = 0f

    private var autoScrollVelY = 0f
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var isAutoScrolling = false
    private var isContinuousDrag = false
    private var lastTouchY = 0f
    private var isScrollingList = false

    private val indexBarRect = RectF()
    private val slotRects = Array(EdgeIndexSlots.TOTAL_SLOTS) { RectF() }
    private val menuRects = Array(WINDOW_OPTIONS.size) { RectF() }
    private val menuCorridorRect = RectF()
    private val sideSwitchRect = RectF()
    private val appRects = ArrayList<Pair<Int, RectF>>()
    private val tempRect = RectF()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indexPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(dp(16f), 0f, dp(6f), Color.argb(235, 0, 0, 0)) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f) }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val accentPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(dp(6f), 0f, dp(2f), Color.argb(180, 0, 0, 0)) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.4f) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val menuBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(dp(14f), 0f, dp(5f), Color.argb(215, 0, 0, 0)) }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var isNight = true
    private var accentColor = Color.parseColor("#3E91FF")

    private val holdRunnable = Runnable {
        if (hoveredAppIndex in displayedItems.indices && displayedItems[hoveredAppIndex] is ListItem.App && !isScrollingList && abs(autoScrollVelY) < 0.5f) {
            isMenuVisible = true
            menuAppIndex = hoveredAppIndex
            hoveredMenuIndex = -1
            HapticHelper.trigger(context, prefs, HapticHelper.Pulse.HOLD)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 190L
                interpolator = OvershootInterpolator(1.1f)
                addUpdateListener { menuProgress = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!isAutoScrolling || abs(autoScrollVelY) < 0.2f) { isAutoScrolling = false; return }
            val maxS = computeMaxScroll()
            val prev = scrollOffsetY
            scrollOffsetY = if (prefs.isAppsBottomToTop) (scrollOffsetY + autoScrollVelY).coerceIn(0f, maxS) else (scrollOffsetY + autoScrollVelY).coerceIn(-maxS, 0f)
            if (scrollOffsetY != prev) {
                rebuildAppRects()
                val newHover = findAppAt(lastPointerX, lastPointerY)
                if (newHover != hoveredAppIndex) {
                    hoveredAppIndex = newHover
                    if (newHover != -1) {
                        lastAnimatedAppIndex = newHover
                        HapticHelper.trigger(context, prefs, HapticHelper.Pulse.HOVER)
                    }
                }
                invalidate()
            }
            handler.postDelayed(this, 16L)
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        applyTheme()
    }

    private fun applyTheme() {
        isNight = prefs.isNightThemeActive()
        accentColor = prefs.getAccentColor()
        val barAlpha = ((prefs.indexBarAlphaPercent / 100f) * 242).toInt().coerceIn(0, 255)

        indexPillPaint.color = if (isNight) Color.argb(barAlpha, 18, 22, 32) else Color.argb(barAlpha, 255, 255, 255)
        borderPaint.color = if (isNight) Color.argb((barAlpha * 0.25f).toInt(), 255, 255, 255) else Color.argb((barAlpha * 0.22f).toInt(), 15, 20, 30)
        rimPaint.color = if (isNight) Color.argb((barAlpha * 0.45f).toInt(), 255, 255, 255) else Color.argb(220, 255, 255, 255)
        labelPaint.color = if (isNight) Color.WHITE else Color.parseColor("#12151C")
        labelPaint.setShadowLayer(dp(2.5f), 0f, dp(1f), if (isNight) Color.argb(230, 0, 0, 0) else Color.argb(210, 255, 255, 255))
        menuBgPaint.color = if (isNight) Color.argb(250, 24, 29, 42) else Color.argb(252, 255, 255, 255)
        accentPillPaint.color = accentColor
        ringPaint.color = accentColor
    }

    fun prepareOpen(fromSwipe: Boolean, initialSlot: Int) {
        applyTheme()
        isContinuousDrag = fromSwipe
        isContinuousAZMode = false
        scroller.abortAnimation()
        cancelHold()
        stopAutoScroll()
        hoveredAppIndex = -1
        currentHoverScale = 1.0f
        scrollOffsetY = 0f

        val safeSlot = initialSlot.coerceIn(EdgeIndexSlots.COLORS.size, EdgeIndexSlots.TOTAL_SLOTS - 1)
        lastTouchedSlot = safeSlot
        selectedLetter = EdgeIndexSlots.getLetter(safeSlot)
        refreshItems()

        if (width > 0 && height > 0) {
            computeGeometry(width.toFloat(), height.toFloat())
            fingerY = slotRects[safeSlot].centerY()
            isTouchingIndex = fromSwipe
            rebuildAppRects()
        }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150L
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { openProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun refreshItems() {
        scrollOffsetY = 0f
        scroller.abortAnimation()
        letterStartIdx.clear()

        if (isContinuousAZMode) {
            val list = ArrayList<ListItem>()
            for (ch in 'A'..'Z') {
                val apps = repository.getAppsForLetter(ch, activeColorFilter)
                if (apps.isNotEmpty()) {
                    letterStartIdx[ch] = list.size
                    list.add(ListItem.Header(ch))
                    apps.forEach { list.add(ListItem.App(it)) }
                }
            }
            displayedItems = list
        } else {
            val apps = if (selectedLetter == '★') repository.getRecentApps(prefs.recentCount, activeColorFilter) else repository.getAppsForLetter(selectedLetter, activeColorFilter)
            displayedItems = apps.map { ListItem.App(it) }
        }
        rebuildAppRects()
    }

    private fun enterContinuousAZMode(jumpTo: Char) {
        if (!isContinuousAZMode) {
            isContinuousAZMode = true
            refreshItems()
        }
        val idx = letterStartIdx[jumpTo] ?: return
        val maxS = computeMaxScroll()
        val slotH = getSlotHeightPx()
        scrollOffsetY = if (prefs.isAppsBottomToTop) (idx * slotH).coerceIn(0f, maxS) else -(idx * slotH).coerceIn(0f, maxS)
        rebuildAppRects()
    }

    private fun getSlotHeightPx(): Float {
        val scale = prefs.panelScalePercent / 100f
        val labelH = if (prefs.showAppLabels) sp(prefs.appLabelSizeSp.toFloat()) * 1.25f + dp(prefs.labelDistanceDp.toFloat()) else 0f
        return dp(46f) * scale + labelH + dp(prefs.appSpacingDp.toFloat())
    }

    private fun computeMaxScroll(): Float {
        val visibleH = (indexBarRect.bottom - indexBarRect.top).coerceAtLeast(dp(120f))
        return (displayedItems.size * getSlotHeightPx() - visibleH).coerceAtLeast(0f)
    }

    private fun rebuildAppRects() {
        appRects.clear()
        if (width <= 0 || height <= 0) return
        val scale = prefs.panelScalePercent / 100f
        val gap = dp(prefs.appsFromIndexDp.toFloat())
        val colW = dp(78f) * scale
        val colLeft = if (prefs.isRightSide) indexBarRect.left - gap - colW else indexBarRect.right + gap
        val slotH = getSlotHeightPx()

        for (i in displayedItems.indices) {
            if (displayedItems[i] !is ListItem.App) continue
            val top = if (prefs.isAppsBottomToTop) indexBarRect.bottom - (i + 1) * slotH + scrollOffsetY else indexBarRect.top + i * slotH + scrollOffsetY
            val bottom = top + slotH
            if (bottom >= indexBarRect.top && top <= indexBarRect.bottom) {
                appRects.add(i to RectF(colLeft, top, colLeft + colW, bottom))
            }
        }
    }

    private fun updateAutoScroll(x: Float, y: Float) {
        lastPointerX = x
        lastPointerY = y
        val maxS = computeMaxScroll()
        if (maxS <= 0f || isInIndexZone(x) || isMenuVisible || height <= 0) { stopAutoScroll(); return }

        val zone = dp(prefs.autoScrollEdgeZoneDp.toFloat())
        val speed = (prefs.autoScrollAppsPerSec * getSlotHeightPx()) / 60f
        val vel = when {
            y <= zone -> speed * (1f - y / zone).coerceIn(0.3f, 1.5f) * (if (prefs.isAppsBottomToTop) 1f else -1f)
            y >= height - zone -> speed * (1f - (height - y) / zone).coerceIn(0.3f, 1.5f) * (if (prefs.isAppsBottomToTop) -1f else 1f)
            else -> 0f
        }
        autoScrollVelY = vel
        if (abs(vel) >= 0.2f && !isAutoScrolling) {
            isAutoScrolling = true
            handler.post(autoScrollRunnable)
        } else if (abs(vel) < 0.2f) {
            stopAutoScroll()
        }
    }

    private fun stopAutoScroll() {
        autoScrollVelY = 0f
        isAutoScrolling = false
        handler.removeCallbacks(autoScrollRunnable)
    }

    private fun scheduleHold(appIdx: Int, x: Float, y: Float) {
        if (isMenuVisible && menuAppIndex == appIdx) return
        handler.removeCallbacks(holdRunnable)
        holdX = x; holdY = y
        handler.postDelayed(holdRunnable, 320L)
    }

    private fun cancelHold() {
        handler.removeCallbacks(holdRunnable)
        isMenuVisible = false
        menuAppIndex = -1
        hoveredMenuIndex = -1
        menuProgress = 0f
    }

    private fun selectSlotByY(y: Float) {
        val slot = (0 until EdgeIndexSlots.TOTAL_SLOTS).firstOrNull { y <= slotRects[it].bottom } ?: (EdgeIndexSlots.TOTAL_SLOTS - 1)
        if (slot == lastTouchedSlot) return
        lastTouchedSlot = slot
        hoveredAppIndex = -1
        cancelHold()

        if (EdgeIndexSlots.isColorSlot(slot)) {
            val col = EdgeIndexSlots.getColor(slot)
            if (col != activeColorFilter) { activeColorFilter = col; refreshItems() }
        } else {
            val ch = EdgeIndexSlots.getLetter(slot)
            if (ch != selectedLetter) {
                selectedLetter = ch
                if (isContinuousAZMode && ch in 'A'..'Z') enterContinuousAZMode(ch)
                else { isContinuousAZMode = false; refreshItems() }
            }
        }
        HapticHelper.trigger(context, prefs, HapticHelper.Pulse.TICK)
    }

    fun handleExternalDrag(rawX: Float, rawY: Float, action: Int) {
        if (indexBarRect.width() <= 0f && width > 0 && height > 0) {
            computeGeometry(width.toFloat(), height.toFloat())
            rebuildAppRects()
        }
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val x = rawX - loc[0]
        val y = rawY - loc[1]

        when (action) {
            MotionEvent.ACTION_MOVE -> {
                isContinuousDrag = true
                processPointerMove(x, y)
                updateAutoScroll(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isTouchingIndex = false
                stopAutoScroll()
                if (isContinuousDrag && tryLaunchAt(x, y)) return
                cancelHold()
                isContinuousDrag = false
                hoveredAppIndex = -1
                if (selectedLetter != '★') enterContinuousAZMode(selectedLetter)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                isTouchingIndex = false; stopAutoScroll(); cancelHold(); isContinuousDrag = false; hoveredAppIndex = -1; invalidate()
            }
        }
    }

    private fun processPointerMove(x: Float, y: Float) {
        lastPointerX = x; lastPointerY = y
        if (isMenuVisible) {
            val opt = findMenuOptionAt(x, y)
            if (opt != hoveredMenuIndex) {
                hoveredMenuIndex = opt
                if (opt != -1) HapticHelper.trigger(context, prefs, HapticHelper.Pulse.HOVER)
            }
            if (opt != -1 || menuCorridorRect.contains(x, y)) return
            cancelHold()
        }

        if (isInIndexZone(x)) {
            isTouchingIndex = true; fingerY = y; cancelHold(); stopAutoScroll(); hoveredAppIndex = -1
            selectSlotByY(y)
            return
        }

        isTouchingIndex = false
        val appIdx = findAppAt(x, y)
        if (appIdx != hoveredAppIndex) {
            hoveredAppIndex = appIdx
            if (appIdx != -1) {
                lastAnimatedAppIndex = appIdx
                currentHoverScale = 1.02f
                HapticHelper.trigger(context, prefs, HapticHelper.Pulse.HOVER)
                scheduleHold(appIdx, x, y)
            } else cancelHold()
        } else if (appIdx != -1 && (abs(x - holdX) > dp(16f) || abs(y - holdY) > dp(16f))) {
            scheduleHold(appIdx, x, y)
        }
    }

    private fun tryLaunchAt(x: Float, y: Float): Boolean {
        if (isMenuVisible && menuAppIndex in displayedItems.indices) {
            val target = (displayedItems[menuAppIndex] as? ListItem.App)?.entry
            if (target != null) {
                val opt = findMenuOptionAt(x, y)
                if (opt in WINDOW_OPTIONS.indices) {
                    cancelHold()
                    HapticHelper.trigger(context, prefs, HapticHelper.Pulse.CONFIRM)
                    onLaunchApp(target, WINDOW_OPTIONS[opt])
                    return true
                }
                if (appRects.any { it.first == menuAppIndex && it.second.contains(x, y) }) {
                    cancelHold()
                    HapticHelper.trigger(context, prefs, HapticHelper.Pulse.CONFIRM)
                    onLaunchApp(target, LaunchWindowMode.NORMAL)
                    return true
                }
            }
            cancelHold()
            return false
        }

        val target = (displayedItems.getOrNull(findAppAt(x, y)) as? ListItem.App)?.entry ?: return false
        cancelHold()
        HapticHelper.trigger(context, prefs, HapticHelper.Pulse.CONFIRM)
        onLaunchApp(target, LaunchWindowMode.NORMAL)
        return true
    }

    private fun isInIndexZone(x: Float) = if (prefs.isRightSide) x >= indexBarRect.left - dp(10f) else x <= indexBarRect.right + dp(10f)
    private fun findAppAt(x: Float, y: Float): Int = appRects.firstOrNull { it.second.contains(x, y) }?.first ?: -1
    private fun findMenuOptionAt(x: Float, y: Float): Int = menuRects.indexOfFirst {
        x in (it.left - dp(6f))..(it.right + dp(6f)) && y in (it.top - dp(6f))..(it.bottom + dp(6f))
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffsetY = scroller.currY.toFloat()
            rebuildAppRects()
            val first = appRects.firstOrNull()?.first?.let { displayedItems.getOrNull(it) }
            val ch = (first as? ListItem.Header)?.letter ?: (first as? ListItem.App)?.entry?.firstLetter
            if (ch != null && ch in 'A'..'Z') selectedLetter = ch
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                scroller.abortAnimation(); lastTouchY = y; isScrollingList = false
                if (sideSwitchRect.contains(x, y)) {
                    cancelHold(); HapticHelper.trigger(context, prefs, HapticHelper.Pulse.CONFIRM); onToggleSide(); return true
                }
                if (isInIndexZone(x) && y in (indexBarRect.top - dp(12f))..(indexBarRect.bottom + dp(12f))) {
                    isTouchingIndex = true; fingerY = y; cancelHold(); selectSlotByY(y); invalidate(); return true
                }
                val appIdx = findAppAt(x, y)
                if (appIdx != -1) {
                    hoveredAppIndex = appIdx; lastAnimatedAppIndex = appIdx; currentHoverScale = 1.02f; scheduleHold(appIdx, x, y); invalidate(); return true
                }
                cancelHold(); onDismiss(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isMenuVisible || isInIndexZone(x)) {
                    isScrollingList = false; processPointerMove(x, y); invalidate(); return true
                }
                val dy = y - lastTouchY
                if (abs(dy) > dp(7f)) {
                    if (!isContinuousAZMode && selectedLetter != '★') enterContinuousAZMode(selectedLetter)
                    val maxS = computeMaxScroll()
                    if (maxS > 0f) {
                        isScrollingList = true; isTouchingIndex = false; cancelHold(); hoveredAppIndex = -1
                        scrollOffsetY = if (prefs.isAppsBottomToTop) (scrollOffsetY + dy).coerceIn(0f, maxS) else (scrollOffsetY + dy).coerceIn(-maxS, 0f)
                        rebuildAppRects(); lastTouchY = y; invalidate(); return true
                    }
                }
                if (!isScrollingList) { processPointerMove(x, y); invalidate() }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouchingIndex = false; stopAutoScroll()
                if (isScrollingList) {
                    velocityTracker?.computeCurrentVelocity(1000, 14000f)
                    val vy = velocityTracker?.yVelocity ?: 0f
                    val maxS = computeMaxScroll()
                    if (abs(vy) > 250f && maxS > 0f) {
                        val minY = if (prefs.isAppsBottomToTop) 0 else -maxS.toInt()
                        val maxY = if (prefs.isAppsBottomToTop) maxS.toInt() else 0
                        scroller.fling(0, scrollOffsetY.toInt(), 0, (vy * 1.45f).toInt(), 0, 0, minY, maxY)
                        postInvalidateOnAnimation()
                    }
                } else if (event.action == MotionEvent.ACTION_UP && tryLaunchAt(x, y)) {
                    velocityTracker?.recycle(); velocityTracker = null; return true
                }
                velocityTracker?.recycle(); velocityTracker = null; cancelHold(); hoveredAppIndex = -1; isScrollingList = false; invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            computeGeometry(w.toFloat(), h.toFloat())
            if (fingerY < 0f) fingerY = slotRects[lastTouchedSlot].centerY()
            rebuildAppRects()
        }
    }

    private fun computeGeometry(w: Float, h: Float) {
        val m = EdgeBarMetrics.compute(w, h, density, prefs)
        val left = if (prefs.isRightSide) w - m.edgeMarginPx - m.indexWidthPx else m.edgeMarginPx
        val right = left + m.indexWidthPx
        indexBarRect.set(left, m.barTopY, right, m.barBottomY)

        for (i in EdgeIndexSlots.COLORS.indices) {
            val top = m.colorsTopY + i * m.colorSlotHeightPx
            slotRects[i].set(left, top, right, top + m.colorSlotHeightPx)
        }
        for (j in EdgeIndexSlots.LETTERS.indices) {
            val top = m.lettersTopY + j * m.letterSlotHeightPx
            slotRects[EdgeIndexSlots.COLORS.size + j].set(left, top, right, top + m.letterSlotHeightPx)
        }

        val btnSize = dp(38f)
        val btnLeft = if (prefs.isRightSide) dp(18f) else w - dp(18f) - btnSize
        sideSwitchRect.set(btnLeft, h - dp(26f) - btnSize, btnLeft + btnSize, h - dp(26f))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val appScale = prefs.panelScalePercent / 100f
        val idxScale = prefs.indexScalePercent / 100f
        val dirSign = if (prefs.isRightSide) -1f else 1f
        val wave = prefs.waveStrengthPercent / 100f

        // Sfondo sfumato dal lato opposto al lato app
        val baseRgb = if (isNight) Color.rgb(10, 12, 18) else Color.rgb(240, 243, 249)
        val aNear = ((prefs.panelBgAlphaPercent / 100f) * 246f * openProgress).toInt().coerceIn(0, 255)
        val aFar = ((prefs.panelBgOppositeAlphaPercent / 100f) * 246f * openProgress).toInt().coerceIn(0, 255)
        val cNear = Color.argb(aNear, Color.red(baseRgb), Color.green(baseRgb), Color.blue(baseRgb))
        val cFar = Color.argb(aFar, Color.red(baseRgb), Color.green(baseRgb), Color.blue(baseRgb))
        bgPaint.shader = if (prefs.isRightSide) LinearGradient(0f, 0f, w, 0f, cFar, cNear, Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, w, 0f, cNear, cFar, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        canvas.save()
        canvas.translate((1f - openProgress) * dp(24f) * (-dirSign), 0f)

        // Barra indice 3D
        val r = indexBarRect.width() / 2f
        canvas.drawRoundRect(indexBarRect, r, r, indexPillPaint)
        canvas.drawRoundRect(indexBarRect, r, r, borderPaint)
        canvas.save()
        canvas.clipRect(indexBarRect.left, indexBarRect.top, indexBarRect.right, indexBarRect.top + r)
        canvas.drawRoundRect(indexBarRect, r, r, rimPaint)
        canvas.restore()

        val firstLetterIdx = EdgeIndexSlots.COLORS.size
        val baseTextSize = (slotRects[firstLetterIdx].height() * 0.72f).coerceIn(dp(7f), dp(12.5f) * idxScale)
        val waveR = dp(58f) * idxScale

        for (i in 0 until EdgeIndexSlots.TOTAL_SLOTS) {
            val rect = slotRects[i]
            val cy = rect.centerY()
            val dist = if (isTouchingIndex && fingerY >= 0f) abs(cy - fingerY) else 9999f
            val wf = if (dist < waveR * 2.2f && wave > 0f) exp(-(dist * dist) / (2f * waveR * waveR)) else 0f
            val cx = rect.centerX() + dirSign * dp(20f) * idxScale * wave * wf

            if (EdgeIndexSlots.isColorSlot(i)) {
                val filter = EdgeIndexSlots.getColor(i)
                val active = (filter == activeColorFilter) || (isTouchingIndex && i == lastTouchedSlot)
                val dotR = (slotRects[0].height() * 0.30f).coerceIn(dp(4.2f), dp(7.5f) * idxScale) * (if (active) 1.28f else 0.92f) * (1f + 0.25f * wf)
                if (active) {
                    dotPaint.color = filter.swatchColor
                } else {
                    val hsv = FloatArray(3).also { Color.colorToHSV(filter.swatchColor, it) }
                    hsv[1] *= 0.42f; hsv[2] *= 0.68f
                    dotPaint.color = Color.HSVToColor(150, hsv)
                }
                canvas.drawCircle(cx, cy, dotR, dotPaint)
                if (filter == activeColorFilter) {
                    ringPaint.color = if (isNight) Color.WHITE else Color.parseColor("#12151C")
                    canvas.drawCircle(cx, cy, dotR + dp(2f), ringPaint)
                    ringPaint.color = accentColor
                }
            } else {
                val ch = EdgeIndexSlots.getLetter(i)
                val selected = ch == selectedLetter
                if (selected) {
                    canvas.drawCircle(cx, cy, dp(11.5f) * idxScale * (1f + 0.15f * wf), accentPillPaint)
                    textPaint.color = Color.WHITE
                } else {
                    val hasApps = (ch == '★') || repository.hasAppsForLetter(ch, activeColorFilter)
                    val base = if (isNight) Color.WHITE else Color.parseColor("#12151C")
                    textPaint.color = Color.argb(if (hasApps) 245 else 75, Color.red(base), Color.green(base), Color.blue(base))
                }
                textPaint.textSize = baseTextSize * (1f + 0.35f * wf)
                val offset = (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(if (ch == '★') "↓" else ch.toString(), cx, cy - offset, textPaint)
            }
        }

        // Colonna App (senza intestazione testo in fondo/alto)
        val colW = dp(78f) * appScale
        val colLeft = if (prefs.isRightSide) indexBarRect.left - dp(prefs.appsFromIndexDp.toFloat()) - colW else indexBarRect.right + dp(prefs.appsFromIndexDp.toFloat())
        val colCenterX = colLeft + colW / 2f

        val targetScale = if (hoveredAppIndex != -1 || isMenuVisible) 1.24f else 1.0f
        if (abs(currentHoverScale - targetScale) > 0.005f) {
            currentHoverScale += (targetScale - currentHoverScale) * 0.38f
            postInvalidateOnAnimation()
        } else currentHoverScale = targetScale

        canvas.save()
        canvas.clipRect(colLeft - dp(24f), indexBarRect.top, colLeft + colW + dp(24f), indexBarRect.bottom)

        val baseIcon = dp(46f) * appScale
        val slotH = getSlotHeightPx()
        val fontPx = sp(prefs.appLabelSizeSp.toFloat())
        labelPaint.textSize = fontPx

        for (i in displayedItems.indices) {
            val top = if (prefs.isAppsBottomToTop) indexBarRect.bottom - (i + 1) * slotH + scrollOffsetY else indexBarRect.top + i * slotH + scrollOffsetY
            val bottom = top + slotH
            if (bottom < indexBarRect.top || top > indexBarRect.bottom) continue

            when (val item = displayedItems[i]) {
                is ListItem.Header -> {
                    textPaint.color = accentColor; textPaint.textSize = dp(14f) * appScale
                    canvas.drawText("— ${item.letter} —", colCenterX, (top + bottom) / 2f, textPaint)
                }
                is ListItem.App -> {
                    val app = item.entry
                    val isHold = isMenuVisible && menuAppIndex == i
                    val isZoom = isHold || i == hoveredAppIndex || (hoveredAppIndex == -1 && !isMenuVisible && i == lastAnimatedAppIndex && currentHoverScale > 1.01f)
                    val scale = if (isZoom) currentHoverScale else 1.0f
                    val iconSize = baseIcon * scale
                    val cx = colCenterX + if (isZoom) dirSign * dp(8f) * wave * ((scale - 1f) / 0.24f) else 0f
                    val iconTop = top + dp(2f) - (iconSize - baseIcon) / 2f
                    tempRect.set(cx - iconSize / 2f, iconTop, cx + iconSize / 2f, iconTop + iconSize)

                    if (isHold) canvas.drawCircle(tempRect.centerX(), tempRect.centerY(), iconSize * 0.62f, ringPaint)
                    bmpPaint.alpha = if (app.isArchived) 175 else 255
                    canvas.drawBitmap(app.iconBitmap, null, tempRect, bmpPaint)
                    bmpPaint.alpha = 255

                    if (prefs.showAppLabels) {
                        val baseLine = top + dp(2f) + baseIcon + dp(prefs.labelDistanceDp.toFloat()) + fontPx
                        canvas.drawText(if (app.isArchived) "☁ ${app.shortLabel}" else app.shortLabel, cx, baseLine, labelPaint)
                    }
                }
            }
        }
        canvas.restore()

        // Tasto rapido cambio lato Destra <-> Sinistra
        canvas.drawRoundRect(sideSwitchRect, dp(12f), dp(12f), indexPillPaint)
        canvas.drawRoundRect(sideSwitchRect, dp(12f), dp(12f), borderPaint)
        textPaint.color = if (isNight) Color.WHITE else Color.parseColor("#12151C")
        textPaint.textSize = dp(16f)
        canvas.drawText("⇄", sideSwitchRect.centerX(), sideSwitchRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // Menu 4 modalità finestra
        val anchor = appRects.firstOrNull { it.first == menuAppIndex }?.second
        if (isMenuVisible && anchor != null) {
            val p = menuProgress.coerceIn(0f, 1.15f)
            val btnW = dp(152f) * appScale; val btnH = dp(38f) * appScale; val gap = dp(7f) * appScale
            val totH = WINDOW_OPTIONS.size * btnH + (WINDOW_OPTIONS.size - 1) * gap
            val tLeft = if (prefs.isRightSide) anchor.left - btnW - dp(12f) * appScale else anchor.right + dp(12f) * appScale
            val tTop = (anchor.centerY() - totH / 2f).coerceIn(dp(32f), height - totH - dp(24f))
            menuCorridorRect.set(minOf(anchor.left, tLeft) - dp(26f), minOf(anchor.top, tTop) - dp(26f), maxOf(anchor.right, tLeft + btnW) + dp(26f), maxOf(anchor.bottom, tTop + totH) + dp(26f))

            textPaint.textSize = dp(12f) * appScale
            val off = (textPaint.descent() + textPaint.ascent()) / 2f
            for (i in WINDOW_OPTIONS.indices) {
                val cy = anchor.centerY() + ((tTop + i * (btnH + gap) + btnH / 2f) - anchor.centerY()) * p
                val cx = anchor.centerX() + ((tLeft + btnW / 2f) - anchor.centerX()) * p
                val hw = (btnW / 2f) * p.coerceAtLeast(0.1f); val hh = (btnH / 2f) * p.coerceAtLeast(0.1f)
                menuRects[i].set(cx - hw, cy - hh, cx + hw, cy + hh)

                val sel = i == hoveredMenuIndex
                canvas.drawRoundRect(menuRects[i], dp(14f), dp(14f), if (sel) accentPillPaint else menuBgPaint)
                canvas.drawRoundRect(menuRects[i], dp(14f), dp(14f), borderPaint)
                textPaint.color = if (sel || isNight) Color.WHITE else Color.parseColor("#12151C")
                canvas.drawText(WINDOW_OPTIONS[i].title, cx, cy - off, textPaint)
            }
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        velocityTracker?.recycle()
        super.onDetachedFromWindow()
    }
}
