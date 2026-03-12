package com.obdsound.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.obdsound.data.obd.ElmCommands
import com.obdsound.data.obd.ObdConnection
import com.obdsound.data.obd.ObdPidParser
import com.obdsound.domain.ObdRepository
import com.obdsound.domain.model.ConnectionState
import com.obdsound.domain.model.EngineData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

@SuppressLint("MissingPermission")
class ObdRepositoryImpl(private val context: Context) : ObdRepository {

    private val connection = ObdConnection()
    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _engineData = MutableStateFlow(EngineData())
    override val engineData: StateFlow<EngineData> = _engineData.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Track PIDs that consistently fail so we can skip them
    private val pidFailCounts = mutableMapOf<String, Int>()
    private val maxFailsBeforeSkip = 3

    override suspend fun connect(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting
        try {
            connection.connect(device)
            _connectionState.value = ConnectionState.Connected(device.name ?: "Unknown")
            pidFailCounts.clear()
            startPolling()
        } catch (e: Exception) {
            connection.disconnect()
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
        }
    }

    override fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        connection.disconnect()
        _connectionState.value = ConnectionState.Disconnected
        _engineData.value = EngineData()
    }

    override fun getPairedDevices(): List<BluetoothDevice> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: return emptyList()
        return adapter.bondedDevices?.toList() ?: emptyList()
    }

    private fun startPolling() {
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val rpm = queryPid(ElmCommands.RPM) { ObdPidParser.parseRpm(it) } ?: _engineData.value.rpm
                    val speed = queryPid(ElmCommands.SPEED) { ObdPidParser.parseSpeed(it) } ?: _engineData.value.speedKmh
                    val throttle = queryPid(ElmCommands.THROTTLE) { ObdPidParser.parseThrottle(it) } ?: _engineData.value.throttlePercent
                    val load = queryPid(ElmCommands.ENGINE_LOAD) { ObdPidParser.parseLoad(it) } ?: _engineData.value.engineLoadPercent

                    _engineData.value = EngineData(
                        rpm = rpm,
                        speedKmh = speed,
                        throttlePercent = throttle,
                        engineLoadPercent = load
                    )
                    yield()  // allow cancellation between poll cycles
                } catch (e: IOException) {
                    _connectionState.value = ConnectionState.Error("Connection lost: ${e.message}")
                    connection.disconnect()
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(100)
                }
            }
        }
    }

    private suspend fun <T> queryPid(pid: String, parser: (String) -> T?): T? {
        if ((pidFailCounts[pid] ?: 0) >= maxFailsBeforeSkip) return null
        return try {
            val response = connection.sendCommand(pid)
            if (response.contains("NO DATA") || response.contains("ERROR")) {
                pidFailCounts[pid] = (pidFailCounts[pid] ?: 0) + 1
                null
            } else {
                pidFailCounts[pid] = 0
                parser(response)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            pidFailCounts[pid] = (pidFailCounts[pid] ?: 0) + 1
            null
        }
    }
}
