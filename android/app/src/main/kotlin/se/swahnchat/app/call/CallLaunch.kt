package se.swahnchat.app.call

import se.swahnchat.core.client.call.CallState

/** What [CallActivity] does with the intent it was started with. */
sealed interface CallLaunch {
    /** Nothing to show (the call is gone, or the process restarted while it rang): the activity just closes. */
    data object Finish : CallLaunch

    /** Show the call that is in progress. */
    data object Show : CallLaunch

    /** Place a call (after the permissions). */
    data class Place(val conversationId: String, val video: Boolean) : CallLaunch

    /** Answer the ringing call [callId] (after the permissions). */
    data class Answer(val callId: String) : CallLaunch
}

/**
 * Decides what a launch of the call screen means, given the call state right now. The activity is also started by a tap on
 * a stale notification or by Answer after the process died while the phone rang; then the state is idle and there is nothing
 * to answer, so it closes instead of showing an empty screen or, worse, answering something else.
 */
fun decideLaunch(action: String?, conversationId: String?, video: Boolean, callId: String?, state: CallState): CallLaunch = when (action) {
    CallActivity.ACTION_START ->
        if (conversationId == null) CallLaunch.Finish
        else if (state is CallState.InCall) CallLaunch.Show // one call at a time
        else CallLaunch.Place(conversationId, video)
    CallActivity.ACTION_ANSWER ->
        if (state is CallState.Incoming && (callId == null || state.callId == callId)) CallLaunch.Answer(state.callId) else CallLaunch.Finish
    else -> if (state is CallState.Idle) CallLaunch.Finish else CallLaunch.Show
}
