package com.letta.mobile.data.chat.tools

/**
 * Whether a presenter ships with the app or a plugin's manifest contributed it. Bundled presenters
 * outrank contributed ones, so a plugin presents what nothing bundled claims (its own actions, a
 * server's custom tools, a CLI of its own) and cannot restyle a bundled tool into something it is not.
 */
internal enum class PresenterTier { Contributed, Bundled }

/** One registration: the presenter and the tier it was registered at. */
internal class RankedPresenter<T>(val presenter: T, val tier: PresenterTier)

/**
 * The presenter of [entries] that claims a call most strongly: the highest tier first, then the
 * highest [specificity] (null when it does not claim the call), and among equals the one
 * registered last.
 */
internal inline fun <T> List<RankedPresenter<T>>.strongestClaim(specificity: (T) -> Int?): T? {
    var best: RankedPresenter<T>? = null
    var bestSpecificity = 0
    for (entry in this) {
        val claim = specificity(entry.presenter) ?: continue
        if (best == null || entry.outranks(best, claim, bestSpecificity)) {
            best = entry
            bestSpecificity = claim
        }
    }
    return best?.presenter
}

private fun RankedPresenter<*>.outranks(other: RankedPresenter<*>, claim: Int, otherClaim: Int): Boolean =
    if (tier != other.tier) tier > other.tier else claim >= otherClaim

/** [this] with [entry] replacing any registration whose [id] is the same. */
internal inline fun <T> List<RankedPresenter<T>>.replacing(entry: RankedPresenter<T>, id: (T) -> String): List<RankedPresenter<T>> {
    val entryId = id(entry.presenter)
    return filterNot { id(it.presenter) == entryId } + entry
}
