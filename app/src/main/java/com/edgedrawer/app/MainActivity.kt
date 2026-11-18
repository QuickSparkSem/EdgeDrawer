package com.edgedrawer.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

class SpectrumColorPickerView(
    context: Context,
    initialColor: Int,
    private val onColorSelected: (Int) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = Color.WHITE }
    private val colors = intArrayOf(Color.WHITE, Color.RED, Color.parseColor("#FF9500"), Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.BLACK)

    private var selectedColor = initialColor
    private var ratio = when (initialColor) {
        Color.WHITE -> 0f
        Color.BLACK -> 1f
        else -> FloatArray(3).also { Color.colorToHSV(initialColor, it) }[0] / 360f * 0.8f + 0.1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0) barPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP) && width > 0) {
            parent?.requestDisallowInterceptTouchEvent(true)
            ratio = (e.x / width).coerceIn(0f, 1f)
            val scaled = ratio * (colors.size - 1)
            val i = scaled.toInt().coerceIn(0, colors.size - 2)
            val t = scaled - i
            val c1 = colors[i]; val c2 = colors[i + 1]
            selectedColor = Color.rgb(
                (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt(),
                (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt(),
                (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt()
            )
            onColorSelected(selectedColor)
            invalidate()
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val pad = 12f * density
        val r = (height * 0.44f) / 2f
        canvas.drawRoundRect(pad, height * 0.28f, width - pad, height * 0.72f, r, r, barPaint)
        val cx = pad + (width - pad * 2f) * ratio
        thumbPaint.color = selectedColor
        thumbPaint.setShadowLayer(4f * density, 0f, 2f * density, Color.argb(180, 0, 0, 0))
        canvas.drawCircle(cx, height / 2f, 13f * density, thumbPaint)
        canvas.drawCircle(cx, height / 2f, 13f * density, strokePaint)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: PrefsManager
    private lateinit var repo: AppRepository
    private lateinit var switchEnable: MaterialSwitch
    private lateinit var btnPerm: MaterialButton
    private lateinit var btnAcc: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)
        repo = AppRepository(this, prefs).also { it.reloadAppsAsync() }
        renderUI()
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        btnPerm.text = if (hasOverlay) "1. Permesso 'Mostra sopra altre app' OK ✓" else "1. Permesso 'Mostra sopra altre app'"
        btnAcc.text = if (EdgeAccessibilityService.instance != null) "3. Schermo Diviso & Rilevamento OK ✓" else "3. Abilita Schermo Diviso & Tastiera/App"
        if (hasOverlay && prefs.isServiceEnabled) ContextCompat.startForegroundService(this, Intent(this, EdgeOverlayService::class.java))
    }

    private fun reloadService() {
        if (prefs.isServiceEnabled && Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(this, Intent(this, EdgeOverlayService::class.java).setAction(EdgeOverlayService.ACTION_RELOAD_CONFIG))
        }
    }

    private fun renderUI() {
        val isNight = prefs.isNightThemeActive()
        val accent = prefs.getAccentColor()
        val bg = if (isNight) Color.parseColor("#0B0D12") else Color.parseColor("#F2F4F8")
        val cardBg = if (isNight) Color.parseColor("#171B24") else Color.WHITE
        val txt = if (isNight) Color.WHITE else Color.parseColor("#11141A")
        val subBtnBg = if (isNight) Color.parseColor("#252B3B") else Color.parseColor("#E6EAF2")
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = ScrollView(this).apply { setBackgroundColor(bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(32), dp(16), dp(32)) }

        col.addView(TextView(this).apply { text = "EdgeDrawer"; textSize = 28f; setTextColor(txt); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, dp(16)) })

        fun addCard(block: LinearLayout.() -> Unit) {
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
            inner.block()
            col.addView(MaterialCardView(this).apply {
                setCardBackgroundColor(cardBg); radius = 48f; cardElevation = 0f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
                addView(inner)
            })
        }

        fun LinearLayout.header(t: String) = addView(TextView(this@MainActivity).apply {
            text = t; textSize = 16f; setTextColor(accent); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, dp(8))
        })

        fun LinearLayout.toggle(t: String, checked: Boolean, onChange: (MaterialSwitch, Boolean) -> Unit) = addView(MaterialSwitch(this@MainActivity).apply {
            text = t; setTextColor(txt); isChecked = checked; setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, b -> onChange(this, b) }
        })

        fun LinearLayout.slider(title: String, unit: String, min: Int, max: Int, step: Int, init: Int, onSet: (Int) -> Unit, onDone: () -> Unit = {}) {
            val label = TextView(this@MainActivity).apply { text = "$title: $init$unit"; setTextColor(txt); textSize = 14f; setPadding(0, dp(10), 0, 2) }
            val seek = SeekBar(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(0, 8, 0, 8)
                this.max = (max - min) / step
                this.progress = ((init - min) / step).coerceIn(0, this.max)
                thumbTintList = ColorStateList.valueOf(accent)
                progressTintList = ColorStateList.valueOf(accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { val r = min + v * step; label.text = "$title: $r$unit"; onSet(r) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) = onDone()
                })
            }
            addView(label); addView(seek)
        }

        // 1. Permessi & Servizio
        addCard {
            btnPerm = MaterialButton(this@MainActivity).apply {
                text = "1. Permesso 'Mostra sopra altre app'"; backgroundTintList = ColorStateList.valueOf(accent); setTextColor(Color.WHITE)
                setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            }
            addView(btnPerm)

            switchEnable = MaterialSwitch(this@MainActivity).apply {
                text = "2. Attiva indice sul bordo"; setTextColor(txt); textSize = 15.5f; isChecked = prefs.isServiceEnabled; setPadding(0, dp(8), 0, dp(6))
                setOnCheckedChangeListener { _, on ->
                    if (on && !Settings.canDrawOverlays(this@MainActivity)) { isChecked = false; return@setOnCheckedChangeListener }
                    prefs.isServiceEnabled = on
                    val i = Intent(this@MainActivity, EdgeOverlayService::class.java)
                    if (on) ContextCompat.startForegroundService(this@MainActivity, i) else stopService(i)
                }
            }
            addView(switchEnable)

            btnAcc = MaterialButton(this@MainActivity).apply {
                text = "3. Abilita Schermo Diviso & Tastiera/App"; backgroundTintList = ColorStateList.valueOf(subBtnBg); setTextColor(txt)
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            addView(btnAcc)

            addView(MaterialButton(this@MainActivity).apply {
                text = "Apri anteprima pannello"; backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT); setTextColor(accent)
                setOnClickListener {
                    if (Settings.canDrawOverlays(this@MainActivity)) {
                        prefs.isServiceEnabled = true; switchEnable.isChecked = true
                        ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, EdgeOverlayService::class.java).setAction(EdgeOverlayService.ACTION_OPEN_DRAWER))
                    }
                }
            })
        }

        // 2. Tema & Colore Accento
        addCard {
            header("Tema & Colore Accento")
            val rowTheme = LinearLayout(this@MainActivity)
            listOf("Auto" to 0, "Giorno" to 1, "Notte" to 2).forEach { (lbl, m) ->
                rowTheme.addView(MaterialButton(this@MainActivity).apply {
                    text = lbl; val active = prefs.themeMode == m
                    backgroundTintList = ColorStateList.valueOf(if (active) accent else subBtnBg)
                    setTextColor(if (active) Color.WHITE else txt)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                    setOnClickListener { prefs.themeMode = m; reloadService(); renderUI() }
                })
            }
            addView(rowTheme)

            val rowAcc = LinearLayout(this@MainActivity).apply { setPadding(0, dp(10), 0, 0) }
            OneUIAccent.entries.forEachIndexed { idx, item ->
                rowAcc.addView(View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) }
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(item.colorInt); if (prefs.accentIndex == idx) setStroke(dp(3), txt) }
                    setOnClickListener { prefs.accentIndex = idx; reloadService(); renderUI() }
                })
            }
            addView(rowAcc)
        }

        // 3. Trigger, Spettro Colore & Ombra
        addCard {
            header("Trigger Laterale, Colore & Ombra")
            toggle(if (prefs.isRightSide) "Lato schermo: Destra" else "Lato schermo: Sinistra", prefs.isRightSide) { sw, r ->
                prefs.isRightSide = r; sw.text = if (r) "Lato schermo: Destra" else "Lato schermo: Sinistra"; reloadService()
            }
            toggle("Nascondi lettere trigger (solo scorrimento invisibile)", prefs.isTriggerHidden) { _, h -> prefs.isTriggerHidden = h; reloadService() }

            addView(TextView(this@MainActivity).apply { text = "Colore lettere trigger (Spettro)"; setTextColor(txt); textSize = 14f; setPadding(0, dp(10), 0, 4) })
            addView(SpectrumColorPickerView(this@MainActivity, prefs.triggerCustomColor) { prefs.triggerCustomColor = it; reloadService() }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
            })

            slider("Profondità ombra trigger", "%", 0, 100, 5, prefs.triggerShadowDepthPercent, { prefs.triggerShadowDepthPercent = it }, { reloadService() })
            slider("Distanza trigger dal bordo display", " dp", 0, 20, 1, prefs.triggerEdgeOffsetDp, { prefs.triggerEdgeOffsetDp = it }, { reloadService() })
            slider("Dimensione indice alfabetico", "%", 65, 140, 1, prefs.indexScalePercent, { prefs.indexScalePercent = it }, { reloadService() })
            slider("Spaziatura lettere A–Z", " dp", 0, 14, 1, prefs.letterSpacingExtraDp, { prefs.letterSpacingExtraDp = it }, { reloadService() })
            slider("Spaziatura pallini colore", " dp", 0, 18, 1, prefs.colorDotSpacingExtraDp, { prefs.colorDotSpacingExtraDp = it }, { reloadService() })
            slider("Altezza base indice", "%", 30, 90, 1, prefs.barHeightPercent, { prefs.barHeightPercent = it }, { reloadService() })
            slider("Posizione verticale", "%", 0, 100, 1, prefs.verticalPositionPercent, { prefs.verticalPositionPercent = it }, { reloadService() })
            slider("Larghezza tocco bordo", " dp", 18, 46, 1, prefs.triggerWidthDp, { prefs.triggerWidthDp = it }, { reloadService() })
            slider("Opacità lettere trigger", "%", 0, 100, 1, prefs.triggerAlphaPercent, { prefs.triggerAlphaPercent = it }, { reloadService() })
        }

        // 4. Disattivazione Automatica & App Nascoste
        addCard {
            header("Regole Automatiche & App Nascoste")
            toggle("Disabilita in modalità orizzontale (Landscape)", prefs.disableInLandscape) { _, v -> prefs.disableInLandscape = v; reloadService() }
            toggle("Disabilita quando la tastiera è aperta", prefs.disableOnKeyboard) { _, v -> prefs.disableOnKeyboard = v; reloadService() }

            addView(MaterialButton(this@MainActivity).apply {
                text = "Lista nera app (${prefs.blacklistPackages.size} selezionate)"; backgroundTintList = ColorStateList.valueOf(subBtnBg); setTextColor(txt)
                setOnClickListener { pickApps("Disabilita trigger in queste app", prefs.blacklistPackages) { prefs.blacklistPackages = it; text = "Lista nera app (${it.size} selezionate)"; reloadService() } }
            })
            addView(MaterialButton(this@MainActivity).apply {
                text = "App nascoste dall'indice (${prefs.hiddenPackages.size} nascoste)"; backgroundTintList = ColorStateList.valueOf(subBtnBg); setTextColor(txt)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                setOnClickListener { pickApps("Nascondi app dall'indice", prefs.hiddenPackages) { prefs.hiddenPackages = it; text = "App nascoste dall'indice (${it.size} nascoste)"; reloadService() } }
            })
        }

        // 5. Dimensioni App/Etichette, Scorrimento & Effetti
        addCard {
            header("Dimensioni App, Scorrimento & Effetti")
            toggle(if (prefs.isAppsBottomToTop) "Ordine app: Dal basso verso l'alto" else "Ordine app: Dall'alto verso il basso", prefs.isAppsBottomToTop) { sw, v ->
                prefs.isAppsBottomToTop = v; sw.text = if (v) "Ordine app: Dal basso verso l'alto" else "Ordine app: Dall'alto verso il basso"
            }
            toggle("Mostra nomi app", prefs.showAppLabels) { _, v -> prefs.showAppLabels = v }

            slider("Scala colonna app e icone", "%", 65, 140, 1, prefs.panelScalePercent, { prefs.panelScalePercent = it })
            slider("Dimensione etichette nomi app", " sp", 8, 18, 1, prefs.appLabelSizeSp, { prefs.appLabelSizeSp = it })
            slider("Zona auto-scroll bordo alto/basso", " dp", 20, 150, 5, prefs.autoScrollEdgeZoneDp, { prefs.autoScrollEdgeZoneDp = it })
            slider("Velocità auto-scroll", " app/s", 1, 18, 1, prefs.autoScrollAppsPerSec, { prefs.autoScrollAppsPerSec = it })
            slider("Numero app recenti", " app", 10, 100, 10, prefs.recentCount, { prefs.recentCount = it })
            slider("Distanza verticale tra app", " dp", 2, 32, 1, prefs.appSpacingDp, { prefs.appSpacingDp = it })
            slider("Distanza etichetta dall'icona", " dp", 0, 18, 1, prefs.labelDistanceDp, { prefs.labelDistanceDp = it })
            slider("Distanza app dall'indice", " dp", 4, 44, 1, prefs.appsFromIndexDp, { prefs.appsFromIndexDp = it })
            slider("Opacità sfondo lato app", "%", 0, 100, 1, prefs.panelBgAlphaPercent, { prefs.panelBgAlphaPercent = it })
            slider("Opacità sfondo lato opposto", "%", 0, 100, 1, prefs.panelBgOppositeAlphaPercent, { prefs.panelBgOppositeAlphaPercent = it })
            slider("Opacità barra alfabetica", "%", 0, 100, 1, prefs.indexBarAlphaPercent, { prefs.indexBarAlphaPercent = it })
            slider("Forza onda indice", "%", 0, 140, 1, prefs.waveStrengthPercent, { prefs.waveStrengthPercent = it })
            slider("Forza onda colore (luce energetica)", "%", 0, 150, 5, prefs.colorWaveStrengthPercent, { prefs.colorWaveStrengthPercent = it })
            slider("Feedback aptico", "%", 0, 100, 5, prefs.hapticStrengthPercent, { prefs.hapticStrengthPercent = it }, { HapticHelper.trigger(this@MainActivity, prefs, HapticHelper.Pulse.TICK) })
        }

        root.addView(col)
        setContentView(root)
    }

    private fun pickApps(title: String, current: Set<String>, onSave: (Set<String>) -> Unit) {
        val apps = repo.getAllInstalledApps()
        if (apps.isEmpty()) { Toast.makeText(this, "Caricamento app, riprova tra un attimo", Toast.LENGTH_SHORT).show(); return }
        val names = apps.map { "${it.label} (${it.packageName})" }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].packageName in current }
        val result = current.toMutableSet()
        AlertDialog.Builder(this).setTitle(title)
            .setMultiChoiceItems(names, checked) { _, i, sel -> if (sel) result.add(apps[i].packageName) else result.remove(apps[i].packageName) }
            .setPositiveButton("Salva") { _, _ -> onSave(result) }
            .setNegativeButton("Annulla", null).show()
    }
}
