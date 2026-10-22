package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.safety.RememberProtectionEngine
import com.example.service.safety.SavedProtectionTarget
import com.example.ui.components.commonDialogTextFieldColors
import com.example.ui.theme.*

private val PACKAGE_OPTIONS = listOf(
    "com.android.settings",
    "com.miui.securitycenter",
    "com.miui.home",
    "miui.systemui.plugin",
    "Custom Package Name"
)

private val EVENT_TYPE_OPTIONS = listOf(
    "Type View Click",
    "Type View Long Click",
    "Blank"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RememberDropdownField(
    label: String,
    selectedValue: String,
    options: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    testTag: String,
    optionTestTagPrefix: String
) {
    ExposedDropdownMenuBox(expanded, onExpandedChange, Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedValue, onValueChange = {}, readOnly = true,
            label = { Text(label, fontSize = 11.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.menuAnchor().fillMaxWidth().testTag(testTag),
            colors = commonDialogTextFieldColors()
        )
        ExposedDropdownMenu(expanded, { onExpandedChange(false) }, Modifier.background(CardDark)) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt, color = if (opt == selectedValue) Color(0xFF38BDF8) else TextPrimary, fontSize = 13.sp) },
                    onClick = { onSelect(opt); onExpandedChange(false) },
                    modifier = Modifier.testTag("${optionTestTagPrefix}${opt.replace(".", "_").replace(" ", "_")}")
                )
            }
        }
    }
}

/**
 * Dialog for adding a new Remember Protection target.
 */
@Composable
fun AddTargetDialog(
    onDismiss: () -> Unit,
    onSave: (SavedProtectionTarget) -> Unit
) {
    var navigationPath by remember { mutableStateOf("") }
    var packageOption by remember { mutableStateOf("com.android.settings") }
    var customPackage by remember { mutableStateOf("") }
    var pkgExpanded by remember { mutableStateOf(false) }
    var eventType by remember { mutableStateOf("Blank") }
    var evtExpanded by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = CardDark, shape = RoundedCornerShape(14.dp),
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = navigationPath, onValueChange = { navigationPath = it },
                    label = { Text("Navigation Path *", fontSize = 11.sp) },
                    placeholder = { Text("e.g. Set up portable hotspot", fontSize = 12.sp) },
                    singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().testTag("add_path_input"),
                    colors = commonDialogTextFieldColors()
                )
                RememberDropdownField("Package Name *", packageOption, PACKAGE_OPTIONS, pkgExpanded, { pkgExpanded = it }, { packageOption = it }, "add_path_package_dropdown", "package_option_")
                if (packageOption == "Custom Package Name") {
                    OutlinedTextField(
                        value = customPackage, onValueChange = { customPackage = it },
                        label = { Text("Custom Package Name *", fontSize = 11.sp) },
                        singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().testTag("add_path_package_input"),
                        colors = commonDialogTextFieldColors()
                    )
                }
                RememberDropdownField("Event Type", eventType, EVENT_TYPE_OPTIONS, evtExpanded, { evtExpanded = it }, { eventType = it }, "add_path_event_type_dropdown", "event_type_option_")
                validationError?.let { Text(it, color = Color(0xFFEF4444), fontSize = 12.sp, modifier = Modifier.testTag("add_path_validation_error")) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pathText = navigationPath.trim()
                    val pkgText = if (packageOption == "Custom Package Name") customPackage.trim() else packageOption.trim()
                    if (pathText.isEmpty()) { validationError = "Navigation Path cannot be empty"; return@Button }
                    if (pkgText.isEmpty()) { validationError = "Package name cannot be empty"; return@Button }
                    val newTarget = SavedProtectionTarget(pathText, pkgText, eventType.trim())
                    val err = RememberProtectionEngine.validateTarget(newTarget)
                    if (err != null) validationError = err else onSave(newTarget)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("add_path_save_button")
            ) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("add_path_cancel_button")) { Text("Cancel", color = TextMuted) }
        }
    )
}

/**
 * Dialog for confirming deletion of a target with 12-hour delayed unlock.
 */
@Composable
fun DeleteTargetDialog(
    target: SavedProtectionTarget,
    onDismiss: () -> Unit,
    onConfirm: (SavedProtectionTarget) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = CardDark, shape = RoundedCornerShape(14.dp),
        title = { Text("Delete Target (12h Unlock)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Deleting \"${target.displayName}\" (${target.packageName}) requires a 12-hour delayed unlock.", fontSize = 13.sp, color = TextSecondary)
                Text("A 12-hour timer will begin. You can cancel deletion anytime before it expires.", fontSize = 12.sp, color = TextMuted)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(target) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)), shape = RoundedCornerShape(8.dp), modifier = Modifier.testTag("confirm_delete_path_button")) {
                Text("Start 10h Unlock", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_delete_path_button")) { Text("Cancel", color = TextMuted) }
        }
    )
}

/**
 * Dialog for inspecting configured target details directly.
 */
@Composable
fun TargetDetailsDialog(
    target: SavedProtectionTarget,
    onDismiss: () -> Unit,
    onDeleteRequest: (SavedProtectionTarget) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = CardDark, shape = RoundedCornerShape(14.dp),
        title = { Text(target.displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Package: ${target.packageName}", fontSize = 12.sp, color = TextSecondary)
                Text("Event: ${target.displayEventType}", fontSize = 12.sp, color = Color(0xFF38BDF8))
            }
        },
        confirmButton = {
            TextButton(onClick = { onDeleteRequest(target) }) { Text("Delete (12h Unlock)", color = Color(0xFFEF4444)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) }
        }
    )
}

