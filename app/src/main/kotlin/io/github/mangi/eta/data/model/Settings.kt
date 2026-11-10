package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val appearance: AppearanceSettings = AppearanceSettings(),
    /** 已看过的引导包及其版本号；键=包名，值=版本。默认空表示都没看过。 */
    val seenTourPacks: Map<String, Int> = emptyMap(),
)
