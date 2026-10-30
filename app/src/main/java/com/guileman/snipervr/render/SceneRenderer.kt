package com.guileman.snipervr.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.guileman.snipervr.core.CameraModel
import com.guileman.snipervr.core.Vec3
import com.guileman.snipervr.game.Models
import com.guileman.snipervr.game.VirtualTarget
import com.guileman.snipervr.game.WorldBox
import com.guileman.snipervr.game.Zone
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Efecto anclado al mundo (polvo de impacto, chispa en el acero). */
class WorldEffect(val pos: Vec3, val start: Double, val duration: Double, val kind: Kind, val sizeM: Double) {
    enum class Kind { DUST, SPARK, HIT }
}

/**
 * Renderizador 3D propio sobre Canvas: polígonos con iluminación simple, bruma por distancia y
 * orden de pintor (lo más lejano se pinta primero).
 */
class SceneRenderer(private val dustSheet: Bitmap?, private val sparkSheet: Bitmap?) {

    private class Poly(val xs: FloatArray, val ys: FloatArray, var depth: Double, var color: Int)

    private val polys = ArrayList<Poly>(512)
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sprite = Paint(Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val sun = Vec3(0.45, -0.35, 0.82).normalized()

    var thermal = false
    /** Color de la bruma atmosférica. */
    var hazeColor = Color.rgb(196, 206, 214)

    // ------------------------------------------------------------------ escenario sintético

    fun drawSynthetic(c: Canvas, cam: CameraModel) {
        val w = cam.width.toFloat(); val h = cam.height.toFloat()
        val skyCol = if (thermal) Color.rgb(32, 32, 32) else Color.rgb(150, 182, 214)
        val groundCol = if (thermal) Color.rgb(78, 78, 78) else Color.rgb(118, 120, 72)
        // Horizonte: dos puntos muy lejanos a la altura del ojo, a ambos lados de la mira.
        val fwdH = Vec3(cam.forward.x, cam.forward.y, 0.0)
        if (fwdH.length() < 1e-3) {
            c.drawColor(if (cam.forward.z > 0) skyCol else groundCol)
        } else {
            val f = fwdH.normalized()
            val r = Vec3(f.y, -f.x, 0.0)
            val eye = cam.position
            val pa = cam.project(eye + (f - r * 0.3) * 1e5)
            val pb = cam.project(eye + (f + r * 0.3) * 1e5)
            val ps = cam.project(eye + f * 1e5 + Vec3.UP * 3e4)
            if (pa == null || pb == null) {
                c.drawColor(if (cam.forward.z > 0) skyCol else groundCol)
            } else {
                var dx = (pb[0] - pa[0]).toFloat(); var dy = (pb[1] - pa[1]).toFloat()
                val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                dx /= len; dy /= len
                var nx = -dy; var ny = dx
                if (ps != null && ((ps[0] - pa[0]) * nx + (ps[1] - pa[1]) * ny) < 0) { nx = -nx; ny = -ny }
                val big = (w + h) * 4
                val ax = pa[0].toFloat() - dx * big; val ay = pa[1].toFloat() - dy * big
                val bx = pa[0].toFloat() + dx * big; val by = pa[1].toFloat() + dy * big
                c.drawColor(groundCol)
                path.reset()
                path.moveTo(ax, ay); path.lineTo(bx, by)
                path.lineTo(bx + nx * big, by + ny * big); path.lineTo(ax + nx * big, ay + ny * big); path.close()
                val hx = pa[0].toFloat(); val hy = pa[1].toFloat()
                fill.shader = LinearGradient(hx, hy, hx + nx * h, hy + ny * h,
                    if (thermal) Color.rgb(48, 48, 48) else Color.rgb(212, 222, 230), skyCol, Shader.TileMode.CLAMP)
                c.drawPath(path, fill)
                fill.shader = null
            }
        }
        // Matas y piedras como referencia de movimiento (fijas al mundo)
        val rnd = java.util.Random(1234)
        for (i in 0 until 260) {
            val az = rnd.nextDouble() * Math.PI * 2
            val r = 20 + rnd.nextDouble() * 1200
            val p = Vec3(sin(az) * r, cos(az) * r, 0.0)
            val s = cam.project(p) ?: continue
            val sizePx = (0.6 + rnd.nextDouble()) * cam.focalPx / s[2]
            if (sizePx < 0.6 || s[0] < -50 || s[0] > w + 50 || s[1] < -50 || s[1] > h + 50) continue
            val haze = 1 - exp(-s[2] / 1800)
            fill.color = blend(if (thermal) Color.rgb(70, 70, 70) else Color.rgb(70, 86, 40), hazeColor, haze * 0.8)
            c.drawOval(RectF((s[0] - sizePx).toFloat(), (s[1] - sizePx * 0.6).toFloat(), (s[0] + sizePx).toFloat(), s[1].toFloat()), fill)
        }
    }

    // ------------------------------------------------------------------ blancos 3D

    fun drawTargets(c: Canvas, cam: CameraModel, targets: List<VirtualTarget>) {
        polys.clear()
        for (t in targets) {
            // Descartar rápido lo que está detrás o muy fuera de cuadro.
            val cc = cam.toCamera(t.pos + Vec3(0.0, 0.0, 1.0))
            if (cc.z < 1.0) continue
            val sx = cam.width / 2 + cam.focalPx * cc.x / cc.z
            val sy = cam.height / 2 - cam.focalPx * cc.y / cc.z
            val margin = cam.focalPx * 3.0 / cc.z + 50
            if (sx < -margin || sx > cam.width + margin || sy < -margin || sy > cam.height + margin) continue
            for (box in t.boxes) addBox(cam, box, t.alive)
        }
        polys.sortByDescending { it.depth }
        for (p in polys) {
            path.reset()
            path.moveTo(p.xs[0], p.ys[0])
            for (i in 1 until p.xs.size) path.lineTo(p.xs[i], p.ys[i])
            path.close()
            fill.color = p.color
            c.drawPath(path, fill)
            edge.color = blend(p.color, Color.BLACK, 0.25)
            c.drawPath(path, edge)
        }
    }

    private fun addBox(cam: CameraModel, box: WorldBox, alive: Boolean) {
        val camC = Array(8) { cam.toCamera(box.corners[it]) }
        for (f in Models.FACES) {
            var cx = 0.0; var cy = 0.0; var cz = 0.0
            for (i in f) { val w = box.corners[i]; cx += w.x; cy += w.y; cz += w.z }
            val fc = Vec3(cx / 4, cy / 4, cz / 4)
            val n = (fc - box.center).normalized()
            if (n dot (cam.position - fc) <= 0) continue // cara trasera
            val xs = FloatArray(4); val ys = FloatArray(4)
            var depth = 0.0
            var ok = true
            for ((k, i) in f.withIndex()) {
                val s = cam.cameraToScreen(camC[i], 0.2)
                if (s == null) { ok = false; break }
                xs[k] = s[0].toFloat(); ys[k] = s[1].toFloat(); depth += s[2]
            }
            if (!ok) continue
            depth /= 4
            val light = 0.42 + 0.58 * max(0.0, n dot sun)
            val color = if (thermal) {
                // Visor térmico: el cuerpo caliente se ve blanco; se enfría al caer.
                val heat = if (box.zone == Zone.STEEL) 0.35 else if (alive) 0.95 else 0.75
                val v = (255 * heat * (0.85 + 0.15 * light)).toInt().coerceIn(0, 255)
                Color.rgb(v, v, v)
            } else {
                val base = shade(box.color, light)
                blend(base, hazeColor, 1 - exp(-depth / 2600))
            }
            polys.add(Poly(xs, ys, depth, color))
        }
    }

    // ------------------------------------------------------------------ efectos

    fun drawEffects(c: Canvas, cam: CameraModel, effects: List<WorldEffect>, now: Double) {
        for (e in effects) {
            val age = (now - e.start) / e.duration
            if (age < 0 || age > 1) continue
            val s = cam.project(e.pos) ?: continue
            val sizePx = (e.sizeM * cam.focalPx / s[2]).toFloat().coerceAtLeast(6f)
            val sheet = if (e.kind == WorldEffect.Kind.SPARK) sparkSheet else dustSheet
            if (sheet != null) {
                val frames = sheet.width / sheet.height
                val fi = (age * frames).toInt().coerceIn(0, frames - 1)
                val fw = sheet.height
                val src = Rect(fi * fw, 0, (fi + 1) * fw, fw)
                val grow = if (e.kind == WorldEffect.Kind.DUST) (0.6 + age * 0.9).toFloat() else 1f
                val half = sizePx * grow
                val dst = RectF(s[0].toFloat() - half, s[1].toFloat() - half * 1.6f, s[0].toFloat() + half, s[1].toFloat() + half * 0.4f)
                sprite.alpha = (255 * (1 - age * age)).toInt()
                c.drawBitmap(sheet, src, dst, sprite)
            } else {
                fill.color = Color.argb((200 * (1 - age)).toInt(), 180, 160, 120)
                c.drawCircle(s[0].toFloat(), s[1].toFloat(), sizePx * (0.5f + age.toFloat()), fill)
            }
        }
    }

    /** Estela del proyectil (trazador visible por la mira). */
    fun drawTracer(c: Canvas, cam: CameraModel, pts: List<Vec3>) {
        if (pts.size < 2) return
        var prev: DoubleArray? = null
        for ((i, p) in pts.withIndex()) {
            val s = cam.project(p)
            if (s != null && prev != null) {
                val a = i.toFloat() / pts.size
                line.strokeWidth = max(1.5f, (0.08 * cam.focalPx / s[2]).toFloat())
                line.color = Color.argb((140 * a).toInt(), 255, 240, 200)
                c.drawLine(prev[0].toFloat(), prev[1].toFloat(), s[0].toFloat(), s[1].toFloat(), line)
            }
            prev = s
        }
    }

    /** Etiqueta de lo detectado: sólo texto, sin recuadros ni porcentajes. */
    fun drawLabel(c: Canvas, text: String, x: Float, y: Float, sizePx: Float, color: Int = Color.WHITE) {
        labelPaint.textSize = sizePx
        labelPaint.color = color
        c.drawText(text, x, y, labelPaint)
    }

    companion object {
        fun shade(color: Int, k: Double): Int {
            val r = (Color.red(color) * k).toInt().coerceIn(0, 255)
            val g = (Color.green(color) * k).toInt().coerceIn(0, 255)
            val b = (Color.blue(color) * k).toInt().coerceIn(0, 255)
            return Color.rgb(r, g, b)
        }

        fun blend(a: Int, b: Int, t: Double): Int {
            val k = t.coerceIn(0.0, 1.0)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
            )
        }

        @Suppress("unused")
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * min(1f, max(0f, t))
    }
}
