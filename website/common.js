/* ============================================================
   LiteChunk 官网 · 公共脚本
   主题、移动端导航、滚动入场、文档目录高亮、更新日志渲染
   ============================================================ */
(function () {
  'use strict';

  var THEME_KEY = 'lc-theme';
  var root = document.documentElement;
  var $ = function (sel, ctx) { return (ctx || document).querySelector(sel); };
  var $$ = function (sel, ctx) {
    return Array.prototype.slice.call((ctx || document).querySelectorAll(sel));
  };

  /* ---------------- 主题：浅色 / 深色 ---------------- */
  function currentTheme() {
    return root.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
  }

  function syncThemeMeta(theme) {
    var meta = $('meta[name="theme-color"]');
    if (!meta) return;
    var bg = getComputedStyle(root).getPropertyValue('--page').trim();
    meta.setAttribute('content', bg || (theme === 'dark' ? '#080c0a' : '#f5f8f5'));
  }

  function applyTheme(theme) {
    root.setAttribute('data-theme', theme);
    try { localStorage.setItem(THEME_KEY, theme); } catch (e) { /* 隐私模式下忽略 */ }
    var btn = $('#themeBtn');
    if (btn) {
      btn.setAttribute('aria-pressed', theme === 'dark' ? 'true' : 'false');
      btn.setAttribute('aria-label', theme === 'dark' ? '切换到浅色主题' : '切换到深色主题');
      btn.title = btn.getAttribute('aria-label');
    }
    syncThemeMeta(theme);
  }

  function initTheme() {
    var btn = $('#themeBtn');
    if (btn) {
      btn.addEventListener('click', function () {
        applyTheme(currentTheme() === 'dark' ? 'light' : 'dark');
      });
    }
    syncThemeMeta(currentTheme());
  }

  /* ---------------- 顶栏与移动端导航 ---------------- */
  function initHead() {
    var head = $('#siteHead');
    var menuBtn = $('#menuBtn');
    var nav = $('#siteNav');

    if (head) {
      var onScroll = function () {
        head.classList.toggle('is-stuck', window.scrollY > 6);
      };
      window.addEventListener('scroll', onScroll, { passive: true });
      onScroll();
    }

    if (!menuBtn || !nav) return;

    function setOpen(open) {
      nav.setAttribute('data-open', open ? 'true' : 'false');
      menuBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
      menuBtn.setAttribute('aria-label', open ? '关闭菜单' : '打开菜单');
    }

    setOpen(false);
    menuBtn.addEventListener('click', function () {
      setOpen(nav.getAttribute('data-open') !== 'true');
    });

    // 点击链接、按 Esc、点空白处都收起
    nav.addEventListener('click', function (e) {
      if (e.target.closest('a')) setOpen(false);
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') setOpen(false);
    });
    document.addEventListener('click', function (e) {
      if (nav.getAttribute('data-open') !== 'true') return;
      if (nav.contains(e.target) || menuBtn.contains(e.target)) return;
      setOpen(false);
    });
    window.addEventListener('resize', function () {
      if (window.innerWidth >= 860) setOpen(false);
    });

    // 当前页高亮：用文件名比较，避免不同部署路径下出错
    var here = location.pathname.split('/').pop() || 'index.html';
    $$('a[data-nav]', nav).forEach(function (a) {
      var file = a.getAttribute('href').split('#')[0] || 'index.html';
      if (file === here) a.setAttribute('aria-current', 'page');
    });
  }

  /* ---------------- 滚动入场：只播一次 ---------------- */
  function initReveal() {
    var items = $$('.reveal');
    if (!items.length) return;

    var reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduce || !('IntersectionObserver' in window)) {
      items.forEach(function (el) { el.classList.add('is-in'); });
      return;
    }

    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-in');
          io.unobserve(entry.target);
        }
      });
    }, { rootMargin: '0px 0px -6% 0px', threshold: 0.05 });

    items.forEach(function (el) { io.observe(el); });

    // 点锚点直接跳转时，中间内容被整段跨过，回调不会触发；用 rAF 节流的滚动兜底
    var ticking = false;
    function sweep() {
      ticking = false;
      var vh = window.innerHeight;
      items.forEach(function (el) {
        if (el.classList.contains('is-in')) return;
        var r = el.getBoundingClientRect();
        if (r.top < vh - 40 && r.bottom > 0) {
          el.classList.add('is-in');
          io.unobserve(el);
        }
      });
    }
    window.addEventListener('scroll', function () {
      if (ticking) return;
      ticking = true;
      window.requestAnimationFrame(sweep);
    }, { passive: true });
    window.addEventListener('load', sweep);
  }

  /* ---------------- 文档页目录高亮 ---------------- */
  function initToc() {
    var toc = $('.doc__toc');
    if (!toc) return;
    var links = $$('a', toc);
    var map = {};
    links.forEach(function (a) {
      var id = a.getAttribute('href').slice(1);
      var sec = document.getElementById(id);
      if (sec) map[id] = a;
    });

    var ids = Object.keys(map);
    if (!ids.length || !('IntersectionObserver' in window)) return;

    var spy = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        links.forEach(function (a) { a.classList.remove('is-active'); });
        map[entry.target.id].classList.add('is-active');
      });
    }, { rootMargin: '-20% 0px -70% 0px', threshold: 0 });

    ids.forEach(function (id) { spy.observe(document.getElementById(id)); });
  }

  /* ---------------- 回到顶部 ---------------- */
  function initToTop() {
    var btn = $('#toTop');
    if (!btn) return;
    var onScroll = function () {
      btn.classList.toggle('is-on', window.scrollY > 640);
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
    btn.addEventListener('click', function () {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }

  /* ---------------- 轻提示 ---------------- */
  var toastTimer = null;
  function toast(msg) {
    var el = $('#toast');
    if (!el) return;
    el.textContent = msg;
    el.classList.add('is-on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('is-on'); }, 1800);
  }

  function initCopy() {
    document.addEventListener('click', function (e) {
      var btn = e.target.closest('[data-copy]');
      if (!btn) return;
      var text = btn.getAttribute('data-copy');

      function fallback() {
        var ta = document.createElement('textarea');
        ta.value = text;
        ta.setAttribute('readonly', '');
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); } catch (err) { }
        document.body.removeChild(ta);
        toast('已复制：' + text);
      }

      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(function () {
          toast('已复制：' + text);
        }, fallback);
      } else {
        fallback();
      }
    });
  }

  /* ---------------- 页脚年份 ---------------- */
  function initYear() {
    $$('[data-year]').forEach(function (el) {
      el.textContent = String(new Date().getFullYear());
    });
  }

  /* ---------------- 更新日志：拉 VERSION.md 并渲染 ---------------- */
  var VERSION_URL = 'https://raw.githubusercontent.com/FreneticWind60/LiteChunk/main/VERSION.md';
  var VERSION_PAGE = 'https://github.com/FreneticWind60/LiteChunk/blob/main/VERSION.md';

  function esc(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // 只覆盖 VERSION.md 用到的语法：行内代码、加粗、链接
  function inline(s) {
    return esc(s)
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/\[([^\]]+)\]\(([^)]+)\)/g,
        '<a href="$2" target="_blank" rel="noopener">$1</a>');
  }

  function parseMd(md) {
    var lines = md.replace(/\r\n/g, '\n').split('\n');
    var out = [];
    var i = 0;

    while (i < lines.length) {
      var line = lines[i];

      if (!line.trim()) { i++; continue; }

      var h = line.match(/^(#{1,6})\s+(.*)$/);
      if (h) {
        var lv = Math.min(h[1].length + 1, 6);
        out.push({ type: 'heading', level: lv, text: h[2] });
        i++;
        continue;
      }

      // 表格：首行含 |，次行是分隔行
      if (/^\s*\|/.test(line) && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1] || '')) {
        var rows = [];
        var header = splitRow(line);
        i += 2;
        while (i < lines.length && /^\s*\|/.test(lines[i])) {
          rows.push(splitRow(lines[i]));
          i++;
        }
        out.push({ type: 'table', header: header, rows: rows });
        continue;
      }

      // 段落
      var buf = [];
      while (i < lines.length && lines[i].trim() && !/^\s*\|/.test(lines[i]) && !/^#{1,6}\s/.test(lines[i])) {
        buf.push(lines[i].trim());
        i++;
      }
      if (buf.length) out.push({ type: 'p', text: buf.join(' ') });
    }
    return out;
  }

  function splitRow(line) {
    return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(function (c) {
      return c.trim();
    });
  }

  function renderLog(md) {
    var blocks = parseMd(md);
    var html = '';
    var raw = md;

    blocks.forEach(function (b) {
      if (b.type === 'heading') {
        if (b.level <= 2) return; // 一级标题在页面里已有，不重复
        html += '<h3>' + inline(b.text) + '</h3>';
        return;
      }

      if (b.type === 'p') {
        html += '<p>' + inline(b.text) + '</p>';
        return;
      }

      if (b.type === 'table') {
        var isVersionTable = b.header.some(function (x) { return /版本/.test(x); }) &&
          b.header.some(function (x) { return /新特性|变化|更新/.test(x); });

        if (!isVersionTable) {
          html += '<table class="log-table"><thead><tr>' +
            b.header.map(function (x) { return '<th>' + inline(x) + '</th>'; }).join('') +
            '</tr></thead><tbody>' +
            b.rows.map(function (r) {
              return '<tr>' + r.map(function (c) { return '<td>' + inline(c) + '</td>'; }).join('') + '</tr>';
            }).join('') + '</tbody></table>';
          return;
        }

        // 版本表：倒序（最新的在最上面），一行一张卡
        var rows = b.rows.slice().reverse();
        rows.forEach(function (r, idx) {
          var ver = (r[0] || '').replace(/\*\*/g, '');
          var feat = r[1] || '';
          var limit = r[2] || '';

          html += '<article class="log__item' + (idx === 0 ? ' log__item--latest' : '') + '">' +
            '<div class="log__ver"><h3>' + inline(ver) + '</h3>' +
            (idx === 0 ? '<span class="badge">最新</span>' : '') +
            '</div>' +
            '<div class="log__cols">' +
            '<div><h4>新特性</h4><p>' + inline(feat) + '</p></div>' +
            '<div class="log__cols--limit"><h4>已知局限</h4><p>' +
            (limit.trim() === '—' || !limit.trim() ? '无' : inline(limit)) +
            '</p></div></div></article>';
        });
      }
    });

    return { html: html, raw: raw };
  }

  function initLog() {
    var mount = $('#logRoot');
    if (!mount) return;

    var hint = $('#logHint');
    var refresh = $('#logRefresh');
    var cached = null;

    function showError(err) {
      mount.innerHTML =
        '<div class="callout">' +
        '<h3>没能取到 VERSION.md</h3>' +
        '<p>更新日志直接从 GitHub 仓库读取，需要网络连通。刚才的错误：' +
        esc(String(err && err.message ? err.message : err)) + '。</p>' +
        '<p>可以直接去仓库看最新内容。</p>' +
        '<a class="btn btn--primary btn--sm" href="' + VERSION_PAGE + '" target="_blank" rel="noopener">' +
        '在 GitHub 打开 VERSION.md</a></div>';
      if (hint) hint.textContent = '读取失败';
    }

    function paint(md) {
      var res = renderLog(md);
      mount.innerHTML = '<div class="log">' + res.html + '</div>' +
        '<details class="log__raw"><summary>查看原始 Markdown</summary><pre>' +
        esc(res.raw) + '</pre></details>';
    }

    function load() {
      mount.innerHTML = '<div class="skeleton"><div></div><div></div><div></div></div>';
      if (hint) hint.textContent = '正在读取…';
      if (refresh) refresh.disabled = true;

      // 加时间戳绕开 raw.githubusercontent.com 的 CDN 缓存
      fetch(VERSION_URL + '?t=' + Date.now(), { cache: 'no-store' })
        .then(function (res) {
          if (!res.ok) throw new Error('HTTP ' + res.status);
          return res.text();
        })
        .then(function (md) {
          cached = md;
          paint(md);
          if (hint) {
            var d = new Date();
            var p = function (n) { return (n < 10 ? '0' : '') + n; };
            hint.textContent = '同步于 ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ' · ' + md.length + ' 字符';
          }
        })
        .catch(showError)
        .then(function () {
          if (refresh) refresh.disabled = false;
        });
    }

    if (refresh) {
      refresh.addEventListener('click', function () {
        if (cached && !navigator.onLine) {
          paint(cached);
          toast('离线，显示上次读取的内容');
          return;
        }
        load();
      });
    }

    load();
  }

  /* ---------------- 启动 ---------------- */
  function boot() {
    initTheme();
    initHead();
    initReveal();
    initToc();
    initToTop();
    initCopy();
    initYear();
    initLog();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
