package com.letta.mobile.ui.theme

import com.letta.mobile.ui.theme.LettaCodeFont

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

val Typography.listItemHeadline: TextStyle
    get() = titleMedium

val Typography.listItemSupporting: TextStyle
    get() = bodySmall.copy(fontWeight = FontWeight.Medium)

val Typography.listItemMetadata: TextStyle
    get() = labelMedium.copy(fontWeight = FontWeight.SemiBold)

val Typography.listItemMetadataMonospace: TextStyle
    get() = labelMedium.copy(
        fontFamily = LettaCodeFont,
        fontWeight = FontWeight.SemiBold,
    )

val Typography.dialogSectionHeading: TextStyle
    get() = labelLarge.copy(fontWeight = FontWeight.SemiBold)

val Typography.sectionTitle: TextStyle
    get() = titleSmallEmphasized

val Typography.chatBubbleSender: TextStyle
    get() = labelMediumEmphasized

val Typography.statValue: TextStyle
    get() = headlineMedium
