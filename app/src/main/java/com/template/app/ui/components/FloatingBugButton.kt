package com.template.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A floating, draggable bug-report button rendered as an overlay on all screens.
 *
 * Only rendered when [visible] is `true` (typically when admin mode is active and
 * the "Bug Report Button" toggle is on in Settings → Debug).
 *
 * ## Interaction
 * - **Drag** — repositions the button anywhere on screen.
 * - **Tap** (total drag distance < 20 px) — hides the button for one frame, captures
 *   a screenshot of the current screen via [View.drawToBitmap], then calls
 *   [onScreenshotCaptured] with the resulting [Bitmap].
 *
 * The caller is responsible for storing the bitmap (e.g. in [bugreport.ScreenshotHolder])
 * and navigating to the bug report screen.
 *
 * @param visible Whether the button should be shown. Pass `false` to hide it completely.
 * @param onScreenshotCaptured Called with the captured screen bitmap after a tap.
 */
@Composable
fun FloatingBugButton(
    visible: Boolean,
    onScreenshotCaptured: (Bitmap) -> Unit
) {
    if (!visible) return

    var offsetX by remember { mutableFloatStateOf(16f) }
    var offsetY by remember { mutableFloatStateOf(400f) }
    var capturing by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(capturing) {
        if (capturing) {
            delay(80) // one frame — lets the button disappear before capture
            val bitmap = view.drawToBitmap()
            capturing = false
            onScreenshotCaptured(bitmap)
        }
    }

    if (!capturing) {
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(52.dp)
                .shadow(6.dp, CircleShape)
                .background(MaterialTheme.colorScheme.error, CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        var isDragging = false
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val delta = change.positionChange()
                            totalX += abs(delta.x)
                            totalY += abs(delta.y)
                            if (totalX > 10f || totalY > 10f) {
                                isDragging = true
                                change.consume()
                                offsetX += delta.x
                                offsetY += delta.y
                            }
                        } while (event.changes.any { it.pressed })
                        if (!isDragging) {
                            capturing = true
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = "Report Bug",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
