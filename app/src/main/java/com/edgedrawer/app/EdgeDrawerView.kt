package com.edgedrawer.app

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Trigger laterale trasparente che mostra direttamente l'alfabeto (↓, A-Z)
 * con leggera ombra, ridimensionabile e posizionabile anche piccolo nell'angolo in basso.
 */
class EdgeTriggerView(
    context: Context,
    private val prefs: PrefsManager
) : View(context) {

    companion object {
        val INDEX_ITEMS: List<Char> = listOf('★') + ('A'..'Z').toList()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(dp(2.5f), 0f, dp(1f), Color.argb(210, 0, 0, 0))
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun getLetterAtY(localY: Float): Char {
        if (height <= 0) return '★'
        val pad = dp(6f)
        val usableH = (height - pad * 2f).coerceAtLeast(1f)
        val clamped = (localY - pad).coerceIn(0f, usableH - 0.1f)
        val idx = ((clamped / usableH) * INDEX_ITEMS.size).toInt()
            .coerceIn(0, INDEX_ITEMS.lastIndex)
        return INDEX_ITEMS[idx]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (prefs.isTriggerHidden || prefs.triggerAlphaPercent <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = dp(6f)
        val usableH = (h - pad * 2f).coerceAtLeast(1f)
        val stepH = usableH / INDEX_ITEMS.size

        val alphaInt = ((prefs.triggerAlphaPercent / 100f) * 255).toInt().coerceIn(0, 255)
        letterPaint.color = Color.argb(alphaInt, 255, 255, 255)
        letterPaint.setShadowLayer(dp(2.5f), 0f, dp(1f), Color.argb((alphaInt * 0.85f).toInt(), 0, 0, 0))

        // Adatta la dimensione del testo se l'utente lo rimpicciolisce nell'angolo
        val computedSize = (stepH * 0.72f).coerceIn(dp(6.5f), dp(11.5f))
        letterPaint.textSize = computedSize

        val cx = w / 2f
        val textOffset = (letterPaint.descent() + letterPaint.ascent()) / 2f

        for (i in INDEX_ITEMS.indices) {
            val ch = INDEX_ITEMS[i]
            val cy = pad + stepH * (i + 0.5f)
            val label = if (ch == '★') "↓" else ch.toString()
            canvas.drawText(label, cx, cy - textOffset, letterPaint)
        }
    }
}

/**
 * Pannello laterale fluido con indice A-Z, pallini colore diretti in basso
 * e menu rapido tenendo il dito su un'app (Pop-up / Metà sopra / Metà sotto).
 */
class EdgeDrawerView(
    context: Context,
    private val repository: AppRepository,
    private val prefs: PrefsManager,
    private val onLaunchApp: (AppEntry, LaunchWindowMode) -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {

    companion object {
        private val INDEX_ITEMS: List<Char> = listOf('★') + ('A'..'Z').toList()
        private val WINDOW_OPTIONS = listOf(
            LaunchWindowMode.POPUP,
            LaunchWindowMode.SPLIT_TOP,
            LaunchWindowMode.SPLIT_BOTTOM
        )
        private const val HOLD_DELAY_MS = 380L
    }

    private var selectedIndexItem: Char = '★'
    private var activeColorFilter: AppColorFilter = AppColorFilter.ALL
    private var displayedApps: List<AppEntry> = emptyList()
    private var hoveredAppIndex: Int = -1
    private var scrollOffsetY: Float = 0f

    // Animazione fluida zoom icone (senza allocazioni in onDraw)
    private var currentHoverScale: Float = 1.0f
    private var lastAnimatedAppIndex: Int = -1

    // Menu tenuta su app (Pop-up / Metà sopra / Metà sotto)
    private var isWindowMenuVisible: Boolean = false
    private var windowMenuAppIndex: Int = -1
    private var hoveredWindowOptionIndex: Int = -1
    private var holdAnchorX: Float = 0f
    private var holdAnchorY: Float = 0f

    // Rettangoli pre-allocati per hit-testing a zero allocazioni
    private val indexBarRect = RectF()
    private val indexItemRects = Array(INDEX_ITEMS.size) { RectF() }
    private val colorDotRects = Array(AppColorFilter.entries.size) { RectF() }
    private val windowOptionRects = Array(WINDOW_OPTIONS.size) { RectF() }
    private val appItemRects = mutableListOf<Pair<AppEntry, RectF>>()
    private val tempRect = RectF()

    private var isContinuousGestureFromEdge = false
    private var lastTouchY = 0f
    private var isScrollingAppList = false

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    // Oggetti Paint pre-allocati
    private val dimBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val indexTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D94FF")
    }
    private val headerLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val appLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(210, 0, 0, 0))
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val menuBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(242, 24, 29, 42)
    }
    private val menuHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D94FF")
    }
    private val menuTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val holdRunnable = Runnable {
        if (hoveredAppIndex in displayedApps.indices && !isScrollingAppList) {
            isWindowMenuVisible = true
            windowMenuAppIndex = hoveredAppIndex
            hoveredWindowOptionIndex = -1
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        applyVisualPrefs()
    }

    private fun applyVisualPrefs() {
        val alphaRatio = (prefs.panelAlphaPercent / 100f).coerceIn(0.15f, 1f)
        dimBgPaint.color = Color.argb((180 * alphaRatio).toInt(), 11, 13, 19)
        panelGlassPaint.color = Color.argb((225 * alphaRatio).toInt(), 22, 26, 36)
        panelBorderPaint.color = Color.argb((60 * alphaRatio).toInt(), 255, 255, 255)
    }

    fun prepareOpen(fromContinuousSwipe: Boolean, initialLetter: Char, initialY: Float) {
        applyVisualPrefs()
        isContinuousGestureFromEdge = fromContinuousSwipe
        cancelHoldTimer()
        hoveredAppIndex = -1
        currentHoverScale = 1.0f
        scrollOffsetY = 0f

        selectedIndexItem = initialLetter
        refreshDisplayedApps()
        if (width > 0 && height > 0) {
            computeStaticGeometry(width.toFloat(), height.toFloat())
            if (initialLetter == '★') {
                selectIndexByY(initialY, haptic = false)
            }
        }
        invalidate()
    }

    private fun refreshDisplayedApps() {
        scrollOffsetY = 0f
        displayedApps = if (selectedIndexItem == '★') {
            repository.getRecentApps(prefs.recentCount, activeColorFilter)
        } else {
            repository.getAppsForLetter(selectedIndexItem, activeColorFilter)
        }
    }

    private fun scheduleHoldTimer(appIdx: Int, x: Float, y: Float) {
        if (isWindowMenuVisible && windowMenuAppIndex == appIdx) return
        cancelHoldTimer()
        holdAnchorX = x
        holdAnchorY = y
        postDelayed(holdRunnable, HOLD_DELAY_MS)
    }

    private fun cancelHoldTimer() {
        removeCallbacks(holdRunnable)
        if (isWindowMenuVisible) {
            isWindowMenuVisible = false
            windowMenuAppIndex = -1
            hoveredWindowOptionIndex = -1
        }
    }

    private fun selectIndexByY(y: Float, haptic: Boolean = true) {
        if (indexBarRect.height() <= 0f) return
        val clampedY = y.coerceIn(indexBarRect.top + dp(2f), indexBarRect.bottom - dp(2f))
        val ratio = ((clampedY - indexBarRect.top) / indexBarRect.height()).coerceIn(0f, 0.999f)
        val idx = (ratio * INDEX_ITEMS.size).toInt().coerceIn(0, INDEX_ITEMS.lastIndex)
        val found = INDEX_ITEMS[idx]
        if (found != selectedIndexItem) {
            selectedIndexItem = found
            hoveredAppIndex = -1
            cancelHoldTimer()
            refreshDisplayedApps()
            if (haptic) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    /**
     * Gestisce il trascinamento continuo dal bordo senza staccare il dito:
     * funziona su indice A-Z, sui pallini colore in basso, sulle app e sul menu tenuta!
     */
    fun handleExternalDrag(rawX: Float, rawY: Float, action: Int) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val localX = rawX - loc[0]
        val localY = rawY - loc[1]

        when (action) {
            MotionEvent.ACTION_MOVE -> {
                isContinuousGestureFromEdge = true
                processPointerMove(localX, localY)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isContinuousGestureFromEdge) {
                    if (tryExecuteReleaseAction(localX, localY)) return
                }
                cancelHoldTimer()
                isContinuousGestureFromEdge = false
                hoveredAppIndex = -1
                invalidate()
            }
        }
    }

    private fun processPointerMove(x: Float, y: Float) {
        // 1. Se il menu Pop-up / Metà schermo è visibile, controlla se il dito è su una delle 3 opzioni
        if (isWindowMenuVisible) {
            val optIdx = findWindowOptionAt(x, y)
            if (optIdx != hoveredWindowOptionIndex) {
                hoveredWindowOptionIndex = optIdx
                if (optIdx != -1) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            // Se il dito resta sull'app di origine o sulle opzioni, mantieni il menu aperto
            val stillOnOriginApp = windowMenuAppIndex in appItemRects.indices &&
                appItemRects[windowMenuAppIndex].second.contains(x, y)
            if (optIdx != -1 || stillOnOriginApp) {
                return
            }
            // Se il dito si sposta lontano verso l'indice o un'altra zona, chiudi il menu
            cancelHoldTimer()
        }

        // 2. Controlla se il dito passa sopra uno dei pallini colore in basso (senza staccare!)
        val colorHit = findColorDotAt(x, y)
        if (colorHit != null) {
            cancelHoldTimer()
            hoveredAppIndex = -1
            if (colorHit != activeColorFilter) {
                activeColorFilter = colorHit
                refreshDisplayedApps()
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            return
        }

        // 3. Controlla se il dito è nella fascia dell'indice verticale A-Z
        if (isInIndexZone(x)) {
            cancelHoldTimer()
            hoveredAppIndex = -1
            selectIndexByY(y, haptic = true)
            return
        }

        // 4. Controlla se il dito è sopra un'app
        val appIdx = findAppIndexAt(x, y)
        if (appIdx != hoveredAppIndex) {
            hoveredAppIndex = appIdx
            if (appIdx != -1) {
                lastAnimatedAppIndex = appIdx
                currentHoverScale = 1.02f
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                scheduleHoldTimer(appIdx, x, y)
            } else {
                cancelHoldTimer()
            }
        } else if (appIdx != -1) {
            // Se il dito si muove troppo velocemente sull'icona, riavvia il timer di tenuta
            if (abs(x - holdAnchorX) > dp(14f) || abs(y - holdAnchorY) > dp(14f)) {
                scheduleHoldTimer(appIdx, x, y)
            }
        }
    }

    private fun tryExecuteReleaseAction(x: Float, y: Float): Boolean {
        // Caso A: Menu Pop-up / Metà sopra / Metà sotto aperto
        if (isWindowMenuVisible && windowMenuAppIndex in displayedApps.indices) {
            val targetApp = displayedApps[windowMenuAppIndex]
            val optIdx = findWindowOptionAt(x, y)
            if (optIdx in WINDOW_OPTIONS.indices) {
                val chosenMode = WINDOW_OPTIONS[optIdx]
                cancelHoldTimer()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onLaunchApp(targetApp, chosenMode)
                return true
            }
            // Se rilascia ancora sopra l'app stessa -> avvio normale
            if (windowMenuAppIndex in appItemRects.indices &&
                appItemRects[windowMenuAppIndex].second.contains(x, y)
            ) {
                cancelHoldTimer()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onLaunchApp(targetApp, LaunchWindowMode.NORMAL)
                return true
            }
            cancelHoldTimer()
            return false
        }

        // Caso B: Rilascio diretto sopra un'app -> avvio normale
        val appIdx = findAppIndexAt(x, y)
        if (appIdx in displayedApps.indices) {
            val target = displayedApps[appIdx]
            cancelHoldTimer()
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onLaunchApp(target, LaunchWindowMode.NORMAL)
            return true
        }
        return false
    }

    private fun isInIndexZone(x: Float): Boolean {
        return if (prefs.isRightSide) {
            x >= indexBarRect.left - dp(10f)
        } else {
            x <= indexBarRect.right + dp(10f)
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

    private fun findColorDotAt(x: Float, y: Float): AppColorFilter? {
        val filters = AppColorFilter.entries
        for (i in filters.indices) {
            if (colorDotRects[i].contains(x, y)) {
                return filters[i]
            }
        }
        return null
    }

    private fun findWindowOptionAt(x: Float, y: Float): Int {
        for (i in windowOptionRects.indices) {
            if (windowOptionRects[i].contains(x, y)) {
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

                // 1. Tocco diretto su un pallino colore in basso
                val colorHit = findColorDotAt(x, y)
                if (colorHit != null) {
                    cancelHoldTimer()
                    activeColorFilter = colorHit
                    refreshDisplayedApps()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                }

                // 2. Tocco sull'indice A-Z
                if (indexBarRect.contains(x, y)) {
                    cancelHoldTimer()
                    selectIndexByY(y, haptic = true)
                    invalidate()
                    return true
                }

                // 3. Tocco su un'app
                val appIdx = findAppIndexAt(x, y)
                if (appIdx != -1) {
                    hoveredAppIndex = appIdx
                    lastAnimatedAppIndex = appIdx
                    currentHoverScale = 1.02f
                    scheduleHoldTimer(appIdx, x, y)
                    invalidate()
                    return true
                }

                // 4. Tocco fuori -> chiudi
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

                val colorHit = findColorDotAt(x, y)
                if (colorHit != null || isInIndexZone(x)) {
                    isScrollingAppList = false
                    processPointerMove(x, y)
                    invalidate()
                    return true
                }

                val dy = y - lastTouchY
                if (!isWindowMenuVisible && abs(dy) > dp(10f) && displayedApps.size > 6) {
                    isScrollingAppList = true
                    cancelHoldTimer()
                    hoveredAppIndex = -1
                    scrollOffsetY = (scrollOffsetY + dy).coerceIn(-dp(420f), 0f)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            computeStaticGeometry(w.toFloat(), h.toFloat())
        }
    }

    private fun computeStaticGeometry(w: Float, h: Float) {
        val scale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.35f)
        val isRight = prefs.isRightSide

        val edgeMargin = dp(8f)
        val indexWidth = dp(32f) * scale
        val appColWidth = dp(84f) * scale
        val topPadding = dp(48f)
        val bottomPadding = dp(32f)

        val indexLeft = if (isRight) w - edgeMargin - indexWidth else edgeMargin
        val indexRight = indexLeft + indexWidth
        indexBarRect.set(indexLeft, topPadding, indexRight, h - bottomPadding)

        val itemHeight = indexBarRect.height() / INDEX_ITEMS.size
        for (i in INDEX_ITEMS.indices) {
            val top = indexBarRect.top + i * itemHeight
            indexItemRects[i].set(indexBarRect.left, top, indexBarRect.right, top + itemHeight)
        }

        // Posiziona la griglia dei 7 pallini colore in basso nella colonna app
        val colLeft = if (isRight) indexLeft - dp(10f) - appColWidth else indexRight + dp(10f)
        val colCenterX = colLeft + appColWidth / 2f

        val dotRadius = dp(9f) * scale
        val dotSpacingX = dp(23f) * scale
        val dotSpacingY = dp(24f) * scale
        val bottomRowY = h - bottomPadding - dotRadius - dp(4f)
        val topRowY = bottomRowY - dotSpacingY

        // Fila superiore: 4 pallini (Tutti, Rosso, Giallo, Verde)
        // Fila inferiore: 3 pallini (Blu, Viola, Bianco/Nero)
        val hitPad = dp(4f)
        for (i in 0 until 4) {
            val cx = colCenterX + (i - 1.5f) * dotSpacingX
            colorDotRects[i].set(
                cx - dotRadius - hitPad,
                topRowY - dotRadius - hitPad,
                cx + dotRadius + hitPad,
                topRowY + dotRadius + hitPad
            )
        }
        for (i in 4 until AppColorFilter.entries.size) {
            val colIdx = i - 4
            val cx = colCenterX + (colIdx - 1.0f) * dotSpacingX
            colorDotRects[i].set(
                cx - dotRadius - hitPad,
                bottomRowY - dotRadius - hitPad,
                cx + dotRadius + hitPad,
                bottomRowY + dotRadius + hitPad
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Sfondo oscurato/blur
        canvas.drawRect(0f, 0f, w, h, dimBgPaint)

        val scale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.35f)
        val isRight = prefs.isRightSide
        val showLabels = prefs.showAppLabels

        // 2. Pillola indice verticale A-Z
        val pillRadius = indexBarRect.width() / 2f
        canvas.drawRoundRect(indexBarRect, pillRadius, pillRadius, panelGlassPaint)
        canvas.drawRoundRect(indexBarRect, pillRadius, pillRadius, panelBorderPaint)

        indexTextPaint.textSize = dp(11f) * scale
        val idxTextOffset = (indexTextPaint.descent() + indexTextPaint.ascent()) / 2f

        for (i in INDEX_ITEMS.indices) {
            val ch = INDEX_ITEMS[i]
            val rect = indexItemRects[i]
            val isSelected = (ch == selectedIndexItem)
            val hasApps = (ch == '★') || repository.hasAppsForLetter(ch, activeColorFilter)

            if (isSelected) {
                canvas.drawCircle(rect.centerX(), rect.centerY(), dp(12.5f) * scale, activePillPaint)
                indexTextPaint.color = Color.WHITE
            } else {
                indexTextPaint.color = if (hasApps) {
                    Color.argb(235, 255, 255, 255)
                } else {
                    Color.argb(65, 255, 255, 255)
                }
            }

            val label = if (ch == '★') "↓" else ch.toString()
            canvas.drawText(label, rect.centerX(), rect.centerY() - idxTextOffset, indexTextPaint)
        }

        // 3. Intestazione colonna app
        val appColWidth = dp(84f) * scale
        val colLeft = if (isRight) indexBarRect.left - dp(10f) - appColWidth else indexBarRect.right + dp(10f)
        val colRight = colLeft + appColWidth
        val colCenterX = (colLeft + colRight) / 2f

        headerLetterPaint.textSize = dp(19f) * scale
        val headerY = indexBarRect.top + dp(22f) * scale
        val headerTitle = if (selectedIndexItem == '★') "Recenti" else selectedIndexItem.toString()
        canvas.drawText(headerTitle, colCenterX, headerY, headerLetterPaint)

        // 4. Pallini colore diretti in basso (scorrimento continuo senza staccare il dito)
        val filters = AppColorFilter.entries
        val dotVisualRadius = dp(8.5f) * scale
        for (i in filters.indices) {
            val filter = filters[i]
            val r = colorDotRects[i]
            val isSelectedColor = (filter == activeColorFilter)
            val radius = if (isSelectedColor) dotVisualRadius * 1.18f else dotVisualRadius

            dotPaint.color = filter.swatchColor
            canvas.drawCircle(r.centerX(), r.centerY(), radius, dotPaint)

            if (isSelectedColor) {
                canvas.drawCircle(r.centerX(), r.centerY(), radius + dp(2.5f), dotRingPaint)
            }
        }

        // 5. Colonna verticale delle app (zoom fluido senza alone blu)
        val listTop = headerY + dp(14f) * scale
        val listBottom = colorDotRects[0].top - dp(12f) * scale
        appItemRects.clear()

        // Aggiorna animazione fluida dello zoom icona
        val targetScale = if (hoveredAppIndex != -1) 1.20f else 1.0f
        if (abs(currentHoverScale - targetScale) > 0.005f) {
            currentHoverScale += (targetScale - currentHoverScale) * 0.35f
            postInvalidateOnAnimation()
        } else {
            currentHoverScale = targetScale
        }

        canvas.save()
        canvas.clipRect(colLeft - dp(12f), listTop, colRight + dp(12f), listBottom)

        val slotHeight = (if (showLabels) dp(74f) else dp(58f)) * scale
        val baseIconSize = dp(46f) * scale

        if (displayedApps.isEmpty()) {
            appLabelPaint.textSize = dp(11f) * scale
            appLabelPaint.color = Color.argb(160, 255, 255, 255)
            canvas.drawText("Nessuna app", colCenterX, listTop + dp(36f), appLabelPaint)
            appLabelPaint.color = Color.WHITE
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

                    val iconLeft = colCenterX - iconSize / 2f
                    val iconTop = itemTop + dp(5f) * scale - (iconSize - baseIconSize) / 2f
                    tempRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

                    canvas.drawBitmap(app.iconBitmap, null, tempRect, bitmapPaint)

                    if (showLabels) {
                        canvas.drawText(
                            app.shortLabel,
                            colCenterX,
                            itemTop + dp(64f) * scale,
                            appLabelPaint
                        )
                    }
                }
            }
        }
        canvas.restore()

        // 6. Menu rapido Pop-up / Metà sopra / Metà sotto (quando tieni il dito sull'app)
        if (isWindowMenuVisible && windowMenuAppIndex in appItemRects.indices) {
            drawWindowModeMenu(canvas, appItemRects[windowMenuAppIndex].second, isRight, scale)
        }
    }

    private fun drawWindowModeMenu(
        canvas: Canvas,
        anchorAppRect: RectF,
        isRight: Boolean,
        scale: Float
    ) {
        val btnWidth = dp(112f) * scale
        val btnHeight = dp(38f) * scale
        val gap = dp(8f) * scale
        val totalHeight = WINDOW_OPTIONS.size * btnHeight + (WINDOW_OPTIONS.size - 1) * gap

        val menuLeft = if (isRight) {
            anchorAppRect.left - btnWidth - dp(12f) * scale
        } else {
            anchorAppRect.right + dp(12f) * scale
        }
        val startTop = (anchorAppRect.centerY() - totalHeight / 2f)
            .coerceIn(dp(48f), height - totalHeight - dp(36f))

        menuTextPaint.textSize = dp(12f) * scale
        val textOffset = (menuTextPaint.descent() + menuTextPaint.ascent()) / 2f

        for (i in WINDOW_OPTIONS.indices) {
            val top = startTop + i * (btnHeight + gap)
            val rect = windowOptionRects[i]
            rect.set(menuLeft, top, menuLeft + btnWidth, top + btnHeight)

            val isSelected = (i == hoveredWindowOptionIndex)
            val paint = if (isSelected) menuHighlightPaint else menuBgPaint
            canvas.drawRoundRect(rect, dp(14f), dp(14f), paint)
            canvas.drawRoundRect(rect, dp(14f), dp(14f), panelBorderPaint)

            canvas.drawText(
                WINDOW_OPTIONS[i].title,
                rect.centerX(),
                rect.centerY() - textOffset,
                menuTextPaint
            )
        }
    }
}
