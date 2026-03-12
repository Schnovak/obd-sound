package com.obdsound.domain.model

data class EngineData(
    val rpm: Int = 0,
    val speedKmh: Int = 0,
    val throttlePercent: Float = 0f,
    val engineLoadPercent: Float = 0f
)
