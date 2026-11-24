package com.gfs.iyango.data.repository

import com.gfs.iyango.core.model.HomePreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class HomePreferencesDto(
    val moduleOrder: List<String> = HomePreferences().moduleOrder,
    val hiddenModules: Set<String> = emptySet(),
    val quickActions: List<String> = HomePreferences().quickActions,
    val version: Int = 1,
) {
    fun toModel(): HomePreferences = HomePreferences(moduleOrder, hiddenModules, quickActions, version).sanitized()
    companion object {
        fun fromModel(model: HomePreferences): HomePreferencesDto = model.sanitized().let {
            HomePreferencesDto(it.moduleOrder, it.hiddenModules, it.quickActions)
        }
    }
}

/** A damaged homepage cannot reset otherwise valid wallpaper/navigation preferences. */
internal object SafeHomePreferencesSerializer : JsonTransformingSerializer<HomePreferencesDto>(HomePreferencesDto.serializer()) {
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val safe = runCatching { codec.decodeFromJsonElement(HomePreferencesDto.serializer(), element) }
            .getOrDefault(HomePreferencesDto()).let { HomePreferencesDto.fromModel(it.toModel()) }
        return codec.encodeToJsonElement(HomePreferencesDto.serializer(), safe)
    }
}
