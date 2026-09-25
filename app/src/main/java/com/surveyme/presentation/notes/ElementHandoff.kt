package com.surveyme.presentation.notes

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.surveyme.core.OsmElementRef

/**
 * The ways an OSM element can be handed to something that edits map data (item 10). This app
 * never edits elements itself; it opens them elsewhere.
 *
 * What is verified (against the editors' manifests and source, 2026-09-25) and what is not:
 * - osm.org and the web editor are plain https links; any browser (or Vespucci, which also
 *   claims osm.org element URLs) answers them.
 * - Vespucci (`de.blau.android`) registers `http://127.0.0.1:8111/...` in RemoteControlUrlActivity
 *   and handles `load_and_zoom` with `left/right/top/bottom` and `select=node123`. The intent is
 *   sent to that package explicitly so no local web server or JOSM is involved. Not yet tried
 *   on a device.
 * - StreetComplete (`de.westnordost.streetcomplete`) registers only the `geo:` scheme and its own
 *   short-link domain; there is no element-level intent, so it gets a `geo:` at the element.
 * - "Open map here" is the same `geo:` URI through the system chooser, for any map app.
 *
 * Package checks need the `<queries>` block in the manifest on Android 11+.
 */
object ElementHandoff {

    const val VESPUCCI = "de.blau.android"
    const val STREETCOMPLETE = "de.westnordost.streetcomplete"

    /** One chip in the details sheet. [intent] is what the tap starts. */
    data class Option(val label: String, val intent: Intent)

    fun options(context: Context, element: OsmElementRef, lat: Double, lon: Double): List<Option> {
        val pm = context.packageManager
        val result = mutableListOf<Option>()
        result += Option("osm.org", view(element.browseUrl))
        result += Option("Edit online", view(element.editUrl))
        if (isInstalled(pm, VESPUCCI)) {
            result += Option("Vespucci", view(element.josmRemoteUrl(lat, lon)).setPackage(VESPUCCI))
        }
        if (isInstalled(pm, STREETCOMPLETE)) {
            result += Option("StreetComplete", view(OsmElementRef.geoUri(lat, lon)).setPackage(STREETCOMPLETE))
        }
        val geo = view(OsmElementRef.geoUri(lat, lon))
        if (resolves(pm, geo)) result += Option("Open map here", geo)
        return result
    }

    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        true
    }.getOrDefault(false)

    private fun resolves(pm: PackageManager, intent: Intent): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0) != null
        }
    }.getOrDefault(false)
}
