package com.guileman.snipervr.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Sintetizador propio sobre AudioTrack: todos los sonidos se generan en tiempo real, sin
 * archivos de audio.
 */
class Synth {
    companion object { const val SR = 44100 }

    private abstract class Voice(var delay: Int) {
        var n = 0
        /** Devuelve false cuando terminó. */
        abstract fun sample(i: Int): Float
        abstract val length: Int
    }

    private val voices = CopyOnWriteArrayList<Voice>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private val rnd = java.util.Random()

    /** Intensidad del viento ambiente 0..1. */
    @Volatile var windLevel = 0f
    var volume = 0.9f

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, 4096) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        running = true
        thread = Thread({ loop(t) }, "synth").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        track?.run { try { stop() } catch (_: Exception) {}; release() }
        track = null
    }

    private fun loop(t: AudioTrack) {
        val block = 512
        val buf = ShortArray(block)
        var windLp = 0f; var windLp2 = 0f; var windEnv = 0f
        var gustPh = 0.0
        while (running) {
            val vs = voices.toTypedArray()
            for (i in 0 until block) {
                var s = 0f
                for (v in vs) {
                    if (v.delay > 0) { v.delay--; continue }
                    if (v.n >= v.length) continue
                    s += v.sample(v.n)
                    v.n++
                }
                // Viento ambiente: ruido filtrado con ráfagas lentas
                gustPh += 0.35 / SR
                windEnv += ((windLevel * (0.7f + 0.3f * sin(gustPh * 2 * PI).toFloat())) - windEnv) * 0.0005f
                val nz = rnd.nextFloat() * 2 - 1
                windLp += (nz - windLp) * 0.02f
                windLp2 += (windLp - windLp2) * 0.05f
                s += windLp2 * windEnv * 2.2f
                val out = tanh((s * volume).toDouble()).toFloat()
                buf[i] = (out * 32000).toInt().toShort()
            }
            for (v in vs) if (v.n >= v.length) voices.remove(v)
            t.write(buf, 0, block)
        }
    }

    private fun sec(s: Double) = (s * SR).toInt()

    // ------------------------------------------------------------------ sonidos

    /** Disparo de rifle: estampido + golpe grave + eco del entorno. */
    fun gunshot() {
        voices.add(object : Voice(0) {
            var lp = 0f
            override val length = sec(0.45)
            override fun sample(i: Int): Float {
                val t = i.toDouble() / SR
                val nz = rnd.nextFloat() * 2 - 1
                lp += (nz - lp) * (0.55f - (t * 0.9).toFloat()).coerceAtLeast(0.05f)
                val crack = lp * exp(-t / 0.018).toFloat() * 1.6f
                val boom = sin(2 * PI * (55 + 80 * exp(-t / 0.03)) * t).toFloat() * exp(-t / 0.09).toFloat() * 1.2f
                val tail = lp * exp(-t / 0.12).toFloat() * 0.35f
                return crack + boom + tail
            }
        })
        // Ecos (reflexiones del terreno)
        for ((d, a) in listOf(0.14 to 0.30f, 0.33 to 0.18f, 0.62 to 0.10f, 1.05 to 0.06f)) {
            voices.add(noiseBurst(d, 0.35, a, 0.04f, 0.18))
        }
    }

    /** Ráfaga de ruido filtrado. */
    private fun noiseBurst(delayS: Double, durS: Double, amp: Float, cutoff: Float, tau: Double, attack: Double = 0.003): Voice =
        object : Voice(sec(delayS)) {
            var lp = 0f; var lp2 = 0f
            override val length = sec(durS)
            override fun sample(i: Int): Float {
                val t = i.toDouble() / SR
                val nz = rnd.nextFloat() * 2 - 1
                lp += (nz - lp) * cutoff
                lp2 += (lp - lp2) * cutoff
                val env = (if (t < attack) t / attack else exp(-(t - attack) / tau)).toFloat()
                return lp2 * env * amp * 3f
            }
        }

    /** Impacto sordo en un cuerpo, retrasado por el tiempo de vuelo y la vuelta del sonido. */
    fun thud(delayS: Double, distance: Double) {
        val a = (0.9f * (120f / (distance.toFloat() + 60f))).coerceIn(0.05f, 0.9f)
        voices.add(noiseBurst(delayS, 0.25, a, 0.03f, 0.05))
        voices.add(object : Voice(sec(delayS)) {
            override val length = sec(0.2)
            override fun sample(i: Int): Float {
                val t = i.toDouble() / SR
                return sin(2 * PI * 90 * t).toFloat() * exp(-t / 0.05).toFloat() * a
            }
        })
    }

    /** Campanazo de la placa de acero (parciales inarmónicos). */
    fun steel(delayS: Double, distance: Double) {
        val a = (0.8f * (200f / (distance.toFloat() + 150f))).coerceIn(0.05f, 0.8f)
        val parts = doubleArrayOf(820.0, 1930.0, 3170.0, 4400.0)
        val amps = floatArrayOf(1f, 0.6f, 0.35f, 0.2f)
        voices.add(object : Voice(sec(delayS)) {
            override val length = sec(1.4)
            override fun sample(i: Int): Float {
                val t = i.toDouble() / SR
                var s = 0.0
                for (k in parts.indices) s += sin(2 * PI * parts[k] * t) * amps[k] * exp(-t / (0.6 / (k + 1)))
                return (s * a * 0.5).toFloat() * (if (t < 0.002) (t / 0.002).toFloat() else 1f)
            }
        })
    }

    /** Polvo/tierra al impactar el suelo. */
    fun dirt(delayS: Double, distance: Double) {
        val a = (0.5f * (100f / (distance.toFloat() + 80f))).coerceIn(0.03f, 0.5f)
        voices.add(noiseBurst(delayS, 0.3, a, 0.08f, 0.06))
    }

    /** Chasquido supersónico cuando la bala pasa cerca. */
    fun crack() = voices.add(noiseBurst(0.0, 0.05, 0.5f, 0.6f, 0.006, 0.0005))

    /** Clic de torreta. */
    fun click() {
        voices.add(object : Voice(0) {
            override val length = sec(0.012)
            override fun sample(i: Int): Float {
                val t = i.toDouble() / SR
                return (sin(2 * PI * 3200 * t) * exp(-t / 0.0015)).toFloat() * 0.5f
            }
        })
    }

    private fun metalClick(delayS: Double, freq: Double, amp: Float) {
        voices.add(object : Voice(sec(delayS)) {
            var lp = 0f
            override val length = sec(0.05)
            override fun sample(i: Int): Float {
                val t = i.toDouble() / SR
                lp += ((rnd.nextFloat() * 2 - 1) - lp) * 0.5f
                return ((sin(2 * PI * freq * t) * 0.6 + lp * 0.4) * exp(-t / 0.008)).toFloat() * amp
            }
        })
    }

    /** Ciclo de cerrojo: levantar, atrás, adelante, bajar. */
    fun bolt() {
        metalClick(0.30, 1800.0, 0.45f)
        voices.add(noiseBurst(0.38, 0.12, 0.25f, 0.25f, 0.05))
        metalClick(0.52, 1400.0, 0.5f)
        voices.add(noiseBurst(0.68, 0.10, 0.25f, 0.25f, 0.04))
        metalClick(0.80, 2200.0, 0.55f)
        metalClick(0.95, 1600.0, 0.5f)
    }

    /** Recarga del cargador. */
    fun reload() {
        metalClick(0.0, 900.0, 0.5f)
        metalClick(0.6, 1200.0, 0.4f)
        metalClick(1.3, 1000.0, 0.6f)
        metalClick(1.5, 2000.0, 0.4f)
    }

    /** Gatillo sin munición. */
    fun dryFire() = metalClick(0.0, 2600.0, 0.35f)
}
