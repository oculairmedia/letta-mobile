package com.letta.mobile.domain

import com.letta.mobile.data.model.Agent
import me.xdrop.fuzzywuzzy.FuzzySearch
import javax.inject.Inject
import javax.inject.Singleton

data class AgentSearchResult(
    val agent: Agent,
    val score: Int
)

@Singleton
class AgentSearch @Inject constructor() {

    companion object {
        private const val MIN_SCORE_THRESHOLD = 40
        private const val NAME_WEIGHT = 3.0
        private const val TAG_WEIGHT = 2.0
        private const val MODEL_WEIGHT = 1.5
        private const val DESCRIPTION_WEIGHT = 1.0
    }

    fun search(agents: List<Agent>, query: String): List<Agent> {
        if (query.isBlank()) return agents

        val normalizedQuery = query.trim().lowercase()

        return agents
            .map { agent -> AgentSearchResult(agent, calculateScore(agent, normalizedQuery)) }
            .filter { it.score >= MIN_SCORE_THRESHOLD }
            .sortedByDescending { it.score }
            .map { it.agent }
    }

    private fun calculateScore(agent: Agent, query: String): Int {
        var totalScore = 0.0
        var maxPossibleWeight = 0.0

        // Name matching (highest weight)
        val nameScore = getBestMatchScore(agent.name, query)
        totalScore += nameScore * NAME_WEIGHT
        maxPossibleWeight += NAME_WEIGHT

        // Tags matching
        val tagScores = agent.tags?.map { tag ->
            getBestMatchScore(tag, query)
        } ?: emptyList()
        if (tagScores.isNotEmpty()) {
            totalScore += (tagScores.maxOrNull() ?: 0) * TAG_WEIGHT
            maxPossibleWeight += TAG_WEIGHT
        }

        // Model matching
        agent.model?.let { model ->
            val modelScore = getBestMatchScore(model, query)
            totalScore += modelScore * MODEL_WEIGHT
            maxPossibleWeight += MODEL_WEIGHT
        }

        // Description matching (lowest weight)
        agent.description?.let { desc ->
            val descScore = getBestMatchScore(desc, query)
            totalScore += descScore * DESCRIPTION_WEIGHT
            maxPossibleWeight += DESCRIPTION_WEIGHT
        }

        return if (maxPossibleWeight > 0) {
            (totalScore / maxPossibleWeight).toInt()
        } else {
            0
        }
    }

    private fun getBestMatchScore(text: String, query: String): Int {
        val normalizedText = text.lowercase()

        // Exact contains gets a boost
        if (normalizedText.contains(query)) {
            return 100
        }

        // Use multiple fuzzy matching strategies and take the best
        val ratioScore = FuzzySearch.ratio(normalizedText, query)
        val partialRatioScore = FuzzySearch.partialRatio(normalizedText, query)
        val tokenSortScore = FuzzySearch.tokenSortRatio(normalizedText, query)
        val tokenSetScore = FuzzySearch.tokenSetRatio(normalizedText, query)

        return maxOf(ratioScore, partialRatioScore, tokenSortScore, tokenSetScore)
    }
}
