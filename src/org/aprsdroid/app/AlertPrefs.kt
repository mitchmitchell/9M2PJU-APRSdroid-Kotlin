package org.aprsdroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.text.input.KeyboardType
import org.aprsdroid.app.ui.PrefItem
import org.aprsdroid.app.ui.PreferenceScreen
import org.aprsdroid.app.ui.theme.AprsTheme

/** Settings → Alerts. Changes apply at once while tracking is on. */
class AlertPrefs : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UIHelper.applySystemBarInsets(this)
        val unit = if (PrefsWrapper(this).isMetric()) "km" else "miles"
        setContent {
            AprsTheme {
                PreferenceScreen(
                    title = "Alerts",
                    onBack = { finish() },
                    items = listOf(
                        PrefItem.Switch(
                            key = "alerts.enabled",
                            title = "Alerts",
                            summary = "Notify me about stations and weather while tracking is on",
                            default = false,
                        ),
                        PrefItem.Category("Stations I watch"),
                        PrefItem.EditText(
                            key = "alerts.watch",
                            title = "Callsigns",
                            summary = "Separate with commas. W5ABC covers every SSID, W5ABC-9 only that one, KD5* everything starting KD5.",
                            hint = "W5ABC, N5XYZ-9",
                            dependency = "alerts.enabled",
                        ),
                        PrefItem.Switch(
                            key = "alerts.heard",
                            title = "When one comes on the air",
                            summary = "After 30 minutes or more of silence",
                            default = true,
                            dependency = "alerts.enabled",
                        ),
                        PrefItem.Switch(
                            key = "alerts.moved",
                            title = "When one starts moving",
                            default = true,
                            dependency = "alerts.enabled",
                        ),
                        PrefItem.Category("Stations near me"),
                        PrefItem.EditText(
                            key = "alerts.area_radius",
                            title = "Distance ($unit)",
                            summary = "Alert when a station comes within this distance. 0 turns it off.",
                            default = "0",
                            keyboardType = KeyboardType.Decimal,
                            dependency = "alerts.enabled",
                        ),
                        PrefItem.Switch(
                            key = "alerts.area_anyone",
                            title = "Any moving station",
                            summaryOn = "Every station on the move that comes near",
                            summaryOff = "Only the stations I watch",
                            default = false,
                            dependency = "alerts.enabled",
                        ),
                        PrefItem.EditText(
                            key = "alerts.area_centre",
                            title = "Measure from",
                            summary = "Leave empty to use where the phone is. Or a fixed place as latitude, longitude.",
                            hint = "33.0110, -97.0527",
                            dependency = "alerts.enabled",
                        ),
                        PrefItem.Category("Weather"),
                        PrefItem.Switch(
                            key = "alerts.weather",
                            title = "Weather warnings",
                            summary = "From the US National Weather Service for where you are, checked every 10 minutes. Needs an internet connection.",
                            default = false,
                            dependency = "alerts.enabled",
                        ),
                        PrefItem.List(
                            key = "alerts.weather_min",
                            title = "How serious",
                            entries = arrayOf("Everything, including advisories", "Moderate and worse", "Severe and extreme only"),
                            entryValues = arrayOf("minor", "moderate", "severe"),
                            default = "moderate",
                            dependency = "alerts.weather",
                        ),
                    ),
                )
            }
        }
    }
}
