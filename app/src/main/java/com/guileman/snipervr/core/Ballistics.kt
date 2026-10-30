package com.guileman.snipervr.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Cartucho: datos reales publicados por los fabricantes (aprox.). */
data class Cartridge(
    val name: String,
    val muzzleVelocity: Double, // m/s
    val bcG7: Double,           // lb/in², modelo G7
    val massGr: Double,         // granos
    val diameterIn: Double,     // pulgadas
    val lengthIn: Double,       // largo de la punta, pulgadas
    val twistIn: Double         // paso de estría, pulgadas por vuelta (derecha)
) {
    companion object {
        val PRESETS = listOf(
            Cartridge(".308 Win 175gr SMK", 792.0, 0.243, 175.0, 0.308, 1.240, 10.0),
            Cartridge("6.5 Creedmoor 140gr ELD-M", 823.0, 0.326, 140.0, 0.264, 1.400, 8.0),
            Cartridge(".338 Lapua 300gr SMK", 838.0, 0.383, 300.0, 0.338, 1.700, 10.0),
            Cartridge(".30-06 180gr caza", 820.0, 0.226, 180.0, 0.308, 1.250, 10.0),
            Cartridge(".223 Rem 77gr OTM", 838.0, 0.190, 77.0, 0.224, 0.995, 8.0),
            Cartridge(".50 BMG 750gr A-MAX", 860.0, 0.520, 750.0, 0.510, 2.310, 15.0)
        )
    }
}

/** Atmósfera: densidad del aire y velocidad del sonido. */
data class Atmosphere(
    val temperatureC: Double = 15.0,
    val altitudeM: Double = 0.0,
    val latitudeDeg: Double = -34.6
) {
    val pressurePa: Double get() = 101325.0 * (1 - 2.25577e-5 * altitudeM).pow(5.25588)
    val density: Double get() = pressurePa / (287.05 * (temperatureC + 273.15))
    val speedOfSound: Double get() = 331.3 * sqrt(1 + temperatureC / 273.15)
}

/** Tabla de arrastre estándar G7 (Mach -> Cd). */
object G7 {
    private val mach = doubleArrayOf(
        0.0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70,
        0.725, 0.75, 0.775, 0.80, 0.825, 0.85, 0.875, 0.90, 0.925, 0.95, 0.975, 1.0, 1.025, 1.05,
        1.075, 1.10, 1.125, 1.15, 1.20, 1.25, 1.30, 1.35, 1.40, 1.50, 1.55, 1.60, 1.65, 1.70,
        1.75, 1.80, 1.85, 1.90, 1.95, 2.00, 2.05, 2.10, 2.15, 2.20, 2.25, 2.30, 2.35, 2.40,
        2.45, 2.50, 2.55, 2.60, 2.65, 2.70, 2.75, 2.80, 2.85, 2.90, 2.95, 3.00, 3.10, 3.20,
        3.30, 3.40, 3.50, 3.60, 3.70, 3.80, 3.90, 4.00
    )
    private val cd = doubleArrayOf(
        0.1198, 0.1197, 0.1196, 0.1194, 0.1193, 0.1194, 0.1194, 0.1194, 0.1193, 0.1193, 0.1194,
        0.1193, 0.1194, 0.1197, 0.1202, 0.1207, 0.1215, 0.1226, 0.1242, 0.1266, 0.1306, 0.1368,
        0.1464, 0.1660, 0.2054, 0.2993, 0.3803, 0.4015, 0.4043, 0.4034, 0.4014, 0.3987, 0.3955,
        0.3884, 0.3810, 0.3732, 0.3657, 0.3580, 0.3440, 0.3376, 0.3315, 0.3260, 0.3209, 0.3160,
        0.3117, 0.3078, 0.3042, 0.3010, 0.2980, 0.2951, 0.2922, 0.2892, 0.2864, 0.2835, 0.2807,
        0.2779, 0.2752, 0.2725, 0.2697, 0.2670, 0.2643, 0.2615, 0.2588, 0.2561, 0.2533, 0.2506,
        0.2479, 0.2451, 0.2424, 0.2368, 0.2313, 0.2258, 0.2205, 0.2154, 0.2106, 0.2060, 0.2017,
        0.1975, 0.1935
    )

    fun cd(m: Double): Double {
        if (m <= mach[0]) return cd[0]
        if (m >= mach.last()) return cd.last()
        var lo = 0; var hi = mach.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (mach[mid] > m) hi = mid else lo = mid }
        val t = (m - mach[lo]) / (mach[hi] - mach[lo])
        return cd[lo] + (cd[hi] - cd[lo]) * t
    }
}

/** Muestra de la trayectoria. */
class TrajPoint(val t: Double, val pos: Vec3, val vel: Vec3)

class Trajectory(val points: List<TrajPoint>) {
    /** Posición interpolada al tiempo t. */
    fun at(t: Double): TrajPoint? {
        if (points.isEmpty() || t > points.last().t) return null
        var lo = 0; var hi = points.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (points[mid].t > t) hi = mid else lo = mid }
        val a = points[lo]; val b = points[hi]
        val k = if (b.t > a.t) (t - a.t) / (b.t - a.t) else 0.0
        return TrajPoint(t, a.pos + (b.pos - a.pos) * k, a.vel + (b.vel - a.vel) * k)
    }

    /** Primer punto donde la distancia horizontal recorrida desde el origen alcanza [range]. */
    fun atHorizontalRange(origin: Vec3, range: Double): TrajPoint? {
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val ra = hdist(a.pos - origin); val rb = hdist(b.pos - origin)
            if (ra <= range && rb >= range) {
                val k = if (rb > ra) (range - ra) / (rb - ra) else 0.0
                return TrajPoint(a.t + (b.t - a.t) * k, a.pos + (b.pos - a.pos) * k, a.vel + (b.vel - a.vel) * k)
            }
        }
        return null
    }

    private fun hdist(v: Vec3) = sqrt(v.x * v.x + v.y * v.y)
}

/**
 * Modelo de punto material con arrastre G7, gravedad, viento, Coriolis y deriva por giro (Litz).
 * Integración Runge-Kutta 4.
 */
class BallisticSolver(var cartridge: Cartridge, var atmosphere: Atmosphere = Atmosphere()) {

    companion object {
        const val G = 9.80665
        const val EARTH_OMEGA = 7.2921159e-5
        const val LB_IN2_TO_KG_M2 = 703.0696
    }

    var enableCoriolis = true
    var enableSpinDrift = true

    private fun dragK(vRel: Double): Double {
        val mach = vRel / atmosphere.speedOfSound
        return atmosphere.density * G7.cd(mach) * PI / (8.0 * cartridge.bcG7 * LB_IN2_TO_KG_M2)
    }

    private fun accel(v: Vec3, wind: Vec3): Vec3 {
        val vr = v - wind
        val sp = vr.length()
        val drag = vr * (-dragK(sp) * sp)
        var a = drag + Vec3(0.0, 0.0, -G)
        if (enableCoriolis) {
            val lat = Math.toRadians(atmosphere.latitudeDeg)
            val omega = Vec3(0.0, EARTH_OMEGA * cos(lat), EARTH_OMEGA * sin(lat))
            a += (omega cross v) * -2.0
        }
        return a
    }

    /** Factor de estabilidad giroscópica (fórmula de Miller). */
    fun stabilityFactor(): Double {
        val m = cartridge.massGr; val d = cartridge.diameterIn
        val l = cartridge.lengthIn / d; val t = cartridge.twistIn / d
        val sg = 30.0 * m / (t * t * d.pow(3) * l * (1 + l * l))
        val fv = (cartridge.muzzleVelocity * 3.28084 / 2800.0).pow(1.0 / 3.0)
        val tempF = atmosphere.temperatureC * 9 / 5 + 32
        val pressInHg = atmosphere.pressurePa / 3386.39
        val fa = ((tempF + 460) / (59 + 460)) * (29.92 / pressInHg)
        return sg * fv * fa
    }

    /** Deriva por giro en metros (positiva = hacia la derecha) tras t segundos. */
    fun spinDrift(t: Double): Double {
        if (!enableSpinDrift || t <= 0) return 0.0
        return 1.25 * (stabilityFactor() + 1.2) * t.pow(1.83) * 0.0254
    }

    /**
     * Simula el disparo.
     * @param origin posición de la boca del cañón (mundo)
     * @param dir dirección del ánima (unitaria)
     * @param wind viento en el mundo (m/s), hacia donde sopla
     * @param maxRange distancia máxima a simular
     */
    fun simulate(
        origin: Vec3, dir: Vec3, wind: Vec3,
        maxRange: Double = 2500.0, maxTime: Double = 6.0,
        dt: Double = 0.0005, sampleEvery: Int = 4
    ): Trajectory {
        val pts = ArrayList<TrajPoint>(4096)
        var p = origin
        var v = dir.normalized() * cartridge.muzzleVelocity
        var t = 0.0
        val right = (dir cross Vec3.UP).normalized().let { if (it.length() < 0.5) Vec3.EAST else it }
        pts.add(TrajPoint(0.0, p, v))
        var step = 0
        while (t < maxTime) {
            val k1v = accel(v, wind); val k1p = v
            val k2v = accel(v + k1v * (dt / 2), wind); val k2p = v + k1v * (dt / 2)
            val k3v = accel(v + k2v * (dt / 2), wind); val k3p = v + k2v * (dt / 2)
            val k4v = accel(v + k3v * dt, wind); val k4p = v + k3v * dt
            v += (k1v + k2v * 2.0 + k3v * 2.0 + k4v) * (dt / 6)
            p += (k1p + k2p * 2.0 + k3p * 2.0 + k4p) * (dt / 6)
            t += dt
            step++
            if (step % sampleEvery == 0) {
                pts.add(TrajPoint(t, p + right * spinDrift(t), v))
                val d = p - origin
                if (sqrt(d.x * d.x + d.y * d.y) > maxRange) break
                if (p.z < origin.z - 400.0) break
            }
        }
        return Trajectory(pts)
    }

    /**
     * Ángulo de cero (rad): elevación del ánima respecto a la línea de mira para que la bala
     * cruce la línea de mira a [zeroRange] metros, con la mira [sightHeight] m sobre el ánima.
     */
    fun zeroAngle(zeroRange: Double, sightHeight: Double): Double {
        val savedC = enableCoriolis; val savedS = enableSpinDrift
        enableCoriolis = false; enableSpinDrift = false
        var lo = -0.01; var hi = 0.03
        repeat(40) {
            val mid = (lo + hi) / 2
            val dir = Vec3(0.0, cos(mid), sin(mid))
            val tr = simulate(Vec3(0.0, 0.0, -sightHeight), dir, Vec3.ZERO, zeroRange + 50, dt = 0.001, sampleEvery = 1)
            val hit = tr.atHorizontalRange(Vec3.ZERO, zeroRange)
            val z = hit?.pos?.z ?: -1e9
            if (z > 0) hi = mid else lo = mid
        }
        enableCoriolis = savedC; enableSpinDrift = savedS
        return (lo + hi) / 2
    }

    /**
     * Solución de tiro: corrección (rad) de elevación y deriva para un blanco a [range] m,
     * con el viento expresado como componente cruzada (m/s, positiva = de izquierda a derecha)
     * y frontal (positiva = de frente). Devuelve (elevación, deriva, tiempo de vuelo, velocidad).
     */
    fun solution(range: Double, zeroAng: Double, sightHeight: Double, crossWind: Double, headWind: Double): FiringSolution {
        val dir = Vec3(0.0, cos(zeroAng), sin(zeroAng))
        val wind = Vec3(crossWind, -headWind, 0.0)
        val tr = simulate(Vec3(0.0, 0.0, -sightHeight), dir, wind, range + 20)
        val hit = tr.atHorizontalRange(Vec3.ZERO, range) ?: return FiringSolution(0.0, 0.0, 0.0, 0.0, false)
        // La bala está en hit.pos; corrección angular necesaria = -ángulo del desvío
        val elev = -kotlin.math.atan2(hit.pos.z, range)
        val drift = -kotlin.math.atan2(hit.pos.x, range)
        return FiringSolution(elev, drift, hit.t, hit.vel.length(), true)
    }

    /** Energía cinética en joules. */
    fun energy(v: Double) = 0.5 * (cartridge.massGr * 6.479891e-5) * v * v
}

data class FiringSolution(
    val elevationRad: Double, // positivo = subir
    val windageRad: Double,   // positivo = corregir a la derecha
    val timeOfFlight: Double,
    val velocity: Double,
    val valid: Boolean
) {
    val elevationMil get() = elevationRad * 1000
    val windageMil get() = windageRad * 1000
    val elevationMoa get() = Math.toDegrees(elevationRad) * 60
    val windageMoa get() = Math.toDegrees(windageRad) * 60
}

/** Unidades de las torretas. */
enum class TurretUnit(val label: String, val clickRad: Double) {
    MRAD("MRAD 0.1", 0.0001),
    MOA("MOA 1/4", Math.toRadians(0.25 / 60.0));
}

/** Viento con ráfagas pseudoaleatorias suaves. */
class WindModel(seed: Long = System.nanoTime()) {
    private val rnd = java.util.Random(seed)
    var baseSpeed = 3.0          // m/s
    var baseFromDeg = 270.0      // de dónde viene (azimut, 0 = Norte)
    var gustiness = 0.35
    private var phase1 = rnd.nextDouble() * 100
    private var phase2 = rnd.nextDouble() * 100
    var speed = baseSpeed; private set
    var fromDeg = baseFromDeg; private set

    fun randomize(maxSpeed: Double) {
        baseSpeed = rnd.nextDouble() * maxSpeed
        baseFromDeg = rnd.nextDouble() * 360
    }

    fun update(t: Double) {
        val g = sin(t * 0.37 + phase1) * 0.6 + sin(t * 1.13 + phase2) * 0.4
        speed = (baseSpeed * (1 + gustiness * g)).coerceAtLeast(0.0)
        fromDeg = baseFromDeg + 12 * sin(t * 0.21 + phase2)
    }

    /** Vector de viento en ENU (hacia donde sopla). */
    fun vector(): Vec3 {
        val to = Math.toRadians(fromDeg + 180)
        return Vec3(sin(to) * speed, cos(to) * speed, 0.0)
    }

    /** Componentes relativas a un azimut de tiro: (cruzada izq→der, de frente). */
    fun relative(aimAzimuthDeg: Double): Pair<Double, Double> {
        val w = vector()
        val a = Math.toRadians(aimAzimuthDeg)
        val fwd = Vec3(sin(a), cos(a), 0.0)
        val right = Vec3(cos(a), -sin(a), 0.0)
        return Pair(w dot right, -(w dot fwd))
    }
}

/** Estima distancia por tamaño angular conocido (telémetro de retícula). */
object RangeEstimator {
    fun range(realSizeM: Double, angularSizeRad: Double): Double =
        if (angularSizeRad <= 1e-6) Double.POSITIVE_INFINITY else realSizeM / (2 * kotlin.math.tan(angularSizeRad / 2))

    @Suppress("unused")
    fun smooth(prev: Double, next: Double, a: Double) = if (prev.isNaN() || abs(prev - next) > prev * 0.5) next else prev + (next - prev) * a

    @Suppress("unused")
    fun expDecay(dt: Double, tau: Double) = 1 - exp(-dt / tau)
}
