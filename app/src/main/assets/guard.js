// Runs inside every page: stops scripted pop-ups, hides common ad slots,
// and removes invisible full-screen "tap anywhere" ad overlays.
(function () {
  if (window.__cleanGuard) return;
  window.__cleanGuard = true;

  // Pop-ups are handled by the app itself: ones you didn't tap are blocked,
  // ones you tapped (sign-in, payment windows) open in a closable window.

  // Hide common ad containers.
  var css = [
    'ins.adsbygoogle', '[id^="google_ads"]', '[id^="div-gpt-ad"]', '.adsbox',
    '[class*="popunder"]', '[id*="popunder"]', 'iframe[src*="doubleclick"]',
    'iframe[src*="googlesyndication"]', '[class^="ad-"]', '[class*=" ad-banner"]',
    '[id^="ad_"]', '.ad-container', '.ad-slot', '.sponsored-ads'
  ].join(',') + '{display:none!important;visibility:hidden!important;}';

  function addStyle() {
    if (document.getElementById('__cleanStyle')) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    var st = document.createElement('style');
    st.id = '__cleanStyle';
    st.textContent = css;
    root.appendChild(st);
  }

  function isOverlay(el) {
    var s = getComputedStyle(el);
    if (s.position !== 'fixed' && s.position !== 'absolute') return false;
    var r = el.getBoundingClientRect();
    var covers = r.width >= innerWidth * 0.8 && r.height >= innerHeight * 0.8;
    if (!covers) return false;
    var invisible = parseFloat(s.opacity) < 0.1 || s.backgroundColor === 'rgba(0, 0, 0, 0)';
    var onTop = parseInt(s.zIndex, 10) >= 1000;
    return invisible && onTop && !el.querySelector('video, input, form, iframe[src*="player"]');
  }

  function sweep() {
    addStyle();
    if (!document.body) return;
    var els = document.body.querySelectorAll('div, a, span');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.children.length <= 2 && isOverlay(el)) el.remove();
    }
  }

  addStyle();
  var pending = false;
  new MutationObserver(function () {
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; sweep(); }, 300);
  }).observe(document.documentElement || document, { childList: true, subtree: true });
  document.addEventListener('DOMContentLoaded', sweep);
  setInterval(sweep, 2000);
})();
