package org.aprsdroid.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.aprsdroid.app.data.AprsDatabase
import org.aprsdroid.app.data.PostEntity
import org.aprsdroid.app.data.StationEntity

/**
 * ViewModel for the station detail screen (StationActivity).
 *
 * Shows a single station's details, its SSIDs, and its recent
 * packets. Replaces the Scala `StationActivity` +
 * `StationListAdapter` (SSIDs mode) + `PostListAdapter` stack.
 */
class StationDetailViewModel(
    app: Application,
    val targetCall: String,
) : AndroidViewModel(app) {

    private val db = AprsDatabase.get(app)

    /** The target station's position/info. */
    val station: StateFlow<StationEntity?> =
        kotlinx.coroutines.flow.flow {
            emit(db.stationDao().getStaPosition(targetCall))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    /** All SSIDs of the target call. */
    val ssids: StateFlow<List<StationEntity>> =
        db.stationDao().getAllSsids(
            targetCall.substringBefore('-'),
            "${targetCall.substringBefore('-')}-%",
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    /** Recent posts/packets for the target station. */
    val posts: StateFlow<List<PostEntity>> =
        db.postDao().getStaPosts(targetCall, "300")
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    val isMetric: Boolean = org.aprsdroid.app.PrefsWrapper(app).isMetric()
    val myCall: String = org.aprsdroid.app.PrefsWrapper(app).getCallSsid()

    /** Our own position (latE6, lonE6): our latest beacon, else the phone's last fix. */
    val myPosition: StateFlow<Pair<Int, Int>?> =
        kotlinx.coroutines.flow.flow {
            val own = db.stationDao().getStaPosition(myCall)
            emit(
                own?.let { it.lat to it.lon }
                    ?: org.aprsdroid.app.MapData.lastKnownLocation(app)
                        ?.let { (it.lat * 1e6).toInt() to (it.lon * 1e6).toInt() },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ---- history: what this device has heard, or more from the user's history server ----

    private val localHistory: kotlinx.coroutines.flow.Flow<org.aprsdroid.app.StationHistory> =
        kotlinx.coroutines.flow.combine(db.positionDao().getPositionsForCall(targetCall), posts) { positions, posts ->
            val track = positions.asReversed().map {
                org.aprsdroid.app.TrackPoint(it.ts, it.lat / 1e6, it.lon / 1e6)
            }
            val weather = posts.mapNotNull { p ->
                org.aprsdroid.app.AprsWeather.fromPacket(p.message)?.let { org.aprsdroid.app.WeatherSample(p.ts, it) }
            }.sortedBy { it.ts }
            org.aprsdroid.app.StationHistory(track, weather, "this device")
        }

    private val serverHistory = kotlinx.coroutines.flow.MutableStateFlow<org.aprsdroid.app.StationHistory?>(null)

    /** Why the history server could not be used, for the user; null when fine or not set up. */
    val historyError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** The fuller of the two histories. */
    val history: StateFlow<org.aprsdroid.app.StationHistory> =
        kotlinx.coroutines.flow.combine(localHistory, serverHistory) { local, server ->
            if (server != null && server.track.size + server.weather.size >= local.track.size + local.weather.size) server
            else local
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            org.aprsdroid.app.StationHistory(emptyList(), emptyList(), "this device"),
        )

    // ---- QRZ.com ----

    /** null = not set up or still loading. */
    val qrz = kotlinx.coroutines.flow.MutableStateFlow<org.aprsdroid.app.QrzReply?>(null)
    val qrzError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val qrzConfigured: Boolean = org.aprsdroid.app.OnlineLookups.hasQrz(app)

    init {
        val io = kotlinx.coroutines.Dispatchers.IO
        if (org.aprsdroid.app.OnlineLookups.hasHistoryServer(app)) {
            viewModelScope.launch(io) {
                try {
                    serverHistory.value = org.aprsdroid.app.OnlineLookups.fetchHistory(app, targetCall, HISTORY_HOURS)
                } catch (e: Exception) {
                    historyError.value = e.message ?: e.javaClass.simpleName
                }
            }
        }
        // QRZ knows licensees, not objects or tactical names: only ask for things shaped like a callsign.
        if (qrzConfigured && CALLSIGN.matches(org.aprsdroid.app.QrzLookup.baseCall(targetCall))) {
            viewModelScope.launch(io) {
                try {
                    qrz.value = org.aprsdroid.app.OnlineLookups.qrz(app, targetCall)
                } catch (e: Exception) {
                    qrzError.value = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    companion object {
        /** How far back to ask the history server: one week. */
        const val HISTORY_HOURS = 168
        private val CALLSIGN = Regex("^[A-Z0-9]{1,3}[0-9][A-Z0-9]{0,3}[A-Z]$")
    }

    class Factory(private val app: Application, private val targetCall: String) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StationDetailViewModel(app, targetCall) as T
        }
    }
}
