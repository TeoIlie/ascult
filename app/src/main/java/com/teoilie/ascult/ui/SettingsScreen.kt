package com.teoilie.ascult.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = { TextButton(onClick = onBack) { Text("Done") } },
            )
        },
    ) { padding ->
        val form = viewModel.form ?: return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ApiKeySection(form.apiKey, viewModel::setApiKey)
            HorizontalDivider()
            DictionarySection(
                text = form.dictionary,
                isDefault = form.dictionaryIsDefault,
                onChange = viewModel::setDictionary,
                onReset = viewModel::resetDictionary,
                onShare = { share(context, "Ascult dictionary", viewModel.dictionaryJson()) },
            )
        }
    }
}

@Composable
private fun ApiKeySection(apiKey: String, onChange: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    Text("Soniox API key", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = apiKey,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Uses the build's key when empty") },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
    )
}

@Composable
private fun DictionarySection(
    text: DictionaryText,
    isDefault: Boolean,
    onChange: (DictionaryText) -> Unit,
    onReset: () -> Unit,
    onShare: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Dictionary", style = MaterialTheme.typography.titleMedium)
            Text(
                if (isDefault) "Bundled default" else "Edited in the app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onShare) { Text("Share JSON") }
        TextButton(onClick = onReset, enabled = !isDefault) { Text("Reset") }
    }
    DictionaryField(
        label = "Expected words",
        hint = "Uncommon Romanian words and names, one per line",
        value = text.terms,
        onChange = { onChange(text.copy(terms = it)) },
    )
    DictionaryField(
        label = "Fixed translations",
        hint = "One per line: țarina = field",
        value = text.translationTerms,
        onChange = { onChange(text.copy(translationTerms = it)) },
    )
    DictionaryField(
        label = "Context",
        hint = "One per line: domain: Orthodox sermon",
        value = text.general,
        onChange = { onChange(text.copy(general = it)) },
    )
    DictionaryField(
        label = "Background",
        hint = "Free text about the speaker or topic",
        value = text.text,
        onChange = { onChange(text.copy(text = it)) },
    )
}

@Composable
private fun DictionaryField(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text(hint) },
        minLines = 3,
    )
}

fun share(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title))
}
