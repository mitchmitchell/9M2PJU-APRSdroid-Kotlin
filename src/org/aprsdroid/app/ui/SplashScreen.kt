package org.aprsdroid.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import org.aprsdroid.app.BuildConfig
import org.aprsdroid.app.R

private val Top = Color(0xFF13233F)
private val Bottom = Color(0xFF0A1222)
private val Amber = Color(0xFFEBCF3A)
private val Ink = Color(0xFFF2F5FA)
private val Quiet = Color(0xFF9FB4CC)

/**
 * The launch screen. The artwork tells the app's story left to right:
 * packets from your radio, through APRSdroid, onto the map in TAK.
 *
 * Always navy, whatever the theme, so it matches the window it starts on.
 * The artwork source is doc/art/splash_art.svg.
 */
@Composable
fun SplashScreen() {
    val context = LocalContext.current
    // One short fade-and-settle as the app opens; skipped when the user has turned animations off.
    val animationsOff = remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
    val appear = remember { Animatable(if (animationsOff) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Top, Bottom)))
            .safeDrawingPadding(),
    ) {
        val wide = maxWidth > maxHeight
        val art: @Composable (Modifier) -> Unit = { m ->
            Image(
                painter = painterResource(R.drawable.splash_art),
                // The words beside it say what the picture shows.
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = m.graphicsLayer {
                    alpha = appear.value
                    val s = 0.94f + 0.06f * appear.value
                    scaleX = s
                    scaleY = s
                },
            )
        }
        val words: @Composable (TextAlign) -> Unit = { align ->
            Text(
                "APRSDroid-TAK",
                style = TextStyle(fontSize = if (wide) 44.sp else 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                color = Ink,
                textAlign = align,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "From your radio, to the map, to TAK.",
                style = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
                color = Amber,
                textAlign = align,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Built on APRSdroid by Georg Lukas DO1GL, with the work of NA7Q and 9M2PJU.\n" +
                    "Version ${BuildConfig.VERSION_NAME}",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = Quiet,
                textAlign = align,
            )
        }

        if (wide) {
            // Tablets and landscape: picture on the left, words on the right.
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                art(Modifier.weight(1.5f).fillMaxHeight(0.8f))
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f).widthIn(max = 420.dp)) { words(TextAlign.Start) }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                art(Modifier.fillMaxWidth())
                Spacer(Modifier.height(28.dp))
                words(TextAlign.Center)
            }
        }
    }
}
