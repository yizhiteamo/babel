package com.babel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.babel.app.ui.BabelApp
import com.babel.app.ui.theme.BabelTheme
import com.babel.platform.capture.MediaProjectionScreenCapture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Injected here rather than reached through the ViewModel: starting a
     * session needs the Activity's result registry for the consent dialog.
     */
    @Inject
    lateinit var screenCapture: MediaProjectionScreenCapture

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BabelTheme {
                BabelApp(screenCapture = screenCapture)
            }
        }
    }
}
