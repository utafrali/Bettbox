package com.appshub.bettbox

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import com.appshub.bettbox.plugins.AppPlugin
import com.appshub.bettbox.plugins.TilePlugin
import com.appshub.bettbox.plugins.VpnPlugin
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class RunState {
    START,
    PENDING,
    STOP
}

object GlobalState {
    val runLock = ReentrantLock()

    const val NOTIFICATION_CHANNEL = "Bettbox"
    const val NOTIFICATION_ID = 1

    private const val TOGGLE_DEBOUNCE_MS = 1000L
    private const val PENDING_TIMEOUT_MS = 5000L
    private const val STOP_LOCK_TIMEOUT_MS = 5000L

    @Volatile
    private var lastToggleAt = 0L

    @Volatile
    var currentRunState: RunState = RunState.STOP
        private set

    val runState = MutableLiveData(RunState.STOP)

    private var pendingTimeoutJob: Job? = null

    var flutterEngine: FlutterEngine? = null

    @Volatile
    var isSmartStopped = false

    @Volatile
    var isStopping = false

    fun updateRunState(newState: RunState) {
        if (newState != RunState.PENDING) {
            pendingTimeoutJob?.cancel()
            pendingTimeoutJob = null
        }
        currentRunState = newState
        runState.postValueSafe(newState)
    }

    private fun MutableLiveData<RunState>.postValueSafe(value: RunState) = try {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            this.value = value
        } else {
            postValue(value)
        }
    } catch (_: Exception) {
        postValue(value)
    }

    private fun startPendingTimeout() {
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(PENDING_TIMEOUT_MS)
            if (currentRunState == RunState.PENDING) {
                android.util.Log.w("GlobalState", "PENDING state timeout, resetting to STOP")
                updateRunState(RunState.STOP)
            }
        }
    }

    fun updateIsStopping(value: Boolean) {
        isStopping = value
        runCatching {
            val ts = if (value) System.currentTimeMillis() else 0L
            BettboxApplication.getAppContext()
                .getSharedPreferences("vpn_state", android.content.Context.MODE_PRIVATE)
                .edit()
                .putLong("stop_lock_ts", ts)
                .apply()
        }
    }

    fun isCurrentlyStopping(): Boolean {
        if (isStopping) return true
        return runCatching {
            val sp = BettboxApplication.getAppContext()
                .getSharedPreferences("vpn_state", android.content.Context.MODE_PRIVATE)
            val ts = sp.getLong("stop_lock_ts", 0L)
            if (ts == 0L) return false

            val now = System.currentTimeMillis()
            if (now - ts > STOP_LOCK_TIMEOUT_MS) {
                sp.edit().remove("stop_lock_ts").apply()
                false
            } else {
                true
            }
        }.getOrDefault(false)
    }

    fun getCurrentAppPlugin(): AppPlugin? {
        return flutterEngine?.plugins?.get(AppPlugin::class.java) as? AppPlugin
    }

    fun syncStatus() {
        val status = VpnPlugin.getStatus()
        updateRunState(if (status) RunState.START else RunState.STOP)
    }

    suspend fun getText(text: String): String = getCurrentAppPlugin()?.getText(text) ?: ""

    fun getCurrentTilePlugin(): TilePlugin? {
        return flutterEngine?.plugins?.get(TilePlugin::class.java) as? TilePlugin
    }

    fun getCurrentVPNPlugin(): VpnPlugin? {
        return flutterEngine?.plugins?.get(VpnPlugin::class.java) as? VpnPlugin
    }

    fun handleToggle() {
        if (!acquireToggleSlot()) return
        if (!handleStart(skipDebounce = true)) {
            handleStop(skipDebounce = true)
        }
    }

    fun handleStart(skipDebounce: Boolean = false): Boolean {
        if (!skipDebounce && !acquireToggleSlot()) return false
        if (currentRunState != RunState.STOP) return false

        updateRunState(RunState.PENDING)
        startPendingTimeout()
        runLock.withLock {
            if (flutterEngine != null) {
                getCurrentTilePlugin()?.handleStart() ?: VpnPlugin.startLastKnownProfile()
            } else {
                VpnPlugin.startLastKnownProfile()
            }
        }
        return true
    }

    fun handleStop(skipDebounce: Boolean = false) {
        if (!skipDebounce && !acquireToggleSlot()) return
        if (currentRunState != RunState.START) return

        updateRunState(RunState.PENDING)
        startPendingTimeout()
        runLock.withLock {
            if (flutterEngine != null) {
                getCurrentTilePlugin()?.handleStop() ?: VpnPlugin.handleStop()
            } else {
                VpnPlugin.handleStop()
            }
        }
    }

    private fun acquireToggleSlot(): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastToggleAt < TOGGLE_DEBOUNCE_MS) return false
            lastToggleAt = now
            return true
        }
    }

    fun handleTryDestroy() = Unit
}
