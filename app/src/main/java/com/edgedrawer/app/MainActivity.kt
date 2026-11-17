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

        val root = ScrollView(this).apply { setBackgroundColor(bgMain) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(36))
        }

        container.addView(TextView(this).apply {
            text = "EdgeDrawer"
            textSize = 30f
            setTextColor(textPrimary)
            setTypeface(null, Typeface.BOLD)
        })

        container.addView(TextView(this).apply {
            text = "Colori sopra la A, indice A–Z, recenti sotto la Z e finestre rapide"
            textSize = 13.5f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(18))
        })

        // --- CARD 1: ATTIVAZIONE & PERMESSI ---
        val cardMain = createCard(cardBg, dp(16))
        val mainLayout = createCardInner(dp(18))

        btnPermission = MaterialButton(this).apply {
            text = "1. Permesso 'Mostra sopra altre app'"
            backgroundTintList = ColorStateList.valueOf(accent)
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
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
                    Toast.makeText(this@MainActivity, "Concedi prima il permesso 'Mostra sopra altre app'", Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                prefs.isServiceEnabled = isChecked
                val serviceIntent = Intent(this@MainActivity, EdgeOverlayService::class.java)
                if (isChecked) ContextCompat.startForegroundService(this@MainActivity, serviceIntent) else stopService(serviceIntent)
            }
        }

        btnSplitSupport = MaterialButton(this).apply {
            text = "3. Abilita Schermo Diviso (Metà sopra/sotto)"
            backgroundTintList = ColorStateList.valueOf(if (isNight) Color.parseColor("#252B3B") else Color.parseColor("#E6EAF2"))
            setTextColor(textPrimary)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
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
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, EdgeOverlayService::class.java).apply { action = EdgeOverlayService.ACTION_OPEN_DRAWER }
                )
            }
        }

        mainLayout.addView(btnPermission)
        mainLayout.addView(switchEnable)
        mainLayout.addView(btnSplitSupport)
        mainLayout.addView(btnTest)
        cardMain.addView(mainLayout)
        container.addView(cardMain)

        // --- CARD 2: TEMA ONE UI ---
        val cardTheme = createCard(cardBg, dp(16))
        val themeLayout = createCardInner(dp(18))
        themeLayout.addView(createSectionHeader("Aspetto & Tema One UI", accent, dp(10)))

        themeLayout.addView(TextView(this).apply {
            text = when (prefs.themeMode) {
                1 -> "Modalità tema: Giorno"
                2 -> "Modalità tema: Notte"
                else -> "Modalità tema: Automatico (Sistema)"
            }
            setTextColor(textPrimary)
            textSize = 14.5f
            setPadding(0, 0, 0, dp(8))
        })

        val themeButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("Auto" to 0, "Giorno" to 1, "Notte" to 2).forEach { (label, modeVal) ->
            themeButtonsRow.addView(MaterialButton(this).apply {
                text = label
                textSize = 12.5f
                val active = prefs.themeMode == modeVal
                backgroundTintList = ColorStateList.valueOf(
                    if (active) accent else (if (isNight) Color.parseColor("#252B3B") else Color.parseColor("#E6EAF2"))
                )
                setTextColor(if (active) Color.WHITE else textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                setOnClickListener {
                    prefs.themeMode = modeVal
                    notifyServiceReload()
                    buildOneUIScreen()
                }
            })
        }
        themeLayout.addView(themeButtonsRow)

        themeLayout.addView(TextView(this).apply {
            text = "Colore accento"
            setTextColor(textPrimary)
            textSize = 14.5f
            setPadding(0, dp(14), 0, dp(8))
        })

        val accentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        OneUIAccent.entries.forEachIndexed { idx, item ->
            accentRow.addView(View(this).apply {
                val size = dp(32)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(10) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(item.colorInt)
                    if (prefs.accentIndex == idx) setStroke(dp(3), textPrimary)
                }
                setOnClickListener {
                    prefs.accentIndex = idx
                    notifyServiceReload()
                    buildOneUIScreen()
                }
            })
        }
        themeLayout.addView(accentRow)
        cardTheme.addView(themeLayout)
        container.addView(cardTheme)

        // --- CARD 3: TRIGGER LATERALE & SPAZIATURE INDICE ---
        val cardTrigger = createCard(cardBg, dp(16))
        val triggerLayout = createCardInner(dp(18))
        triggerLayout.addView(createSectionHeader("Trigger Laterale & Spaziature Indice", accent, dp(10)))

        triggerLayout.addView(MaterialSwitch(this).apply {
            text = if (prefs.isRightSide) "Lato schermo: Destra" else "Lato schermo: Sinistra"
            setTextColor(textPrimary)
            isChecked = prefs.isRightSide
            setOnCheckedChangeListener { _, isRight ->
                prefs.isRightSide = isRight
                text = if (isRight) "Lato schermo: Destra" else "Lato schermo: Sinistra"
                notifyServiceReload()
            }
        })

        triggerLayout.addView(MaterialSwitch(this).apply {
            text = if (prefs.isTriggerTextBlack) "Colore lettere trigger: Nero" else "Colore lettere trigger: Bianco"
            setTextColor(textPrimary)
            setPadding(0, dp(6), 0, 0)
            isChecked = prefs.isTriggerTextBlack
            setOnCheckedChangeListener { _, isBlack ->
                prefs.isTriggerTextBlack = isBlack
                text = if (isBlack) "Colore lettere trigger: Nero" else "Colore lettere trigger: Bianco"
                notifyServiceReload()
            }
        })

        triggerLayout.addView(MaterialSwitch(this).apply {
            text = "Nascondi lettere trigger (solo scorrimento invisibile)"
            setTextColor(textPrimary)
            setPadding(0, dp(6), 0, 0)
            isChecked = prefs.isTriggerHidden
            setOnCheckedChangeListener { _, hidden ->
                prefs.isTriggerHidden = hidden
                notifyServiceReload()
            }
        })

        addSlider(triggerLayout, "Spaziatura verticale lettere A–Z", " dp", 0, 14, 1, prefs.letterSpacingExtraDp, textPrimary, accent, dp(12), { prefs.letterSpacingExtraDp = it }, { notifyServiceReload() })
        addSlider(triggerLayout, "Spaziatura verticale pallini colore", " dp", 0, 18, 1, prefs.colorDotSpacingExtraDp, textPrimary, accent, dp(12), { prefs.colorDotSpacingExtraDp = it }, { notifyServiceReload() })
        addSlider(triggerLayout, "Altezza base indice", "%", 30, 90, 1, prefs.barHeightPercent, textPrimary, accent, dp(12), { prefs.barHeightPercent = it }, { notifyServiceReload() })
        addSlider(triggerLayout, "Posizione verticale (Alto ↔ In fondo)", "%", 0, 100, 1, prefs.verticalPositionPercent, textPrimary, accent, dp(12), { prefs.verticalPositionPercent = it }, { notifyServiceReload() })
        addSlider(triggerLayout, "Larghezza area tocco bordo", " dp", 18, 46, 1, prefs.triggerWidthDp, textPrimary, accent, dp(12), { prefs.triggerWidthDp = it }, { notifyServiceReload() })
        addSlider(triggerLayout, "Opacità lettere trigger", "%", 0, 100, 1, prefs.triggerAlphaPercent, textPrimary, accent, dp(12), { prefs.triggerAlphaPercent = it }, { notifyServiceReload() })

        cardTrigger.addView(triggerLayout)
        container.addView(cardTrigger)

        // --- CARD 4: SFONDO SFUMATO, ONDA COLORE & APTICO ---
        val cardVisual = createCard(cardBg, dp(16))
        val visualLayout = createCardInner(dp(18))
        visualLayout.addView(createSectionHeader("Sfondo Sfumato, Onda Colore & Aptico", accent, dp(10)))

        addSlider(visualLayout, "Opacità sfondo lato app (molto opaco)", "%", 0, 100, 1, prefs.panelBgAlphaPercent, textPrimary, accent, dp(8), { prefs.panelBgAlphaPercent = it }, {})
        addSlider(visualLayout, "Opacità sfondo lato opposto (sfumato)", "%", 0, 100, 1, prefs.panelBgOppositeAlphaPercent, textPrimary, accent, dp(12), { prefs.panelBgOppositeAlphaPercent = it }, {})
        addSlider(visualLayout, "Opacità barra alfabetica pannello", "%", 0, 100, 1, prefs.indexBarAlphaPercent, textPrimary, accent, dp(12), { prefs.indexBarAlphaPercent = it }, {})
        addSlider(visualLayout, "Forza impulso onda colore", "%", 0, 100, 5, prefs.colorWaveStrengthPercent, textPrimary, accent, dp(12), { prefs.colorWaveStrengthPercent = it }, {})
        addSlider(visualLayout, "Potenza feedback aptico", "%", 0, 100, 5, prefs.hapticStrengthPercent, textPrimary, accent, dp(12), { prefs.hapticStrengthPercent = it }, {
            HapticHelper.trigger(this, prefs, HapticHelper.PulseType.TICK)
        })
        addSlider(visualLayout, "Forza animazione onda (stile Niagara)", "%", 0, 140, 1, prefs.waveStrengthPercent, textPrimary, accent, dp(12), { prefs.waveStrengthPercent = it }, {})

        cardVisual.addView(visualLayout)
        container.addView(cardVisual)

        // --- CARD 5: DIMENSIONI, SCORRIMENTO CONTINUO & DISTANZE APP ---
        val cardSpacing = createCard(cardBg, dp(16))
        val spacingLayout = createCardInner(dp(18))
        spacingLayout.addView(createSectionHeader("Elenco App, Scorrimento Continuo & Distanze", accent, dp(10)))

        spacingLayout.addView(MaterialSwitch(this).apply {
            text = if (prefs.isAppsBottomToTop) "Ordine app: Dal basso verso l'alto (mano piccola)" else "Ordine app: Dall'alto verso il basso"
            setTextColor(textPrimary)
            isChecked = prefs.isAppsBottomToTop
            setOnCheckedChangeListener { _, bottomUp ->
                prefs.isAppsBottomToTop = bottomUp
                text = if (bottomUp) "Ordine app: Dal basso verso l'alto (mano piccola)" else "Ordine app: Dall'alto verso il basso"
            }
        })

        spacingLayout.addView(MaterialSwitch(this).apply {
            text = "Mostra etichette nomi app"
            setTextColor(textPrimary)
            setPadding(0, dp(6), 0, 0)
            isChecked = prefs.showAppLabels
            setOnCheckedChangeListener { _, show -> prefs.showAppLabels = show }
        })

        addSlider(spacingLayout, "Zona attivazione scroll (distanza bordo alto/basso)", " dp", 20, 150, 5, prefs.autoScrollEdgeZoneDp, textPrimary, accent, dp(12), { prefs.autoScrollEdgeZoneDp = it }, {})
        addSlider(spacingLayout, "Velocità scorrimento continuo app", " app/s", 1, 15, 1, prefs.autoScrollAppsPerSec, textPrimary, accent, dp(12), { prefs.autoScrollAppsPerSec = it }, {})
        addSlider(spacingLayout, "Numero app recenti (sotto la Z)", " app", 10, 100, 10, prefs.recentCount, textPrimary, accent, dp(12), { prefs.recentCount = it }, {})
        addSlider(spacingLayout, "Scala generale pannello e icone", "%", 70, 130, 1, prefs.panelScalePercent, textPrimary, accent, dp(12), { prefs.panelScalePercent = it }, { notifyServiceReload() })
        addSlider(spacingLayout, "Distanza verticale tra le app", " dp", 2, 32, 1, prefs.appSpacingDp, textPrimary, accent, dp(12), { prefs.appSpacingDp = it }, {})
        addSlider(spacingLayout, "Distanza etichetta dall'icona", " dp", 0, 18, 1, prefs.labelDistanceDp, textPrimary, accent, dp(12), { prefs.labelDistanceDp = it }, {})
        addSlider(spacingLayout, "Distanza delle app dall'indice A–Z", " dp", 4, 44, 1, prefs.appsFromIndexDp, textPrimary, accent, dp(12), { prefs.appsFromIndexDp = it }, {})

        cardSpacing.addView(spacingLayout)
        container.addView(cardSpacing)

        root.addView(container)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        btnPermission.text = if (hasOverlay) "1. Permesso 'Mostra sopra altre app' OK ✓" else "1. Permesso 'Mostra sopra altre app'"
        val hasSplit = EdgeAccessibilityService.instance != null
        btnSplitSupport.text = if (hasSplit) "3. Schermo Diviso (Metà sopra/sotto) OK ✓" else "3. Abilita Schermo Diviso (Metà sopra/sotto)"

        if (hasOverlay && prefs.isServiceEnabled) {
            ContextCompat.startForegroundService(this, Intent(this, EdgeOverlayService::class.java))
        }
    }

    private fun notifyServiceReload() {
        if (prefs.isServiceEnabled && Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, EdgeOverlayService::class.java).apply { action = EdgeOverlayService.ACTION_RELOAD_CONFIG }
            )
        }
    }

    private fun createCard(bgColor: Int, marginBottomPx: Int) = MaterialCardView(this).apply {
        setCardBackgroundColor(bgColor)
        radius = 52f
        cardElevation = 0f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = marginBottomPx
        }
    }

    private fun createCardInner(padPx: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padPx, padPx, padPx, padPx)
    }

    private fun createSectionHeader(textStr: String, color: Int, bottomPad: Int) = TextView(this).apply {
        text = textStr
        textSize = 16.5f
        setTextColor(color)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, bottomPad)
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
                override fun onStopTrackingTouch(sb: SeekBar?) = onStop()
            })
        }
        parent.addView(label)
        parent.addView(seek)
    }
}
