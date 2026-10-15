package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.UnlockRequest
import com.example.service.safety.SavedProtectionTarget
import com.example.ui.InlineUnlockTimer
import com.example.ui.theme.*

val SavedProtectionTarget.displayName: String
    get() = navigationPath.ifEmpty { packageName }

val SavedProtectionTarget.displayEventType: String
    get() = if (eventType.isBlank() || eventType.equals("Blank", ignoreCase = true)) "Blank" else eventType

/**
 * Compact list row representing a single saved Remember Protection target.
 */
@Composable
fun RememberTargetRow(
    target: SavedProtectionTarget,
    isSelected: Boolean,
    pendingDeleteReq: UnlockRequest?,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onCancelDelete: (Int) -> Unit,
    onTimerExpired: () -> Unit
) {
    Surface(
        color = CardDark,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) Color(0xFF38BDF8) else CardBorderDark),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("saved_path_card_${target.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Shield, null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(target.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${target.packageName} · ${target.displayEventType}", fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (pendingDeleteReq != null) {
                InlineUnlockTimer(pendingDeleteReq.unlockAtTime, { onCancelDelete(pendingDeleteReq.id) }, onTimerExpired, "12h")
            } else {
                IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp).testTag("delete_path_button_${target.id}")) {
                    Icon(Icons.Default.DeleteOutline, "Delete", tint = Color(0xFFEF4444).copy(alpha = 0.85f), modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}
