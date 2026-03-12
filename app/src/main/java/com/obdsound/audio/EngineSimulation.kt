package com.obdsound.audio

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Engine physics simulation with startup/shutdown, progressive throttle,
 * and per-car tuning. Based on markeasting/engine-audio (MIT license).
 */
class EngineSimulation {

    enum class EngineState { OFF, CRANKING, RUNNING, STOPPING }

    // === User input (set by UI) ===
    var gasPressed = false
    var braking = false

    // === Readable output ===
    var rpm = 0f
        private set
    var gear = 0
        private set
    var engineState = EngineState.OFF
        private set
    var throttlePosition = 0.0
        private set
    var brakePosition = 0.0
        private set

    // === Per-car engine params (set via setCarParams) ===
    private var idle = 1000.0
    private var limiter = 9000.0
    private var softLimiter = 8950.0
    private var torqueNm = 400.0
    private var engineBrakingNm = 200.0
    private var engineInertia = 1.0
    private val limiterMs = 0.0
    private val limiterDelay = 100.0

    // Engine.throttle
    private var throttle = 0.0

    // Integration state
    private var eTheta = 0.0
    private var eOmega = 0.0
    private var ePrevTheta = 0.0
    private var lastLimiterTime = -10000.0

    // === Drivetrain ===
    private var gears = doubleArrayOf(3.4, 2.36, 1.85, 1.47, 1.24, 1.07)
    private val dtInertia = 0.15
    private val dtDamping = 12.0
    private val dtCompliance = 0.01
    private val shiftTimeMs = 120.0

    private var dtTheta = 0.0
    private var dtOmega = 0.0
    private var dtPrevTheta = 0.0

    // Shift state
    private var shifting = false
    private var shiftStartSimTime = 0.0
    private var pendingGear = 0
    var downShift = false
        private set
    private var upShift = false
    private var shiftThrottleTimer = 0.0

    // Time tracking
    private var simTime = 0.0

    // Cranking state
    private var crankStartTime = 0.0
    private val crankDurationMs = 1200.0

    fun setCarParams(
        idleRpm: Float, limiterRpm: Float, softLimiterRpm: Float,
        torque: Float, braking: Float, inertia: Float, gearRatios: DoubleArray
    ) {
        idle = idleRpm.toDouble()
        limiter = limiterRpm.toDouble()
        softLimiter = softLimiterRpm.toDouble()
        torqueNm = torque.toDouble()
        engineBrakingNm = braking.toDouble()
        engineInertia = inertia.toDouble()
        gears = gearRatios
        if (gear > gears.size) gear = 0
    }

    fun startEngine() {
        if (engineState == EngineState.OFF || engineState == EngineState.STOPPING) {
            engineState = EngineState.CRANKING
            crankStartTime = simTime
            eOmega = 0.0
            eTheta = 0.0
            ePrevTheta = 0.0
            dtOmega = 0.0
            dtTheta = 0.0
            dtPrevTheta = 0.0
            gear = 0
            throttle = 0.0
            throttlePosition = 0.0
            brakePosition = 0.0
            lastLimiterTime = -10000.0
        }
    }

    fun stopEngine() {
        if (engineState == EngineState.RUNNING || engineState == EngineState.CRANKING) {
            engineState = EngineState.STOPPING
            gear = 0
            shifting = false
            downShift = false
            upShift = false
        }
    }

    /**
     * Called at ~120Hz.
     */
    fun update(dtSeconds: Float) {
        val dt = dtSeconds.toDouble()
        simTime += dt * 1000.0

        when (engineState) {
            EngineState.OFF -> {
                rpm = 0f
                throttlePosition = 0.0
                return
            }
            EngineState.CRANKING -> {
                updateCranking(dt)
                return
            }
            EngineState.STOPPING, EngineState.RUNNING -> {
                // Continue to normal update below
            }
        }

        // === Progressive throttle with shift effects ===
        shiftThrottleTimer = max(0.0, shiftThrottleTimer - dt)

        if (upShift && shiftThrottleTimer > 0) {
            throttle = max(0.0, throttle - 0.6 * (dt / 0.016))
        } else if (downShift) {
            val blipTarget = 0.7
            throttle = min(blipTarget, throttle + 0.5 * (dt / 0.016))
        } else if (engineState == EngineState.STOPPING) {
            // Engine dying — throttle closed quickly
            throttle = max(0.0, throttle - dt / 0.2)
        } else if (gasPressed) {
            // Progressive pedal: 0→1 in ~1.0 second
            throttle = min(1.0, throttle + dt / 1.0)
        } else {
            // Release: 1→0 in ~0.4 seconds
            throttle = max(0.0, throttle - dt / 0.4)
        }

        throttlePosition = throttle

        if (upShift && shiftThrottleTimer <= 0) upShift = false

        // === Progressive braking: 0→full in ~0.6s, release in ~0.3s ===
        if (braking) {
            brakePosition = min(1.0, brakePosition + dt / 0.6)
        } else {
            brakePosition = max(0.0, brakePosition - dt / 0.3)
        }
        if (brakePosition > 0) {
            dtOmega -= 0.3 * brakePosition * (dt / 0.016)
            val minOmega = if (gear > 0) idle * 0.8 / (30.0 * PI) else 0.0
            if (dtOmega < minOmega) dtOmega = minOmega
        }

        // === Shift completion (simulation clock) ===
        if (shifting && simTime - shiftStartSimTime >= shiftTimeMs) {
            completeShift()
        }

        // === Sub-stepped physics (20 substeps) ===
        val subSteps = 20
        val h = dt / subSteps

        for (i in 0 until subSteps) {
            integrateEngine(simTime + dt * 1000.0 * i / subSteps, h)
            dtPrevTheta = dtTheta
            dtTheta += dtOmega * h

            if (gear > 0) {
                val compliance = max(0.0006 - 0.00015 * gear, 0.00007)
                val c = dtTheta - eTheta
                val corr = engineGetCorrection(c, h, compliance)
                eTheta += corr * sign(c)
            }
            if (gear > 0) {
                val c = eTheta - dtTheta
                val corr = dtGetCorrection(c, h, dtCompliance)
                dtTheta += corr * sign(c)
            }

            eOmega = (eTheta - ePrevTheta) / h
            dtOmega = (dtTheta - dtPrevTheta) / h

            if (gear > 0) {
                val damping = if (gear > 3) 9.0 else 12.0
                eOmega += (dtOmega - eOmega) * damping * h
            }
            if (gear > 0) {
                val damping = if (gear > 3) dtDamping * 0.75 else dtDamping
                dtOmega += (eOmega - dtOmega) * damping * h
            }
        }

        // Clamp engine omega
        val maxOmega = limiter * 1.05 / (30.0 * PI)
        val minIdleOmega = if (engineState == EngineState.STOPPING) 0.0
                           else idle * 0.5 / (30.0 * PI)
        if (eOmega > maxOmega) eOmega = maxOmega
        if (eOmega < minIdleOmega) eOmega = minIdleOmega

        // Engine.ts: this.rpm = (60 * this.omega) / 2 * Math.PI
        rpm = ((60.0 * eOmega) / 2.0 * PI).toFloat().coerceAtLeast(0f)

        // Engine died
        if (engineState == EngineState.STOPPING && rpm < 100f) {
            engineState = EngineState.OFF
            eOmega = 0.0
            rpm = 0f
        }
    }

    private fun updateCranking(dt: Double) {
        val elapsed = simTime - crankStartTime
        val progress = (elapsed / crankDurationMs).coerceIn(0.0, 1.0)

        if (progress < 0.65) {
            // Starter motor — uneven cranking at ~180 RPM with compression wobble
            val crankRpm = 180.0 + 40.0 * sin(elapsed * 0.025)
            eOmega = crankRpm / (30.0 * PI)
        } else {
            // Engine catches — ramp toward idle with accelerating curve
            val catchProgress = (progress - 0.65) / 0.35
            val crankRpm = 180.0 + 40.0 * sin(elapsed * 0.025)
            val crankOmega = crankRpm / (30.0 * PI)
            val idleOmega = idle / (30.0 * PI)
            eOmega = crankOmega + (idleOmega - crankOmega) * catchProgress * catchProgress
        }

        ePrevTheta = eTheta
        eTheta += eOmega * dt
        dtTheta = eTheta
        dtPrevTheta = ePrevTheta
        dtOmega = eOmega

        rpm = ((60.0 * eOmega) / 2.0 * PI).toFloat().coerceAtLeast(0f)
        throttlePosition = 0.0

        if (progress >= 1.0) {
            engineState = EngineState.RUNNING
            eOmega = idle / (30.0 * PI)
        }
    }

    /**
     * Engine.integrate — torque model with per-car tuning.
     */
    private fun integrateEngine(timeMs: Double, h: Double) {
        // Limiter
        if (rpm >= softLimiter) {
            val r = ratio(rpm.toDouble(), softLimiter, limiter)
            throttle *= (1.0 - r).pow(0.05)
        }
        if (rpm >= limiter) {
            lastLimiterTime = timeMs
        }
        if (timeMs - lastLimiterTime >= limiterMs) {
            val t = timeMs - lastLimiterTime
            val r = ratio(t, 0.0, limiterDelay)
            throttle *= r
        } else {
            throttle = 0.0
        }

        // Idle controller — disabled when stopping
        var idleTorque = 0.0
        if (engineState != EngineState.STOPPING && throttle < 0.1 && rpm < idle * 1.5) {
            val rIdle = ratio(rpm.toDouble(), idle * 0.9, idle)
            val idleStrength = if (gear > 0) engineBrakingNm * 2.0 else engineBrakingNm * 0.8
            idleTorque = (1.0 - rIdle) * idleStrength
        }

        // Torque
        val t1 = throttle.pow(1.2) * torqueNm
        val brakingScale = if (gear > 0) 1.0 else 0.35
        val t2 = (1.0 - throttle).pow(1.2) * engineBrakingNm * brakingScale
        val netTorque = t1 - t2 + idleTorque

        // Integrate
        val dAlpha = netTorque / engineInertia
        ePrevTheta = eTheta
        eOmega += dAlpha * h
        eTheta += eOmega * h
    }

    private fun engineGetCorrection(corr: Double, h: Double, compliance: Double): Double {
        val w = corr * corr * (1.0 / engineInertia)
        val dlambda = -corr / (w + compliance / h / h)
        return corr * -dlambda
    }

    private fun dtGetCorrection(corr: Double, h: Double, compliance: Double): Double {
        val w = corr * corr * (1.0 / dtInertia)
        val dlambda = -corr / (w + compliance / h / h)
        return corr * -dlambda
    }

    // === Gear shifting ===
    fun shiftUp() {
        if (engineState == EngineState.RUNNING && gear < gears.size && !shifting) {
            changeGear(gear + 1)
        }
    }

    fun shiftDown() {
        if (engineState == EngineState.RUNNING && gear > 0 && !shifting) {
            changeGear(gear - 1)
        }
    }

    private fun changeGear(newGear: Int) {
        val prevRatio = getGearRatio(gear)
        val nextRatio = getGearRatio(newGear)
        val ratioRatio = if (prevRatio > 0) nextRatio / prevRatio else 0.0

        if (ratioRatio == 1.0) return

        gear = 0

        if (ratioRatio > 1.0) {
            downShift = true
            shiftThrottleTimer = 0.15
        } else if (ratioRatio in 0.01..0.99) {
            upShift = true
            shiftThrottleTimer = 0.12
        }

        pendingGear = newGear
        shifting = true
        shiftStartSimTime = simTime
    }

    private fun completeShift() {
        val nextRatio = getGearRatio(pendingGear)

        val oldGearForRatio = if (downShift) pendingGear + 1 else pendingGear - 1
        val oldRatio = getGearRatio(oldGearForRatio)
        if (oldRatio > 0) {
            dtOmega *= (nextRatio / oldRatio)
            if (downShift) {
                eOmega = dtOmega
                eTheta = dtTheta
                ePrevTheta = dtPrevTheta
            }
        } else {
            // Engaging from neutral — simulate clutch bite
            dtTheta = eTheta
            dtPrevTheta = eTheta
            dtOmega = eOmega * 0.3
        }

        gear = pendingGear.coerceIn(0, gears.size)
        downShift = false
        shifting = false
    }

    private fun getGearRatio(g: Int): Double {
        return if (g in 1..gears.size) gears[g - 1] else 0.0
    }

    private fun ratio(value: Double, min: Double, max: Double): Double {
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    }
}
