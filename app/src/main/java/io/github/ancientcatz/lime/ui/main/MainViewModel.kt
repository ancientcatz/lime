package io.github.ancientcatz.lime.ui.main

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.ancientcatz.lime.R
import io.github.ancientcatz.lime.SharedConfig
import io.github.ancientcatz.lime.model.Orientation
import java.text.Collator

/**
 * View model for the installed-app list.
 *
 * Implements progressive icon rendering with alphabetical priority:
 *
 *  Phase 1 (enumeration + sorting): on a background thread, enumerate installed apps,
 *  load their labels, filter by the show-system-apps preference, sort with configured apps
 *  first (then alphabetical by label), and capture the configured orientation. The list is
 *  published as soon as Phase 1 completes — initially empty (no icons yet), then icons
 *  trickle in during Phase 2.
 *
 *  Phase 2 (icon loading): icons are loaded concurrently on a bounded dispatcher
 *  ([ICON_CONCURRENCY] = 8). Icons are requested in sort order: the pending queue is
 *  already sorted, so iterating it in order naturally prioritizes configured apps and the
 *  start of the alphabet. As each icon finishes, the corresponding item is moved from the
 *  pending list to the ready list at its correct position, and the visible list is
 *  republished (throttled). Items with a failed icon load are skipped (logged) and never
 *  appear, so a single failure does not block the rest of the list.
 *
 *  Sort order: configured apps (orientation != UNSPECIFIED) first, then unconfigured apps.
 *  Within each group, apps are sorted alphabetically by label (locale-aware, case-insensitive
 *  via [Collator] PRIMARY strength), with packageName as a deterministic tie-break.
 *
 *  List updates are throttled ([PUBLISH_THROTTLE_MS] = 120ms) to avoid excessive DiffUtil
 *  passes during rapid icon loading. This keeps scrolling smooth — without throttling, each
 *  icon load would trigger a separate submitList + DiffUtil pass, causing jitter when the
 *  user scrolls during loading.
 *
 *  All expensive work (PackageManager calls, icon decoding, sorting) runs off the main
 *  thread. UI state (the visible list, the loading flag) is published via LiveData
 *  postValue, which is safe from any thread. Obsolete loading jobs are cancelled when a
 *  new load starts (force reload) or when the view model is cleared.
 */
class MainViewModel(app: Application) : AndroidViewModel(app),
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "LimeMainViewModel"
        /**
         * Maximum number of icons loaded concurrently. Bounded to avoid excessive I/O / CPU
         * contention on PackageManager (which holds a lock and marshals across the package
         * service). 8 is a reasonable balance between throughput and resource pressure on
         * typical devices.
         */
        private const val ICON_CONCURRENCY = 8
        /**
         * Minimum delay between list publishes during icon loading. Rapid icon completions
         * are coalesced into a single publish every [PUBLISH_THROTTLE_MS] ms, reducing the
         * number of DiffUtil passes and keeping scrolling smooth.
         */
        private const val PUBLISH_THROTTLE_MS = 120L
    }

    private val sharedConfig = SharedConfig.Provider(app)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    private val keyshowSystemApps = app.getString(R.string.key_setting_show_system_apps)
    private val showSystemApps get() = prefs.getBoolean(keyshowSystemApps, false)

    /**
     * The full list of enumerated apps with their labels and orientations, but WITHOUT icons
     * (icon == null). Sorted by [readyListComparator]. Populated during Phase 1 and used as
     * the source of truth for icon loading and for the current orientation state.
     * Access is synchronized on [listLock].
     */
    private val appList = mutableListOf<AppListItem>()

    /**
     * Indices into [appList] for items whose icon has NOT yet been loaded. In the same sort
     * order as [appList] (so iterating this queue loads icons in priority order: configured
     * apps first, then alphabetical). Mutated only on the background thread.
     */
    private val pendingIconIndices = ArrayDeque<Int>()

    /**
     * The currently visible list: items whose icons have loaded successfully, in sort order
     * (configured first, then alphabetical). Published to [appListLiveData] on each
     * incremental update (throttled). Access is synchronized on [listLock].
     */
    private val readyList = mutableListOf<AppListItem>()

    /**
     * Lock for synchronized access to [appList] and [readyList] from multiple background
     * coroutines (icon loading + config updates).
     */
    private val listLock = Any()

    private val appListLiveDataInternal = MutableLiveData<List<AppListItem>>()
    private var hasLoadedAppList = false

    /**
     * Whether a load is currently in progress. Used to guard against concurrent loads
     * (without a loading screen, there is no LiveData for this — just an internal flag).
     */
    @Volatile
    private var isLoading = false

    /**
     * The current icon-loading job. Cancelled when a new load starts (force reload) so that
     * in-flight icon loads from the previous enumeration do not leak into the new list.
     */
    private var loadJob: Job? = null

    /**
     * A bounded view of [Dispatchers.IO] for icon loading. Limits concurrent icon loads to
     * [ICON_CONCURRENCY] regardless of how many coroutines we launch.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val iconDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(ICON_CONCURRENCY)

    /**
     * Comparator for [readyList] and [appList]: configured apps (orientation != UNSPECIFIED)
     * first, then alphabetical by label (locale-aware, case-insensitive via [Collator] PRIMARY
     * strength), with [packageName] as a deterministic tie-break.
     */
    private val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    private val readyListComparator: Comparator<AppListItem> = Comparator { a, b ->
        // Configured apps (orientation != UNSPECIFIED) come first.
        val aConfigured = a.orientation != Orientation.UNSPECIFIED
        val bConfigured = b.orientation != Orientation.UNSPECIFIED
        if (aConfigured != bConfigured) {
            if (aConfigured) -1 else 1
        } else {
            val byKey = collator.compare(a.sortKey, b.sortKey)
            if (byKey != 0) byKey else collator.compare(a.packageName, b.packageName)
        }
    }

    /**
     * Active load benchmark, or null when no load is in progress. Captures timing metrics
     * (time to enumeration done, time to first icon, time to a useful portion of the list,
     * time to all icons done) and logs them at the end of the load. See [LoadBenchmark].
     */
    @Volatile
    private var currentBenchmark: LoadBenchmark? = null

    /**
     * Pending throttled publish job. When non-null, a publish is scheduled but not yet
     * executed. Calling [schedulePublish] cancels any pending publish and schedules a new
     * one, coalescing rapid updates.
     */
    @Volatile
    private var publishJob: Job? = null

    val appListLiveData: LiveData<List<AppListItem>> = appListLiveDataInternal

    @Volatile
    var queryText = ""
        set(value) {
            field = value
            schedulePublish()
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        loadJob?.cancel()
        publishJob?.cancel()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val k = key ?: return
        sharedConfig.savePref(k)
        if (k == keyshowSystemApps) {
            hasLoadedAppList = false
        }
    }

    /**
     * Kicks off the two-phase app-list load. Idempotent: if a load is already in progress
     * or the list has already been loaded (and [forceReload] is false), this is a no-op.
     * Calling with [forceReload] = true cancels any in-flight load and starts fresh.
     */
    fun loadAppList(forceReload: Boolean = false) {
        if (isLoading || (hasLoadedAppList && !forceReload)) {
            return
        }
        loadJob?.cancel()
        isLoading = true
        val benchmark = LoadBenchmark().also { currentBenchmark = it }
        loadJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                // ── Phase 1: enumerate + sort ──────────────────────────────────────────
                synchronized(listLock) {
                    appList.clear()
                    readyList.clear()
                }
                pendingIconIndices.clear()
                schedulePublish()

                val packageManager = getApplication<Application>().packageManager
                val enumerated = packageManager.getInstalledApplications(0)
                    .filter {
                        showSystemApps || (it.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
                                it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0)
                    }
                    .map { appInfo ->
                        val configApp = sharedConfig.findConfigApp(appInfo.packageName)
                        AppListItem(
                            packageName = appInfo.packageName,
                            name = runCatching {
                                packageManager.getApplicationLabel(appInfo).toString()
                            }.getOrDefault(appInfo.packageName),
                            icon = null,
                            info = appInfo,
                            orientation = if (configApp != null) {
                                Orientation.values()[configApp.orientation]
                            } else {
                                Orientation.UNSPECIFIED
                            },
                        )
                    }
                    .sortedWith(readyListComparator)

                synchronized(listLock) {
                    appList.addAll(enumerated)
                }

                // Populate the pending queue in sort order (appList is already sorted).
                for (i in enumerated.indices) {
                    pendingIconIndices.addLast(i)
                }

                benchmark.onEnumerationDone(enumerated.size)
                hasLoadedAppList = true
                isLoading = false

                // ── Phase 2: load icons concurrently in priority order ──────────────────
                loadIcons()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load app list", t)
                isLoading = false
            }
        }
    }

    /**
     * Loads icons for all pending apps. Icons are requested in sort order (the pending
     * queue preserves [appList]'s sort order: configured first, then alphabetical). Each
     * icon load runs on [iconDispatcher] (bounded to [ICON_CONCURRENCY] concurrent loads).
     * When an icon finishes, the item is moved from the pending queue to [readyList] at its
     * correct position and the visible list is republished (throttled).
     *
     * Failed icon loads are logged and the item is silently dropped from the ready list —
     * it never appears, but does not block other items.
     */
    private suspend fun loadIcons() {
        val packageManager = getApplication<Application>().packageManager
        // Launch icon loads in sort order. The bounded dispatcher naturally limits
        // concurrency; launching them in order means the first ICON_CONCURRENCY items
        // (configured apps, then top of the alphabet) start loading first.
        val iconJobs = mutableListOf<Job>()
        while (pendingIconIndices.isNotEmpty()) {
            val index = pendingIconIndices.removeFirst()
            val item = synchronized(listLock) { appList[index] }
            iconJobs += viewModelScope.launch(iconDispatcher) {
                val icon: Drawable? = runCatching {
                    packageManager.getApplicationIcon(item.info)
                }.getOrElse {
                    Log.w(TAG, "Failed to load icon for ${item.packageName}", it)
                    null
                }
                if (icon != null) {
                    onIconReady(index, icon)
                }
            }
        }
        // Wait for all icon loads to finish so the coroutine doesn't complete early.
        iconJobs.forEach { it.join() }
        // Final publish (immediate, not throttled) to ensure the complete list is shown.
        publishNow()
        currentBenchmark?.onAllIconsDone()
    }

    /**
     * Called (on the iconDispatcher thread) when an icon finishes loading. Inserts the item
     * into [readyList] at its correct position and schedules a throttled publish.
     *
     * The insert position is found via binary search using [readyListComparator], so each
     * call is O(log n) for the search plus O(n) for the list shift. [readyList] access is
     * synchronized on [listLock] because multiple icon-loading coroutines may call this
     * concurrently.
     */
    private fun onIconReady(index: Int, icon: Drawable) {
        val readyCount: Int
        synchronized(listLock) {
            val item = appList[index].copy(icon = icon)
            val insertPos = readyList.binarySearch(item, readyListComparator)
                .let { if (it < 0) -(it + 1) else it }
            readyList.add(insertPos, item)
            readyCount = readyList.size
        }
        currentBenchmark?.onIconReady(readyCount)
        schedulePublish()
    }

    /**
     * Schedules a throttled publish of the current [readyList] (filtered by [queryText]) to
     * [appListLiveData]. Rapid calls are coalesced: if a publish is already pending, it is
     * cancelled and rescheduled, so at most one publish executes every
     * [PUBLISH_THROTTLE_MS] ms. This keeps scrolling smooth during rapid icon loading.
     */
    private fun schedulePublish() {
        publishJob?.cancel()
        publishJob = viewModelScope.launch(Dispatchers.Default) {
            delay(PUBLISH_THROTTLE_MS)
            doPublish()
        }
    }

    /**
     * Publishes the current [readyList] immediately, bypassing the throttle. Used for the
     * final publish after all icons are loaded, and for immediate updates (e.g. orientation
     * changes) where the user expects instant feedback.
     */
    private suspend fun publishNow() = withContext(Dispatchers.Default) {
        publishJob?.cancel()
        doPublish()
    }

    /**
     * Takes a synchronized snapshot of [readyList] (filtered by [queryText]) and posts it
     * to [appListLiveData]. Must be called on a background thread.
     */
    private fun doPublish() {
        val q = queryText
        val snapshot = synchronized(listLock) {
            if (q.isEmpty()) {
                readyList.toList()
            } else {
                readyList.filter {
                    it.name.contains(q, true) || it.packageName.contains(q, true)
                }
            }
        }
        appListLiveDataInternal.postValue(snapshot)
    }

    /**
     * Updates the configured orientation for [packageName] and persists it. Also updates the
     * matching item in [appList] (source of truth) and re-inserts it in [readyList] at its
     * new position (since changing orientation may move it between the configured and
     * unconfigured groups). The update is published immediately (not throttled) so the user
     * sees the change instantly.
     */
    fun updateConfigApp(packageName: String, orientation: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            if (orientation != Orientation.UNSPECIFIED.ordinal) {
                sharedConfig.addConfigApp(packageName, orientation)
            } else {
                sharedConfig.removeConfigApp(packageName)
            }
            val newOrientation = Orientation.values()[orientation]
            synchronized(listLock) {
                // Update the canonical entry in appList.
                val listIndex = appList.indexOfFirst { it.packageName == packageName }
                if (listIndex >= 0) {
                    appList[listIndex] = appList[listIndex].copy(orientation = newOrientation)
                }
                // Remove and re-insert the visible entry in readyList at its new position
                // (orientation change may move it between configured/unconfigured groups).
                val readyIndex = readyList.indexOfFirst { it.packageName == packageName }
                if (readyIndex >= 0) {
                    val updated = readyList[readyIndex].copy(orientation = newOrientation)
                    readyList.removeAt(readyIndex)
                    val insertPos = readyList.binarySearch(updated, readyListComparator)
                        .let { if (it < 0) -(it + 1) else it }
                    readyList.add(insertPos, updated)
                }
            }
            publishNow()
        }
    }

    /**
     * Captures timing metrics for a single app-list load (cold start). All times are measured
     * relative to [System.nanoTime] captured at construction (the moment [loadAppList] is
     * called). Metrics are logged via [Log.i] at the end of the load so they can be read
     * from logcat with `adb logcat -s LimeMainViewModel`.
     *
     * Metrics:
     *  - [enumerationDoneMs]: time from load start to Phase 1 complete (apps enumerated,
     *    labels loaded, sorted). This is when the list becomes visible (even though it's
     *    still empty of icons).
     *  - [firstIconMs]: time from load start to the first icon becoming ready. This is when
     *    the user first sees a populated row.
     *  - [usefulPortionMs]: time from load start to a "useful portion" of the list being
     *    visible — defined as 20 ready items (enough to fill a typical phone screen). This
     *    is when the user can start meaningfully browsing.
     *  - [allIconsDoneMs]: time from load start to all icons loaded. This is when the list
     *    is complete.
     *
     * To benchmark a cold start:
     *   adb shell am force-stop io.github.ancientcatz.lime
     *   adb shell am start -n io.github.ancientcatz.lime/.ui.MainActivity
     *   adb logcat -s LimeMainViewModel:I | grep benchmark
     */
    private inner class LoadBenchmark {
        private val startNanos = System.nanoTime()
        private var totalApps: Int = 0
        private var enumerationDoneMs: Long = -1
        private var firstIconMs: Long = -1
        private var usefulPortionMs: Long = -1
        private var allIconsDoneMs: Long = -1

        fun onEnumerationDone(appCount: Int) {
            totalApps = appCount
            enumerationDoneMs = elapsedMs()
            Log.i(TAG, "benchmark: enumeration done — $appCount apps in ${enumerationDoneMs}ms")
        }

        fun onIconReady(readyCount: Int) {
            if (firstIconMs < 0 && readyCount == 1) {
                firstIconMs = elapsedMs()
                Log.i(TAG, "benchmark: first icon visible at ${firstIconMs}ms")
            }
            // "Useful portion" = 20 items (fills a typical phone screen).
            if (usefulPortionMs < 0 && readyCount >= 20) {
                usefulPortionMs = elapsedMs()
                Log.i(TAG, "benchmark: useful portion (20 icons) at ${usefulPortionMs}ms")
            }
        }

        fun onAllIconsDone() {
            allIconsDoneMs = elapsedMs()
            Log.i(TAG, "benchmark: all icons done at ${allIconsDoneMs}ms ($totalApps apps)")
            Log.i(TAG, "benchmark summary: enumeration=${enumerationDoneMs}ms, " +
                    "firstIcon=${firstIconMs}ms, usefulPortion=${usefulPortionMs}ms, " +
                    "allDone=${allIconsDoneMs}ms, totalApps=$totalApps")
        }

        private fun elapsedMs(): Long = (System.nanoTime() - startNanos) / 1_000_000
    }
}
