package com.babel.app.capability

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.babel.app.capability.AndroidCapabilityChecker.Companion.isAccessibilityServiceRunning
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The decision that once made the app contradict itself.
 *
 * Reading only the enabled-services list reported the service as granted on a
 * device where nothing was bound, so a permission card said yes on the same
 * screen where manga mode said it could not start. Both settings have to agree.
 *
 * On the device rather than on the JVM: `ComponentName` and `TextUtils` are
 * real Android types, and the comparison this makes is exactly the part that
 * would be lost to a stub returning defaults.
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.class=
 *     com.babel.app.capability.AccessibilityStatusTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityStatusTest {

    private val babel = "com.babel/com.babel.platform.accessibility.BabelAccessibilityService"
    private val other = "com.other/com.other.SomeService"

    /**
     * The state this was written for: a real device, after the system failed to
     * bind the service at boot. The name stays in the list; the master switch
     * sits at zero; nothing is running.
     */
    @Test
    fun listedButAccessibilityTurnedOffIsNotGranted() {
        assertFalse(
            isAccessibilityServiceRunning(
                masterSwitch = 0,
                enabledServices = babel,
                expected = babel,
            ),
        )
    }

    @Test
    fun listedAndAccessibilityOnIsGranted() {
        assertTrue(
            isAccessibilityServiceRunning(
                masterSwitch = 1,
                enabledServices = babel,
                expected = babel,
            ),
        )
    }

    /** Somebody else's service running is not ours running. */
    @Test
    fun accessibilityOnForAnotherServiceIsNotGranted() {
        assertFalse(
            isAccessibilityServiceRunning(
                masterSwitch = 1,
                enabledServices = other,
                expected = babel,
            ),
        )
    }

    @Test
    fun oursAmongOthersIsGranted() {
        assertTrue(
            isAccessibilityServiceRunning(
                masterSwitch = 1,
                enabledServices = "$other:$babel",
                expected = babel,
            ),
        )
    }

    /**
     * The same component, written the short way. Settings stores what was
     * declared, and a relative class name is legal — which is why this compares
     * components rather than strings.
     */
    @Test
    fun theShortFormNamesTheSameService() {
        assertTrue(
            isAccessibilityServiceRunning(
                masterSwitch = 1,
                enabledServices = "com.babel/.platform.accessibility.BabelAccessibilityService",
                expected = babel,
            ),
        )
    }

    @Test
    fun nothingEnabledIsNotGranted() {
        assertFalse(
            isAccessibilityServiceRunning(masterSwitch = 1, enabledServices = null, expected = babel),
        )
        assertFalse(
            isAccessibilityServiceRunning(masterSwitch = 1, enabledServices = "", expected = babel),
        )
    }
}
