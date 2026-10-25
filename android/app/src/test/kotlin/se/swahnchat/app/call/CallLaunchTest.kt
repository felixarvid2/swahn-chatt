package se.swahnchat.app.call

import se.swahnchat.core.client.call.CallState
import se.swahnchat.core.client.call.EndReason
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the call screen does with its launch intent. [decideLaunch] is the whole decision of the activity (the activity itself
 * is a Hilt activity, which the plain-Application unit tests cannot start): the interesting case is Answer after the process died
 * while the phone rang, when the state is idle and the screen must close instead of showing nothing or answering anything.
 */
class CallLaunchTest {
    private val ringing = CallState.Incoming("c1", "conv", "bob", video = false)
    private val connected = CallState.Connected("c1", "conv", "bob", video = true, outgoing = true, since = 1)

    @Test fun `answer after the process died while ringing finishes the screen`() {
        assertEquals(CallLaunch.Finish, decideLaunch(CallActivity.ACTION_ANSWER, null, false, "c1", CallState.Idle))
    }

    @Test fun `answer for a call that ended, or for another call, finishes`() {
        assertEquals(CallLaunch.Finish, decideLaunch(CallActivity.ACTION_ANSWER, null, false, "c1", CallState.Ended("c1", EndReason.MISSED)))
        assertEquals(CallLaunch.Finish, decideLaunch(CallActivity.ACTION_ANSWER, null, false, "c-old", ringing))
        assertEquals(CallLaunch.Finish, decideLaunch(CallActivity.ACTION_ANSWER, null, false, "c1", connected))
    }

    @Test fun `answer for the ringing call answers it`() {
        assertEquals(CallLaunch.Answer("c1"), decideLaunch(CallActivity.ACTION_ANSWER, null, false, "c1", ringing))
    }

    @Test fun `showing the call with nothing going on finishes, with a call shows it`() {
        assertEquals(CallLaunch.Finish, decideLaunch(CallActivity.ACTION_SHOW, null, false, null, CallState.Idle))
        assertEquals(CallLaunch.Finish, decideLaunch(null, null, false, null, CallState.Idle))
        assertEquals(CallLaunch.Show, decideLaunch(CallActivity.ACTION_SHOW, null, false, null, ringing))
        assertEquals(CallLaunch.Show, decideLaunch(CallActivity.ACTION_SHOW, null, false, null, connected))
        assertEquals(CallLaunch.Show, decideLaunch(CallActivity.ACTION_SHOW, null, false, null, CallState.Ended("c1", EndReason.HANGUP_REMOTE)))
    }

    @Test fun `starting a call places it unless a call is in progress`() {
        assertEquals(CallLaunch.Place("conv", true), decideLaunch(CallActivity.ACTION_START, "conv", true, null, CallState.Idle))
        assertEquals(CallLaunch.Place("conv", false), decideLaunch(CallActivity.ACTION_START, "conv", false, null, CallState.Ended("c0", EndReason.NO_ANSWER)))
        assertEquals(CallLaunch.Show, decideLaunch(CallActivity.ACTION_START, "conv", true, null, connected))
        assertEquals(CallLaunch.Show, decideLaunch(CallActivity.ACTION_START, "conv", true, null, ringing))
        assertEquals(CallLaunch.Finish, decideLaunch(CallActivity.ACTION_START, null, true, null, CallState.Idle))
    }
}
