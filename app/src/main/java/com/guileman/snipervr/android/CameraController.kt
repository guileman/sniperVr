package com.guileman.snipervr.android

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.atan

/**
 * Datos ópticos reales de la cámara (leídos con Camera2): distancia focal, tamaño del sensor
 * y campo de visión.
 */
data class LensInfo(
    val focalMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val hFovRad: Double,
    val vFovRad: Double,
    val sensorOrientation: Int
) {
    interface Getter {
        fun <T> get(key: CameraCharacteristics.Key<T>): T?
    }

    companion object {
        /** Valores típicos por si un equipo no informa sus características. */
        val DEFAULT = LensInfo(4.3f, 5.6f, 4.2f, 2 * atan(5.6 / (2 * 4.3)), 2 * atan(4.2 / (2 * 4.3)), 90)

        fun from(ch: Getter): LensInfo {
            val focals: FloatArray? = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val phys: android.util.SizeF? = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixArr: Size? = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val active: android.graphics.Rect? = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val orient: Int = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            if (focals == null || focals.isEmpty() || phys == null) return DEFAULT
            val f = focals.minOrNull() ?: focals[0]
            // El área activa puede ser algo menor que la matriz completa de píxeles.
            var w = phys.width; var h = phys.height
            if (pixArr != null && active != null && pixArr.width > 0 && pixArr.height > 0) {
                w *= active.width().toFloat() / pixArr.width
                h *= active.height().toFloat() / pixArr.height
            }
            val big = maxOf(w, h); val small = minOf(w, h)
            return LensInfo(f, big, small, 2 * atan(big / (2.0 * f)), 2 * atan(small / (2.0 * f)), orient)
        }
    }
}

/**
 * CameraX: vista previa de la cámara trasera, zoom y (en modo real) análisis de imágenes para
 * el detector de objetos.
 */
class CameraController(
    private val activity: ComponentActivity,
    private val previewView: PreviewView,
    private val analyzer: ((ImageProxy) -> Unit)?
) {
    var camera: Camera? = null; private set
    var lens: LensInfo = LensInfo.DEFAULT; private set
    /** Tamaño de la imagen de la vista previa en landscape (ancho >= alto). */
    var previewSize = Size(1440, 1080); private set
    var minZoom = 1f; private set
    var maxZoom = 8f; private set
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @SuppressLint("UnsafeOptInUsageError")
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun start(onReady: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val provider = future.get()
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)
            if (analyzer != null) {
                val analysisSel = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSel)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { img -> analyzer.invoke(img) }
                useCases.add(analysis)
            }
            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
                camera = cam
                val info = Camera2CameraInfo.from(cam.cameraInfo)
                lens = LensInfo.from(object : LensInfo.Getter {
                    override fun <T> get(key: CameraCharacteristics.Key<T>): T? = info.getCameraCharacteristic(key)
                })
                cam.cameraInfo.zoomState.value?.let { minZoom = it.minZoomRatio; maxZoom = it.maxZoomRatio }
                cam.cameraInfo.zoomState.observe(activity) { z -> minZoom = z.minZoomRatio; maxZoom = z.maxZoomRatio }
                preview.resolutionInfo?.resolution?.let {
                    previewSize = Size(maxOf(it.width, it.height), minOf(it.width, it.height))
                }
                onReady()
            } catch (e: Exception) {
                Log.e("CameraController", "No se pudo abrir la cámara", e)
                onReady()
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    /** Zoom realmente aplicado (limitado al rango del equipo). */
    fun setZoom(ratio: Float): Float {
        val r = ratio.coerceIn(maxOf(1f, minZoom), maxZoom)
        camera?.cameraControl?.setZoomRatio(r)
        return r
    }

    fun shutdown() {
        analysisExecutor.shutdown()
    }
}
