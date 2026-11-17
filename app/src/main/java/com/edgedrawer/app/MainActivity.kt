package com.edgedrawer.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var switchEnable: MaterialSwitch
    private lateinit var btnPermission: MaterialButton
    private lateinit var btnSplitSupport: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)
        buildOneUIScreen()
    }

    private fun buildOneUIScreen() {
        val isNight = prefs.isNightThemeActive()
        val accent = prefs.getAccentColor()

        val bgMain = if (isNight) Color.parseColor("#0B0D12") else Color.parseColor("#F2F4F8")
        val cardBg = if (isNight) Color.parseColor("#171B24") else Color.WHITE
        val textPrimary = if (isNight) Color.WHITE else Color.parseColor("#11141A")
        val textSecondary = if (isNight) Color.parseColor("#949EB0") else Color.parseColor("#5F6878")

        window.statusBarColor = bgMain
        window.navigationBarColor = bgMain

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = ScrollView(this).apply {
            setBackgroundColor(bgMain)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(36))
        }

        val title = TextView(this).apply {
            text = "EdgeDrawer"
            textSize = 30f
            setTextColor(textPrimary)
            setTypeface(null, Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Colori sopra la A, indice A–Z, recenti sotto la Z e finestre rapide"
            textSize = 13.5f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(18))
        }

        // --- CARD 1: ATTIVAZIONE & PERMESSI ---
        val cardMain = createCard(cardBg, dp(16))
        val mainLayout = createCardInner(dp(18))

        btnPermission = MaterialButton(this).apply {
            text = "1. Permesso 'Mostra sopra altre app'"
            backgroundTintList = ColorStateList.valueOf(accent)
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        switchEnable = MaterialSwitch(this).apply {
            text = "2. Attiva indice verticale sul bordo"
            setTextColor(textPrimary)
            textSize = 15.5f
            setPadding(0, dp(10), 0, dp(6))
            isChecked = prefs.isServiceEnabled
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && !Settings.canDrawOverlays(this@MainActivity)) {
                    this.isChecked = false
                    Toast.makeText(
                        this@MainActivity,
                        "Concedi prima il permesso 'Mostra sopra altre app'",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnCheckedChangeListener
                }
                prefs.isServiceEnabled = isChecked
                val serviceIntent = Intent(this@MainActivity, EdgeOverlayService::class.java)
                if (isChecked) {
                    ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                } else {
                    stopService(serviceIntent)
                }
            }
        }

        btnSplitSupport = MaterialButton(this).apply {
            text = "3. Abilita Schermo Diviso (Metà sopra/sotto)"
            backgroundTintList = ColorStateList.valueOf(
                if (isNight) Color.parseColor("#252B3B") else Color.parseColor("#E6EAF2")
            )
            setTextColor(textPrimary)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnTest = MaterialButton(this).apply {
            text = "Anteprima pannello adesso"
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(accent)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Attiva prima il permesso", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.isServiceEnabled = true
                switchEnable.isChecked = true
                val intent = Intent(this@MainActivity, EdgeOverlayService::class.java).apply {
                    action = EdgeOverlayService.ACTION_OPEN_DRAWER
                }
                ContextCompat.startForegroundService(this@MainActivity, intent)
            }
        }

        mainLayout.addView(btnPermission)
        mainLayout.addView(switchEnable)
        mainLayout.addView(btnSplitSupport)
        mainLayout.addView(btnTest)
        cardMain.addView(mainLayout)

        // --- CARD 2: TEMA ONE UI ---
        val cardTheme = createCard(cardBg, dp(16))
        val themeLayout = createCardInner(dp(18))
        themeLayout.addView(createSectionHeader("Aspetto & Tema One UI", accent, dp(10)))

        val themeModeLabel = TextView(this).apply {
            text = when (prefs.themeMode) {
                1 -> "Modalità tema: Giorno"
                2 -> "Modalità tema: Notte"
                else -> "Modalità tema: Automatico (Sistema)"
            }
            setTextColor(textPrimary)
            textSize = 14.5f
            setPadding(0, 0, 0, dp(8))
        }
        themeLayout.addView(themeModeLabel)

        val themeButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val modes = listOf("Auto" to 0, "Giorno" to 1, "Notte" to 2)
        modes.forEach { (label, modeVal) ->
            val btn = MaterialButton(this).apply {
                text = label
                textSize = 12.5f
                val active = prefs.themeMode == modeVal
                backgroundTintList = ColorStateList.valueOf(
                    if (active) accent else (if (isNight) Color.parseColor("#252B3B") else Color.parseColor("#E6EAF2"))
                )
                setTextColor(if (active) Color.WHITE else textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    prefs.themeMode = modeVal
                    notifyServiceReload()
                    buildOneUIScreen()
                }
            }
            themeButtonsRow.addView(btn)
        }
        themeLayout.addView(themeButtonsRow)

        val accentLabel = TextView(this).apply {
            text = "Colore accento"
            setTextColor(textPrimary)
            textSize = 14.5f
            setPadding(0, dp(14), 0, dp(8))
        }
        themeLayout.addView(accentLabel)

        val accentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        OneUIAccent.entries.forEachIndexed { idx, item ->
            val circle = View(this).apply {
                val size = dp(32)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(10)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(item.colorInt)
                    if (prefs.accentIndex == idx) {
                        setStroke(dp(3), textPrimary)
                    }
                }
                setOnClickListener {
                    prefs.accentIndex = idx
                    notifyServiceReload()
                    buildOneUIScreen()
                }
            }
            accentRow.addView(circle)
        }
        themeLayout.addView(accentRow)
        cardTheme.addView(themeLayout)

        // --- CARD 3: TRIGGER LATERALE & POSIZIONE VERTICALE ---
        val cardTrigger = createCard(cardBg, dp(16))
        val triggerLayout = createCardInner(dp(18))
        triggerLayout.addView(createSectionHeader("Trigger Laterale & Posizionamento", accent, dp(10)))

        val sideSwitch = MaterialSwitch(this).apply {
            text = if (prefs.isRightSide) "Lato schermo: Destra" else "Lato schermo: Sinistra"
            setTextColor(textPrimary)
            isChecked = prefs.isRightSide
            setOnCheckedChangeListener { _, isRight ->
                prefs.isRightSide = isRight
                text = if (isRight) "Lato schermo: Destra" else "Lato schermo: Sinistra"
                notifyServiceReload()
            }
        }

        val triggerColorSwitch = MaterialSwitch(this).apply {
            text = if (prefs.isTriggerTextBlack) "Colore lettere trigger: Nero" else "Colore lettere trigger: Bianco"
            setTextColor(textPrimary)
            setPadding(0, dp(6), 0, 0)
            isChecked = prefs.isTriggerTextBlack
            setOnCheckedChangeListener { _, isBlack ->
                prefs.isTriggerTextBlack = isBlack
                text = if (isBlack) "Colore lettere trigger: Nero" else "Colore lettere trigger: Bianco"
                notifyServiceReload()
            }
        }

        val hideTriggerSwitch = MaterialSwitch(this).apply {
            text = "Nascondi lettere trigger (solo scorrimento invisibile)"
            setTextColor(textPrimary)
            setPadding(0, dp(6), 0, 0)
            isChecked = prefs.isTriggerHidden
            setOnCheckedChangeListener { _, hidden ->
                prefs.isTriggerHidden = hidden
                notifyServiceReload()
            }
        }

        triggerLayout.addView(sideSwitch)
        triggerLayout.addView(triggerColorSwitch)
        triggerLayout.addView(hideTriggerSwitch)

        addSlider(
            parent = triggerLayout,
            titlePrefix = "Altezza indice (Trigger e Pannello)",
            unit = "%",
            min = 30,
            max = 90,
            step = 1,
            initial = prefs.barHeightPercent,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.barHeightPercent = it },
            onStop = { notifyServiceReload() }
        )

        addSlider(
            parent = triggerLayout,
            titlePrefix = "Posizione verticale (Alto ↔ In fondo)",
            unit = "%",
            min = 0,
            max = 100,
            step = 1,
            initial = prefs.verticalPositionPercent,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.verticalPositionPercent = it },
            onStop = { notifyServiceReload() }
        )

        addSlider(
            parent = triggerLayout,
            titlePrefix = "Larghezza area tocco bordo",
            unit = " dp",
            min = 18,
            max = 46,
            step = 1,
            initial = prefs.triggerWidthDp,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.triggerWidthDp = it },
            onStop = { notifyServiceReload() }
        )

        addSlider(
            parent = triggerLayout,
            titlePrefix = "Opacità lettere trigger",
            unit = "%",
            min = 0,
            max = 100,
            step = 1,
            initial = prefs.triggerAlphaPercent,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.triggerAlphaPercent = it },
            onStop = { notifyServiceReload() }
        )

        cardTrigger.addView(triggerLayout)

        // --- CARD 4: TRASPARENZA, BLUR E ANIMAZIONE NIAGARA ---
        val cardVisual = createCard(cardBg, dp(16))
        val visualLayout = createCardInner(dp(18))
        visualLayout.addView(createSectionHeader("Trasparenza, Blur & Animazioni", accent, dp(10)))

        addSlider(
            parent = visualLayout,
            titlePrefix = "Sfocatura sfondo (Blur)",
            unit = "",
            min = 0,
            max = 120,
            step = 1,
            initial = prefs.blurRadius,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(8),
            onChanged = { prefs.blurRadius = it },
            onStop = {}
        )

        addSlider(
            parent = visualLayout,
            titlePrefix = "Opacità sfondo pannello",
            unit = "%",
            min = 0,
            max = 100,
            step = 1,
            initial = prefs.panelBgAlphaPercent,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.panelBgAlphaPercent = it },
            onStop = {}
        )

        addSlider(
            parent = visualLayout,
            titlePrefix = "Opacità barra alfabetica pannello",
            unit = "%",
            min = 0,
            max = 100,
            step = 1,
            initial = prefs.indexBarAlphaPercent,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.indexBarAlphaPercent = it },
            onStop = {}
        )

        addSlider(
            parent = visualLayout,
            titlePrefix = "Forza animazione onda (stile Niagara)",
            unit = "%",
            min = 0,
            max = 140,
            step = 1,
            initial = prefs.waveStrengthPercent,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.waveStrengthPercent = it },
            onStop = {}
        )

        cardVisual.addView(visualLayout)

        // --- CARD 5: DISTANZE & DIMENSIONI APP ---
        val cardSpacing = createCard(cardBg, dp(16))
        val spacingLayout = createCardInner(dp(18))
        spacingLayout.addView(createSectionHeader("Dimensioni, Distanze & App Recenti", accent, dp(10)))

        val labelsSwitch = MaterialSwitch(this).apply {
            text = "Mostra etichette nomi app"
            setTextColor(textPrimary)
            isChecked = prefs.showAppLabels
            setOnCheckedChangeListener { _, show ->
                prefs.showAppLabels = show
            }
        }
        spacingLayout.addView(labelsSwitch)

        addSlider(
            parent = spacingLayout,
            titlePrefix = "Numero app recenti (sotto la Z)",
            unit = " app",
            min = 10,
            max = 100,
            step = 10,
            initial = prefs.recentCount,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.recentCount = it },
            onStop = {}
        )

        addSlider(
            parent = spacingLayout,
            titlePrefix = "Scala generale pannello e icone",
            unit = "%",
            min = 70,
            max = 130,
            step = 1,
            initial = prefs.panelScalePercent,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.panelScalePercent = it },
            onStop = { notifyServiceReload() }
        )

        addSlider(
            parent = spacingLayout,
            titlePrefix = "Distanza verticale tra le app",
            unit = " dp",
            min = 2,
            max = 32,
            step = 1,
            initial = prefs.appSpacingDp,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.appSpacingDp = it },
            onStop = {}
        )

        addSlider(
            parent = spacingLayout,
            titlePrefix = "Distanza etichetta dall'icona",
            unit = " dp",
            min = 0,
            max = 18,
            step = 1,
            initial = prefs.labelDistanceDp,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.labelDistanceDp = it },
            onStop = {}
        )

        addSlider(
            parent = spacingLayout,
            titlePrefix = "Distanza delle app dall'indice A–Z",
            unit = " dp",
            min = 4,
            max = 44,
            step = 1,
            initial = prefs.appsFromIndexDp,
            textColor = textPrimary,
            accentColor = accent,
            dp12 = dp(12),
            onChanged = { prefs.appsFromIndexDp = it },
            onStop = {}
        )

        cardSpacing.addView(spacingLayout)

        container.addView(title)
        container.addView(subtitle)
        container.addView(cardMain)
        container.addView(cardTheme)
        container.addView(cardTrigger)
        container.addView(cardVisual)
        container.addView(cardSpacing)
        root.addView(container)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        btnPermission.text = if (hasOverlay) {
            "1. Permesso 'Mostra sopra altre app' OK ✓"
        } else {
            "1. Permesso 'Mostra sopra altre app'"
        }

        val hasSplit = EdgeAccessibilityService.instance != null
        btnSplitSupport.text = if (hasSplit) {
            "3. Schermo Diviso (Metà sopra/sotto) OK ✓"
        } else {
            "3. Abilita Schermo Diviso (Metà sopra/sotto)"
        }

        if (hasOverlay && prefs.isServiceEnabled) {
            ContextCompat.startForegroundService(this, Intent(this, EdgeOverlayService::class.java))
        }
    }

    private fun notifyServiceReload() {
        if (prefs.isServiceEnabled && Settings.canDrawOverlays(this)) {
            val intent = Intent(this, EdgeOverlayService::class.java).apply {
                action = EdgeOverlayService.ACTION_RELOAD_CONFIG
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun createCard(bgColor: Int, marginBottomPx: Int): MaterialCardView {
        return MaterialCardView(this).apply {
            setCardBackgroundColor(bgColor)
            radius = 52f
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = marginBottomPx
            }
        }
    }

    private fun createCardInner(padPx: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx, padPx, padPx)
        }
    }

    private fun createSectionHeader(textStr: String, color: Int, bottomPad: Int): TextView {
        return TextView(this).apply {
            text = textStr
            textSize = 16.5f
            setTextColor(color)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, bottomPad)
        }
    }

    private fun addSlider(
        parent: LinearLayout,
        titlePrefix: String,
        unit: String,
        min: Int,
        max: Int,
        step: Int,
        initial: Int,
        textColor: Int,
        accentColor: Int,
        dp12: Int,
        onChanged: (Int) -> Unit,
        onStop: () -> Unit
    ) {
        val stepsCount = (max - min) / step
        val label = TextView(this).apply {
            text = "$titlePrefix: $initial$unit"
            setTextColor(textColor)
            textSize = 14f
            setPadding(0, dp12, 0, 4)
        }
        val seek = SeekBar(this).apply {
            this.max = stepsCount
            this.progress = ((initial - min) / step).coerceIn(0, stepsCount)
            thumbTintList = ColorStateList.valueOf(accentColor)
            progressTintList = ColorStateList.valueOf(accentColor)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val realVal = min + value * step
                    label.text = "$titlePrefix: $realVal$unit"
                    onChanged(realVal)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    onStop()
                }
            })
        }
        parent.addView(label)
        parent.addView(seek)
    }
}
