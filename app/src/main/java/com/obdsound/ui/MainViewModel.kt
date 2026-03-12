package com.obdsound.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obdsound.audio.CarSound
import com.obdsound.audio.EngineSimulation
import com.obdsound.audio.SampleBank
import com.obdsound.audio.SoundEngine
import com.obdsound.data.ObdRepositoryImpl
import com.obdsound.domain.model.ConnectionState
import com.obdsound.domain.model.EngineData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DashboardUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val engineData: EngineData = EngineData(),
    val pairedDevices: List<BluetoothDevice> = emptyList(),
    val soundPlaying: Boolean = false,
    val demoMode: Boolean = true,
    val rpm: Float = 0f,
    val gear: Int = 0,
    val gasPressed: Boolean = false,
    val brakePressed: Boolean = false,
    val selectedCar: CarSound = CarSound.BAC_MONO,
    val vehicleMaxRpm: Int = 6500,
    val demoThrottle: Float = 0f,
    val demoBrake: Float = 0f
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ObdRepositoryImpl(application)
    private val sampleBank = SampleBank(application)
    private val soundEngine = SoundEngine(sampleBank)
    private val simulation = EngineSimulation()

    private var simulationJob: Job? = null

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.engineData.collect { data ->
                _uiState.update { it.copy(engineData = data) }
                if (_uiState.value.soundPlaying && !_uiState.value.demoMode) {
                    soundEngine.updateEngineData(data)
                }
            }
        }
        viewModelScope.launch {
            repository.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    fun loadPairedDevices() {
        _uiState.update { it.copy(pairedDevices = repository.getPairedDevices()) }
    }

    fun connect(device: BluetoothDevice) {
        viewModelScope.launch { repository.connect(device) }
    }

    fun disconnect() { repository.disconnect() }

    fun selectCar(car: CarSound) {
        _uiState.update { it.copy(selectedCar = car) }
        val pack = soundEngine.loadCar(car)
        applyCarParams(pack)
    }

    private fun applyCarParams(pack: SampleBank.CarPack) {
        simulation.setCarParams(
            idleRpm = pack.idleRpm,
            limiterRpm = pack.engineLimiter,
            softLimiterRpm = pack.softLimiter,
            torque = pack.torqueNm,
            braking = pack.engineBrakingNm,
            inertia = pack.inertia,
            gearRatios = pack.gearRatios
        )
    }

    fun toggleSound() {
        if (_uiState.value.soundPlaying) {
            // Begin shutdown sequence — engine dies down audibly
            simulation.stopEngine()
            // Simulation loop detects OFF state and stops audio
        } else {
            val pack = soundEngine.loadCar(_uiState.value.selectedCar)
            applyCarParams(pack)
            soundEngine.start()
            simulation.startEngine()
            _uiState.update { it.copy(soundPlaying = true) }
            if (_uiState.value.demoMode) startSimulation()
        }
    }

    fun toggleDemoMode() {
        val newDemo = !_uiState.value.demoMode
        _uiState.update { it.copy(demoMode = newDemo) }
        if (newDemo) {
            soundEngine.setVehicleMaxRpm(0)
            if (_uiState.value.soundPlaying) startSimulation()
        } else {
            simulationJob?.cancel(); simulationJob = null
            soundEngine.setVehicleMaxRpm(_uiState.value.vehicleMaxRpm)
        }
    }

    private fun startSimulation() {
        simulationJob?.cancel()
        simulationJob = viewModelScope.launch {
            var frameCount = 0
            while (isActive) {
                simulation.update(1f / 120f)
                soundEngine.updateFromSimulation(
                    simulation.rpm,
                    simulation.throttlePosition.toFloat()
                )

                // Engine shutdown complete — stop audio
                if (simulation.engineState == EngineSimulation.EngineState.OFF &&
                    _uiState.value.soundPlaying
                ) {
                    soundEngine.stop()
                    _uiState.update {
                        it.copy(soundPlaying = false, rpm = 0f, gear = 0, demoThrottle = 0f)
                    }
                    break
                }

                // Update UI at 60Hz
                if (frameCount++ % 2 == 0) {
                    _uiState.update {
                        it.copy(
                            rpm = simulation.rpm,
                            gear = simulation.gear,
                            demoThrottle = (simulation.throttlePosition * 100).toFloat(),
                            demoBrake = (simulation.brakePosition * 100).toFloat()
                        )
                    }
                }
                delay(8L)
            }
        }
    }

    fun setVehicleMaxRpm(rpm: Int) {
        val clamped = rpm.coerceIn(3000, 15000)
        _uiState.update { it.copy(vehicleMaxRpm = clamped) }
        soundEngine.setVehicleMaxRpm(clamped)
    }

    fun onGasPressed() { simulation.gasPressed = true; _uiState.update { it.copy(gasPressed = true) } }
    fun onGasReleased() { simulation.gasPressed = false; _uiState.update { it.copy(gasPressed = false) } }
    fun onBrakePressed() { simulation.braking = true; _uiState.update { it.copy(brakePressed = true) } }
    fun onBrakeReleased() { simulation.braking = false; _uiState.update { it.copy(brakePressed = false) } }
    fun shiftUp() { simulation.shiftUp() }
    fun shiftDown() { simulation.shiftDown() }

    override fun onCleared() {
        super.onCleared()
        soundEngine.stop()
        repository.disconnect()
    }
}
