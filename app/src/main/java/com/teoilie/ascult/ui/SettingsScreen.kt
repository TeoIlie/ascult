package com.teoilie.ascult.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.teoilie.ascult.translation.CLAUDE_MODEL

/** The two API keys, each edited in an [ApiKeyDialog]. */
private enum class ApiKeyField(val title: String) { SONIOX("Soniox API key"), ANTHROPIC("Anthropic API key") }

/** Grouped settings: theme, vocabulary and context (own screen), API keys (dialog), about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenDictionary: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    var editingKey by rememberSaveable { mutableStateOf<ApiKeyField?>(null) }
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
            SettingsGroup("Sermon") {
                SegmentedListItem(
                    onClick = onOpenDictionary,
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    leadingContent = { SettingIcon(R.drawable.ic_book) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                    supportingContent = { Text("Expected words, fixed translations, setting, background\n${form.dictionarySource}") },
                ) { Text(VOCABULARY_TITLE) }
            }
            SettingsGroup("API keys") {
                SegmentedListItem(
                    onClick = { editingKey = ApiKeyField.SONIOX },
                    shapes = ListItemDefaults.segmentedShapes(0, 2),
                    leadingContent = { SettingIcon(R.drawable.ic_key) },
                    supportingContent = { Text("Transcription · " + keyStatus(form.apiKey, BuildConfig.SONIOX_API_KEY)) },
                ) { Text(ApiKeyField.SONIOX.title) }
                SegmentedListItem(
                    onClick = { editingKey = ApiKeyField.ANTHROPIC },
                    shapes = ListItemDefaults.segmentedShapes(1, 2),
                    leadingContent = { SettingIcon(R.drawable.ic_key) },
                    supportingContent = { Text("Claude translation · " + keyStatus(form.anthropicApiKey, BuildConfig.ANTHROPIC_API_KEY)) },
                ) { Text(ApiKeyField.ANTHROPIC.title) }
            }
            SettingsGroup("About") {
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    leadingContent = { SettingIcon(R.drawable.ic_info) },
                    supportingContent = { Text("Romanian to English · Soniox $SONIOX_MODEL · Claude $CLAUDE_MODEL") },
                ) { Text("Ascult ${BuildConfig.VERSION_NAME}") }
            }
        }
    }

    editingKey?.let { field ->
        ApiKeyDialog(
            title = field.title,
            initial = when (field) {
                ApiKeyField.SONIOX -> viewModel.form?.apiKey
                ApiKeyField.ANTHROPIC -> viewModel.form?.anthropicApiKey
            }.orEmpty(),
            onSave = { key ->
                when (field) {
                    ApiKeyField.SONIOX -> viewModel.setApiKey(key)
                    ApiKeyField.ANTHROPIC -> viewModel.setAnthropicApiKey(key)
                }
                editingKey = null
            },
            onDismiss = { editingKey = null },
        )
    }
}

/** A key's supporting text: the saved key's last characters, else the debug build's fallback key. */
private fun keyStatus(key: String, buildKey: String): String = when {
    key.isNotBlank() -> "Set · ends in ${key.takeLast(4)}"
    buildKey.isNotBlank() -> "Using the build's key"
    else -> "Not set"
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
        ConnectedToggleGroup(ThemeMode.entries, theme, ThemeMode::label, onChange)
    }
}

@Composable
private fun ApiKeyDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf(initial) }
    var visible by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_key), contentDescription = null) },
        title = { Text(title) },
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
