/* ============================================================
   main.js — 演示器行为。**不属于产品**（投产时连同 stage.css 一起删）。

   产品自身的行为只有三件，其余都是壳：
     · 5 个目的地切换（底部栏 / 侧边导轨共用一套 DOM）
     · 打开 / 关闭用户详情（单栏档是抽屉，双栏档是常驻栏）
     · 双栏档拖动分栏
   ============================================================ */
(function () {
  'use strict';

  var VIEWPORTS = {
    phone: { w: 393, h: 852, label: '手机 393×852' },
    tabp:  { w: 834, h: 1112, label: '平板竖 834×1112' },
    tabl:  { w: 1112, h: 834, label: '平板横 1112×834' },
    desk:  { w: 1440, h: 900, label: '大屏 1440×900' }
  };

  var app = document.getElementById('app');
  var stage = document.querySelector('.stage');
  var canvas = document.querySelector('.canvas');
  var fit = document.querySelector('.fit');
  var screenEl = document.getElementById('screen');
  var current = 'tabl';

  /* ---------------- 出图模式：index.html#tabl,bare ----------------
     bare = 只留被演示的容器本身：没有工具条、没有侧栏、不缩放，
     容器尺寸 == 窗口尺寸。出图与断言都走这一条路径，
     避免再手写第二份「渲染壳」（上一版就是壳自己坏了且静默）。 */
  var bare = false;
  /* device = 真机全屏：去掉演示外壳（同 bare），但容器**尺寸跟随窗口**而不是钉死在档位。
     给 Android WebView 端打包时用（index.html#device），这样 @container app 的断点
     由真实屏幕宽度驱动；出图/断言仍走 bare（尺寸可控）。 */
  var device = false;
  var wantDetail = false;
  var wantTab = null;

  function applyHash() {
    var parts = (location.hash || '').replace(/^#/, '').split(',').map(function (s) { return s.trim(); });
    parts.forEach(function (p) {
      /* w1024x768 —— 任意尺寸档位（iPad 分屏 / 多窗口 / 弹窗这类非标宽度，
         正是容器查询要覆盖的场景：断点测的是容器，不是窗口） */
      var m = /^w(\d+)x(\d+)$/.exec(p);
      if (m) {
        VIEWPORTS.custom = { w: +m[1], h: +m[2], label: '自定义 ' + m[1] + '×' + m[2] };
        current = 'custom';
      }
      else if (VIEWPORTS[p]) current = p;
      else if (p === 'bare') bare = true;
      else if (p === 'device') device = true;
      else if (p === 'detail') wantDetail = true;
      else if (p.indexOf('t-') === 0) wantTab = p.slice(2);
      else if (p === 'brand') { document.documentElement.classList.add('brand'); var bEl = document.getElementById('brand'); if (bEl) bEl.checked = true; }
    });
    if (wantTab) goto(wantTab);
    if (wantDetail) openDetail();
    document.querySelectorAll('.vbtn').forEach(function (b) {
      b.classList.toggle('is-on', b.dataset.vp === current);
    });
    if (bare || device) {
      document.documentElement.classList.add('bare');
      stage.classList.add('bare');
    }
  }

  /* ---------------- 视口切换 + 自适应缩放 ---------------- */
  function layout() {
    if (device) {
      /* 真机全屏：容器 = 窗口，断点由真实宽度驱动（WebView 装着走这条） */
      fit.style.width = '100%';
      fit.style.height = '100%';
      fit.style.transform = 'none';
      readMetrics();
      return;
    }
    var vp = VIEWPORTS[current];
    fit.style.width = vp.w + 'px';
    fit.style.height = vp.h + 'px';
    if (bare) {
      /* 出图模式：容器钉死在左上角、按档位写死尺寸，
         这样容器宽度就等于档位宽度，跟无头窗口的实测视口（有 22/98px 的偏差）无关 */
      fit.style.transform = 'none';
    } else {
      var availW = canvas.clientWidth - 44;
      var availH = canvas.clientHeight - 44;
      var s = Math.min(availW / vp.w, availH / vp.h, 1);
      fit.style.transform = 'translate(-50%, -50%) scale(' + s + ')';
    }
    readMetrics();
  }

  document.querySelectorAll('.vbtn').forEach(function (b) {
    b.addEventListener('click', function () {
      document.querySelectorAll('.vbtn').forEach(function (x) { x.classList.remove('is-on'); });
      b.classList.add('is-on');
      current = b.dataset.vp;
      layout();
    });
  });

  /* ---------------- 开关 ---------------- */
  document.getElementById('brand').addEventListener('change', function (e) {
    document.documentElement.classList.toggle('brand', e.target.checked);
    readMetrics();
  });
  document.getElementById('hot').addEventListener('change', function (e) {
    stage.classList.toggle('show-hot', e.target.checked);
  });
  document.getElementById('grid').addEventListener('change', function (e) {
    stage.classList.toggle('show-grid', e.target.checked);
  });

  /* ---------------- Tab 切换 ---------------- */
  function goto(tab) {
    /* ⚠️ 只能用 .page[data-screen]：标签页曾经也叫 .screen，和**挂容器查询的那个
       盒子**同名 —— 一是会被一起 hidden（整个容器读成 0×0，且不报错），二是每个
       标签页自己也成了名为 app 的容器，把里面网格的列数查询全劫持成自己的宽度。 */
    document.querySelectorAll('.page[data-screen]').forEach(function (s) {
      s.hidden = s.dataset.screen !== tab;
    });
    /* 只有「动态 / 好友」是有主从关系的列表页，双栏档才拆「列表 + 详情」；
       主页 / 收藏 / 个人 是面板页（网格、资料卡），宽度整块给内容，
       否则统计格会被挤在 360px 的列表列里、6 列变成 6 个小方块。 */
    var wide = tab !== 'feed' && tab !== 'friends';
    var contentEl = document.querySelector('.app__content');
    if (contentEl) contentEl.classList.toggle('is-wide', wide);
    document.querySelectorAll('.navitem').forEach(function (n) {
      n.classList.toggle('is-on', n.dataset.goto === tab);
    });
    var main = document.querySelector('.pane--main');
    if (main) main.scrollTop = 0;
  }

  document.querySelectorAll('[data-goto]').forEach(function (el) {
    el.addEventListener('click', function () { goto(el.dataset.goto); });
  });

  /* ---------------- 详情 ---------------- */
  var openDetail = function () { app.classList.add('detail-open'); };
  var closeDetail = function () { app.classList.remove('detail-open'); };

  document.querySelectorAll('[data-open-detail]').forEach(function (el) {
    el.addEventListener('click', openDetail);
  });
  document.getElementById('detailClose').addEventListener('click', closeDetail);
  document.getElementById('scrim').addEventListener('click', closeDetail);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') closeDetail();
  });
  /* 双栏档的详情本来就是常驻的（.detail 在栅格第二列），不依赖这个 class；
     class 只管单栏档的抽屉开合 —— 所以初始不开，免得手机档一进来就被盖住。 */

  /* ---------------- 分栏拖动（只在双栏档生效） ---------------- */
  var splitter = document.getElementById('splitter');
  var dragging = false;

  function setListWidth(px) {
    var w = Math.max(280, Math.min(560, px));
    app.style.setProperty('--list-w', w + 'px');
  }

  splitter.addEventListener('pointerdown', function (e) {
    dragging = true;
    splitter.classList.add('is-drag');
    splitter.setPointerCapture(e.pointerId);
  });
  splitter.addEventListener('pointermove', function (e) {
    if (!dragging) return;
    var box = app.getBoundingClientRect();
    var railW = document.querySelector('.app__rail').getBoundingClientRect().width;
    setListWidth(e.clientX - box.left - railW);
  });
  splitter.addEventListener('pointerup', function (e) {
    dragging = false;
    splitter.classList.remove('is-drag');
    splitter.releasePointerCapture(e.pointerId);
    readMetrics();
  });
  splitter.addEventListener('keydown', function (e) {
    var cur = parseFloat(getComputedStyle(app).getPropertyValue('--list-w')) || 360;
    if (e.key === 'ArrowLeft') { setListWidth(cur - 24); e.preventDefault(); }
    if (e.key === 'ArrowRight') { setListWidth(cur + 24); e.preventDefault(); }
    readMetrics();
  });

  /* ---------------- 侧栏实测 ---------------- */
  function box(sel) {
    var el = document.querySelector(sel);
    if (!el) return null;
    var r = el.getBoundingClientRect();
    return { w: Math.round(r.width), h: Math.round(r.height) };
  }

  function sizeOf(el) {
    if (!el) return null;
    var r = el.getBoundingClientRect();
    return { w: Math.round(r.width), h: Math.round(r.height), x: Math.round(r.left), y: Math.round(r.top) };
  }

  function readMetrics() {
    var rail = document.querySelector('.app__rail');
    var bar = document.querySelector('.app__bar');
    var detail = document.getElementById('detail');
    var content = document.querySelector('.app__content');
    var feed = document.querySelector('.page[data-screen="feed"]');
    var card = feed && feed.querySelector('.card');
    var stat = document.querySelector('.statgrid');
    var fav = document.querySelector('.favgrid');
    var chip = document.querySelector('.chip');
    var field = document.querySelector('.field');
    var navItem = (getComputedStyle(rail).display !== 'none' ? rail : bar).querySelector('.navitem');

    var railShown = getComputedStyle(rail).display !== 'none';
    var barShown = getComputedStyle(bar).display !== 'none';
    var csContent = getComputedStyle(content);
    var csCard = card ? getComputedStyle(card) : null;
    var csChip = getComputedStyle(chip);
    var csDetail = getComputedStyle(detail);
    var appBox = app.getBoundingClientRect();

    function cols(el) {
      return el ? getComputedStyle(el).gridTemplateColumns.split(' ').length : 0;
    }

    /* ---- 侧栏（给人看的） ---- */
    document.getElementById('m-size').textContent =
      Math.round(appBox.width) + '×' + Math.round(appBox.height) + '（CSS px）';
    document.getElementById('m-nav').textContent =
      (railShown ? '侧边导轨 ' + sizeOf(rail).w + 'px' : '—') + (barShown ? ' + 底部 5 Tab' : '');
    document.getElementById('m-cols').textContent =
      csContent.display === 'grid' ? csContent.gridTemplateColumns : '单列';
    document.getElementById('m-card').textContent = card ? sizeOf(card).w + 'px' : '—';
    document.getElementById('m-detail').textContent =
      csDetail.position === 'static' ? '常驻右栏 ' + sizeOf(detail).w + 'px' : '底部抽屉';
    document.getElementById('m-overflow').textContent =
      content.scrollWidth > content.clientWidth + 1
        ? '有（' + content.scrollWidth + ' > ' + content.clientWidth + '）'
        : '无';

    /* ---- 供出图 / 断言脚本读取（省掉注入探针） ---- */
    window.__LAYOUT__ = {
      tier: current, bare: bare,
      viewport: { w: window.innerWidth, h: window.innerHeight },
      screen: {
        w: Math.round(screenEl.getBoundingClientRect().width),
        h: Math.round(screenEl.getBoundingClientRect().height),
        containerType: getComputedStyle(screenEl).containerType,
        containerName: getComputedStyle(screenEl).containerName
      },
      app: {
        w: Math.round(appBox.width), h: Math.round(appBox.height),
        /* 栅格轨道与自定义属性都报出来：导轨宽度对不上时一眼看出是
           --rail-w 没生效，还是轨道被别的东西挤了 */
        gridCols: getComputedStyle(app).gridTemplateColumns,
        railVar: getComputedStyle(app).getPropertyValue('--rail-w').trim()
      },
      rail: { shown: railShown, w: sizeOf(rail).w, h: sizeOf(rail).h },
      bar: { shown: barShown, h: sizeOf(bar).h },
      navItem: sizeOf(navItem),
      navItemLabel: getComputedStyle(navItem.querySelector('.navitem__label')).display,
      topbar: sizeOf(document.querySelector('.topbar')),
      content: {
        display: csContent.display,
        cols: csContent.gridTemplateColumns,
        colCount: csContent.display === 'grid' ? csContent.gridTemplateColumns.split(' ').length : 1,
        w: sizeOf(content).w, h: sizeOf(content).h,
        scrollW: content.scrollWidth, clientW: content.clientWidth,
        overflow: content.scrollWidth > content.clientWidth + 1
      },
      feedCard: card
        ? {
            w: sizeOf(card).w, h: sizeOf(card).h,
            radius: csCard.borderTopLeftRadius,
            background: csCard.backgroundColor,
            bodyDir: csCard.display
          }
        : null,
      chip: { h: sizeOf(chip).h, radius: csChip.borderTopLeftRadius },
      field: { h: sizeOf(field).h, radius: getComputedStyle(field).borderTopLeftRadius },
      /* x / top 是**渲染后的实际位置**（含 transform）：抽屉做过水平居中，
         双栏档要归零 —— 曾经因为一条 transition 把 transform 冻在起始值，
         位置算错但只查 position/width 是查不出来的。 */
      detail: {
        position: csDetail.position, w: sizeOf(detail).w, h: sizeOf(detail).h,
        x: sizeOf(detail).x, top: sizeOf(detail).y,
        transform: csDetail.transform
      },
      statCols: cols(stat),
      statW: stat ? sizeOf(stat).w : 0,
      favCols: cols(fav),
      /* 越界元素：宽高溢出被演示容器的任何可见件（不含本来就该滚动的列表） */
      overflowEls: (function () {
        /* 越界 = 被演示容器的盒子撑破了。滚动容器里的内容天然会超出
           （横向卡片条 .strip、可滚动的列表），那不算破版 —— 所以先往上
           走一遍，只要有任何一层 overflow 不是 visible 就跳过。 */
        var inScroller = function (el) {
          var n = el.parentElement;
          while (n && n !== app) {
            var o = getComputedStyle(n);
            if (o.overflow !== 'visible' || o.overflowX !== 'visible' || o.overflowY !== 'visible') return true;
            n = n.parentElement;
          }
          return false;
        };
        var bad = [];
        app.querySelectorAll('*').forEach(function (el) {
          var cs = getComputedStyle(el);
          if (cs.display === 'none' || cs.visibility === 'hidden' || cs.position === 'absolute') return;
          var r = el.getBoundingClientRect();
          if (r.width === 0 || inScroller(el)) return;
          if (r.right > appBox.left + appBox.width + 1 || r.left < appBox.left - 1) {
            bad.push((el.className || el.tagName) + ' ' + Math.round(r.left) + '→' + Math.round(r.right));
          }
        });
        return bad.slice(0, 6);
      })(),
      shell: {
        surface: getComputedStyle(app).backgroundColor,
        onSurface: getComputedStyle(app).color,
        font: getComputedStyle(app).fontFamily
      },
      /* 主色实测值：验证「动态蓝 #0054d6」与「品牌青 #006a6c」两套皮肤切换确实生效 */
      palette: {
        primary: getComputedStyle(document.documentElement).getPropertyValue('--md-primary').trim()
      }
    };
  }

  applyHash();
  new ResizeObserver(layout).observe(canvas);
  window.addEventListener('resize', layout);
  layout();
  /* 首帧之后再放开过渡：容器查询在首帧就要改 .detail 的 transform /
     栅格轨道，那时若 transition 已存在，计算值会被冻在过渡起点。 */
  requestAnimationFrame(function () {
    requestAnimationFrame(function () { document.documentElement.classList.add('ready'); });
  });
})();
