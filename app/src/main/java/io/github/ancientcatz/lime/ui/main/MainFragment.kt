package io.github.ancientcatz.lime.ui.main

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.core.widget.ContentLoadingProgressBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import io.github.libxposed.service.XposedService
import io.github.ancientcatz.lime.App
import io.github.ancientcatz.lime.R
import io.github.ancientcatz.lime.ui.MainActivity
import io.github.ancientcatz.lime.ui.setting.SettingFragment

class MainFragment : Fragment(R.layout.fragment_main), App.ServiceStateListener,
    AppListAdapter.EventListener {

    companion object {
        val TAG = MainFragment::class.simpleName!!
        private const val MIN_ITEM_CLICK_DURATION = 500L
    }

    private val mainViewModel by viewModels<MainViewModel>()
    private var lastItemClickTime = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Modern menu API: register a MenuProvider with the activity's MenuHost instead of
        // overriding the deprecated Fragment.onCreateOptionsMenu / Fragment.onOptionsItemSelected
        // and calling Fragment.setHasOptionsMenu(true). AppCompatActivity implements MenuHost
        // and routes menu inflation and item selection through our provider while this
        // fragment's view is at least RESUMED. The provider is automatically removed when
        // viewLifecycleOwner reaches DESTROYED, so there is no leak and no need to override
        // onDestroyOptionsMenu.
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        val appListView = view.findViewById<RecordScrollRecyclerView>(R.id.app_list)
        val progressBarView = view.findViewById<ContentLoadingProgressBar>(R.id.progress_bar)
        val appListAdapter = AppListAdapter()
        appListAdapter.eventListener = this
        appListView.apply {
            adapter = appListAdapter
            itemAnimator = null
        }
        mainViewModel.appListLiveData.observe(viewLifecycleOwner) {
            // submitList runs DiffUtil on a background thread and dispatches the minimal
            // set of insertions/deletions/moves to the adapter. Stable IDs (packageName)
            // ensure existing item views are reused rather than rebound when new items
            // trickle in during progressive icon loading.
            //
            // NOTE: Do NOT call scrollToLastPosition() here. During progressive icon loading
            // the list updates frequently (every ~120ms), and restoring the scroll position
            // on each update would fight with the user's active scrolling, causing glitches.
            // The RecyclerView preserves scroll position across adapter updates automatically
            // (thanks to stable IDs). The scroll position is restored once, the first time
            // the list becomes visible (see the isLoading observer below).
            appListAdapter.submitList(it)
        }
        mainViewModel.isLoadingLiveData.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                // Loading: show the spinner, hide the list.
                appListView.isVisible = false
                progressBarView.show()
            } else {
                // Loading done (first icon has rendered). Show the list immediately —
                // icons will continue trickling in progressively. Restore the saved
                // scroll position once, when the list first becomes visible.
                progressBarView.hide()
                appListView.isVisible = true
                if (!hasRestoredScroll) {
                    hasRestoredScroll = true
                    appListView.scrollToLastPosition()
                }
            }
        }
    }

    /** Tracks whether the saved scroll position has been restored yet. */
    private var hasRestoredScroll = false

    override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this, notifyImmediately = true)
        refreshTitle()
        mainViewModel.loadAppList()
    }

    override fun onStop() {
        App.removeServiceStateListener(this)
        super.onStop()
    }

    /**
     * Modern module-active detection: the framework delivers an [XposedService] binder to the
     * module's settings process when the module is enabled in NPatch (or LSPosed). If the binder
     * is held, the module is active. This replaces the legacy Java-level hook on
     * `MainFragment.isModuleActive` which never actually ran in production because the modern
     * module is never injected into its own settings process (scope is configurable via
     * NPatch).
     */
    private fun isModuleActive(): Boolean = App.mService != null

    private fun refreshTitle() {
        activity?.let {
            it.title = "${getString(R.string.app_name)}${
                if (!isModuleActive()) " (${getString(R.string.label_module_not_active)})" else ""
            }"
        }
    }

    override fun onServiceStateChanged(service: XposedService?) {
        // Refresh the title (which depends on App.mService) whenever the service state changes.
        refreshTitle()
    }

    /**
     * MenuProvider that inflates the main menu (search / refresh / settings / exit) and handles
     * item selection. Replaces the deprecated Fragment.onCreateOptionsMenu and
     * Fragment.onOptionsItemSelected overrides. Registered with the activity's MenuHost in
     * [on onViewCreated] with the RESUMED lifecycle state, so the menu is only visible and
     * receives item-selection callbacks while this fragment is the active screen.
     */
    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.main, menu)
            val searchItem = menu.findItem(R.id.search)
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextChange(newText: String): Boolean {
                    mainViewModel.queryText = newText
                    return true
                }

                override fun onQueryTextSubmit(query: String): Boolean {
                    return false
                }
            })
            // Initialize the "Launchable apps only" checkbox from the view model's state.
            menu.findItem(R.id.launchable_only).isChecked = mainViewModel.launchableOnly
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.refresh -> {
                    hasRestoredScroll = false
                    mainViewModel.loadAppList(true)
                    true
                }
                R.id.launchable_only -> {
                    // Toggle the checkbox and the view model's filter, then reload.
                    item.isChecked = !item.isChecked
                    mainViewModel.launchableOnly = item.isChecked
                    hasRestoredScroll = false
                    mainViewModel.loadAppList(true)
                    true
                }
                R.id.setting -> {
                    MainActivity.gotoFragment(
                        parentFragmentManager,
                        SettingFragment(),
                        SettingFragment.TAG
                    )
                    true
                }
                R.id.exit -> {
                    activity?.finishAffinity()
                    true
                }
                else -> false
            }
        }
    }

    override fun onAppListItemClick(appListItem: AppListItem) {
        val activity = activity
        val currentTime = System.currentTimeMillis()
        if (activity != null && currentTime - lastItemClickTime > MIN_ITEM_CLICK_DURATION) {
            AlertDialog.Builder(activity)
                .setSingleChoiceItems(
                    R.array.orientation_options,
                    appListItem.orientation.ordinal
                ) { dialog, which ->
                    mainViewModel.updateConfigApp(appListItem.packageName, which)
                    dialog.dismiss()
                }.show()
            lastItemClickTime = currentTime
        }
    }
}
