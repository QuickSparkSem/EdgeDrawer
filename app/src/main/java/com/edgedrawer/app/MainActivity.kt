package com.edgedrawer.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
        window.statusBarColor = Color.parseColor("#0F1117")
        window.navigationBarColor = Color.parseColor("#0F1117")
        prefs = PrefsManager(this)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1117"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(42), dp(20), dp(36))
        }

        val title = TextView(this).apply {
            text = "EdgeDrawer A–Z"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Indice alfabetico trasparente, filtro colore diretto e apertura Pop-up / Split"
            textSize = 13.5f
            setTextColor(Color.parseColor("#9AA3B2"))
            setPadding(0, dp(4), 0, dp(18))
        }

        // --- CARD 1: PERMESSI E ATTIVAZIONE ---
        val cardMain = createCard(dp(16))
        val mainLayout = createCardInner(dp(16))

        btnPermission = MaterialButton(this).apply {
            text = "1. Concedi permesso 'Mostra sopra altre app'"
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
            text = "2. Attiva indice laterale ovunque"
            setTextColor(Color.WHITE)
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
            setBackgroundColor(Color.parseColor("#242B3D"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnTest = MaterialButton(this).apply {
            text = "Apri pannello adesso"
            setBackgroundColor(Color.parseColor("#1F2636"))
            setTextColor(Color.parseColor("#4D94FF"))
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

        // --- CARD 2: IMPOSTAZIONI TRIGGER ALFABETICO ---
        val cardTrigger = createCard(dp(16))
        val triggerLayout = createCardInner(dp(16))

        triggerLayout.addView(createSectionHeader("Trigger Alfabetico sul Bordo", dp(10)))

        val sideSwitch = MaterialSwitch(this).apply {
            text = if (prefs.isRightSide) "Lato schermo: Destra" else "Lato schermo: Sinistra"
            setTextColor(Color.WHITE)
            isChecked = prefs.isRightSide
            setOnCheckedChangeListener { _, isRight ->
                prefs.isRightSide = isRight
                text = if (isRight) "Lato schermo: Destra" else "Lato schermo: Sinistra"
                notifyServiceReload()
            }
        }

        val cornerSwitch = MaterialSwitch(this).apply {
            text = if (prefs.isTriggerBottomCorner) "Posizione: Angolo in basso" else "Posizione: Centro verticale"
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, 0)
            isChecked = prefs.isTriggerBottomCorner
            setOnCheckedChangeListener { _, inCorner ->
                prefs.isTriggerBottomCorner = inCorner
                text = if (inCorner) "Posizione: Angolo in basso" else "Posizione: Centro verticale"
                notifyServiceReload()
            }
        }

        val hideTriggerSwitch = MaterialSwitch(this).apply {
            text = "Nascondi lettere trigger (solo gesture invisibile)"
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, 0)
            isChecked = prefs.isTriggerHidden
            setOnCheckedChangeListener { _, hidden ->
                prefs.isTriggerHidden = hidden
                notifyServiceReload()
            }
        }

        triggerLayout.addView(sideSwitch)
        triggerLayout.addView(cornerSwitch)
        triggerLayout.addView(hideTriggerSwitch)

        addSlider(
            parent = triggerLayout,
            titlePrefix = "Altezza/Dimensione trigger",
            unit = "%",
            min = 28,
            max = 90,
            initial = prefs.triggerSizePercent,
            dp12 = dp(12),
            onChanged = { prefs.triggerSizePercent = it },
            onStop = { notifyServiceReload() }
        )

        addSlider(
            parent = triggerLayout,
            titlePrefix = "Larghezza area tocco trigger",
            unit = " dp",
            min = 16,
            max = 44,
            initial = prefs.triggerWidthDp,
            dp12 = dp(12),
            onChanged = { prefs.triggerWidthDp = it },
            onStop = { notifyServiceReload() }
        )

        addSlider(
            parent = triggerLayout,
            titlePrefix = "Visibilità lettere trigger",
            unit = "%",
            min = 5,
            max = 100,
            initial = prefs.triggerAlphaPercent,
            dp12 = dp(12),
            onChanged = { prefs.triggerAlphaPercent = it },
            onStop = { notifyServiceReload() }
        )

        cardTrigger.addView(triggerLayout)

        // --- CARD 3: IMPOSTAZIONI PANNELLO E APP ---
        val cardPanel = createCard(dp(16))
        val panelLayout = createCardInner(dp(16))

        panelLayout.addView(createSectionHeader("Pannello Aperto & Icone App", dp(10)))

        val labelsSwitch = MaterialSwitch(this).apply {
            text = "Mostra etichette nomi app"
            setTextColor(Color.WHITE)
            isChecked = prefs.showAppLabels
            setOnCheckedChangeListener { _, show ->
                prefs.showAppLabels = show
            }
        }
        panelLayout.addView(labelsSwitch)

        addSlider(
            parent = panelLayout,
            titlePrefix = "Dimensione pannello e icone",
            unit = "%",
            min = 70,
            max = 130,
            initial = prefs.panelScalePercent,
            dp12 = dp(12),
            onChanged = { prefs.panelScalePercent = it },
            onStop = {}
        )

        addSlider(
            parent = panelLayout,
            titlePrefix = "Opacità sfondo pannello",
            unit = "%",
            min = 20,
            max = 100,
            initial = prefs.panelAlphaPercent,
            dp12 = dp(12),
            onChanged = { prefs.panelAlphaPercent = it },
            onStop = {}
        )

        addSlider(
            parent = panelLayout,
            titlePrefix = "Intensità effetto Blur",
            unit = "",
            min = 0,
            max = 100,
            initial = prefs.blurRadius,
            dp12 = dp(12),
            onChanged = { prefs.blurRadius = it },
            onStop = {}
        )

        cardPanel.addView(panelLayout)

        // Guida rapida
        val guideText = TextView(this).apply {
            text = "• Scorri sul trigger alfabetico per aprire su ↓ (Recenti) o A–Z.\n" +
                "• Senza staccare il dito: passa sui pallini colorati in basso per filtrare per colore, poi risali sull'alfabeto.\n" +
                "• Tieni fermo il dito su un'app: appaiono di fianco 'Pop-up', 'Metà sopra' e 'Metà sotto' (trascina verso il centro e rilascia per sceglierli)."
            setTextColor(Color.parseColor("#9AA3B2"))
            textSize = 13f
            setPadding(dp(4), dp(8), dp(4), 0)
        }

        container.addView(title)
        container.addView(subtitle)
        container.addView(cardMain)
        container.addView(cardTrigger)
        container.addView(cardPanel)
        container.addView(guideText)
        root.addView(container)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        btnPermission.text = if (hasOverlay) {
            "1. Permesso 'Mostra sopra altre app' OK ✓"
        } else {
            "1. Concedi permesso 'Mostra sopra altre app'"
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

    private fun createCard(marginBottomPx: Int): MaterialCardView {
        return MaterialCardView(this).apply {
            setCardBackgroundColor(Color.parseColor("#181C26"))
            radius = 40f
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

    private fun createSectionHeader(textStr: String, bottomPad: Int): TextView {
        return TextView(this).apply {
            text = textStr
            textSize = 16f
            setTextColor(Color.parseColor("#4D94FF"))
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
        initial: Int,
        dp12: Int,
        onChanged: (Int) -> Unit,
        onStop: () -> Unit
    ) {
        val label = TextView(this).apply {
            text = "$titlePrefix: $initial$unit"
            setTextColor(Color.WHITE)
            setPadding(0, dp12, 0, 4)
        }
        val seek = SeekBar(this).apply {
            this.max = max - min
            this.progress = (initial - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val realVal = min + value
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
