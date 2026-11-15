package io.infotive.anulleong.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.infotive.anulleong.R

/** "Morning drive" palette: sky, road, and three feeling colours a child can name. */
object C {
    val sky = Color(0xFFE7F1FC)       // page background
    val skyDeep = Color(0xFFCFE3F7)   // hero sky gradient end
    val paper = Color(0xFFFFFFFF)     // surfaces
    val ink = Color(0xFF1B2A4A)       // text
    val inkSoft = Color(0xFF5B6B88)   // secondary text
    val line = Color(0xFFD6E2F0)
    val road = Color(0xFF34405C)      // asphalt
    val grass = Color(0xFFBFE6C9)
    val periwinkle = Color(0xFF5B6CFF) // brand / primary action
    val mint = Color(0xFF23B38A)      // 괜찮아요
    val sun = Color(0xFFFFB81C)       // 조금 불편 / notice
    val berry = Color(0xFFF2557E)     // 멀미 / strong
    val lilac = Color(0xFF8E7CF0)     // demo / past-record
    val lilacDeep = Color(0xFF5E48D8) // past-record banners (white text needs contrast)
}

val Jua = FontFamily(Font(R.font.jua))

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.periwinkle, onPrimary = Color.White, background = C.sky, surface = C.paper,
            onSurface = C.ink, onBackground = C.ink, error = C.berry, surfaceContainerLow = C.paper,
        ),
        content = content,
    )
}
