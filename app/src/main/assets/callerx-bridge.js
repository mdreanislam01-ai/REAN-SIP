/* Native actions for the supplied CallerX UI. No visual elements are created here. */
(function () {
  'use strict';
  const native = function (method) {
    if (!window.CallerXNative || typeof window.CallerXNative[method] !== 'function') return null;
    return window.CallerXNative[method].bind(window.CallerXNative);
  };
  const callNative = function (method) {
    const fn = native(method);
    if (!fn) return null;
    try { return fn.apply(null, Array.prototype.slice.call(arguments, 1)); } catch (_) { return null; }
  };

  window.__callerXIncoming = false;
  window.__callerXSpeaker = false;
  window.__callerXMuted = false;
  window.__callerXEnding = false;

  const oldSaveSip = window.saveSip;
  window.saveSip = function () {
    const username = document.getElementById('sip-user').value.trim();
    const server = document.getElementById('sip-server').value.trim();
    if (!username || !server) {
      alert('Username ও Server দিন');
      return;
    }
    callNative('saveSip', JSON.stringify({
      username: username,
      password: document.getElementById('sip-pass').value,
      server: server,
      port: document.getElementById('sip-port').value.trim(),
      transport: document.getElementById('sip-transport').value
    }));
  };

  const oldSelectCallMethod = window.selectCallMethod;
  window.selectCallMethod = function (method) {
    oldSelectCallMethod(method);
    if (!window.pendingCall) return;
    if (method === 'SIP') {
      callNative('startSipCall', window.pendingCall.number || '');
    } else {
      callNative('openSystemDialer', window.pendingCall.number || '');
    }
  };

  const oldAcceptCall = window.acceptCall;
  window.acceptCall = function () {
    if (window.__callerXIncoming) {
      window.__callerXIncoming = false;
      callNative('answerCall');
    }
    oldAcceptCall();
  };

  const oldEndCall = window.endCall;
  window.endCall = function (ok) {
    if (!window.__callerXEnding) callNative('endCall');
    oldEndCall(ok);
    window.__callerXEnding = false;
  };

  const oldToggleRecording = window.toggleRecording;
  window.toggleRecording = function () {
    callNative('toggleRecording');
    oldToggleRecording();
  };

  function iconPath() {
    return '<svg viewBox="0 0 24 24"><path d="M20.01 15.38c-1.23 0-2.42-.2-3.53-.56a.977.977 0 0 0-1.01.24l-1.57 1.97c-2.83-1.35-5.48-3.9-6.89-6.83l1.95-1.66c.27-.28.35-.67.24-1.02-.37-1.11-.56-2.3-.56-3.53 0-.54-.45-.99-.99-.99H4.19C3.65 3 3 3.24 3 3.99 3 13.28 10.73 21 20.01 21c.71,0 .99-.63 .99-1.18v-3.45c0-.54-.45-.99-.99-.99z"/></svg>';
  }

  window.callerXShowIncoming = function (name, number) {
    window.__callerXIncoming = true;
    window.__callerXEnding = false;
    window.isOngoing = false;
    window.isRecording = false;
    window.pendingCall = { name: name || number, number: number, isSpam: false, letter: (name || '?').charAt(0) };
    document.getElementById('inc-name').textContent = name || number;
    document.getElementById('inc-num').textContent = name ? number : '';
    document.getElementById('inc-label').textContent = 'Incoming SIP call';
    document.getElementById('inc-badge').textContent = 'SIP VOIP HD AUDIO';
    document.getElementById('inc-timer').style.display = 'none';
    document.getElementById('inc-av').innerHTML = iconPath();
    document.getElementById('call-actions').innerHTML =
      '<div class="inc-act">' +
        '<div class="act-btn"><div class="circ rej" onclick="callerXDeclineIncoming()">' + iconPath() + '</div><span>Decline</span></div>' +
        '<div class="act-btn"><div class="circ acc" onclick="callerXAcceptIncoming()">' + iconPath() + '</div><span>Answer</span></div>' +
      '</div>';
    go('s-incoming');
  };

  window.callerXAcceptIncoming = function () {
    window.__callerXIncoming = false;
    callNative('answerCall');
    oldAcceptCall();
  };

  window.callerXDeclineIncoming = function () {
    window.__callerXIncoming = false;
    callNative('endCall');
    go('s-calls');
  };

  window.callerXCallConnected = function () {
    if (!window.isOngoing) oldAcceptCall();
  };

  window.callerXCallEnded = function () {
    if (document.getElementById('s-incoming').classList.contains('active')) {
      window.__callerXEnding = true;
      oldEndCall(true);
    }
  };

  window.callerXSetSipStatus = function (state) {
    setSipStatus(state === 'ok');
  };

  window.callerXHydrateSip = function (account, isOnline) {
    if (account) {
      document.getElementById('sip-user').value = account.username || '';
      document.getElementById('sip-server').value = account.server || '';
      document.getElementById('sip-port').value = account.port || '5060';
      document.getElementById('sip-transport').value = account.transport || 'TLS';
    }
    setSipStatus(!!isOnline);
  };

  window.callerXOnSipSaved = function () {
    setSipStatus(true);
    alert('SIP Account Registered • Online');
    go('s-settings');
  };

  window.callerXError = function (message) {
    alert(message || 'Action could not be completed');
  };

  // Existing in-call controls keep their supplied appearance and now use the
  // native SIP engine in addition to their UI state.
  document.addEventListener('click', function (event) {
    const action = event.target.closest ? event.target.closest('.act-btn') : null;
    if (!action) return;
    const text = action.textContent.replace(/\s+/g, ' ').trim();
    if (text === 'Mute') {
      window.__callerXMuted = !window.__callerXMuted;
      callNative('setMuted', window.__callerXMuted);
    } else if (text === 'Speaker') {
      window.__callerXSpeaker = !window.__callerXSpeaker;
      callNative('setSpeaker', window.__callerXSpeaker);
    } else if (text === 'Hold') {
      callNative('toggleHold');
    }
  }, true);

  const defaultDialer = document.getElementById('default-dialer');
  if (defaultDialer) {
    defaultDialer.onclick = function (event) {
      event.stopPropagation();
      callNative('requestDefaultDialer');
    };
  }

  // The logo is the asset supplied by the user. It is applied without adding
  // another visual component to their supplied layout.
  document.querySelectorAll('.av-sm').forEach(function (avatar) {
    avatar.textContent = '';
    avatar.classList.add('callerx-logo-avatar');
  });

  callNative('uiReady');
}());
