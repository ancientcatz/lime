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
import kotlinx.coroutines.cancel
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
 *  load their labels, filter by the show-system-apps preference, sort alphabetically by
 *  label (locale-aware, case-insensitive, tie-break by packageName for determinism),
 *  and capture the configured orientation. The list is published as soon as Phase 1
 *  completes — initially empty (no icons yet), then icons trickle in during Phase 2.
 *
 *  Phase 2 (icon loading): icons are loaded concurrently on a bounded dispatcher
 *  ([ICON_CONCURRENCY] = 8). Icons are requested in alphabetical order: the pending
 *  list is already sorted, so iterating it in order naturally prioritizes the start of
 *  the alphabet. As each icon finishes, the corresponding item is moved from the
 *  pending list to the ready list at its correct alphabetical position, and the visible
 *  list is republished. Items with a failed icon load are skipped (logged) and never
 *  appear, so a single failure does not block the rest of the list.
 *
 *  The ready list is always sorted; inserting a newly-ready item uses binary search to
 *  find the correct position, so each incremental update is O(log n) for the insert
 *  plus O(n) for the list copy that LiveData publishes. The adapter's DiffUtil (with
 *  stable packageName identifiers) minimizes view recreation on each update.
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
    }

    private val sharedConfig = SharedConfig.Provider(app)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    private val keyshowSystemApps = app.getString(R.string.key_setting_show_system_apps)
    private val showSystemApps get() = prefs.getBoolean(keyshowSystemApps, false)

    /**
     * The full list of enumerated apps with their labels and orientations, but WITHOUT icons
     * (icon == null). Sorted alphabetically by [AppListItem.sortKey]. Populated during Phase 1
     * and used as the source of truth for icon loading and for the current orientation state.
     * Access is confined to the loading coroutines (off the main thread).
     */
    private val appList = mutableListOf<AppListItem>()

    /**
     * Indices into [appList] for items whose icon has NOT yet been loaded. Sorted in the same
     * alphabetical order as [appList] (so iterating this list loads icons in alphabetical
     * priority order). Mutated only on the background thread.
     */
    private val pendingIconIndices = ArrayDeque<Int>()

    /**
     * The currently visible list: items whose icons have loaded successfully, in alphabetical
     * order. Published to [appListLiveData] on every incremental update. Confined to the
     * background thread for mutation; copies are published to LiveData.
     */
    private val readyList = mutableListOf<AppListItem>()

    private val appListLiveDataInternal = MutableLiveData<List<AppListItem>>()
    private val isLoadingLiveDataInternal = MutableLiveData<Boolean>()
    private var hasLoadedAppList = false

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
     * Comparator for [readyList]: locale-aware, case-insensitive comparison on [AppListItem.sortKey]
     * (via [Collator] PRIMARY strength), with [AppListItem.packageName] as a deterministic
     * tie-break. Used both for the Phase 1 sort and for the binary-search inserts in Phase 2.
     */
    private val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    private val readyListComparator: Comparator<AppListItem> = Comparator { a, b ->
        val byKey = collator.compare(a.sortKey, b.sortKey)
        if (byKey != 0) byKey else collator.compare(a.packageName, b.packageName)
    }

    /**
     * Active load benchmark, or null when no load is in progress. Captures timing metrics
     * (time to enumeration done, time to first icon, time to a useful portion of the list,
     * time to all icons done) and logs them at the end of the load. See [LoadBenchmark].
     */
    @Volatile
    private var currentBenchmark: LoadBenchmark? = null

    val appListLiveData: LiveData<List<AppListItem>> = appListLiveDataInternal
    val isLoadingLiveData: LiveData<Boolean> = isLoadingLiveDataInternal

    @Volatile
    var queryText = ""
        set(value) {
            field = value
            viewModelScope.launch { publishFiltered() }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        loadJob?.cancel()
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
        if (isLoadingLiveDataInternal.value == true || (hasLoadedAppList && !forceReload)) {
            return
        }
        loadJob?.cancel()
        isLoadingLiveDataInternal.value = true
        val benchmark = LoadBenchmark().also { currentBenchmark = it }
        loadJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                // ── Phase 1: enumerate + sort ──────────────────────────────────────────
                appList.clear()
                readyList.clear()
                pendingIconIndices.clear()
                publishFiltered()

                val packageManager = getApplication<Application>().packageManager
                packageManager.getInstalledApplications(0)
                    .filter {
                        showSystemApps || (it.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
                                it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0)
                    }
                    .mapTo(appList) { appInfo ->
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
                    .sortWith(readyListComparator)

                // Populate the pending queue in alphabetical order (appList is already sorted).
                for (i in appList.indices) {
                    pendingIconIndices.addLast(i)
                }

                benchmark.onEnumerationDone(appList.size)

                // Phase 1 complete: hide the loading spinner. The list is still empty (no
                // icons), but the RecyclerView becomes visible so the user sees an empty
                // list that fills in progressively.
                isLoadingLiveDataInternal.postValue(false)
                hasLoadedAppList = true

                // ── Phase 2: load icons concurrently in alphabetical priority order ─────
                loadIcons()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load app list", t)
                isLoadingLiveDataInternal.postValue(false)
            }
        }
    }

    /**
     * Loads icons for all pending apps. Icons are requested in alphabetical order (the
     * pending queue preserves [appList]'s sort order). Each icon load runs on [iconDispatcher]
     * (bounded to [ICON_CONCURRENCY] concurrent loads). When an icon finishes, the item is
     * moved from the pending queue to [readyList] at its correct alphabetical position and
     * the visible list is republished.
     *
     * Failed icon loads are logged and the item is silently dropped from the ready list —
     * it never appears, but does not block other items.
     */
    private suspend fun loadIcons() {
        val packageManager = getApplication<Application>().packageManager
        // Launch icon loads in alphabetical order. The bounded dispatcher naturally limits
        // concurrency; launching them in order means the first ICON_CONCURRENCY items (top
        // of the alphabet) start loading first.
        val iconJobs = mutableListOf<Job>()
        while (pendingIconIndices.isNotEmpty()) {
            val index = pendingIconIndices.removeFirst()
            val item = appList[index]
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
        currentBenchmark?.onAllIconsDone()
    }

    /**
     * Called (on the iconDispatcher thread) when an icon finishes loading. Inserts the item
     * into [readyList] at its correct alphabetical position and republishes the visible list.
     *
     * The insert position is found via binary search on [AppListItem.sortKey] (with
     * packageName tie-break), so each call is O(log n) for the search plus O(n) for the list
     * shift. Since [readyList] is confined to the background thread and icon loads are
     * serialized through the bounded dispatcher, no additional synchronization is needed.
     */
    private suspend fun onIconReady(index: Int, icon: Drawable) {
        val item = appList[index].copy(icon = icon)
        // Binary search for the insertion point. readyList is sorted by (sortKey, packageName).
        val insertPos = readyList.binarySearch(item, readyListComparator).let { if (it < 0) -(it + 1) else it }
        readyList.add(insertPos, item)
        currentBenchmark?.onIconReady(readyList.size)
        publishFiltered()
    }

    /**
     * Publishes the current [readyList] (filtered by [queryText]) to [appListLiveData].
     * Must be called on a background thread (it reads [readyList] and [queryText] without
     * synchronization). The LiveData.postValue call is thread-safe and delivers the update
     * to the main thread.
     */
    private suspend fun publishFiltered() = withContext(Dispatchers.Default) {
        val snapshot = if (queryText.isEmpty()) {
            readyList.toList()
        } else {
            readyList.filter {
                it.name.contains(queryText, true) ||
                        it.packageName.contains(queryText, true)
            }
        }
        appListLiveDataInternal.postValue(snapshot)
    }

    /**
     * Updates the configured orientation for [packageName] and persists it. Also updates the
     * matching item in [readyList] (if its icon has loaded) so the change is immediately
     * visible. Runs on a background thread.
     */
    fun updateConfigApp(packageName: String, orientation: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            if (orientation != Orientation.UNSPECIFIED.ordinal) {
                sharedConfig.addConfigApp(packageName, orientation)
            } else {
                sharedConfig.removeConfigApp(packageName)
            }
            // Update the canonical entry in appList (source of truth for orientation).
            val listIndex = appList.indexOfFirst { it.packageName == packageName }
            if (listIndex >= 0) {
                appList[listIndex] = appList[listIndex].copy(
                    orientation = Orientation.values()[orientation]
                )
            }
            // Update the visible entry in readyList if present.
            val readyIndex = readyList.indexOfFirst { it.packageName == packageName }
            if (readyIndex >= 0) {
                readyList[readyIndex] = readyList[readyIndex].copy(
                    orientation = Orientation.values()[orientation]
                )
            }
            publishFiltered()
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
        private val totalApps: Int get() = appList.size
        private var enumerationDoneMs: Long = -1
        private var firstIconMs: Long = -1
        private var usefulPortionMs: Long = -1
        private var allIconsDoneMs: Long = -1

        fun onEnumerationDone(appCount: Int) {
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
