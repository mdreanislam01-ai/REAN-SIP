package com.reansip.dialer

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Deliberately narrow bridge between the supplied CallerX HTML and Android.
 * The HTML remains the visual/UI source of truth; this class only connects its
 * existing SIP, calling and permission actions to native Android behaviour.
 */
class CallerXBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun uiReady() = activity.onCallerXUiReady()

    @JavascriptInterface
    fun saveSip(json: String) = activity.saveSipFromUi(json)

    @JavascriptInterface
    fun startSipCall(number: String) = activity.startSipCallFromUi(number)

    @JavascriptInterface
    fun openSystemDialer(number: String) = activity.openSystemDialer(number)

    @JavascriptInterface
    fun answerCall() = activity.answerCallFromUi()

    @JavascriptInterface
    fun endCall() = activity.endCallFromUi()

    @JavascriptInterface
    fun setMuted(muted: Boolean) = activity.setMutedFromUi(muted)

    @JavascriptInterface
    fun setSpeaker(on: Boolean) = activity.setSpeakerFromUi(on)

    @JavascriptInterface
    fun toggleHold() = activity.toggleHoldFromUi()

    @JavascriptInterface
    fun toggleRecording() = activity.toggleRecordingFromUi()

    @JavascriptInterface
    fun setAutoRecording(enabled: Boolean) = activity.setAutoRecordingFromUi(enabled)

    @JavascriptInterface
    fun requestCallPermissions() = activity.requestCallPermissionsFromUi()

    @JavascriptInterface
    fun requestDefaultDialer() = activity.requestDefaultDialerFromUi()

    /** Avoid exposing a generic eval or arbitrary native method invoker to the page. */
    @JavascriptInterface
    fun appVersion(): String = "CallerX"
}
