package com.edgedrawer.app

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class EdgeDrawerView(
    context: Context,
    private val repository: AppRepository,
    private val prefs: PrefsManager,
    private val onLaunchApp: (AppEntry) -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {

    companion object {
        // '★' rappresenta il cassetto delle app installate di recente prima della 'A'
        private val INDEX_ITEMS: List<Char> = listOf('★') + ('A'..'Z').toList()
    }

    private var selectedIndexItem: Char = '★'
    private var activeColorFilter: AppColorFilter = AppColorFilter.ALL
    private var isColorFolderOpen: Boolean = false
    private var displayedApps: List<AppEntry> = emptyList()
    private var hoveredAppIndex: Int = -1
    private var scrollOffsetY: Float = 0f

    // Rettangoli per hit-testing rapido
    private val indexBarRect = RectF()
    private val indexItemRects = mutableListOf<Pair<Char, RectF>>()
    private val appItemRects = mutableListOf<Pair<AppEntry, RectF>>()
    private val colorButtonRect = RectF()
    private val colorFolderRect = RectF()
    private val colorOptionRects = mutableListOf<Pair<AppColorFilter, RectF>>()

    // Stato touch per supportare sia "trascina e rilascia" sia "alza il dito e clicca"
    private var isContinuousGestureFromEdge = false
    private var lastTouchY = 0f
    private var lastTouchX = 0f
    private var isScrollingAppList = false

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    // Paint objects
    private val dimBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 11, 13, 19)
    }

    private val panelGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 22, 26, 36)
    }

    private val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(55, 255, 255, 255)
    }

    private val indexTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(11.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D94FF")
    }

    private val headerLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(20f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val appLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(200, 0, 0, 0))
    }

    private val hoverHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 77, 148, 255)
    }

    private val folderBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(242, 28, 33, 46)
    }

    private val folderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun prepareOpen(fromContinuousSwipe: Boolean, initialY: Float) {
        isContinuousGestureFromEdge = fromContinuousSwipe
        isColorFolderOpen = false
        hoveredAppIndex = -1
        scrollOffsetY = 0f
        if (width > 0 && height > 0) {
            selectIndexByY(initialY)
        } else {
            selectedIndexItem = '★'
            refreshDisplayedApps()
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

    private fun selectIndexByY(y: Float) {
        if (indexItemRects.isEmpty()) return
        val clampedY = y.coerceIn(indexBarRect.top + dp(4f), indexBarRect.bottom - dp(4f))
        val found = indexItemRects.minByOrNull { abs(it.second.centerY() - clampedY) }?.first
        if (found != null && found != selectedIndexItem) {
            selectedIndexItem = found
            hoveredAppIndex = -1
            refreshDisplayedApps()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * Gestisce il trascinamento continuo iniziato dal bordo dello schermo prima ancora
     * che l'utente stacchi il dito.
     */
    fun handleExternalDrag(rawX: Float, rawY: Float, action: Int) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val localX = rawX - loc[0]
        val localY = rawY - loc[1]

        when (action) {
            MotionEvent.ACTION_MOVE -> {
                isContinuousGestureFromEdge = true
                // Se il dito è sopra l'indice verticale, cambia lettera
                if (isInIndexZone(localX)) {
                    hoveredAppIndex = -1
                    selectIndexByY(localY)
                } else {
                    // Se il dito si sposta nella colonna app, evidenzia l'app sotto il dito
                    val appIdx = findAppIndexAt(localX, localY)
                    if (appIdx != hoveredAppIndex) {
                        hoveredAppIndex = appIdx
                        if (appIdx != -1) {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isContinuousGestureFromEdge) {
                    val appIdx = findAppIndexAt(localX, localY)
                    if (appIdx in displayedApps.indices) {
                        val target = displayedApps[appIdx]
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onLaunchApp(target)
                        return
                    }
                }
                // Se l'utente alza il dito senza essere sopra un'app, il pannello resta aperto!
                isContinuousGestureFromEdge = false
                hoveredAppIndex = -1
                invalidate()
            }
        }
    }

    private fun isInIndexZone(x: Float): Boolean {
        return if (prefs.isRightSide) {
            x >= indexBarRect.left - dp(12f)
        } else {
            x <= indexBarRect.right + dp(12f)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                isScrollingAppList = false

                // 1. Se la cartella colori è aperta, controlla clic su un colore o chiusura cartella
                if (isColorFolderOpen) {
                    val chosenColor = colorOptionRects.firstOrNull { it.second.contains(x, y) }?.first
                    if (chosenColor != null) {
                        activeColorFilter = chosenColor
                        isColorFolderOpen = false
                        refreshDisplayedApps()
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        invalidate()
                        return true
                    } else if (!colorFolderRect.contains(x, y)) {
                        isColorFolderOpen = false
                        invalidate()
                        return true
                    }
                }

                // 2. Clic sul pulsante cartella colori in basso
                if (colorButtonRect.contains(x, y)) {
                    isColorFolderOpen = !isColorFolderOpen
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                }

                // 3. Tocco sull'indice A-Z / Recenti
                if (indexBarRect.contains(x, y)) {
                    isColorFolderOpen = false
                    selectIndexByY(y)
                    invalidate()
                    return true
                }

                // 4. Tocco su un'app nella colonna verticale
                val appIdx = findAppIndexAt(x, y)
                if (appIdx != -1) {
                    hoveredAppIndex = appIdx
                    invalidate()
                    return true
                }

                // 5. Tocco sullo sfondo esterno -> chiudi pannello
                onDismiss()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (indexBarRect.contains(x, y) || isInIndexZone(x)) {
                    hoveredAppIndex = -1
                    selectIndexByY(y)
                    invalidate()
                    return true
                }

                val dy = y - lastTouchY
                if (abs(dy) > dp(8f) && displayedApps.size > 6) {
                    isScrollingAppList = true
                    hoveredAppIndex = -1
                    scrollOffsetY = (scrollOffsetY + dy).coerceIn(-dp(400f), 0f)
                    lastTouchY = y
                    invalidate()
                    return true
                }

                val appIdx = findAppIndexAt(x, y)
                if (appIdx != hoveredAppIndex) {
                    hoveredAppIndex = appIdx
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isScrollingAppList && hoveredAppIndex in displayedApps.indices) {
                    val target = displayedApps[hoveredAppIndex]
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onLaunchApp(target)
                    return true
                }
                hoveredAppIndex = -1
                isScrollingAppList = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Sfondo oscurato/blur
        canvas.drawRect(0f, 0f, w, h, dimBgPaint)

        val isRight = prefs.isRightSide
        val edgeMargin = dp(8f)
        val indexWidth = dp(34f)
        val appColWidth = dp(86f)
        val topPadding = dp(52f)
        val bottomPadding = dp(36f)

        // Posizionamento indice verticale (Destra o Sinistra)
        val indexLeft = if (isRight) w - edgeMargin - indexWidth else edgeMargin
        val indexRight = indexLeft + indexWidth
        indexBarRect.set(indexLeft, topPadding, indexRight, h - bottomPadding)

        // Disegna pillola indice A-Z
        canvas.drawRoundRect(indexBarRect, dp(17f), dp(17f), panelGlassPaint)
        canvas.drawRoundRect(indexBarRect, dp(17f), dp(17f), panelBorderPaint)

        indexItemRects.clear()
        val itemHeight = indexBarRect.height() / INDEX_ITEMS.size
        INDEX_ITEMS.forEachIndexed { idx, ch ->
            val top = indexBarRect.top + idx * itemHeight
            val bottom = top + itemHeight
            val rect = RectF(indexBarRect.left, top, indexBarRect.right, bottom)
            indexItemRects.add(ch to rect)

            val isSelected = (ch == selectedIndexItem)
            val hasApps = if (ch == '★') true else repository.hasAppsForLetter(ch, activeColorFilter)

            if (isSelected) {
                canvas.drawCircle(rect.centerX(), rect.centerY(), dp(13f), activePillPaint)
                indexTextPaint.color = Color.WHITE
            } else {
                indexTextPaint.color = if (hasApps) {
                    Color.argb(235, 255, 255, 255)
                } else {
                    Color.argb(65, 255, 255, 255)
                }
            }

            val label = if (ch == '★') "↓" else ch.toString()
            val textY = rect.centerY() - (indexTextPaint.descent() + indexTextPaint.ascent()) / 2f
            canvas.drawText(label, rect.centerX(), textY, indexTextPaint)
        }

        // Colonna verticale delle app accanto all'indice
        val colLeft = if (isRight) indexLeft - dp(10f) - appColWidth else indexRight + dp(10f)
        val colRight = colLeft + appColWidth
        val colCenterX = (colLeft + colRight) / 2f

        // Intestazione sezione (es. "Recenti" o lettera "S")
        val headerY = topPadding + dp(24f)
        headerLetterPaint.textAlign = Paint.Align.CENTER
        val headerTitle = if (selectedIndexItem == '★') "Recenti" else selectedIndexItem.toString()
        canvas.drawText(headerTitle, colCenterX, headerY, headerLetterPaint)

        // Pulsante cartella colori in basso
        val colorBtnSize = dp(46f)
        val colorBtnCenterY = h - bottomPadding - colorBtnSize / 2f
        colorButtonRect.set(
            colCenterX - colorBtnSize / 2f,
            colorBtnCenterY - colorBtnSize / 2f,
            colCenterX + colorBtnSize / 2f,
            colorBtnCenterY + colorBtnSize / 2f
        )
        drawColorFolderButton(canvas, colorButtonRect)

        // Area verticale disponibile per le icone delle app
        val listTop = headerY + dp(16f)
        val listBottom = colorButtonRect.top - dp(14f)
        appItemRects.clear()

        canvas.save()
        canvas.clipRect(colLeft - dp(8f), listTop, colRight + dp(8f), listBottom)

        val slotHeight = dp(76f)
        val iconSize = dp(46f)

        if (displayedApps.isEmpty()) {
            appLabelPaint.color = Color.argb(160, 255, 255, 255)
            canvas.drawText("Nessuna app", colCenterX, listTop + dp(40f), appLabelPaint)
            appLabelPaint.color = Color.WHITE
        } else {
            displayedApps.forEachIndexed { i, app ->
                val itemTop = listTop + i * slotHeight + scrollOffsetY
                val itemBottom = itemTop + slotHeight
                if (itemBottom >= listTop && itemTop <= listBottom) {
                    val itemRect = RectF(colLeft, itemTop, colRight, itemBottom)
                    appItemRects.add(app to itemRect)

                    val isHovered = (i == hoveredAppIndex)
                    if (isHovered) {
                        canvas.drawRoundRect(itemRect, dp(16f), dp(16f), hoverHighlightPaint)
                    }

                    val scale = if (isHovered) 1.16f else 1.0f
                    val scaledIcon = iconSize * scale
                    val iconLeft = colCenterX - scaledIcon / 2f
                    val iconTop = itemTop + dp(6f) - (scaledIcon - iconSize) / 2f
                    val dstRect = RectF(iconLeft, iconTop, iconLeft + scaledIcon, iconTop + scaledIcon)

                    canvas.drawBitmap(app.iconBitmap, null, dstRect, null)

                    val shortName = if (app.label.length > 11) {
                        app.label.take(10) + "…"
                    } else {
                        app.label
                    }
                    canvas.drawText(shortName, colCenterX, itemTop + dp(66f), appLabelPaint)
                }
            }
        }
        canvas.restore()

        // Disegna la cartella colori aperta se attiva
        if (isColorFolderOpen) {
            drawColorFolderPopup(canvas, colLeft, colRight, isRight)
        }
    }

    private fun drawColorFolderButton(canvas: Canvas, rect: RectF) {
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, panelGlassPaint)
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, panelBorderPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (activeColorFilter != AppColorFilter.ALL) {
            // Mostra il colore attivo selezionato
            dotPaint.color = activeColorFilter.swatchColor
            canvas.drawCircle(rect.centerX(), rect.centerY(), dp(12f), dotPaint)
        } else {
            // Disegna mini-griglia di pallini colorati stile cartella
            val colors = listOf(
                AppColorFilter.RED.swatchColor,
                AppColorFilter.YELLOW.swatchColor,
                AppColorFilter.GREEN.swatchColor,
                AppColorFilter.BLUE.swatchColor,
                AppColorFilter.PURPLE.swatchColor,
                AppColorFilter.MONO.swatchColor
            )
            val offsets = listOf(
                -dp(7f) to -dp(5f),
                0f to -dp(5f),
                dp(7f) to -dp(5f),
                -dp(7f) to dp(5f),
                0f to dp(5f),
                dp(7f) to dp(5f)
            )
            colors.forEachIndexed { idx, col ->
                dotPaint.color = col
                val (ox, oy) = offsets[idx]
                canvas.drawCircle(rect.centerX() + ox, rect.centerY() + oy, dp(2.8f), dotPaint)
            }
        }
    }

    private fun drawColorFolderPopup(
        canvas: Canvas,
        colLeft: Float,
        colRight: Float,
        isRight: Boolean
    ) {
        val filters = AppColorFilter.values()
        val popupWidth = dp(156f)
        val rowHeight = dp(38f)
        val popupHeight = filters.size * rowHeight + dp(16f)

        val popupLeft = if (isRight) colLeft - popupWidth - dp(8f) else colRight + dp(8f)
        val popupBottom = colorButtonRect.bottom
        val popupTop = popupBottom - popupHeight

        colorFolderRect.set(popupLeft, popupTop, popupLeft + popupWidth, popupBottom)
        canvas.drawRoundRect(colorFolderRect, dp(18f), dp(18f), folderBgPaint)
        canvas.drawRoundRect(colorFolderRect, dp(18f), dp(18f), panelBorderPaint)

        colorOptionRects.clear()
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        filters.forEachIndexed { idx, filter ->
            val rTop = popupTop + dp(8f) + idx * rowHeight
            val rBottom = rTop + rowHeight
            val rowRect = RectF(popupLeft + dp(6f), rTop, popupLeft + popupWidth - dp(6f), rBottom)
            colorOptionRects.add(filter to rowRect)

            if (filter == activeColorFilter) {
                canvas.drawRoundRect(rowRect, dp(10f), dp(10f), hoverHighlightPaint)
            }

            dotPaint.color = filter.swatchColor
            canvas.drawCircle(rowRect.left + dp(16f), rowRect.centerY(), dp(7f), dotPaint)

            val count = repository.countAppsForColor(filter)
            val textY = rowRect.centerY() - (folderTextPaint.descent() + folderTextPaint.ascent()) / 2f
            canvas.drawText("${filter.label} ($count)", rowRect.left + dp(32f), textY, folderTextPaint)
        }
    }
}
