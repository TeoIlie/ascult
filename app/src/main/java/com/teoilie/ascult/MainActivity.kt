package com.teoilie.ascult

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.teoilie.ascult.ui.AscultTheme
import com.teoilie.ascult.ui.TranslationScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AscultTheme {
                TranslationScreen()
            }
        }
    }
}
