package com.guileman.snipervr.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.guileman.snipervr.core.Quat
import java.util.concurrent.ConcurrentHashMap

/** Una detección en coordenadas normalizadas (0..1) de la imagen ya orientada en landscape. */
data class NormDetection(val cocoName: String, val label: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Lote de detecciones con la orientación del celular en el instante de la foto. */
class DetectionBatch(val items: List<NormDetection>, val orientation: Quat?, val imageAspect: Float)

/**
 * Detección de objetos con MediaPipe (EfficientDet-Lite0, COCO). El juego usa 11 de las 80
 * clases: persona y 10 animales.
 */
class Detector(context: Context, humans: Boolean, animals: Boolean, private val onResult: (DetectionBatch) -> Unit) {

    companion object {
        const val MODEL = "efficientdet_lite0.tflite"

        /** Nombre COCO -> (etiqueta en pantalla, alto típico en metros para estimar distancia). */
        val CLASSES: Map<String, Pair<String, Double>> = linkedMapOf(
            "person" to ("Persona" to 1.70),
            "bird" to ("Ave" to 0.25),
            "cat" to ("Gato" to 0.28),
            "dog" to ("Perro" to 0.55),
            "horse" to ("Caballo" to 1.60),
            "sheep" to ("Oveja" to 0.80),
            "cow" to ("Vaca" to 1.40),
            "elephant" to ("Elefante" to 3.00),
            "bear" to ("Oso" to 1.10),
            "zebra" to ("Cebra" to 1.40),
            "giraffe" to ("Jirafa" to 4.80)
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val orientations = ConcurrentHashMap<Long, Quat>()
    private val aspects = ConcurrentHashMap<Long, Float>()
    private var lastTs = 0L
    @Volatile var orientationProvider: () -> Quat? = { null }
    private var detector: ObjectDetector? = null
    var error: String? = null; private set

    init {
        val allow = CLASSES.keys.filter { (humans && it == "person") || (animals && it != "person") }
        try {
            val base = BaseOptions.builder().setModelAssetPath(MODEL).build()
            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setMaxResults(8)
                .setScoreThreshold(0.40f)
                .setCategoryAllowlist(allow)
                .setResultListener { result, image ->
                    val ts = result.timestampMs()
                    val q = orientations.remove(ts)
                    val aspect = aspects.remove(ts) ?: (4f / 3f)
                    orientations.keys.filter { it < ts }.forEach { orientations.remove(it); aspects.remove(it) }
                    val w = image.width.toFloat(); val h = image.height.toFloat()
                    val list = result.detections().mapNotNull { d ->
                        val cat = d.categories().firstOrNull() ?: return@mapNotNull null
                        val name = cat.categoryName()
                        val info = CLASSES[name] ?: return@mapNotNull null
                        val b = d.boundingBox()
                        NormDetection(name, info.first, b.left / w, b.top / h, b.right / w, b.bottom / h)
                    }
                    val batch = DetectionBatch(list, q, aspect)
                    main.post { onResult(batch) }
                }
                .setErrorListener { e -> Log.e("Detector", "MediaPipe", e) }
                .build()
            detector = ObjectDetector.createFromOptions(context, opts)
        } catch (e: Exception) {
            error = e.message ?: "No se pudo cargar el modelo"
            Log.e("Detector", "init", e)
        }
    }

    /** Se llama desde el hilo de análisis de CameraX. */
    fun analyze(proxy: ImageProxy) {
        val det = detector
        if (det == null) { proxy.close(); return }
        try {
            val rot = proxy.imageInfo.rotationDegrees
            var bmp: Bitmap = proxy.toBitmap()
            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts
            orientationProvider()?.let { orientations[ts] = it }
            aspects[ts] = bmp.width.toFloat() / bmp.height
            det.detectAsync(BitmapImageBuilder(bmp).build(), ts)
        } catch (e: Exception) {
            Log.e("Detector", "analyze", e)
        } finally {
            proxy.close()
        }
    }

    fun close() {
        try { detector?.close() } catch (_: Exception) {}
    }
}
