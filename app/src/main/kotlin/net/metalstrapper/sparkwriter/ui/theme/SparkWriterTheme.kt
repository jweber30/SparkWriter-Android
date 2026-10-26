package net.metalstrapper.sparkwriter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val SparkWriterLightColors = lightColorScheme()

private val SparkWriterDarkColors = darkColorScheme()

@Composable
fun SparkWriterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) SparkWriterDarkColors else SparkWriterLightColors,
        typography = Typography(),
        content = content,
    )
}
