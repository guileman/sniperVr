package com.guileman.snipervr.game

import com.guileman.snipervr.core.Vec3
import kotlin.math.cos
import kotlin.math.sin

/** Zona de impacto de cada parte del modelo. */
enum class Zone { HEAD, VITAL, BODY, LIMB, STEEL }

/**
 * Parte low-poly: una caja con medidas reales en metros, en coordenadas locales del modelo
 * (x = derecha, y = adelante, z = arriba, origen en el suelo entre los pies).
 */
class Part(
    val center: Vec3,
    val size: Vec3,
    val color: Int,
    val zone: Zone,
    /** Pivote de la oscilación (cadera, hombro...). */
    val pivot: Vec3 = center,
    /** Fase y amplitud (rad) del balanceo alrededor del eje X local al caminar. */
    val swingPhase: Double = 0.0,
    val swingAmp: Double = 0.0,
    /** Inclinación fija (rad) alrededor del eje X local en el pivote (cuellos, cuernos). */
    val pitch: Double = 0.0,
    /** Inclinación fija (rad) alrededor del eje Y local (cuernos abiertos). */
    val roll: Double = 0.0
)

/** Caja orientada en el mundo (resultado de transformar una parte). */
class WorldBox(val corners: Array<Vec3>, val color: Int, val zone: Zone, val center: Vec3, val axes: Array<Vec3>, val half: Vec3)

enum class TargetKind(val label: String, val isHuman: Boolean, val walkSpeed: Double, val runSpeed: Double, val height: Double) {
    HUMAN("Persona", true, 1.3, 4.5, 1.75),
    DEER("Ciervo", false, 1.1, 9.0, 1.6),
    BOAR("Jabalí", false, 0.9, 7.0, 0.9),
    GUANACO("Guanaco", false, 1.2, 10.0, 1.8),
    STEEL("Placa de acero", false, 0.0, 0.0, 1.3)
}

object Models {
    private fun c(hex: Long) = hex.toInt()

    private val SKIN = c(0xFFC8A07E)
    private val SKIN2 = c(0xFF8D6346)

    fun build(kind: TargetKind, variant: Int): List<Part> = when (kind) {
        TargetKind.HUMAN -> human(variant)
        TargetKind.DEER -> deer()
        TargetKind.BOAR -> boar()
        TargetKind.GUANACO -> guanaco()
        TargetKind.STEEL -> steel()
    }

    private fun human(variant: Int): List<Part> {
        val shirts = intArrayOf(c(0xFF556B2F), c(0xFF6B4F3A), c(0xFF34495E), c(0xFF7A7A6A), c(0xFF8B2E2E))
        val pants = intArrayOf(c(0xFF3B3B30), c(0xFF2F3B4C), c(0xFF4A3F2E))
        val shirt = shirts[variant % shirts.size]
        val pant = pants[variant % pants.size]
        val skin = if (variant % 2 == 0) SKIN else SKIN2
        val hip = 0.88
        val sh = 1.42
        return listOf(
            Part(Vec3(-0.10, 0.0, hip / 2), Vec3(0.15, 0.17, hip), pant, Zone.LIMB, Vec3(-0.10, 0.0, hip), 0.0, 0.45),
            Part(Vec3(0.10, 0.0, hip / 2), Vec3(0.15, 0.17, hip), pant, Zone.LIMB, Vec3(0.10, 0.0, hip), Math.PI, 0.45),
            Part(Vec3(0.0, 0.0, hip + 0.12), Vec3(0.36, 0.21, 0.24), pant, Zone.BODY),
            Part(Vec3(0.0, 0.0, 1.19), Vec3(0.40, 0.23, 0.36), shirt, Zone.VITAL),
            Part(Vec3(-0.26, 0.0, sh - 0.32), Vec3(0.10, 0.11, 0.64), shirt, Zone.LIMB, Vec3(-0.26, 0.0, sh), Math.PI, 0.4),
            Part(Vec3(0.26, 0.0, sh - 0.32), Vec3(0.10, 0.11, 0.64), shirt, Zone.LIMB, Vec3(0.26, 0.0, sh), 0.0, 0.4),
            Part(Vec3(0.0, 0.0, 1.47), Vec3(0.10, 0.10, 0.08), skin, Zone.HEAD),
            Part(Vec3(0.0, 0.01, 1.62), Vec3(0.19, 0.22, 0.24), skin, Zone.HEAD),
            Part(Vec3(0.0, -0.01, 1.745), Vec3(0.21, 0.24, 0.05), shirt, Zone.HEAD)
        )
    }

    private fun quadruped(
        bodyLen: Double, bodyW: Double, bodyH: Double, legLen: Double,
        neckLen: Double, neckPitch: Double, headLen: Double, headH: Double,
        color: Int, belly: Int, legColor: Int, extra: List<Part> = emptyList()
    ): List<Part> {
        val bz = legLen + bodyH / 2
        val fy = bodyLen / 2 - 0.12
        val ry = -bodyLen / 2 + 0.12
        val lx = bodyW / 2 - 0.06
        val legs = listOf(
            Part(Vec3(-lx, fy, legLen / 2), Vec3(0.08, 0.09, legLen), legColor, Zone.LIMB, Vec3(-lx, fy, legLen), 0.0, 0.5),
            Part(Vec3(lx, fy, legLen / 2), Vec3(0.08, 0.09, legLen), legColor, Zone.LIMB, Vec3(lx, fy, legLen), Math.PI, 0.5),
            Part(Vec3(-lx, ry, legLen / 2), Vec3(0.09, 0.10, legLen), legColor, Zone.LIMB, Vec3(-lx, ry, legLen), Math.PI, 0.5),
            Part(Vec3(lx, ry, legLen / 2), Vec3(0.09, 0.10, legLen), legColor, Zone.LIMB, Vec3(lx, ry, legLen), 0.0, 0.5)
        )
        // Zona vital (corazón/pulmones): tercio delantero del cuerpo, detrás de la paleta.
        val vitalLen = bodyLen * 0.35
        val chest = Part(Vec3(0.0, bodyLen / 2 - vitalLen / 2 - 0.05, bz), Vec3(bodyW, vitalLen, bodyH), color, Zone.VITAL)
        val rear = Part(Vec3(0.0, -(vitalLen + 0.05) / 2, bz), Vec3(bodyW * 0.98, bodyLen - vitalLen - 0.05, bodyH * 0.97), color, Zone.BODY)
        val front = Part(Vec3(0.0, bodyLen / 2 - 0.025, bz), Vec3(bodyW * 0.9, 0.05, bodyH * 0.92), color, Zone.BODY)
        val bellyPart = Part(Vec3(0.0, 0.0, legLen + 0.03), Vec3(bodyW * 0.8, bodyLen * 0.8, 0.06), belly, Zone.BODY)
        val neckBase = Vec3(0.0, bodyLen / 2 - 0.05, bz + bodyH * 0.25)
        val neck = Part(
            neckBase + Vec3(0.0, 0.0, neckLen / 2), Vec3(0.16, 0.18, neckLen), color, Zone.VITAL,
            pivot = neckBase, pitch = neckPitch
        )
        val headPos = neckBase + Vec3(0.0, sin(neckPitch) * neckLen, cos(neckPitch) * neckLen)
        val head = Part(headPos + Vec3(0.0, headLen / 2 - 0.05, 0.0), Vec3(0.16, headLen, headH), color, Zone.HEAD)
        return legs + listOf(chest, rear, front, bellyPart, neck, head) + extra.map {
            Part(it.center + headPos, it.size, it.color, it.zone, it.pivot + headPos, it.swingPhase, it.swingAmp, it.pitch, it.roll)
        }
    }

    private fun deer(): List<Part> {
        val antler = c(0xFFD9C9A3)
        val antlers = listOf(
            Part(Vec3(-0.07, 0.0, 0.25), Vec3(0.03, 0.03, 0.40), antler, Zone.HEAD, Vec3(-0.07, 0.0, 0.07), roll = -0.45),
            Part(Vec3(0.07, 0.0, 0.25), Vec3(0.03, 0.03, 0.40), antler, Zone.HEAD, Vec3(0.07, 0.0, 0.07), roll = 0.45),
            Part(Vec3(-0.14, 0.08, 0.30), Vec3(0.025, 0.025, 0.2), antler, Zone.HEAD, Vec3(-0.14, 0.0, 0.22), pitch = 0.6),
            Part(Vec3(0.14, 0.08, 0.30), Vec3(0.025, 0.025, 0.2), antler, Zone.HEAD, Vec3(0.14, 0.0, 0.22), pitch = 0.6)
        )
        return quadruped(1.40, 0.40, 0.52, 0.82, 0.55, 0.55, 0.38, 0.18,
            c(0xFF8B5A2B), c(0xFFD2B48C), c(0xFF6B4423), antlers)
    }

    private fun boar(): List<Part> {
        val tusk = c(0xFFEEE8D5)
        val snout = listOf(
            Part(Vec3(0.0, 0.33, -0.05), Vec3(0.10, 0.12, 0.10), c(0xFF4A3B33), Zone.HEAD),
            Part(Vec3(-0.06, 0.32, 0.0), Vec3(0.02, 0.06, 0.02), tusk, Zone.HEAD),
            Part(Vec3(0.06, 0.32, 0.0), Vec3(0.02, 0.06, 0.02), tusk, Zone.HEAD)
        )
        return quadruped(1.15, 0.42, 0.50, 0.38, 0.18, 1.2, 0.40, 0.26,
            c(0xFF3E342E), c(0xFF4E443C), c(0xFF2A231F), snout)
    }

    private fun guanaco(): List<Part> {
        val ears = listOf(
            Part(Vec3(-0.05, -0.02, 0.12), Vec3(0.03, 0.03, 0.10), c(0xFF9C6B3C), Zone.HEAD),
            Part(Vec3(0.05, -0.02, 0.12), Vec3(0.03, 0.03, 0.10), c(0xFF9C6B3C), Zone.HEAD)
        )
        return quadruped(1.30, 0.40, 0.50, 0.85, 0.75, 0.18, 0.30, 0.16,
            c(0xFFB5814A), c(0xFFF0E6D6), c(0xFF9C6B3C), ears)
    }

    private fun steel(): List<Part> {
        val post = c(0xFF5A4A3A)
        return listOf(
            Part(Vec3(-0.35, 0.0, 0.6), Vec3(0.06, 0.06, 1.2), post, Zone.LIMB),
            Part(Vec3(0.35, 0.0, 0.6), Vec3(0.06, 0.06, 1.2), post, Zone.LIMB),
            Part(Vec3(0.0, 0.0, 1.2), Vec3(0.76, 0.06, 0.06), post, Zone.LIMB),
            Part(Vec3(0.0, 0.0, 0.95), Vec3(0.45, 0.025, 0.45), c(0xFFE8E8E8), Zone.STEEL)
        )
    }

    /** Rota un punto alrededor de un pivote: primero roll (eje Y), luego pitch (eje X). */
    private fun rotLocal(p: Vec3, pivot: Vec3, pitch: Double, roll: Double): Vec3 {
        var d = p - pivot
        if (roll != 0.0) {
            val cr = cos(roll); val sr = sin(roll)
            d = Vec3(d.x * cr + d.z * sr, d.y, -d.x * sr + d.z * cr)
        }
        if (pitch != 0.0) {
            val cp = cos(pitch); val sp = sin(pitch)
            // Rotación alrededor de X: positivo inclina +Z hacia +Y (adelante).
            d = Vec3(d.x, d.y * cp + d.z * sp, -d.y * sp + d.z * cp)
        }
        return pivot + d
    }

    /**
     * Pasa las partes a cajas en el mundo.
     * @param heading rumbo hacia donde mira el modelo (rad, 0 = Norte, horario)
     * @param walkPhase fase de la animación de caminar
     * @param fall 0..1 caída lateral (blanco abatido)
     */
    fun toWorld(parts: List<Part>, pos: Vec3, heading: Double, walkPhase: Double, walkAmount: Double, fall: Double): List<WorldBox> {
        val ch = cos(heading); val sh = sin(heading)
        val fallAng = fall * Math.PI / 2 * 0.95
        val cf = cos(fallAng); val sf = sin(fallAng)
        fun toW(l: Vec3): Vec3 {
            // Caída: giro alrededor del eje Y local (de costado).
            val f = Vec3(l.x * cf + l.z * sf, l.y, -l.x * sf + l.z * cf)
            // Rumbo: local y (adelante) -> (sin h, cos h), local x (derecha) -> (cos h, -sin h).
            return Vec3(pos.x + f.x * ch + f.y * sh, pos.y - f.x * sh + f.y * ch, pos.z + f.z)
        }
        val out = ArrayList<WorldBox>(parts.size)
        for (p in parts) {
            val swing = p.swingAmp * walkAmount * sin(walkPhase + p.swingPhase)
            val hx = p.size.x / 2; val hy = p.size.y / 2; val hz = p.size.z / 2
            val local = arrayOf(
                Vec3(-hx, -hy, -hz), Vec3(hx, -hy, -hz), Vec3(hx, hy, -hz), Vec3(-hx, hy, -hz),
                Vec3(-hx, -hy, hz), Vec3(hx, -hy, hz), Vec3(hx, hy, hz), Vec3(-hx, hy, hz)
            )
            val corners = Array(8) { i ->
                val lp = p.center + local[i]
                toW(rotLocal(lp, p.pivot, p.pitch + swing, p.roll))
            }
            val center = toW(rotLocal(p.center, p.pivot, p.pitch + swing, p.roll))
            val ax = (corners[1] - corners[0]).normalized()
            val ay = (corners[3] - corners[0]).normalized()
            val az = (corners[4] - corners[0]).normalized()
            out.add(WorldBox(corners, p.color, p.zone, center, arrayOf(ax, ay, az), Vec3(hx, hy, hz)))
        }
        return out
    }

    /** Las 6 caras de una caja (índices de esquinas en sentido antihorario visto desde fuera). */
    val FACES = arrayOf(
        intArrayOf(0, 3, 2, 1), // abajo
        intArrayOf(4, 5, 6, 7), // arriba
        intArrayOf(0, 1, 5, 4), // atrás (-y)
        intArrayOf(2, 3, 7, 6), // adelante (+y)
        intArrayOf(3, 0, 4, 7), // izquierda (-x)
        intArrayOf(1, 2, 6, 5)  // derecha (+x)
    )

    /**
     * Intersección segmento-caja orientada (método de slabs). Devuelve fracción [0,1] o null.
     */
    fun segmentHitsBox(a: Vec3, b: Vec3, box: WorldBox, margin: Double = 0.0): Double? {
        val d = b - a
        val rel = a - box.center
        var tMin = 0.0; var tMax = 1.0
        val halves = doubleArrayOf(box.half.x + margin, box.half.y + margin, box.half.z + margin)
        for (i in 0..2) {
            val axis = box.axes[i]
            val e = axis dot rel
            val f = axis dot d
            val h = halves[i]
            if (kotlin.math.abs(f) < 1e-12) {
                if (e < -h || e > h) return null
            } else {
                var t1 = (-h - e) / f; var t2 = (h - e) / f
                if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
                if (t1 > tMin) tMin = t1
                if (t2 < tMax) tMax = t2
                if (tMin > tMax) return null
            }
        }
        return tMin
    }
}
