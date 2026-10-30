package com.guileman.snipervr.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.guileman.snipervr.core.Mat3
import com.guileman.snipervr.core.OrientationFilter
import com.guileman.snipervr.core.Quat

/**
 * Lee giroscopio, acelerómetro y brújula y los fusiona con [OrientationFilter].
 * Si el equipo no tiene giroscopio, usa el vector de rotación del sistema.
 */
class SensorTracker(context: Context, useCompass: Boolean) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val rotVec = sm.getDefaultSensor(if (useCompass) Sensor.TYPE_ROTATION_VECTOR else Sensor.TYPE_GAME_ROTATION_VECTOR)
    val filter = OrientationFilter().apply { this.useCompass = useCompass && mag != null }
    val hasGyro get() = gyro != null
    private val rm = FloatArray(9)

    /** Calidad de la brújula reportada por el sistema (0..3). */
    var compassAccuracy = 3; private set

    val orientation: Quat get() = filter.q
    val ready get() = filter.initialized

    fun start() {
        if (gyro != null) {
            sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST)
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
            if (filter.useCompass) sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
        } else if (rotVec != null) {
            sm.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
            if (mag != null) sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> filter.onGyroscope(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), e.timestamp)
            Sensor.TYPE_ACCELEROMETER -> {
                filter.onAccelerometer(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                if (gyro == null) filter.absoluteFromAccelMag()?.let { filter.setExternal(it) }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> filter.onMagnetometer(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rm, v)
                filter.setExternal(Quat.fromMat3(Mat3(DoubleArray(9) { rm[it].toDouble() })))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) compassAccuracy = accuracy
    }
}
