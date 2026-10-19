package com.teoilie.ascult

import android.app.Application
import android.content.Context
import com.teoilie.ascult.history.HistoryStore
import com.teoilie.ascult.session.TranslationSession
import com.teoilie.ascult.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AscultApp : Application() {
    val settings by lazy { SettingsStore(this) }

    /** Saved runs; loaded in the background when first used. */
    val history by lazy { HistoryStore(this, CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

    /** One session per process, shared by the screen and the foreground service. */
    val session by lazy { TranslationSession(this, settings, history) }
}

/** The app's shared objects: `context.app.session`, `context.app.settings`, `context.app.history`. */
val Context.app: AscultApp get() = applicationContext as AscultApp
