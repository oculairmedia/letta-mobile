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
        private const val MIN_FIELD_MATCH_SCORE = 60
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
        var weightedScoreSum = 0.0
        var matchedWeightSum = 0.0

        // Name matching (highest weight)
        val nameScore = getBestMatchScore(agent.name, query)
        if (nameScore > 0) {
            weightedScoreSum += nameScore * NAME_WEIGHT
            matchedWeightSum += NAME_WEIGHT
        }

        // Tags matching
        val tagScores = agent.tags.map { tag ->
            getBestMatchScore(tag, query)
        }
        val bestTagScore = tagScores.maxOrNull() ?: 0
        if (bestTagScore > 0) {
            weightedScoreSum += bestTagScore * TAG_WEIGHT
            matchedWeightSum += TAG_WEIGHT
        }

        // Model matching
        agent.model?.let { model ->
            val modelScore = getBestMatchScore(model, query)
            if (modelScore > 0) {
                weightedScoreSum += modelScore * MODEL_WEIGHT
                matchedWeightSum += MODEL_WEIGHT
            }
        }

        // Description matching (lowest weight)
        agent.description?.let { desc ->
            val descScore = getBestMatchScore(desc, query)
            if (descScore > 0) {
                weightedScoreSum += descScore * DESCRIPTION_WEIGHT
                matchedWeightSum += DESCRIPTION_WEIGHT
            }
        }

        return if (matchedWeightSum > 0) {
            (weightedScoreSum / matchedWeightSum).toInt()
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

        if (query.contains('/') || query.contains('-')) {
            val segments = normalizedText
                .split('/', '-', '_', ' ')
                .filter { it.isNotBlank() }
            if (segments.any { it == query || it.contains(query) }) {
                return 95
            }
        }

        // Use multiple fuzzy matching strategies and take the best
        val ratioScore = FuzzySearch.ratio(normalizedText, query)
        val partialRatioScore = FuzzySearch.partialRatio(normalizedText, query)
        val tokenSortScore = FuzzySearch.tokenSortRatio(normalizedText, query)
        val tokenSetScore = FuzzySearch.tokenSetRatio(normalizedText, query)

        val fuzzyScore = maxOf(ratioScore, partialRatioScore, tokenSortScore, tokenSetScore)
        return if (fuzzyScore >= MIN_FIELD_MATCH_SCORE) fuzzyScore else 0
    }
}
