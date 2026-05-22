package com.example.haptok.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val HaptokFontFamily = FontFamily.Default

val HaptokTypography = Typography(
    displayLarge = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.Bold, fontSize = 57.sp, letterSpacing = (-0.25).sp),
    headlineLarge = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = HaptokFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
)
