/*
 * Injected into every page by the WebView.
 * - Detects "listing" pages (several comic cards) vs "comic" pages (single post / has ?dl= link)
 * - Adds a tick-box overlay on every comic card (multi-select)
 * - Reports selection + page type to Android through window.CG
 *
 * If the site layout differs, tweak CARD / titleLink() below – that is the only site-specific part.
 */
(function () {
  if (window.__cgInstalled) return;
  window.__cgInstalled = true;

  var CARD = 'article, .post, .type-post, .item-post';
  var NOT_COMIC_PATH = /\/(category|tag|author|page|feed)\//i;
  var pre = {};      // urls pre-selected on earlier pages
  var cards = [];
  var observing = false;
  var timer = null;

  function titleLink(card) {
    var a = card.querySelector('h1 a[href], h2 a[href], h3 a[href], h4 a[href], .entry-title a[href], .post-title a[href]');
    if (!a) { var im = card.querySelector('a[href] img'); a = im ? im.closest('a') : null; }
    return a;
  }
  function valid(a) {
    try {
      var u = new URL(a.href);
      return u.origin === location.origin && u.pathname.length > 1 &&
             !NOT_COMIC_PATH.test(u.pathname) && u.pathname !== location.pathname;
    } catch (e) { return false; }
  }
  function titleOf(card, a) {
    var img = card.querySelector('img');
    return (a.textContent || a.title || (img && img.alt) || '').replace(/\s+/g, ' ').trim();
  }
  function isComicPage() {
    return /\bsingle\b/.test(document.body.className) || !!document.querySelector('a[href*="dl="]');
  }
  function pageType() {
    if (isComicPage()) return 'comic';
    return cards.length >= 2 ? 'listing' : 'other';
  }
  function report() {
    try {
      CG.onPage(JSON.stringify({ type: pageType(), count: cards.length, url: location.href, title: document.title }));
    } catch (e) {}
  }
  function paint(card, on) {
    var b = card.__cgBox;
    b.textContent = on ? '\u2713' : '';
    b.style.background = on ? '#2e7d32' : 'rgba(0,0,0,.55)';
    card.style.outline = on ? '3px solid #2e7d32' : '';
  }
  function setSel(card, on, notify) {
    paint(card, on);
    if (notify) { try { CG.onToggle(card.__cgUrl, card.__cgTitle, on); } catch (e) {} }
  }

  function decorate() {
    cards = [];
    if (!isComicPage()) {
      document.querySelectorAll(CARD).forEach(function (card) {
        if (card.parentElement && card.parentElement.closest(CARD)) return; // top-level cards only
        var a = titleLink(card);
        if (!a || !valid(a)) return;
        cards.push(card);
        if (card.__cg) return;
        card.__cg = true;
        card.__cgUrl = a.href;
        card.__cgTitle = titleOf(card, a);
        if (getComputedStyle(card).position === 'static') card.style.position = 'relative';

        var box = document.createElement('div');
        box.style.cssText = 'position:absolute;top:8px;right:8px;width:34px;height:34px;border-radius:50%;' +
          'border:2px solid #fff;color:#fff;font:bold 20px/30px sans-serif;text-align:center;' +
          'z-index:99999;cursor:pointer;background:rgba(0,0,0,.55);';
        box.addEventListener('click', function (e) {
          e.preventDefault(); e.stopPropagation();
          setSel(card, !card.__cgOn, true);
          card.__cgOn = !card.__cgOn;
        }, true);
        card.appendChild(box);

        // Download button in the top-left corner of the comic
        var dl = document.createElement('div');
        dl.textContent = '\u2B07';
        dl.style.cssText = 'position:absolute;top:8px;left:8px;width:34px;height:34px;border-radius:50%;' +
          'border:2px solid #fff;color:#fff;font:bold 18px/30px sans-serif;text-align:center;' +
          'z-index:99999;cursor:pointer;background:rgba(25,118,210,.92);';
        dl.addEventListener('click', function (e) {
          e.preventDefault(); e.stopPropagation();
          dl.style.background = '#2e7d32';
          try { CG.onDownload(card.__cgUrl, card.__cgTitle); } catch (err) {}
        }, true);
        card.appendChild(dl);
        card.__cgBox = box;
        card.__cgOn = false;
        if (pre[card.__cgUrl]) { card.__cgOn = true; setSel(card, true, false); }
      });
    }
    report();
  }

  window.__cgInit = function (list) {
    (list || []).forEach(function (u) { pre[u] = true; });
    decorate();
    if (!observing) {
      observing = true;
      // handles lazy-loaded / infinite-scroll cards
      new MutationObserver(function () {
        clearTimeout(timer); timer = setTimeout(decorate, 400);
      }).observe(document.body, { childList: true, subtree: true });
    }
  };

  window.__cgSelectAll = function (on) {
    cards.forEach(function (c) {
      if (!!c.__cgOn !== on) { c.__cgOn = on; setSel(c, on, true); }
    });
  };
})();
