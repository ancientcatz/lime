package io.github.ancientcatz.lime.ui.main

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import io.github.ancientcatz.lime.model.Orientation

/**
 * One row in the installed-app list.
 *
 * [icon] is nullable to support progressive icon rendering: the view model populates the list
 * with items whose icon is still loading (icon == null), and only publishes items whose icon
 * has finished loading. The adapter never sees items with a null icon, so no placeholder is
 * ever displayed.
 *
 * [sortKey] is the lowercased application label used for alphabetical sorting. It is captured
 * at enumeration time (before icon loading) so the sort order is deterministic and does not
 * depend on which icon finishes loading first. Ties on [sortKey] are broken by [packageName]
 * for full determinism.
 */
data class AppListItem(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    val info: ApplicationInfo,
    val orientation: Orientation = Orientation.UNSPECIFIED,
    val sortKey: String = name.lowercase().trim(),
)
