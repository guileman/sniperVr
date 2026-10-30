package com.guileman.snipervr.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.View
import com.guileman.snipervr.R
import com.guileman.snipervr.android.DetectionBatch
import com.guileman.snipervr.android.Detector
import com.guileman.snipervr.audio.Synth
import com.guileman.snipervr.core.CameraModel
import com.guileman.snipervr.core.FiringSolution
import com.guileman.snipervr.core.Quat
import com.guileman.snipervr.core.RangeEstimator
import com.guileman.snipervr.core.TurretUnit
import com.guileman.snipervr.core.Vec3
import com.guileman.snipervr.game.GameEvent
import com.guileman.snipervr.game.GameMode
import com.guileman.snipervr.game.GameSettings
import com.guileman.snipervr.game.RealDetection
import com.guileman.snipervr.game.TargetKind
import com.guileman.snipervr.game.World
import com.guileman.snipervr.game.Zone
import com.guileman.snipervr.render.SceneRenderer
import com.guileman.snipervr.render.WorldEffect
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Vista principal del juego: corre el bucle, proyecta el mundo, dibuja la retícula, la máscara
 * de la mira y el HUD.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, val settings: GameSettings, private val synth: Synth) : View(context), Choreographer.FrameCallback {

    val world = World(settings)
    val cam = CameraModel()
    private val renderer = SceneRenderer(
        loadBmp(R.drawable.fx_dust), loadBmp(R.drawable.fx_spark)
    )
    private val muzzleSmoke = loadBmp(R.drawable.fx_muzzle)
    private val effects = ArrayList<WorldEffect>()

    /** Proveedor de orientación (sensores). */
    var orientationSource: () -> Quat? = { null }
    var sensorsReady: () -> Boolean = { false }
    /** Aplica el zoom en la cámara y devuelve el zoom real. */
    var zoomSetter: (Float) -> Float = { it }
    /** Efectos de pantalla al disparar (desenfoque, retroceso). */
    var onShotFx: () -> Unit = {}

    // Óptica
    var sensorHFov = Math.toRadians(66.0)
    var previewW = 1440.0
    var previewH = 1080.0
    var magnification = settings.magnification
    var scopeCircle = settings.scopeCircle
    var thermal = false
        set(v) { field = v; renderer.thermal = v }
    private var focalAtZoom1 = 1000.0
    private var appliedZoom = 1.0
    var effectiveMag = magnification; private set

    // Estado
    private var lastFrameNs = 0L
    private var running = false
    private var spawned = false
    private var shotFlashUntil = 0.0
    private var message = ""
    private var messageColor = Color.WHITE
    private var messageUntil = 0.0
    private var solution: FiringSolution? = null
    private var solutionRange = 0.0
    private var lastSolveAt = -1.0
    private var lastRange: Double? = null
    var detectorError: String? = null

    // Pinturas
    private val reticle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private val reticleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK }
    private val reticleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER }
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 6, 6, 6) }
    private val vignette = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 230, 220); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val hudStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(230, 230, 220); strokeWidth = 3f }
    private val hudFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sprite = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    private fun loadBmp(id: Int): Bitmap? = try { BitmapFactory.decodeResource(resources, id) } catch (_: Exception) { null }

    // ------------------------------------------------------------------ ciclo de vida

    fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cam.width = w.toDouble(); cam.height = h.toDouble()
        updateOptics()
    }

    /** Recalcula la focal en píxeles y el zoom para lograr los aumentos pedidos. */
    fun updateOptics() {
        if (cam.width < 10) return
        focalAtZoom1 = CameraModel.focalFromFov(sensorHFov, previewW, previewH, cam.width, cam.height, 1.0)
        val dm = resources.displayMetrics
        val dpi = (dm.xdpi + dm.ydpi) / 2.0
        val screenWidthM = cam.width / dpi * 0.0254
        val wanted = CameraModel.zoomForMagnification(screenWidthM, settings.eyeDistanceM, magnification, focalAtZoom1, cam.width)
        appliedZoom = if (settings.mode == GameMode.VIRTUAL && settings.syntheticScene) wanted
        else zoomSetter(wanted.toFloat()).toDouble()
        cam.focalPx = focalAtZoom1 * appliedZoom
        // Aumentos efectivos que percibe el ojo a la distancia de montaje.
        val eyeHalf = atan(screenWidthM / 2 / settings.eyeDistanceM)
        val trueHalf = atan(cam.width / 2 / cam.focalPx)
        effectiveMag = tan(eyeHalf) / tan(trueHalf)
    }

    fun changeMagnification(delta: Int) {
        val steps = listOf(3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 20.0, 25.0)
        val i = steps.indexOfFirst { it >= magnification - 0.01 }.coerceAtLeast(0)
        magnification = steps[(i + delta).coerceIn(0, steps.size - 1)]
        updateOptics()
        synth.click()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNs == 0L) 0.016 else ((frameTimeNanos - lastFrameNs) * 1e-9).coerceIn(0.0, 0.1)
        lastFrameNs = frameTimeNanos
        orientationSource()?.let { cam.orientation = it }
        cam.screenRotationDeg = (display?.rotation ?: 1) * 90
        cam.update()
        if (!spawned && sensorsReady()) {
            world.spawnInitial(cam.azimuthDeg())
            spawned = true
        }
        world.update(dt)
        handleEvents()
        effects.removeAll { world.time - it.start > it.duration }
        synth.windLevel = (world.wind.speed / 10.0).toFloat().coerceIn(0f, 1f) * 0.6f
        if (settings.assist && world.time - lastSolveAt > 0.25) solve()
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun solve() {
        lastSolveAt = world.time
        lastRange = world.rangeUnderReticle(cam)
        val r = lastRange ?: return
        if (r < 10) return
        val (cross, head) = world.wind.relative(cam.azimuthDeg())
        world.solver.enableCoriolis = false
        solution = world.solver.solution(r, world.zeroAngle, settings.sightHeight, cross, head)
        world.solver.enableCoriolis = true
        solutionRange = r
    }

    // ------------------------------------------------------------------ acciones

    fun fire() {
        if (world.rounds == 0) { synth.dryFire(); flash("SIN MUNICIÓN · RECARGAR", Color.rgb(255, 180, 60)); return }
        if (!world.canFire) return
        if (world.fire(cam)) {
            synth.gunshot()
            if (world.rounds > 0) synth.bolt()
            shotFlashUntil = world.time + 0.6
            onShotFx()
        }
    }

    fun reload() {
        if (world.rounds < world.magazineSize) { world.reload(); synth.reload(); flash("RECARGANDO", Color.LTGRAY) }
    }

    fun turret(elev: Int, wind: Int) {
        world.elevClicks += elev
        world.windClicks += wind
        synth.click()
    }

    fun autoDial() {
        val s = solution ?: return
        val u = settings.turretUnit
        world.elevClicks = (s.elevationRad / u.clickRad).roundToInt()
        world.windClicks = (s.windageRad / u.clickRad).roundToInt()
        repeat(3) { synth.click() }
        flash("Torretas ajustadas a ${solutionRange.roundToInt()} m", Ui.ACCENT)
    }

    fun zeroTurrets() { world.elevClicks = 0; world.windClicks = 0; synth.click() }

    fun respawn() {
        world.spawnInitial(cam.azimuthDeg())
        flash("Blancos reubicados frente a vos", Color.LTGRAY)
    }

    private fun flash(text: String, color: Int, seconds: Double = 2.2) {
        message = text; messageColor = color; messageUntil = world.time + seconds
    }

    // ------------------------------------------------------------------ detecciones reales

    fun onDetections(batch: DetectionBatch) {
        if (cam.width < 10) return
        val saved = cam.orientation
        batch.orientation?.let { cam.orientation = it; cam.update() }
        val aspect = batch.imageAspect.toDouble()
        // Escalado FILL_CENTER de la vista previa (misma imagen recortada por el zoom).
        val scale = max(cam.width / aspect, cam.height / 1.0)
        fun sx(u: Float) = cam.width / 2 + (u - 0.5) * aspect * scale
        fun sy(v: Float) = cam.height / 2 + (v - 0.5) * scale
        val dets = batch.items.map { d ->
            val cx = sx((d.left + d.right) / 2); val cy = sy((d.top + d.bottom) / 2)
            val dir = cam.rayFromScreen(cx, cy)
            val l = cam.rayFromScreen(sx(d.left), cy); val r = cam.rayFromScreen(sx(d.right), cy)
            val t = cam.rayFromScreen(cx, sy(d.top)); val b = cam.rayFromScreen(cx, sy(d.bottom))
            val angW = acos((l dot r).coerceIn(-1.0, 1.0))
            val angH = acos((t dot b).coerceIn(-1.0, 1.0))
            val cut = d.top < 0.01f || d.bottom > 0.99f
            val realH = Detector.CLASSES[d.cocoName]?.second ?: 1.0
            val range = if (cut) Double.NaN else RangeEstimator.range(realH, angH)
            RealDetection(d.label, d.cocoName, dir, angW, angH, range)
        }
        cam.orientation = saved; cam.update()
        world.updateRealDetections(dets)
    }

    // ------------------------------------------------------------------ eventos

    private fun handleEvents() {
        for (e in world.events) {
            when (e) {
                is GameEvent.Hit -> {
                    val delay = e.distance / 343.0
                    synth.thud(delay, e.distance)
                    e.worldPos?.let { effects.add(WorldEffect(it, world.time, 0.5, WorldEffect.Kind.HIT, 0.5)) }
                    val zoneTxt = when (e.zone) {
                        Zone.HEAD -> "IMPACTO EN CABEZA"
                        Zone.VITAL -> if (e.human) "IMPACTO EN TORSO" else "IMPACTO VITAL"
                        Zone.BODY -> "IMPACTO"
                        else -> "IMPACTO LEVE"
                    }
                    val status = if (e.down) " · ABATIDO" else " · HERIDO, HUYE"
                    flash("$zoneTxt$status · ${e.distance.roundToInt()} m", if (e.down) Color.rgb(255, 90, 70) else Color.rgb(255, 200, 80))
                }
                is GameEvent.Impact -> {
                    effects.add(WorldEffect(e.worldPos, world.time, 1.2, WorldEffect.Kind.DUST, 1.4))
                    synth.dirt(e.distance / 343.0, e.distance)
                    flash("FALLO · ${e.distance.roundToInt()} m", Color.LTGRAY, 1.5)
                }
                is GameEvent.Steel -> {
                    effects.add(WorldEffect(e.worldPos, world.time, 0.35, WorldEffect.Kind.SPARK, 0.8))
                    synth.steel(e.distance / 343.0, e.distance)
                    flash("¡ACERO! · ${e.distance.roundToInt()} m", Color.rgb(140, 220, 255))
                }
                is GameEvent.NearMiss -> {
                    synth.crack()
                    flash("${e.label}: bala cerca, huye", Color.rgb(255, 200, 80), 1.5)
                }
                is GameEvent.RealImpact -> {
                    val p = cam.position + e.dir * e.distance
                    effects.add(WorldEffect(p, world.time, 1.2, WorldEffect.Kind.DUST, 1.4))
                    synth.dirt(e.distance / 343.0, e.distance)
                    flash("FALLO", Color.LTGRAY, 1.5)
                }
                is GameEvent.Fired -> {}
            }
        }
        world.events.clear()
    }

    // ------------------------------------------------------------------ dibujo

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        val radius = min(w, h) * 0.48f

        if (settings.mode == GameMode.VIRTUAL && settings.syntheticScene) renderer.drawSynthetic(c, cam)

        if (settings.mode == GameMode.VIRTUAL) {
            renderer.drawTargets(c, cam, world.targets)
            for (s in world.shots) if (!s.done) renderer.drawTracer(c, cam, s.tracer)
            drawVirtualLabels(c)
        } else {
            drawRealLabels(c)
        }
        renderer.drawEffects(c, cam, effects, world.time)
        drawMuzzleSmoke(c, w, h)

        if (!sensorsReady()) {
            hud.textSize = h * 0.04f; hud.textAlign = Paint.Align.CENTER
            c.drawText("Calibrando sensores… mové el celular en forma de 8", cx, cy - h * 0.2f, hud)
        }

        drawReticle(c, cx, cy, if (scopeCircle) radius else max(w, h))
        if (scopeCircle) drawScopeMask(c, cx, cy, radius, w, h)
        drawHud(c, w, h, cx, cy, radius)
    }

    private fun drawVirtualLabels(c: Canvas) {
        for (t in world.targets) {
            if (t.kind == TargetKind.STEEL) continue
            val top = t.pos + Vec3(0.0, 0.0, t.kind.height + 0.35)
            val s = cam.project(top) ?: continue
            if (s[0] < 0 || s[0] > width || s[1] < 0 || s[1] > height) continue
            val size = (height * 0.032f).coerceAtLeast(22f)
            val text = if (t.alive) t.kind.label else "${t.kind.label} · abatido"
            renderer.drawLabel(c, text, s[0].toFloat(), s[1].toFloat(), size, if (t.alive) Color.WHITE else Color.rgb(255, 120, 100))
        }
    }

    private fun drawRealLabels(c: Canvas) {
        for (tr in world.realTracks) {
            val p = cam.project(cam.position + tr.dir * 100.0) ?: continue
            val topY = p[1] - tr.angH / 2 * cam.focalPx - height * 0.015
            val size = (height * 0.034f).coerceAtLeast(22f)
            val hit = world.time < tr.hitUntil
            renderer.drawLabel(c, if (hit) tr.hitText else tr.label, p[0].toFloat(), topY.toFloat(), size,
                if (hit) Color.rgb(255, 110, 90) else Color.WHITE)
        }
    }

    private fun drawMuzzleSmoke(c: Canvas, w: Float, h: Float) {
        val left = shotFlashUntil - world.time
        if (left <= 0) return
        val a = (left / 0.6).toFloat()
        val bmp = muzzleSmoke
        if (bmp != null) {
            sprite.alpha = (200 * a).toInt()
            val sz = h * (1.4f - a * 0.6f)
            c.drawBitmap(bmp, null, RectF(w / 2 - sz, h - sz * 0.9f, w / 2 + sz, h + sz * 0.6f), sprite)
        }
    }

    /** Retícula de primer plano focal en MRAD (las marcas abarcan ángulos reales). */
    private fun drawReticle(c: Canvas, cx: Float, cy: Float, extent: Float) {
        val mil = cam.pxPerMil().toFloat()
        val color = if (thermal) Color.rgb(255, 60, 40) else Color.BLACK
        reticle.color = color; reticleFill.color = color; reticleText.color = color
        val thin = max(1.6f, height / 900f)
        c.save()
        // Postes gruesos exteriores
        reticle.strokeWidth = thin * 4
        val inner = 10 * mil
        if (inner < extent) {
            c.drawLine(cx - extent, cy, cx - inner, cy, reticle)
            c.drawLine(cx + inner, cy, cx + extent, cy, reticle)
            c.drawLine(cx, cy + inner, cx, cy + extent, reticle)
            c.drawLine(cx, cy - extent, cx, cy - inner, reticle)
        }
        // Cruz fina
        reticle.strokeWidth = thin
        val fine = min(inner, extent)
        c.drawLine(cx - fine, cy, cx + fine, cy, reticle)
        c.drawLine(cx, cy - fine, cx, cy + fine, reticle)
        // Marcas cada 1 mrad (y medias si hay espacio)
        reticleText.textSize = max(16f, min(mil * 0.7f, height * 0.03f))
        if (mil > 3f) {
            for (i in 1..10) {
                val d = i * mil
                val len = if (i % 2 == 0) mil * 0.5f else mil * 0.3f
                c.drawLine(cx + d, cy - len, cx + d, cy + len, reticle)
                c.drawLine(cx - d, cy - len, cx - d, cy + len, reticle)
                c.drawLine(cx - len, cy + d, cx + len, cy + d, reticle)
                c.drawLine(cx - len, cy - d, cx + len, cy - d, reticle)
                if (mil > 12f) {
                    val h2 = mil * 0.15f
                    c.drawLine(cx + d - mil / 2, cy - h2, cx + d - mil / 2, cy + h2, reticle)
                    c.drawLine(cx - d + mil / 2, cy - h2, cx - d + mil / 2, cy + h2, reticle)
                    c.drawLine(cx - h2, cy + d - mil / 2, cx + h2, cy + d - mil / 2, reticle)
                }
                if (i % 2 == 0 && mil > 14f) {
                    c.drawText("$i", cx + d, cy - len - 4, reticleText)
                    c.drawText("$i", cx - mil * 0.9f, cy + d + reticleText.textSize / 3, reticleText)
                }
            }
            // Árbol de navidad: puntos de compensación de viento bajo el centro
            if (mil > 8f) {
                for (row in 2..8 step 2) {
                    val y = cy + row * mil
                    val spread = row / 2
                    for (k in 1..spread) {
                        c.drawCircle(cx + k * mil, y, thin * 1.4f, reticleFill)
                        c.drawCircle(cx - k * mil, y, thin * 1.4f, reticleFill)
                    }
                }
            }
        }
        // Punto central
        c.drawCircle(cx, cy, thin * 1.6f, reticleFill)
        c.restore()

        // Marca de compensación sugerida (asistencia): diferencia entre solución y torretas.
        val s = solution
        if (settings.assist && s != null && s.valid) {
            val u = settings.turretUnit
            val holdUp = s.elevationRad - world.elevClicks * u.clickRad
            val holdRight = s.windageRad - world.windClicks * u.clickRad
            if (abs(holdUp) > 0.00008 || abs(holdRight) > 0.00008) {
                // Para pegar más arriba, se apunta más abajo: el punto de impacto queda bajo el centro.
                val px = cx - (holdRight * 1000).toFloat() * mil
                val py = cy + (holdUp * 1000).toFloat() * mil
                hudStroke.color = Color.rgb(255, 200, 40); hudStroke.strokeWidth = thin * 1.5f
                path.reset()
                val d = max(8f, mil * 0.35f)
                path.moveTo(px, py - d); path.lineTo(px + d, py); path.lineTo(px, py + d); path.lineTo(px - d, py); path.close()
                c.drawPath(path, hudStroke)
            }
        }
    }

    private fun drawScopeMask(c: Canvas, cx: Float, cy: Float, r: Float, w: Float, h: Float) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        path.addCircle(cx, cy, r, Path.Direction.CW)
        c.drawPath(path, mask)
        // Sombra interna del tubo (viñeta)
        vignette.shader = RadialGradient(cx, cy, r, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(120, 0, 0, 0), Color.argb(255, 0, 0, 0)),
            floatArrayOf(0f, 0.82f, 0.95f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r + 1, vignette)
        hudStroke.color = Color.rgb(30, 30, 30); hudStroke.strokeWidth = 6f
        c.drawCircle(cx, cy, r, hudStroke)
    }

    private fun clockOf(fromDeg: Double, aimDeg: Double): Int {
        var rel = (fromDeg - aimDeg) % 360; if (rel < 0) rel += 360
        val hr = (rel / 30).roundToInt() % 12
        return if (hr == 0) 12 else hr
    }

    private fun drawHud(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, r: Float) {
        val ts = h * 0.034f
        hud.textSize = ts
        val u = settings.turretUnit
        val unitTxt = if (u == TurretUnit.MRAD) "MRAD" else "MOA"
        val elevVal = if (u == TurretUnit.MRAD) world.elevClicks * 0.1 else world.elevClicks * 0.25
        val windVal = if (u == TurretUnit.MRAD) world.windClicks * 0.1 else world.windClicks * 0.25

        // Columna izquierda (fuera del círculo si hay lugar)
        val lx = ts * 0.8f
        var y = ts * 1.6f
        hud.textAlign = Paint.Align.LEFT
        fun line(t: String, color: Int = Color.rgb(230, 230, 220)) { hud.color = color; c.drawText(t, lx, y, hud); y += ts * 1.35f }
        line(world.cartridge.name, Ui.ACCENT)
        line("Cero ${settings.zeroRange.roundToInt()} m")
        line("ELEV  %+.${if (u == TurretUnit.MRAD) 1 else 2}f %s".format(elevVal, unitTxt))
        line("DERIV %+.${if (u == TurretUnit.MRAD) 1 else 2}f %s".format(windVal, unitTxt))
        val range = lastRange ?: world.rangeUnderReticle(cam)
        line("DIST  ${range?.let { "${it.roundToInt()} m" } ?: "---"}")
        val (cross, _) = world.wind.relative(cam.azimuthDeg())
        line("VIENTO %.1f m/s · %d h".format(world.wind.speed, clockOf(world.wind.fromDeg, cam.azimuthDeg())))
        line("  cruzado %.1f m/s %s".format(abs(cross), if (cross > 0.05) "→" else if (cross < -0.05) "←" else ""))
        if (settings.assist) {
            val s = solution
            if (s != null && s.valid) {
                val k = if (u == TurretUnit.MRAD) 1000.0 else 1.0
                val e = if (u == TurretUnit.MRAD) s.elevationMil else s.elevationMoa
                val wv = if (u == TurretUnit.MRAD) s.windageMil else s.windageMoa
                line("SOLUCIÓN %d m".format(solutionRange.roundToInt()), Color.rgb(255, 200, 40))
                line("  subir %.${if (k > 1) 1 else 2}f · %s %.${if (k > 1) 1 else 2}f".format(e, if (wv >= 0) "der" else "izq", abs(wv)), Color.rgb(255, 200, 40))
                line("  vuelo %.2f s · %d m/s".format(s.timeOfFlight, s.velocity.roundToInt()), Color.rgb(255, 200, 40))
            }
        }

        // Columna derecha
        val rx = w - lx
        y = ts * 1.6f
        hud.textAlign = Paint.Align.RIGHT
        fun rline(t: String, color: Int = Color.rgb(230, 230, 220)) { hud.color = color; c.drawText(t, rx, y, hud); y += ts * 1.35f }
        rline("PUNTOS ${world.score}", Ui.ACCENT)
        rline("Impactos ${world.hits}/${world.shotsFired}")
        rline("Abatidos ${world.kills}")
        rline("%.1fx".format(magnification) + if (abs(effectiveMag - magnification) > 0.3) " (real %.1fx)".format(effectiveMag) else "")
        rline("Rumbo %03d°".format(cam.azimuthDeg().roundToInt() % 360))
        rline("Elev %+.1f°".format(cam.elevationDeg()))
        val reloading = world.time < world.reloadUntil
        val bolt = world.time < world.boltReadyAt
        rline(if (reloading) "RECARGANDO…" else if (bolt) "CERROJO…" else "LISTO", if (reloading || bolt) Color.rgb(255, 180, 60) else Color.rgb(120, 230, 120))
        // Munición
        val bw = ts * 0.35f; val bh = ts * 1.1f
        for (i in 0 until world.magazineSize) {
            hudFill.color = if (i < world.rounds) Color.rgb(212, 170, 72) else Color.argb(90, 200, 200, 200)
            val x = rx - (world.magazineSize - i) * bw * 1.8f
            c.drawRoundRect(RectF(x, y - bh * 0.7f, x + bw, y + bh * 0.3f), bw / 2, bw / 2, hudFill)
        }
        y += ts * 1.35f
        detectorError?.let { rline("Detector: $it", Color.rgb(255, 120, 100)) }

        // Nivel de burbuja (cant) bajo la retícula
        val cant = cam.cantDeg()
        val by = if (scopeCircle) cy + r * 0.82f else h * 0.9f
        val bwid = r * 0.35f
        hudStroke.color = Color.argb(200, 230, 230, 220); hudStroke.strokeWidth = 3f
        c.drawLine(cx - bwid, by, cx + bwid, by, hudStroke)
        c.drawLine(cx, by - 8, cx, by + 8, hudStroke)
        val bubbleX = cx + (cant / 10.0).coerceIn(-1.0, 1.0).toFloat() * bwid
        hudFill.color = if (abs(cant) < 1.0) Color.rgb(90, 230, 90) else Color.rgb(255, 170, 40)
        c.drawCircle(bubbleX, by, ts * 0.32f, hudFill)

        // Flecha de viento relativa a la mira
        val ax = if (scopeCircle) cx + r * 0.72f else w * 0.88f
        val ay = if (scopeCircle) cy + r * 0.72f else h * 0.82f
        val toRel = Math.toRadians(world.wind.fromDeg + 180 - cam.azimuthDeg())
        val len = ts * 1.3f
        val ex = ax + (kotlin.math.sin(toRel) * len).toFloat()
        val ey = ay - (kotlin.math.cos(toRel) * len).toFloat()
        hudStroke.color = Color.rgb(140, 210, 255); hudStroke.strokeWidth = 4f
        c.drawLine(ax - (ex - ax), ay - (ey - ay), ex, ey, hudStroke)
        c.drawCircle(ex, ey, 6f, hudStroke)

        // Mensaje central
        if (world.time < messageUntil) {
            hud.textAlign = Paint.Align.CENTER
            hud.textSize = h * 0.045f
            hud.color = messageColor
            c.drawText(message, cx, cy + r * 0.55f, hud)
        }
    }
}
