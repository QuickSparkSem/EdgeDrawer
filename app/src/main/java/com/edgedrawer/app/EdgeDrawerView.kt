package com.edgedrawer.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.OverScroller
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val pillRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && w > 0 && h > 0) {
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
    }

    fun getSlotAtY(localY: Float): Int {
        val usableH = (height - dp(6f)).coerceAtLeast(1f)
        val idx = ((localY.coerceIn(0f, usableH - 0.1f) / usableH) * EdgeIndexSlots.LETTERS.size).toInt()
        return EdgeIndexSlots.COLORS.size + idx.coerceIn(0, EdgeIndexSlots.LETTERS.lastIndex)
    }

    override fun onDraw(canvas: Canvas) {
        if (prefs.isTriggerHidden || prefs.triggerAlphaPercent <= 0 || width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val usableH = (h - dp(6f)).coerceAtLeast(1f)
        val stepH = usableH / EdgeIndexSlots.LETTERS.size
        val alpha = ((prefs.triggerAlphaPercent / 100f) * 255).toInt().coerceIn(0, 255)
        val col = prefs.triggerCustomColor
        val style = prefs.getVisualStyle()
        val isNight = prefs.isNightThemeActive()
        val accent = prefs.getAccentColor()

        val insetX = dp(2f)
        pillRect.set(insetX, dp(1f), w - insetX, h - dp(3f))
        val pillR = pillRect.width() / 2f

        when (style) {
            IndexVisualStyle.GLASS -> {
                val base = if (isNight) Color.WHITE else Color.BLACK
                pillPaint.shader = LinearGradient(
                    0f, 0f, w, h,
                    Color.argb((alpha * 0.24f).toInt(), Color.red(base), Color.green(base), Color.blue(base)),
                    Color.argb((alpha * 0.08f).toInt(), Color.red(base), Color.green(base), Color.blue(base)),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(pillRect, pillR, pillR, pillPaint)
                pillPaint.shader = null
                borderPaint.color = Color.argb((alpha * 0.55f).toInt(), 255, 255, 255)
                canvas.drawRoundRect(pillRect, pillR, pillR, borderPaint)
            }
            IndexVisualStyle.MATERIAL -> {
                val surface = if (isNight) Color.rgb(26, 31, 42) else Color.rgb(245, 247, 252)
                val tonal = ColorUtils.blendARGB(surface, accent, 0.24f)
                pillPaint.color = Color.argb((alpha * 0.85f).toInt(), Color.red(tonal), Color.green(tonal), Color.blue(tonal))
                canvas.drawRoundRect(pillRect, pillR, pillR, pillPaint)
                borderPaint.color = Color.argb((alpha * 0.45f).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
                canvas.drawRoundRect(pillRect, pillR, pillR, borderPaint)
            }
            IndexVisualStyle.RELIEF_3D, IndexVisualStyle.MINIMAL -> Unit
        }

        textPaint.color = Color.argb(alpha, Color.red(col), Color.green(col), Color.blue(col))
        val shadow = prefs.triggerShadowDepthPercent / 100f
        if (style == IndexVisualStyle.RELIEF_3D && shadow > 0.01f) {
            val sCol = if (Color.luminance(col) < 0.25f) Color.argb((230 * shadow).toInt(), 255, 255, 255) else Color.argb((245 * shadow).toInt(), 0, 0, 0)
            val dx = (if (prefs.isRightSide) -1f else 1f) * dp(0.8f + 2.6f * shadow)
            textPaint.setShadowLayer(dp(1.5f + 5.5f * shadow), dx, dp(0.8f + 2.2f * shadow), sCol)
        } else {
            textPaint.clearShadowLayer()
        }

        val idxScale = prefs.indexScalePercent / 100f
        textPaint.textSize = (stepH * 0.74f).coerceIn(dp(7f), dp(12f) * idxScale)
        val anchorX = when (style) {
            IndexVisualStyle.GLASS, IndexVisualStyle.MATERIAL -> w / 2f
            else -> if (prefs.isRightSide) w - dp(2.4f) else dp(2.4f)
        }
        val skewX = if (style == IndexVisualStyle.RELIEF_3D) (if (prefs.isRightSide) 0.14f else -0.14f) else 0f
        val textOffset = (textPaint.descent() + textPaint.ascent()) / 2f

        for (i in EdgeIndexSlots.LETTERS.indices) {
            val ch = EdgeIndexSlots.LETTERS[i]
            canvas.save()
            canvas.translate(anchorX, stepH * (i + 0.5f))
            if (skewX != 0f) canvas.skew(skewX, 0f)
            canvas.drawText(if (ch == '★') "↓" else ch.toString(), 0f, -textOffset, textPaint)
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
            LaunchWindowMode.PIN_SCREEN,
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

    // Onda energetica di colore (60 FPS)
    private var waveColor = Color.TRANSPARENT
    private var waveOriginX = 0f
    private var waveOriginY = 0f
    private var waveProgress = 0f
    private var waveAnimator: ValueAnimator? = null

    // Menu opzioni finestra (Swipe dall'app)
    private var isMenuVisible = false
    private var menuAppIndex = -1
    private var hoveredMenuIndex = -1
    private var menuProgress = 0f
    private var menuOpenedDuringCurrentGesture = false
    private var swipeAppIdx = -1
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    // Scorrimento & prevenzione chiusure involontarie
    private var autoScrollVelY = 0f
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var isAutoScrolling = false
    private var isContinuousDrag = false
    private var downX = 0f
    private var downY = 0f
    private var lastTouchY = 0f
    private var isScrollingList = false
    private var downOnEmptySpace = false

    private val indexBarRect = RectF()
    private val appColumnRect = RectF()
    private val slotRects = Array(EdgeIndexSlots.TOTAL_SLOTS) { RectF() }
    private val menuRects = Array(WINDOW_OPTIONS.size) { RectF() }
    private val menuCorridorRect = RectF()
    private val sideSwitchRect = RectF()
    private val appRects = ArrayList<Pair<Int, RectF>>()
    private val tempRect = RectF()
    private val hsvBuffer = FloatArray(3)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val energyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indexPillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indexSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.1f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val accentPillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.4f) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val menuBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sideBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var isNight = true
    private var accentColor = Color.parseColor("#3E91FF")
    private var visualStyle = IndexVisualStyle.RELIEF_3D

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!isAutoScrolling || abs(autoScrollVelY) < 0.2f || isMenuVisible) { isAutoScrolling = false; return }
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
                        swipeAppIdx = newHover
                        swipeStartX = lastPointerX
                        swipeStartY = lastPointerY
                        HapticHelper.trigger(context, prefs, HapticHelper.Pulse.HOVER)
                    }
                }
                invalidate()
            }
            handler.postDelayed(this, 16L)
        }
    }

    init {
        applyTheme()
    }

    private fun applyTheme() {
        isNight = prefs.isNightThemeActive()
        accentColor = prefs.getAccentColor()
        visualStyle = prefs.getVisualStyle()
        val barAlpha = ((prefs.indexBarAlphaPercent / 100f) * 242).toInt().coerceIn(0, 255)

        when (visualStyle) {
            IndexVisualStyle.RELIEF_3D -> {
                indexPillPaint.setShadowLayer(dp(10f), 0f, dp(3f), Color.argb(190, 0, 0, 0))
                indexPillPaint.color = if (isNight) Color.argb(barAlpha, 18, 22, 32) else Color.argb(barAlpha, 255, 255, 255)
                borderPaint.strokeWidth = dp(1.2f)
                borderPaint.color = if (isNight) Color.argb((barAlpha * 0.25f).toInt(), 255, 255, 255) else Color.argb((barAlpha * 0.22f).toInt(), 15, 20, 30)
                rimPaint.color = if (isNight) Color.argb((barAlpha * 0.50f).toInt(), 255, 255, 255) else Color.argb(225, 255, 255, 255)
                accentPillPaint.clearShadowLayer()
            }
            IndexVisualStyle.GLASS -> {
                indexPillPaint.clearShadowLayer()
                val glassAlpha = (barAlpha * 0.44f).toInt().coerceIn(28, 150)
                indexPillPaint.color = if (isNight) Color.argb(glassAlpha, 28, 36, 52) else Color.argb(glassAlpha, 255, 255, 255)
                borderPaint.strokeWidth = dp(1.4f)
                borderPaint.color = Color.argb(if (isNight) 135 else 210, 255, 255, 255)
                rimPaint.color = Color.argb(180, 255, 255, 255)
                accentPillPaint.clearShadowLayer()
            }
            IndexVisualStyle.MATERIAL -> {
                indexPillPaint.clearShadowLayer()
                val surface = if (isNight) Color.rgb(24, 28, 38) else Color.rgb(246, 248, 253)
                val tonal = ColorUtils.blendARGB(surface, accentColor, 0.20f)
                indexPillPaint.color = Color.argb(barAlpha, Color.red(tonal), Color.green(tonal), Color.blue(tonal))
                borderPaint.strokeWidth = dp(1f)
                borderPaint.color = ColorUtils.setAlphaComponent(accentColor, 95)
                rimPaint.color = Color.TRANSPARENT
                accentPillPaint.clearShadowLayer()
            }
            IndexVisualStyle.MINIMAL -> {
                indexPillPaint.clearShadowLayer()
                indexPillPaint.color = Color.TRANSPARENT
                borderPaint.color = Color.TRANSPARENT
                rimPaint.color = Color.TRANSPARENT
                accentPillPaint.clearShadowLayer()
            }
        }

        val menuAlpha = ((prefs.appMenuAlphaPercent / 100f) * 255).toInt().coerceIn(25, 255)
        menuBgPaint.color = if (isNight) Color.argb(menuAlpha, 24, 29, 42) else Color.argb(menuAlpha, 255, 255, 255)

        sideBtnPaint.color = if (isNight) Color.argb(240, 24, 29, 42) else Color.argb(245, 255, 255, 255)
        labelPaint.color = if (isNight) Color.WHITE else Color.parseColor("#12151C")
        accentPillPaint.color = accentColor
        ringPaint.color = accentColor
    }

    private fun openWindowMenu(appIdx: Int) {
        stopAutoScroll()
        isMenuVisible = true
        menuAppIndex = appIdx
        hoveredAppIndex = appIdx
        hoveredMenuIndex = -1
        menuOpenedDuringCurrentGesture = true
        HapticHelper.trigger(context, prefs, HapticHelper.Pulse.HOLD)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160L
            interpolator = OvershootInterpolator(1.08f)
            addUpdateListener { menuProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun closeWindowMenu() {
        swipeAppIdx = -1
        menuOpenedDuringCurrentGesture = false
        isMenuVisible = false
        menuAppIndex = -1
        hoveredMenuIndex = -1
        menuProgress = 0f
    }

    private fun triggerEnergyWave(ox: Float, oy: Float, color: Int) {
        if (prefs.colorWaveStrengthPercent <= 0) return
        waveOriginX = ox
        waveOriginY = oy
        waveColor = color
        waveAnimator?.cancel()
        waveProgress = 0.01f
        val durationMs = (760L + (prefs.colorWaveStrengthPercent * 2.2f).toLong()).coerceIn(600L, 1150L)
        waveAnimator = ValueAnimator.ofFloat(0.01f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.25f)
            addUpdateListener {
                waveProgress = it.animatedValue as Float
                if (waveProgress >= 0.999f) waveProgress = 0f
                invalidate()
            }
            start()
        }
    }

    fun prepareOpen(fromSwipe: Boolean, initialSlot: Int) {
        applyTheme()
        isContinuousDrag = fromSwipe
        isContinuousAZMode = false
        scroller.abortAnimation()
        closeWindowMenu()
        stopAutoScroll()
        hoveredAppIndex = -1
        currentHoverScale = 1.0f
        scrollOffsetY = 0f
        downOnEmptySpace = false

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
            duration = 130L
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
        val slotH = getSlotHeightPx()
        for (i in displayedItems.indices) {
            if (displayedItems[i] !is ListItem.App) continue
            val top = if (prefs.isAppsBottomToTop) indexBarRect.bottom - (i + 1) * slotH + scrollOffsetY else indexBarRect.top + i * slotH + scrollOffsetY
            val bottom = top + slotH
            if (bottom >= indexBarRect.top && top <= indexBarRect.bottom) {
                appRects.add(i to RectF(appColumnRect.left, top, appColumnRect.right, bottom))
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

    private fun selectSlotByY(y: Float) {
        val slot = (0 until EdgeIndexSlots.TOTAL_SLOTS).firstOrNull { y <= slotRects[it].bottom } ?: (EdgeIndexSlots.TOTAL_SLOTS - 1)
        if (slot == lastTouchedSlot) return
        lastTouchedSlot = slot
        hoveredAppIndex = -1
        closeWindowMenu()

        if (EdgeIndexSlots.isColorSlot(slot)) {
            val col = EdgeIndexSlots.getColor(slot)
            activeColorFilter = col
            refreshItems()
            triggerEnergyWave(slotRects[slot].centerX(), y.coerceIn(0f, height.toFloat()), col.swatchColor)
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
                if (isContinuousDrag && handleReleaseAction(x, y)) return
                closeWindowMenu()
                isContinuousDrag = false
                hoveredAppIndex = -1
                if (selectedLetter != '★') enterContinuousAZMode(selectedLetter)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                isTouchingIndex = false; stopAutoScroll(); closeWindowMenu(); isContinuousDrag = false; hoveredAppIndex = -1; invalidate()
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
            closeWindowMenu()
        }

        if (isInIndexZone(x)) {
            isTouchingIndex = true; fingerY = y; closeWindowMenu(); stopAutoScroll(); hoveredAppIndex = -1
            selectSlotByY(y)
            return
        }

        isTouchingIndex = false
        val appIdx = findAppAt(x, y)

        if (swipeAppIdx != -1) {
            val inwardDx = if (prefs.isRightSide) (swipeStartX - x) else (x - swipeStartX)
            val threshold = dp(prefs.appMenuSwipeThresholdDp.toFloat())
            if (inwardDx >= threshold && inwardDx > abs(y - swipeStartY) * 1.15f) {
                openWindowMenu(swipeAppIdx)
                return
            }
        }

        if (appIdx != hoveredAppIndex) {
            hoveredAppIndex = appIdx
            if (appIdx != -1) {
                lastAnimatedAppIndex = appIdx
                currentHoverScale = 1.02f
                swipeAppIdx = appIdx
                swipeStartX = x
                swipeStartY = y
                HapticHelper.trigger(context, prefs, HapticHelper.Pulse.HOVER)
            } else {
                swipeAppIdx = -1
            }
        }
    }

    private fun handleReleaseAction(x: Float, y: Float): Boolean {
        if (isMenuVisible && menuAppIndex in displayedItems.indices) {
            val target = (displayedItems[menuAppIndex] as? ListItem.App)?.entry
            if (target != null) {
                val opt = findMenuOptionAt(x, y)
                if (opt in WINDOW_OPTIONS.indices) {
                    closeWindowMenu()
                    HapticHelper.trigger(context, prefs, HapticHelper.Pulse.CONFIRM)
                    onLaunchApp(target, WINDOW_OPTIONS[opt])
                    return true
                }
                if (menuOpenedDuringCurrentGesture) {
                    menuOpenedDuringCurrentGesture = false
                    isContinuousDrag = false
                    invalidate()
                    return true
                }
            }
            closeWindowMenu()
            invalidate()
            return true
        }

        val target = (displayedItems.getOrNull(findAppAt(x, y)) as? ListItem.App)?.entry ?: return false
        closeWindowMenu()
        HapticHelper.trigger(context, prefs, HapticHelper.Pulse.CONFIRM)
        onLaunchApp(target, LaunchWindowMode.NORMAL)
        return true
    }

    private fun isInIndexZone(x: Float): Boolean {
        val pad = dp(18f)
        return if (prefs.isRightSide) x >= indexBarRect.left - pad else x <= indexBarRect.right + pad
    }

    private fun isInSafeInteractionArea(x: Float, y: Float): Boolean {
        val pad = dp(24f)
        val inIndexArea = isInIndexZone(x) && y in (indexBarRect.top - pad)..(indexBarRect.bottom + pad)
        val inAppColArea = x in (appColumnRect.left - pad)..(appColumnRect.right + pad) &&
            y in (indexBarRect.top - pad)..(indexBarRect.bottom + pad)
        return inIndexArea || inAppColArea || sideSwitchRect.contains(x, y)
    }

    private fun findAppAt(x: Float, y: Float): Int = appRects.firstOrNull {
        x in (it.second.left - dp(8f))..(it.second.right + dp(8f)) && y in it.second.top..it.second.bottom
    }?.first ?: -1

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
                scroller.abortAnimation()
                downX = x; downY = y; lastTouchY = y
                isScrollingList = false
                downOnEmptySpace = false

                if (sideSwitchRect.contains(x, y)) {
                    closeWindowMenu(); HapticHelper.trigger(context, prefs, HapticHelper.Pulse.CONFIRM); onToggleSide(); return true
                }

                if (isMenuVisible) {
                    val opt = findMenuOptionAt(x, y)
                    if (opt != -1 || menuCorridorRect.contains(x, y)) {
                        hoveredMenuIndex = opt
                        invalidate()
                        return true
                    }
                    closeWindowMenu()
                    invalidate()
                    return true
                }

                if (isInIndexZone(x) && y in (indexBarRect.top - dp(28f))..(indexBarRect.bottom + dp(28f))) {
                    isTouchingIndex = true; fingerY = y; closeWindowMenu(); selectSlotByY(y); invalidate(); return true
                }

                val appIdx = findAppAt(x, y)
                if (appIdx != -1) {
                    hoveredAppIndex = appIdx; lastAnimatedAppIndex = appIdx; currentHoverScale = 1.02f
                    swipeAppIdx = appIdx; swipeStartX = x; swipeStartY = y
                    invalidate()
                    return true
                }

                if (!isInSafeInteractionArea(x, y)) {
                    downOnEmptySpace = true
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isMenuVisible || isTouchingIndex || isInIndexZone(x)) {
                    isScrollingList = false
                    processPointerMove(x, y)
                    invalidate()
                    return true
                }

                val dx = x - downX
                val dy = y - lastTouchY
                val totalDy = y - downY

                if (swipeAppIdx != -1 && !isScrollingList) {
                    val inwardDx = if (prefs.isRightSide) (swipeStartX - x) else (x - swipeStartX)
                    val threshold = dp(prefs.appMenuSwipeThresholdDp.toFloat())
                    if (inwardDx >= threshold && inwardDx > abs(y - swipeStartY) * 1.15f) {
                        openWindowMenu(swipeAppIdx)
                        return true
                    }
                }

                if (abs(totalDy) > dp(16f) && abs(totalDy) > abs(dx) * 1.25f) {
                    if (!isContinuousAZMode && selectedLetter != '★') enterContinuousAZMode(selectedLetter)
                    val maxS = computeMaxScroll()
                    if (maxS > 0f) {
                        isScrollingList = true; isTouchingIndex = false; closeWindowMenu(); hoveredAppIndex = -1
                        scrollOffsetY = if (prefs.isAppsBottomToTop) (scrollOffsetY + dy).coerceIn(0f, maxS) else (scrollOffsetY + dy).coerceIn(-maxS, 0f)
                        rebuildAppRects(); lastTouchY = y; invalidate(); return true
                    }
                }

                if (!isScrollingList) {
                    processPointerMove(x, y)
                    invalidate()
                }
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
                } else if (event.action == MotionEvent.ACTION_UP) {
                    if (handleReleaseAction(x, y)) {
                        velocityTracker?.recycle(); velocityTracker = null; return true
                    }
                    if (downOnEmptySpace && hypot(x - downX, y - downY) < dp(14f)) {
                        velocityTracker?.recycle(); velocityTracker = null; closeWindowMenu(); onDismiss(); return true
                    }
                }
                velocityTracker?.recycle(); velocityTracker = null
                if (!isMenuVisible) closeWindowMenu()
                hoveredAppIndex = -1; isScrollingList = false; downOnEmptySpace = false; invalidate()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val left = minOf(indexBarRect.left, appColumnRect.left).toInt().coerceAtLeast(0)
                val right = maxOf(indexBarRect.right, appColumnRect.right).toInt().coerceAtMost(w)
                systemGestureExclusionRects = listOf(Rect(left, 0, right, h))
            }
        }
    }

    private fun computeGeometry(w: Float, h: Float) {
        val m = EdgeBarMetrics.compute(w, h, density, prefs)
        val left = if (prefs.isRightSide) w - m.edgeMarginPx - m.indexWidthPx else m.edgeMarginPx
        val right = left + m.indexWidthPx
        indexBarRect.set(left, m.barTopY, right, m.barBottomY)

        val scale = prefs.panelScalePercent / 100f
        val gap = dp(prefs.appsFromIndexDp.toFloat())
        val colW = dp(78f) * scale
        val colLeft = if (prefs.isRightSide) indexBarRect.left - gap - colW else indexBarRect.right + gap
        appColumnRect.set(colLeft, m.barTopY, colLeft + colW, m.barBottomY)

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

    private fun drawEnergyLightWave(canvas: Canvas, w: Float, h: Float) {
        val strength = prefs.colorWaveStrengthPercent / 100f
        if (waveProgress <= 0f || waveColor == Color.TRANSPARENT || strength <= 0.01f) return

        val env = ((1f - waveProgress) * (if (waveProgress < 0.12f) waveProgress / 0.12f else 1f)).coerceIn(0f, 1f)
        val maxReach = hypot(w, h) * (0.55f + 0.65f * strength)
        val radius = (maxReach * waveProgress).coerceAtLeast(1f)
        val r = Color.red(waveColor); val g = Color.green(waveColor); val b = Color.blue(waveColor)
        val coreR = (r + (255 - r) * 0.45f).toInt()
        val coreG = (g + (255 - g) * 0.45f).toInt()
        val coreB = (b + (255 - b) * 0.45f).toInt()

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)

        val fingerGlowR = dp(64f + 48f * strength) * (1f - waveProgress * 0.45f)
        val fingerAlpha = (210f * strength.coerceAtMost(1.2f) * env).toInt().coerceIn(0, 255)
        if (fingerAlpha > 2 && fingerGlowR > 1f) {
            energyPaint.shader = RadialGradient(
                waveOriginX, waveOriginY, fingerGlowR,
                intArrayOf(Color.argb(fingerAlpha, 255, 255, 255), Color.argb((fingerAlpha * 0.75f).toInt(), r, g, b), Color.argb(0, r, g, b)),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(waveOriginX, waveOriginY, fingerGlowR, energyPaint)
        }

        fun drawWaveRing(cx: Float, cy: Float, ringR: Float, intensity: Float) {
            val peakA = (195f * intensity * env).toInt().coerceIn(0, 255)
            if (peakA <= 2 || ringR <= 2f) return
            val bandW = dp(58f + 34f * strength)
            val end = ((ringR + bandW * 0.35f) / ringR).coerceAtMost(1f)
            val center = ((ringR - bandW * 0.15f) / ringR).coerceIn(0f, end)
            val start = ((ringR - bandW) / ringR).coerceIn(0f, center)
            energyPaint.shader = RadialGradient(
                cx, cy, ringR,
                intArrayOf(
                    Color.argb(0, r, g, b),
                    Color.argb((peakA * 0.25f).toInt(), r, g, b),
                    Color.argb(peakA, coreR, coreG, coreB),
                    Color.argb((peakA * 0.65f).toInt(), r, g, b),
                    Color.argb(0, r, g, b)
                ),
                floatArrayOf(0f, start, center, (center + end) * 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, ringR, energyPaint)
        }

        drawWaveRing(waveOriginX, waveOriginY, radius, strength.coerceAtMost(1.25f))

        val oppWallX = if (prefs.isRightSide) 0f else w
        val nearWallX = if (prefs.isRightSide) w else 0f
        if (radius > abs(waveOriginX - oppWallX)) drawWaveRing(2f * oppWallX - waveOriginX, waveOriginY, radius, strength * 0.62f)
        if (radius > waveOriginY) drawWaveRing(waveOriginX, -waveOriginY, radius, strength * 0.55f)
        if (radius > (h - waveOriginY)) drawWaveRing(waveOriginX, 2f * h - waveOriginY, radius, strength * 0.55f)
        if (radius > abs(waveOriginX - nearWallX)) drawWaveRing(2f * nearWallX - waveOriginX, waveOriginY, radius, strength * 0.40f)

        val rimThick = dp(16f)
        val oppDist = abs(waveOriginX - oppWallX)
        if (radius > oppDist) {
            val impact = ((1f - (radius - oppDist) / (maxReach * 0.65f)).coerceIn(0f, 1f) * 165f * strength * env).toInt().coerceIn(0, 255)
            if (impact > 2) {
                edgeGlowPaint.shader = if (prefs.isRightSide) LinearGradient(0f, 0f, rimThick, 0f, Color.argb(impact, coreR, coreG, coreB), Color.argb(0, r, g, b), Shader.TileMode.CLAMP)
                else LinearGradient(w, 0f, w - rimThick, 0f, Color.argb(impact, coreR, coreG, coreB), Color.argb(0, r, g, b), Shader.TileMode.CLAMP)
                canvas.drawRect(if (prefs.isRightSide) 0f else w - rimThick, 0f, if (prefs.isRightSide) rimThick else w, h, edgeGlowPaint)
            }
        }
        if (radius > waveOriginY) {
            val impact = ((1f - (radius - waveOriginY) / (maxReach * 0.65f)).coerceIn(0f, 1f) * 145f * strength * env).toInt().coerceIn(0, 255)
            if (impact > 2) {
                edgeGlowPaint.shader = LinearGradient(0f, 0f, 0f, rimThick, Color.argb(impact, coreR, coreG, coreB), Color.argb(0, r, g, b), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, rimThick, edgeGlowPaint)
            }
        }
        if (radius > (h - waveOriginY)) {
            val impact = ((1f - (radius - (h - waveOriginY)) / (maxReach * 0.65f)).coerceIn(0f, 1f) * 145f * strength * env).toInt().coerceIn(0, 255)
            if (impact > 2) {
                edgeGlowPaint.shader = LinearGradient(0f, h, 0f, h - rimThick, Color.argb(impact, coreR, coreG, coreB), Color.argb(0, r, g, b), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, h - rimThick, w, h, edgeGlowPaint)
            }
        }
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val appScale = prefs.panelScalePercent / 100f
        val idxScale = prefs.indexScalePercent / 100f
        val dirSign = if (prefs.isRightSide) -1f else 1f
        val wave = prefs.waveStrengthPercent / 100f

        val baseRgb = if (isNight) Color.rgb(10, 12, 18) else Color.rgb(240, 243, 249)
        val aNear = ((prefs.panelBgAlphaPercent / 100f) * 246f * openProgress).toInt().coerceIn(0, 255)
        val aFar = ((prefs.panelBgOppositeAlphaPercent / 100f) * 246f * openProgress).toInt().coerceIn(0, 255)
        val cNear = Color.argb(aNear, Color.red(baseRgb), Color.green(baseRgb), Color.blue(baseRgb))
        val cFar = Color.argb(aFar, Color.red(baseRgb), Color.green(baseRgb), Color.blue(baseRgb))
        bgPaint.shader = if (prefs.isRightSide) LinearGradient(0f, 0f, w, 0f, cFar, cNear, Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, w, 0f, cNear, cFar, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        drawEnergyLightWave(canvas, w, h)

        canvas.save()
        canvas.translate((1f - openProgress) * dp(24f) * (-dirSign), 0f)

        val r = indexBarRect.width() / 2f
        if (visualStyle != IndexVisualStyle.MINIMAL) {
            canvas.drawRoundRect(indexBarRect, r, r, indexPillPaint)
            if (visualStyle == IndexVisualStyle.GLASS) {
                indexSheenPaint.shader = LinearGradient(
                    indexBarRect.left, indexBarRect.top, indexBarRect.right, indexBarRect.bottom,
                    Color.argb(if (isNight) 46 else 115, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(indexBarRect, r, r, indexSheenPaint)
            }
            if (borderPaint.color != Color.TRANSPARENT) canvas.drawRoundRect(indexBarRect, r, r, borderPaint)
            if (rimPaint.color != Color.TRANSPARENT) {
                canvas.save()
                canvas.clipRect(indexBarRect.left, indexBarRect.top, indexBarRect.right, indexBarRect.top + r)
                canvas.drawRoundRect(indexBarRect, r, r, rimPaint)
                canvas.restore()
            }
        }

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
                    Color.colorToHSV(filter.swatchColor, hsvBuffer)
                    hsvBuffer[1] *= 0.42f; hsvBuffer[2] *= 0.68f
                    dotPaint.color = Color.HSVToColor(150, hsvBuffer)
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
                    val pillR = dp(11.5f) * idxScale * (1f + 0.15f * wf)
                    if (visualStyle == IndexVisualStyle.MATERIAL) {
                        tempRect.set(cx - pillR * 1.15f, cy - pillR * 0.85f, cx + pillR * 1.15f, cy + pillR * 0.85f)
                        canvas.drawRoundRect(tempRect, pillR * 0.65f, pillR * 0.65f, accentPillPaint)
                    } else {
                        canvas.drawCircle(cx, cy, pillR, accentPillPaint)
                    }
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

        val colCenterX = appColumnRect.centerX()
        val targetScale = if (hoveredAppIndex != -1 || isMenuVisible) 1.24f else 1.0f
        if (abs(currentHoverScale - targetScale) > 0.005f) {
            currentHoverScale += (targetScale - currentHoverScale) * 0.38f
            postInvalidateOnAnimation()
        } else currentHoverScale = targetScale

        canvas.save()
        canvas.clipRect(appColumnRect.left - dp(24f), indexBarRect.top, appColumnRect.right + dp(24f), indexBarRect.bottom)

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

        canvas.drawRoundRect(sideSwitchRect, dp(12f), dp(12f), sideBtnPaint)
        canvas.drawRoundRect(sideSwitchRect, dp(12f), dp(12f), borderPaint)
        textPaint.color = if (isNight) Color.WHITE else Color.parseColor("#12151C")
        textPaint.textSize = dp(16f)
        canvas.drawText("⇄", sideSwitchRect.centerX(), sideSwitchRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // Menu Opzioni App personalizzabile (Dimensione, Trasparenza, Posizione)
        val anchor = appRects.firstOrNull { it.first == menuAppIndex }?.second
        if (isMenuVisible && anchor != null) {
            val p = menuProgress.coerceIn(0f, 1.15f)
            val menuScale = prefs.appMenuScalePercent / 100f
            val btnW = dp(164f) * menuScale
            val btnH = dp(36f) * menuScale
            val gap = dp(6f) * menuScale
            val dist = dp(prefs.appMenuDistanceDp.toFloat())
            val vOffset = dp(prefs.appMenuVerticalOffsetDp.toFloat())
            val totH = WINDOW_OPTIONS.size * btnH + (WINDOW_OPTIONS.size - 1) * gap

            val rawLeft = if (prefs.isRightSide) anchor.left - btnW - dist else anchor.right + dist
            val tLeft = rawLeft.coerceIn(dp(12f), (w - btnW - dp(12f)).coerceAtLeast(dp(12f)))
            val tTop = (anchor.centerY() - totH / 2f + vOffset).coerceIn(dp(28f), (h - totH - dp(24f)).coerceAtLeast(dp(28f)))

            menuCorridorRect.set(
                minOf(anchor.left, tLeft) - dp(30f),
                minOf(anchor.top, tTop) - dp(30f),
                maxOf(anchor.right, tLeft + btnW) + dp(30f),
                maxOf(anchor.bottom, tTop + totH) + dp(30f)
            )

            textPaint.textSize = dp(12f) * menuScale
            val off = (textPaint.descent() + textPaint.ascent()) / 2f
            val menuAlpha = ((prefs.appMenuAlphaPercent / 100f) * 255).toInt().coerceIn(25, 255)
            for (i in WINDOW_OPTIONS.indices) {
                val cy = anchor.centerY() + ((tTop + i * (btnH + gap) + btnH / 2f) - anchor.centerY()) * p
                val cx = anchor.centerX() + ((tLeft + btnW / 2f) - anchor.centerX()) * p
                val hw = (btnW / 2f) * p.coerceAtLeast(0.1f)
                val hh = (btnH / 2f) * p.coerceAtLeast(0.1f)
                menuRects[i].set(cx - hw, cy - hh, cx + hw, cy + hh)

                val sel = i == hoveredMenuIndex
                canvas.drawRoundRect(menuRects[i], dp(14f * menuScale), dp(14f * menuScale), if (sel) accentPillPaint else menuBgPaint)
                canvas.drawRoundRect(menuRects[i], dp(14f * menuScale), dp(14f * menuScale), borderPaint)
                val baseTxt = if (sel || isNight) Color.WHITE else Color.parseColor("#12151C")
                textPaint.color = Color.argb(if (sel) 255 else maxOf(menuAlpha, 190), Color.red(baseTxt), Color.green(baseTxt), Color.blue(baseTxt))
                canvas.drawText(WINDOW_OPTIONS[i].title, cx, cy - off, textPaint)
            }
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        waveAnimator?.cancel()
        velocityTracker?.recycle()
        super.onDetachedFromWindow()
    }
}
