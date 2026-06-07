package com.letta.mobile.data.a2ui

internal expect object A2uiBindingFormatters {
    fun formatNumber(
        value: Double,
        minimumFractionDigits: Int,
        maximumFractionDigits: Int,
    ): String

    fun formatCurrency(
        value: Double,
        currencyCode: String,
    ): String

    fun formatDate(
        value: String,
        pattern: String,
    ): String
}
