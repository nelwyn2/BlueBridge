package com.bluebridge.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluebridge.android.data.api.Region
import com.bluebridge.android.data.models.UiColorOverrides
import com.bluebridge.android.data.models.UiColorSlot
import com.bluebridge.android.ui.components.ControlSection
import com.bluebridge.android.ui.theme.*
import com.bluebridge.android.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val region by viewModel.region.collectAsStateWithLifecycle()
    val tempUnit by viewModel.temperatureUnit.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val selectedThemeId by viewModel.appTheme.collectAsStateWithLifecycle()
    val uiColorOverrides by viewModel.uiColorOverrides.collectAsStateWithLifecycle()
    val servicePin by viewModel.servicePin.collectAsStateWithLifecycle()
    val selectedTheme = AppTheme.fromId(selectedThemeId)

    var showRegionPicker by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showColorCustomizer by remember { mutableStateOf(false) }
    var editingColorSlot by remember { mutableStateOf<UiColorSlot?>(null) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Account ───────────────────────────────────────────────────────
            ControlSection(title = "Account") {
                Column {
                    SettingsRow(
                        icon = Icons.Filled.Language,
                        label = "Region / Brand",
                        value = Region.valueOf(region).label,
                        onClick = { showRegionPicker = true }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    SettingsRow(
                        icon = Icons.Filled.Lock,
                        label = "Bluelink PIN",
                        value = if (servicePin.isNullOrBlank()) "Not set" else "••••",
                        onClick = { showPinDialog = true }
                    )
                }
            }

            // ── Appearance ────────────────────────────────────────────────────
            ControlSection(title = "Appearance") {
                Column {
                    SettingsRow(
                        icon = Icons.Filled.Palette,
                        label = "Color Theme",
                        value = selectedTheme.displayName,
                        onClick = { showThemePicker = true }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    SettingsRow(
                        icon = Icons.Filled.ColorLens,
                        label = "Customize UI Colors",
                        value = when (val count = uiColorOverrides.activeCount()) {
                            0 -> "Default"
                            1 -> "1 override"
                            else -> "$count overrides"
                        },
                        onClick = { showColorCustomizer = true }
                    )
                }
            }

            // ── Preferences ───────────────────────────────────────────────────
            ControlSection(title = "Preferences") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Temperature unit toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Thermostat, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Temperature Unit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row {
                            FilterChip(
                                selected = tempUnit == "F",
                                onClick = { viewModel.setTemperatureUnit("F") },
                                label = { Text("°F") },
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            FilterChip(
                                selected = tempUnit == "C",
                                onClick = { viewModel.setTemperatureUnit("C") },
                                label = { Text("°C") }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                    // Biometric
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Biometric Lock",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Unlock BlueBridge and sign in with saved credentials",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = { viewModel.setBiometricEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            }

            // ── About ─────────────────────────────────────────────────────────
            ControlSection(title = "About") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsInfoRow("Version", "1.11")
                    SettingsInfoRow("API", "Hyundai Bluelink / Kia Connect")
                    SettingsInfoRow("Credit", "Nelwyn99")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "BlueBridge is an unofficial third-party app and is not affiliated with Hyundai or Kia. " +
                        "Use of this app is at your own risk.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
            }

            // ── Sign out ──────────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Filled.Logout, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign Out")
            }
        }
    }


    // ── Bluelink PIN dialog ──────────────────────────────────────────────────
    if (showPinDialog) {
        var pinInput by remember(servicePin) { mutableStateOf(servicePin.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Bluelink PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Your 4-digit Bluelink PIN is required for unlock, remote start, horn, and lights.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it.filter { ch -> ch.isDigit() }.take(4) },
                        label = { Text("PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setServicePin(pinInput)
                    showPinDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Region picker dialog ──────────────────────────────────────────────────
    if (showRegionPicker) {
        AlertDialog(
            onDismissRequest = { showRegionPicker = false },
            title = { Text("Select Region & Brand") },
            text = {
                Column {
                    Region.entries.filter { it != Region.AU }.forEach { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setRegion(r.name)
                                    showRegionPicker = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = r.name == region, onClick = {
                                viewModel.setRegion(r.name)
                                showRegionPicker = false
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(r.label)
                        }
                        if (r != Region.entries.filter { it != Region.AU }.last()) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRegionPicker = false }) { Text("Cancel") } }
        )
    }

    // ── Theme picker dialog ───────────────────────────────────────────────────
    if (showThemePicker) {
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text("Choose Color Theme") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    availableAppThemes().forEach { theme ->
                        ThemeOptionRow(
                            theme = theme,
                            selected = theme.id == selectedThemeId,
                            onClick = {
                                viewModel.setAppTheme(theme.id)
                                showThemePicker = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemePicker = false }) { Text("Cancel") } }
        )
    }

    // ── Custom color editor ───────────────────────────────────────────────────
    if (showColorCustomizer) {
        AlertDialog(
            onDismissRequest = { showColorCustomizer = false },
            title = { Text("Customize UI Colors") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Each color overrides the selected theme. Reset a row to fall back to ${selectedTheme.displayName}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UiColorSlot.entries.forEach { slot ->
                        ColorOverrideRow(
                            slot = slot,
                            theme = selectedTheme,
                            overrides = uiColorOverrides,
                            onEdit = { editingColorSlot = slot },
                            onReset = { viewModel.resetUiColor(slot) }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showColorCustomizer = false }) { Text("Done") } },
            dismissButton = {
                TextButton(onClick = { viewModel.resetUiColors() }) { Text("Reset all") }
            }
        )
    }

    editingColorSlot?.let { slot ->
        ColorPickerDialog(
            slot = slot,
            theme = selectedTheme,
            overrides = uiColorOverrides,
            onDismiss = { editingColorSlot = null },
            onSave = { hex ->
                viewModel.setUiColor(slot, hex)
                editingColorSlot = null
            },
            onReset = {
                viewModel.resetUiColor(slot)
                editingColorSlot = null
            }
        )
    }

    // ── Logout confirm dialog ─────────────────────────────────────────────────
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Sign Out?") },
            text = { Text("You'll need to sign in again to control your vehicle.") },
            confirmButton = {
                Button(
                    onClick = { showLogoutDialog = false; onLogout() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Sign Out") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SettingsRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun ThemeOptionRow(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ThemeColorDot(theme.primary)
            ThemeColorDot(theme.secondary)
            ThemeColorDot(theme.tertiary)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(theme.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(theme.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


@Composable
fun ColorOverrideRow(
    slot: UiColorSlot,
    theme: AppTheme,
    overrides: UiColorOverrides,
    onEdit: () -> Unit,
    onReset: () -> Unit
) {
    val overrideHex = overrides.valueFor(slot)
    val effectiveColor = colorFromHexOrNull(overrideHex) ?: defaultColorForSlot(theme, slot)
    val effectiveHex = overrideHex ?: colorToHex(effectiveColor)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onEdit)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(effectiveColor)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    slot.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                if (!overrideHex.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = onReset,
                        label = {
                            Text(
                                "Reset",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.heightIn(min = 32.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                            labelColor = MaterialTheme.colorScheme.secondary
                        )
                    )
                }
            }
            Text(
                slot.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Text(
                if (overrideHex.isNullOrBlank()) "$effectiveHex • theme default" else "$effectiveHex • custom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun ColorPickerDialog(
    slot: UiColorSlot,
    theme: AppTheme,
    overrides: UiColorOverrides,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit
) {
    val initialColor = colorFromHexOrNull(overrides.valueFor(slot)) ?: defaultColorForSlot(theme, slot)

    var previewColor by remember(slot) { mutableStateOf(initialColor) }
    var hexInput by remember(slot) { mutableStateOf(colorToHex(initialColor)) }
    var hexError by remember(slot) { mutableStateOf(false) }

    fun channelValue(shift: Int): Int = (previewColor.toArgb() shr shift) and 0xFF

    fun updatePreviewColor(red: Int, green: Int, blue: Int) {
        previewColor = Color(red = red / 255f, green = green / 255f, blue = blue / 255f)
        hexInput = "#%02X%02X%02X".format(red, green, blue)
        hexError = false
    }

    fun updateFromHex(input: String) {
        val parsedColor = colorFromHexOrNull(input)
        if (parsedColor == null) {
            hexInput = input.uppercase()
            hexError = true
            return
        }

        previewColor = parsedColor
        hexInput = colorToHex(parsedColor)
        hexError = false
    }

    val red = channelValue(16)
    val green = channelValue(8)
    val blue = channelValue(0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(slot.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(74.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(previewColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        hexInput,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = readableTextOn(previewColor)
                    )
                }

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { updateFromHex(it) },
                    label = { Text("Hex color") },
                    placeholder = { Text("#00D4FF") },
                    singleLine = true,
                    isError = hexError,
                    supportingText = {
                        Text(if (hexError) "Use #RRGGBB or RRGGBB" else slot.description)
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                RgbSliderRow("Red", red) { updatePreviewColor(it, green, blue) }
                RgbSliderRow("Green", green) { updatePreviewColor(red, it, blue) }
                RgbSliderRow("Blue", blue) { updatePreviewColor(red, green, it) }
            }
        },
        confirmButton = {
            Button(
                enabled = !hexError,
                onClick = { normalizeHexColor(hexInput)?.let(onSave) }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun RgbSliderRow(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text(value.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            steps = 254
        )
    }
}

private fun readableTextOn(color: Color): Color {
    val argb = color.toArgb()
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF
    val luminance = (0.299 * red + 0.587 * green + 0.114 * blue)
    return if (luminance > 150) Color(0xFF071018) else Color.White
}

@Composable
fun ThemeColorDot(color: Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .background(color, CircleShape)
    )
}
