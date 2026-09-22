package com.alex.catflix.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BrandRedDark,
    onPrimary = Color(0xFF1A0505),
    secondary = BrandGoldDark,
    tertiary = BrandGold,
    background = InkBackground,
    onBackground = InkOnSurface,
    surface = InkSurface,
    onSurface = InkOnSurface,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = InkOnVariant,
    surfaceContainerHigh = InkSurfaceHigh,
    error = Color(0xFFFF8A80)
)

private val LightColorScheme = lightColorScheme(
    primary = BrandCrimson,
    onPrimary = Color.White,
    secondary = BrandGold,
    tertiary = BrandGold,
    background = PaperBackground,
    onBackground = PaperOnSurface,
    surface = PaperSurface,
    onSurface = PaperOnSurface,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = PaperOnVariant,
    surfaceContainerHigh = PaperSurfaceHigh,
    error = Color(0xFFBA1A1A)
)

@Composable
fun CatFlixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),

    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = CatFlixShapes,
        content = content
    )
}
