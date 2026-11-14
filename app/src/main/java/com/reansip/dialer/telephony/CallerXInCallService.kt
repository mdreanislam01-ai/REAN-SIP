package com.reansip.dialer.telephony

import android.telecom.InCallService

/**
 * Declared solely so Android can offer CallerX for the already-present
 * "Set as Default Dialer" option in the supplied CallerX UI. SIP calls remain
 * handled by the Linphone service; Android owns cellular-call routing.
 */
class CallerXInCallService : InCallService()
