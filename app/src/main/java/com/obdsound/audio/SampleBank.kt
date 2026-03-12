package com.obdsound.audio

import android.content.Context
import com.obdsound.R
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class CarSound(val displayName: String) {
    BAC_MONO("BAC Mono"),
    FERRARI_458("Ferrari 458"),
    PROCAR("BMW M1 Procar")
}

data class CarSoundConfig(
    val onLowRes: Int, val onLowRpm: Int, val onLowVol: Float,
    val onHighRes: Int, val onHighRpm: Int, val onHighVol: Float,
    val offLowRes: Int, val offLowRpm: Int, val offLowVol: Float,
    val offHighRes: Int, val offHighRpm: Int, val offHighVol: Float,
    val limiterRes: Int, val limiterRpm: Int, val limiterVol: Float,
    val engineLimiter: Float, val softLimiter: Float, val inertia: Float,
    val gearRatios: DoubleArray = doubleArrayOf(3.4, 2.36, 1.85, 1.47, 1.24, 1.07),
    val idleRpm: Float = 1000f,
    val torqueNm: Float = 400f,
    val engineBrakingNm: Float = 200f
)

class SampleBank(private val context: Context) {

    data class Sample(
        val pcm: FloatArray,
        val sampleRate: Int,
        val nativeRpm: Int,
        val volume: Float = 1.0f
    )

    data class CarPack(
        val onLow: Sample,
        val onHigh: Sample,
        val offLow: Sample,
        val offHigh: Sample,
        val limiter: Sample,
        val engineLimiter: Float,
        val softLimiter: Float,
        val inertia: Float,
        val gearRatios: DoubleArray,
        val idleRpm: Float,
        val torqueNm: Float,
        val engineBrakingNm: Float
    )

    private val configs = mapOf(
        // BAC Mono: Hewland FTR 6-speed sequential — close-ratio racing gearbox
        CarSound.BAC_MONO to CarSoundConfig(
            onLowRes = R.raw.bac_on_low, onLowRpm = 1000, onLowVol = 0.5f,
            onHighRes = R.raw.bac_on_high, onHighRpm = 1000, onHighVol = 0.5f,
            offLowRes = R.raw.bac_off_low, offLowRpm = 1000, offLowVol = 0.5f,
            offHighRes = R.raw.bac_off_high, offHighRpm = 1000, offHighVol = 0.5f,
            limiterRes = R.raw.bac_limiter, limiterRpm = 8000, limiterVol = 0.4f,
            engineLimiter = 9000f, softLimiter = 8950f, inertia = 1.0f,
            gearRatios = doubleArrayOf(2.92, 2.21, 1.77, 1.48, 1.27, 1.09),
            idleRpm = 1100f, torqueNm = 350f, engineBrakingNm = 140f
        ),
        // Ferrari 458: Getrag 7-speed dual-clutch (F1 DCT)
        CarSound.FERRARI_458 to CarSoundConfig(
            onLowRes = R.raw.f458_on_low, onLowRpm = 5300, onLowVol = 1.5f,
            onHighRes = R.raw.f458_on_high, onHighRpm = 7700, onHighVol = 2.5f,
            offLowRes = R.raw.f458_off_low, offLowRpm = 6900, offLowVol = 1.4f,
            offHighRes = R.raw.f458_off_high, offHighRpm = 7900, offHighVol = 1.6f,
            limiterRes = R.raw.f458_limiter, limiterRpm = 0, limiterVol = 1.8f,
            engineLimiter = 8900f, softLimiter = 8800f, inertia = 0.8f,
            gearRatios = doubleArrayOf(3.08, 2.19, 1.63, 1.29, 1.03, 0.84, 0.69),
            idleRpm = 1000f, torqueNm = 540f, engineBrakingNm = 280f
        ),
        // BMW M1 Procar: ZF 5-speed dog-engagement racing gearbox
        CarSound.PROCAR to CarSoundConfig(
            onLowRes = R.raw.procar_on_low, onLowRpm = 3200, onLowVol = 1.0f,
            onHighRes = R.raw.procar_on_high, onHighRpm = 8000, onHighVol = 1.0f,
            offLowRes = R.raw.procar_off_low, offLowRpm = 3400, offLowVol = 1.3f,
            offHighRes = R.raw.procar_off_high, offHighRpm = 8430, offHighVol = 1.3f,
            limiterRes = R.raw.procar_limiter, limiterRpm = 8000, limiterVol = 0.5f,
            engineLimiter = 9000f, softLimiter = 9000f, inertia = 1.0f,
            gearRatios = doubleArrayOf(3.72, 2.40, 1.77, 1.28, 1.00),
            idleRpm = 950f, torqueNm = 450f, engineBrakingNm = 200f
        )
    )

    fun loadCar(car: CarSound): CarPack {
        val cfg = configs[car]!!
        return CarPack(
            onLow = loadSample(cfg.onLowRes, cfg.onLowRpm, cfg.onLowVol),
            onHigh = loadSample(cfg.onHighRes, cfg.onHighRpm, cfg.onHighVol),
            offLow = loadSample(cfg.offLowRes, cfg.offLowRpm, cfg.offLowVol),
            offHigh = loadSample(cfg.offHighRes, cfg.offHighRpm, cfg.offHighVol),
            limiter = loadSample(cfg.limiterRes, cfg.limiterRpm, cfg.limiterVol),
            engineLimiter = cfg.engineLimiter,
            softLimiter = cfg.softLimiter,
            inertia = cfg.inertia,
            gearRatios = cfg.gearRatios,
            idleRpm = cfg.idleRpm,
            torqueNm = cfg.torqueNm,
            engineBrakingNm = cfg.engineBrakingNm
        )
    }

    private fun loadSample(resId: Int, nativeRpm: Int, volume: Float): Sample {
        context.resources.openRawResource(resId).use { stream ->
            val allBytes = stream.readBytes()
            val parsed = parseWav(allBytes)
            var pcm = when (parsed.bitsPerSample) {
                16 -> convert16BitToFloat(parsed.pcmData)
                24 -> convert24BitToFloat(parsed.pcmData)
                else -> convert16BitToFloat(parsed.pcmData)
            }
            if (parsed.channels == 2) pcm = mixToMono(pcm, parsed.channels)

            // Crossfade loop boundary to eliminate click at wrap point
            pcm = crossfadeLoop(pcm, 128)

            return Sample(pcm = pcm, sampleRate = parsed.sampleRate, nativeRpm = nativeRpm, volume = volume)
        }
    }

    private data class ParsedWav(
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val pcmData: ByteArray
    )

    /**
     * Proper WAV chunk parser. Iterates RIFF chunks to find "fmt " and "data",
     * skipping any LIST/INFO/other metadata chunks.
     */
    private fun parseWav(bytes: ByteArray): ParsedWav {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header: "RIFF" + fileSize + "WAVE"
        val riff = String(bytes, 0, 4)
        if (riff != "RIFF") throw IllegalArgumentException("Not a WAV file")
        buf.position(8)
        val wave = String(bytes, 8, 4)
        if (wave != "WAVE") throw IllegalArgumentException("Not a WAV file")
        buf.position(12)

        var channels = 1
        var sampleRate = 44100
        var bitsPerSample = 16
        var pcmData: ByteArray? = null

        // Iterate chunks
        while (buf.remaining() >= 8) {
            val chunkId = String(bytes, buf.position(), 4)
            buf.position(buf.position() + 4)
            val chunkSize = buf.getInt()

            when (chunkId) {
                "fmt " -> {
                    val fmtStart = buf.position()
                    buf.getShort() // audio format (1 = PCM)
                    channels = buf.getShort().toInt()
                    sampleRate = buf.getInt()
                    buf.getInt() // byte rate
                    buf.getShort() // block align
                    bitsPerSample = buf.getShort().toInt()
                    // Skip any extra fmt bytes
                    buf.position(fmtStart + chunkSize)
                }
                "data" -> {
                    val dataSize = minOf(chunkSize, buf.remaining())
                    pcmData = ByteArray(dataSize)
                    buf.get(pcmData)
                    break // got what we need
                }
                else -> {
                    // Skip unknown chunks (LIST, INFO, etc.)
                    val skip = chunkSize + (chunkSize % 2) // WAV chunks are word-aligned
                    if (skip > buf.remaining()) break
                    buf.position(buf.position() + skip)
                }
            }
        }

        return ParsedWav(
            channels = channels,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            pcmData = pcmData ?: ByteArray(0)
        )
    }

    /**
     * Crossfade the first and last N samples for seamless looping.
     */
    private fun crossfadeLoop(pcm: FloatArray, fadeLen: Int): FloatArray {
        if (pcm.size < fadeLen * 2) return pcm
        val result = pcm.copyOf()
        for (i in 0 until fadeLen) {
            val t = i.toFloat() / fadeLen
            result[i] = pcm[i] * t + pcm[pcm.size - fadeLen + i] * (1f - t)
        }
        // Trim the tail that was blended into the head
        return result.copyOf(pcm.size - fadeLen)
    }

    private fun convert16BitToFloat(bytes: ByteArray): FloatArray {
        val n = bytes.size / 2
        val result = FloatArray(n)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) result[i] = buf.getShort().toFloat() / 32768f
        return result
    }

    private fun convert24BitToFloat(bytes: ByteArray): FloatArray {
        val n = bytes.size / 3
        val result = FloatArray(n)
        for (i in 0 until n) {
            val b0 = bytes[i * 3].toInt() and 0xFF
            val b1 = bytes[i * 3 + 1].toInt() and 0xFF
            val b2 = bytes[i * 3 + 2].toInt()
            result[i] = ((b2 shl 16) or (b1 shl 8) or b0).toFloat() / 8388608f
        }
        return result
    }

    private fun mixToMono(stereo: FloatArray, channels: Int): FloatArray {
        val mono = FloatArray(stereo.size / channels)
        for (i in mono.indices) {
            var sum = 0f
            for (ch in 0 until channels) sum += stereo[i * channels + ch]
            mono[i] = sum / channels
        }
        return mono
    }
}
