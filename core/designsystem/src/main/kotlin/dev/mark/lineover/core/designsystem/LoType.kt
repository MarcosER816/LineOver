package dev.mark.lineover.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Selecting the weight axis of a variable font is still marked experimental in Compose.
@OptIn(ExperimentalTextApi::class)
private fun robotoFlex(weight: FontWeight) =
    Font(
        resId = R.font.roboto_flex,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

/** Roboto Flex (variable font, bundled, SIL OFL 1.1). */
val RobotoFlex =
    FontFamily(
        robotoFlex(FontWeight.Normal),
        robotoFlex(FontWeight.Medium),
        robotoFlex(FontWeight.SemiBold),
    )

/** Type scale: 11 / 12 / 14 / 16 / 20 / 24 sp, always with tabular numerals. */
object LoType {
    private const val TABULAR_NUMERALS = "tnum"

    private fun style(
        size: Int,
        lineHeight: Int,
        weight: FontWeight = FontWeight.Normal,
    ) = TextStyle(
        fontFamily = RobotoFlex,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        fontFeatureSettings = TABULAR_NUMERALS,
    )

    val Micro = style(size = 11, lineHeight = 16, weight = FontWeight.Medium)
    val Caption = style(size = 12, lineHeight = 16)
    val Body = style(size = 14, lineHeight = 20)
    val Title = style(size = 16, lineHeight = 24, weight = FontWeight.Medium)
    val Headline = style(size = 20, lineHeight = 28, weight = FontWeight.Medium)
    val Display = style(size = 24, lineHeight = 32, weight = FontWeight.SemiBold)
}

internal val LoMaterialTypography =
    Typography(
        headlineSmall = LoType.Display,
        titleLarge = LoType.Headline,
        titleMedium = LoType.Title,
        titleSmall = LoType.Body.copy(fontWeight = FontWeight.Medium),
        bodyLarge = LoType.Title.copy(fontWeight = FontWeight.Normal),
        bodyMedium = LoType.Body,
        bodySmall = LoType.Caption,
        labelLarge = LoType.Body.copy(fontWeight = FontWeight.Medium),
        labelMedium = LoType.Caption.copy(fontWeight = FontWeight.Medium),
        labelSmall = LoType.Micro,
    )
