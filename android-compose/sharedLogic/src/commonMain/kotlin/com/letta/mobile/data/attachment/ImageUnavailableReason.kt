package com.letta.mobile.data.attachment

/** Why an attachment exists but cannot be displayed; independent of its absent payload. */
sealed interface ImageUnavailableReason {
    /** Stable representation used by unavailable-image markers and snapshot metadata. */
    val wireValue: String

    /** The producer did not supply image bytes. */
    data object Missing : ImageUnavailableReason {
        override val wireValue: String = "missing"
    }
    /** Bytes were supplied but could not be decoded. */
    data object Invalid : ImageUnavailableReason {
        override val wireValue: String = "invalid"
    }
    /** The inline payload exceeded the receiving peer's transport budget. */
    data object TooLarge : ImageUnavailableReason {
        override val wireValue: String = "too_large"
    }
    /** This client cannot resolve the source or reference. */
    data object UnsupportedSource : ImageUnavailableReason {
        override val wireValue: String = "unsupported_source"
    }
    companion object {
        /** Read the marker without conflating unknown sources with missing data. */
        fun fromWire(value: String?): ImageUnavailableReason? = when (value) {
            Missing.wireValue -> Missing
            Invalid.wireValue -> Invalid
            TooLarge.wireValue -> TooLarge
            UnsupportedSource.wireValue -> UnsupportedSource
            else -> null
        }
    }
}
