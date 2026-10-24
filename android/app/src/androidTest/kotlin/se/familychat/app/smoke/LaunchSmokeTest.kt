package se.familychat.app.smoke

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import se.familychat.app.MainActivity
import kotlin.test.assertTrue

/**
 * The app starts: application class, Hilt graph, theme and first composition. Runs on the R8 build too, where it is the only
 * check of the manifest components and the Hilt/Compose rules (the Compose assertions are in the `ui` package, debug only).
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @Test fun mainActivityReachesResumedAndShowsAWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.isAttachedToWindow)
                assertTrue(activity.window.decorView.width > 0)
            }
        }
    }
}
