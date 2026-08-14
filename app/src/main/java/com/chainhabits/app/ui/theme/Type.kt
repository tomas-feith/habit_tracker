package com.chainhabits.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale tuned around one idea: the streak number is the reward, so it gets the
 * loudest voice on the screen and everything else steps back to support it.
 */
private val Default = Typography()

val HabitTypography =
    Default.copy(
        // The hero number on a habit row. Tight leading so it sits close to its unit.
        displaySmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                lineHeight = 42.sp,
                letterSpacing = (-1.5).sp,
            ),
        headlineLarge =
            Default.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
            ),
        titleMedium =
            Default.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
            ),
        titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        // Unit captions under the hero number: small, spaced, quiet.
        labelSmall =
            Default.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
            ),
    )
