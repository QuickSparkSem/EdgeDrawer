package com.edgedrawer.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.exp

object EdgeIndexSlots {
    val COLORS: List<AppColorFilter> = AppColorFilter.entries.toList()
    val LETTERS: List<Char> = ('A'..'Z').toList() + listOf('★')
    val TOTAL_SLOTS: Int = COLORS.size + LETTERS.size
    val RECENT_SLOT_INDEX: Int = TOTAL_SLOTS - 1

    fun isColorSlot(slotIndex: Int): Boolean = slotIndex in COLORS.indices
    fun getColor(slotIndex: Int): AppColorFilter = COLORS[slotIndex.coerceIn(0, COLORS.lastIndex)]
    fun getLetter(slotIndex: Int): Char = LETTERS[(slotIndex - COLORS.size).coerceIn(0, LETTERS.lastIndex)]
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
        val scale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.35f)
        // Attaccato al bordo fisico dello schermo
        val edgeMargin = 0f
        val indexWidth = (prefs.triggerWidthDp * density).coerceAtLeast(20f * density) * scale

        val pad = 6f * density
        val heightRatio = (prefs.barHeightPercent / 100f).coerceIn(0.30f, 0.90f)
        val baseLettersH = screenH * heightRatio
        val extraLetterSpace = prefs.letterSpacingExtraDp * density * EdgeIndexSlots.LETTERS.size

        val maxAllowedTotalH = screenH - 32f * density
        val rawLetterSlotH = ((baseLettersH + extraLetterSpace - pad * 2f) / EdgeIndexSlots.LETTERS.size)
            .coerceAtLeast(10f * density)
        val rawColorSlotH = (rawLetterSlotH * 1.15f + prefs.colorDotSpacingExtraDp * density)
            .coerceAtLeast(12f * density)

        val rawTotalH = pad * 2f +
            rawColorSlotH * EdgeIndexSlots.COLORS.size +
            rawLetterSlotH * EdgeIndexSlots.LETTERS.size

        val fitRatio = if (rawTotalH > maxAllowedTotalH) maxAllowedTotalH / rawTotalH else 1f
        val colorSlotH = rawColorSlotH * fitRatio
        val letterSlotH = rawLetterSlotH * fitRatio

        val colorsSectionH = colorSlotH * EdgeIndexSlots.COLORS.size
        val lettersSectionH = letterSlotH * EdgeIndexSlots.LETTERS.size
        val totalBarH = pad * 2f + colorsSectionH + lettersSectionH

        val topSafeMargin = 24f * density
        val bottomSafeMargin = 14f * density
        val availableSpan = (screenH - topSafeMargin - bottomSafeMargin - totalBarH).coerceAtLeast(0f)
        val posRatio = (prefs.verticalPositionPercent / 100f).coerceIn(0f, 1f)

        val barTop = topSafeMargin + availableSpan * posRatio
        val colorsTop = barTop + pad
        val lettersTop = colorsTop + colorsSectionH
        val barBottom = barTop + totalBarH

        return Layout(
            edgeMarginPx = edgeMargin,
            indexWidthPx = indexWidth,
            barTopY = barTop,
            barBottomY = barBottom,
            colorsTopY = colorsTop,
            lettersTopY = lettersTop,
            lettersHeightPx = lettersSectionH + pad,
            colorSlotHeightPx = colorSlotH,
            letterSlotHeightPx = letterSlotH
        )
    }
}

/**
 * Trigger laterale a filo bordo: le lettere appaiono in bilico sul margine del display,
 * come se stessero per saltare fuori o scivolare dentro il bordo.
 */
class EdgeTriggerView(
    context: Context,
    private val prefs: PrefsManager
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun getSlotAtY(localY: Float): Int {
        if (height <= 0) return EdgeIndexSlots.RECENT_SLOT_INDEX
        val usableH = (height - dp(6f)).coerceAtLeast(1f)
        val clamped = localY.coerceIn(0f, usableH - 0.1f)
        val letterIdx = ((clamped / usableH) * EdgeIndexSlots.LETTERS.size).toInt()
            .coerceIn(0, EdgeIndexSlots.LETTERS.lastIndex)
        return EdgeIndexSlots.COLORS.size + letterIdx
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (prefs.isTriggerHidden || prefs.triggerAlphaPercent <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val usableH = (h - dp(6f)).coerceAtLeast(1f)
        val stepH = usableH / EdgeIndexSlots.LETTERS.size
        val alphaInt = ((prefs.triggerAlphaPercent / 100f) * 255).toInt().coerceIn(0, 255)
        val isBlack = prefs.isTriggerTextBlack
        val isRight = prefs.isRightSide

        if (isBlack) {
            letterPaint.color = Color.argb(alphaInt, 12, 15, 20)
            letterPaint.setShadowLayer(
                dp(2.5f), if (isRight) -dp(1.2f) else dp(1.2f), dp(1f),
                Color.argb((alphaInt * 0.8f).toInt(), 255, 255, 255)
            )
        } else {
            letterPaint.color = Color.argb(alphaInt, 255, 255, 255)
            letterPaint.setShadowLayer(
                dp(2.8f), if (isRight) -dp(1.4f) else dp(1.4f), dp(1f),
                Color.argb((alphaInt * 0.9f).toInt(), 0, 0, 0)
            )
        }

        val scale = (prefs.panelScalePercent / 100f).coerceIn(0.70f, 1.30f)
        letterPaint.textSize = (stepH * 0.76f).coerceIn(dp(7.5f), dp(12f) * scale)
        letterPaint.textAlign = Paint.Align.CENTER

        // Posiziona il centro della lettera quasi a filo del bordo fisico (2.5dp dentro)
        // e inclina leggermente i glifi come se sporgessero dal bordo
        val edgeAnchorX = if (isRight) w - dp(2.6f) else dp(2.6f)
        val skewX = if (isRight) 0.14f else -0.14f
        val textOffset = (letterPaint.descent() + letterPaint.ascent()) / 2f

        for (i in EdgeIndexSlots.LETTERS.indices) {
            val ch = EdgeIndexSlots.LETTERS[i]
            val cy = stepH * (i + 0.5f)
            val label = if (ch == '★') "↓" else ch.toString()

            canvas.save()
            canvas.translate(edgeAnchorX, cy)
            canvas.skew(skewX, 0f)
            canvas.drawText(label, 0f, -textOffset, letterPaint)
            canvas.restore()
        }
    }
}

class EdgeDrawerView(
    context: Context,
    private val repository: AppRepository,
    private val prefs: PrefsManager,
    private val onLaunchApp: (AppEntry, LaunchWindowMode) -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {

    companion object {
        private val WINDOW_OPTIONS = listOf(
            LaunchWindowMode.SPLIT_TOP,
            LaunchWindowMode.POPUP,
            LaunchWindowMode.SPLIT_BOTTOM
        )
        private const val HOLD_DELAY_MS = 320L
    }

    private val uiHandler = Handler(Looper.getMainLooper())

    private var selectedLetter: Char = '★'
    private var activeColorFilter: AppColorFilter = AppColorFilter.ALL
    private var lastTouchedSlot: Int = EdgeIndexSlots.RECENT_SLOT_INDEX
    private var displayedApps: List<AppEntry> = emptyList()
    private var hoveredAppIndex: Int = -1
    private var scrollOffsetY: Float = 0f

    private var fingerY: Float = -1f
    private var isFingerTouchingIndex: Boolean = false
    private var openProgress: Float = 0f
    private var currentHoverScale: Float = 1.0f
    private var lastAnimatedAppIndex: Int = -1

    // Impulso onda colore lenta dal lato indice al lato opposto
    private var colorWaveColor: Int = Color.TRANSPARENT
    private var colorWaveProgress: Float = 0f
    private var colorWaveAnimator: ValueAnimator? = null

    // Menu finestre (Schermo diviso su / Pop-up / Schermo diviso giù) con animazione d'uscita dall'app
    private var isWindowMenuVisible: Boolean = false
    private var windowMenuAppIndex: Int = -1
    private var hoveredWindowOptionIndex: Int = -1
    private var menuRevealProgress: Float = 0f
    private var menuAnimator: ValueAnimator? = null
    private var holdAnchorX: Float = 0f
    private var holdAnchorY: Float = 0f

    // Scorrimento continuo vicino al bordo alto/basso dello schermo senza staccare il dito
    private var autoScrollVelocityY: Float = 0f
    private var lastPointerX: Float = 0f
    private var lastPointerY: Float = 0f
    private var isAutoScrolling: Boolean = false
    private var isContinuousGesture: Boolean = false
    private var lastTouchY: Float = 0f
    private var isScrollingAppList: Boolean = false

    private val indexBarRect = RectF()
    private val slotRects = Array(EdgeIndexSlots.TOTAL_SLOTS) { RectF() }
    private val windowOptionRects = Array(WINDOW_OPTIONS.size) { RectF() }
    private val safeMenuCorridorRect = RectF()
    private val appItemRects = mutableListOf<Pair<AppEntry, RectF>>()
    private val tempRect = RectF()

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val dimBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val deepShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colorWavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedGradientWidth = -1f
    private var cachedGradientProgress = -1f

    private val indexPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(dp(12f), 0f, dp(4f), Color.argb(215, 0, 0, 0))
    }
    private val indexBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val indexTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val appHighlightRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val appLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val menuBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(dp(10f), 0f, dp(3f), Color.argb(170, 0, 0, 0))
    }
    private val menuHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val menuTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var isNight: Boolean = true
    private var accentColor: Int = Color.parseColor("#3E91FF")

    private val holdRunnable = Runnable {
        if (hoveredAppIndex in displayedApps.indices && !isScrollingAppList && abs(autoScrollVelocityY) < 0.5f) {
            isWindowMenuVisible = true
            windowMenuAppIndex = hoveredAppIndex
            hoveredWindowOptionIndex = -1
            HapticHelper.trigger(context, prefs, HapticHelper.PulseType.LONG_HOLD)
            animateMenuReveal()
        }
    }

    private val autoScrollTickRunnable = object : Runnable {
        override fun run() {
            if (!isAutoScrolling || abs(autoScrollVelocityY) < 0.2f) {
                isAutoScrolling = false
                return
            }
            val maxScroll = computeMaxScrollOffset()
            val prev = scrollOffsetY
            scrollOffsetY = if (prefs.isAppsBottomToTop) {
                (scrollOffsetY + autoScrollVelocityY).coerceIn(0f, maxScroll)
            } else {
                (scrollOffsetY + autoScrollVelocityY).coerceIn(-maxScroll, 0f)
            }
            if (scrollOffsetY != prev) {
                hoveredAppIndex = findAppIndexAt(lastPointerX, lastPointerY)
                invalidate()
            }
            uiHandler.postDelayed(this, 16L)
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        applyThemeAndPrefs()
    }

    private fun applyThemeAndPrefs() {
        isNight = prefs.isNightThemeActive()
        accentColor = prefs.getAccentColor()
        cachedGradientWidth = -1f

        val barAlpha = ((prefs.indexBarAlphaPercent / 100f) * 240).toInt().coerceIn(0, 255)
        if (isNight) {
            indexPillPaint.color = Color.argb(barAlpha, 18, 21, 30)
            indexBorderPaint.color = Color.argb((barAlpha * 0.28f).toInt(), 255, 255, 255)
            headerPaint.color = Color.WHITE
            appLabelPaint.color = Color.WHITE
            appLabelPaint.setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(220, 0, 0, 0))
            dotRingPaint.color = Color.WHITE
            menuBgPaint.color = Color.argb(248, 26, 31, 44)
        } else {
            indexPillPaint.color = Color.argb(barAlpha, 255, 255, 255)
            indexBorderPaint.color = Color.argb((barAlpha * 0.25f).toInt(), 15, 20, 30)
            headerPaint.color = Color.parseColor("#12151C")
            appLabelPaint.color = Color.parseColor("#12151C")
            appLabelPaint.setShadowLayer(dp(2.5f), 0f, dp(1f), Color.argb(200, 255, 255, 255))
            dotRingPaint.color = Color.parseColor("#12151C")
            menuBgPaint.color = Color.argb(250, 255, 255, 255)
        }

        activePillPaint.color = accentColor
        menuHighlightPaint.color = accentColor
        appHighlightRingPaint.color = accentColor
    }

    private fun animateMenuReveal() {
        menuAnimator?.cancel()
        menuRevealProgress = 0f
        menuAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 210L
            interpolator = OvershootInterpolator(1.15f)
            addUpdateListener {
                menuRevealProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun triggerDirectionalColorWave(color: Int) {
        if (prefs.colorWaveStrengthPercent <= 0) return
        colorWaveColor = color
        colorWaveAnimator?.cancel()
        colorWaveProgress = 0.01f
        colorWaveAnimator = ValueAnimator.ofFloat(0.01f, 1f).apply {
            duration = 880L
            interpolator = DecelerateInterpolator(1.3f)
            addUpdateListener {
                colorWaveProgress = it.animatedValue as Float
                if (colorWaveProgress >= 0.999f) colorWaveProgress = 0f
                invalidate()
            }
            start()
        }
    }

    private fun updateBackgroundShaders(w: Float) {
        if (w <= 0f) return
        if (w == cachedGradientWidth && abs(openProgress - cachedGradientProgress) < 0.01f) return
        cachedGradientWidth = w
        cachedGradientProgress = openProgress

        val baseRgb = if (isNight) Color.rgb(11, 13, 19) else Color.rgb(240, 243, 249)
        val r = Color.red(baseRgb)
        val g = Color.green(baseRgb)
        val b = Color.blue(baseRgb)

        val alphaNear = ((prefs.panelBgAlphaPercent / 100f) * 245f * openProgress).toInt().coerceIn(0, 255)
        val alphaFar = ((prefs.panelBgOppositeAlphaPercent / 100f) * 245f * openProgress).toInt().coerceIn(0, 255)
        val alphaMid = ((alphaNear * 0.62f) + (alphaFar * 0.38f)).toInt().coerceIn(0, 255)

        val cNear = Color.argb(alphaNear, r, g, b)
        val cMid = Color.argb(alphaMid, r, g, b)
        val cFar = Color.argb(alphaFar, r, g, b)

        dimBgPaint.shader = if (prefs.isRightSide) {
            LinearGradient(0f, 0f, w, 0f, intArrayOf(cFar, cMid, cNear, cNear), floatArrayOf(0f, 0.48f, 0.72f, 1f), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, w, 0f, intArrayOf(cNear, cNear, cMid, cFar), floatArrayOf(0f, 0.28f, 0.52f, 1f), Shader.TileMode.CLAMP)
        }

        val shadowAlpha = (235f * openProgress).toInt().coerceIn(0, 255)
        val deepBlack = Color.argb(shadowAlpha, 0, 0, 0)
        val midBlack = Color.argb((shadowAlpha * 0.55f).toInt(), 0, 0, 0)
        val clearBlack = Color.argb(0, 0, 0, 0)
        val shadowW = dp(90f)

        deepShadowPaint.shader = if (prefs.isRightSide) {
            LinearGradient(w - shadowW, 0f, w, 0f, intArrayOf(clearBlack, midBlack, deepBlack), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, shadowW, 0f, intArrayOf(deepBlack, midBlack, clearBlack), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
    }

    fun prepareOpen(fromContinuousSwipe: Boolean, initialSlot: Int) {
        applyThemeAndPrefs()
        isContinuousGesture = fromContinuousSwipe
        cancelHoldTimer()
        stopAutoScroll()
        hoveredAppIndex = -1
        currentHoverScale = 1.0f
        scrollOffsetY = 0f

        val safeSlot = initialSlot.coerceIn(EdgeIndexSlots.COLORS.size, EdgeIndexSlots.TOTAL_SLOTS - 1)
        lastTouchedSlot = safeSlot
        selectedLetter = EdgeIndexSlots.getLetter(safeSlot)
        refreshDisplayedApps()

        if (width > 0 && height > 0) {
            computeGeometry(width.toFloat(), height.toFloat())
            fingerY = slotRects[safeSlot].centerY()
            isFingerTouchingIndex = fromContinuousSwipe
        }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (prefs.waveStrengthPercent > 0) 180L else 90L
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener {
                openProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun refreshDisplayedApps() {
        scrollOffsetY = 0f
        displayedApps = if (selectedLetter == '★') {
            repository.getRecentApps(prefs.recentCount, activeColorFilter)
        } else {
            repository.getAppsForLetter(selectedLetter, activeColorFilter)
        }
    }

    private fun getSlotHeightPx(): Float {
        val scale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.35f)
        val baseIconSize = dp(46f) * scale
        val labelDist = dp(prefs.labelDistanceDp.toFloat())
        val labelHeight = if (prefs.showAppLabels) dp(13f) * scale + labelDist else 0f
        return baseIconSize + labelHeight + dp(prefs.appSpacingDp.toFloat())
    }

    private fun computeMaxScrollOffset(): Float {
        val scale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.35f)
        val totalContentH = displayedApps.size * getSlotHeightPx()
        val visibleListH = (indexBarRect.height() - dp(36f) * scale).coerceAtLeast(dp(120f))
        return (totalContentH - visibleListH).coerceAtLeast(0f)
    }

    /**
     * Scorrimento continuo quando il dito (senza staccarsi) si avvicina al bordo alto o basso dello schermo.
     */
    private fun updateAutoScrollForContinuousDrag(x: Float, y: Float) {
        lastPointerX = x
        lastPointerY = y
        val maxScroll = computeMaxScrollOffset()
        if (maxScroll <= 0f || isInIndexZone(x) || isWindowMenuVisible || height <= 0) {
            stopAutoScroll()
            return
        }

        val edgeZonePx = dp(prefs.autoScrollEdgeZoneDp.toFloat())
        val pxPerFrame = (prefs.autoScrollAppsPerSec * getSlotHeightPx()) / 60f
        val isBottomUp = prefs.isAppsBottomToTop

        // Verso l'alto dello schermo (y <= edgeZonePx) -> scorre l'elenco verso l'alto
        // Verso il basso dello schermo (y >= height - edgeZonePx) -> scorre l'elenco verso il basso
        val newVelocity = when {
            y <= edgeZonePx -> {
                val factor = (1f - (y / edgeZonePx)).coerceIn(0.25f, 1.4f)
                val mag = pxPerFrame * factor
                if (isBottomUp) mag else -mag
            }
            y >= height - edgeZonePx -> {
                val factor = (1f - ((height - y) / edgeZonePx)).coerceIn(0.25f, 1.4f)
                val mag = pxPerFrame * factor
                if (isBottomUp) -mag else mag
            }
            else -> 0f
        }

        autoScrollVelocityY = newVelocity
        if (abs(newVelocity) >= 0.2f) {
            if (!isAutoScrolling) {
                isAutoScrolling = true
                uiHandler.post(autoScrollTickRunnable)
            }
        } else {
            stopAutoScroll()
        }
    }

    private fun stopAutoScroll() {
        autoScrollVelocityY = 0f
        isAutoScrolling = false
        uiHandler.removeCallbacks(autoScrollTickRunnable)
    }

    private fun scheduleHoldTimer(appIdx: Int, x: Float, y: Float) {
        if (isWindowMenuVisible && windowMenuAppIndex == appIdx) return
        uiHandler.removeCallbacks(holdRunnable)
        holdAnchorX = x
        holdAnchorY = y
        uiHandler.postDelayed(holdRunnable, HOLD_DELAY_MS)
    }

    private fun cancelHoldTimer() {
        uiHandler.removeCallbacks(holdRunnable)
        menuAnimator?.cancel()
        if (isWindowMenuVisible) {
            isWindowMenuVisible = false
            windowMenuAppIndex = -1
            hoveredWindowOptionIndex = -1
            menuRevealProgress = 0f
        }
    }

    private fun selectSlotByY(y: Float) {
        if (indexBarRect.height() <= 0f) return
        var slotIdx = EdgeIndexSlots.TOTAL_SLOTS - 1
        for (i in 0 until EdgeIndexSlots.TOTAL_SLOTS) {
            if (y <= slotRects[i].bottom) {
                slotIdx = i
                break
            }
        }
        if (y < slotRects[0].top) slotIdx = 0

        if (slotIdx != lastTouchedSlot) {
            lastTouchedSlot = slotIdx
            hoveredAppIndex = -1
            cancelHoldTimer()

            if (EdgeIndexSlots.isColorSlot(slotIdx)) {
                val newColor = EdgeIndexSlots.getColor(slotIdx)
                if (newColor != activeColorFilter) {
                    activeColorFilter = newColor
                    refreshDisplayedApps()
                    triggerDirectionalColorWave(newColor.swatchColor)
                }
            } else {
                val newLetter = EdgeIndexSlots.getLetter(slotIdx)
                if (newLetter != selectedLetter) {
                    selectedLetter = newLetter
                    refreshDisplayedApps()
                }
            }
            HapticHelper.trigger(context, prefs, HapticHelper.PulseType.TICK)
        }
    }

    fun handleExternalDrag(rawX: Float, rawY: Float, action: Int) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val localX = rawX - loc[0]
        val localY = rawY - loc[1]

        when (action) {
            MotionEvent.ACTION_MOVE -> {
                isContinuousGesture = true
                processPointerMove(localX, localY)
                updateAutoScrollForContinuousDrag(localX, localY)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isFingerTouchingIndex = false
                stopAutoScroll()
                if (isContinuousGesture && tryExecuteReleaseAction(localX, localY)) return
                cancelHoldTimer()
                isContinuousGesture = false
                hoveredAppIndex = -1
                invalidate()
            }
        }
    }

    private fun processPointerMove(x: Float, y: Float) {
        if (isWindowMenuVisible) {
            val optIdx = findWindowOptionAt(x, y)
            if (optIdx != hoveredWindowOptionIndex) {
                hoveredWindowOptionIndex = optIdx
                if (optIdx != -1) HapticHelper.trigger(context, prefs, HapticHelper.PulseType.APP_HOVER)
            }
            if (optIdx != -1 || safeMenuCorridorRect.contains(x, y)) return
            cancelHoldTimer()
        }

        if (isInIndexZone(x)) {
            isFingerTouchingIndex = true
            fingerY = y
            cancelHoldTimer()
            stopAutoScroll()
            hoveredAppIndex = -1
            selectSlotByY(y)
            return
        }

        isFingerTouchingIndex = false

        val appIdx = findAppIndexAt(x, y)
        if (appIdx != hoveredAppIndex) {
            hoveredAppIndex = appIdx
            if (appIdx != -1) {
                lastAnimatedAppIndex = appIdx
                currentHoverScale = 1.02f
                HapticHelper.trigger(context, prefs, HapticHelper.PulseType.APP_HOVER)
                scheduleHoldTimer(appIdx, x, y)
            } else {
                cancelHoldTimer()
            }
        } else if (appIdx != -1) {
            if (abs(x - holdAnchorX) > dp(16f) || abs(y - holdAnchorY) > dp(16f)) {
                scheduleHoldTimer(appIdx, x, y)
            }
        }
    }

    private fun tryExecuteReleaseAction(x: Float, y: Float): Boolean {
        if (isWindowMenuVisible && windowMenuAppIndex in displayedApps.indices) {
            val targetApp = displayedApps[windowMenuAppIndex]
            val optIdx = findWindowOptionAt(x, y)
            if (optIdx in WINDOW_OPTIONS.indices) {
                val chosenMode = WINDOW_OPTIONS[optIdx]
                cancelHoldTimer()
                HapticHelper.trigger(context, prefs, HapticHelper.PulseType.CONFIRM)
                onLaunchApp(targetApp, chosenMode)
                return true
            }
            if (windowMenuAppIndex in appItemRects.indices &&
                appItemRects[windowMenuAppIndex].second.contains(x, y)
            ) {
                cancelHoldTimer()
                HapticHelper.trigger(context, prefs, HapticHelper.PulseType.CONFIRM)
                onLaunchApp(targetApp, LaunchWindowMode.NORMAL)
                return true
            }
            cancelHoldTimer()
            return false
        }

        val appIdx = findAppIndexAt(x, y)
        if (appIdx in displayedApps.indices) {
            val target = displayedApps[appIdx]
            cancelHoldTimer()
            HapticHelper.trigger(context, prefs, HapticHelper.PulseType.CONFIRM)
            onLaunchApp(target, LaunchWindowMode.NORMAL)
            return true
        }
        return false
    }

    private fun isInIndexZone(x: Float): Boolean {
        val margin = dp(10f)
        return if (prefs.isRightSide) x >= indexBarRect.left - margin else x <= indexBarRect.right + margin
    }

    private fun findAppIndexAt(x: Float, y: Float): Int {
        for (i in appItemRects.indices) {
            if (appItemRects[i].second.contains(x, y)) return i
        }
        return -1
    }

    private fun findWindowOptionAt(x: Float, y: Float): Int {
        val hitSlop = dp(6f)
        for (i in windowOptionRects.indices) {
            val r = windowOptionRects[i]
            if (x >= r.left - hitSlop && x <= r.right + hitSlop &&
                y >= r.top - hitSlop && y <= r.bottom + hitSlop
            ) {
                return i
            }
        }
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = y
                isScrollingAppList = false

                if (isInIndexZone(x) && y >= indexBarRect.top - dp(12f) && y <= indexBarRect.bottom + dp(12f)) {
                    isFingerTouchingIndex = true
                    fingerY = y
                    cancelHoldTimer()
                    selectSlotByY(y)
                    invalidate()
                    return true
                }

                val appIdx = findAppIndexAt(x, y)
                if (appIdx != -1) {
                    hoveredAppIndex = appIdx
                    lastAnimatedAppIndex = appIdx
                    currentHoverScale = 1.02f
                    scheduleHoldTimer(appIdx, x, y)
                    invalidate()
                    return true
                }

                cancelHoldTimer()
                onDismiss()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isWindowMenuVisible) {
                    processPointerMove(x, y)
                    invalidate()
                    return true
                }

                if (isInIndexZone(x)) {
                    isScrollingAppList = false
                    processPointerMove(x, y)
                    invalidate()
                    return true
                }

                val dy = y - lastTouchY
                val maxScroll = computeMaxScrollOffset()
                if (!isWindowMenuVisible && abs(dy) > dp(8f) && maxScroll > 0f) {
                    isScrollingAppList = true
                    isFingerTouchingIndex = false
                    cancelHoldTimer()
                    hoveredAppIndex = -1

                    scrollOffsetY = if (prefs.isAppsBottomToTop) {
                        (scrollOffsetY + dy).coerceIn(0f, maxScroll)
                    } else {
                        (scrollOffsetY + dy).coerceIn(-maxScroll, 0f)
                    }
                    lastTouchY = y
                    invalidate()
                    return true
                }

                if (!isScrollingAppList) {
                    processPointerMove(x, y)
                    updateAutoScrollForContinuousDrag(x, y)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isFingerTouchingIndex = false
                stopAutoScroll()
                if (!isScrollingAppList && tryExecuteReleaseAction(x, y)) return true
                cancelHoldTimer()
                hoveredAppIndex = -1
                isScrollingAppList = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacks(holdRunnable)
        stopAutoScroll()
        colorWaveAnimator?.cancel()
        menuAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            cachedGradientWidth = -1f
            computeGeometry(w.toFloat(), h.toFloat())
            if (fingerY < 0f) fingerY = slotRects[lastTouchedSlot].centerY()
        }
    }

    private fun computeGeometry(w: Float, h: Float) {
        val m = EdgeBarMetrics.compute(w, h, density, prefs)
        val isRight = prefs.isRightSide
        val indexLeft = if (isRight) w - m.edgeMarginPx - m.indexWidthPx else m.edgeMarginPx
        val indexRight = indexLeft + m.indexWidthPx

        indexBarRect.set(indexLeft, m.barTopY, indexRight, m.barBottomY)

        for (i in EdgeIndexSlots.COLORS.indices) {
            val sTop = m.colorsTopY + i * m.colorSlotHeightPx
            slotRects[i].set(indexLeft, sTop, indexRight, sTop + m.colorSlotHeightPx)
        }
        for (j in EdgeIndexSlots.LETTERS.indices) {
            val slotIdx = EdgeIndexSlots.COLORS.size + j
            val sTop = m.lettersTopY + j * m.letterSlotHeightPx
            slotRects[slotIdx].set(indexLeft, sTop, indexRight, sTop + m.letterSlotHeightPx)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val scale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.35f)
        val isRight = prefs.isRightSide
        val dirSign = if (isRight) -1f else 1f
        val waveStrength = (prefs.waveStrengthPercent / 100f).coerceIn(0f, 1.5f)
        val slideOffset = (1f - openProgress) * dp(24f) * (-dirSign)

        updateBackgroundShaders(w)
        canvas.drawRect(0f, 0f, w, h, dimBgPaint)
        canvas.drawRect(0f, 0f, w, h, deepShadowPaint)

        // Onda luminosa lenta di colore che viaggia dal lato indice verso il lato opposto
        if (colorWaveProgress > 0f && colorWaveColor != Color.TRANSPARENT && prefs.colorWaveStrengthPercent > 0) {
            val strengthRatio = prefs.colorWaveStrengthPercent / 100f
            val fadeEnv = (1f - colorWaveProgress) * (if (colorWaveProgress < 0.15f) colorWaveProgress / 0.15f else 1f)
            val peakAlpha = (155f * strengthRatio * fadeEnv).toInt().coerceIn(0, 255)
            val cr = Color.red(colorWaveColor)
            val cg = Color.green(colorWaveColor)
            val cb = Color.blue(colorWaveColor)

            val cPeak = Color.argb(peakAlpha, cr, cg, cb)
            val cZero = Color.argb(0, cr, cg, cb)
            val front = colorWaveProgress

            val positions = if (isRight) {
                val pFront = (1f - front).coerceIn(0f, 1f)
                val pTrail = (1f - front * 0.45f).coerceIn(pFront, 1f)
                floatArrayOf(0f, (pFront - 0.14f).coerceAtLeast(0f), pFront, pTrail, 1f)
            } else {
                val pFront = front.coerceIn(0f, 1f)
                val pTrail = (front * 0.45f).coerceIn(0f, pFront)
                floatArrayOf(0f, pTrail, pFront, (pFront + 0.14f).coerceAtMost(1f), 1f)
            }
            val colors = if (isRight) {
                intArrayOf(cZero, cZero, cPeak, Color.argb((peakAlpha * 0.35f).toInt(), cr, cg, cb), cZero)
            } else {
                intArrayOf(cZero, Color.argb((peakAlpha * 0.35f).toInt(), cr, cg, cb), cPeak, cZero, cZero)
            }
            colorWavePaint.shader = LinearGradient(0f, 0f, w, 0f, colors, positions, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, colorWavePaint)
        }

        canvas.save()
        canvas.translate(slideOffset, 0f)

        val pillRadius = indexBarRect.width() / 2f
        canvas.drawRoundRect(indexBarRect, pillRadius, pillRadius, indexPillPaint)
        canvas.drawRoundRect(indexBarRect, pillRadius, pillRadius, indexBorderPaint)

        val firstLetterSlotIdx = EdgeIndexSlots.COLORS.size
        val baseLetterSize = (slotRects[firstLetterSlotIdx].height() * 0.72f).coerceIn(dp(7f), dp(11.5f) * scale)
        val waveRadiusPx = dp(58f) * scale
        val maxWaveShiftPx = dp(20f) * scale * waveStrength

        for (i in 0 until EdgeIndexSlots.TOTAL_SLOTS) {
            val rect = slotRects[i]
            val cy = rect.centerY()

            val dist = if (isFingerTouchingIndex && fingerY >= 0f) abs(cy - fingerY) else 9999f
            val waveFactor = if (dist < waveRadiusPx * 2.2f && waveStrength > 0f) {
                exp(-(dist * dist) / (2f * waveRadiusPx * waveRadiusPx))
            } else {
                0f
            }
            val waveShiftX = dirSign * maxWaveShiftPx * waveFactor

            if (EdgeIndexSlots.isColorSlot(i)) {
                val filter = EdgeIndexSlots.getColor(i)
                val isSelectedColor = (filter == activeColorFilter)
                val cx = rect.centerX() + waveShiftX
                val baseDotR = (slotRects[0].height() * 0.32f).coerceIn(dp(4.5f), dp(8f) * scale)
                val dotR = baseDotR * (if (isSelectedColor) 1.24f else 1f) * (1f + 0.25f * waveFactor)

                dotPaint.color = filter.swatchColor
                canvas.drawCircle(cx, cy, dotR, dotPaint)
                if (isSelectedColor) canvas.drawCircle(cx, cy, dotR + dp(2f), dotRingPaint)
            } else {
                val ch = EdgeIndexSlots.getLetter(i)
                val isSelected = (ch == selectedLetter)
                val hasApps = (ch == '★') || repository.hasAppsForLetter(ch, activeColorFilter)

                val cx = rect.centerX() + waveShiftX
                if (isSelected) {
                    val pillR = (dp(11.5f) * scale) * (1f + 0.15f * waveFactor)
                    canvas.drawCircle(cx, cy, pillR, activePillPaint)
                    indexTextPaint.color = Color.WHITE
                } else {
                    val baseTextColor = if (isNight) Color.WHITE else Color.parseColor("#12151C")
                    indexTextPaint.color = Color.argb(
                        if (hasApps) 235 else 65,
                        Color.red(baseTextColor),
                        Color.green(baseTextColor),
                        Color.blue(baseTextColor)
                    )
                }

                indexTextPaint.textSize = baseLetterSize * (1f + 0.35f * waveFactor)
                val textOffset = (indexTextPaint.descent() + indexTextPaint.ascent()) / 2f
                val label = if (ch == '★') "↓" else ch.toString()
                canvas.drawText(label, cx, cy - textOffset, indexTextPaint)
            }
        }

        val appGapFromIndex = dp(prefs.appsFromIndexDp.toFloat())
        val appColWidth = dp(76f) * scale
        val colLeft = if (isRight) indexBarRect.left - appGapFromIndex - appColWidth else indexBarRect.right + appGapFromIndex
        val colRight = colLeft + appColWidth
        val colCenterX = (colLeft + colRight) / 2f

        val isBottomUp = prefs.isAppsBottomToTop
        headerPaint.textSize = dp(18f) * scale
        val headerTitle = if (selectedLetter == '★') "Recenti (${displayedApps.size})" else selectedLetter.toString()

        val listTop: Float
        val listBottom: Float
        if (isBottomUp) {
            val headerBottomY = indexBarRect.bottom - dp(6f) * scale
            canvas.drawText(headerTitle, colCenterX, headerBottomY, headerPaint)
            listTop = indexBarRect.top
            listBottom = headerBottomY - dp(22f) * scale
        } else {
            val headerY = indexBarRect.top + dp(18f) * scale
            canvas.drawText(headerTitle, colCenterX, headerY, headerPaint)
            listTop = headerY + dp(12f) * scale
            listBottom = indexBarRect.bottom
        }

        appItemRects.clear()

        val targetScale = if (hoveredAppIndex != -1 || isWindowMenuVisible) 1.24f else 1.0f
        if (abs(currentHoverScale - targetScale) > 0.005f) {
            currentHoverScale += (targetScale - currentHoverScale) * 0.38f
            postInvalidateOnAnimation()
        } else {
            currentHoverScale = targetScale
        }

        canvas.save()
        canvas.clipRect(colLeft - dp(24f), listTop, colRight + dp(24f), listBottom)

        val showLabels = prefs.showAppLabels
        val baseIconSize = dp(46f) * scale
        val labelDist = dp(prefs.labelDistanceDp.toFloat())
        val slotHeight = getSlotHeightPx()

        if (displayedApps.isEmpty()) {
            appLabelPaint.textSize = dp(11f) * scale
            val emptyY = if (isBottomUp) listBottom - dp(24f) else listTop + dp(32f)
            canvas.drawText("Nessuna app", colCenterX, emptyY, appLabelPaint)
        } else {
            appLabelPaint.textSize = dp(11f) * scale
            for (i in displayedApps.indices) {
                val app = displayedApps[i]
                val itemTop = if (isBottomUp) {
                    listBottom - (i + 1) * slotHeight + scrollOffsetY
                } else {
                    listTop + i * slotHeight + scrollOffsetY
                }
                val itemBottom = itemTop + slotHeight
                if (itemBottom >= listTop && itemTop <= listBottom) {
                    val itemRect = RectF(colLeft, itemTop, colRight, itemBottom)
                    appItemRects.add(app to itemRect)

                    val isActiveHoldApp = isWindowMenuVisible && windowMenuAppIndex == i
                    val isZoomed = isActiveHoldApp || (i == hoveredAppIndex) ||
                        (hoveredAppIndex == -1 && !isWindowMenuVisible && i == lastAnimatedAppIndex && currentHoverScale > 1.01f)
                    val iconScale = if (isZoomed) currentHoverScale else 1.0f
                    val iconSize = baseIconSize * iconScale

                    val popShiftX = if (isZoomed) dirSign * dp(8f) * waveStrength * ((iconScale - 1f) / 0.24f) else 0f
                    val iconCenterX = colCenterX + popShiftX
                    val iconLeft = iconCenterX - iconSize / 2f
                    val iconTop = itemTop + dp(2f) - (iconSize - baseIconSize) / 2f
                    tempRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    // Alone luminoso attorno all'app selezionata per il menu finestra
                    if (isActiveHoldApp) {
                        canvas.drawCircle(
                            tempRect.centerX(),
                            tempRect.centerY(),
                            iconSize * 0.62f,
                            appHighlightRingPaint
                        )
                    }

                    canvas.drawBitmap(app.iconBitmap, null, tempRect, bitmapPaint)

                    if (showLabels) {
                        val labelBaselineY = itemTop + dp(2f) + baseIconSize + labelDist + dp(10f) * scale
                        canvas.drawText(app.shortLabel, iconCenterX, labelBaselineY, appLabelPaint)
                    }
                }
            }
        }
        canvas.restore()

        if (isWindowMenuVisible && windowMenuAppIndex in appItemRects.indices) {
            drawWindowModeMenu(canvas, appItemRects[windowMenuAppIndex].second, isRight, scale)
        }

        canvas.restore()
    }

    /**
     * Disegna i 3 tasti (Schermo diviso su / Pop-up / Schermo diviso giù)
     * che emergono animati direttamente dal centro dell'icona app tenuta col dito.
     */
    private fun drawWindowModeMenu(
        canvas: Canvas,
        anchorAppRect: RectF,
        isRight: Boolean,
        scale: Float
    ) {
        val progress = menuRevealProgress.coerceIn(0f, 1.15f)
        val btnWidth = dp(148f) * scale
        val btnHeight = dp(40f) * scale
        val gap = dp(8f) * scale
        val totalHeight = WINDOW_OPTIONS.size * btnHeight + (WINDOW_OPTIONS.size - 1) * gap

        val targetLeft = if (isRight) {
            anchorAppRect.left - btnWidth - dp(12f) * scale
        } else {
            anchorAppRect.right + dp(12f) * scale
        }
        val targetStartTop = (anchorAppRect.centerY() - totalHeight / 2f)
            .coerceIn(dp(36f), height - totalHeight - dp(24f))

        val pad = dp(26f)
        safeMenuCorridorRect.set(
            minOf(anchorAppRect.left, targetLeft) - pad,
            minOf(anchorAppRect.top, targetStartTop) - pad,
            maxOf(anchorAppRect.right, targetLeft + btnWidth) + pad,
            maxOf(anchorAppRect.bottom, targetStartTop + totalHeight) + pad
        )

        val originX = anchorAppRect.centerX()
        val originY = anchorAppRect.centerY()

        menuTextPaint.textSize = dp(12f) * scale
        val textOffset = (menuTextPaint.descent() + menuTextPaint.ascent()) / 2f
        val defaultTextColor = if (isNight) Color.WHITE else Color.parseColor("#12151C")
        val alphaInt = (255f * progress.coerceIn(0f, 1f)).toInt()

        for (i in WINDOW_OPTIONS.indices) {
            val targetTop = targetStartTop + i * (btnHeight + gap)
            val targetCenterX = targetLeft + btnWidth / 2f
            val targetCenterY = targetTop + btnHeight / 2f

            // Interpolazione dal centro dell'app alla posizione finale del pulsante
            val curCenterX = originX + (targetCenterX - originX) * progress
            val curCenterY = originY + (targetCenterY - originY) * progress
            val curHalfW = (btnWidth / 2f) * progress.coerceAtLeast(0.1f)
            val curHalfH = (btnHeight / 2f) * progress.coerceAtLeast(0.1f)

            val rect = windowOptionRects[i]
            rect.set(
                curCenterX - curHalfW,
                curCenterY - curHalfH,
                curCenterX + curHalfW,
                curCenterY + curHalfH
            )

            val isSelected = (i == hoveredWindowOptionIndex)
            val paint = if (isSelected) menuHighlightPaint else menuBgPaint
            val prevAlpha = paint.alpha
            paint.alpha = (prevAlpha * (alphaInt / 255f)).toInt()

            canvas.drawRoundRect(rect, dp(14f), dp(14f), paint)
            canvas.drawRoundRect(rect, dp(14f), dp(14f), indexBorderPaint)
            paint.alpha = prevAlpha

            menuTextPaint.color = if (isSelected) Color.WHITE else defaultTextColor
            menuTextPaint.alpha = alphaInt
            canvas.drawText(
                WINDOW_OPTIONS[i].title,
                rect.centerX(),
                rect.centerY() - textOffset,
                menuTextPaint
            )
        }
    }
}
