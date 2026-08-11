package app.remotex.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun useTwoPane(width: Dp, height: Dp): Boolean =
    width >= 600.dp && height >= 480.dp

internal fun usePermanentTelemetryPane(width: Dp, height: Dp): Boolean =
    width >= 1200.dp && height >= 480.dp
