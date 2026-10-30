package com.guileman.snipervr.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import com.guileman.snipervr.R
import com.guileman.snipervr.core.Cartridge
import com.guileman.snipervr.core.TurretUnit
import com.guileman.snipervr.game.GameMode
import com.guileman.snipervr.game.GameSettings
import com.guileman.snipervr.game.TargetSet

/** Menú principal, armado en código. */
class MainActivity : ComponentActivity() {

    private lateinit var s: GameSettings
    private lateinit var form: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        s = Prefs.load(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(BitmapFactory.decodeResource(resources, R.drawable.menu_bg))
            alpha = 0.85f
        }
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        // Columna izquierda: título y botón de inicio
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(this@MainActivity, 24f).toInt()
            setPadding(p, p, p, p)
        }
        left.addView(Ui.label(this, "SNIPER AR", 40f, Color.WHITE, true))
        left.addView(Ui.label(this, "Mira de francotirador con realidad aumentada", 15f, Ui.ACCENT))
        left.addView(Ui.label(this, "Montá el celular en tu réplica de rifle y apuntá con el cuerpo.\nGatillo: pantalla, teclas de volumen o control Bluetooth.", 13f, Color.LTGRAY).apply {
            setPadding(0, Ui.dp(this@MainActivity, 12f).toInt(), 0, Ui.dp(this@MainActivity, 18f).toInt())
        })
        left.addView(Ui.button(this, "  INICIAR  ", 22f) { startGame() }.apply {
            background = Ui.bg(Ui.ACCENT, Ui.dp(this@MainActivity, 14f))
            setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(-2, -2))
        panel.addView(left, LinearLayout.LayoutParams(0, -1, 0.9f))

        // Columna derecha: opciones
        val scroll = ScrollView(this).apply {
            background = Ui.bg(Ui.PANEL, Ui.dp(this@MainActivity, 16f))
        }
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@MainActivity, 16f).toInt()
            setPadding(p, p / 2, p, p)
        }
        scroll.addView(form)
        val lp = LinearLayout.LayoutParams(0, -1, 1.1f).apply {
            val m = Ui.dp(this@MainActivity, 12f).toInt(); setMargins(m, m, m, m)
        }
        panel.addView(scroll, lp)
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        buildForm()
        hideSystemUi()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun buildForm() {
        form.removeAllViews()
        val ctx = this
        form.addView(Ui.segmented(ctx, "Modalidad", listOf("Blancos virtuales 3D", "Blancos reales (cámara)"), s.mode.ordinal) {
            s = s.copy(mode = GameMode.entries[it]); buildForm()
        })
        if (s.mode == GameMode.REAL && s.targetSet == TargetSet.PRACTICE) s = s.copy(targetSet = TargetSet.HUMANS)
        val sets = if (s.mode == GameMode.VIRTUAL) listOf("Tirador (humanos)", "Cazador (animales)", "Práctica (aceros)")
        else listOf("Tirador (personas)", "Cazador (animales)")
        form.addView(Ui.segmented(ctx, "Blancos", sets, s.targetSet.ordinal.coerceAtMost(sets.size - 1)) {
            s = s.copy(targetSet = TargetSet.entries[it])
        })
        form.addView(Ui.segmented(ctx, "Cartucho", Cartridge.PRESETS.map { it.name.substringBefore(" ") }, s.cartridgeIndex) {
            s = s.copy(cartridgeIndex = it)
        })
        val zeros = listOf(100.0, 200.0, 300.0)
        form.addView(Ui.segmented(ctx, "Distancia de cero", zeros.map { "${it.toInt()} m" }, zeros.indexOf(s.zeroRange).coerceAtLeast(0)) {
            s = s.copy(zeroRange = zeros[it])
        })
        form.addView(Ui.segmented(ctx, "Torretas", TurretUnit.entries.map { it.label }, s.turretUnit.ordinal) {
            s = s.copy(turretUnit = TurretUnit.entries[it])
        })
        val mags = listOf(3.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0)
        form.addView(Ui.segmented(ctx, "Aumentos de la mira", mags.map { "${it.toInt()}x" }, mags.indexOf(s.magnification).coerceAtLeast(2)) {
            s = s.copy(magnification = mags[it])
        })
        val eyes = listOf(0.20, 0.25, 0.30, 0.35, 0.40, 0.50)
        form.addView(Ui.segmented(ctx, "Distancia ojo–pantalla (montaje)", eyes.map { "${(it * 100).toInt()} cm" }, eyes.indexOf(s.eyeDistanceM).coerceAtLeast(2)) {
            s = s.copy(eyeDistanceM = eyes[it])
        })
        form.addView(Ui.segmented(ctx, "Vista", listOf("Sólo el círculo de la mira", "Pantalla completa"), if (s.scopeCircle) 0 else 1) {
            s = s.copy(scopeCircle = it == 0)
        })
        val ranges = listOf(50.0 to 300.0, 100.0 to 600.0, 300.0 to 1000.0)
        if (s.mode == GameMode.VIRTUAL) {
            form.addView(Ui.segmented(ctx, "Distancia de los blancos", listOf("Cerca 50–300 m", "Media 100–600 m", "Lejos 300–1000 m"),
                ranges.indexOfFirst { it.first == s.minRange }.coerceAtLeast(1)) {
                s = s.copy(minRange = ranges[it].first, maxRange = ranges[it].second)
            })
            form.addView(Ui.segmented(ctx, "Fondo", listOf("Cámara (realidad aumentada)", "Escenario sintético"), if (s.syntheticScene) 1 else 0) {
                s = s.copy(syntheticScene = it == 1)
            })
        }
        val winds = listOf(0.0, 3.0, 6.0, 10.0)
        form.addView(Ui.segmented(ctx, "Viento máximo", listOf("Calma", "Suave 3 m/s", "Moderado 6 m/s", "Fuerte 10 m/s"), winds.indexOf(s.maxWind).coerceAtLeast(0)) {
            s = s.copy(maxWind = winds[it])
        })
        form.addView(Ui.segmented(ctx, "Asistencia balística (computadora de tiro)", listOf("Sí", "No (realista)"), if (s.assist) 0 else 1) {
            s = s.copy(assist = it == 0)
        })
        form.addView(Ui.segmented(ctx, "Brújula", listOf("Usar brújula", "Sólo giroscopio (soportes metálicos)"), if (s.useCompass) 0 else 1) {
            s = s.copy(useCompass = it == 0)
        })
    }

    private fun startGame() {
        Prefs.save(this, s)
        startActivity(Intent(this, GameActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }
}
