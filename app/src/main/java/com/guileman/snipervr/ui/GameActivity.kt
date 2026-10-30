package com.guileman.snipervr.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.guileman.snipervr.android.CameraController
import com.guileman.snipervr.android.Detector
import com.guileman.snipervr.android.SensorTracker
import com.guileman.snipervr.audio.Synth
import com.guileman.snipervr.game.GameMode
import com.guileman.snipervr.game.GameSettings
import com.guileman.snipervr.game.TargetSet

class GameActivity : ComponentActivity() {

    private lateinit var settings: GameSettings
    private lateinit var gameView: GameView
    private lateinit var container: FrameLayout
    private var previewView: PreviewView? = null
    private var camera: CameraController? = null
    private var detector: Detector? = null
    private lateinit var sensors: SensorTracker
    private val synth = Synth()
    private lateinit var controls: View
    private var blurAnim: ValueAnimator? = null

    private val usesCamera get() = settings.mode == GameMode.REAL || !settings.syntheticScene

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = Prefs.load(this)
        sensors = SensorTracker(this, settings.useCompass)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        container = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(-1, -1))

        if (usesCamera) {
            previewView = PreviewView(this).apply {
                // TextureView: necesario para aplicar RenderEffect (desenfoque, visor térmico).
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            container.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        }

        gameView = GameView(this, settings, synth)
        gameView.orientationSource = { if (sensors.ready) sensors.orientation else null }
        gameView.sensorsReady = { sensors.ready }
        gameView.onShotFx = { shotEffects() }
        container.addView(gameView, FrameLayout.LayoutParams(-1, -1))

        controls = buildControls()
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hideSystemUi()

        if (!sensors.hasGyro) Toast.makeText(this, "Este equipo no tiene giroscopio: el seguimiento será menos preciso", Toast.LENGTH_LONG).show()

        if (usesCamera) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else Toast.makeText(this, "Sin permiso de cámara no hay realidad aumentada", Toast.LENGTH_LONG).show()
    }

    private fun startCamera() {
        val pv = previewView ?: return
        if (settings.mode == GameMode.REAL) {
            val humans = settings.targetSet != TargetSet.ANIMALS
            val animals = settings.targetSet == TargetSet.ANIMALS
            detector = Detector(this, humans, animals) { batch -> gameView.onDetections(batch) }.also { d ->
                d.orientationProvider = { if (sensors.ready) sensors.orientation else null }
                gameView.detectorError = d.error
            }
        }
        val det = detector
        val analyzer: ((androidx.camera.core.ImageProxy) -> Unit)? = det?.let { d -> { img -> d.analyze(img) } }
        val cc = CameraController(this, pv, analyzer)
        camera = cc
        gameView.zoomSetter = { z -> cc.setZoom(z) }
        cc.start {
            gameView.sensorHFov = cc.lens.hFovRad
            gameView.previewW = cc.previewSize.width.toDouble()
            gameView.previewH = cc.previewSize.height.toDouble()
            gameView.updateOptics()
        }
    }

    // ------------------------------------------------------------------ controles

    private fun buildControls(): View {
        val ctx = this
        val layer = FrameLayout(ctx)
        val pad = Ui.dp(ctx, 8f).toInt()

        fun col(gravity: Int) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            this.gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            layer.addView(this, FrameLayout.LayoutParams(-2, -2, gravity))
        }
        fun LinearLayout.btn(t: String, size: Float = 13f, f: () -> Unit) =
            addView(Ui.button(ctx, t, size) { f() }, LinearLayout.LayoutParams(Ui.dp(ctx, 92f).toInt(), -2).apply { topMargin = pad / 2 })

        // Izquierda abajo: torretas
        val left = col(Gravity.START or Gravity.BOTTOM)
        left.btn("ELEV ▲") { gameView.turret(1, 0) }
        left.btn("ELEV ▼") { gameView.turret(-1, 0) }
        left.btn("DER ►") { gameView.turret(0, 1) }
        left.btn("◄ IZQ") { gameView.turret(0, -1) }
        if (settings.assist) left.btn("AUTO") { gameView.autoDial() }
        left.btn("Torretas 0") { gameView.zeroTurrets() }

        // Derecha: disparo y opciones
        val right = col(Gravity.END or Gravity.BOTTOM)
        right.btn("Zoom +") { gameView.changeMagnification(1) }
        right.btn("Zoom −") { gameView.changeMagnification(-1) }
        right.btn("Vista") { gameView.scopeCircle = !gameView.scopeCircle }
        right.btn("Térmico") { toggleThermal() }
        right.btn("Recargar") { gameView.reload() }
        if (settings.mode == GameMode.VIRTUAL) right.btn("Reubicar") { gameView.respawn() }
        val fire = Ui.button(ctx, "FUEGO", 18f) { gameView.fire() }.apply {
            background = Ui.bg(Color.argb(170, 160, 30, 20), Ui.dp(ctx, 46f), Ui.dp(ctx, 2f).toInt(), Color.argb(220, 255, 210, 200))
        }
        right.addView(fire, LinearLayout.LayoutParams(Ui.dp(ctx, 92f).toInt(), Ui.dp(ctx, 92f).toInt()).apply { topMargin = pad })

        // Salir y ocultar controles (para montar el celular)
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad, pad, pad, pad) }
        top.addView(Ui.button(ctx, "✕ Menú") { finish() })
        val hide = Ui.button(ctx, "Ocultar") { b ->
            val vis = left.visibility == View.VISIBLE
            val v = if (vis) View.GONE else View.VISIBLE
            left.visibility = v; right.visibility = v
            b.text = if (vis) "Mostrar" else "Ocultar"
        }
        top.addView(hide, LinearLayout.LayoutParams(-2, -2).apply { marginStart = pad })
        // Abajo al centro, para no tapar el HUD
        layer.addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))
        return layer
    }

    // ------------------------------------------------------------------ efectos de cámara

    private fun thermalFilter(): ColorMatrixColorFilter {
        // Blanco caliente: luminancia con alto contraste.
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val k = 1.9f; val off = -110f
        val contrast = ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, off,
            0f, k, 0f, 0f, off,
            0f, 0f, k, 0f, off,
            0f, 0f, 0f, 1f, 0f))
        gray.postConcat(contrast)
        return ColorMatrixColorFilter(gray)
    }

    private fun toggleThermal() {
        gameView.thermal = !gameView.thermal
        applyEffects(0f)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && gameView.thermal && usesCamera) {
            Toast.makeText(this, "El visor térmico de la cámara requiere Android 12", Toast.LENGTH_SHORT).show()
        }
    }

    /** Aplica desenfoque (al disparar) y visor térmico con RenderEffect. */
    private fun applyEffects(blur: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val pv = previewView
        var camFx: RenderEffect? = if (gameView.thermal) RenderEffect.createColorFilterEffect(thermalFilter()) else null
        if (blur > 0.5f) {
            val b = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
            camFx = if (camFx != null) RenderEffect.createChainEffect(b, camFx) else b
            gameView.setRenderEffect(RenderEffect.createBlurEffect(blur * 0.6f, blur * 0.6f, Shader.TileMode.CLAMP))
        } else {
            gameView.setRenderEffect(null)
        }
        pv?.setRenderEffect(camFx)
    }

    private fun shotEffects() {
        // Retroceso: la imagen salta hacia arriba y vuelve.
        container.animate().cancel()
        container.translationY = 0f
        container.animate().translationY(-height() * 0.06f).rotation(-0.8f).setDuration(45).withEndAction {
            container.animate().translationY(0f).rotation(0f).setDuration(380).start()
        }.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurAnim?.cancel()
            blurAnim = ValueAnimator.ofFloat(28f, 0f).apply {
                duration = 420
                addUpdateListener { applyEffects(it.animatedValue as Float) }
                start()
            }
        }
    }

    private fun height() = container.height.toFloat()

    // ------------------------------------------------------------------ gatillo físico

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (event.repeatCount == 0) gameView.fire()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { gameView.turret(1, 0); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { gameView.turret(-1, 0); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { gameView.turret(0, 1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { gameView.turret(0, -1); return true }
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_R -> { gameView.reload(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------------ ciclo de vida

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        sensors.start()
        synth.start()
        gameView.start()
    }

    override fun onPause() {
        super.onPause()
        gameView.stop()
        sensors.stop()
        synth.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        camera?.shutdown()
    }
}
