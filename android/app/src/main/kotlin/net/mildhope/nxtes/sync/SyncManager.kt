package net.mildhope.nxtes.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.ink.PageSession
import net.mildhope.nxtes.nxtes
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class SyncProblem { OFFLINE, SIGNED_OUT, STORAGE_FULL, SERVER }

data class SyncStatus(val running: Boolean = false, val lastSynced: Long? = null, val problem: SyncProblem? = null)

/**
 * Runs sync for the app: soon after local changes, when another device changes something while
 * the app is open, every hour in the background, and when asked.
 */
class SyncManager(private val context: Context, private val store: LibraryStore, private val scope: CoroutineScope) {
    private val dir = File(context.noBackupFilesDir, "sync")
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var live: Job? = null

    val account = SyncAccount(File(dir, "account.json"))
    val client = SyncClient(account)
    val sessions = OpenSessions()
    val engine = SyncEngine(store, client, dir, sessions)

    private val _status = MutableStateFlow(SyncStatus(lastSynced = prefs.getLong(LAST_SYNCED, 0).takeIf { it > 0 }))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var wifiOnlyState by mutableStateOf(prefs.getBoolean(WIFI_ONLY, false))

    /** Sync only over unmetered networks such as Wi-Fi. */
    var wifiOnly: Boolean
        get() = wifiOnlyState
        set(value) {
            wifiOnlyState = value
            prefs.edit { putBoolean(WIFI_ONLY, value) }
            schedule()
            requestSync()
        }

    init {
        scope.launch(Dispatchers.IO) {
            store.state.first { it.loaded }
            engine.prepare()
            wake.receiveAsFlow().collect { syncNow() }
        }
        scope.launch {
            store.state.map { if (it.loaded) it.folders to it.documents else null }.distinctUntilChanged().collectLatest { library ->
                if (library == null || account.session == null) return@collectLatest
                delay(LOCAL_CHANGE_DELAY_MS)
                requestSync()
            }
        }
    }

    fun requestSync() {
        wake.trySend(Unit)
    }

    /** Syncs now unless signed out or offline; [userAsked] ignores the Wi-Fi only setting. Returns false when it failed. */
    suspend fun syncNow(userAsked: Boolean = false): Boolean {
        val session = account.session ?: return true
        if (!online(ignoreWifiOnly = userAsked)) {
            if (userAsked) _status.update { it.copy(problem = SyncProblem.OFFLINE) }
            return !userAsked
        }
        _status.update { it.copy(running = true) }
        return try {
            store.state.first { it.loaded }
            if (engine.userId != session.userId) engine.reset(session.userId)
            engine.sync()
            val now = System.currentTimeMillis()
            prefs.edit { putLong(LAST_SYNCED, now) }
            _status.value = SyncStatus(lastSynced = now)
            true
        } catch (e: CancellationException) {
            _status.update { it.copy(running = false) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            _status.update { it.copy(running = false, problem = problemOf(e)) }
            false
        }
    }

    suspend fun signIn(server: String, email: String, password: String) {
        val session = client.login(server, email, password, deviceName())
        if (engine.userId != session.userId) engine.reset(session.userId)
        account.signIn(session)
        _status.value = SyncStatus()
        schedule()
        requestSync()
    }

    suspend fun signOut() {
        runCatching { client.logout() }.onFailure { Log.w(TAG, "Signing out on the server failed", it) }
        forget()
    }

    suspend fun deleteAccount(password: String) {
        client.deleteAccount(password)
        forget()
    }

    private suspend fun forget() {
        account.signOut()
        engine.reset(null)
        prefs.edit { remove(LAST_SYNCED) }
        _status.value = SyncStatus()
        schedule()
    }

    /** Listens for other devices' changes while the app is on screen, and syncs once more when it leaves. */
    fun setForeground(foreground: Boolean) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (!foreground) {
            if (watching) connectivity.unregisterNetworkCallback(networkCallback)
            watching = false
            live?.cancel()
            live = null
            if (account.session != null) syncInBackground()
            return
        }
        if (watching) return
        network = connectivity.activeNetwork
        connectivity.registerDefaultNetworkCallback(networkCallback)
        watching = true
        requestSync()
        listen()
    }

    private var watching = false
    private var network: Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(available: Network) {
            scope.launch {
                if (!watching || available == network) return@launch
                network = available
                requestSync()
                listen()
            }
        }

        override fun onLost(lost: Network) {
            scope.launch { if (lost == network) network = null }
        }
    }

    /** (Re)connects to the server's change events, retrying with growing pauses while it can't. */
    private fun listen() {
        live?.cancel()
        live = scope.launch(Dispatchers.IO) {
            var wait = RETRY_MS
            while (isActive) {
                if (account.session == null || !online()) {
                    delay(IDLE_CHECK_MS)
                    continue
                }
                var opened = false
                try {
                    client.listen(
                        onOpen = {
                            opened = true
                            requestSync()
                        },
                        onChange = { seq -> if (seq > engine.cursor) requestSync() },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "Live updates stopped", e)
                }
                wait = if (opened) RETRY_MS else (wait * 2).coerceAtMost(MAX_RETRY_MS)
                delay(wait)
            }
        }
    }

    /** Keeps the hourly background sync in line with the account and the Wi-Fi setting. */
    fun schedule() {
        val work = WorkManager.getInstance(context)
        if (account.session == null) {
            work.cancelUniqueWork(PERIODIC_WORK)
            work.cancelUniqueWork(ONCE_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(constraints()).build()
        work.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun syncInBackground() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun constraints() =
        Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

    private fun online(ignoreWifiOnly: Boolean = false): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return ignoreWifiOnly || !wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun problemOf(e: Exception) = when {
        e is ApiException && e.status == 401 -> SyncProblem.SIGNED_OUT
        e is ApiException && e.status == 507 -> SyncProblem.STORAGE_FULL
        e is ApiException -> SyncProblem.SERVER
        e is IOException -> SyncProblem.OFFLINE
        else -> SyncProblem.SERVER
    }

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    private companion object {
        const val TAG = "Sync"
        const val WIFI_ONLY = "wifi_only"
        const val LAST_SYNCED = "last_synced"
        const val PERIODIC_WORK = "sync"
        const val ONCE_WORK = "sync-once"
        const val LOCAL_CHANGE_DELAY_MS = 4_000L
        const val RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 120_000L
        const val IDLE_CHECK_MS = 15_000L
    }
}

/** The page sessions of documents open on screen. Touch only from the main thread. */
class OpenSessions : OpenDocuments {
    private val sessions = mutableMapOf<String, PageSession>()

    fun register(documentId: String, session: PageSession) {
        sessions[documentId] = session
    }

    fun unregister(documentId: String, session: PageSession) {
        if (sessions[documentId] === session) sessions.remove(documentId)
    }

    override suspend fun flush() = withContext(Dispatchers.Main) { sessions.values.toList().forEach { it.flush() } }

    override suspend fun reload(documentId: String) = withContext(Dispatchers.Main) { sessions[documentId]?.reset() ?: Unit }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (applicationContext.nxtes.sync.syncNow()) Result.success() else Result.retry()
}
