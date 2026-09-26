package com.surveyme

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.surveyme.data.PoiManager
import com.surveyme.data.model.Poi
import com.surveyme.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Item 17: the ping notification's deep link. The intent is built exactly as
 * `LocationTrackingService.showPoiNotification` builds it; launching MainActivity with it must
 * land on the Notes tab with the details sheet open on the item.
 *
 * Runs on a device or emulator: `.\gradlew.bat connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkTest {

    private companion object {
        const val SOURCE = "androidTest"
    }

    // HomeFragment asks for POST_NOTIFICATIONS on API 33+; the system dialog would pause the
    // activity and stall Espresso. On older APIs there is nothing to grant.
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val poi = Poi(
        id = "deeplink_${System.currentTimeMillis()}",
        name = "Deep link test item",
        description = "Bench gone, path blocked",
        latitude = 43.7,
        longitude = -79.4,
        source = SOURCE
    )
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun storePoi() {
        runBlocking { PoiManager.getRepository(context).insertPoi(poi) }
    }

    @After
    fun cleanUp() {
        scenario?.close()
        runBlocking { PoiManager.getRepository(context).deleteAllFromSource(SOURCE) }
    }

    /** The intent the ping notification fires; keep in step with `showPoiNotification`. */
    private fun pingIntent(): Intent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("surveyme://poi/${poi.id}")
        putExtra(MainActivity.EXTRA_OPEN_POI_ID, poi.id)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    @Test
    fun pingIntentOpensTheNotesTabWithTheItemsDetails() {
        scenario = ActivityScenario.launch(pingIntent())

        // The deep link reads the POI from Room in a lifecycle coroutine that Espresso does not
        // idle on, so the assertions are retried for a few seconds.
        eventually {
            onView(withId(R.id.bottomNavigation)).check { view: View, noView ->
                if (noView != null) throw noView
                assertEquals(R.id.notesFragment, (view as BottomNavigationView).selectedItemId)
            }
            onView(withId(R.id.textTitle)).inRoot(isDialog()).check(matches(withText(poi.name)))
        }
    }

    private fun eventually(timeoutMillis: Long = 10_000, block: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                block()
                return
            } catch (t: Throwable) {
                last = t
                Thread.sleep(200)
            }
        }
        throw AssertionError("still not there after ${timeoutMillis / 1000}s", last)
    }
}
