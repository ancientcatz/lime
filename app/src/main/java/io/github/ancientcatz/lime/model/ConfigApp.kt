package io.github.ancientcatz.lime.model

import kotlinx.serialization.Serializable

@Serializable
data class ConfigApp(
    val packageName: String,
    val orientation: Int = Orientation.UNSPECIFIED.ordinal,
)