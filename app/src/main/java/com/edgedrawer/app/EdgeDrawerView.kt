package com.edgedrawer.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.exp

object EdgeIndexSlots {
    val COLORS: List<AppColorFilter> = AppColorFilter.entries.toList()
    val LETTERS: List<Char> = ('A'..'Z').toList() + listOf('★')
    val TOTAL_SLOTS: Int = COLORS.size + LETTERS.size
    val RECENT_SLOT_INDEX: Int = TOTAL_SLOTS - 1

    fun isColorSlot(slotIndex: Int): Boolean = slotIndex in COLORS.indices
    fun getColor(slotIndex: Int): AppColorFilter =
        COLORS[slotIndex.coerceIn(0, COLORS.lastIndex)]

    fun getLetter(slotIndex: Int): Char =
        LETTERS[(slotIndex - COLORS.size).coerceIn(0, LETTERS.lastIndex)]
}

class EdgeTriggerView(
    context: Context,
    private val prefs: PrefsManager
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun getSlotAtY(localY: Float): Int {
        if (height <= 0) return EdgeIndexSlots.RECENT_SLOT_INDEX
        val pad = dp(6f)
        val usableH = (height - pad * 2f).coerceAtLeast(1f)
        val clamped = (localY - pad).coerceIn(0f, usableH - 0.1f)
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

        val pad = dp(6f)
        val usableH = (h - pad * 2f).coerceAtLeast(1f)
        val stepH = usableH / EdgeIndexSlots.LETTERS.size

        val alphaInt = ((prefs.triggerAlphaPercent / 100f) * 255).toInt().coerceIn(0, 255)
        val isBlack = prefs.isTriggerTextBlack

        if (isBlack) {
            letterPaint.color = Color.argb(alphaInt, 15, 18, 24)
            letterPaint.setShadowLayer(
                dp(2.2f), 0f, dp(1f),
                Color.argb((alphaInt * 0.75f).toInt(), 255, 255, 255)
            )
        } else {
            letterPaint.color = Color.argb(alphaInt, 255, 255, 255)
            letterPaint.setShadowLayer(
                dp(2.5f), 0f, dp(1f),
                Color.argb((alphaInt * 0.85f).toInt(), 0, 0, 0)
            )
        }

        val scale = (prefs.panelScalePercent / 100f).coerceIn(0.70f, 1.30f)
        letterPaint.textSize = (stepH * 0.72f).coerceIn(dp(7f), dp(11.5f) * scale)

        val cx = w / 2f
        val textOffset = (letterPaint.descent() + letterPaint.ascent()) / 2f

        for (i in EdgeIndexSlots.LETTERS.indices) {
            val ch = EdgeIndexSlots.LETTERS[i]
            val cy = pad + stepH * (i + 0.5f)
            val label = if (ch == '★') "↓" else ch.toString()
            canvas.drawText(label, cx, cy - textOffset, letterPaint)
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
            LaunchWindowMode.POPUP,
            LaunchWindowMode.SPLIT_TOP,
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

    private var isWindowMenuVisible: Boolean = false
    private var windowMenuAppIndex: Int = -1
    private var hoveredWindowOptionIndex: Int = -1
    private var holdAnchorX: Float = 0f
    private var holdAnchorY: Float = 0f

    private val indexBarRect = RectF()
    private val slotRects = Array(EdgeIndexSlots.TOTAL_SLOTS) { RectF() }
    private val windowOptionRects = Array(WINDOW_OPTIONS.size) { RectF() }
    private val safeMenuCorridorRect = RectF()
    private val appItemRects = mutableListOf<Pair<AppEntry, RectF>>()
    private val tempRect = RectF()

    private var isContinuousGesture = false
    private var lastTouchY = 0f
    private var isScrollingAppList = false

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val dimBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedGradientWidth = -1f
    private var cachedGradientProgress = -1f

    private val indexPillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indexBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val indexTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
    private val menuBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val menuHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val menuTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var isNight: Boolean = true
    private var accentColor: Int = Color.parseColor("#3E91FF")

    private val holdRunnable = Runnable {
        if (hoveredAppIndex in displayedApps.indices && !isScrollingAppList) {
            isWindowMenuVisible = true
            windowMenuAppIndex = hoveredAppIndex
            hoveredWindowOptionIndex = -1
            HapticHelper.trigger(context, prefs, HapticHelper.PulseType.LONG_HOLD)
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        applyThemeAndPrefs()
    }

    private fun applyThemeAndPrefs() {
        isNight = prefs.isNightThemeActive()
        accentColor = prefs.getAccentColor()
        cachedGradientWidth = -1f

        val barAlpha = ((prefs.indexBarAlphaPercent / 100f) * 235).toInt().coerceIn(0, 255)

        if (isNight) {
            indexPillPaint.color = Color.argb(barAlpha, 22, 26, 36)
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
    }

    /**
     * Crea il gradiente sfumato orizzontale:
     * molto opaco sotto la colonna app/indice e sfumato verso la trasparenza sul lato opposto.
     */
    private fun updateBackgroundGradient(w: Float) {
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

        val colorNear = Color.argb(alphaNear, r, g, b)
        val colorMid = Color.argb(alphaMid, r, g, b)
        val colorFar = Color.argb(alphaFar, r, g, b)

        dimBgPaint.shader = if (prefs.isRightSide) {
            // Lato destro opaco -> centro morbido -> lato sinistro trasparente
            LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(colorFar, colorMid, colorNear, colorNear),
                floatArrayOf(0f, 0.48f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            // Lato sinistro opaco -> centro morbido -> lato destro trasparente
            LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(colorNear, colorNear, colorMid, colorFar),
                floatArrayOf(0f, 0.28f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    fun prepareOpen(fromContinuousSwipe: Boolean, initialSlot: Int, initialRawY: Float) {
        applyThemeAndPrefs()
        isContinuousGesture = fromContinuousSwipe
        cancelHoldTimer()
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

        val animDuration = if (prefs.waveStrengthPercent > 0) 180L else 90L
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animDuration
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

    private fun scheduleHoldTimer(appIdx: Int, x: Float, y: Float) {
        if (isWindowMenuVisible && windowMenuAppIndex == appIdx) return
        uiHandler.removeCallbacks(holdRunnable)
        holdAnchorX = x
        holdAnchorY = y
        uiHandler.postDelayed(holdRunnable, HOLD_DELAY_MS)
    }

    private fun cancelHoldTimer() {
        uiHandler.removeCallbacks(holdRunnable)
        if (isWindowMenuVisible) {
            isWindowMenuVisible = false
            windowMenuAppIndex = -1
            hoveredWindowOptionIndex = -1
        }
    }

    private fun selectSlotByY(y: Float, haptic: Boolean = true) {
        if (indexBarRect.height() <= 0f) return
        val pad = dp(6f)
        val usableTop = indexBarRect.top + pad
        val usableH = (indexBarRect.height() - pad * 2f).coerceAtLeast(1f)
        val ratio = ((y - usableTop) / usableH).coerceIn(0f, 0.999f)
        val slotIdx = (ratio * EdgeIndexSlots.TOTAL_SLOTS).toInt()
            .coerceIn(0, EdgeIndexSlots.TOTAL_SLOTS - 1)

        if (slotIdx != lastTouchedSlot) {
            lastTouchedSlot = slotIdx
            hoveredAppIndex = -1
            cancelHoldTimer()

            if (EdgeIndexSlots.isColorSlot(slotIdx)) {
                val newColor = EdgeIndexSlots.getColor(slotIdx)
                if (newColor != activeColorFilter) {
                    activeColorFilter = newColor
                    refreshDisplayedApps()
                }
            } else {
                val newLetter = EdgeIndexSlots.getLetter(slotIdx)
                if (newLetter != selectedLetter) {
                    selectedLetter = newLetter
                    refreshDisplayedApps()
                }
            }

            if (haptic) {
                HapticHelper.trigger(context, prefs, HapticHelper.PulseType.TICK)
            }
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
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isFingerTouchingIndex = false
                if (isContinuousGesture) {
                    if (tryExecuteReleaseAction(localX, localY)) return
                }
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
                if (optIdx != -1) {
                    HapticHelper.trigger(context, prefs, HapticHelper.PulseType.APP_HOVER)
                }
            }
            if (optIdx != -1 || safeMenuCorridorRect.contains(x, y)) {
                return
            }
            cancelHoldTimer()
        }

        if (isInIndexZone(x)) {
            isFingerTouchingIndex = true
            fingerY = y
            cancelHoldTimer()
            hoveredAppIndex = -1
            selectSlotByY(y, haptic = true)
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
            if (abs(x - holdAnchorX) > dp(18f) || abs(y - holdAnchorY) > dp(18f)) {
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
        return if (prefs.isRightSide) {
            x >= indexBarRect.left - margin
        } else {
            x <= indexBarRect.right + margin
        }
    }

    private fun findAppIndexAt(x: Float, y: Float): Int {
        for (i in appItemRects.indices) {
            if (appItemRects[i].second.contains(x, y)) {
                return i
            }
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
                    selectSlotByY(y, haptic = true)
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
                if (!isWindowMenuVisible && abs(dy) > dp(10f) && displayedApps.size > 4) {
                    isScrollingAppList = true
                    isFingerTouchingIndex = false
                    cancelHoldTimer()
                    hoveredAppIndex = -1

                    val scale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.35f)
                    val baseIconSize = dp(46f) * scale
                    val labelDist = dp(prefs.labelDistanceDp.toFloat())
                    val labelHeight = if (prefs.showAppLabels) dp(13f) * scale + labelDist else 0f
                    val slotHeight = baseIconSize + labelHeight + dp(prefs.appSpacingDp.toFloat())
                    val totalContentH = displayedApps.size * slotHeight
                    val visibleListH = (indexBarRect.height() - dp(36f) * scale).coerceAtLeast(dp(120f))
                    val maxScrollUp = -(totalContentH - visibleListH).coerceAtLeast(0f)

                    scrollOffsetY = (scrollOffsetY + dy).coerceIn(maxScrollUp, 0f)
                    lastTouchY = y
                    invalidate()
                    return true
                }

                if (!isScrollingAppList) {
                    processPointerMove(x, y)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isFingerTouchingIndex = false
                if (!isScrollingAppList && tryExecuteReleaseAction(x, y)) {
                    return true
                }
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
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            cachedGradientWidth = -1f
            computeGeometry(w.toFloat(), h.toFloat())
            if (fingerY < 0f) {
                fingerY = slotRects[lastTouchedSlot].centerY()
            }
        }
    }

    private fun computeGeometry(w: Float, h: Float) {
        val scale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.35f)
        val isRight = prefs.isRightSide

        val edgeMargin = dp(6f)
        val indexWidth = (prefs.triggerWidthDp * density).coerceAtLeast(dp(24f)) * scale
        val indexLeft = if (isRight) w - edgeMargin - indexWidth else edgeMargin
        val indexRight = indexLeft + indexWidth

        val heightRatio = (prefs.barHeightPercent / 100f).coerceIn(0.30f, 0.90f)
        val lettersHeight = h * heightRatio
        val pad = dp(6f)
        val letterStepH = (lettersHeight - pad * 2f) / EdgeIndexSlots.LETTERS.size
        val totalBarHeight = pad * 2f + letterStepH * EdgeIndexSlots.TOTAL_SLOTS

        val topSafeMargin = dp(28f)
        val bottomSafeMargin = dp(16f)
        val availableVerticalSpan = (h - topSafeMargin - bottomSafeMargin - totalBarHeight).coerceAtLeast(0f)
        val posRatio = (prefs.verticalPositionPercent / 100f).coerceIn(0f, 1f)
        val barTop = topSafeMargin + availableVerticalSpan * posRatio
        val barBottom = barTop + totalBarHeight

        indexBarRect.set(indexLeft, barTop, indexRight, barBottom)

        for (i in 0 until EdgeIndexSlots.TOTAL_SLOTS) {
            val sTop = barTop + pad + i * letterStepH
            slotRects[i].set(indexLeft, sTop, indexRight, sTop + letterStepH)
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
        val slideOffset = (1f - openProgress) * dp(28f) * (-dirSign)

        updateBackgroundGradient(w)
        canvas.drawRect(0f, 0f, w, h, dimBgPaint)

        canvas.save()
        canvas.translate(slideOffset, 0f)

        val pillRadius = indexBarRect.width() / 2f
        canvas.drawRoundRect(indexBarRect, pillRadius, pillRadius, indexPillPaint)
        canvas.drawRoundRect(indexBarRect, pillRadius, pillRadius, indexBorderPaint)

        val baseLetterSize = (slotRects[0].height() * 0.72f).coerceIn(dp(7f), dp(11.5f) * scale)
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
                val baseDotR = (slotRects[0].height() * 0.34f).coerceIn(dp(4.5f), dp(8f) * scale)
                val dotR = baseDotR * (if (isSelectedColor) 1.22f else 1f) * (1f + 0.25f * waveFactor)

                dotPaint.color = filter.swatchColor
                canvas.drawCircle(cx, cy, dotR, dotPaint)

                if (isSelectedColor) {
                    canvas.drawCircle(cx, cy, dotR + dp(2f), dotRingPaint)
                }
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
                    val alpha = if (hasApps) 235 else 65
                    indexTextPaint.color = Color.argb(
                        alpha,
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
        val colLeft = if (isRight) {
            indexBarRect.left - appGapFromIndex - appColWidth
        } else {
            indexBarRect.right + appGapFromIndex
        }
        val colRight = colLeft + appColWidth
        val colCenterX = (colLeft + colRight) / 2f

        headerPaint.textSize = dp(18f) * scale
        val headerY = indexBarRect.top + dp(18f) * scale
        val headerTitle = if (selectedLetter == '★') "Recenti (${displayedApps.size})" else selectedLetter.toString()
        canvas.drawText(headerTitle, colCenterX, headerY, headerPaint)

        val listTop = headerY + dp(12f) * scale
        val listBottom = indexBarRect.bottom
        appItemRects.clear()

        val targetScale = if (hoveredAppIndex != -1) 1.22f else 1.0f
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
        val labelHeight = if (showLabels) dp(13f) * scale + labelDist else 0f
        val spacingBetweenApps = dp(prefs.appSpacingDp.toFloat())
        val slotHeight = baseIconSize + labelHeight + spacingBetweenApps

        if (displayedApps.isEmpty()) {
            appLabelPaint.textSize = dp(11f) * scale
            canvas.drawText("Nessuna app", colCenterX, listTop + dp(32f), appLabelPaint)
        } else {
            appLabelPaint.textSize = dp(11f) * scale
            for (i in displayedApps.indices) {
                val app = displayedApps[i]
                val itemTop = listTop + i * slotHeight + scrollOffsetY
                val itemBottom = itemTop + slotHeight
                if (itemBottom >= listTop && itemTop <= listBottom) {
                    val itemRect = RectF(colLeft, itemTop, colRight, itemBottom)
                    appItemRects.add(app to itemRect)

                    val isZoomed = (i == hoveredAppIndex) ||
                        (hoveredAppIndex == -1 && i == lastAnimatedAppIndex && currentHoverScale > 1.01f)
                    val iconScale = if (isZoomed) currentHoverScale else 1.0f
                    val iconSize = baseIconSize * iconScale

                    val popShiftX = if (isZoomed) dirSign * dp(8f) * waveStrength * ((iconScale - 1f) / 0.22f) else 0f
                    val iconCenterX = colCenterX + popShiftX
                    val iconLeft = iconCenterX - iconSize / 2f
                    val iconTop = itemTop + dp(2f) - (iconSize - baseIconSize) / 2f
                    tempRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

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

    private fun drawWindowModeMenu(
        canvas: Canvas,
        anchorAppRect: RectF,
        isRight: Boolean,
        scale: Float
    ) {
        val btnWidth = dp(122f) * scale
        val btnHeight = dp(40f) * scale
        val gap = dp(8f) * scale
        val totalHeight = WINDOW_OPTIONS.size * btnHeight + (WINDOW_OPTIONS.size - 1) * gap

        val menuLeft = if (isRight) {
            anchorAppRect.left - btnWidth - dp(10f) * scale
        } else {
            anchorAppRect.right + dp(10f) * scale
        }
        val startTop = (anchorAppRect.centerY() - totalHeight / 2f)
            .coerceIn(dp(36f), height - totalHeight - dp(24f))

        val pad = dp(24f)
        safeMenuCorridorRect.set(
            minOf(anchorAppRect.left, menuLeft) - pad,
            minOf(anchorAppRect.top, startTop) - pad,
            maxOf(anchorAppRect.right, menuLeft + btnWidth) + pad,
            maxOf(anchorAppRect.bottom, startTop + totalHeight) + pad
        )

        menuTextPaint.textSize = dp(12.5f) * scale
        val textOffset = (menuTextPaint.descent() + menuTextPaint.ascent()) / 2f
        val defaultTextColor = if (isNight) Color.WHITE else Color.parseColor("#12151C")

        for (i in WINDOW_OPTIONS.indices) {
            val top = startTop + i * (btnHeight + gap)
            val rect = windowOptionRects[i]
            rect.set(menuLeft, top, menuLeft + btnWidth, top + btnHeight)

            val isSelected = (i == hoveredWindowOptionIndex)
            val paint = if (isSelected) menuHighlightPaint else menuBgPaint
            canvas.drawRoundRect(rect, dp(14f), dp(14f), paint)
            canvas.drawRoundRect(rect, dp(14f), dp(14f), indexBorderPaint)

            menuTextPaint.color = if (isSelected) Color.WHITE else defaultTextColor
            canvas.drawText(
                WINDOW_OPTIONS[i].title,
                rect.centerX(),
                rect.centerY() - textOffset,
                menuTextPaint
            )
        }
    }
}
