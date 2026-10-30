package com.guileman.snipervr.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.tan

/**
 * Proyección de cámara "pinhole" hecha a mano: pasa puntos del mundo (ENU) a píxeles de pantalla
 * usando la orientación del celular. Por eso los blancos virtuales quedan fijos al entorno.
 */
class CameraModel {
    /** Rotación dispositivo -> mundo. */
    var orientation: Quat = Quat.IDENTITY

    /** Rotación de pantalla: 0, 90, 180, 270 (grados, como Surface.ROTATION_*). */
    var screenRotationDeg = 90

    var width = 1920.0
    var height = 1080.0

    /** Distancia focal en píxeles de pantalla (ya incluye zoom y recorte de la vista previa). */
    var focalPx = 1500.0

    /** Posición de la cámara en el mundo. */
    var position = Vec3(0.0, 0.0, 1.6)

    // Base de la cámara en el mundo (se recalculan en update()).
    var right = Vec3.EAST; private set
    var up = Vec3.UP; private set
    var forward = Vec3.NORTH; private set

    fun update() {
        // Ejes de la pantalla expresados en coordenadas del dispositivo (retrato natural:
        // X derecha, Y arriba, Z hacia el usuario). La cámara trasera mira hacia -Z.
        val (rd, ud) = when (screenRotationDeg) {
            // ROTATION_90: el celular se giró 90° antihorario (parte superior a la izquierda).
            90 -> Vec3(0.0, -1.0, 0.0) to Vec3(1.0, 0.0, 0.0)
            270 -> Vec3(0.0, 1.0, 0.0) to Vec3(-1.0, 0.0, 0.0)
            180 -> Vec3(-1.0, 0.0, 0.0) to Vec3(0.0, -1.0, 0.0)
            else -> Vec3(1.0, 0.0, 0.0) to Vec3(0.0, 1.0, 0.0)
        }
        right = orientation.rotate(rd)
        up = orientation.rotate(ud)
        forward = orientation.rotate(Vec3(0.0, 0.0, -1.0))
    }

    /** Coordenadas de cámara (x derecha, y arriba, z profundidad) de un punto del mundo. */
    fun toCamera(world: Vec3): Vec3 {
        val p = world - position
        return Vec3(p dot right, p dot up, p dot forward)
    }

    /** Proyecta coordenadas de cámara a pantalla; null si está detrás. */
    fun cameraToScreen(c: Vec3, near: Double = 0.1): DoubleArray? {
        if (c.z < near) return null
        return doubleArrayOf(width / 2 + focalPx * c.x / c.z, height / 2 - focalPx * c.y / c.z, c.z)
    }

    fun project(world: Vec3): DoubleArray? = cameraToScreen(toCamera(world))

    /** Rayo (dirección unitaria en el mundo) que pasa por el píxel (sx, sy). */
    fun rayFromScreen(sx: Double, sy: Double): Vec3 {
        val x = (sx - width / 2) / focalPx
        val y = -(sy - height / 2) / focalPx
        return (forward + right * x + up * y).normalized()
    }

    /** Azimut (0 = Norte, sentido horario) de la línea de mira, en grados. */
    fun azimuthDeg(): Double {
        val a = Math.toDegrees(atan2(forward.x, forward.y))
        return (a + 360) % 360
    }

    /** Elevación de la línea de mira en grados. */
    fun elevationDeg(): Double = Math.toDegrees(asin(forward.z.coerceIn(-1.0, 1.0)))

    /** Inclinación lateral (cant) en grados: giro de la retícula respecto al horizonte. */
    fun cantDeg(): Double {
        val horizRight = (forward cross Vec3.UP).normalized()
        val s = (horizRight cross right) dot forward
        val c = horizRight dot right
        return Math.toDegrees(atan2(s, c))
    }

    /** Píxeles que ocupa 1 miliradián en el centro de la pantalla. */
    fun pxPerMil(): Double = focalPx * 0.001

    /** Campo de visión horizontal mostrado en pantalla (grados). */
    fun horizontalFovDeg(): Double = Math.toDegrees(2 * atan(width / 2 / focalPx))

    companion object {
        /**
         * Focal en píxeles de pantalla a partir del FOV horizontal del sensor y del escalado
         * FILL_CENTER de la vista previa.
         * @param sensorHFovRad FOV horizontal de la imagen de cámara en orientación landscape
         * @param imgW, imgH tamaño de la imagen de la cámara (landscape)
         * @param viewW, viewH tamaño de la vista
         * @param zoom zoom digital aplicado
         */
        fun focalFromFov(sensorHFovRad: Double, imgW: Double, imgH: Double, viewW: Double, viewH: Double, zoom: Double): Double {
            val fImg = imgW / (2 * tan(sensorHFovRad / 2))
            val scale = maxOf(viewW / imgW, viewH / imgH)
            return fImg * scale * zoom
        }

        /**
         * Zoom necesario para que la pantalla, vista a [eyeDistanceM] metros, se comporte como una
         * mira de [magnification] aumentos: el ojo ve la pantalla con un ángulo A y la mira debe
         * mostrar un campo real A / magnification.
         */
        fun zoomForMagnification(
            screenWidthM: Double, eyeDistanceM: Double, magnification: Double,
            focalPxAtZoom1: Double, viewW: Double
        ): Double {
            val eyeHalfAngle = atan(screenWidthM / 2 / eyeDistanceM)
            val trueHalfFov = atan(tan(eyeHalfAngle) / magnification)
            val neededFocal = viewW / 2 / tan(trueHalfFov)
            return neededFocal / focalPxAtZoom1
        }

        @Suppress("unused")
        const val TWO_PI = 2 * PI
    }
}
