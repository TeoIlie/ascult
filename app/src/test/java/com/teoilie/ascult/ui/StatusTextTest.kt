package com.teoilie.ascult.ui

import com.teoilie.ascult.session.Phase
import com.teoilie.ascult.session.SessionState
import com.teoilie.ascult.settings.AudioSettings
import com.teoilie.ascult.settings.MicSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class StatusTextTest {
    private val audio = AudioSettings(source = MicSource.UNPROCESSED, gainDb = 12)
    private val settings = "Unprocessed · +12 dB"

    @Test
    fun `idle shows the settings, noting when recording is on`() {
        assertEquals(StatusText("Ready", settings), statusText(SessionState(), audio))
        assertEquals(
            StatusText("Ready", "$settings · Recording on"),
            statusText(SessionState(), audio.copy(saveRecording = true)),
        )
    }

    @Test
    fun `listening says whether it is recording`() {
        assertEquals(StatusText("Listening", settings), statusText(SessionState(phase = Phase.LISTENING), audio))
        assertEquals(
            StatusText("Recording", settings),
            statusText(SessionState(phase = Phase.LISTENING, recording = File("x.wav")), audio.copy(saveRecording = true)),
        )
    }

    @Test
    fun `reconnecting and finishing replace the phase`() {
        assertEquals(
            StatusText("Reconnecting…", "Audio is kept and sent once back"),
            statusText(SessionState(phase = Phase.RECONNECTING), audio),
        )
        assertEquals(StatusText("Finishing…", settings), statusText(SessionState(phase = Phase.STOPPING), audio))
    }

    @Test
    fun `an error wins over every phase`() {
        for (phase in Phase.entries) {
            val state = SessionState(phase = phase, error = "Invalid API key")
            assertEquals(StatusText("Error", "Invalid API key", isError = true), statusText(state, audio))
        }
    }
}
