package io.infotive.anulleong

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.infotive.anulleong.ui.MainScreen
import io.infotive.anulleong.ui.AppTheme

class MainActivity : ComponentActivity() {
    private val vm: CareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val ui by vm.ui.collectAsStateWithLifecycle()
                MainScreen(ui, vm)
            }
        }
    }
}
