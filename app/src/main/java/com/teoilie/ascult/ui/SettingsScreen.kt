package com.teoilie.ascult.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teoilie.ascult.BuildConfig
import com.teoilie.ascult.R
import com.teoilie.ascult.settings.ThemeMode
import com.teoilie.ascult.soniox.SONIOX_MODEL

/** Grouped settings: theme, Soniox API key (dialog), dictionary (own screen), about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenDictionary: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    var editingApiKey by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val form = viewModel.form ?: return@Scaffold
        SettingsColumn(Modifier.fillMaxSize().padding(padding)) {
            SettingsGroup("Appearance") {
                ThemeSetting(theme, viewModel::setTheme)
            }
            SettingsGroup("Transcription") {
                SegmentedListItem(
                    onClick = { editingApiKey = true },
                    shapes = ListItemDefaults.segmentedShapes(0, 2),
                    leadingContent = { SettingIcon(R.drawable.ic_key) },
                    supportingContent = {
                        Text(if (form.apiKey.isBlank()) "Using the build's key" else "Set · ends in ${form.apiKey.takeLast(4)}")
                    },
                ) { Text("Soniox API key") }
                SegmentedListItem(
                    onClick = onOpenDictionary,
                    shapes = ListItemDefaults.segmentedShapes(1, 2),
                    leadingContent = { SettingIcon(R.drawable.ic_book) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                    supportingContent = { Text(form.dictionarySource) },
                ) { Text("Dictionary") }
            }
            SettingsGroup("About") {
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    leadingContent = { SettingIcon(R.drawable.ic_info) },
                    supportingContent = { Text("Soniox $SONIOX_MODEL · Romanian to English") },
                ) { Text("Ascult ${BuildConfig.VERSION_NAME}") }
            }
        }
    }

    if (editingApiKey) {
        ApiKeyDialog(
            initial = viewModel.form?.apiKey.orEmpty(),
            onSave = { viewModel.setApiKey(it); editingApiKey = false },
            onDismiss = { editingApiKey = false },
        )
    }
}

/** A list item's leading icon in a tonal circle. */
@Composable
private fun SettingIcon(@DrawableRes icon: Int) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Theme row, then System / Light / Dark as a connected button group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeSetting(theme: ThemeMode, onChange: (ThemeMode) -> Unit) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(0, 2),
        leadingContent = { SettingIcon(R.drawable.ic_contrast) },
        supportingContent = {
            Text(
                when (theme) {
                    ThemeMode.SYSTEM -> "Follows the phone"
                    ThemeMode.LIGHT -> "Always light"
                    ThemeMode.DARK -> "Always dark"
                },
            )
        },
    ) { Text("Theme") }
    SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(1, 2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            val modes = ThemeMode.entries
            modes.forEachIndexed { index, mode ->
                ToggleButton(
                    checked = theme == mode,
                    onCheckedChange = { onChange(mode) },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                ) {
                    if (theme == mode) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(ToggleButtonDefaults.IconSize))
                        Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                    }
                    Text(mode.label)
                }
            }
        }
    }
}

@Composable
private fun ApiKeyDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf(initial) }
    var visible by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_key), contentDescription = null) },
        title = { Text("Soniox API key") },
        text = {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Uses the build's key when empty") },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            contentDescription = if (visible) "Hide key" else "Show key",
                        )
                    }
                },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(key) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
