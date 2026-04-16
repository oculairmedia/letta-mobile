package com.letta.mobile.bot.skills

import android.content.Context
import android.util.Log
import com.letta.mobile.data.model.ToolCreateParams
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class BotSkill(
    val id: String,
    val displayName: String,
    val description: String,
    val promptFragment: String,
    val localToolNames: Set<String> = emptySet(),
    val activationRule: BotSkillActivationRule = BotSkillActivationRule.Always,
    val documentation: String? = null,
)

sealed interface BotSkillActivationRule {
    fun isActive(date: LocalDate): Boolean

    data object Always : BotSkillActivationRule {
        override fun isActive(date: LocalDate): Boolean = true
    }

    data class WeekdayOnly(
        val weekdays: Set<DayOfWeek>,
    ) : BotSkillActivationRule {
        override fun isActive(date: LocalDate): Boolean = date.dayOfWeek in weekdays
    }
}

@Singleton
class BotSkillRegistry internal constructor(
    private val skillLoader: () -> List<BotSkill>,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(skillLoader = { loadBundledSkills(context) })

    private val skills: Map<String, BotSkill> by lazy {
        skillLoader().associateBy { it.id }
    }

    fun listAvailableSkills(): List<BotSkill> = skills.values.sortedBy { it.id }

    fun getSkill(skillId: String): BotSkill? = skills[skillId]

    fun findUnknownSkillIds(skillIds: List<String>): List<String> = skillIds
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .filterNot { it in skills }

    fun resolveEnabledSkills(
        skillIds: List<String>,
        date: LocalDate = LocalDate.now(),
    ): List<BotSkill> {
        return skillIds
            .mapNotNull { skills[it] }
            .filter { it.activationRule.isActive(date) }
    }

    internal companion object {
        private const val TAG = "BotSkillRegistry"
        private val json = Json { ignoreUnknownKeys = true }

        private fun loadBundledSkills(context: Context): List<BotSkill> {
            val assetManager = context.assets
            val index = runCatching {
                assetManager.open("bot-skills/index.json").bufferedReader().use { reader ->
                    json.decodeFromString<BotSkillIndex>(reader.readText())
                }
            }.getOrElse { error ->
                Log.w(TAG, "Failed to load bundled bot skill index", error)
                return emptyList()
            }

            return index.skills.mapNotNull { skillId ->
                runCatching {
                    val manifest = assetManager.open("bot-skills/$skillId/skill.json").bufferedReader().use { reader ->
                        json.decodeFromString<BotSkillManifest>(reader.readText())
                    }
                    val documentation = runCatching {
                        assetManager.open("bot-skills/$skillId/SKILL.md").bufferedReader().use { it.readText() }
                    }.getOrNull()

                    BotSkill(
                        id = manifest.id,
                        displayName = manifest.displayName,
                        description = manifest.description,
                        promptFragment = manifest.promptFragment ?: documentation?.trim().orEmpty(),
                        localToolNames = manifest.localTools.map { it.lowercase() }.toSet(),
                        activationRule = manifest.activation.toRule(),
                        documentation = documentation,
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "Failed to load bot skill '$skillId'", error)
                    null
                }
            }
        }
    }
}

@Serializable
private data class BotSkillIndex(
    val skills: List<String> = emptyList(),
)

@Serializable
private data class BotSkillManifest(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val description: String = "",
    @SerialName("prompt_fragment") val promptFragment: String? = null,
    @SerialName("local_tools") val localTools: List<String> = emptyList(),
    val activation: BotSkillActivationManifest = BotSkillActivationManifest(),
)

@Serializable
private data class BotSkillActivationManifest(
    val type: String = "always",
    val weekdays: List<Int> = emptyList(),
) {
    fun toRule(): BotSkillActivationRule = when (type.lowercase()) {
        "weekday_only" -> {
            val configuredDays = weekdays.mapNotNull { value ->
                DayOfWeek.entries.firstOrNull { it.value == value }
            }.toSet()
            BotSkillActivationRule.WeekdayOnly(
                weekdays = configuredDays.ifEmpty {
                    setOf(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY,
                    )
                }
            )
        }

        else -> BotSkillActivationRule.Always
    }
}
