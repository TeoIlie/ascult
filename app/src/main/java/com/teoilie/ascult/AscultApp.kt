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

val Context.session: TranslationSession get() = (applicationContext as AscultApp).session

val Context.settings: SettingsStore get() = (applicationContext as AscultApp).settings
