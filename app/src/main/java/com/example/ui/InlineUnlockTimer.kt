package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun InlineUnlockTimer(
    unlockAtTime: Long,
    onCancel: (() -> Unit)? = null,
    onTimerExpired: (() -> Unit)? = null,
    labelPrefix: String? = null,
    isLightBackground: Boolean = false,
    modifier: Modifier = Modifier
) {
    var remainingTimeText by remember { mutableStateOf("") }

    LaunchedEffect(unlockAtTime) {
        while (true) {
            val diff = unlockAtTime - System.currentTimeMillis()
            if (diff <= 0) {
                remainingTimeText = "00:00:00"
                onTimerExpired?.invoke()
                break
            } else {
                val hours = diff / (1000 * 3600)
                val minutes = (diff % (1000 * 3600)) / (1000 * 60)
                val seconds = (diff % (1000 * 60)) / 1000
                remainingTimeText = "%02d:%02d:%02d".format(hours, minutes, seconds)
            }
            delay(1000)
        }
    }

    val surfaceColor = remember(isLightBackground) {
        if (isLightBackground) Color(0xFFFEF3C7) else Color(0xFFF59E0B).copy(alpha = 0.15f)
    }
    val borderColor = remember(isLightBackground) {
        if (isLightBackground) Color(0xFFFDE68A) else Color(0xFFF59E0B).copy(alpha = 0.45f)
    }
    val textColor = remember(isLightBackground) {
        if (isLightBackground) Color(0xFFB45309) else Color(0xFFFBBF24)
    }
    val cancelBg = remember(isLightBackground) {
        if (isLightBackground) Color(0xFFFEE2E2) else Color(0xFFEF4444).copy(alpha = 0.2f)
    }
    val cancelIconTint = remember(isLightBackground) {
        if (isLightBackground) Color(0xFFDC2626) else Color(0xFFF87171)
    }

    Surface(
        color = surfaceColor,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(if (isLightBackground) 1.dp else 0.7.dp, borderColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = if (onCancel != null) 3.dp else 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val prefix = labelPrefix ?: "⏳"
            Text(
                text = "$prefix $remainingTimeText",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )

            if (onCancel != null) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(cancelBg)
                        .clickable { onCancel() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "বাতিল করুন",
                        tint = cancelIconTint,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}
