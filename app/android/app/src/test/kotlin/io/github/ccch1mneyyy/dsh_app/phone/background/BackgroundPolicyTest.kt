package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The version split: what each Android level may attempt, and which flags it may ask for. */
class BackgroundPolicyTest {
    private val base = listOf("PUBLIC", "OWN_CONTENT_ONLY", "SUPPORTS_TOUCH", "DESTROY_CONTENT_ON_REMOVAL")
    private val trusted = listOf("TRUSTED", "OWN_FOCUS", "STEAL_TOP_FOCUS_DISABLED")

    @Test fun android14IsTheSupportedFloor() {
        assertTrue(BackgroundPolicy.supported(34))
        assertTrue(BackgroundPolicy.supported(35))
        assertFalse(BackgroundPolicy.supported(33))
        assertFalse(BackgroundPolicy.supported(32))
        // The advertised floor and the full cap are one number: nothing below 14 claims support.
        assertEquals(34, BackgroundPolicy.FULL_SDK)
        assertEquals(33, BackgroundPolicy.EXPERIMENTAL_SDK)
    }

    @Test fun android13ReportsEveryPartSeparatelyAndNeverAsSupported() {
        val profile = BackgroundPolicy.profile(33)
        assertEquals(33, profile.sdk)
        assertEquals(BackgroundPolicy.Support.EXPERIMENTAL, profile.display)
        assertEquals(BackgroundPolicy.Support.EXPERIMENTAL, profile.launch)
        assertEquals(BackgroundPolicy.Support.EXPERIMENTAL, profile.inject)
        assertEquals(BackgroundPolicy.Support.EXPERIMENTAL, profile.tree)
        assertEquals(BackgroundPolicy.Support.FULL, profile.ime)
        assertFalse(profile.supported)
        // A probe may run there; the mode stays opt-in until a device says otherwise.
        assertTrue(BackgroundPolicy.canProbe(33))
        assertFalse(BackgroundPolicy.canProbe(32))
    }

    @Test fun android14HasEveryPartFully() {
        val profile = BackgroundPolicy.profile(34)
        assertEquals(BackgroundPolicy.Support.FULL, profile.display)
        assertEquals(BackgroundPolicy.Support.FULL, profile.tree)
        assertTrue(profile.supported)
    }

    @Test fun belowTheExperimentThereIsNothingToAttempt() {
        val profile = BackgroundPolicy.profile(32)
        assertEquals(BackgroundPolicy.Support.NONE, profile.display)
        assertEquals(BackgroundPolicy.Support.NONE, profile.ime)
        assertFalse(profile.supported)
        assertFalse(BackgroundPolicy.canProbe(32))
    }

    @Test fun flagsFor13NeverNameAFieldThatOnlyExistsFrom14() {
        assertEquals(base, BackgroundPolicy.flagNames(33))
        assertEquals(base, BackgroundPolicy.flagNames(29))
    }

    @Test fun flagsFor14AddTrustedOwnFocusAndNoFocusTheft() {
        assertEquals(base + trusted, BackgroundPolicy.flagNames(34))
        assertEquals(base + trusted, BackgroundPolicy.flagNames(35))
    }

    @Test fun theGateCanOnlyJudgeInputWhereTheModeIsFullySupported() {
        assertTrue(BackgroundPolicy.probeCanJudge(34))
        // 13: a window usually cannot attach, so a failed probe there proves nothing about input.
        assertFalse(BackgroundPolicy.probeCanJudge(33))
        assertFalse(BackgroundPolicy.probeCanJudge(32))
    }

    @Test fun below14TheKeyboardIsKeptOnTheBackgroundDisplay() {
        // HIDE on a display without its own focus sends the app to the default display for its IME.
        assertEquals(BackgroundPolicy.IME_POLICY_LOCAL, BackgroundPolicy.imePolicy(33))
        assertEquals(BackgroundPolicy.IME_POLICY_LOCAL, BackgroundPolicy.imePolicy(32))
        assertEquals(BackgroundPolicy.IME_POLICY_HIDE, BackgroundPolicy.imePolicy(34))
        assertEquals(0, BackgroundPolicy.IME_POLICY_LOCAL)
        assertEquals(2, BackgroundPolicy.IME_POLICY_HIDE)
    }
}
