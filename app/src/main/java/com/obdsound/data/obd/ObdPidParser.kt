package com.obdsound.data.obd

object ObdPidParser {

    /**
     * Parse RPM from "41 0C XX YY" response.
     * RPM = ((A * 256) + B) / 4
     */
    fun parseRpm(response: String): Int? {
        val bytes = extractDataBytes(response, "410C") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 4
    }

    /**
     * Parse vehicle speed from "41 0D XX" response.
     * Speed = A (km/h)
     */
    fun parseSpeed(response: String): Int? {
        val bytes = extractDataBytes(response, "410D") ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0]
    }

    /**
     * Parse throttle position from "41 11 XX" response.
     * Throttle = (A * 100) / 255
     */
    fun parseThrottle(response: String): Float? {
        val bytes = extractDataBytes(response, "4111") ?: return null
        if (bytes.isEmpty()) return null
        return (bytes[0] * 100f) / 255f
    }

    /**
     * Parse engine load from "41 04 XX" response.
     * Load = (A * 100) / 255
     */
    fun parseLoad(response: String): Float? {
        val bytes = extractDataBytes(response, "4104") ?: return null
        if (bytes.isEmpty()) return null
        return (bytes[0] * 100f) / 255f
    }

    /**
     * Strip spaces from response, find the header, return remaining data bytes as ints.
     */
    private fun extractDataBytes(response: String, header: String): List<Int>? {
        val clean = response.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        val headerIndex = clean.indexOf(header)
        if (headerIndex < 0) return null

        val dataHex = clean.substring(headerIndex + header.length)
        if (dataHex.length < 2) return null

        return dataHex.chunked(2).mapNotNull { hex ->
            hex.toIntOrNull(16)
        }
    }
}
