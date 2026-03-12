package com.obdsound.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class ObdConnection {

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    suspend fun connect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        disconnect()

        val sppUuid = UUID.fromString(ElmCommands.SPP_UUID)
        val btSocket = try {
            device.createRfcommSocketToServiceRecord(sppUuid)
        } catch (e: IOException) {
            // Fallback for cheap ELM327 clones
            device.createInsecureRfcommSocketToServiceRecord(sppUuid)
        }

        try {
            btSocket.connect()
        } catch (e: IOException) {
            // Last resort: reflection-based fallback
            try {
                btSocket.close()
            } catch (_: IOException) {}

            val fallback = device.javaClass
                .getMethod("createRfcommSocket", Int::class.java)
                .invoke(device, 1) as BluetoothSocket
            fallback.connect()
            socket = fallback
            inputStream = fallback.inputStream
            outputStream = fallback.outputStream
            initElm()
            return@withContext
        }

        socket = btSocket
        inputStream = btSocket.inputStream
        outputStream = btSocket.outputStream
        initElm()
    }

    private suspend fun initElm() {
        // Reset - wait longer for this one
        sendCommand(ElmCommands.RESET, timeoutMs = 3000)
        sendCommand(ElmCommands.ECHO_OFF)
        sendCommand(ElmCommands.LINEFEED_OFF)
        sendCommand(ElmCommands.SPACES_OFF)
        sendCommand(ElmCommands.HEADERS_OFF)
        sendCommand(ElmCommands.AUTO_PROTOCOL)
    }

    suspend fun sendCommand(command: String, timeoutMs: Long = 2000): String =
        withContext(Dispatchers.IO) {
            val os = outputStream ?: throw IOException("Not connected")
            val inp = inputStream ?: throw IOException("Not connected")

            os.write("$command\r".toByteArray())
            os.flush()

            withTimeout(timeoutMs) {
                readUntilPrompt(inp)
            }
        }

    private fun readUntilPrompt(input: InputStream): String {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("Stream ended")
            val c = b.toChar()
            if (c == ElmCommands.PROMPT) break
            buffer.append(c)
        }
        return buffer.toString().trim()
    }

    fun disconnect() {
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        inputStream = null
        outputStream = null
    }
}
