package com.github.tvbox.osc.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ColorScheme.cardContainer: Color
    get() = surfaceBright

/** 中间态警告色(如:延迟中等)。语义色收敛于此,严禁在组件里散落裸 hex。 */
val ColorScheme.warning: Color
    get() = Color(0xFFB5A642)

fun ColorScheme.toPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerLow = Color(0xFF060606),
    surfaceContainerHigh = Color(0xFF0B0B0B),
    surfaceContainerHighest = Color(0xFF121212),
    surfaceBright = Color(0xFF141414),
    surfaceVariant = Color(0xFF1A1A1A),
    surfaceTint = Color.Black,
)

@Composable
fun ColorScheme.filterChipColors(containerColor: Color = Color.Transparent): SelectableChipColors =
    FilterChipDefaults.filterChipColors(
        containerColor = containerColor,
        selectedContainerColor = primaryContainer,
        selectedLabelColor = onPrimaryContainer,
    )
