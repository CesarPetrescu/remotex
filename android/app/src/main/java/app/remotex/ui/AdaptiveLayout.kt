package app.remotex.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun useTwoPane(width: Dp, height: Dp): Boolean =
    width >= 600.dp && height >= 480.dp

internal fun usePermanentTelemetryPane(width: Dp, height: Dp): Boolean =
    width >= 960.dp && height >= 480.dp

// Two full chat columns need ~600dp each to keep their meta rails and
// composers usable, so side-by-side sessions are landscape-tablet only.
internal fun useSplitChat(width: Dp, height: Dp): Boolean =
    width >= 1200.dp && height >= 480.dp
