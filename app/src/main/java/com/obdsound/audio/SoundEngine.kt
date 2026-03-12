package com.obdsound.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.obdsound.domain.model.EngineData
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * Real-time low-latency audio engine.
 * 4 layers crossfaded by RPM + throttle, pitch via detune.
 * Per-sample interpolation for smooth transitions.
 */
class SoundEngine(private val sampleBank: SampleBank) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BUFFER_FRAMES = 256  // ~5.8ms per buffer - low latency
    }

    private var carPack: SampleBank.CarPack? = null
    private var audioTrack: AudioTrack? = null
    private var renderThread: Thread? = null
    @Volatile private var running = false

    // Direct volatile writes from simulation thread, read by audio thread
    @Volatile private var targetRpm = 1000f
    @Volatile private var targetThrottle = 0f
    private var currentRpm = 1000f
    private var currentThrottle = 0f

    // Vehicle RPM scaling: 0 = no scaling (demo mode), >0 = scale OBD RPM to sound range
    @Volatile private var vehicleMaxRpm = 0f

    private var posOnLow = 0.0
    private var posOnHigh = 0.0
    private var posOffLow = 0.0
    private var posOffHigh = 0.0
    private var posLimiter = 0.0

    fun loadCar(car: CarSound): SampleBank.CarPack {
        val wasRunning = running
        if (wasRunning) stop()
        val pack = sampleBank.loadCar(car)
        carPack = pack
        if (wasRunning) start()
        return pack
    }

    fun start() {
        if (running) return
        if (carPack == null) carPack = sampleBank.loadCar(CarSound.BAC_MONO)

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)  // lower latency than MEDIA
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)  // small buffer, avoids underrun pops
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        currentRpm = 1000f
        currentThrottle = 0f
        posOnLow = 0.0; posOnHigh = 0.0; posOffLow = 0.0; posOffHigh = 0.0; posLimiter = 0.0
        running = true
        audioTrack?.play()

        renderThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = FloatArray(BUFFER_FRAMES)
            while (running) {
                fillBuffer(buffer)
                audioTrack?.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
        }, "SoundEngine-Render").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        renderThread?.join(1000)
        renderThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun setVehicleMaxRpm(maxRpm: Int) {
        vehicleMaxRpm = maxRpm.toFloat()
    }

    fun updateEngineData(data: EngineData) {
        // Scale real vehicle RPM to sound pack RPM range
        val vMax = vehicleMaxRpm
        val pack = carPack
        if (vMax > 0f && pack != null) {
            targetRpm = data.rpm.toFloat() * (pack.engineLimiter / vMax)
        } else {
            targetRpm = data.rpm.toFloat()
        }
        targetThrottle = data.throttlePercent
    }

    fun updateFromSimulation(rpm: Float, throttle: Float) {
        targetRpm = rpm
        targetThrottle = throttle * 100f
    }

    private fun fillBuffer(buffer: FloatArray) {
        val pack = carPack ?: return buffer.fill(0f).let { return }

        val tRpm = targetRpm
        val tThr = targetThrottle

        // Fast per-sample interpolation — respond within ~2ms
        // At 44100 Hz: alpha 0.003 → 63% in ~330 samples (~7.5ms)
        val rpmAlpha = 0.003f
        val thrAlpha = 0.005f

        val softLimiter = pack.softLimiter
        val limiterRpm = pack.engineLimiter
        val rpmPitchFactor = 0.2f

        // Dynamic crossfade range from sample native RPMs
        val sampleRpmLow = minOf(pack.onLow.nativeRpm, pack.offLow.nativeRpm).toFloat()
        val sampleRpmHigh = maxOf(pack.onHigh.nativeRpm, pack.offHigh.nativeRpm).toFloat()
        // If samples have distinct RPMs, use those; otherwise default to 30%-70% of limiter
        val rpmLow: Float
        val rpmRange: Float
        if (sampleRpmHigh > sampleRpmLow * 1.2f) {
            rpmLow = sampleRpmLow
            rpmRange = sampleRpmHigh - sampleRpmLow
        } else {
            rpmLow = limiterRpm * 0.3f
            rpmRange = limiterRpm * 0.4f
        }

        for (i in buffer.indices) {
            currentRpm += (tRpm - currentRpm) * rpmAlpha
            currentThrottle += (tThr - currentThrottle) * thrAlpha

            // Equal-power crossfade by RPM (dynamic range from sample RPMs)
            val rpmBlend = ((currentRpm - rpmLow) / rpmRange).coerceIn(0f, 1f)
            val highGain = cos((1f - rpmBlend) * 0.5f * PI.toFloat())
            val lowGain = cos(rpmBlend * 0.5f * PI.toFloat())

            // Equal-power crossfade by throttle
            val thrNorm = (currentThrottle / 100f).coerceIn(0f, 1f)
            val onGain = cos((1f - thrNorm) * 0.5f * PI.toFloat())
            val offGain = cos(thrNorm * 0.5f * PI.toFloat())

            // Limiter gain
            val limBlend = ((currentRpm - softLimiter * 0.93f) / (limiterRpm - softLimiter * 0.93f))
                .coerceIn(0f, 1f)

            // Per-sample pitch
            val pitchOnLow = getPitch(currentRpm, pack.onLow.nativeRpm.toFloat(), rpmPitchFactor)
            val pitchOnHigh = getPitch(currentRpm, pack.onHigh.nativeRpm.toFloat(), rpmPitchFactor)
            val pitchOffLow = getPitch(currentRpm, pack.offLow.nativeRpm.toFloat(), rpmPitchFactor)
            val pitchOffHigh = getPitch(currentRpm, pack.offHigh.nativeRpm.toFloat(), rpmPitchFactor)

            var mix = 0f
            mix += readSample(pack.onLow.pcm, posOnLow) * onGain * lowGain * pack.onLow.volume
            mix += readSample(pack.onHigh.pcm, posOnHigh) * onGain * highGain * pack.onHigh.volume
            mix += readSample(pack.offLow.pcm, posOffLow) * offGain * lowGain * pack.offLow.volume
            mix += readSample(pack.offHigh.pcm, posOffHigh) * offGain * highGain * pack.offHigh.volume
            mix += readSample(pack.limiter.pcm, posLimiter) * limBlend * pack.limiter.volume

            posOnLow = advancePos(posOnLow, pitchOnLow, pack.onLow.pcm.size)
            posOnHigh = advancePos(posOnHigh, pitchOnHigh, pack.onHigh.pcm.size)
            posOffLow = advancePos(posOffLow, pitchOffLow, pack.offLow.pcm.size)
            posOffHigh = advancePos(posOffHigh, pitchOffHigh, pack.offHigh.pcm.size)
            posLimiter = advancePos(posLimiter, 1.0, pack.limiter.pcm.size)

            // Fade to silence below 80 RPM (engine off / dying)
            val masterVol = (currentRpm / 80f).coerceIn(0f, 1f)
            buffer[i] = (mix * masterVol).coerceIn(-1f, 1f)
        }
    }

    private fun getPitch(rpm: Float, sampleRpm: Float, factor: Float): Double {
        val detuneCents = (rpm - sampleRpm) * factor
        return 2.0.pow(detuneCents / 1200.0)
    }

    private fun readSample(pcm: FloatArray, pos: Double): Float {
        if (pcm.isEmpty()) return 0f
        val idx = pos.toInt() % pcm.size
        val frac = (pos - pos.toInt()).toFloat()
        val next = (idx + 1) % pcm.size
        return pcm[idx] * (1f - frac) + pcm[next] * frac
    }

    private fun advancePos(pos: Double, pitch: Double, size: Int): Double {
        if (size == 0) return 0.0
        var p = pos + pitch
        while (p >= size) p -= size
        return p
    }
}
