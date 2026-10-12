package com.teoilie.ascult

import android.app.Application
import android.content.Context
import com.teoilie.ascult.session.TranslationSession
import com.teoilie.ascult.settings.SettingsStore

class AscultApp : Application() {
    val settings by lazy { SettingsStore(this) }

    /** One session per process, shared by the screen and the foreground service. */
    val session by lazy { TranslationSession(this, settings) }
}

/** The app's shared objects: `context.app.session`, `context.app.settings`. */
val Context.app: AscultApp get() = applicationContext as AscultApp
