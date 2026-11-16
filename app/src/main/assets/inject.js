/*
 * Injected into every page by the WebView.
 *
 * - Detects comic cards (listing pages) with a wide set of selectors, plus a
 *   layout-agnostic fallback (any same-origin link that wraps a thumbnail image),
 *   so the overlays show up even when the site theme changes.
 * - Puts a blue DOWNLOAD button (top-left) and a tick-box (top-right) on every card.
 * - On a single comic page it shows a floating "Download" button instead.
 * - Everything is styled through an injected <style> block with !important, and the
 *   icons are inline SVG, so no site CSS and no emoji font can hide them.
 *
 * Site-specific tweak points: CARD selector list and titleLink().
 */
(function () {
  if (window.__cgInstalled) return;
  window.__cgInstalled = true;

  var CARD = [
    'article', '.post', '.type-post', '.item-post', '.post-item', '.entry-card',
    '.manga-item', '.comic-item', '.page-item-detail', '.listupd .bs', '.listupd .bsx',
    '.bixbox .bs', '.uta', '.bs', '.bsx', '.thumb', '.card', '.item', 'li.item',
    '.entry', '.post-thumbnail', '.c-tabs-item__content', '.manga__item'
  ].join(',');
  var NAV = /^\/(category|tag|author|page|feed|about|contact|privacy|dmca|login|register|search|wp-|feed)(\/|$)/i;
  var pre = {};        // urls pre-selected on earlier pages
  var cards = [];
  var seen = {};       // url -> card, for the fallback pass
  var observing = false;
  var timer = null;

  /* ---------- styling (injected once, !important so site CSS cannot hide it) ---------- */
  function style() {
    if (document.getElementById('cg-style')) return;
    var s = document.createElement('style');
    s.id = 'cg-style';
    s.textContent = [
      '.cg-btn{position:absolute!important;top:8px!important;width:38px!important;height:38px!important;',
      'box-sizing:border-box!important;border-radius:50%!important;border:2px solid #fff!important;',
      'display:flex!important;align-items:center!important;justify-content:center!important;',
      'z-index:2147483647!important;cursor:pointer!important;padding:0!important;margin:0!important;',
      'opacity:1!important;visibility:visible!important;box-shadow:0 2px 6px rgba(0,0,0,.45)!important;}',
      '.cg-btn svg{width:20px!important;height:20px!important;display:block!important;',
      'fill:#fff!important;stroke:#fff!important;pointer-events:none!important;}',
      '.cg-dl{left:8px!important;background:#1565c0!important;}',
      '.cg-dl.cg-done{background:#2e7d32!important;}',
      '.cg-tick{right:8px!important;background:rgba(0,0,0,.6)!important;',
      'color:#fff!important;font:bold 20px/34px sans-serif!important;text-align:center!important;}',
      '.cg-tick.cg-on{background:#2e7d32!important;}',
      '.cg-fab{position:fixed!important;right:16px!important;bottom:88px!important;z-index:2147483647!important;',
      'display:flex!important;align-items:center!important;gap:8px!important;padding:12px 18px!important;',
      'border-radius:28px!important;background:#1565c0!important;color:#fff!important;',
      'font:bold 15px/1 sans-serif!important;box-shadow:0 4px 14px rgba(0,0,0,.5)!important;',
      'cursor:pointer!important;border:0!important;opacity:1!important;visibility:visible!important;}',
      '.cg-fab.cg-done{background:#2e7d32!important;}',
      '.cg-fab svg{width:20px!important;height:20px!important;fill:#fff!important;stroke:#fff!important;}',
      '.cg-note{position:fixed!important;left:12px!important;bottom:88px!important;z-index:2147483646!important;',
      'max-width:60vw!important;padding:8px 12px!important;border-radius:10px!important;',
      'background:rgba(0,0,0,.82)!important;color:#fff!important;font:13px/1.35 sans-serif!important;}'
    ].join('');
    (document.head || document.documentElement).appendChild(s);
  }

  var ARROW = '<svg viewBox="0 0 24 24" aria-hidden="true">' +
    '<path d="M11 3h2v9.17l3.59-3.58L18 10l-6 6-6-6 1.41-1.41L11 12.17z"/>' +
    '<path d="M5 19h14v2H5z"/></svg>';

  /* ---------- page / card helpers ---------- */
  function titleLink(card) {
    var a = card.querySelector('h1 a[href], h2 a[href], h3 a[href], h4 a[href], .entry-title a[href], .post-title a[href], .tt a[href], .title a[href]');
    if (!a) { var im = card.querySelector('a[href] img'); a = im ? im.closest('a') : null; }
    return a;
  }
  function sameOriginOk(a) {
    try {
      var u = new URL(a.href, location.href);
      return u.origin === location.origin && u.pathname.length > 1 &&
             !NAV.test(u.pathname) && u.pathname !== location.pathname;
    } catch (e) { return false; }
  }
  function titleOf(card, a) {
    var img = card.querySelector('img');
    var t = (a && (a.textContent || a.title)) || '';
    if (!t.trim()) t = (img && (img.alt || img.title)) || '';
    if (!t.trim()) t = card.getAttribute('title') || '';
    return t.replace(/\s+/g, ' ').trim();
  }
  function isComicPage() {
    var b = (document.body && document.body.className) || '';
    return /\bsingle\b|\bpost-template-single\b/.test(b) ||
           !!document.querySelector('a[href*="dl="]') ||
           !!document.querySelector('.chapter-content, .entry-content .reading-content, .read-container');
  }
  function pageType() {
    if (cards.length >= 2) return 'listing';
    if (isComicPage()) return 'comic';
    return 'other';
  }
  function report() {
    try {
      CG.onPage(JSON.stringify({ type: pageType(), count: cards.length, url: location.href, title: document.title }));
    } catch (e) {}
  }

  /* ---------- buttons ---------- */
  function addDownload(card) {
    var dl = document.createElement('div');
    dl.className = 'cg-btn cg-dl';
    dl.title = 'Download this comic';
    dl.innerHTML = ARROW;
    dl.addEventListener('click', function (e) {
      e.preventDefault(); e.stopPropagation();
      dl.classList.add('cg-done');
      try { CG.onDownload(card.__cgUrl, card.__cgTitle); } catch (err) {}
    }, true);
    card.appendChild(dl);
  }
  function addTick(card) {
    var box = document.createElement('div');
    box.className = 'cg-btn cg-tick';
    box.title = 'Select';
    box.addEventListener('click', function (e) {
      e.preventDefault(); e.stopPropagation();
      card.__cgOn = !card.__cgOn;
      paint(card, card.__cgOn);
      try { CG.onToggle(card.__cgUrl, card.__cgTitle, card.__cgOn); } catch (err) {}
    }, true);
    card.appendChild(box);
    card.__cgBox = box;
    return box;
  }
  function paint(card, on) {
    var b = card.__cgBox;
    if (!b) return;
    b.textContent = on ? '\u2713' : '';
    b.className = 'cg-btn cg-tick' + (on ? ' cg-on' : '');
    card.style.outline = on ? '3px solid #2e7d32' : '';
  }
  function decorateCard(card, a) {
    if (card.__cg) return false;
    card.__cg = true;
    card.__cgUrl = a.href;
    card.__cgTitle = titleOf(card, a);
    if (getComputedStyle(card).position === 'static') card.style.position = 'relative';
    addTick(card);
    addDownload(card);
    card.__cgOn = false;
    if (pre[card.__cgUrl]) { card.__cgOn = true; paint(card, true); }
    return true;
  }

  /* ---------- detection ---------- */
  function collect() {
    cards = [];
    seen = {};
    if (isComicPage()) return;

    // pass 1: known theme selectors
    document.querySelectorAll(CARD).forEach(function (card) {
      if (!card.parentElement || (card.parentElement.closest && card.parentElement.closest(CARD))) return;
      var a = titleLink(card);
      if (!a || !sameOriginOk(a)) return;
      if (seen[a.href]) return;
      seen[a.href] = card;
      cards.push(card);
      decorateCard(card, a);
    });

    // pass 2: layout-agnostic fallback — a same-origin link wrapping a thumbnail
    if (cards.length < 2) {
      document.querySelectorAll('a[href] img').forEach(function (img) {
        var a = img.closest('a[href]');
        if (!a || !sameOriginOk(a)) return;
        if (seen[a.href]) return;
        var card = a.parentElement;
        // climb until the container holds the thumbnail plus some text (title)
        for (var i = 0; i < 3 && card && card !== document.body; i++) {
          if ((card.textContent || '').trim().length > 3) break;
          card = card.parentElement;
        }
        if (!card || card === document.body || card === document.documentElement) return;
        if (card.querySelectorAll('a[href] img').length > 6) return; // looks like a grid wrapper, not one card
        seen[a.href] = card;
        cards.push(card);
        decorateCard(card, a);
      });
    }
  }

  function fab() {
    if (document.getElementById('cg-fab')) return;
    var b = document.createElement('button');
    b.id = 'cg-fab';
    b.className = 'cg-fab';
    b.innerHTML = ARROW + '<span>Download</span>';
    b.addEventListener('click', function (e) {
      e.preventDefault(); e.stopPropagation();
      b.classList.add('cg-done');
      var t = (document.title || 'comic').split(/\s[|\u2013\u2014-]\s/)[0].trim() || 'comic';
      try { CG.onDownload(location.href, t); } catch (err) {}
    }, true);
    (document.body || document.documentElement).appendChild(b);
  }

  function note(text) {
    var n = document.getElementById('cg-note');
    if (!n) {
      n = document.createElement('div');
      n.id = 'cg-note';
      n.className = 'cg-note';
      (document.body || document.documentElement).appendChild(n);
    }
    n.textContent = text;
    n.style.display = 'block';
    clearTimeout(n.__t);
    n.__t = setTimeout(function () { n.style.display = 'none'; }, 6000);
  }

  function decorate() {
    style();
    collect();

    var single = isComicPage();
    var old = document.getElementById('cg-fab');
    if (single) {
      fab();
    } else if (old) {
      old.remove();
    }
    if (!single && cards.length === 0) {
      note('ComicGrabber: no comic cards found on this page \u2014 open a comic, then use the Download button.');
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
        clearTimeout(timer); timer = setTimeout(decorate, 500);
      }).observe(document.body, { childList: true, subtree: true });
      window.addEventListener('popstate', function () { setTimeout(decorate, 300); });
    }
  };

  window.__cgSelectAll = function (on) {
    cards.forEach(function (c) {
      if (!!c.__cgOn !== on) { c.__cgOn = on; paint(c, on); try { CG.onToggle(c.__cgUrl, c.__cgTitle, on); } catch (e) {} }
    });
  };
})();
