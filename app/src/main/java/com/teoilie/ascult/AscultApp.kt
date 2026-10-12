package com.teoilie.ascult

import android.app.Application
import android.content.Context
import com.teoilie.ascult.session.TranslationSession

class AscultApp : Application() {
    /** One session per process, shared by the screen and the foreground service. */
    val session by lazy { TranslationSession(this) }
}

val Context.session: TranslationSession get() = (applicationContext as AscultApp).session
