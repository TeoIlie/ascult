package com.teoilie.ascult.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teoilie.ascult.R

/** Editor for the Soniox context; edits save as they are typed (see [SettingsViewModel]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val form = viewModel.form
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Dictionary")
                        if (form != null) {
                            Text(
                                if (form.dictionaryIsDefault) "Bundled default" else "Edited in the app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { share(context, "Ascult dictionary", viewModel.dictionaryJson()) }) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = "Share as JSON")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Reset to bundled default") },
                                enabled = form?.dictionaryIsDefault == false,
                                onClick = {
                                    viewModel.resetDictionary()
                                    menuOpen = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        form ?: return@Scaffold
        val text = form.dictionary
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DictionaryField(
                label = "Expected words",
                hint = "Uncommon Romanian words and names, one per line",
                value = text.terms,
                onChange = { viewModel.setDictionary(text.copy(terms = it)) },
            )
            DictionaryField(
                label = "Fixed translations",
                hint = "One per line: țarina = field",
                value = text.translationTerms,
                onChange = { viewModel.setDictionary(text.copy(translationTerms = it)) },
            )
            DictionaryField(
                label = "Context",
                hint = "One per line: domain: Orthodox sermon",
                value = text.general,
                onChange = { viewModel.setDictionary(text.copy(general = it)) },
            )
            DictionaryField(
                label = "Background",
                hint = "Free text about the speaker or topic",
                value = text.text,
                onChange = { viewModel.setDictionary(text.copy(text = it)) },
            )
        }
    }
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
