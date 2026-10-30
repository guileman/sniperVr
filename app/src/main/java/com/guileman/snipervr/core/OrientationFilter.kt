package com.guileman.snipervr.core

import kotlin.math.sqrt

/**
 * Fusión de sensores propia (filtro complementario en cuaterniones):
 *  - el giroscopio da la rotación rápida y suave,
 *  - el acelerómetro corrige la inclinación (gravedad),
 *  - la brújula corrige el rumbo (deriva del giróscopo en el eje vertical).
 * El resultado es la rotación dispositivo -> mundo ENU.
 */
class OrientationFilter {
    var q: Quat = Quat.IDENTITY; private set
    var initialized = false; private set

    /** Peso de corrección por segundo (más alto = sigue más a acelerómetro/brújula). */
    var tiltGain = 0.8
    var headingGain = 0.25
    var useCompass = true

    private var gravity: Vec3? = null
    private var magnetic: Vec3? = null
    private var lastGyroNs = 0L

    /** Corrección de rumbo manual (grados), p.ej. declinación magnética. */
    var headingOffsetDeg = 0.0

    fun onAccelerometer(x: Double, y: Double, z: Double) {
        val g = Vec3(x, y, z)
        gravity = gravity?.let { it + (g - it) * 0.15 } ?: g
        if (!initialized) tryInit()
    }

    fun onMagnetometer(x: Double, y: Double, z: Double) {
        val m = Vec3(x, y, z)
        magnetic = magnetic?.let { it + (m - it) * 0.2 } ?: m
        if (!initialized) tryInit()
    }

    fun onGyroscope(x: Double, y: Double, z: Double, timestampNs: Long) {
        if (lastGyroNs == 0L || !initialized) { lastGyroNs = timestampNs; return }
        val dt = (timestampNs - lastGyroNs) * 1e-9
        lastGyroNs = timestampNs
        if (dt <= 0 || dt > 0.2) return

        // Integración: q = q * exp(w dt / 2) (velocidad angular en ejes del dispositivo).
        val w = Vec3(x, y, z)
        val ang = w.length() * dt
        if (ang > 1e-9) q = (q * Quat.fromAxisAngle(w, ang)).normalized()

        correct(dt)
    }

    /** Rotación absoluta por acelerómetro + brújula (como SensorManager.getRotationMatrix). */
    fun absoluteFromAccelMag(): Quat? {
        val g = gravity ?: return null
        val upD = g.normalized()                 // "arriba" medido en ejes del dispositivo
        val m = magnetic
        val eastD: Vec3
        if (m != null && useCompass) {
            val e = m cross upD
            if (e.length() < 1e-6) return null
            eastD = e.normalized()
        } else {
            // Sin brújula: conserva el rumbo actual y sólo nivela.
            val curEastD = q.conj().rotate(Vec3.EAST)
            val e = curEastD - upD * (curEastD dot upD)
            if (e.length() < 1e-6) return null
            eastD = e.normalized()
        }
        val northD = upD cross eastD
        // Filas = ejes del mundo expresados en el dispositivo => R mapea dispositivo->mundo.
        var r = Quat.fromMat3(Mat3(doubleArrayOf(
            eastD.x, eastD.y, eastD.z,
            northD.x, northD.y, northD.z,
            upD.x, upD.y, upD.z
        )))
        if (headingOffsetDeg != 0.0 && useCompass) {
            r = Quat.fromAxisAngle(Vec3.UP, Math.toRadians(-headingOffsetDeg)) * r
        }
        return r
    }

    private fun tryInit() {
        if (gravity == null || (useCompass && magnetic == null)) return
        absoluteFromAccelMag()?.let { q = it; initialized = true }
    }

    private fun correct(dt: Double) {
        val g = gravity ?: return
        // Si hay aceleración lineal fuerte (retroceso, movimiento brusco) no se corrige la inclinación.
        val gNorm = g.length()
        val accelTrust = (1.0 - kotlin.math.abs(gNorm - 9.81) / 3.0).coerceIn(0.0, 1.0)
        val target = absoluteFromAccelMag() ?: return
        val k = ((if (useCompass) headingGain else tiltGain) * dt * accelTrust).coerceIn(0.0, 0.2)
        q = q.nlerp(target, k)
    }

    /** Alterna a un cuaternión externo (TYPE_ROTATION_VECTOR del sistema). */
    fun setExternal(quat: Quat) { q = quat.normalized(); initialized = true }

    fun reset() { initialized = false; lastGyroNs = 0L; gravity = null; magnetic = null }

    @Suppress("unused")
    private fun len(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
}
