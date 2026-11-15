package io.infotive.anulleong

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.infotive.anulleong.ui.AppTheme
import io.infotive.anulleong.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val vm: CareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                // In-car displays run at low density (Pleos: 2560×1440 @160dpi = 1600 dp wide) and are
                // read at arm's length, so scale the whole UI up there; phones keep their own density.
                val widthDp = LocalConfiguration.current.screenWidthDp
                val scale = when { widthDp >= 1500 -> 1.4f; widthDp >= 1200 -> 1.2f; else -> 1f }
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    MainScreen(ui, vm)
                }
            }
        }
    }
}
