package com.guileman.snipervr.core

import kotlin.math.sqrt

/**
 * Vector 3D en metros. Convención de mundo: ENU (x = Este, y = Norte, z = Arriba),
 * origen en los pies del tirador.
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 { val l = length(); return if (l < 1e-12) this else this / l }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UP = Vec3(0.0, 0.0, 1.0)
        val NORTH = Vec3(0.0, 1.0, 0.0)
        val EAST = Vec3(1.0, 0.0, 0.0)
    }
}

/** Matriz 3x3 fila-mayor. */
class Mat3(val m: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)) {
    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z
    )
    fun transpose() = Mat3(doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))
    fun column(i: Int) = Vec3(m[i], m[3 + i], m[6 + i])
}

/** Cuaternión unitario (w, x, y, z). Rota vectores del dispositivo al mundo. */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(q: Quat) = Quat(
        w * q.w - x * q.x - y * q.y - z * q.z,
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w
    )
    fun conj() = Quat(w, -x, -y, -z)
    fun norm() = sqrt(w * w + x * x + y * y + z * z)
    fun normalized(): Quat { val n = norm(); return Quat(w / n, x / n, y / n, z / n) }
    fun dot(q: Quat) = w * q.w + x * q.x + y * q.y + z * q.z

    fun rotate(v: Vec3): Vec3 {
        // v' = q v q*
        val p = this * Quat(0.0, v.x, v.y, v.z) * conj()
        return Vec3(p.x, p.y, p.z)
    }

    fun toMat3(): Mat3 {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return Mat3(doubleArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy)
        ))
    }

    /** Interpolación lineal normalizada (suficiente para correcciones pequeñas). */
    fun nlerp(target: Quat, t: Double): Quat {
        val s = if (dot(target) < 0) -1.0 else 1.0
        return Quat(
            w + (target.w * s - w) * t, x + (target.x * s - x) * t,
            y + (target.y * s - y) * t, z + (target.z * s - z) * t
        ).normalized()
    }

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        fun fromAxisAngle(axis: Vec3, angle: Double): Quat {
            val a = axis.normalized(); val h = angle / 2; val s = kotlin.math.sin(h)
            return Quat(kotlin.math.cos(h), a.x * s, a.y * s, a.z * s)
        }

        fun fromMat3(r: Mat3): Quat {
            val m = r.m
            val tr = m[0] + m[4] + m[8]
            return if (tr > 0) {
                val s = sqrt(tr + 1.0) * 2
                Quat(0.25 * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
            } else if (m[0] > m[4] && m[0] > m[8]) {
                val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2
                Quat((m[7] - m[5]) / s, 0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
            } else if (m[4] > m[8]) {
                val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2
                Quat((m[2] - m[6]) / s, (m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s)
            } else {
                val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2
                Quat((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s)
            }.normalized()
        }
    }
}
