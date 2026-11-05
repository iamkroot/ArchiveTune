/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmedia

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import moe.rukamori.archivetune.constants.LocalSongsExcludedFoldersKey
import moe.rukamori.archivetune.constants.LocalSongsIncludedFoldersKey
import moe.rukamori.archivetune.constants.LocalSongsMinDurationSecondsKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: LocalSongScanner,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isStarted = AtomicBoolean(false)
    private val syncMutex = Mutex()
    private val debounceLock = Any()
    private var debounceJob: Job? = null

    @Volatile
    private var lastSyncTimestamp = 0L

    @Volatile
    private var failureBackoffMs = 0L

    private val _onSyncCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onSyncCompleted: SharedFlow<Unit> = _onSyncCompleted.asSharedFlow()

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            Timber.tag(LogTag).d("MediaStore change detected, scheduling local sync")
            syncDebounced(initialDelayMs = 1500L)
        }
    }

    fun start() {
        if (!isStarted.compareAndSet(false, true)) return
        registerObserver()
        syncDebounced(initialDelayMs = 2000L)
    }

    private fun registerObserver() {
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver,
            )
        }.onFailure {
            Timber.tag(LogTag).w(it, "Failed to register MediaStore ContentObserver")
        }
    }

    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun syncDebounced(initialDelayMs: Long = 1000L) {
        synchronized(debounceLock) {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                if (initialDelayMs > 0L) {
                    delay(initialDelayMs)
                }
                sync()
            }
        }
    }

    suspend fun sync(force: Boolean = false) {
        if (!hasPermission()) {
            Timber.tag(LogTag).d("Skipping local media sync: audio permission not granted")
            return
        }

        val now = System.currentTimeMillis()
        if (!force && (now - lastSyncTimestamp < (MIN_SYNC_INTERVAL_MS + failureBackoffMs))) {
            return
        }

        if (!syncMutex.tryLock()) return

        try {
            Timber.tag(LogTag).i("Starting automatic local media sync")
            val minDuration = context.dataStore.get(LocalSongsMinDurationSecondsKey, 0)
            val included = context.dataStore.get(LocalSongsIncludedFoldersKey, emptySet())
            val excluded = context.dataStore.get(LocalSongsExcludedFoldersKey, emptySet())
            val config = LocalSongScanConfig(
                minimumDurationSeconds = minDuration,
                includedFolders = included,
                excludedFolders = excluded,
            )
            val summary = scanner.scanDevice(config)
            failureBackoffMs = 0L
            _onSyncCompleted.tryEmit(Unit)
            Timber.tag(LogTag).i("Local media sync completed: %d songs scanned", summary.scannedSongs)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            failureBackoffMs = (failureBackoffMs * 2).coerceIn(10_000L, 120_000L).coerceAtLeast(10_000L)
            Timber.tag(LogTag).w(error, "Local media sync failed, backing off for %d ms", failureBackoffMs)
        } finally {
            lastSyncTimestamp = System.currentTimeMillis()
            syncMutex.unlock()
        }
    }

    companion object {
        private const val LogTag = "LocalMediaSyncCoordinator"
        private const val MIN_SYNC_INTERVAL_MS = 10_000L
    }
}
