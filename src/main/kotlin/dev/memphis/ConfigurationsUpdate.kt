package dev.memphis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ConfigurationsUpdate(
    @SerialName("station_name") val stationName: String,
    val type: String,
    val update: Boolean
)
