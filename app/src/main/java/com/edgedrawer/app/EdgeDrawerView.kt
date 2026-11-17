package com.edgedrawer.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
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
        // Scala dedicata esclusivamente all'indice alfabetico (coerente tra trigger e pannello aperto)
        val idxScale = (prefs.indexScalePercent / 100f).coerceIn(0.65f, 1.40f)
        val edgeMargin = prefs.triggerEdgeOffsetDp * density
        val indexWidth = (prefs.triggerWidthDp * density).coerceAtLeast(20f * density) * idxScale

        val pad = 6f * density
        val heightRatio = (prefs.barHeightPercent / 100f).coerceIn(0.30f, 0.90f)
        val baseLettersH = screenH * heightRatio
        val extraLetterSpace = prefs.letterSpacingExtraDp * density * EdgeIndexSlots.LETTERS.size

        val maxAllowedTotalH = screenH - 32f * density
        val rawLetterSlotH = ((baseLettersH + extraLetterSpace - pad * 2f) / EdgeIndexSlots.LETTERS.size)
            .coerceAtLeast(9f * density) * idxScale
        val rawColorSlotH = (rawLetterSlotH * 1.12f + prefs.colorDotSpacingExtraDp * density)
            .coerceAtLeast(11f * density)

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

class EdgeTriggerView(
    context: Context,
    private val prefs: PrefsManager
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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
        val isRight = prefs.isRightSide

        val customColor = prefs.triggerCustomColor
        letterPaint.color = Color.argb(
            alphaInt,
            Color.red(customColor),
            Color.green(customColor),
            Color.blue(customColor)
        )

        val shadowDepthRatio = prefs.triggerShadowDepthPercent / 100f
        if (shadowDepthRatio > 0.01f) {
            val luma = Color.luminance(customColor)
            val shadowCol = if (luma < 0.25f) {
                Color.argb((230 * shadowDepthRatio).toInt(), 255, 255, 255)
            } else {
                Color.argb((245 * shadowDepthRatio).toInt(), 0, 0, 0)
            }
            val radius = dp(1.5f + 5.5f * shadowDepthRatio)
            val dx = (if (isRight) -1f else 1f) * dp(0.8f + 2.6f * shadowDepthRatio)
            val dy = dp(0.8f + 2.2f * shadowDepthRatio)
            letterPaint.setShadowLayer(radius, dx, dy, shadowCol)
        } else {
            letterPaint.clearShadowLayer()
        }

        val idxScale = (prefs.indexScalePercent / 100f).coerceIn(0.65f, 1.40f)
        letterPaint.textSize = (stepH * 0.76f).coerceIn(dp(7f), dp(12.5f) * idxScale)

        // Quando distance == 0, le lettere sono incassate a filo bordo come se uscissero dal display
        val edgeAnchorX = if (isRight) w - dp(2.2f) else dp(2.2f)
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
        private const val HOLD_DELAY_MS = 320L
    }

    private sealed class ListItem {
        data class Header(val title: String) : ListItem()
        data class App(val entry: AppEntry) : ListItem()
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val scroller = OverScroller(context, DecelerateInterpolator(1.6f))
    private var velocityTracker: VelocityTracker? = null

    private var selectedLetter: Char = '★'
    private var activeColorFilter: AppColorFilter = AppColorFilter.ALL
    private var lastTouchedSlot: Int = EdgeIndexSlots.RECENT_SLOT_INDEX

    // Modalità elenco continuo A -> Z quando l'utente rilascia il dito e scorre liberamente
    private var isContinuousAZMode: Boolean = false
    private var displayedItems: List<ListItem> = emptyList()
    private val letterStartItemIndex = HashMap<Char, Int>()

    private var hoveredAppIndex: Int = -1
    private var scrollOffsetY: Float = 0f

    private var fingerY: Float = -1f
    private var isFingerTouchingIndex: Boolean = false
    private var openProgress: Float = 0f
    private var currentHoverScale: Float = 1.0f
    private var lastAnimatedAppIndex: Int = -1

    private var colorWaveColor: Int = Color.TRANSPARENT
    private var colorWaveProgress: Float = 0f
    private var colorWaveAnimator: ValueAnimator? = null

    private var isWindowMenuVisible: Boolean = false
    private var windowMenuAppIndex: Int = -1
    private var hoveredWindowOptionIndex: Int = -1
    private var menuRevealProgress: Float = 0f
    private var menuAnimator: ValueAnimator? = null
    private var holdAnchorX: Float = 0f
    private var holdAnchorY: Float = 0f

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
    private val sideSwitchRect = RectF()
    private val appItemRects = ArrayList<Pair<Int, RectF>>()
    private val tempRect = RectF()

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float = value * scaledDensity

    private val dimBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val deepShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colorWavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedGradientWidth = -1f
    private var cachedGradientProgress = -1f

    // Effetto 3D: ombra profonda + bordo di riflesso superiore per staccare dal contesto
    private val indexPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(dp(16f), 0f, dp(6f), Color.argb(235, 0, 0, 0))
    }
    private val indexBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val indexHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val indexTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(dp(6f), 0f, dp(2f), Color.argb(180, 0, 0, 0))
    }
    private val appHighlightRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val sectionDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val appLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
    }
    private val menuBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(dp(14f), 0f, dp(5f), Color.argb(215, 0, 0, 0))
    }
    private val menuHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val menuTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val sideBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(dp(8f), 0f, dp(3f), Color.argb(180, 0, 0, 0))
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var isNight: Boolean = true
    private var accentColor: Int = Color.parseColor("#3E91FF")

    private val holdRunnable = Runnable {
        if (hoveredAppIndex in displayedItems.indices &&
            displayedItems[hoveredAppIndex] is ListItem.App &&
            !isScrollingAppList &&
            abs(autoScrollVelocityY) < 0.5f
        ) {
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
                // Ricostruisce subito le coordinate app durante lo scroll così il punto di tocco non si disallinea mai
                rebuildAppRects()
                val newHover = findAppIndexAt(lastPointerX, lastPointerY)
                if (newHover != hoveredAppIndex) {
                    hoveredAppIndex = newHover
                    if (newHover != -1) {
                        lastAnimatedAppIndex = newHover
                        HapticHelper.trigger(context, prefs, HapticHelper.PulseType.APP_HOVER)
                    }
                }
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

        val barAlpha = ((prefs.indexBarAlphaPercent / 100f) * 242).toInt().coerceIn(0, 255)
        if (isNight) {
            indexPillPaint.color = Color.argb(barAlpha, 18, 22, 32)
            indexBorderPaint.color = Color.argb((barAlpha * 0.25f).toInt(), 255, 255, 255)
            indexHighlightPaint.color = Color.argb((barAlpha * 0.45f).toInt(), 255, 255, 255)
            headerPaint.color = Color.WHITE
            sectionDividerPaint.color = accentColor
            appLabelPaint.color = Color.WHITE
            appLabelPaint.setShadowLayer(dp(3f), 0f, dp(1.2f), Color.argb(235, 0, 0, 0))
            dotRingPaint.color = Color.WHITE
            menuBgPaint.color = Color.argb(250, 24, 29, 42)
            sideBtnPaint.color = Color.argb(235, 28, 34, 48)
        } else {
            indexPillPaint.color = Color.argb(barAlpha, 255, 255, 255)
            indexBorderPaint.color = Color.argb((barAlpha * 0.22f).toInt(), 15, 20, 30)
            indexHighlightPaint.color = Color.argb(220, 255, 255, 255)
            headerPaint.color = Color.parseColor("#12151C")
            sectionDividerPaint.color = accentColor
            appLabelPaint.color = Color.parseColor("#12151C")
            appLabelPaint.setShadowLayer(dp(2.5f), 0f, dp(1f), Color.argb(210, 255, 255, 255))
            dotRingPaint.color = Color.parseColor("#12151C")
            menuBgPaint.color = Color.argb(252, 255, 255, 255)
            sideBtnPaint.color = Color.argb(240, 255, 255, 255)
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
            duration = 920L
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

        val baseRgb = if (isNight) Color.rgb(10, 12, 18) else Color.rgb(240, 243, 249)
        val r = Color.red(baseRgb)
        val g = Color.green(baseRgb)
        val b = Color.blue(baseRgb)

        val alphaNear = ((prefs.panelBgAlphaPercent / 100f) * 246f * openProgress).toInt().coerceIn(0, 255)
        val alphaFar = ((prefs.panelBgOppositeAlphaPercent / 100f) * 246f * openProgress).toInt().coerceIn(0, 255)
        val alphaMid = ((alphaNear * 0.62f) + (alphaFar * 0.38f)).toInt().coerceIn(0, 255)

        val cNear = Color.argb(alphaNear, r, g, b)
        val cMid = Color.argb(alphaMid, r, g, b)
        val cFar = Color.argb(alphaFar, r, g, b)

        dimBgPaint.shader = if (prefs.isRightSide) {
            LinearGradient(0f, 0f, w, 0f, intArrayOf(cFar, cMid, cNear, cNear), floatArrayOf(0f, 0.48f, 0.72f, 1f), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, w, 0f, intArrayOf(cNear, cNear, cMid, cFar), floatArrayOf(0f, 0.28f, 0.52f, 1f), Shader.TileMode.CLAMP)
        }

        val shadowAlpha = (240f * openProgress).toInt().coerceIn(0, 255)
        val deepBlack = Color.argb(shadowAlpha, 0, 0, 0)
        val midBlack = Color.argb((shadowAlpha * 0.55f).toInt(), 0, 0, 0)
        val clearBlack = Color.argb(0, 0, 0, 0)
        val shadowW = dp(95f)

        deepShadowPaint.shader = if (prefs.isRightSide) {
            LinearGradient(w - shadowW, 0f, w, 0f, intArrayOf(clearBlack, midBlack, deepBlack), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, shadowWidth = shadowW, 0f, intArrayOf(deepBlack, midBlack, clearBlack), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
    }

    fun prepareOpen(fromContinuousSwipe: Boolean, initialSlot: Int) {
        applyThemeAndPrefs()
        isContinuousGesture = fromContinuousSwipe
        isContinuousAZMode = false
        scroller.abortAnimation()
        cancelHoldTimer()
        stopAutoScroll()
        hoveredAppIndex = -1
        currentHoverScale = 1.0f
        scrollOffsetY = 0f

        val safeSlot = initialSlot.coerceIn(EdgeIndexSlots.COLORS.size, EdgeIndexSlots.TOTAL_SLOTS - 1)
        lastTouchedSlot = safeSlot
        selectedLetter = EdgeIndexSlots.getLetter(safeSlot)
        refreshDisplayedItems()

        if (width > 0 && height > 0) {
            computeGeometry(width.toFloat(), height.toFloat())
            fingerY = slotRects[safeSlot].centerY()
            isFingerTouchingIndex = fromContinuousSwipe
            rebuildAppRects()
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

    private fun refreshDisplayedItems() {
        scrollOffsetY = 0f
        scroller.abortAnimation()
        letterStartItemIndex.clear()

        if (isContinuousAZMode) {
            val combined = ArrayList<ListItem>()
            for (ch in 'A'..'Z') {
                val apps = repository.getAppsForLetter(ch, activeColorFilter)
                if (apps.isNotEmpty()) {
                    letterStartItemIndex[ch] = combined.size
                    combined.add(ListItem.Header(ch.toString()))
                    apps.forEach { combined.add(ListItem.App(it)) }
                }
            }
            displayedItems = combined
        } else {
            val apps = if (selectedLetter == '★') {
                repository.getRecentApps(prefs.recentCount, activeColorFilter)
            } else {
                repository.getAppsForLetter(selectedLetter, activeColorFilter)
            }
            displayedItems = apps.map { ListItem.App(it) }
        }
        rebuildAppRects()
    }

    private fun enterContinuousAZMode(jumpToLetter: Char) {
        if (!isContinuousAZMode) {
            isContinuousAZMode = true
            refreshDisplayedItems()
        }
        val targetIdx = letterStartItemIndex[jumpToLetter] ?: return
        val maxScroll = computeMaxScrollOffset()
        val slotH = getSlotHeightPx()
        scrollOffsetY = if (prefs.isAppsBottomToTop) {
            (targetIdx * slotH).coerceIn(0f, maxScroll)
        } else {
            -(targetIdx * slotH).coerceIn(0f, maxScroll)
        }
        rebuildAppRects()
    }

    private fun getSlotHeightPx(): Float {
        val appScale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.40f)
        val baseIconSize = dp(46f) * appScale
        val labelDist = dp(prefs.labelDistanceDp.toFloat())
        val labelHeight = if (prefs.showAppLabels) sp(prefs.appLabelSizeSp.toFloat()) * 1.25f + labelDist else 0f
        return baseIconSize + labelHeight + dp(prefs.appSpacingDp.toFloat())
    }

    private fun getListBounds(): Pair<Float, Float> {
        val appScale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.40f)
        return if (prefs.isAppsBottomToTop) {
            val headerBottomY = indexBarRect.bottom - dp(6f) * appScale
            indexBarRect.top to (headerBottomY - dp(22f) * appScale)
        } else {
            val headerY = indexBarRect.top + dp(18f) * appScale
            (headerY + dp(12f) * appScale) to indexBarRect.bottom
        }
    }

    private fun computeMaxScrollOffset(): Float {
        val (listTop, listBottom) = getListBounds()
        val visibleListH = (listBottom - listTop).coerceAtLeast(dp(120f))
        val totalContentH = displayedItems.size * getSlotHeightPx()
        return (totalContentH - visibleListH).coerceAtLeast(0f)
    }

    private fun rebuildAppRects() {
        appItemRects.clear()
        if (width <= 0 || height <= 0) return

        val appScale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.40f)
        val isRight = prefs.isRightSide
        val appGapFromIndex = dp(prefs.appsFromIndexDp.toFloat())
        val appColWidth = dp(78f) * appScale
        val colLeft = if (isRight) indexBarRect.left - appGapFromIndex - appColWidth else indexBarRect.right + appGapFromIndex
        val colRight = colLeft + appColWidth

        val (listTop, listBottom) = getListBounds()
        val isBottomUp = prefs.isAppsBottomToTop
        val slotHeight = getSlotHeightPx()

        for (i in displayedItems.indices) {
            if (displayedItems[i] !is ListItem.App) continue
            val itemTop = if (isBottomUp) {
                listBottom - (i + 1) * slotHeight + scrollOffsetY
            } else {
                listTop + i * slotHeight + scrollOffsetY
            }
            val itemBottom = itemTop + slotHeight
            if (itemBottom >= listTop && itemTop <= listBottom) {
                appItemRects.add(i to RectF(colLeft, itemTop, colRight, itemBottom))
            }
        }
    }

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

        val newVelocity = when {
            y <= edgeZonePx -> {
                val factor = (1f - (y / edgeZonePx)).coerceIn(0.3f, 1.5f)
                val mag = pxPerFrame * factor
                if (isBottomUp) mag else -mag
            }
            y >= height - edgeZonePx -> {
                val factor = (1f - ((height - y) / edgeZonePx)).coerceIn(0.3f, 1.5f)
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
                    refreshDisplayedItems()
                    triggerDirectionalColorWave(newColor.swatchColor)
                }
            } else {
                val newLetter = EdgeIndexSlots.getLetter(slotIdx)
                if (newLetter != selectedLetter) {
                    selectedLetter = newLetter
                    if (isContinuousAZMode && newLetter in 'A'..'Z') {
                        enterContinuousAZMode(newLetter)
                    } else {
                        isContinuousAZMode = false
                        refreshDisplayedItems()
                    }
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

                // Al rilascio senza aver lanciato nulla, abilita lo scorrimento continuo fluido dalla A alla Z
                if (selectedLetter != '★') {
                    enterContinuousAZMode(selectedLetter)
                }
                invalidate()
            }
        }
    }

    private fun processPointerMove(x: Float, y: Float) {
        lastPointerX = x
        lastPointerY = y

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
        if (isWindowMenuVisible && windowMenuAppIndex in displayedItems.indices) {
            val targetItem = displayedItems[windowMenuAppIndex] as? ListItem.App
            if (targetItem != null) {
                val optIdx = findWindowOptionAt(x, y)
                if (optIdx in WINDOW_OPTIONS.indices) {
                    val chosenMode = WINDOW_OPTIONS[optIdx]
                    cancelHoldTimer()
                    HapticHelper.trigger(context, prefs, HapticHelper.PulseType.CONFIRM)
                    onLaunchApp(targetItem.entry, chosenMode)
                    return true
                }
                val anchorRect = appItemRects.firstOrNull { it.first == windowMenuAppIndex }?.second
                if (anchorRect != null && anchorRect.contains(x, y)) {
                    cancelHoldTimer()
                    HapticHelper.trigger(context, prefs, HapticHelper.PulseType.CONFIRM)
                    onLaunchApp(targetItem.entry, LaunchWindowMode.NORMAL)
                    return true
                }
            }
            cancelHoldTimer()
            return false
        }

        val appIdx = findAppIndexAt(x, y)
        val targetItem = displayedItems.getOrNull(appIdx) as? ListItem.App
        if (targetItem != null) {
            cancelHoldTimer()
            HapticHelper.trigger(context, prefs, HapticHelper.PulseType.CONFIRM)
            onLaunchApp(targetItem.entry, LaunchWindowMode.NORMAL)
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
            if (appItemRects[i].second.contains(x, y)) return appItemRects[i].first
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

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffsetY = scroller.currY.toFloat()
            rebuildAppRects()
            updateActiveLetterFromScroll()
            postInvalidateOnAnimation()
        }
    }

    private fun updateActiveLetterFromScroll() {
        if (!isContinuousAZMode || appItemRects.isEmpty()) return
        val firstVisibleIdx = appItemRects.first().first
        val item = displayedItems.getOrNull(firstVisibleIdx)
        val ch = when (item) {
            is ListItem.Header -> item.title.firstOrNull()
            is ListItem.App -> item.entry.firstLetter
            else -> null
        }
        if (ch != null && ch in 'A'..'Z' && ch != selectedLetter) {
            selectedLetter = ch
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                scroller.abortAnimation()
                lastTouchY = y
                isScrollingAppList = false

                // Pulsante rapido cambio lato Destra <-> Sinistra
                if (sideSwitchRect.contains(x, y)) {
                    cancelHoldTimer()
                    HapticHelper.trigger(context, prefs, HapticHelper.PulseType.CONFIRM)
                    onToggleSide()
                    return true
                }

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
                if (!isWindowMenuVisible && abs(dy) > dp(7f)) {
                    if (!isContinuousAZMode && selectedLetter != '★') {
                        enterContinuousAZMode(selectedLetter)
                    }
                    val maxScroll = computeMaxScrollOffset()
                    if (maxScroll > 0f) {
                        isScrollingAppList = true
                        isFingerTouchingIndex = false
                        cancelHoldTimer()
                        hoveredAppIndex = -1

                        scrollOffsetY = if (prefs.isAppsBottomToTop) {
                            (scrollOffsetY + dy).coerceIn(0f, maxScroll)
                        } else {
                            (scrollOffsetY + dy).coerceIn(-maxScroll, 0f)
                        }
                        rebuildAppRects()
                        updateActiveLetterFromScroll()
                        lastTouchY = y
                        invalidate()
                        return true
                    }
                }

                if (!isScrollingAppList) {
                    processPointerMove(x, y)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isFingerTouchingIndex = false
                stopAutoScroll()

                if (isScrollingAppList) {
                    velocityTracker?.computeCurrentVelocity(1000, 14000f)
                    val vy = velocityTracker?.yVelocity ?: 0f
                    val maxScroll = computeMaxScrollOffset()
                    if (abs(vy) > 250f && maxScroll > 0f) {
                        val minY = if (prefs.isAppsBottomToTop) 0 else -maxScroll.toInt()
                        val maxY = if (prefs.isAppsBottomToTop) maxScroll.toInt() else 0
                        scroller.fling(0, scrollOffsetY.toInt(), 0, (vy * 1.45f).toInt(), 0, 0, minY, maxY)
                        postInvalidateOnAnimation()
                    }
                } else if (tryExecuteReleaseAction(x, y)) {
                    recycleVelocityTracker()
                    return true
                }

                recycleVelocityTracker()
                cancelHoldTimer()
                hoveredAppIndex = -1
                isScrollingAppList = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacks(holdRunnable)
        stopAutoScroll()
        colorWaveAnimator?.cancel()
        menuAnimator?.cancel()
        recycleVelocityTracker()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            cachedGradientWidth = -1f
            computeGeometry(w.toFloat(), h.toFloat())
            if (fingerY < 0f) fingerY = slotRects[lastTouchedSlot].centerY()
            rebuildAppRects()
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

        // Pulsante rapido cambio mano (Destra <-> Sinistra) nell'angolo inferiore del pannello
        val btnSize = dp(38f)
        val marginSide = dp(18f)
        val btnBottom = h - dp(26f)
        val btnLeft = if (isRight) marginSide else w - marginSide - btnSize
        sideSwitchRect.set(btnLeft, btnBottom - btnSize, btnLeft + btnSize, btnBottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val appScale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.40f)
        val idxScale = (prefs.indexScalePercent / 100f).coerceIn(0.65f, 1.40f)
        val isRight = prefs.isRightSide
        val dirSign = if (isRight) -1f else 1f
        val waveStrength = (prefs.waveStrengthPercent / 100f).coerceIn(0f, 1.5f)
        val slideOffset = (1f - openProgress) * dp(24f) * (-dirSign)

        updateBackgroundShaders(w)
        canvas.drawRect(0f, 0f, w, h, dimBgPaint)
        canvas.drawRect(0f, 0f, w, h, deepShadowPaint)

        // Onda luminosa lenta di colore dal lato indice al lato opposto
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

        // Barra indice 3D con riflesso di rilievo e ombra profonda
        val pillRadius = indexBarRect.width() / 2f
        canvas.drawRoundRect(indexBarRect, pillRadius, pillRadius, indexPillPaint)
        canvas.drawRoundRect(indexBarRect, pillRadius, pillRadius, indexBorderPaint)
        canvas.save()
        canvas.clipRect(indexBarRect.left, indexBarRect.top, indexBarRect.right, indexBarRect.top + pillRadius)
        canvas.drawRoundRect(indexBarRect, pillRadius, pillRadius, indexHighlightPaint)
        canvas.restore()

        val firstLetterSlotIdx = EdgeIndexSlots.COLORS.size
        val baseLetterSize = (slotRects[firstLetterSlotIdx].height() * 0.72f).coerceIn(dp(7f), dp(12.5f) * idxScale)
        val waveRadiusPx = dp(58f) * idxScale
        val maxWaveShiftPx = dp(20f) * idxScale * waveStrength

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
                val isHoveredColor = isFingerTouchingIndex && (i == lastTouchedSlot)
                val cx = rect.centerX() + waveShiftX
                val baseDotR = (slotRects[0].height() * 0.30f).coerceIn(dp(4.2f), dp(7.5f) * idxScale)
                val dotR = baseDotR * (if (isSelectedColor || isHoveredColor) 1.28f else 0.92f) * (1f + 0.25f * waveFactor)

                // Colori leggermente smorti/spenti finché non ci passi sopra o non sono attivi,
                // così le lettere dell'alfabeto restano visivamente in primo piano
                val rawCol = filter.swatchColor
                if (isSelectedColor || isHoveredColor) {
                    dotPaint.color = rawCol
                } else {
                    val hsv = FloatArray(3)
                    Color.colorToHSV(rawCol, hsv)
                    hsv[1] *= 0.42f
                    hsv[2] *= 0.68f
                    dotPaint.color = Color.HSVToColor(150, hsv)
                }

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
                    val pillR = (dp(11.5f) * idxScale) * (1f + 0.15f * waveFactor)
                    canvas.drawCircle(cx, cy, pillR, activePillPaint)
                    indexTextPaint.color = Color.WHITE
                } else {
                    val baseTextColor = if (isNight) Color.WHITE else Color.parseColor("#12151C")
                    indexTextPaint.color = Color.argb(
                        if (hasApps) 245 else 75,
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
        val appColWidth = dp(78f) * appScale
        val colLeft = if (isRight) indexBarRect.left - appGapFromIndex - appColWidth else indexBarRect.right + appGapFromIndex
        val colRight = colLeft + appColWidth
        val colCenterX = (colLeft + colRight) / 2f

        val isBottomUp = prefs.isAppsBottomToTop
        headerPaint.textSize = dp(18f) * appScale
        val headerTitle = when {
            isContinuousAZMode -> "A–Z ($selectedLetter)"
            selectedLetter == '★' -> "Recenti (${displayedItems.size})"
            else -> selectedLetter.toString()
        }

        val (listTop, listBottom) = getListBounds()
        if (isBottomUp) {
            val headerBottomY = indexBarRect.bottom - dp(6f) * appScale
            canvas.drawText(headerTitle, colCenterX, headerBottomY, headerPaint)
        } else {
            val headerY = indexBarRect.top + dp(18f) * appScale
            canvas.drawText(headerTitle, colCenterX, headerY, headerPaint)
        }

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
        val baseIconSize = dp(46f) * appScale
        val labelDist = dp(prefs.labelDistanceDp.toFloat())
        val slotHeight = getSlotHeightPx()
        val labelFontPx = sp(prefs.appLabelSizeSp.toFloat())

        if (displayedItems.isEmpty()) {
            appLabelPaint.textSize = labelFontPx
            val emptyY = if (isBottomUp) listBottom - dp(24f) else listTop + dp(32f)
            canvas.drawText("Nessuna app", colCenterX, emptyY, appLabelPaint)
        } else {
            appLabelPaint.textSize = labelFontPx
            sectionDividerPaint.textSize = dp(15f) * appScale

            for (i in displayedItems.indices) {
                val itemTop = if (isBottomUp) {
                    listBottom - (i + 1) * slotHeight + scrollOffsetY
                } else {
                    listTop + i * slotHeight + scrollOffsetY
                }
                val itemBottom = itemTop + slotHeight
                if (itemBottom < listTop || itemTop > listBottom) continue

                when (val item = displayedItems[i]) {
                    is ListItem.Header -> {
                        val centerY = (itemTop + itemBottom) / 2f
                        canvas.drawText("— ${item.title} —", colCenterX, centerY, sectionDividerPaint)
                    }
                    is ListItem.App -> {
                        val app = item.entry
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

                        if (isActiveHoldApp) {
                            canvas.drawCircle(tempRect.centerX(), tempRect.centerY(), iconSize * 0.62f, appHighlightRingPaint)
                        }

                        // App archiviate disegnate con leggera trasparenza distintiva
                        bitmapPaint.alpha = if (app.isArchived) 175 else 255
                        canvas.drawBitmap(app.iconBitmap, null, tempRect, bitmapPaint)
                        bitmapPaint.alpha = 255

                        if (showLabels) {
                            val labelBaselineY = itemTop + dp(2f) + baseIconSize + labelDist + labelFontPx
                            val textToDraw = if (app.isArchived) "☁ ${app.shortLabel}" else app.shortLabel
                            canvas.drawText(textToDraw, iconCenterX, labelBaselineY, appLabelPaint)
                        }
                    }
                }
            }
        }
        canvas.restore()

        // Tasto rapido cambio mano Destra <-> Sinistra (⇄) in basso
        canvas.drawRoundRect(sideSwitchRect, dp(12f), dp(12f), sideBtnPaint)
        canvas.drawRoundRect(sideSwitchRect, dp(12f), dp(12f), indexBorderPaint)
        indexTextPaint.color = if (isNight) Color.WHITE else Color.parseColor("#12151C")
        indexTextPaint.textSize = dp(16f)
        val switchOffset = (indexTextPaint.descent() + indexTextPaint.ascent()) / 2f
        canvas.drawText("⇄", sideSwitchRect.centerX(), sideSwitchRect.centerY() - switchOffset, indexTextPaint)

        val anchorPair = appItemRects.firstOrNull { it.first == windowMenuAppIndex }
        if (isWindowMenuVisible && anchorPair != null) {
            drawWindowModeMenu(canvas, anchorPair.second, isRight, appScale)
        }

        canvas.restore()
    }

    private fun drawWindowModeMenu(
        canvas: Canvas,
        anchorAppRect: RectF,
        isRight: Boolean,
        scale: Float
    ) {
        val progress = menuRevealProgress.coerceIn(0f, 1.15f)
        val btnWidth = dp(152f) * scale
        val btnHeight = dp(38f) * scale
        val gap = dp(7f) * scale
        val totalHeight = WINDOW_OPTIONS.size * btnHeight + (WINDOW_OPTIONS.size - 1) * gap

        val targetLeft = if (isRight) {
            anchorAppRect.left - btnWidth - dp(12f) * scale
        } else {
            anchorAppRect.right + dp(12f) * scale
        }
        val targetStartTop = (anchorAppRect.centerY() - totalHeight / 2f)
            .coerceIn(dp(32f), height - totalHeight - dp(24f))

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
