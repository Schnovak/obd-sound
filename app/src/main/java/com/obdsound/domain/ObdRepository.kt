package com.obdsound.domain

import android.bluetooth.BluetoothDevice
import com.obdsound.domain.model.ConnectionState
import com.obdsound.domain.model.EngineData
import kotlinx.coroutines.flow.StateFlow

interface ObdRepository {
    val engineData: StateFlow<EngineData>
    val connectionState: StateFlow<ConnectionState>

    suspend fun connect(device: BluetoothDevice)
    fun disconnect()
    fun getPairedDevices(): List<BluetoothDevice>
}
