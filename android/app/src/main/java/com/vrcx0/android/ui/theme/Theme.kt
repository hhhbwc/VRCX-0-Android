package com.vrcx0.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Flat, restrained Material 3 -- deliberately not full Expressive.
 *
 * VRCX is information-dense (friend lists, feeds, notifications), so the
 * Expressive influence is limited to corner radius and motion rather than
 * larger type or louder shapes. See docs/ANDROID_UI_SPEC.md section 3.
 */

// A teal-and-amber identity, deliberately distinct from the stock purple the
// upstream app ships with -- the user asked for a look of its own. Dynamic
// (wallpaper) colour still wins when enabled.
private val LightColors = lightColorScheme(
    primary = Color(0xFF006A6C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F3),
    onPrimaryContainer = Color(0xFF002021),
    secondary = Color(0xFF8A5100),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCBE),
    onSecondaryContainer = Color(0xFF2C1600),
    tertiary = Color(0xFF9C4148),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD9),
    onTertiaryContainer = Color(0xFF410009)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CD9DB),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F50),
    onPrimaryContainer = Color(0xFF9CF1F3),
    secondary = Color(0xFFFFB877),
    onSecondary = Color(0xFF492900),
    secondaryContainer = Color(0xFF673B00),
    onSecondaryContainer = Color(0xFFFFDCBE),
    tertiary = Color(0xFFFFB3B2),
    onTertiary = Color(0xFF5F1219),
    tertiaryContainer = Color(0xFF7E2B32),
    onTertiaryContainer = Color(0xFFFFDAD9)
)

/** Warm, slightly desaturated accent used for connection and presence states. */
object VrcxColors {
    val Connected = Color(0xFF1D9E75)
    val Reconnecting = Color(0xFFBA7517)
    val Disconnected = Color(0xFF888780)
    val Error = Color(0xFFE24B4A)
}

/**
 * VRCX typography.
 *
 * Stock Material 3 `Typography()` was in use here, which is a poor fit for this
 * app in two specific ways:
 *
 *  1. **Headings were not distinct from body.** Material's title/headline
 *     styles carry no negative tracking, so at the sizes used in a dense list
 *     a "title" and a "body" line read as the same weight of text. Every
 *     heading here gets tightened tracking, which is what actually separates a
 *     heading from body copy at small sizes on a phone.
 *  2. **Line heights were tuned for comfortable long-form reading**, not for
 *     rows in a list. Material's defaults are generous; in a 72dp friend row
 *     the extra leading pushes secondary text out of the visual group it
 *     belongs to. The body/label styles below use tighter, more deliberate
 *     leading while staying above the accessibility floor.
 *
 * Sizes are deliberately NOT inflated: this is an information-dense app and
 * growing the type scale would trade real rows-per-screen for no gain.
 */
private val VrcxLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private val VrcxTypography = Typography(
    // Screen titles ("主页", "好友") and dialog titles.
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
        lineHeightStyle = VrcxLineHeightStyle
    ),
    // Section headers inside a screen.
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
        lineHeightStyle = VrcxLineHeightStyle
    ),
    // Card titles, sheet titles, list row names.
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = VrcxLineHeightStyle
    ),
    // Dense sub-headers (group headers in the friends list).
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = VrcxLineHeightStyle
    ),
    // Primary body copy.
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = VrcxLineHeightStyle
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = VrcxLineHeightStyle
    ),
    // Secondary row text: world names, timestamps, status.
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = VrcxLineHeightStyle
    ),
    // Buttons and chips.
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = VrcxLineHeightStyle
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
        lineHeightStyle = VrcxLineHeightStyle
    ),
    /*
     * The most-used style in the app (37 call sites). It carries uppercase-ish
     * metadata (counts, "在线 12/87", filter labels), so it gets positive
     * tracking: at 11sp, letters need air to stay legible on a phone.
     */
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
        lineHeightStyle = VrcxLineHeightStyle
    )
)

@Composable
fun VrcxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VrcxTypography,
        content = content
    )
}
