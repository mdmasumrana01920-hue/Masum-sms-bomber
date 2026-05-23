package com.example.ui.theme

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

private val DarkColorScheme =
  darkColorScheme(
    primary = GoldPrimary,
    secondary = GoldSecondary,
    tertiary = GreenAccent,
    background = DarkCanvas,
    surface = DarkSurface,
    onPrimary = Color(0xFF121212), // Bold dark text on Gold
    onSecondary = Color(0xFF121212),
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceContainer = DarkCard
  )

private val LightColorScheme = DarkColorScheme // Force-dark is highly recommended for this aesthetics-driven developer tooling

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force dark theme as requested for the custom Gold & Slate scheme
  dynamicColor: Boolean = false, // Use our handcrafted palette
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
