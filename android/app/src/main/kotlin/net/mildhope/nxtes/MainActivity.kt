package net.mildhope.nxtes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.mildhope.nxtes.library.LibraryScreen
import net.mildhope.nxtes.ui.NxtesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NxtesTheme {
                LibraryScreen()
            }
        }
    }
}
