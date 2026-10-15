package com.example.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.UnlockRequest
import com.example.ui.InlineUnlockTimer
import com.example.ui.theme.*

@Composable
fun UninstallProtectionDialog(
    show: Boolean,
    pendingUnlockReq: UnlockRequest?,
    uninstallProtected: Boolean,
    onDismiss: () -> Unit,
    onCancelUnlock: (Int) -> Unit,
    onTimerExpired: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (pendingUnlockReq != null) "Deactivation in Progress" else "Uninstall Protection",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pendingUnlockReq != null) {
                    Text(
                        text = "Uninstall Protection deactivation countdown is running. Device Admin will be deactivated when timer expires.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                    InlineUnlockTimer(
                        unlockAtTime = pendingUnlockReq.unlockAtTime,
                        onCancel = {
                            onCancelUnlock(pendingUnlockReq.id)
                            onDismiss()
                        },
                        onTimerExpired = {
                            onTimerExpired()
                            onDismiss()
                        },
                        labelPrefix = "Deactivates in"
                    )
                } else if (uninstallProtected) {
                    Text(
                        text = "Focus Shield is actively protected as an Android Device Administrator. The Android OS prevents uninstallation from the launcher, application manager, or Google Play Store while active.\n\nTo disable Uninstall Protection, a standard 12-hour delayed unlock is required.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                } else {
                    Text(
                        text = "Focus Shield can prevent unauthorized uninstallation using Android Device Administrator.\n\nTap Enable to activate Uninstall Protection.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
        },
        confirmButton = {
            if (pendingUnlockReq != null) {
                TextButton(
                    onClick = {
                        onCancelUnlock(pendingUnlockReq.id)
                        onDismiss()
                    }
                ) {
                    Text("Cancel Deactivation", color = EmeraldGreenLight, fontWeight = FontWeight.SemiBold)
                }
            } else if (uninstallProtected) {
                TextButton(
                    onClick = {
                        onToggle(true)
                        onDismiss()
                    }
                ) {
                    Text("Deactivate (12h Delay)", color = AmberWarning, fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(
                    onClick = {
                        onToggle(false)
                        onDismiss()
                    }
                ) {
                    Text("Enable", color = EmeraldGreenLight, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        },
        containerColor = CardDark,
        shape = RoundedCornerShape(16.dp)
    )
}
