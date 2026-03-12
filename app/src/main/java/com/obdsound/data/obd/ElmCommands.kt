package com.obdsound.data.obd

object ElmCommands {
    // Initialization
    const val RESET = "ATZ"
    const val ECHO_OFF = "ATE0"
    const val LINEFEED_OFF = "ATL0"
    const val SPACES_OFF = "ATS0"
    const val AUTO_PROTOCOL = "ATSP0"
    const val HEADERS_OFF = "ATH0"

    // OBD-II PID requests (Mode 01)
    const val RPM = "010C"
    const val SPEED = "010D"
    const val THROTTLE = "0111"
    const val ENGINE_LOAD = "0104"

    // ELM327 prompt character
    const val PROMPT = '>'

    // SPP UUID for RFCOMM
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
}
