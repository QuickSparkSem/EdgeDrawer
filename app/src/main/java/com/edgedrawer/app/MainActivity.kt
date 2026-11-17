package com.edgedrawer.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1117"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(42), dp(20), dp(32))
        }

        val title = TextView(this).apply {
            text = "EdgeDrawer A–Z"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Indice laterale rapido con app recenti e filtro per colore icona"
            textSize = 14f
            setTextColor(Color.parseColor("#9AA3B2"))
            setPadding(0, dp(4), 0, dp(20))
        }

        // Card Permessi & Attivazione
        val cardMain = createCard(dp(16))
        val cardMainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        btnPermission = MaterialButton(this).apply {
            text = "1. Concedi permesso 'Mostra sopra altre app'"
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        switchEnable = MaterialSwitch(this).apply {
            text = "2. Attiva gesture laterale ovunque"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, dp(12), 0, dp(4))
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

        val btnTest = MaterialButton(this).apply {
            text = "Prova subito il pannello"
            setBackgroundColor(Color.parseColor("#242B3D"))
            setTextColor(Color.WHITE)
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

        cardMainLayout.addView(btnPermission)
        cardMainLayout.addView(switchEnable)
        cardMainLayout.addView(btnTest)
        cardMain.addView(cardMainLayout)

        // Card Personalizzazione
        val cardSettings = createCard(dp(16))
        val settingsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

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

        val blurLabel = TextView(this).apply {
            text = "Intensità effetto Blur: ${prefs.blurRadius}"
            setTextColor(Color.WHITE)
            setPadding(0, dp(14), 0, dp(4))
        }
        val blurSeek = SeekBar(this).apply {
            max = 100
            progress = prefs.blurRadius
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    prefs.blurRadius = value
                    blurLabel.text = "Intensità effetto Blur: $value"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val widthLabel = TextView(this).apply {
            text = "Larghezza area gesture bordo: ${prefs.triggerWidthDp} dp"
            setTextColor(Color.WHITE)
            setPadding(0, dp(14), 0, dp(4))
        }
        val widthSeek = SeekBar(this).apply {
            max = 45
            progress = prefs.triggerWidthDp
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val safe = value.coerceAtLeast(12)
                    prefs.triggerWidthDp = safe
                    widthLabel.text = "Larghezza area gesture bordo: $safe dp"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    notifyServiceReload()
                }
            })
        }

        settingsLayout.addView(sideSwitch)
        settingsLayout.addView(blurLabel)
        settingsLayout.addView(blurSeek)
        settingsLayout.addView(widthLabel)
        settingsLayout.addView(widthSeek)
        cardSettings.addView(settingsLayout)

        // Guida rapida gesti
        val guideText = TextView(this).apply {
            text = "• Scorri dal bordo verso l'interno per aprire l'indice.\n" +
                "• Senza staccare il dito: scorri su ↓ (Recenti) o A–Z, spostati sull'app desiderata e alza il dito per avviarla subito.\n" +
                "• Alzando il dito: il pannello resta aperto e puoi toccare la cartella colori in basso per filtrare le app solo di quel colore."
            setTextColor(Color.parseColor("#9AA3B2"))
            textSize = 13f
            setPadding(dp(4), dp(18), dp(4), 0)
            gravity = Gravity.START
        }

        container.addView(title)
        container.addView(subtitle)
        container.addView(cardMain)
        container.addView(cardSettings)
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
}
