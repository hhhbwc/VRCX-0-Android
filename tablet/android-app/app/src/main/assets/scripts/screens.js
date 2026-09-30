/*
 * 五屏的**真实数据**渲染 —— 动态 / 好友 / 主页 / 收藏 / 个人，外加用户详情面板。
 *
 * ## 与样例数据的分工
 *
 * index.html 里那五屏是设计期的**静态样例**，它是浏览器模式（出图、四档布局断言、
 * 无桥演示）要用的那份，一行都不动。本文件只在 App 内且已登录时才介入，
 * 把每个列表容器的**内容整体换掉**。所以两套渲染互不影响：
 *
 *   浏览器 / 未登录 → 样例（并且顶部那条「演示数据」横幅还在）
 *   App 内 + 已登录 → 真实数据（横幅撤掉 —— 屏上已经没有样例了）
 *
 * ## 上屏纪律：一个裸 id 都不许出现
 *
 * VRChat 的 `location` 形如 `wrld_xxxx:19925~hidden(usr_yyyy)~region(jp)`：
 * 直接印出来会**同时**泄漏世界 id 和第三方的 user id。所以：
 *   · 所有服务端来的字符串都经 `safe()`（本文件里唯一的上屏出口）；
 *   · 匹配到裸 id 就换成调用方给的占位文案，**兜底绝不返回入参本身**；
 *   · location 一律先解析成「世界名 + 可见范围」，解析不出就不编（宁可空着）；
 *   · 行与行之间的关联**不放 data 属性**，走闭包 —— DOM 里因此连 id 的影都没有。
 * 回归测试 `_shots/make_screens_shot.py` 断言的正是"渲染完的 DOM 里搜不到 id"。
 *
 * ## 命令形态
 *
 * 参数一律**平铺**传，包不包 `input` 由 argforms.js 那张表决定（见 bridge.js）。
 * 形状取自 `src/src/platform/tauri/bindings.ts`（与服务端同一批 serde 类型）。
 * ⚠️ `app__vrchat_auth_current_user_get` 回的是 `{status, data}`，其中 **data 是
 * JSON 字符串**，要再 parse 一次 —— 当成对象用会静默拿到 undefined。
 */
(function () {
  'use strict';

  // 只认 App 全屏模式；浏览器/演示档位一律不介入（样例数据照旧）
  if (!window.VRCX || !window.VRCX.available) return;
  if ((location.hash || '').indexOf('device') < 0) return;

  var VRCX = window.VRCX;

  /* ------------------------------------------------------------------ 工具 */

  function $(id) { return document.getElementById(id); }
  function clear(node) { while (node && node.firstChild) node.removeChild(node.firstChild); }
  function show(node, on) { if (node) node.hidden = !on; }

  /**
   * 裸 id 的形态。screen 上的每一条服务端文本都要过这里。
   * `location` 那种复合串（`wrld_…~hidden(usr_…)`）整条命中 → 整条被换掉，正是我们要的。
   */
  var ID_RE = /\b(?:usr|wrld|avtr|grp)_[A-Za-z0-9_-]{4,}/;

  /**
   * 上屏前的唯一出口。**兜底返回 fallback，绝不返回入参本身** ——
   * 这是这套纪律里最容易写错的一条：`return text || fallback` 看着对，
   * 但一个"含有 id 的 text"会被原样放行。
   */
  function safe(text, fallback) {
    var s = (text === null || text === undefined) ? '' : String(text);
    if (!s) return fallback || '';
    if (ID_RE.test(s)) return fallback || '';
    return s;
  }

  /** 建一个纯文本节点。text 走 safe()，所以"忘了过滤"这件事在结构上不可能发生。 */
  function E(tag, cls, text, fallback) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined && text !== null) node.textContent = safe(text, fallback);
    return node;
  }

  function frag() { return document.createDocumentFragment(); }
  function add(parent, child) { if (child) parent.appendChild(child); return parent; }

  /** 首字母占位（样例数据用的就是这个观感，真数据保持一致）。 */
  function initial(name) {
    var s = safe(name, '·') || '·';
    return s.replace(/^[\s\u3000]+/, '').slice(0, 1).toUpperCase() || '·';
  }

  /* ---- 图片：服务端代取（每张一次往返），内存缓存 + 并发去重，失败回退首字母 ---- */

  var imgPending = {};

  /** VRChat 图片 URL 的「指定宽度」形态。
      `/api/1/image/file_<uuid>/<version>`            → 原图（动辄几 MB）
      `/api/1/image/file_<uuid>/<version>/<width>`    → 该宽度的缩略
      好友列表只要 64px 的小圆图：让服务端代理原图，242 个好友就是几百 MB 的
      data URL，路由器的内存和局域网带宽都扛不住。认不出的形状就原样用。 */
  function vrchatThumb(url, width) {
    var s = safe(url, '');
    if (!/^https?:\/\/[^/]+\/api\/1\/image\/[^/]+\/[^/]+$/.test(s)) return s;
    return s + '/' + width;
  }

  /**
   * 好友头像 URL —— 三层兜底，顺序不能换。
   *
   * ⚠️⚠️ 关键事实（2026-09-27 从**服务端实测诊断**拿到，不是推测）：
   * `/.config/VRCX-0/tenants/<id>/diagnostics/user-api-fields.jsonl` 里
   * `GET /auth/user/friends` 的 241 个样本中，
   *   `currentAvatarThumbnailImageUrl` → **241/241 = missing**
   *   `currentAvatarImageUrl`          → 241/241 非空字符串
   *   `iconUrl`                        → 非空字符串
   * 也就是说 VRChat 的 friends 端点**已经不再返回缩略图字段**了。
   * 只认 thumbnail 字段 ⇒ 每个好友都退化成首字母圆 —— 这就是"好友都没头像"。
   * （内部 JSON 名是 snake_case，服务端契约 `FriendRecord` 序列化成 camelCase。）
   */
  function friendAvatarUrl(rec) {
    if (!rec) return '';
    return safe(rec.currentAvatarThumbnailImageUrl, '')
      || vrchatThumb(safe(rec.currentAvatarImageUrl, ''), 64)
      || vrchatThumb(safe(rec.iconUrl, ''), 64);
  }

  /* ==================================================================
     状态灯（VRChat presence）—— 2026-09-29 对齐桌面版
     ================================================================== */

  /** `status` 归一化：joinme → join me、askme → ask me、`offline …` → offline。
      照搬桌面版 src/src/shared/utils/friendStatus.ts:34-45 `normalizeUserStatus`。 */
  function normalizeUserStatus(value) {
    var s = String(value == null ? '' : value).trim().toLowerCase();
    if (!s) return '';
    if (s === 'joinme') return 'join me';
    if (s === 'askme') return 'ask me';
    if (s === 'offline:offline' || s.indexOf('offline ') === 0) return 'offline';
    return s;
  }

  /** 只认 VRChat 那五档；不认识的返回 ''（桌面版 `userStatusFromValue` 同行为）。 */
  function userStatusFromValue(value) {
    var s = normalizeUserStatus(value);
    return (s === 'join me' || s === 'active' || s === 'ask me'
      || s === 'busy' || s === 'offline') ? s : '';
  }

  /**
   * 好友状态灯的类名。
   *
   * ⚠️ **判定链逐条照搬**桌面版
   * `src/src/components/sidebar/friends-sidebar/friendsSidebarModel.ts:264-371`
   * （好友分支，不是 currentUser 分支）。字段来源：服务端契约
   * `crates/core/src/friends.rs:94` `FriendRecord`（`state` / `status` / `location`）。
   *
   * 为什么必须照搬而不是自己配一套：以前这里只有 online / active / 其它三档且全是实心，
   * join me（蓝）、ask me（橙）、busy（红）被一并挤成一档 ——
   * 于是一屏里看着"一部分人的灯没规律地不一样"。其实那是有规律的，只是我们丢了三个维度。
   *
   * 两个维度一起编码（这就是 VRChat 官方的表达方式）：
   *   颜色 = `status` 五档    join me 蓝 / active 绿 / ask me 橙 / busy 红 / offline 灰
   *   形态 = 在不在实例里     实心 = 人真的在游戏里（`state === 'online'`，location 指得出来）
   *                          空心 = 只是网页/APP 挂着（`state === 'active'`），
   *                                只画一圈状态色描边、内部填背景色
   *
   * 返回 `''` 表示**不画点**（桌面版同样如此：信息不足时不画，而不是兜底给个灰点）。
   */
  function statusDotClass(rec) {
    if (!rec) return '';
    var st = safe(rec.state, '');
    var status = userStatusFromValue(rec.status);
    var loc = safe(rec.location, '');
    var userId = safe(rec.id, '') || safe(rec.userId, '');

    if (st === 'offline') return 'dot dot--offline';
    /* 在私密房间且服务器拿不到任何 presence → 桌面版给实心灰 */
    if (status !== 'active' && loc === 'private' && st === '' && userId) {
      return 'dot dot--offline';
    }
    if (st === 'active') {
      /* 网页/APP 在线但不在实例里：空心，颜色仍随 status 走（桌面版 activeStatusDotClassName） */
      var hollow = status === 'join me' ? 'dot--joinme'
        : status === 'ask me' ? 'dot--askme'
        : status === 'busy' ? 'dot--busy'
        : 'dot--online';
      return 'dot dot--hollow ' + hollow;
    }
    if (loc === 'offline' && st !== 'online') return 'dot dot--offline';
    if (status === 'active') return 'dot dot--online';
    if (status === 'join me') return 'dot dot--joinme';
    if (status === 'ask me') return 'dot dot--askme';
    if (status === 'busy') return 'dot dot--busy';
    /* 桌面版到这里返回 `''`（信息不足就不画）。
       这里**故意偏离**一次：降级名单（`rosterDegraded`，走好友日志兜底）
       里 `status` 字段常常整体缺失，照搬的话整屏好友一个点都没有，
       看起来像"状态全坏了"。既然已经知道人在实例里
       （`state === 'online'` 且 location 指得出来），就给实心绿。 */
    if (st === 'online' && loc && loc !== 'offline' && loc !== 'private') {
      return 'dot dot--online';
    }
    return '';
  }

  /** 状态灯的人话解释 —— 做 title / aria-label。
      只给一个颜色点是丢信息：用户分不出绿色实心跟绿色空心到底差在哪。 */
  /* ⚠️ 状态文案取**桌面版官方译文**（src/localization/zh-CN.json:3696-3703），
     别自己意译：
       status: join_me=欢迎加入 / ask_me=忙碌 / busy=请勿打扰 / active=活跃中 / offline=离线
       state : online=在线 / active=活跃中（仅登录网页端） / offline=离线
     2026-09-30 修：原来 join me / ask me 直接把英文打到界面上，而且
     **ask me 与 busy 的中文接反了**（ask me 写成"忙碌"、busy 也写"忙碌"）。
     VRChat 这两档的语义是反直觉的，照官方抄就对了。 */
  var STATUS_ZH = {
    'join me': '欢迎加入', 'ask me': '忙碌', 'busy': '请勿打扰',
    'active': '活跃中', 'online': '在线', 'offline': '离线'
  };
  var STATE_ZH = {
    'online': '在线', 'active': '活跃中（仅登录网页端）', 'offline': '离线'
  };

  function statusTip(rec) {
    if (!rec) return '';
    var st = safe(rec.state, '');
    var status = userStatusFromValue(rec.status);
    var base = STATUS_ZH[status] || '';
    var stateText = STATE_ZH[st] || '';
    if (st === 'offline') return '离线';
    /* 两个维度分开说清楚：状态灯的颜色 = status，实心/空心 = state。
       只报一半就会出现"灯是蓝的却写着在线"这种看不懂的组合。 */
    if (st === 'active') return (base ? base + ' · ' : '') + '活跃中（仅登录网页端）';
    if (st === 'online') return (base ? base + ' · ' : '') + '在线（在游戏内）';
    return base || status || '';
  }

  /** VRChat status → 中文短标签（分组头 / 筛选器文案用）。 */
  function statusLabel(status) {
    var s = userStatusFromValue(status);
    return STATUS_ZH[s] || '';
  }

  /** 排序用的 status 权值，大 = 排前面。
      照搬桌面版 src/src/shared/utils/friendStatus.ts:54-110 `sortStatus`，
      优先级 **join me > active > ask me > busy > offline**。
      这一步正是"排序总差点东西"的来源 —— 之前组内只按人数排，完全没有状态维度。 */
  function statusRank(status) {
    var s = userStatusFromValue(status);
    if (s === 'join me') return 5;
    if (s === 'active') return 4;
    if (s === 'ask me') return 3;
    if (s === 'busy') return 2;
    if (s === 'offline') return 1;
    return 0;
  }

  function loadImage(url) {
    if (!url) return Promise.resolve('');
    if (state.imgCache[url]) return Promise.resolve(state.imgCache[url]);
    if (imgPending[url]) return imgPending[url];
    imgPending[url] = cmd('app__external_api_image_data_url_get', { url: url })
      .then(function (out) {
        var data = safe(out && out.data, '');
        if (data) state.imgCache[url] = data;
        delete imgPending[url];
        return data;
      })
      .catch(function () {
        delete imgPending[url];
        return '';
      });
    return imgPending[url];
  }

  /** 有图异步换图（元素还在屏上才替换），没图保持首字母。 */
  function setAvatarImage(el, url) {
    if (!el || !url) return;
    loadImage(url).then(function (data) {
      if (!data || !el.isConnected) return;
      el.textContent = '';
      var img = document.createElement('img');
      img.src = data;
      img.alt = '';
      el.appendChild(img);
    });
  }

  function avatarEl(name, extraCls, url) {
    var el = E('span', 'avatar avatar--40' + (extraCls ? ' ' + extraCls : ''), initial(name));
    setAvatarImage(el, url);
    return el;
  }

  /* ---- 时间 ---- */

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  /**
   * 时间戳 → `MM-DD HH:mm`（今天则 `今天 HH:mm`）。
   * 认不出来就返回空串 —— 空着比印一个 `Invalid Date` 好。
   * 服务端 `created_at` 可能是 `YYYY-MM-DD HH:MM:SS`（SQLite 口径，按 UTC 存）或 ISO。
   */
  /* ==================================================================
     「关于」页四个开关的配置位 —— 2026-09-29 接线
     ------------------------------------------------------------------
     以前这四项是 index.html 里的纯静态 HTML，**全 scripts 目录 grep 不到任何绑定**
     —— 点了没反应。接线时每一条都必须真的改变某个行为，否则等于把"不会动的假控件"
     换成"会动的假控件"，比原来更骗人：
       reduceMotion  → html.no-motion：压掉列表入场 + 按压反馈（见 styles/app.css 同名规则）
       instanceId    → 位置行末尾追加实例号（走既有的 worldIdOf/worldSuffix 拆分，屏幕上不出现裸 id）
       hideNames     → 列表 / 详情里的名字改用 username
       relativeTime  → timeText 在"刚刚 / N 分钟前"与"今天 HH:mm"之间切换
     ================================================================== */
  var PREF_KEY = 'vrcx0.tablet.prefs';
  /* theme: 'system' | 'light' | 'dark' —— 个人页「外观」3 档主题（2026-09-30 接线）。
     'system' = 不设 data-theme，交给 CSS 的 prefers-color-scheme 媒体查询。 */
  var PREFS = { reduceMotion: true, instanceId: false, hideNames: false, relativeTime: true, theme: 'system' };
  var THEME_VALUES = ['system', 'light', 'dark'];

  function prefs() { return PREFS; }

  function loadPrefs() {
    try {
      var raw = window.localStorage.getItem(PREF_KEY);
      if (!raw) return;
      var obj = JSON.parse(raw);
      if (!obj || typeof obj !== 'object') return;
      for (var k in PREFS) {
        if (!Object.prototype.hasOwnProperty.call(PREFS, k)) continue;
        if (typeof PREFS[k] === 'boolean') {
          if (typeof obj[k] === 'boolean') PREFS[k] = obj[k];
        } else if (k === 'theme' && THEME_VALUES.indexOf(obj[k]) >= 0) {
          PREFS[k] = obj[k];
        }
      }
    } catch (e) { /* localStorage 不可用时（隐私模式 / 老 WebView）就留在默认值 */ }
  }

  function savePrefs() {
    try { window.localStorage.setItem(PREF_KEY, JSON.stringify(PREFS)); } catch (e) {}
  }

  /** 把配置落到看得见的地方。⚠️ 只改 <html> 的 class / 属性，
      不去逐个元素设 inline style —— 后者会在重渲染时被冲掉。 */
  function applyPrefs() {
    try {
      document.documentElement.classList.toggle('no-motion', PREFS.reduceMotion);
      /* theme：'system' 不设 data-theme，让 CSS 的 prefers-color-scheme 生效；
         'light'/'dark' 显式写属性，优先级高于系统。 */
      if (PREFS.theme === 'light' || PREFS.theme === 'dark') {
        document.documentElement.setAttribute('data-theme', PREFS.theme);
      } else {
        document.documentElement.removeAttribute('data-theme');
      }
      paintThemeChips();
    } catch (e) {}
  }

  /** 个人页「外观」3 档主题：高亮当前选中项。 */
  function paintThemeChips() {
    var box = $('themeChips');
    if (!box) return;
    var chips = box.querySelectorAll('.chip');
    for (var i = 0; i < chips.length; i++) {
      var v = chips[i].getAttribute('data-theme-value');
      chips[i].classList.toggle('is-on', v === PREFS.theme);
    }
  }

  function timeText(createdAt, unixSeconds) {
    var d = null;
    var s = String(createdAt || '').trim();
    if (s) {
      // 'YYYY-MM-DD HH:MM:SS' 里的空格在 Safari/老 WebView 上会被当成非法格式
      d = new Date(s.replace(' ', 'T').replace(/Z?$/, 'Z'));
      if (isNaN(d.getTime())) d = new Date(s);
    }
    if ((!d || isNaN(d.getTime())) && unixSeconds) {
      var n = Number(unixSeconds);
      if (isFinite(n) && n > 0) d = new Date(n * 1000);
    }
    if (!d || isNaN(d.getTime())) return '';
    var now = new Date();
    var hm = pad2(d.getHours()) + ':' + pad2(d.getMinutes());
    var sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
      && d.getDate() === now.getDate();
    var stamp = sameDay ? ('今天 ' + hm)
      : (pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + hm);
    /* 「动态时间用相对时间」关掉就是绝对时间戳；开着才给"刚刚 / N 分钟前"。
       以前这个开关是死的，现在由 prefs().relativeTime 真正控制 decades —— 改了要重渲才看得到。 */
    if (!PREFS.relativeTime) return stamp;
    var diff = Math.floor((now.getTime() - d.getTime()) / 1000);
    if (diff < 0) return stamp;                    // 时钟回拨，别显示"-3 分钟前"
    if (diff < 60) return '刚刚';
    if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前';
    if (sameDay) return Math.floor(diff / 3600) + ' 小时前';
    if (diff < 86400 * 2) return '昨天 ' + hm;
    return stamp;
  }

  /** `2019-07-14T...` → `2019-07-14`；认不出就空。 */
  function dateText(value) {
    var s = String(value || '').trim();
    if (!s) return '';
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(s);
    return m ? m[1] + '-' + m[2] + '-' + m[3] : '';
  }

  /* ---- 平台 / 可见范围 / 动态类型 ---- */

  var PLATFORM = {
    standalonewindows: 'PC', windows: 'PC', pc: 'PC',
    android: 'Android', ios: 'iOS', web: 'Web',
    '': ''
  };

  function platformText(v) {
    var k = String(v || '').trim().toLowerCase();
    if (!k) return '';
    return PLATFORM[k] || safe(v, '');
  }

  /** 实例串里的可见范围：`~hidden(usr_x)` / `~friends(usr_x)` / `~private(usr_x)` / `~group(grp_x)`。 */
  function accessKind(location) {
    var s = String(location || '');
    if (!s || s === 'offline' || s === 'private' || s === 'traveling') return '';
    if (s.indexOf('~') < 0) return 'public';
    if (/~hidden\(/.test(s)) return 'hidden';
    if (/~friends\(/.test(s)) return 'friends';
    if (/~private\(/.test(s)) return 'private';
    if (/~group\(/.test(s)) return 'group';
    return 'public';
  }

  var ACCESS_TEXT = {
    public: '公开', friends: '好友可见', hidden: '仅邀请', private: '私密', group: '群组'
  };

  function accessText(location) {
    var k = accessKind(location);
    if (k === 'hidden' && /~canRequestInvite/.test(String(location || ''))) return '仅邀请 · 可请求';
    return ACCESS_TEXT[k] || '';
  }

  /** `wrld_x:123~...` → `wrld_x`（只取世界 id，不上屏，用于查名字）。 */
  function worldIdOf(location) {
    var s = String(location || '').trim();
    if (s.indexOf('wrld_') !== 0) return '';
    var cut = s.indexOf(':');
    return cut > 0 ? s.slice(0, cut) : s;
  }

  /** 非世界实例串的几种「位置」字面量。 */
  function specialPlace(location) {
    var s = String(location || '').trim();
    if (s === 'private') return '私密房间';
    if (s === 'traveling' || s === 'travelingToWorld') return '传送中';
    if (s === 'offline' || s === '') return '';
    if (/^local/.test(s)) return '本机测试世界';
    return '';
  }

  var FEED_KIND = {
    GPS: '位置', Online: '上线', Offline: '下线', Status: '状态',
    Bio: '简介', Avatar: '模型', DisplayName: '昵称'
  };

  function feedKindText(type) {
    var t = String(type || '');
    if (!t) return '';
    return FEED_KIND[t] || safe(t, '');
  }

  /* ---- 信任等级 ---- */

  /* 信任等级中文（owner 口径）：游客 / 萌新 / 玩家 / 长期玩家 / 资深玩家。
     VRChat tag 有历史别名（new_user/intermediate/legend），一并兜住。 */
  /* 权威 = 1.0 系列 Protocol.kt trustRankLabel（owner 的 zh 表）：
     游客 / 萌新 / 玩家 / 长期玩家 / 资深玩家 / 传奇玩家；nuisance = 劣迹玩家。 */
  var TRUST = [
    ['system_trust_legendary', '传奇玩家'],
    ['system_trust_veteran', '资深玩家'],
    ['system_trust_trusted', '资深玩家'],   // legacy alias，老日志口径
    ['system_trust_known', '长期玩家'],
    ['system_trust_user', '玩家'],
    ['system_trust_basic', '萌新'],
    ['system_trust_nuisance', '劣迹玩家'],
    ['system_trust_visitor', '游客']
  ];

  function trustText(tags) {
    if (!tags || !tags.length) return '';
    for (var i = 0; i < TRUST.length; i++) {
      if (tags.indexOf(TRUST[i][0]) >= 0) return TRUST[i][1];
    }
    return '';
  }

  /* ------------------------------------------------------------------ 状态 */

  var state = {
    loaded: false,      // 五屏是否已经是真实数据
    busy: false,
    tried: false,
    error: '',
    userId: '',
    me: null,
    myAvatars: [],
    feedRows: [],
    feedHasMore: false,
        roster: { order: [], byId: {} },
        rosterDegraded: false,
    worldNames: {},     // wrld_id -> 世界名（**只用来查表，绝不上屏 id 本身**）
    retries: 0,
    imgCache: {},       // 图片 url -> dataURL（服务端代取，内存缓存）
    avatarUrls: {},     // userId -> 头像缩略图 url（feed 行喂给好友行/详情用）
    fav: { kind: 'world', rows: { world: [], avatar: [], friend: [] }, loaded: {} },
    favBaseline: null,                   // app__social_favorites_baseline_get 的 snapshot
    favFvrt: { pair: {}, ent: {} },      // 实体id|夹键 → fvrt_ 对象id（删除要用，见 buildFavFvrtMap）
    favError: '',                        // 收藏读取失败的原因（空 = 没失败）
    favNames: { world: {}, avatar: {}, group: {} },   // id -> {name}
    browse: [],
    search: { feed: '', friends: '', scope: 'mine', liveSeq: 0 },
    fFilter: '',                         // 好友筛选：'' 全部 / same 同房间 / online / active / offline
    feedFilter: '',                      // 动态类型筛选：'' 全部 / GPS / Status / Bio / Avatar / Online / Offline
    savedGroups: [],                     // 群组收藏夹集合（saved_group_favorites）
    savedGroupsError: '',                // 群组收藏夹读取失败的原因（空 = 没失败）
    configs: [],                         // 服务器配置（config_list_values，只读展示）
    streamWsid: '',                      // 已消费过的 realtime 会话 id（去重用）
    supported: [],                        // /v1/health 的 commands.supported（conn.js 转交）
    notif: { rows: [], loaded: false, busy: false }
  };

  /* ------------------------------------------------------------------ 取数 */

  function cmd(name, args) { return VRCX.command(name, args || {}); }

  /**
   * `{status, data}` → 解出来的 JSON。
   * ⚠️ `data` 是**字符串**（服务端把 VRChat 响应体原样放在里面），必须再 parse。
   */
  function unwrap(response) {
    if (!response) return null;
    var status = Number(response.status || 0);
    if (status && (status < 200 || status >= 300)) {
      throw new Error('VRChat 请求失败（' + status + '）');
    }
    var raw = response.data;
    if (typeof raw !== 'string') return raw || null;
    if (!raw.trim()) return null;
    try { return JSON.parse(raw); } catch (e) { return null; }
  }

  /** 世界 id → 名字。查 `world_summaries_get`（服务端的世界缓存），查不到就不编。 */
  function loadWorldNames(ids) {
    var want = [];
    var seen = {};
    for (var i = 0; i < ids.length; i++) {
      var id = ids[i];
      if (!id || seen[id]) continue;
      seen[id] = true;
      want.push(id);
    }
    if (!want.length) return Promise.resolve();
    return cmd('app__world_summaries_get', { worldIds: want }).then(function (map) {
      if (!map || typeof map !== 'object') return;
      for (var id in map) {
        if (!Object.prototype.hasOwnProperty.call(map, id)) continue;
        var s = map[id];
        if (s && s.name) state.worldNames[id] = safe(s.name, '');
      }
    }).catch(function () { /* 名字查不到只是显示不出，不算致命 */ });
  }

  function loadMe() {
    return cmd('app__vrchat_auth_current_user_get').then(function (r) {
      state.me = unwrap(r) || null;
    });
  }

  function loadMyAvatars() {
    return cmd('app__my_avatars_get', {
      currentAvatarId: '',
      previousAvatarSwapTime: 0
    }).then(function (list) {
      state.myAvatars = (list || []).filter(function (a) { return a && typeof a === 'object'; });
    }).catch(function () { state.myAvatars = []; });
  }

  /* 服务端 realtime 对账会拒掉没有 realtime 会话的基线请求（stale:"Superseded
     friend roster baseline."，重启也不自愈）—— 那时降级到好友日志的当前列表：
     本地库数据，{displayName,friendNumber,trustLevel,userId}，没有在线状态。 */
  var TRUST_LEVEL_TO_TAG = {
    "Legendary": "system_trust_legendary",
    "Veteran": "system_trust_veteran",
    "Trusted User": "system_trust_trusted",
    "Known User": "system_trust_known",
    "User": "system_trust_user",
    "Basic User": "system_trust_basic",
    "New User": "system_trust_new_user",
    "Visitor": "system_trust_visitor",
    "Nuisance": "system_trust_nuisance"
  };

  function fallbackRoster() {
    return cmd('app__friend_log_current_list', { userId: state.userId })
      .then(function (rows) {
        var list = Array.isArray(rows) ? rows : [];
        list.sort(function (a, b) { return (a.friendNumber || 0) - (b.friendNumber || 0); });
        var byId = {};
        var order = [];
        for (var i = 0; i < list.length; i++) {
          var row = list[i];
          if (!row || !row.userId) continue;
          var tag = TRUST_LEVEL_TO_TAG[row.trustLevel];
          byId[row.userId] = {
            id: row.userId,
            displayName: row.displayName || '',
            tags: tag ? [tag] : [],
            state: 'offline',   /* 降级模式没有在线状态，如实归入离线组 */
            degraded: true
          };
          order.push(row.userId);
        }
        state.roster = { order: order, byId: byId };
        state.friendCount = order.length;
        state.rosterDegraded = true;
      })
      .catch(function () {
        state.roster = { order: [], byId: {} };
        state.friendCount = 0;
        state.rosterDegraded = false;
      });
  }

  /** realtime 会话 id：conn.js 事件流下发后存进 state.streamWebsocket。
      baseline 请求不带它，服务端对已激活流会话一律对账拒（Superseded）。 */
  function connWsid() {
    try {
      var s = window.VRCXCon && window.VRCXCon.get ? window.VRCXCon.get() : null;
      return (s && s.streamWebsocket) || '';
    } catch (e) { return ''; }
  }

  /** VRChat API 端点（auth/status 下发）。基线对账比较
      {userId, endpoint, websocket} 三元组 —— endpoint 缺了照样被拒（0.2.12 实锤）。 */
  function connEndpoint() {
    try {
      var s = window.VRCXCon && window.VRCXCon.get ? window.VRCXCon.get() : null;
      return (s && s.vrchatEndpoint) || '';
    } catch (e) { return ''; }
  }

  function loadRoster() {
    return cmd('app__social_friend_roster_baseline_get', {
      userId: state.userId,
      endpoint: connEndpoint(),
      websocket: connWsid(),
      isFirstLoad: !connWsid()
    }).then(function (out) {
      var snap = out && out.snapshot;
      if (!snap) {
        state.rosterDegraded = !!(out && out.stale);
        return fallbackRoster();
      }
      var byId = (snap && snap.friendsById) || {};
      var order = (snap && snap.orderedFriendIds) || [];
      if (!order.length && Object.keys(byId).length) order = Object.keys(byId);
      state.roster = { order: order, byId: byId };
      state.friendCount = (out && out.count) || order.length;
      state.rosterDegraded = false;
    }).catch(function () {
      state.rosterDegraded = true;
      return fallbackRoster();
    });
  }

  function loadFeed() {
    return cmd('app__feed_latest_query', {
      query: { userId: state.userId, maxRows: 120 }
    }).then(function (out) {
      state.feedRows = (out && out.rows) || [];
      state.feedHasMore = !!(out && out.persistedHasMore);
    }).catch(function () { state.feedRows = []; state.feedHasMore = false; });
  }

  /**
   * 收藏数据的**真实来源**是服务端的收藏基线快照，不是本地表。
   *
   * ⚠️⚠️ 原来这里调的是 `app__favorite_list`，它读的是本地三张表
   * （favorite_world / favorite_avatar / favorite_friend）。而这三张表
   * **一张都没被写过**：2026-09-27 直接开库查过两台的 DB ——
   *   · 路由器租户库 `/.config/VRCX-0/tenants/dCvGnQSe0NEQjIA-/VRCX-0.sqlite3`
   *   · 本机桌面库 `%APPDATA%/VRCX-0/VRCX-0.sqlite3`
   *   两边都是 0 行。所以收藏页永远空白，跟有没有收藏毫无关系。
   * 真正在跑的是 `app__social_favorites_baseline_get`（登录时桌面端就用它
   * 把远端收藏拉回来，服务端会顺带 update_favorites_baseline 缓存进运行时）。
   * 会话级缓存一次，别每次切档都打 VRChat。
   */
  var favBaselinePending = null;

  function loadFavoritesBaseline() {
    if (state.favBaseline) return Promise.resolve(state.favBaseline);
    if (favBaselinePending) return favBaselinePending;
    var rosterById = {};
    for (var i = 0; i < state.roster.order.length; i++) {
      var id = state.roster.order[i];
      if (state.roster.byId[id]) rosterById[id] = state.roster.byId[id];
    }
    favBaselinePending = cmd('app__social_favorites_baseline_get', {
      userId: state.userId,
      endpoint: connEndpoint(),
      currentUserSnapshot: state.me || {},
      friendRosterById: rosterById
    }).then(function (out) {
      favBaselinePending = null;
      state.favBaseline = (out && out.snapshot) || null;
      buildFavFvrtMap(state.favBaseline);
      /* 成功必须清掉上一次的失败文案 —— 否则"会话恢复后收藏回来了，
         界面上还挂着上次的错误"（0.2.18 的 favError 生命周期 bug 同款）。 */
      state.favError = '';
      if (!state.favBaseline) state.favError = '服务端没有返回收藏快照（还没登录 VRChat？）。';
      return state.favBaseline;
    }).catch(function (err) {
      favBaselinePending = null;
      state.favError = window.VRCX ? window.VRCX.describeError(err) : '收藏读取失败。';
      return null;
    });
    return favBaselinePending;
  }

  /** 分组 key（服务端给的是 `worlds1` 这种机器键）→ 人能读的收藏夹名。 */
  var FAV_GROUP_FIELD = {
    world: 'favoriteWorldGroups', avatar: 'favoriteAvatarGroups', friend: 'favoriteFriendGroups'
  };

  function favoriteGroupName(snap, kind, key) {
    var list = (snap && snap[FAV_GROUP_FIELD[kind]]) || [];
    for (var i = 0; i < list.length; i++) {
      if (String(list[i].key) === String(key)) {
        return safe(list[i].displayName, '') || safe(list[i].name, '') || '';
      }
    }
    return '';
  }

  /**
   * 快照 → 渲染层要的行形态 `{worldId|avatarId|userId, groupName}`。
   * 世界与好友有服务端的 `grouped*ByGroupKey`（带收藏夹归属），
   * 模型没有分组映射，只能走平铺的 id 列表（服务端就只给了这两个）。
   */
  function rowsFromBaseline(snap, kind) {
    var out = [];
    if (!snap) return out;
    var grouped = null;
    var flat = [];
    if (kind === 'world') {
      grouped = snap.groupedFavoriteWorldIdsByGroupKey;
      flat = snap.favoriteWorldIds || [];
    } else if (kind === 'avatar') {
      flat = snap.favoriteAvatarIds || [];
    } else {
      grouped = snap.groupedFavoriteFriendIdsByGroupKey;
      flat = snap.favoriteFriendIds || [];
    }
    var seen = {};
    function push(entityId, groupKey) {
      if (!entityId || seen[entityId]) return;
      seen[entityId] = true;
      // 名字别叫 row：本文件别处有 30+ 个函数参数也叫 row，而
      // `_shots/lint_state_fields.py` 是按「名字 → 已知字面量键集」做静态判定的，
      // 一个局部 `var row = {…}` 会把文件里所有 `row.X` 读取都拖成误报。
      var favRow = { groupName: favoriteGroupName(snap, kind, groupKey) };
      /* 夹键（"world:worlds1" 这种 $groupKey 原文）也要带上：
         删除与编辑收藏夹都得靠它反查，不能只留显示名。 */
      if (groupKey) favRow.groupKey = groupKey;
      if (kind === 'world') favRow.worldId = entityId;
      else if (kind === 'avatar') favRow.avatarId = entityId;
      else favRow.userId = entityId;
      out.push(favRow);
    }
    if (grouped) {
      for (var gk in grouped) {
        if (!Object.prototype.hasOwnProperty.call(grouped, gk)) continue;
        var ids = grouped[gk] || [];
        for (var g = 0; g < ids.length; g++) push(ids[g], gk);
      }
    }
    for (var f = 0; f < flat.length; f++) push(flat[f], '');
    return out;
  }

  function loadFavorites(kind) {
    return loadFavoritesBaseline().then(function (snap) {
      var rows = rowsFromBaseline(snap, kind);
      state.fav.rows[kind] = rows;
      state.fav.loaded[kind] = true;
      if (!rows.length && state.favError) state.error = state.favError;
    });
  }

  /** 收藏只有 id，名字要另外补水。世界走 hydrate + 世界缓存，模型走 hydrate，好友从名单里取。 */
  function loadFavoriteNames() {
    var worlds = [];
    var avatars = [];
    var i;
    var r;
    for (i = 0; i < state.fav.rows.world.length; i++) {
      r = state.fav.rows.world[i];
      if (r && r.worldId) worlds.push(r.worldId);
    }
    for (i = 0; i < state.fav.rows.avatar.length; i++) {
      r = state.fav.rows.avatar[i];
      if (r && r.avatarId) avatars.push(r.avatarId);
    }
    var jobs = [];
    if (worlds.length) {
      jobs.push(cmd('app__favorite_details_hydrate', { kind: 'world', favoriteIds: worlds })
        .then(function (out) { collectNames('world', out); })
        .catch(function () {}));
    }
    if (avatars.length) {
      jobs.push(cmd('app__favorite_details_hydrate', { kind: 'avatar', favoriteIds: avatars })
        .then(function (out) { collectNames('avatar', out); })
        .catch(function () {}));
    }
    // 世界再兜一层：即便 hydrate 没给，服务端世界缓存里可能也有
    if (worlds.length) jobs.push(loadWorldNames(worlds));
    return Promise.all(jobs);
  }

  /* ==================================================================
     收藏增删改（0.2.31-t4）
     ------------------------------------------------------------------
     服务端的写命令（oss/crates/remote-server/src/commands/favorites.rs）：
       app__vrchat_favorite_add        {type, favoriteId, tags=夹键}
       app__vrchat_favorite_delete     {objectId = fvrt_ 对象id}
       app__vrchat_favorite_group_save {type, group=夹键, displayName, visibility}
     三条都是 INPUT_COMMANDS：客户端平铺传参，ArgForms.encode 包 {input:…}，
     服务端 parse_required 解 —— 与 boop_send 同一条链路。

     ⚠️ 删除打的 VRChat 端点是 DELETE /favorites/{fvrt_…}，要的是**收藏对象 id**，
     不是 wrld_/avtr_/usr_ 实体 id。基线快照的 remoteFavoritesById 恰好是
     fvrt → {favoriteId, type, $groupKey} 的映射，客户端自己就能翻，不用多打接口。
     ================================================================== */

  /** 从基线建 fvrt 反查表。键值都是**逻辑用的 id**，绝不走 safe()（它会把 id 清空）。 */
  function buildFavFvrtMap(snap) {
    state.favFvrt.pair = {};
    state.favFvrt.ent = {};
    if (!snap) return;
    var byId = snap.remoteFavoritesById || {};
    for (var fvrt in byId) {
      if (!Object.prototype.hasOwnProperty.call(byId, fvrt)) continue;
      var f = byId[fvrt] || {};
      var ent = String(f.favoriteId || '');
      if (!ent) continue;
      var gk = String(f['$groupKey'] || '');
      if (gk) state.favFvrt.pair[ent + '|' + gk] = fvrt;
      if (!state.favFvrt.ent[ent]) state.favFvrt.ent[ent] = fvrt;
    }
  }

  /** 渲染行 → 要删的 fvrt id。优先「实体+夹」精确匹配，退回「实体」唯一命中。 */
  function findFavFvrt(item) {
    var ent = item ? (item.worldId || item.avatarId || item.userId) : '';
    if (!ent) return '';
    var gk = String(item.groupKey || '');
    if (gk && state.favFvrt.pair[ent + '|' + gk]) return state.favFvrt.pair[ent + '|' + gk];
    return String(state.favFvrt.ent[ent] || '');
  }

  /** 收藏写操作后的统一善后：基线全失效 → 回源 → 重画。
      不做乐观删除 —— 删完立刻回源，界面说的就是服务端的真相。 */
  function refreshFavorites() {
    state.favBaseline = null;
    favBaselinePending = null;
    state.favFvrt.pair = {};
    state.favFvrt.ent = {};
    state.fav.rows = { world: [], avatar: [], friend: [] };
    state.fav.loaded = {};
    state.favNames = { world: {}, avatar: {}, group: {} };
    return loadFavorites(state.fav.kind)
      .then(function () { return loadFavoriteNames(); })
      .then(function () { renderFavorites(); })
      .catch(function (err) {
        state.favError = window.VRCX ? window.VRCX.describeError(err) : '收藏刷新失败。';
        renderFavorites();
      });
  }

  /** 移除单条收藏。破坏性动作，走 conn.js 的确认框（同「忘记此服务器」语义）。 */
  function favRemove(fvrtId, label) {
    if (!fvrtId) return;
    var ask = window.__ASK_CONFIRM__ || function (o, a) { return a(); };
    ask({
      title: '移除收藏',
      body: '把「' + (label || '这个收藏') + '」从 VRChat 收藏里移除？此操作直接改动账号，不可撤销。',
      okLabel: '移除',
      danger: true
    }, function () {
      return cmd('app__vrchat_favorite_delete', { objectId: fvrtId })
        .then(function () {
          toast('已移除');
          return refreshFavorites();
        })
        .catch(function (err) {
          toast('移除失败：' + (window.VRCX ? window.VRCX.describeError(err) : String(err)));
        });
    });
  }

  /* ---- 加收藏：选夹弹层 ---- */

  var favPickCtx = { kind: '', entityId: '', name: '' };

  function favPickNote(text) {
    var note = $('favPickNote');
    if (note) {
      note.textContent = text || '';
      note.hidden = !text;
    }
  }

  /** 好友详情（dFav）与世界详情（screens_tools 经 __SCREENS__.openFavPick）共用。 */
  function openFavPick(kind, entityId, entityName) {
    if (kind !== 'world' && kind !== 'avatar' && kind !== 'friend') return;
    if (!entityId) { toast('不知道要收藏谁。'); return; }
    favPickCtx.kind = kind;
    favPickCtx.entityId = String(entityId);
    favPickCtx.name = entityName || '';
    var title = $('favPickTitle');
    if (title) title.textContent = '把「' + (favPickCtx.name || FAV_LABEL[kind]) + '」加进收藏夹';
    var grid = $('favPickGrid');
    if (grid) clear(grid);
    favPickNote('');
    show($('favPickPanel'), true);
    loadFavoritesBaseline().then(function (snap) {
      if ($('favPickPanel') && $('favPickPanel').hidden) return;   // 用户已经关了
      if (!snap) { favPickNote('收藏夹没读到：' + (state.favError || '先登录 VRChat。')); return; }
      renderFavPickGrid(snap[FAV_GROUP_FIELD[kind]] || []);
    }).catch(function () {
      favPickNote('收藏夹没读到：' + (state.favError || '读取失败。'));
    });
  }

  function renderFavPickGrid(groups) {
    var grid = $('favPickGrid');
    if (!grid) return;
    clear(grid);
    if (!groups.length) {
      favPickNote('这个类型还没有收藏夹 —— 先关掉这个弹层，点收藏页右上角「新建收藏夹」。');
      return;
    }
    for (var i = 0; i < groups.length; i++) {
      (function (g) {
        var shown = String(g.displayName || '') || String(g.name || '') || '未命名';
        var cap = Number(g.capacity || 0);
        var cnt = Number(g.count || 0);
        var full = cap > 0 && cnt >= cap;
        var cell = document.createElement('button');
        cell.type = 'button';
        cell.className = 'card card--low favcell';
        if (full) cell.disabled = true;
        var thumb = E('span', 'worldcard__thumb');
        thumb.textContent = thumbText(shown);
        add(cell, thumb);
        add(cell, E('span', 'worldcard__name', cap > 0 ? (shown + ' ' + cnt + '/' + cap) : shown));
        if (full) add(cell, E('span', 'plat', '已满'));
        else cell.addEventListener('click', function () { favAddToGroup(g); });
        add(grid, cell);
      })(groups[i]);
    }
  }

  /** 真正写入。tags/复用组的 type —— vrcPlusWorld 夹必须原样带它的 type。 */
  function favAddToGroup(g) {
    var tag = String((g && g.name) || '');
    var gtype = String((g && g.type) || favPickCtx.kind || '');
    if (!tag || !gtype) { toast('这个收藏夹缺键名，加不了。'); return; }
    cmd('app__vrchat_favorite_add', { type: gtype, favoriteId: favPickCtx.entityId, tags: tag })
      .then(function () {
        toast('已加进收藏夹');
        show($('favPickPanel'), false);
        return refreshFavorites();
      })
      .catch(function (err) {
        toast('收藏失败：' + (window.VRCX ? window.VRCX.describeError(err) : String(err)));
      });
  }

  /* ---- 收藏夹新建 / 编辑 ---- */

  var favEditCtx = { mode: 'create', kind: 'world', type: 'world', group: '', visibility: 'private' };

  function favEditNote(text) {
    var note = $('favEditNote');
    if (note) {
      note.textContent = text || '';
      note.hidden = !text;
    }
  }

  /** 收藏页的「新建收藏夹」：按当前档建。「群组」档是本地集合，别混进来。 */
  function openFavGroupCreate() {
    var kind = state.fav.kind;
    if (kind === 'group') { toast('「群组」档是本地集合，不在这里建。'); return; }
    openFavGroupEdit(kind, '');
  }

  /** group 传夹键（"worlds1"），空 = 新建模式。 */
  function openFavGroupEdit(kind, group) {
    if (kind !== 'world' && kind !== 'avatar' && kind !== 'friend') return;
    favEditCtx.mode = group ? 'edit' : 'create';
    favEditCtx.kind = kind;
    var snap = state.favBaseline;
    var groups = (snap && snap[FAV_GROUP_FIELD[kind]]) || [];
    var cur = null;
    for (var i = 0; i < groups.length; i++) {
      if (String(groups[i].name || '') === String(group)) { cur = groups[i]; break; }
    }
    favEditCtx.group = cur ? String(cur.name || '') : String(group || '');
    favEditCtx.type = cur ? String(cur.type || kind) : kind;
    var title = $('favEditTitle');
    if (title) title.textContent = cur ? '编辑收藏夹' : ('新建' + (FAV_LABEL[kind] || '') + '收藏夹');
    var inp = $('favEditName');
    if (inp) inp.value = cur ? (String(cur.displayName || '') || String(cur.name || '')) : '';
    favEditCtx.visibility = cur ? (String(cur.visibility || '') || 'private') : 'private';
    paintFavVisChips();
    favEditNote('');
    show($('favEditPanel'), true);
  }

  function paintFavVisChips() {
    var box = $('favEditVis');
    if (!box) return;
    var chips = box.querySelectorAll('.chip');
    for (var i = 0; i < chips.length; i++) {
      chips[i].classList.toggle('is-on', chips[i].getAttribute('data-vis') === favEditCtx.visibility);
    }
  }

  /** 新建时的夹键：同型夹最大序号 + 1，超 favoriteLimits 就拒绝（返回空串）。 */
  function nextGroupKey(kind) {
    var snap = state.favBaseline || {};
    var groups = snap[FAV_GROUP_FIELD[kind]] || [];
    var prefix = { world: 'worlds', avatar: 'avatars', friend: 'friends' }[kind];
    var maxN = 0;
    for (var i = 0; i < groups.length; i++) {
      var nm = String((groups[i] && groups[i].name) || '');
      var m = nm.match(new RegExp('^' + prefix + '(\\d+)$'));
      if (m) {
        var n = parseInt(m[1], 10);
        if (n > maxN) maxN = n;
      }
    }
    var limits = (snap.favoriteLimits && snap.favoriteLimits.maxFavoriteGroups) || {};
    var cap = Number(limits[kind] || 0);
    if (cap > 0 && maxN + 1 > cap) return '';
    return prefix + (maxN + 1);
  }

  function favEditSave() {
    var inp = $('favEditName');
    var displayName = inp ? inp.value.replace(/^\s+|\s+$/g, '') : '';
    var create = favEditCtx.mode === 'create';
    var group = create ? nextGroupKey(favEditCtx.kind) : favEditCtx.group;
    if (!group) { favEditNote(create ? '这个类型的收藏夹数量已达上限。' : '收藏夹键丢失，关掉面板重试。'); return; }
    var btn = $('favEditSave');
    if (btn) btn.disabled = true;
    var args = { type: favEditCtx.type, group: group, visibility: favEditCtx.visibility };
    if (displayName) args.displayName = displayName;
    cmd('app__vrchat_favorite_group_save', args)
      .then(function () {
        if (btn) btn.disabled = false;
        show($('favEditPanel'), false);
        toast(create ? '收藏夹已创建' : '收藏夹已更新');
        return refreshFavorites();
      })
      .catch(function (err) {
        if (btn) btn.disabled = false;
        favEditNote('保存失败：' + (window.VRCX ? window.VRCX.describeError(err) : String(err)));
      });
  }

  /** 群组收藏夹加载：集合 → 逐组查名（缺名只是显示不出，不报错）。 */
  function loadSavedGroups() {
    return cmd('app__saved_group_favorites_get').then(function (snap) {
      var collections = (snap && snap.collections) || [];
      state.savedGroups = collections.filter(function (c) {
        return c && typeof c === 'object';
      });
      var want = {};
      for (var i = 0; i < state.savedGroups.length; i++) {
        var gids = state.savedGroups[i].groupIds || [];
        for (var j = 0; j < gids.length; j++) {
          if (gids[j] && !state.favNames.group[gids[j]]) want[gids[j]] = true;
        }
      }
      var ids = Object.keys(want).slice(0, 30);
      return Promise.all(ids.map(function (gid) {
        return cmd('app__vrchat_group_get', { groupId: gid }).then(function (r) {
          var g = unwrap(r);
          if (g && g.name) state.favNames.group[gid] = safe(g.name, '');
        }).catch(function () {});
      }));
    }).then(function () {
      state.savedGroupsError = '';
      renderFavorites();
    }).catch(function (err) {
      /* 集合没读到就如实报错（区别于"空的"） */
      state.savedGroups = [];
      state.savedGroupsError = window.VRCX ? window.VRCX.describeError(err) : '读取失败';
      renderFavorites();
    });
  }

  function collectNames(kind, out) {
    var byId = out && out.detailsById;
    if (!byId || typeof byId !== 'object') return;
    var bucket = state.favNames[kind] || (state.favNames[kind] = {});
    for (var id in byId) {
      if (!Object.prototype.hasOwnProperty.call(byId, id)) continue;
      var d = byId[id];
      var name = d && (d.name || d.displayName || d.worldName);
      if (name) bucket[id] = safe(name, '');
    }
  }

  function loadBrowse() {
    return cmd('app__browse_history_query', {
      ownerUserId: state.userId,
      entityKind: null,
      cursor: null,
      limit: 12
    }).then(function (out) {
      state.browse = (out && out.items) || [];
    }).catch(function () { state.browse = []; });
  }

  /** 一次性把五屏要的数据都拉回来，然后统一渲染。 */
  function loadAll(silent) {
    if (state.busy) return Promise.resolve(false);
    state.busy = true;
    state.error = '';
    /* silent = 事件触发的后台对账：屏上已有真实数据，别闪加载横幅 */
    if (!silent) paintNotice('正在从服务器读取…');

    return loadMe()
      .then(function () {
        /* 各屏数据并行取，**各自兜底**：一个命令 500 不能拖垮其它屏
           （2026-09-22 真机实锤：串行链里一个 VRChat 500 = 五屏全空白）。 */
        return Promise.all([
          loadRoster().catch(function () {}),
          loadFeed().catch(function () {}),
          loadMyAvatars().catch(function () {}),
          loadFavorites('world').catch(function () {}),
          loadFavorites('avatar').catch(function () {}),
          loadFavorites('friend').catch(function () {}),
          loadBrowse().catch(function () {})
        ]);
      })
      .then(function () { return loadFavoriteNames(); })
      .then(function () {
        // 世界名：名单里所有 location + 我自己的 homeLocation
        var ids = [];
        for (var i = 0; i < state.roster.order.length; i++) {
          var rec = state.roster.byId[state.roster.order[i]];
          var w = worldIdOf(rec && rec.location);
          if (w) ids.push(w);
        }
        if (state.me) {
          var home = worldIdOf(state.me.homeLocation);
          if (home) ids.push(home);
        }
        return loadWorldNames(ids);
      })
      .then(function () {
        state.loaded = true;
        state.busy = false;
        state.tried = true;
        state.retries = 0;
        renderAll();
        // 未读角标：登录后拉一次通知列表（面板开着时也会重新拉）
        loadNotifs();
        loadConfigs();
        return true;
      })
      .catch(function (err) {
        state.busy = false;
        state.tried = true;
        state.error = VRCX.describeError(err);
        // 取数失败**不能**把样例当成真实数据留着 —— 横幅要留着说清楚
        renderAll();
        paintNotice('取数失败：' + state.error);
        paintLoadFailed();
        /* VRChat 侧 500/网络抖动多为瞬态：15s 后自动重试，最多 3 次。 */
        if ((state.retries || 0) < 3) {
          state.retries = (state.retries || 0) + 1;
          setTimeout(function () {
            if (!state.loaded && !state.busy) loadAll();
          }, 15000);
        }
        return false;
      });
  }

  /** 取数失败后：把骨架条换成诚实的错误提示 —— 骨架条还在一闪一闪的话，
      屏幕一半说"失败了"、一半说"正在加载"，用户不知道该信哪个（0.2.19 实测）。 */
  function paintLoadFailed() {
    var targets = ['feedList', 'friendList', 'homeLatest', 'favBody'];
    var cleared = 0;
    for (var i = 0; i < targets.length; i++) {
      var box = $(targets[i]);
      if (box && box.querySelector('.skel')) {
        cleared++;
        clear(box);
        add(box, E('p', 'emptynote', '取数失败：' + state.error + '。会自动重试；不行就重进一次。'));
      }
    }
    /* feedEmpty 的显隐归 renderFeed 管 —— 失败路径 renderFeed 没跑，这里补上 */
    var fe = $('feedEmpty');
    if (fe && !state.loaded) {
      fe.textContent = '取数失败：' + state.error;
      show(fe, true);
    }
    if (window.__LOADFAIL_TRACE__) window.__LOADFAIL_TRACE__.push('plf cleared=' + cleared);
  }

  /* ------------------------------------------------------------------ 渲染 */

  /** 顶部横幅：已登录时由本文件接管（conn.js 只在未登录时改它）。 */
  function paintNotice(text) {
    var bar = $('demobar');
    var label = $('demobar-text');
    if (!bar) return;
    if (state.loaded && !text) { show(bar, false); return; }
    show(bar, true);
    if (label) label.textContent = safe(text, '正在从服务器读取…');
  }

  function renderAll() {
    if (!state.loaded) {
      paintNotice('');
      /* ⚠️ 取数失败（如 VRChat 会话 401）时 state.loaded 一直是 false ——
         但个人页的"服务器地址"来自本地 configJson，与 VRChat 会话无关。
         不刷新的话，个人页会永远冻结在开机那一刻的渲染（实测显示"未配置"
         而顶栏已连上）。五屏列表仍留给骨架/样例，只刷个人页。 */
      renderProfile();
      return;
    }
    renderFeed();
    renderFriends();
    renderHome();
    renderFavorites();
    renderProfile();
    applyDetailVis();
    paintNotice('');
  }

  /**
   * 列表空/失败时的一句实话。**不写"加载中"糊过去**：区分"确实没有"和"没拉到"。
   */
  function noticeFor(node, count, what) {
    if (!node) return;
    if (!state.loaded) {
      node.textContent = '正在读取' + what + '…';
      node.hidden = false;
      return;
    }
    if (count > 0) { node.hidden = true; node.textContent = ''; return; }
    node.textContent = state.error
      ? what + '没读到：' + state.error
      : '这里还没有' + what + '。';
    node.hidden = false;
  }

  /* ---- 1. 动态 ---- */

  function feedBody(row) {
    var type = String(row.type || '');
    if (type === 'GPS') {
      var place = safe(row.worldName, '');
      if (!place) {
        var sp = specialPlace(row.location);
        place = sp || '（位置未知）';
      }
      var acc = accessText(row.location);
      return '在 ' + place + (acc ? ' · ' + acc : '');
    }
    if (type === 'Status') {
      var st = safe(row.statusDescription, '') || safe(row.status, '');
      var prev = safe(row.previousStatusDescription, '') || safe(row.previousStatus, '');
      if (prev && st && prev !== st) return prev + ' → ' + st;
      return st || '改了状态';
    }
    if (type === 'Bio') return safe(row.bio, '（简介已更新）') || '（简介已更新）';
    if (type === 'Avatar') {
      var av = safe(row.avatarName, '');
      return av ? '换上了模型 ' + av : '换了模型';
    }
    if (type === 'Online') return '上线了';
    if (type === 'Offline') return '下线了';
    // 认不出的类型：不编一句像真的话，只把类型名（已过 safe）说出来
    return feedKindText(type) || '动态';
  }

  function renderFeed() {
    var box = $('feedList');
    if (!box) return;
    clear(box);

    // 只画最近的若干条：这是**列表**，不是全量导出
    var LIMIT = 40;
    var rows = state.feedRows.slice(0, LIMIT);
    // 本地搜索（服务端 quick_search_query 尚未实现）：在已加载的动态里按
    // 好友名 / 动态文本过滤，不重新打接口。裸 id 不会进 haystack（safe 过）。
    if (state.search.feed) {
      var qf = state.search.feed;
      rows = rows.filter(function (row) {
        var nm = safe(row.displayName, '') ||
          (state.roster.byId[row.userId] && state.roster.byId[row.userId].displayName) || '';
        return (nm + ' ' + feedBody(row)).toLowerCase().indexOf(qf) >= 0;
      });
    }
    /* 类型筛选：chip 的 data-feed-kind **就是** FEED_KIND 的键（英文枚举），
       所以这里必须拿 row.type 直接比键。
       ⚠️ 0.2.26 栽过一次：写成 feedKindText(row.type) === state.feedFilter ——
       左边是给人看的**文案**（'位置'）、右边是**键**（'GPS'），恒为 false，
       于是点任何一个类型 chip 都恒筛出 0 条（只有「全部」看着正常）。 */
    if (state.feedFilter) {
      rows = rows.filter(function (row) {
        return String(row.type || '') === state.feedFilter;
      });
    }

    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      var rec = state.roster.byId[row.userId] || null;
      var name = safe(row.displayName, '') || safe(rec && rec.displayName, '') || '未知好友';

      var card = E('button', 'card');
      card.type = 'button';
      card.setAttribute('data-open-detail', '');

      var feedrow = E('div', 'feedrow');
      /* ⚠️⚠️ 2026-09-29 修正 —— 这里原先只从 **feed 行自己**的三个字段取头像：
             row.currentAvatarThumbnailImageUrl || row.currentAvatarImageUrl || row.iconUrl
         而 `app__feed_latest_query` 返回的行**一个头像字段都没有**。对真服务器实测，
         行字段只有：`created_at / displayName / location / ownerUserId / type / userId`。
         所以那三层兜底是**死代码**，恒为 '' ⇒ 动态行永远只显示首字母圆 ——
         而**同一批人**在好友页是有头像的（好友列表走名册，字段齐全）。
         这正是用户报的"好友里的用户也都没有头像"里，只剩动态那半边的那一半。
         行里本来就已经查了 `rec`，同一份数据直接拿来用；自己发的动态（不在好友名册里）
         用 `state.me` 兜住。feed 自带的三个字段留在最后 —— 万一哪天服务端补上就自动生效。 */
      var who = rec || (state.me && state.me.id === row.userId ? state.me : null);
      var avatarUrl = friendAvatarUrl(who)
        || safe(row.currentAvatarThumbnailImageUrl, '')
        || vrchatThumb(safe(row.currentAvatarImageUrl, ''), 64)
        || vrchatThumb(safe(row.iconUrl, ''), 64);
      if (row.userId && avatarUrl) {
        state.avatarUrls[row.userId] = avatarUrl;
      }
      add(feedrow, avatarEl(name, 'av' + ((i % 6) + 1), avatarUrl));

      var body = E('div', 'feedrow__body');
      var line = E('div', 'rowline');
      add(line, E('span', 'name', name, '未知好友'));
      var kind = feedKindText(row.type);
      if (kind) add(line, E('span', 'kind', kind));
      add(body, line);

      var text = feedBody(row);
      if (text) add(body, E('div', 'body', text));
      var when = timeText(row.created_at, row.time);
      if (when) add(body, E('div', 'time', when));
      add(feedrow, body);
      add(card, feedrow);

      /* ⚠️ 关联走闭包，不写进 DOM —— 见文件头「一个裸 id 都不许出现」。
         row.userId 只活在这个闭包里。 */
      bindDetail(card, row.userId, name);
      add(box, card);
    }

    noticeFor($('feedEmpty'), rows.length, '动态');
    if (rows.length === 0 && state.loaded && state.feedRows.length
        && (state.search.feed || state.feedFilter)) {
      var fe2 = $('feedEmpty');
      if (fe2) { fe2.textContent = '没有匹配的动态。换个筛选或清空搜索试试。'; }
    }
  }

  /* ---- 2. 好友 ---- */

  /** 在线/活跃的人按所在世界分组；离线合成一组。 */
  /** 收藏（特别关心）的好友 id 集合 —— 列表置顶 + 星标。 */
  function favoriteFriendSet() {
    var set = {};
    var rows = state.fav.rows.friend || [];
    for (var i = 0; i < rows.length; i++) {
      var id = rows[i] && (rows[i].userId || rows[i].user_id);
      if (id) set[id] = true;
    }
    return set;
  }

  function friendGroups(query, records) {
    var groups = [];
    var byWorld = {};
    var offline = [];
    query = query || '';
    /* records 传入时用调用方筛过的名单（筛选档），否则全量 */
    var source = records || null;
    if (!source) {
      source = [];
      for (var si = 0; si < state.roster.order.length; si++) {
        var sid = state.roster.order[si];
        if (state.roster.byId[sid]) source.push(state.roster.byId[sid]);
      }
    }

    for (var i = 0; i < source.length; i++) {
      var rec = source[i];
      if (!rec) continue;
      // 搜索：按已加载好友名过滤（服务端 quick_search 未实现时的本地降级）
      if (query && (safe(rec.displayName, '') || safe(rec.username, '') || '').toLowerCase().indexOf(query) < 0) continue;
      var st = String(rec.state || 'offline');
      if (st === 'offline') { offline.push(rec); continue; }

      var wid = worldIdOf(rec.location);
      var sp = specialPlace(rec.location);
      var key = wid || (sp ? '#' + sp : '#unknown');
      if (!byWorld[key]) {
        byWorld[key] = { key: key, worldId: wid, special: sp, members: [], state: st };
      }
      byWorld[key].members.push(rec);
      if (st === 'online') byWorld[key].state = 'online';
    }

    var fav = favoriteFriendSet();
    var myId = state.userId || (state.me && state.me.id) || '';

    /* 组内排序 —— 对齐桌面版的默认三级排序
       `Sort by Status` → `Sort Alphabetically` → `None`
       （useFriendsLocationsPreferences.ts:26-28 的 sidebarSortMethod1/2/3 默认值；
        比较器见 shared/utils/compare.ts:99-115 与 friendStatus.ts:54-110）。
       优先级：收藏（特别关心）> 状态 > 显示名字母序 > 稳定原序，
       另外「我」永远在自己所在那一组的首位 —— 这一条桌面版是 unshift 到组内头部
       （useFriendsLocationsPageDerivedState.ts:404-417），语义相同。
       以前这里只有"收藏优先"，其余全靠 roster 原顺序，所以看起来"总差点东西"。 */
    function memberCmp(a, b) {
      if (myId && a.id === myId) return -1;
      if (myId && b.id === myId) return 1;
      var fa = fav[a.id] ? 1 : 0, fb = fav[b.id] ? 1 : 0;
      if (fa !== fb) return fb - fa;
      var ra = statusRank(a.status), rb = statusRank(b.status);
      if (ra !== rb) return rb - ra;
      var na = safe(a.displayName, '') || safe(a.username, '') || '';
      var nb = safe(b.displayName, '') || safe(b.username, '') || '';
      /* 中文界面要用 zh-Hans-CN 排序规则，否则中文档名会落到拼音 bin 序末尾 */
      return na.localeCompare(nb, 'zh-Hans-CN');
    }

    function groupTitle(g) {
      if (g.offline) return '\uffff';   // offline 永远沉底
      return safe(g.worldId, '') || safe(g.special, '') || safe(g.key, '');
    }

    /* 分组顺序：我所在的实例 → 世界名/位置名 → 人数多的在前。
       ⚠️ 这里跟桌面版**故意不同**：桌面版只把 online/favorite/same-instance 等段
       按标题 localeCompare 排，且**不**把"我"所在的实例整体置顶
       （friendsLocationsSections.ts:495-513，只 ensure currentUser 在组内第一）。
       平板这一列只有 400px，翻找成本高，且用户明确反馈"排序总差点东西"——
       所以把"我所在的实例"提到最前。**要改回桌面版语义就改这一处。**
       标题用 worldId/special 而不是 state.worldNames 里的显示名：
       世界名是异步回包的，用它排序会让列表在名字到达时整体跳动，属于可感知抖动。 */
    var mineId = myWorldId();
    var keys = Object.keys(byWorld);
    keys.sort(function (a, b) {
      var ga = byWorld[a], gb = byWorld[b];
      var ma = mineId && ga.worldId === mineId ? 1 : 0;
      var mb = mineId && gb.worldId === mineId ? 1 : 0;
      if (ma !== mb) return mb - ma;
      var ta = groupTitle(ga), tb = groupTitle(gb);
      if (ta !== tb) return ta.localeCompare(tb, 'zh-Hans-CN');
      return gb.members.length - ga.members.length;
    });
    for (var k = 0; k < keys.length; k++) {
      byWorld[keys[k]].members.sort(memberCmp);
      groups.push(byWorld[keys[k]]);
    }
    if (offline.length) {
      offline.sort(memberCmp);
      groups.push({ key: '#offline', offline: true, members: offline });
    }
    return groups;
  }

  function groupHeadText(g) {
    var n = g.members.length + ' 人';
    if (g.offline) return (state.rosterDegraded ? '全部好友 · ' : '离线 · ') + n;
    var name = g.worldId ? (state.worldNames[g.worldId] || '') : g.special;
    if (!name) return (g.key === '#unknown' ? '在线 · 位置未公开' : '在线') + ' · ' + n;
    var acc = accessText(g.members[0].location);
    return name + (acc ? ' · ' + acc : '') + ' · ' + n;
  }

  function friendMatchesFilter(rec) {
    var f = state.fFilter;
    if (!f) return true;
    if (f === 'same') {
      var mine = myWorldId();
      return !!mine && worldIdOf(rec.location) === mine;
    }
    return String(rec.state || 'offline') === f;
  }

  /** 我当前所在的世界 id（同房间筛选用）；拿不到就空。 */
  function myWorldId() {
    var me = state.me;
    var loc = me && typeof me.location === 'string' ? me.location : '';
    return worldIdOf(loc) || '';
  }

  function renderFriends() {
    var box = $('friendList');
    if (!box) return;
    clear(box);

    var favSet = favoriteFriendSet();
    var all = [];
    for (var ri = 0; ri < state.roster.order.length; ri++) {
      var rec0 = state.roster.byId[state.roster.order[ri]];
      if (rec0 && friendMatchesFilter(rec0)) all.push(rec0);
    }
    var groups = friendGroups(state.search.friends, all);
    var total = 0;
    for (var i = 0; i < groups.length; i++) {
      var g = groups[i];
      /* 有世界 id 的分组头可点开世界详情（screens_tools.openWorld） */
      if (g.worldId && window.__SCREENS_TOOLS__ && window.__SCREENS_TOOLS__.openWorld) {
        var wh = document.createElement('button');
        wh.type = 'button';
        wh.className = 'roomhead';
        wh.textContent = groupHeadText(g);
        wh.addEventListener('click', (function (wid) {
          return function () { window.__SCREENS_TOOLS__.openWorld(wid); };
        })(g.worldId));
        add(box, wh);
      } else {
        add(box, E('div', 'roomhead', groupHeadText(g)));
      }

      for (var j = 0; j < g.members.length; j++) {
        var rec = g.members[j];
        total++;
        /* 「隐藏昵称」开关在这儿落地；以前直接取 displayName，开关只能是个摆设 */
        var name = displayNameOf(rec);

        var card = E('button', 'card');
        card.type = 'button';
        card.setAttribute('data-open-detail', '');

        var row = E('div', 'friendrow');
        var wrap = E('span', 'dotwrap');
        add(wrap, avatarEl(name, 'av' + ((total % 6) + 1),
          friendAvatarUrl(rec) || state.avatarUrls[rec.id]));
        /* 状态灯：颜色 = VRChat status 五档，实心 = 在游戏里 / 空心 = 只在网页端 */
        var dotCls = statusDotClass(rec);
        if (dotCls) {
          var dotEl = E('span', dotCls);
          dotEl.title = statusTip(rec);
          dotEl.setAttribute('aria-label', statusTip(rec));
          add(wrap, dotEl);
        }
        add(row, wrap);

        var body = E('div', 'feedrow__body');
        var nameRow = E('div', 'name');
        add(nameRow, document.createTextNode(name));
        if (favSet[rec.id]) add(nameRow, E('span', 'favstar', '★ 特别关心'));
        add(body, nameRow);

        var sub = '';
        if (g.offline) {
          var last = timeText(rec.last_activity, null);
          sub = last ? last + ' 前活跃'
            : (rec.degraded ? trustText(rec.tags) : '');
        } else {
          var place = g.worldId ? (state.worldNames[g.worldId] || '') : (g.special || '');
          var acc = accessText(rec.location);
          /* 「显示实例 ID」在这儿落地。只拼实例号，不拼整串 location */
          var num = PREFS.instanceId ? instanceNumber(rec.location) : '';
          sub = place
            ? ('在 ' + place + (acc ? ' · ' + acc : '') + (num ? ' · #' + num : ''))
            : '位置未公开';
        }
        var subEl = E('div', 'body', sub);
        subEl.style.marginTop = '0';
        add(body, subEl);
        add(row, body);

        if (!g.offline) {
          var plat = platformText(rec.platform);
          if (plat) add(row, E('span', 'plat', plat));
        }
        add(card, row);
        bindDetail(card, rec.id, name);
        add(box, card);
      }
    }

    noticeFor($('friendEmpty'), total, '好友');
    /* 详情列占位的实时统计（在线 / 活跃 / 私密房间） */
    var ph = $('phStats');
    if (ph) {
      clear(ph);
      var nOnline = 0, nActive = 0, nSame = 0;
      var mine2 = myWorldId();
      for (var pi = 0; pi < state.roster.order.length; pi++) {
        var pr = state.roster.byId[state.roster.order[pi]];
        if (!pr) continue;
        var pst = String(pr.state || 'offline');
        if (pst === 'online') nOnline++;
        else if (pst === 'active') nActive++;
        if (mine2 && worldIdOf(pr.location) === mine2) nSame++;
      }
      var stats2 = [
        [String(state.roster.order.length), '好友总数'],
        [String(nOnline + nActive), '在线好友'],
        [String(nSame), '同房间']
      ];
      for (var si2 = 0; si2 < stats2.length; si2++) {
        var st2 = E('div', 'stat');
        add(st2, E('b', '', stats2[si2][0]));
        add(st2, E('span', '', stats2[si2][1]));
        add(ph, st2);
      }
    }
    /* ⚠️ 「筛选/搜索没匹配到」和「确实没有」是两回事，文案必须分开 ——
       否则用户会以为好友没了（0.2.18 实测：同房间档在离线时会白屏误导）。 */
    if (total === 0 && state.loaded
        && (state.fFilter || state.search.friends) && state.roster.order.length) {
      var fe = $('friendEmpty');
      if (fe) { fe.textContent = '没有符合筛选条件的好友。换个筛选或清空搜索试试。'; }
    }
  }

  /* ---- 3. 主页 ---- */

  function statCell(value, label) {
    var card = E('div', 'card card--low stat card--static');
    add(card, E('b', '', String(value)));
    add(card, E('span', '', label));
    return card;
  }

  function worldCard(opts) {
    var card = E('button', 'card card--low worldcard');
    card.type = 'button';
    var thumb = E('span', 'worldcard__thumb');
    thumb.textContent = safe(opts.thumb, '?');   // 多行由 CSS 处理，这里只给两段
    add(card, thumb);
    var body = E('span', 'worldcard__body');
    add(body, E('span', 'worldcard__name', opts.name, '未知世界'));
    if (opts.meta) add(body, E('span', 'worldcard__meta', opts.meta));
    add(card, body);
    if (opts.stack && opts.stack.length) {
      var stack = E('span', 'avstack');
      for (var i = 0; i < opts.stack.length && i < 3; i++) {
        add(stack, E('span', 'avatar av' + ((i % 6) + 1), initial(opts.stack[i])));
      }
      add(card, stack);
    }
    return card;
  }

  /** 名字太长会把 56px 的方块撑破；取前两个词、每词截断。 */
  function thumbText(name) {
    var s = safe(name, '');
    if (!s) return '?';
    var parts = s.split(/\s+/).filter(Boolean);
    if (parts.length >= 2) return parts[0].slice(0, 6) + '\n' + parts[1].slice(0, 6);
    return s.slice(0, 12);
  }

  /* 主页「查看全部」：把被截断的剩余好友聚集地也展开。默认只露前 4 个，
     点了展开全部并切换按钮文案（2026-09-30 把原来的死按钮接成真行为）。 */
  var homePlacesExpanded = false;

  function renderHome() {
    /* 最新动态 —— 借动态流的前两条（不再单独打一次接口） */
    var latest = $('homeLatest');
    if (latest) {
      clear(latest);
      var rows = state.feedRows.slice(0, 2);
      for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        var name = safe(row.displayName, '') || '未知好友';
        var card = E('button', 'card card--low');
        card.type = 'button';
        card.style.padding = '10px 14px';
        card.setAttribute('data-goto', 'feed');
        var line = E('div', 'rowline');
        add(line, E('span', 'name', name, '未知好友'));
        var kind = feedKindText(row.type);
        if (kind) add(line, E('span', 'kind', kind));
        add(card, line);
        var bodyText = feedBody(row);
        if (bodyText) add(card, E('div', 'body', bodyText));
        card.addEventListener('click', function () { goto('feed'); });
        add(latest, card);
      }
    }

    /* 好友聚集地 —— 从名单现算，不是另一次接口 */
    var places = $('homePlaces');
    var groups = [];
    if (places) {
      clear(places);
      var gs = friendGroups();
      for (var k = 0; k < gs.length; k++) {
        if (!gs[k].offline && gs[k].worldId) groups.push(gs[k]);
      }
      var cap = homePlacesExpanded ? groups.length : 4;
      for (var m = 0; m < groups.length && m < cap; m++) {
        var g = groups[m];
        var wname = state.worldNames[g.worldId] || '';
        if (!wname) continue;
        var names = g.members.map(function (r) { return r.displayName || ''; });
        add(places, worldCard({
          thumb: thumbText(wname),
          name: wname,
          meta: g.members.length + ' 位好友 · ' + (accessText(g.members[0].location) || '位置未公开'),
          stack: names
        }));
      }
      var cnt = $('homePlacesCount');
      if (cnt) {
        cnt.textContent = groups.length ? (groups.length + ' 个房间') : '暂无';
      }
      /* 「查看全部」只在确实有被截断的内容时出现；展开后变成「收起」。 */
      var more = $('homePlacesMore');
      if (more) {
        if (groups.length > 4) {
          show(more, true);
          more.textContent = homePlacesExpanded ? '收起' : '查看全部';
        } else {
          show(more, false);
        }
      }
    }

    /* 最近浏览 —— 服务端浏览历史，标题与次数都是真的 */
    var recent = $('homeRecent');
    if (recent) {
      clear(recent);
      var items = state.browse.filter(function (it) {
        return it && it.entityKind === 'world' && it.title;
      }).slice(0, 6);
      for (var b = 0; b < items.length; b++) {
        var it = items[b];
        var c = worldCard({
          thumb: thumbText(it.title),
          name: it.title,
          meta: it.viewCount > 1 ? ('浏览 ' + it.viewCount + ' 次') : '浏览过'
        });
        c.style.width = '220px';
        add(recent, c);
      }
      if (!items.length) {
        add(recent, E('p', 'emptynote', state.error ? ('浏览记录没读到：' + state.error) : '还没有浏览记录。'));
      }
    }

    /* 数据统计 —— 全部由已取回的数据现算。动态类的计数会标出统计窗口。 */
    var grid = $('homeStats');
    if (grid) {
      clear(grid);
      var online = 0;
      var offline = 0;
      for (var f = 0; f < state.roster.order.length; f++) {
        var rec = state.roster.byId[state.roster.order[f]];
        if (!rec) continue;
        if (String(rec.state) === 'offline') offline++; else online++;
      }
      var byType = {};
      for (var t = 0; t < state.feedRows.length; t++) {
        var ty = String(state.feedRows[t].type || '');
        byType[ty] = (byType[ty] || 0) + 1;
      }
      var views = 0;
      var browseWorlds = 0;
      for (var v = 0; v < state.browse.length; v++) {
        if (state.browse[v] && state.browse[v].entityKind === 'world') {
          browseWorlds++;
          views += Number(state.browse[v].viewCount || 0);
        }
      }
      var feedLabel = state.feedHasMore ? ('动态（近 ' + state.feedRows.length + ' 条）') : '动态总数';
      [
        [state.roster.order.length, '好友总数'],
        [online, '在线好友'],
        [offline, '离线好友'],
        [groups.length, '好友聚集地'],
        [state.feedRows.length, feedLabel],
        [byType.GPS || 0, '位置变化'],
        [byType.Status || 0, '状态变化'],
        [byType.Avatar || 0, '换了模型'],
        [byType.Online || 0, '好友上线'],
        [byType.Offline || 0, '好友下线'],
        [state.fav.rows.world.length, '收藏的世界'],
        [state.fav.rows.avatar.length, '收藏的模型'],
        [state.fav.rows.friend.length, '收藏的好友'],
        [browseWorlds, '浏览过的世界'],
        [views, '累计浏览次数'],
        [state.myAvatars.length, '我的模型']
      ].forEach(function (pair) { add(grid, statCell(pair[0], pair[1])); });
    }
  }

  /* ---- 4. 收藏 ---- */

  function favoriteName(kind, row) {
    if (kind === 'world') {
      var wid = row.worldId;
      return state.favNames.world[wid] || state.worldNames[wid] || '';
    }
    if (kind === 'avatar') return state.favNames.avatar[row.avatarId] || '';
    var rec = state.roster.byId[row.userId];
    return (rec && (rec.displayName || rec.username)) || '';
  }


  /** 群组收藏夹：集合名做组头，组员是群组（名字查 favNames.group，裸 id 不上屏）。 */
  function renderSavedGroups(body) {
    var cols = state.savedGroups;
    if (state.savedGroupsError) {
      /* ⚠️ 读取失败 ≠ 没有 —— 失败要说失败原因，别把用户引去建收藏夹 */
      add(body, E('p', 'emptynote', '群组收藏夹没读到：' + state.savedGroupsError));
      return;
    }
    if (!cols.length) {
      add(body, E('p', 'emptynote', '还没有群组收藏夹（桌面端的收藏里建一个就有了）。'));
      return;
    }
    for (var g = 0; g < cols.length; g++) {
      var col = cols[g];
      var head = E('div', 'grouphead');
      add(head, E('span', '', safe(col.name, '') || '未命名收藏夹'));
      var ids = col.groupIds || [];
      add(head, E('span', '', String(ids.length)));
      add(body, head);
      if (!ids.length) {
        add(body, E('p', 'emptynote', '这个收藏夹还是空的。'));
        continue;
      }
      var grid = E('div', 'favgrid');
      for (var k = 0; k < ids.length; k++) {
        var gid = ids[k];
        var name = state.favNames.group[gid] || '';
        var cell = E('div', 'card card--low favcell');
        var thumb = E('span', 'worldcard__thumb');
        thumb.textContent = name ? thumbText(name) : '群组';
        add(cell, thumb);
        add(cell, E('span', 'worldcard__name', name || '（群组名未缓存）'));
        add(grid, cell);
      }
      add(body, grid);
    }
  }

  function renderFavorites() {
    var body = $('favBody');
    if (!body) return;
    clear(body);

    var kind = state.fav.kind;
    if (kind === 'group') { renderSavedGroups(body); return; }
    var rows = state.fav.rows[kind] || [];

    // 按收藏夹分组（服务端给的 groupName 就是收藏夹名；groupKey 是 "world:worlds1" 原键）
    var groups = [];
    var index = {};
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      var gname = safe(row.groupName, '') || '未分组';
      if (!index[gname]) {
        index[gname] = { name: gname, key: String(row.groupKey || ''), items: [] };
        groups.push(index[gname]);
      }
      index[gname].items.push(row);
    }

    for (var g = 0; g < groups.length; g++) {
      var grp = groups[g];
      var head = E('div', 'grouphead');
      add(head, E('span', '', grp.name, '未分组'));
      add(head, E('span', '', String(grp.items.length)));
      /* 组头「编辑」：改名/可见性。键在才给（"未分组"没有可编辑的夹）。 */
      if (grp.key) {
        var eb = document.createElement('button');
        eb.type = 'button';
        eb.className = 'chip';
        eb.textContent = '编辑';
        (function (kind2, key2) {
          eb.addEventListener('click', function () {
            /* key 形如 "world:worlds1"，夹键取最后一个 ":" 之后 */
            var tag = key2.indexOf(':') >= 0 ? key2.slice(key2.lastIndexOf(':') + 1) : key2;
            openFavGroupEdit(kind2, tag);
          });
        })(kind, grp.key);
        add(head, eb);
      }
      add(body, head);

      var grid = E('div', 'favgrid');
      for (var k = 0; k < grp.items.length; k++) {
        var item = grp.items[k];
        var name = favoriteName(kind, item);
        var cell = E('div', 'card card--low favcell');
        var thumb = E('span', 'worldcard__thumb');
        thumb.textContent = name ? thumbText(name) : '未缓存';
        add(cell, thumb);
        add(cell, E('span', 'worldcard__name', name, '（名字未缓存）'));
        /* 「移除」：找得到 fvrt 对象 id 才显示；找不到（纯本地行/快照太旧）
           给一个提示而不是静默没反应。 */
        var fvrt = findFavFvrt(item);
        var rm = document.createElement('button');
        rm.type = 'button';
        rm.className = 'chip';
        rm.textContent = '移除';
        (function (fvrtId, label) {
          rm.addEventListener('click', function (ev) {
            ev.stopPropagation();
            if (!fvrtId) { toast('这条收藏暂时定位不到（快照可能旧了），刷新后再试。'); return; }
            favRemove(fvrtId, label);
          });
        })(fvrt, name);
        add(cell, rm);
        if (kind === 'world' && item.worldId
            && window.__SCREENS_TOOLS__ && window.__SCREENS_TOOLS__.openWorld) {
          cell.style.cursor = 'pointer';
          cell.addEventListener('click', (function (wid) {
            return function () { window.__SCREENS_TOOLS__.openWorld(wid); };
          })(item.worldId));
        }
        add(grid, cell);
      }
      add(body, grid);
    }

    // 当前这一档拿不到名字时，得说清楚是"没有"还是"没拉到"
    var missing = 0;
    for (var m = 0; m < rows.length; m++) {
      if (!favoriteName(kind, rows[m])) missing++;
    }
    var note = $('favEmpty');
    if (note) {
      if (!state.loaded) {
        note.textContent = '正在读取收藏…';
        note.hidden = false;
      } else if (!rows.length) {
        note.textContent = state.fav.loaded[kind]
          ? '这一档还没有收藏。'
          : ('收藏没读到：' + (state.error || '未知错误'));
        note.hidden = false;
      } else if (missing) {
        note.textContent = '有 ' + missing + ' 项的名字服务器还没缓存（只存了 id，不上屏）。';
        note.hidden = false;
      } else {
        note.hidden = true;
        note.textContent = '';
      }
    }
  }

  var FAV_LABEL = { world: '世界', avatar: '模型', friend: '好友', group: '群组' };

  function setFavKind(kind) {
    if (!FAV_LABEL[kind]) return;
    state.fav.kind = kind;
    if (kind === 'group' && !state.savedGroups.length) loadSavedGroups();
    var chips = document.querySelectorAll('#favChips .chip');
    for (var i = 0; i < chips.length; i++) {
      chips[i].classList.toggle('is-on', chips[i].getAttribute('data-fav-kind') === kind);
    }
    renderFavorites();
  }

  /* ---- 5. 个人 ---- */

  function currentAvatarName(me) {
    if (!me) return '';
    // 名字只在 myAvatars 里有；用当前模型的 id 去配。配不上就空着，不猜。
    var want = me.currentAvatar || '';
    for (var i = 0; i < state.myAvatars.length; i++) {
      var a = state.myAvatars[i];
      if (a && want && a.id === want) return safe(a.name, '');
    }
    return '';
  }

  function serverAddressText() {
    var cfg = VRCX.config && VRCX.config();
    var raw = (cfg && cfg.serverAddress) || '';
    if (!raw) return '未配置';
    var m = /^https?:\/\/([^/]+)/i.exec(raw);
    return m ? m[1] : raw;
  }

  /* ---------------------------------------------------------------
     服务器配置的展示白名单
     服务端 `config_list_values` 返回的是**它自己的配置转储**，不是给用户看的
     设置项。直接全量铺屏 = "选项太多太乱"，而且 `vrcx_savedcredentials` 这类
     键是账号凭据的密文块 —— 摆到界面上本身就不该。
     规则：认识的键给中文标签；其余收进折叠区；凭据类**永远不渲染**。
     --------------------------------------------------------------- */
  var CFG_LABELS = {
    'vrcx_lastuserloggedin': '上次登录的账号',
    'vrcx_0_legacyownershipdecided': '数据归属已判定'
  };
  /* 前缀匹配：`vrcx_friendloginit_usr_xxx` 这种把 id 拼进键名的 */
  var CFG_LABEL_PREFIX = [
    ['vrcx_friendloginit_', '好友日志已初始化']
  ];
  var CFG_SECRET = /credential|token|password|secret|cookie|apikey|api_key|savedcredential/i;

  function cfgLabelOf(key) {
    var k = String(key || '').toLowerCase();
    if (CFG_LABELS[k]) return CFG_LABELS[k];
    for (var i = 0; i < CFG_LABEL_PREFIX.length; i++) {
      if (k.indexOf(CFG_LABEL_PREFIX[i][0]) === 0) return CFG_LABEL_PREFIX[i][1];
    }
    return '';
  }

  /**
   * 配置键 / 值的显示文本 —— **先掩码，再交给 `safe()`**。
   *
   * ⚠️⚠️ 不能直接写 `safe(value, '')`。`safe()` 是中央的裸 id 闸门：
   *   `if (ID_RE.test(s)) return fallback || '';`  —— 整串**含** id 就整条丢掉。
   * 而 `vrcx_lastuserloggedin` 的值恰好就是一个 `usr_` id，于是这行只能显示
   * 「（空）」（无头实测原话：`<span class="kv__v">（空）</span>`）。
   * 它确实没泄漏，但也没信息。这里要的是**掩码**，不是丢弃。
   * 掩码结果里的 `•` 不匹配 `ID_RE`，所以还能安全地再过一遍 `safe()`。
   * 键同理：`vrcx_friendloginit_usr_xxx` 这种把 id 拼进键名的会被整条吃掉。
   */
  var CFG_ID_RE = /(usr|wrld|avtr|grp)_[A-Za-z0-9-]+/g;

  function cfgText(value, maxLen) {
    var v = (value === null || value === undefined) ? '' : String(value);
    if (!v) return '';
    v = v.replace(/\s+/g, ' ').replace(CFG_ID_RE, '$1_••••••••');
    if (maxLen && v.length > maxLen) v = v.slice(0, maxLen - 1) + '…';
    return v;
  }

  /** 值可能是一整坨 JSON（TTS 过滤器那种），压成一行摘要再上屏。 */
  function cfgValueText(value) {
    return cfgText(value, 72) || '（空）';
  }

  /** 其余配置项：默认折叠。想看得自己点开 —— 需要它的人一定找得到。 */
  function renderCfgRest(host, rest) {
    var toggle = E('button', 'kv kv--act', '显示其余 ' + rest.length + ' 项（开发者用）');
    toggle.type = 'button';
    add(host, toggle);
    var box = E('div', '');
    box.hidden = true;
    for (var i = 0; i < rest.length; i++) {
      var row = E('div', 'kv');
      add(row, E('span', 'kv__k', cfgText(rest[i].key)));
      add(row, E('span', 'kv__v', cfgValueText(rest[i].value)));
      add(box, row);
    }
    add(host, box);
    toggle.addEventListener('click', function () {
      box.hidden = !box.hidden;
      toggle.textContent = box.hidden
        ? ('显示其余 ' + rest.length + ' 项（开发者用）')
        : ('收起其余 ' + rest.length + ' 项');
    });
  }

  /**
   * 「点开 → 就地改 → 保存」的 kv 行。
   *
   * 这三行（当前模型 / 人称代词 / 状态文字）以前都挂着一枚 chevron，却没有任何
   * 监听器 —— 用户的原话是"有个小箭头说明可以点开有二级菜单，但点了没反应"。
   * 假的可点暗示比不可点更糟：它让人反复去点同一个地方。
   *
   * 命令：`app__vrchat_current_user_update`，输入是
   *   { params: CurrentUserUpdateRequest }（`deny_unknown_fields`，
   *   字段名 camelCase，这里只用 `pronouns` / `statusDescription`）。
   * 它是 INPUT 命令，所以 `cmd` 那一层会包成 `{input:{params:{…}}}`。
   * 只挂一次监听（data-wired 打标），因为 renderProfile 会被反复调用。
   */
  function wireKvEdit(rowId, valueId, field, label) {
    var row = $(rowId);
    if (!row || row.getAttribute('data-wired')) return;
    row.setAttribute('data-wired', '1');
    row.addEventListener('click', function (e) {
      if (row.getAttribute('data-busy')) return;
      var node = $(valueId);
      if (!node) return;
      e.preventDefault();

      var form = E('div', 'kvedit');
      var input = document.createElement('input');
      input.className = 'kvedit__input';
      input.type = 'text';
      input.maxLength = 140;
      input.value = safe(node.textContent, '');
      input.placeholder = label;
      var save = E('button', 'btn btn--filled', '保存');
      var cancel = E('button', 'btn', '取消');
      save.type = 'button';
      cancel.type = 'button';
      var hint = E('p', 'emptynote', '');
      add(form, input);
      add(form, save);
      add(form, cancel);

      row.hidden = true;
      row.parentNode.insertBefore(form, row.nextSibling);
      row.parentNode.insertBefore(hint, form.nextSibling);
      input.focus();

      function close() {
        if (form.parentNode) form.parentNode.removeChild(form);
        if (hint.parentNode) hint.parentNode.removeChild(hint);
        row.hidden = false;
        row.removeAttribute('data-busy');
      }
      cancel.addEventListener('click', close);
      input.addEventListener('keydown', function (ev) {
        if (ev.key === 'Enter') { ev.preventDefault(); save.click(); }
        else if (ev.key === 'Escape') { ev.preventDefault(); close(); }
      });
      save.addEventListener('click', function () {
        var next = input.value.trim();
        if (next === safe(node.textContent, '')) { close(); return; }
        row.setAttribute('data-busy', '1');
        save.disabled = true;
        cancel.disabled = true;
        hint.textContent = '正在保存…';
        var params = {};
        params[field] = next;
        cmd('app__vrchat_current_user_update', { params: params }).then(function () {
          /* 服务端把更新后的 current user 回给我们，直接换掉 state.me 再重画，
             不额外打一次接口 —— 也避免"以为存了其实没存"。 */
          if (state.me && typeof state.me === 'object') state.me[field] = next;
          close();
          renderProfile();
        }).catch(function (err) {
          row.removeAttribute('data-busy');
          save.disabled = false;
          cancel.disabled = false;
          hint.textContent = '没保存成功：'
            + (window.VRCX ? window.VRCX.describeError(err) : '服务端拒绝了这次修改');
        });
      });
    });
  }

  function renderProfile() {
    var me = state.me;
    renderProfileStatic();
    if (!me) return;

    var name = safe(me.displayName, '') || safe(me.username, '') || '';
    var handle = safe(me.username, '');

    var card = $('meCard');
    if (card) card.classList.remove('is-anon');
    var nameEl = $('meName');
    if (nameEl) nameEl.textContent = name || '已登录';
    var av = $('meAvatar');
    if (av) {
      av.textContent = initial(name);
      setAvatarImage(av, friendAvatarUrl(me));
    }
    /* 顶栏那颗 24px 的小头像同理 —— 只认 thumbnail 的话它永远是字母 */
    var topAv = $('topAvatar');
    if (topAv) setAvatarImage(topAv, friendAvatarUrl(me));
    var userEl = $('meUser');
    if (userEl) {
      userEl.textContent = handle ? '@' + handle : '';
      show(userEl, !!handle);
    }
    var st = $('meStatus');
    if (st) { st.hidden = true; st.textContent = ''; }

    function kvRow(id, value, mono) {
      var node = $(id);
      if (!node) return;
      var parent = node.closest ? node.closest('.kv') : null;
      if (!value) { if (parent) parent.hidden = true; return; }
      if (parent) parent.hidden = false;
      node.textContent = value;
      if (mono) node.style.fontFamily = 'var(--font-mono)';
    }

    kvRow('kvAvatar', currentAvatarName(me));
    var homeWid = worldIdOf(me.homeLocation);
    kvRow('kvHomeWorld', homeWid ? (state.worldNames[homeWid] || '') : '');
    kvRow('kvPronouns', safe(me.pronouns, ''));
    kvRow('kvStatusText', safe(me.statusDescription, '') || safe(me.status, ''));
    kvRow('kvTrust', trustText(me.tags));
    wireKvEdit('kvPronounsRow', 'kvPronouns', 'pronouns', '人称代词');
    wireKvEdit('kvStatusRow', 'kvStatusText', 'statusDescription', '状态文字');
    kvRow('kvServerAddr', serverAddressText(), true);

    // 我的模型
    var head = $('meModelsHead');
    var grid = $('meModelsGrid');
    if (grid) {
      clear(grid);
      var list = state.myAvatars.slice(0, 12);
      for (var i = 0; i < list.length; i++) {
        var a = list[i];
        var an = safe(a.name, '') || '未命名模型';
        var cell = E('div', 'card card--low favcell');
        var thumb = E('span', 'worldcard__thumb');
        thumb.textContent = thumbText(an);
        add(cell, thumb);
        add(cell, E('span', 'worldcard__name', an, '未命名模型'));
        add(grid, cell);

        /* ⚠️⚠️ 必须用 IIFE 把这一轮的 thumb/im/名字绑进去。
          `var` 只有函数作用域：直接在循环里 .then(function (data) { … thumb … })
          的话，回调拿到的是**最后一轮**的 thumb —— 于是每张图都往最后一个格子写，
          前面的格子永远是一张没有 src 的 <img>（看起来就是"没有封面"）。
          兜底那句 `thumb.textContent = thumbText(an)` 更狠：它会把最后一个格子
          的图抹掉换成名字。tools 那页的同名函数是带 IIFE 的，这页漏了。 */
        (function (thumbEl, nameText, avatar) {
          var url = safe(avatar.thumbnailImageUrl, '') || safe(avatar.imageUrl, '');
          if (!url) return;                       /* 没 URL 就保留首字母/名字块 */
          thumbEl.textContent = '';
          var im = document.createElement('img');
          im.alt = '';
          thumbEl.appendChild(im);
          loadImage(url).then(function (data) {
            if (!thumbEl.isConnected) return;
            if (data) { im.src = data; return; }
            /* 图没拉到：把占位 <img> 撤回名字块，别留一个破图标 */
            if (im.parentNode) im.parentNode.removeChild(im);
            thumbEl.textContent = nameText;
          });
        })(thumb, thumbText(an), a);
      }
      if (head) {
        var cnt = $('meModelsCount');
        if (cnt) cnt.textContent = String(state.myAvatars.length);
        show(head, list.length > 0);
      }
      show(grid, list.length > 0);
      var allBtn = $('meModelsAll');
      if (allBtn) show(allBtn, state.myAvatars.length > 0);
    }
  }

  /** 个人页里**不依赖登录态**的部分：服务器地址行。me=null（会话失效）时
      renderProfile 也会走到 —— 否则地址行冻结在开机渲染（实测一直"未配置"
      而顶栏已连上）。配置区渲染也在这里触发（见 renderProfileConfig）。 */
  function renderProfileStatic() {
    var addrNode = $('kvServerAddr');
    if (addrNode) {
      addrNode.textContent = serverAddressText();
      addrNode.style.fontFamily = 'var(--font-mono)';
      var addrRow = addrNode.closest ? addrNode.closest('.kv') : null;
      if (addrRow) addrRow.hidden = false;
    }
    renderProfileConfig();
  }

  function renderProfileConfig() {
    var cfgHead = $('meConfigHead');
    var cfgBox = $('meConfig');
    if (!cfgBox) return;
    clear(cfgBox);
    var allCfg = (state.configs || []).filter(function (c) {
      return c && c.key && !CFG_SECRET.test(c.key);
    });
    show(cfgHead, allCfg.length > 0);
    show(cfgBox, allCfg.length > 0);
    var named = [];
    var rest = [];
    for (var ci = 0; ci < allCfg.length; ci++) {
      if (cfgLabelOf(allCfg[ci].key)) named.push(allCfg[ci]); else rest.push(allCfg[ci]);
    }
    for (var ni = 0; ni < named.length; ni++) {
      var row = E('div', 'kv');
      add(row, E('span', 'kv__k', cfgLabelOf(named[ni].key)));
      add(row, E('span', 'kv__v', cfgValueText(named[ni].value)));
      add(cfgBox, row);
    }
    if (!named.length) {
      add(cfgBox, E('p', 'emptynote', '这台服务器没有需要在这里显示的配置项。'));
    }
    if (rest.length) {
      renderCfgRest(cfgBox, rest);
    }
  }

  /* ---- 详情面板 ---- */

  var detailTarget = null;
  var detailOpen = false;

  /** 详情面板可见性统一走这里：只有用户点开过好友才允许它出现。
      双栏档的 .detail 是常驻样式（display:flex），会盖掉 UA 的 [hidden]，
      所以 hidden 属性必须由这里显式管理 + CSS !important 兜底。 */
  function applyDetailVis() {
    var d = $('detail');
    if (d) d.hidden = !detailOpen;
  }

  function setAppOpen(open) {
    var app = document.getElementById('app');
    if (app) app.classList.toggle('detail-open', open);
  }

  function closeDetail() {
    detailOpen = false;
    setAppOpen(false);
    applyDetailVis();
  }

  /* ⚠️ 已删除 floatDetail()（2026-09-23）。它当年为了解决「抽屉底部溢出视口」
     而把 #detail 从 .app__content 挪到 body 直下并加 position:fixed。代价是致命的：
     #detail 一离开 .app__content 就不是网格项了，app.css 里 840dp+ 那套
     「主从双栏 —— 详情常驻右栏」(@container app min-width:840px and orientation:landscape)
     永远命中不了它，于是平板上点好友只能弹整屏宽的底部抽屉（实测矩形 x=0 w=1090、
     height=86vh），而右边那一列被空占着。
     保留 #detail 在原生位置（.app__content 的直接子级）后：
       · 平板横屏 / 大屏：由上面那条容器查询渲染成**常驻右栏**（用户要的形态）；
       · 窄屏 / 竖屏：仍是 CSS 里那套底部抽屉兜底，也不再重复出现两份。
     底部溢出那个原始问题不复存在 —— 右栏是 position:static 的正常流，不会溢出容器。 */

  function bindDetail(card, userId, fallbackName) {
    card.addEventListener('click', function () { fillDetail(userId, fallbackName); });
  }

  /**
   * 用一条名单记录填详情面板。
   * 名单里没有被点的人（比如动态流里的老动态，人已不是好友）时，
   * 仍然要能开面板 —— 只是除名字外没别的可填，那就只填名前的那部分。
   */
  function fillDetail(userId, fallbackName) {
    detailOpen = true;
    setAppOpen(true);
    applyDetailVis();
    var rec = (userId && state.roster.byId[userId]) || null;
    var name = safe(rec && rec.displayName, '') || safe(rec && rec.username, '') || safe(fallbackName, '') || '未知用户';
    detailTarget = rec ? { id: userId, name: name } : { name: name };

    var av = $('dAvatar');
    if (av) {
      av.textContent = initial(name);
      setAvatarImage(av, friendAvatarUrl(rec) || state.avatarUrls[userId] || '');
    }
    var nameEl = $('dName');
    if (nameEl) nameEl.textContent = name;

    var pres = [];
    if (rec) {
      var stCode = safe(rec.state, 'offline');
      var stStatus = userStatusFromValue(rec.status);
      var head = STATUS_ZH[stStatus] || STATE_ZH[stCode] || '离线';
      /* state==='active' = 人只在网页/APP 挂着、不在游戏里。这一档必须明说，
         否则「欢迎加入」配一颗空心灯会被当成点错了。
         ⚠️ 也不再回退到 rec.status 原文 —— 那是英文（join me），
         会直接落到界面上（2026-09-30 的同一批修复）。 */
      if (stCode === 'active' && stStatus !== 'offline') head += '（网页端）';
      pres.push(head);
      var p = platformText(rec.platform);
      if (p) pres.push(p);
      var sd = safe(rec.statusDescription, '');
      if (sd) pres.push(sd);
    }
    var p1 = $('dPresence');
    if (p1) { p1.textContent = pres.join(' · '); show(p1, pres.length > 0); }
    var p2 = $('dPronouns');
    if (p2) {
      var pr = safe(rec && rec.pronouns, '');
      p2.textContent = pr;
      show(p2, !!pr);
    }

    var trust = $('dTrust');
    if (trust) {
      var tt = trustText(rec && rec.tags);
      trust.textContent = tt || (rec ? '好友' : '动态里出现过的用户');
      var row = trust.closest ? trust.closest('.trustrow') : null;
      if (row) row.hidden = false;
      var badge = $('dFriendBadge');
      if (badge) badge.hidden = !(rec && rec.id);
    }

    function section(labelId, textId, value, fallbackText) {
      var label = $(labelId);
      if (!label) return;
      var wrap = label.closest ? label.closest('.detail__section') : null;
      var text = $(textId);
      if (!value) {
        if (wrap) wrap.hidden = true;
        return;
      }
      if (wrap) wrap.hidden = false;
      if (text) text.textContent = value;
    }

    section('dStatusLabel', 'dStatusText', rec ? (safe(rec.statusDescription, '') || safe(rec.status, '')) : '');
    var wid = rec ? worldIdOf(rec.location) : '';
    var place = wid ? (state.worldNames[wid] || '') : (rec ? specialPlace(rec.location) : '');
    var acc = rec ? accessText(rec.location) : '';
    if (place && acc) place += ' · ' + acc;
    section('dWorldLabel', 'dWorldText', place);
    section('dBioLabel', 'dBioText', rec ? safe(rec.bio, '') : '');

    /* 键值行：只画**确实有值**的，缺的整行不出现 —— 空行会被读成"这个字段是空的"，
       而真相往往是"服务器这次没带"。 */
    var kv = $('dKv');
    if (kv) {
      clear(kv);
      var pairs = [];
      if (rec) {
        var plat = platformText(rec.platform);
        if (plat) pairs.push(['平台', plat]);
        var langs = (rec.tags || []).filter(function (t) {
          return typeof t === 'string' && t.indexOf('language_') === 0;
        }).map(function (t) { return t.slice(9); });
        if (langs.length) pairs.push(['使用的语言', langs.join(', ')]);
        var joined = dateText(rec.date_joined);
        if (joined) pairs.push(['加入时间', joined]);
        var lastLogin = timeText(rec.last_login, null);
        if (lastLogin) pairs.push(['最近登录', lastLogin]);
        var lastAct = timeText(rec.last_activity, null);
        if (lastAct) pairs.push(['最近活动', lastAct]);
        var an = safe(rec.currentAvatarName, '');
        if (an) pairs.push(['当前模型', an]);
        pairs.push(['关系', '好友']);
      }
      for (var i = 0; i < pairs.length; i++) {
        var row = E('div', 'kv');
        add(row, E('span', 'kv__k', pairs[i][0]));
        add(row, E('span', 'kv__v', pairs[i][1]));
        add(kv, row);
      }

      /* 用户 ID：屏幕上永远是掩码。真实 id 只活在闭包里，"复制"把它交给剪贴板 ——
         想拿 id 的人拿得到，**屏幕和 DOM 里都不留**（见文件头纪律）。 */
      if (rec && rec.id) {
        var idRow = E('div', 'kv');
        add(idRow, E('span', 'kv__k', '用户 ID'));
        var idVal = E('span', 'kv__v', maskUserId(rec.id));
        idVal.style.fontFamily = 'var(--font-mono)';
        add(idRow, idVal);
        var copy = E('span', 'plat', '复制');
        copy.setAttribute('role', 'button');
        copy.style.cursor = 'pointer';
        (function (realId) {
          copy.addEventListener('click', function () { copyText(realId, copy); });
          idRow.addEventListener('click', function () { copyText(realId, copy); });
        })(rec.id);
        add(idRow, copy);
        add(kv, idRow);
      }
    }

    /* 最近动态：从已经取回来的动态流里筛这个人 —— 不再打接口 */
    var feed = $('dFeed');
    if (feed) {
      clear(feed);
      var mine = [];
      for (var f = 0; f < state.feedRows.length && mine.length < 3; f++) {
        var row2 = state.feedRows[f];
        if (userId && row2.userId === userId) mine.push(row2);
      }
      for (var k = 0; k < mine.length; k++) {
        var r = mine[k];
        var card = E('div', 'card card--low card--static');
        card.style.padding = '10px 14px';
        var line = E('div', 'rowline');
        var when = timeText(r.created_at, r.time);
        add(line, E('span', 'kind', when || feedKindText(r.type)));
        add(card, line);
        var bt = E('div', 'body', feedBody(r));
        bt.style.marginTop = '2px';
        add(card, bt);
        add(feed, card);
      }
      if (!mine.length) {
        add(feed, E('p', 'emptynote', state.error ? ('动态没读到：' + state.error) : '动态里还没有这个人。'));
      }
    }

    var actions = $('dActions');
    if (actions) show(actions, !!rec);

    /* 更多资料（曾用名 / 主页世界 / 简介链接 / 徽章 + 共同好友按钮）。
       ⚠️ 必须在**同步渲染全部结束之后**再调：它内部 await 一次真实接口，
       早调会让后面的同步填充和异步回包抢同一个 DOM 区域。 */
    loadExtraProfile(userId, rec);

    /* 通知其它模块「详情现在是谁」——screens_tools.js 借它拉这个人的
       动态记录（friend_log_history_query）。这里不发数据只发指针，
       记录的取数与渲染都在那边，screens.js 不为工具屏长逻辑。 */
    try {
      document.dispatchEvent(new CustomEvent('vrcx:detail', {
        detail: { id: userId || '', name: name }
      }));
    } catch (e) { /* 老 WebView 没有 CustomEvent 就没有记录区，不算致命 */ }
  }

  /* ==================================================================
     更多资料 —— 对齐原版 UserDialog 的 info / mutual 两个 Tab（2026-09-30）
     ------------------------------------------------------------------
     原版个人主页是 **10 个 Tab**（info / mutual / groups / worlds /
     favorite-worlds / avatars / instance-history / feed / activity / json）。
     瘦客户端塞不下 10 个 Tab，但其中两项的数据我们**本来就能拿到**，
     而以前一个都没接：
       · info 里的 曾用名 / 徽章 / 简介链接 / 主页世界
             → app__vrchat_user_profile_get（原版同一个口子
               `/users/{id}/profile?withGroupsAndWorlds=true`）
       · mutual → app__user_mutual_friends_list_get
     其余（groups / worlds / avatars）要各自的命令 + 列表 UI，留待后续；
     instance-history 更是桌面端**本地库**里的东西，瘦客户端根本没有这个数据源。

     ⚠️⚠️ 白名单渲染，**绝不 dump**。那个响应里还带着：
       email / obfuscatedEmail / discordId / steamId / oculusId / picoId / viveId /
       friendKey / twoFactorAuthEnabled，
       以及 `friends` / `onlineFriends` / `offlineFriends`（**我自己的好友 id 全表**）
       与 `presence.groups`（grp_ 裸 id）。
       这些一个都不许上屏、不许写进 DOM（见文件头的上屏纪律）。
     ================================================================== */

  /** 面板换人的世代号：异步回包回来时若已经换人，整包丢弃。 */
  var extraSeq = 0;

  function asArray(v) {
    return Object.prototype.toString.call(v) === '[object Array]' ? v : [];
  }

  function extraRow(box, label, text) {
    var row = E('div', 'kv');
    add(row, E('span', 'kv__k', label));
    add(row, E('span', 'kv__v', text));
    add(box, row);
    return row;
  }

  /** URL → 主机名。完整地址又长又占屏，而屏幕上只要认得出是哪家平台；
      完整地址仍然点一下就能复制走。 */
  function urlHost(u) {
    var s = String(u || '');
    var m = /^[a-z]+:\/\/([^\/?#]+)/i.exec(s);
    return (m ? m[1] : s).replace(/^www\./i, '');
  }

  function loadExtraProfile(userId, rec) {
    var sec = $('dExtraSection');
    var box = $('dExtra');
    var mBtn = $('dMutualBtn');
    var mBox = $('dMutualBox');
    var seq = ++extraSeq;

    /* 换人就把共同好友区一起清掉 —— 不清的话上一个人的名单会留在下面，
       看起来像"这俩人的共同好友"，是实打实的错数据。 */
    if (box) clear(box);
    ensureMutualTarget(userId);
    if (mBox) { clear(mBox); show(mBox, false); }
    if (mBtn) { show(mBtn, false); mBtn.textContent = '共同好友'; mBtn.disabled = false; }
    if (!sec || !box) return;
    if (!userId || !rec) { show(sec, false); return; }

    show(sec, true);
    add(box, E('p', 'emptynote', '正在读取资料…'));

    cmd('app__vrchat_user_profile_get', { userId: userId, asSelf: false })
      .then(function (res) {
        if (seq !== extraSeq) return;             /* 面板已换人：这包作废 */
        var prof = unwrap(res);
        /* 非好友、或对方把资料设为私密 → 这里会是 403，unwrap 直接抛，
           整段收起。不摆一个空壳区块。 */
        if (!prof || typeof prof !== 'object') { show(sec, false); return; }
        fillExtra(box, prof, rec, userId, seq, mBtn, mBox);
      })
      .catch(function () {
        if (seq !== extraSeq) return;
        show(sec, false);
      });
  }

  function fillExtra(box, prof, rec, userId, seq, mBtn, mBox) {
    clear(box);
    var shown = 0;

    /* ---- 曾用名（原版 info tab 的一栏）----
       空数组是常态（多数人没改过名），别把"没有"渲染成一行空白。 */
    var past = [];
    var pdn = asArray(prof.pastDisplayNames);
    for (var i = 0; i < pdn.length; i++) {
      var pn = safe(pdn[i] && pdn[i].displayName, '');
      if (pn) past.push(pn);
    }
    if (past.length) {
      extraRow(box, '曾用名', past.length > 3
        ? past.slice(0, 3).join('、') + ' 等 ' + past.length + ' 个'
        : past.join('、'));
      shown++;
    }

    /* ---- 主页世界（原版 profileLinks 里的 Home World）----
       ⚠️ 只认 `wrld_` 开头的。`private` / `offline` / `hidden(usr_…)` 都是
       "不公开"的占位符：当世界名印出来是错的，`hidden(usr_…)` 更会顺带泄漏
       别人的 user id —— worldIdOf() 已经把这类挡掉了。 */
    var homeWid = worldIdOf(prof.homeLocation);
    if (homeWid) {
      var hn = state.worldNames[homeWid] || '';
      var homeRow = extraRow(box, '主页世界', hn || '读取中…');
      shown++;
      if (!hn) {
        loadWorldNames([homeWid]).then(function () {
          if (seq !== extraSeq || !homeRow.isConnected) return;
          var v = homeRow.lastChild;
          if (v) v.textContent = state.worldNames[homeWid] || '—';
        });
      }
    }

    /* ---- 简介链接（原版 bioLinks）：显示主机名，点一下复制完整地址 ---- */
    var links = [];
    var bl = asArray(prof.bioLinks);
    for (var j = 0; j < bl.length; j++) {
      if (typeof bl[j] === 'string' && bl[j].trim()) links.push(bl[j].trim());
    }
    for (var k = 0; k < links.length && k < 4; k++) {
      var lrow = E('div', 'kv kv--act');
      add(lrow, E('span', 'kv__k', k === 0 ? '简介链接' : ''));
      var lv = E('span', 'kv__v kv__v--link', urlHost(links[k]));
      add(lrow, lv);
      (function (row, url, node) {
        row.addEventListener('click', function () { copyText(url, node); });
      })(lrow, links[k], lv);
      add(box, lrow);
      shown++;
    }

    /* ---- 徽章（原版挂在资料头部的那些）----
       ⚠️ 原版只把 `showcased && !hidden` 的徽章摆出来
       （UserDialogHeaderBadges.tsx:307）。照抄 —— 否则等于把人家
       刻意藏起来的徽章翻出来给人看。 */
    var badges = [];
    var bs = asArray(prof.badges);
    for (var m = 0; m < bs.length; m++) {
      var b = bs[m];
      if (!b || typeof b !== 'object') continue;
      if (b.showcased !== true || b.hidden === true) continue;
      var bn = safe(b.badgeName, '') || safe(b.name, '');
      var bu = safe(b.badgeImageUrl, '') || safe(b.imageUrl, '') || safe(b.iconUrl, '');
      if (!bn && !bu) continue;
      badges.push([bn, bu]);
      if (badges.length >= 8) break;
    }
    if (badges.length) {
      var brow = E('div', 'kv');
      add(brow, E('span', 'kv__k', '徽章'));
      var strip = E('div', 'chipstrip');
      for (var q = 0; q < badges.length; q++) {
        var chip = E('span', 'chip chip--tag', badges[q][0] || '徽章');
        if (badges[q][1]) {
          (function (chipEl, url) {
            loadImage(url).then(function (data) {
              if (!data || !chipEl.isConnected) return;
              var im = document.createElement('img');
              im.src = data;
              im.alt = '';
              chipEl.insertBefore(im, chipEl.firstChild);
            });
          })(chip, badges[q][1]);
        }
        add(strip, chip);
      }
      add(brow, strip);
      add(box, brow);
      shown++;
    }

    /* 名单里的 bio 常是空的（roster 精简过）：资料接口有就补上主面板那一段。 */
    if (!safe(rec.bio, '')) {
      var pbio = safe(prof.bio, '');
      var bt = $('dBioText');
      var blab = $('dBioLabel');
      var bwrap = blab && blab.closest ? blab.closest('.detail__section') : null;
      if (pbio && bt) {
        bt.textContent = pbio;
        if (bwrap) bwrap.hidden = false;
      }
    }

    /* 共同好友是**按需**拉的（那命令会真的打一次 VRChat），所以按钮本身
       只要知道"这个人是谁"就能摆出来。 */
    if (mBtn) { show(mBtn, true); }
    if (!shown && !mBtn) show($('dExtraSection'), false);
  }

  /* ---- 共同好友（原版 mutual tab）---- */

  var mutualSeq = 0;
  var mutualTarget = '';

  /** 记录"共同好友区现在属于谁"，开面板换人时用来判断要不要清空。 */
  function ensureMutualTarget(userId) {
    if (mutualTarget === userId) return false;
    mutualTarget = userId || '';
    return true;
  }

  function loadMutualFriends() {
    var id = (detailTarget && detailTarget.id) || '';
    var btn = $('dMutualBtn');
    var box = $('dMutualBox');
    if (!id || !box) return;
    var seq = ++mutualSeq;

    if (btn) { btn.disabled = true; btn.textContent = '读取中…'; }
    show(box, true);
    clear(box);
    add(box, E('p', 'emptynote', '正在读取共同好友…'));

    /* ⚠️⚠️ 这一条的返回**不走 `{status, data}` 信封**！
       local.rs 用的是 `encode(local.user_mutual_friends_list(input))`，
       HTTP 层对 `CommandResult::Ok(value)` 直接 `Json(value)` 返回
       （api.rs:193）—— 所以拿到的是裸的 `{ rows: [...], persisted: bool }`。
       套 unwrap() 会把它当成"没有 data 字段"→ null → 又一个静默失效。 */
    cmd('app__user_mutual_friends_list_get', { userId: id }).then(function (res) {
      if (seq !== mutualSeq) return;
      if (btn) { btn.disabled = false; btn.textContent = '共同好友'; }
      var rows = asArray(res && res.rows);
      if (!rows.length) {
        clear(box);
        add(box, E('p', 'emptynote', '没有共同好友（或对方没公开）。'));
        return;
      }
      renderMutual(box, rows);
    }).catch(function (err) {
      if (seq !== mutualSeq) return;
      if (btn) { btn.disabled = false; btn.textContent = '共同好友'; }
      clear(box);
      /* 403/404 = 对方没开"共同好友"共享。这不是故障，别写成报错。 */
      var msg = window.VRCX ? VRCX.describeError(err) : String(err);
      add(box, E('p', 'emptynote',
        /403|404/.test(String(msg)) ? '对方没有公开共同好友。' : ('共同好友没读到：' + msg)));
    });
  }

  function renderMutual(box, rows) {
    clear(box);
    add(box, E('div', 'detail__label', '共同好友 ' + rows.length + ' 个'));
    for (var i = 0; i < rows.length && i < 30; i++) {
      var r = rows[i];
      if (!r || typeof r !== 'object') continue;
      var nm = safe(r.displayName, '') || safe(r.username, '') || '未知用户';
      var fid = typeof r.id === 'string' ? r.id : '';
      var row = E('div', 'kv kv--act');
      /* ⚠️ 不用 avatarEl()：它写死 `avatar--40`，再拼 `avatar--24` 就是
         两个尺寸类同时在身上，后定义的那个赢 —— 这里要的 24px 根本不会生效。 */
      var av = E('span', 'avatar avatar--24 av' + ((i % 6) + 1), initial(nm));
      setAvatarImage(av, friendAvatarUrl(r));
      add(row, av);
      add(row, E('span', 'kv__v', nm));
      if (fid) {
        (function (node, id, name) {
          node.addEventListener('click', function () { fillDetail(id, name); });
        })(row, fid, nm);
      }
      add(box, row);
    }
    if (rows.length > 30) {
      add(box, E('p', 'emptynote', '只显示前 30 个（共 ' + rows.length + ' 个）。'));
    }
  }

  function maskUserId(id) {
    var s = String(id || '');
    if (s.indexOf('usr_') === 0) return 'usr_••••••••';
    return '••••••••';
  }

  function copyText(text, node) {
    var done = function (ok) {
      if (!node) return;
      var old = node.textContent;
      node.textContent = ok ? '已复制' : '复制失败';
      setTimeout(function () { node.textContent = old; }, 1400);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
      return;
    }
    done(false);
  }

  /* ---- 详情面板上的三个动作 ---- */

  function myLocation() {
    var loc = state.me && state.me.location;
    return typeof loc === 'string' && loc.indexOf('wrld_') === 0 ? loc : '';
  }

  function boop() {
    if (!detailTarget || !detailTarget.id) return;
    /* 以前这里直接发 `{userId}`，服务端那个 emoji_id 一直是空的 —— 也就是永远
       只能戳一个"没有表情的白板"（服务端支持 emoji，只是我们没传）。
       现在改成先让用户挑表情（内置 65 个，VRChat 官方那一套）。 */
    openBoopPicker(detailTarget.id, detailTarget.name || '');
  }

  function requestInvite() {
    if (!detailTarget || !detailTarget.id) return;
    VRCX.command('app__vrchat_request_invite_send', {
      receiverUserId: detailTarget.id,
      params: {}
    }).then(function () { toast('已请求邀请'); })
      .catch(function (err) { toast('请求邀请失败：' + VRCX.describeError(err)); });
  }

  function inviteJoin() {
    if (!detailTarget || !detailTarget.id) return;
    var loc = myLocation();
    if (!loc) { toast('你现在不在世界实例里，邀请不了。'); return; }
    var worldId = worldIdOf(loc);
    var cut = loc.indexOf(':');
    var instanceId = cut > 0 ? loc.slice(cut + 1) : '';
    if (!worldId || !instanceId) { toast('读不到你当前的实例，邀请不了。'); return; }
    VRCX.command('app__notification_instance_invite_send', {
      receiverUserId: detailTarget.id,
      worldId: worldId,
      instanceId: instanceId,
      worldName: state.worldNames[worldId] || ''
    }).then(function () { toast('已发出邀请'); })
      .catch(function (err) { toast('邀请失败：' + VRCX.describeError(err)); });
  }

  /** 一句短暂的反馈。不能弹 alert（WebView 系统弹窗跟 App 无关），就用闸门的状态行。 */
  function toast(text) {
    var node = $('g-status');
    if (!node) return;
    node.textContent = safe(text, '');
    node.classList.remove('is-error');
    node.classList.add('is-ok');
    setTimeout(function () { node.textContent = ''; }, 2600);
  }

  /* ---- 切页 ---- */

  function goto(tab) {
    var pages = document.querySelectorAll('.page[data-screen]');
    for (var i = 0; i < pages.length; i++) {
      pages[i].hidden = pages[i].getAttribute('data-screen') !== tab;
    }
    var items = document.querySelectorAll('.navitem');
    for (var j = 0; j < items.length; j++) {
      items[j].classList.toggle('is-on', items[j].getAttribute('data-goto') === tab);
    }
    var wide = tab !== 'feed' && tab !== 'friends';
    var content = document.querySelector('.app__content');
    if (content) content.classList.toggle('is-wide', wide);
    applyDetailVis();
    var main = document.querySelector('.pane--main');
    if (main) main.scrollTop = 0;
  }

  /** 服务器配置（只读展示）：config_list_values 是平铺无参命令。 */
  function loadConfigs() {
    return cmd('app__config_list_values').then(function (rows) {
      state.configs = (rows || []).filter(function (r) {
        return r && typeof r === 'object' && r.key;
      });
      renderProfile();
    }).catch(function () { /* 配置读不到就算了，不影响别的 */ });
  }

  /* ------------------------------------------- 全局搜索 / 通知中心 / 骨架屏 */

  /** 服务端能力表里有没有这条命令（conn.js 从 /v1/health 转交）。
      探针 / 旧服务器没给这张表时按"不支持"处理，调用方走本地兜底。 */
  function serverSupports(name) {
    return Object.prototype.toString.call(state.supported) === '[object Array]'
      && state.supported.indexOf(name) >= 0;
  }

  /**
   * 红线兜底：任何要上屏的第三方文本，先把可能夹带的裸 id 洗掉
   * （quick_search 的 memo/note 是用户自由文本，理论上什么都可能有）。
   * 兜底**绝不返回入参本身** —— 洗完是空的就显示空。
   */
  function scrubIds(text) {
    var s = String(text || '');
    if (/(?:usr|wrld|avtr|grp)_[A-Za-z0-9]/.test(s)) {
      s = s.replace(/(?:usr|wrld|avtr|grp)_[A-Za-z0-9]{3,}/g, '…');
    }
    return s;
  }

  /* ---------- 全局搜索（服务端 app__quick_search_query） ---------- */

  var SEARCH_DEBOUNCE = 300;
  var searchTimer = 0;
  var searchSeq = 0;   // 竞态保护：只吃最后一次输入的结果

  function openSearchPanel() {
    var p = $('searchPanel');
    if (!p) return;
    show(p, true);
    var input = $('searchInput');
    if (input) { input.value = ''; input.focus(); }
    var res = $('searchResults');
    if (res) clear(res);
    /* 作废上一次面板会话的在飞响应（目录/全网各一个序号）——
       否则关面板前发出的查询回来会把已清空的盒子重新填上旧词的结果。 */
    searchSeq += 1;
    state.search.liveSeq += 1;
    state.search.scope = 'mine';
    var chips = $('searchScope');
    if (chips) {
      // 全网档要三条 vrchat_search 命令都在；缺一条就不给这个档，别让用户点了报错
      var live = serverSupports('app__vrchat_search_users_get')
        && serverSupports('app__vrchat_search_worlds_get')
        && serverSupports('app__vrchat_search_groups_get');
      show(chips, live);
      var kids = chips.querySelectorAll('.chip');
      for (var i = 0; i < kids.length; i++) {
        kids[i].classList.toggle('is-on', kids[i].getAttribute('data-scope') === 'mine');
      }
    }
    var hint = $('searchHint');
    if (hint) {
      if (serverSupports('app__quick_search_query')) {
        show(hint, false);
      } else {
        hint.textContent = '服务器未提供全局搜索，这里是已加载好友的本地过滤。';
        show(hint, true);
      }
    }
  }

  function closeSearchPanel() { show($('searchPanel'), false); }

  /** 一条结果行。好友行点开详情；世界行点开世界详情；群组行只展示。 */
  function searchRow(entity) {
    var clickable = !!(entity.id || entity.worldId);
    var row = document.createElement(clickable ? 'button' : 'div');
    row.className = 'panelrow' + (clickable ? '' : ' panelrow--flat');
    if (clickable) row.type = 'button';
    var av = document.createElement('span');
    av.className = 'panelrow__avatar';
    av.textContent = initial(entity.name);
    var text = document.createElement('span');
    text.className = 'panelrow__text';
    var name = document.createElement('div');
    name.className = 'panelrow__name';
    name.textContent = safe(entity.name, '');
    var sub = document.createElement('div');
    sub.className = 'panelrow__sub';
    sub.textContent = scrubIds(entity.subtitle);
    text.appendChild(name);
    if (sub.textContent) text.appendChild(sub);
    row.appendChild(av);
    row.appendChild(text);
    if (clickable) {
      row.addEventListener('click', function () {
        closeSearchPanel();
        if (entity.worldId && window.__SCREENS_TOOLS__ && window.__SCREENS_TOOLS__.openWorld) {
          window.__SCREENS_TOOLS__.openWorld(entity.worldId);
        } else {
          fillDetail(entity.id, entity.name);
        }
      });
    }
    return row;
  }

  function searchGroup(title, rows) {
    if (!rows.length) return null;
    var f = frag();
    var g = document.createElement('div');
    g.className = 'panel__group';
    g.textContent = title;
    f.appendChild(g);
    for (var i = 0; i < rows.length && i < 8; i++) f.appendChild(searchRow(rows[i]));
    return f;
  }

  /** 服务端结果的分组渲染。字段是服务端 encode 出来的 camelCase 形状。 */
  function renderServerSearch(out) {
    var box = $('searchResults');
    if (!box) return;
    clear(box);
    var o = out && typeof out === 'object' ? out : {};
    var groups = [
      ['好友', o.friends || [], 'friend'],
      ['头像', (o.ownAvatars || []).concat(o.favoriteAvatars || []), false],
      ['世界', (o.ownWorlds || []).concat(o.favoriteWorlds || []), 'world'],
      ['群组', (o.ownGroups || []).concat(o.joinedGroups || []), '']
    ];
    var any = false;
    for (var i = 0; i < groups.length; i++) {
      var rows = [];
      var list = groups[i][1];
      var kind = groups[i][2];
      for (var j = 0; j < list.length; j++) {
        var r = list[j];
        if (!r || typeof r !== 'object' || !r.name) continue;
        rows.push({
          id: kind === 'friend' ? (r.id || '') : '',
          worldId: kind === 'world' ? (r.id || '') : '',
          name: safe(r.name, ''),
          subtitle: safe(r.subtitle, '')
        });
      }
      var node = searchGroup(groups[i][0], rows);
      if (node) { box.appendChild(node); any = true; }
    }
    if (!any) {
      var empty = document.createElement('p');
      empty.className = 'emptynote';
      empty.textContent = '没有匹配的结果。';
      box.appendChild(empty);
    }
  }

  /** 本地兜底：在已加载的好友名单里按名字过滤（组形态与服务端一致）。 */
  function renderLocalSearch(q) {
    var box = $('searchResults');
    if (!box) return;
    clear(box);
    var rows = [];
    for (var i = 0; i < state.roster.order.length; i++) {
      var rec = state.roster.byId[state.roster.order[i]];
      if (!rec) continue;
      var name = safe(rec.displayName, '') || safe(rec.username, '');
      if (name && name.toLowerCase().indexOf(q) >= 0) {
        rows.push({ id: rec.id, name: name, subtitle: trustText(rec.tags) });
      }
    }
    var node = searchGroup('好友（本地过滤）', rows);
    if (node) {
      box.appendChild(node);
    } else {
      var empty = document.createElement('p');
      empty.className = 'emptynote';
      empty.textContent = '已加载的数据里没有匹配项。';
      box.appendChild(empty);
    }
  }

  function runSearch(q) {
    var seq = ++searchSeq;
    var box = $('searchResults');
    /* ⚠️ 全网档用独立的 liveSeq 判新鲜度（见 runLiveSearch）。只要这次查询
       不走全网，就先作废在飞的全网响应 —— 否则「全网→我的」切档、清空输入框、
       重开面板这三种时序下，过期分组会 append 到新一轮结果后面。 */
    if (state.search.scope !== 'global' || !q) state.search.liveSeq += 1;
    if (!q) { if (box) clear(box); return; }
    var paint = function (fn, arg) {
      if (seq !== searchSeq) return;
      fn(arg);
    };
    if (state.search.scope === 'global') {
      runLiveSearch(q);
      return;
    }
    if (serverSupports('app__quick_search_query')) {
      cmd('app__quick_search_query', { query: q }).then(function (out) {
        paint(renderServerSearch, out);
      }).catch(function () {
        paint(renderLocalSearch, q.toLowerCase());
      });
    } else {
      paint(renderLocalSearch, q.toLowerCase());
    }
  }

  /* ---------- 全网档：VRChat 实时搜（用户 / 世界 / 群组） ---------- */

  /** 透传命令的 {status,data} 信封 → 数组；data 是 JSON 字符串，必须再 parse。 */
  function unwrapLive(response) {
    if (!response) return null;
    var status = Number(response.status || 0);
    if (status && (status < 200 || status >= 300)) {
      throw new Error('VRChat 请求失败（' + status + '）');
    }
    var raw = response.data;
    if (typeof raw !== 'string') return raw || null;
    if (!raw.trim()) return null;
    try { return JSON.parse(raw); } catch (e) { return null; }
  }

  function runLiveSearch(q) {
    var seq = ++state.search.liveSeq;
    var fresh = function () { return seq === state.search.liveSeq; };
    var box = $('searchResults');
    if (box) clear(box);
    var pending = 3;
    var done = function () {
      pending -= 1;
      if (pending === 0 && fresh()) {
        var empty = box && !box.firstChild;
        if (empty) {
          var p = document.createElement('p');
          p.className = 'emptynote';
          p.textContent = '全网没有匹配的结果。';
          box.appendChild(p);
        }
      }
    };
    // 用户：行可点开详情；世界/群组：只展示（详情页还没建）
    cmd('app__vrchat_search_users_get', { params: { search: q, n: 10 } })
      .then(function (r) {
        if (!fresh()) return;
        renderLiveGroup('全网 · 用户', (unwrapLive(r) || []).map(function (u) {
          return u && u.displayName ? { id: u.id || '', name: u.displayName, subtitle: '' } : null;
        }).filter(Boolean));
        done();
      })
      .catch(function (err) {
        if (fresh()) renderLiveError('全网 · 用户', err);
        done();
      });
    cmd('app__vrchat_search_worlds_get', { params: { search: q, n: 10 }, option: null })
      .then(function (r) {
        if (!fresh()) return;
        renderLiveGroup('全网 · 世界', (unwrapLive(r) || []).map(function (w) {
          if (!w || !w.name) return null;
          var sub = safe(w.authorName, '');
          return { id: '', name: w.name, subtitle: sub ? ('作者 ' + sub) : '' };
        }).filter(Boolean));
        done();
      })
      .catch(function (err) {
        if (fresh()) renderLiveError('全网 · 世界', err);
        done();
      });
    cmd('app__vrchat_search_groups_get', { params: { query: q, n: 10 } })
      .then(function (r) {
        if (!fresh()) return;
        renderLiveGroup('全网 · 群组', (unwrapLive(r) || []).map(function (g) {
          if (!g || !g.name) return null;
          var members = Number(g.memberCount || 0);
          return { id: '', name: g.name, subtitle: members > 0 ? (members + ' 名成员') : '' };
        }).filter(Boolean));
        done();
      })
      .catch(function (err) {
        if (fresh()) renderLiveError('全网 · 群组', err);
        done();
      });
  }

  function renderLiveGroup(title, rows) {
    var box = $('searchResults');
    if (!box) return;
    var node = searchGroup(title, rows);
    if (node) box.appendChild(node);
  }

  function renderLiveError(title, err) {
    var box = $('searchResults');
    if (!box) return;
    var g = document.createElement('div');
    g.className = 'panel__group';
    g.textContent = title + '：' + (window.VRCX ? window.VRCX.describeError(err) : '读取失败');
    box.appendChild(g);
  }

  /* ---------- 通知中心（服务端 app__notification_list_query） ---------- */

  var notifSeq = 0;

  /**
   * 通知 type → 中文名。
   *
   * ⚠️ 2026-09-29 补全到**服务端全部 25 种**
   * （`src/src/repositories/notificationPersistenceRepository.ts:82-107`）。
   * 以前这里只有 8 种，剩下的 17 种会原样把英文 type 打到界面上
   * —— 那种"半中半英"的列表看着就像没做完。
   */
  function notifTypeText(t) {
    var map = {
      /* 好友 / 邀请 */
      friendRequest: '好友请求',
      ignoredFriendRequest: '已忽略好友请求',
      invite: '邀请',
      requestInvite: '请求邀请',
      inviteResponse: '邀请回复',
      requestInviteResponse: '请求邀请回复',
      boop: '戳一戳',
      message: '消息',
      /* 群组 */
      groupChange: '群组变更',
      'group.announcement': '群组公告',
      'group.informative': '群组通知',
      'group.invite': '群组邀请',
      'group.joinRequest': '加入群组申请',
      'group.transfer': '群组转让',
      'group.event.created': '群组活动',
      'group.queueReady': '排队就绪',
      /* 活动 */
      event: '活动',
      'event.announcement': '活动公告',
      /* 审核 */
      'moderation.warning.group': '群组警告',
      'moderation.report.closed': '举报已处理',
      'moderation.contentrestriction': '内容限制',
      /* 其它 */
      'instance.closed': '实例已关闭',
      'economy.alert': '账户提醒',
      'economy.received.gift': '收到礼物',
      'badge.earned': '获得徽章',
      'vrcplus.gift': 'VRC+ 礼物',
      badge: '徽章',
      voteGroup: '群组投票'
    };
    return map[String(t || '')] || String(t || '');
  }

  function openNotifPanel() {
    var p = $('notifPanel');
    if (!p) return;
    show(p, true);
    loadNotifs();
  }

  function closeNotifPanel() { show($('notifPanel'), false); }

  function paintNotifBadge() {
    var badge = $('notifBadge');
    if (!badge) return;
    var n = 0;
    for (var i = 0; i < state.notif.rows.length; i++) {
      if (state.notif.rows[i] && !state.notif.rows[i].seen) n++;
    }
    badge.textContent = n > 99 ? '99+' : String(n);
    show(badge, n > 0);
  }

  function renderNotifs(note) {
    var box = $('notifList');
    if (!box) return;
    clear(box);
    if (note) {
      var p = document.createElement('p');
      p.className = 'emptynote';
      p.textContent = note;
      box.appendChild(p);
      paintNotifBadge();
      return;
    }
    if (!state.notif.rows.length) {
      var empty = document.createElement('p');
      empty.className = 'emptynote';
      empty.textContent = '没有通知。';
      box.appendChild(empty);
    }
    for (var i = 0; i < state.notif.rows.length; i++) {
      var r = state.notif.rows[i];
      if (!r || typeof r !== 'object') continue;
      var row = document.createElement('div');
      row.className = 'panelrow panelrow--flat';
      /* 以前这行**没有 click 也没有 href** —— 通知只能看不能点（2026-09-29 修）。
         这里用 role=button + tabindex 而不是改写成 <button>：
         .panelrow 的现有排版是按块级容器写的，换标签会牵一发动全身。 */
      row.setAttribute('role', 'button');
      row.setAttribute('tabindex', '0');
      (function (rr) {
        row.addEventListener('click', function () { openNotifDetail(rr); });
        row.addEventListener('keydown', function (ev) {
          if (ev.key === 'Enter' || ev.key === ' ' || ev.key === 'Spacebar') {
            ev.preventDefault();
            openNotifDetail(rr);
          }
        });
      })(r);
      var who = safe(r.senderUsername, '');
      var av = document.createElement('span');
      av.className = 'panelrow__avatar';
      av.textContent = who ? initial(who) : '·';
      var text = document.createElement('span');
      text.className = 'panelrow__text';
      var name = document.createElement('div');
      name.className = 'panelrow__name';
      name.textContent = who || notifTypeText(r.type);
      var sub = document.createElement('div');
      sub.className = 'panelrow__sub';
      var bits = [];
      var msg = scrubIds(safe(r.message, '') || safe(r.title, ''));
      if (msg && msg !== name.textContent) bits.push(msg);
      var tt = timeText(r.createdAt || r.created_at);
      if (tt) bits.push(tt);
      var kind = notifTypeText(r.type);
      if (kind && kind !== name.textContent) bits.push(kind);
      sub.textContent = bits.join(' · ');
      text.appendChild(name);
      if (sub.textContent) text.appendChild(sub);
      var dot = document.createElement('span');
      dot.className = 'panelrow__dot';
      show(dot, !r.seen);
      row.appendChild(av);
      row.appendChild(text);
      row.appendChild(dot);
      box.appendChild(row);
    }
    var mark = $('notifMarkSeen');
    var unseen = false;
    for (var k = 0; k < state.notif.rows.length; k++) {
      if (state.notif.rows[k] && !state.notif.rows[k].seen) { unseen = true; break; }
    }
    if (mark) show(mark, unseen);
    paintNotifBadge();
  }

  function loadNotifs() {
    if (state.notif.busy) return;
    var userId = state.userId || (state.me && state.me.id) || '';
    if (!userId) {
      state.notif.rows = [];
      renderNotifs('登录后才能看到通知。');
      return;
    }
    state.notif.busy = true;
    var seq = ++notifSeq;
    var query = {
      userId: userId, search: '', filters: [],
      perTableLimit: 100, limit: 100, includeUnseen: false
    };
    cmd('app__notification_list_query', { query: query }).then(function (rows) {
      if (seq !== notifSeq) return;
      state.notif.busy = false;
      state.notif.rows = (rows || []).filter(function (r) {
        return r && typeof r === 'object';
      });
      state.notif.loaded = true;
      renderNotifs('');
    }).catch(function (err) {
      if (seq !== notifSeq) return;
      state.notif.busy = false;
      renderNotifs(window.VRCX ? window.VRCX.describeError(err) : '通知读取失败。');
    });
  }

  /** 全部标为已读：location=local（纯本地 DB 写，不动 VRChat 那边）。 */
  function markAllNotifsSeen() {
    var items = [];
    for (var i = 0; i < state.notif.rows.length; i++) {
      var r = state.notif.rows[i];
      if (r && !r.seen && r.id) {
        items.push({ id: r.id, version: Number(r.version || 0), location: 'local' });
      }
    }
    if (!items.length) return;
    cmd('app__notification_mark_seen_batch', { items: items }).then(function () {
      for (var i = 0; i < state.notif.rows.length; i++) {
        if (state.notif.rows[i]) state.notif.rows[i].seen = true;
      }
      renderNotifs('');
    }).catch(function (err) {
      renderNotifs(window.VRCX ? window.VRCX.describeError(err) : '标记已读失败。');
    });
  }

  /* ==================================================================
     通知详情 + 单条操作（2026-09-29）
     ------------------------------------------------------------------
     以前列表里的通知**只能看不能点**：renderNotifs 画出来的行既没有 click
     也没有 href，点上去零反应。这里补详情，并按类型给真正能用的操作。

     ⚠️⚠️ 三条对**真服务器逐个命令实测**得到的结论，改这里之前请先看：
     1. `target` **不是字符串**，是 `NotificationTarget` 结构体
        （crates/application/src/social/notification_chains.rs:14，serde camelCase）：
          { id, version, type, senderUserId }
        传字符串 → `invalid arguments: invalid type: string "…",
        expected struct NotificationTarget`，客户端表现为"点了没反应"。
     2. **HTTP 200 不代表成功**：远端失败照样放在 200 的响应体里
        （`status:"remoteFailed"` + `remoteError`，如 `Notification not found`）。
        只看状态码就会把失败当成成功 —— 又是一例静默失效，所以下面读响应体。
     3. `app__social_friend_request_notification_accept` 的必填是
        `notificationId` + `targetUserId`（逐个试出来的，不是 `target`）。
     ================================================================== */

  function notifTarget(row) {
    var raw = row || {};
    /* ⚠️⚠️ 这里**绝不能用 `safe()`**。
       `safe()` 是**显示层**的防泄漏过滤器：命中 `ID_RE`
       （`/\b(?:usr|wrld|avtr|grp)_[A-Za-z0-9_-]{4,}/`）就返回 fallback，
       于是 `usr_…` 会被主动换成空串。
       而这个字段本来就**是 id**、只回传给服务端、永远不上屏 ——
       拿 safe 取的后果是 `senderUserId` 恒为 `''`，
       表现为点了「戳回去」什么都不发生（2026-09-29 在模拟器上实踩到：
       面板没弹出来，也没有任何报错，因为真的走的是"不知道戳谁"那条分支）。
       逻辑取值用原始值；要上屏时再过 `scrubIds()`。 */
    return {
      id: String(raw.id == null ? '' : raw.id),
      version: Number(raw.version || 0),
      type: String(raw.type == null ? '' : raw.type),
      senderUserId: String(raw.senderUserId || raw.sender_user_id || '')
    };
  }

  function closeNotifDetail() { show($('notifDetailPanel'), false); }

  /** 单条标已读。失败也不提示：标没标上都不该挡住用户看内容。 */
  function markNotifSeen(row) {
    if (!row || row.seen || !row.id) return;
    cmd('app__notification_mark_seen_batch', {
      items: [{ id: row.id, version: Number(row.version || 0), location: 'local' }]
    }).then(function () {
      row.seen = true;
      paintNotifBadge();
    }).catch(function () { /* 本地 DB 写失败就算了 */ });
  }

  /** 执行一个通知动作，并给出**诚实**的反馈（含响应体里的远端失败）。 */
  function runNotifAction(command, extra, okText) {
    var owner = state.userId || (state.me && state.me.id) || '';
    if (!owner) { toast('还没登录，做不了这个。'); return Promise.resolve(); }
    var args = { ownerUserId: owner, endpoint: connEndpoint() };
    for (var k in extra) {
      if (Object.prototype.hasOwnProperty.call(extra, k)) args[k] = extra[k];
    }
    return cmd(command, args).then(function (out) {
      /* ⚠️ 同样不能用 safe()：服务端的 `remoteError` 文案里可能带 id
         （如 `Friend usr_… not found`），safe 会把它整条清成空串 →
         下面的 `if (remote || local)` 不成立 → **失败被当成成功**。
         这里要的是"判断有没有错"，不是"能不能上屏"。 */
      var remote = String((out && out.remoteError) || '');
      var local = String((out && out.localError) || '');
      if (remote || local) throw new Error(remote || local);
      if (out && out.status === 'alreadyResolved') { toast('这条已经处理过了。'); }
      else toast(okText || '已处理');
      loadNotifs();
    }).catch(function (err) {
      var msg = window.VRCX ? VRCX.describeError(err) : String(err && err.message || err);
      toast('没做成：' + msg);
    });
  }

  /** `details` 字段是个 JSON 字符串；拿不到就返回空对象，别让它 throw。 */
  function notifDetails(row) {
    var raw = safe(row && row.details, '');
    if (!raw) return {};
    if (typeof raw === 'object') return raw;
    try { return JSON.parse(raw) || {}; } catch (e) { return {}; }
  }

  function openNotifDetail(row) {
    var panel = $('notifDetailPanel');
    var body = $('ndBody');
    if (!panel || !body || !row) return;
    clear(body);
    markNotifSeen(row);

    var type = safe(row.type, '');
    add(body, E('div', 'notifd__kind', notifTypeText(type)));

    var who = safe(row.senderUsername, '') || '某人';
    add(body, E('div', 'notifd__who', who));

    /* ⚠️ 所有可能带裸 id 的字段一律过 scrubIds：location 里可能是
       `wrld_xxx:12345~hidden(usr_yyy)`，`details` 里可能有 worldId。
       "屏幕上一个裸 id 都不能出现" 是本项目红线。 */
    var det = notifDetails(row);
    var title = scrubIds(safe(row.title, ''));
    var msg = scrubIds(safe(row.message, ''));
    if (title) add(body, E('div', 'notifd__text', title));
    if (msg && msg !== title) add(body, E('div', 'notifd__text', msg));

    var bits = [];
    var tt = timeText(row.createdAt || row.created_at);
    if (tt) bits.push(tt);
    if (row.seen !== true) bits.push('未读');
    add(body, E('div', 'notifd__meta', bits.join(' · ')));

    /* ---------- 按类型给操作 ---------- */
    var acts = E('div', 'btnrow notifd__acts');
    function actBtn(label, fn, filled) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'btn' + (filled ? ' btn--filled' : '');
      b.textContent = label;
      b.addEventListener('click', fn);
      acts.appendChild(b);
    }
    function ignore() {
      runNotifAction('app__notification_hide_and_expire',
        { target: notifTarget(row) }, '已忽略');
    }

    if (type === 'invite') {
      actBtn('加入', function () {
        /* ⚠️ instanceId / worldId 也是**回传给服务端的 id**，不能过 safe()
           （worldId 是 `wrld_…`，safe 会把它清空 → 加入就永远失败）。 */
        runNotifAction('app__notification_request_invite_accept', {
          target: notifTarget(row),
          instanceId: String(det.instanceId == null ? '' : det.instanceId),
          worldId: String(det.worldId == null ? '' : det.worldId)
        }, '已加入，正在穿越');
      }, true);
      actBtn('忽略', ignore);
    } else if (type === 'requestInvite') {
      /* 对方想进你这儿。发邀请那条的入参（receiverUserId + instanceId/worldId）
         这次没有逐个实测过，所以**不接**：猜错就是一次"点了没反应"，
         而不确定的功能假装能用比没有更糟。先把有把握的给出来。 */
      actBtn('看看他本人', function () {
        closeNotifDetail();
        closeNotifPanel();
        fillDetail(row.senderUserId, who);
      }, true);
      actBtn('忽略', ignore);
    } else if (type === 'friendRequest') {
      actBtn('接受', function () {
        /* ⚠️ 这条**不是** target 结构体：实测必填是 notificationId + targetUserId。
           notificationId 同样不能用 safe()（`ntf_…` 现在不匹配 ID_RE，
           但哪天 ID_RE 加了前缀就会静默变空 —— 逻辑取值一律不碰 safe）。 */
        runNotifAction('app__social_friend_request_notification_accept', {
          notificationId: notifTarget(row).id,
          targetUserId: notifTarget(row).senderUserId
        }, '已添加好友');
      }, true);
      actBtn('忽略', ignore);
    } else if (type === 'boop') {
      actBtn('戳回去', function () {
        closeNotifDetail();
        openBoopPicker(notifTarget(row).senderUserId, who);
      }, true);
      actBtn('忽略', function () {
        runNotifAction('app__notification_boop_dismiss',
          { senderUserId: notifTarget(row).senderUserId }, '已忽略');
      });
    }

    /* 兜底：任何类型都能"标已读并移出列表" */
    actBtn('标为已读并清除', function () {
      runNotifAction('app__notification_hide_and_expire',
        { target: notifTarget(row) }, '已清除');
    });
    add(body, acts);
    show(panel, true);
  }

  /* ==================================================================
     戳一戳 —— 选表情（2026-09-29）
     ------------------------------------------------------------------
     服务端 `app__vrchat_boop_send` 的入参是 (user_id, emoji_id, inventory_item_id)，
     两者**互斥**：都空 → body `{}`（戳个白板）；给 emoji → 内置表情；
     给 inventory → 库存道具表情；同时给 → 报错（vrchat-client/src/notifications.rs:319-345）。
     服务端**没有**"列出可用 boop emoji"的端点，所以选项来自桌面端内置常量表
     （scripts/emojis.js，65 个，由 tools/gen_boop_emojis.py 生成）。
     ================================================================== */
  var boopChoice = { userId: '', emojiId: '' };

  function closeBoopPicker() {
    show($('boopPanel'), false);
    /* 解绑懒加载观察器：不解绑的话每次开面板都叠一个，
       65 个 target 会一直挂在旧的 observer 上。 */
    try {
      if (window.__BOOP_IO__) { window.__BOOP_IO__.disconnect(); window.__BOOP_IO__ = null; }
    } catch (e) {}
  }

  function openBoopPicker(userId, who) {
    userId = userId || '';
    if (!userId) { toast('不知道戳谁。'); return; }
    var list = window.VRCX_DEFAULT_EMOJIS || [];
    if (!list.length) { doBoop(userId, '', who); return; }

    /* ⚠️ 就地改字段，**不要**整体重新赋值。
       `window.__SCREENS__.boopChoice` 导出的是这个对象的**引用**；
       一旦写成 `boopChoice = {...}`，导出项还指着旧对象 —— 探针永远读到空，
       "选了表情没"就再也测不出来（真机回归会被假绿放过去）。 */
    boopChoice.userId = userId;
    /* 默认选中项：不是表里的第一项（那是 **Angry**，一张生气的脸）。
       面板一开就预选 Angry，用户直接点"就这样戳"就是给人家甩脸 —— 换成
       `default_hand_wave`（挥手），也是 VRChat 自己通知文案里用的那个。
       表里找不到就退回第一项。要改默认表情只动这一处。 */
    var def = list[0];
    for (var d = 0; d < list.length; d++) {
      if (list[d].id === 'default_hand_wave') { def = list[d]; break; }
    }
    boopChoice.emojiId = def.id;
    var grid = $('boopGrid');
    if (!grid) { doBoop(userId, '', who); return; }
    clear(grid);
    var previewQueue = [];      // [元素, 图 url]，交给下面的懒加载逻辑
    var h = $('boopTitle');
    if (h) h.textContent = who ? ('戳一戳 ' + who) : '选个表情戳回去';

    for (var i = 0; i < list.length; i++) {
      (function (item) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'emoji-cell' + (item.id === boopChoice.emojiId ? ' is-on' : '');
        b.setAttribute('aria-label', item.name);
        var pic = document.createElement('span');
        pic.className = 'emoji-cell__pic avatar av' + ((i % 6) + 1);
        pic.textContent = item.name ? item.name.charAt(0) : '?';
        b.appendChild(pic);
        var cap = document.createElement('span');
        cap.className = 'emoji-cell__cap';
        cap.textContent = item.name;
        b.appendChild(cap);
        b.addEventListener('click', function () {
          boopChoice.emojiId = item.id;
          var cells = grid.querySelectorAll('.emoji-cell');
          for (var k = 0; k < cells.length; k++) cells[k].className = 'emoji-cell';
          b.className = 'emoji-cell is-on';
        });
        grid.appendChild(b);
        previewQueue.push([pic, item.previewUrl]);
      })(list[i]);
    }
    show($('boopPanel'), true);

    /* ⚠️ 预览图必须**滚到才加载**，而且不能一次全发。
       2026-09-29 在模拟器上实测：65 张一次全发，界面停在全字母占位 30 秒以上
       （45 秒才基本到位；彩色占位像素数 3500 → 40）。
       单张经 App 图片代理回来是 ~115KB 的 data URL
       （直接调 `app__external_api_image_data_url_get` 实测 200 / 115KB，服务端是有图的），
       65 张 ≈ 7.5MB —— 一次性拉就是把面板自己堵死。
       所以：进了视口才取；旧 WebView 没有 IntersectionObserver 时退化为每 200ms 放 4 张。
       ⚠️ 也不要直接 <img src="https://wiki-files.vrchat.com/…">：
       WebView 未必能直连外域，那样是一屏裂图，比首字母占位更难看。 */
    var items = previewQueue;
    var diagShown = false;
    function diagOnce(msg) {
      if (diagShown) return;
      diagShown = true;
      var t = $('boopTitle');
      if (t) t.textContent = msg;   // 静默降级最难查，把原因写在标题上
    }
    function fetchOne(el, url) {
      loadImage(url).then(function (data) {
        if (!data) { diagOnce('图取不到（代理）：' + String(url).slice(0, 40)); return; }
        if (!el.isConnected) return;
        el.textContent = '';
        var img = document.createElement('img');
        img.src = data;
        img.alt = '';
        el.appendChild(img);
      });
    }
    if (window.IntersectionObserver) {
      var io = new IntersectionObserver(function (entries) {
        for (var k = 0; k < entries.length; k++) {
          var en = entries[k];
          if (!en.isIntersecting) continue;
          io.unobserve(en.target);
          var u = en.target.getAttribute('data-preview-url');
          if (u) fetchOne(en.target, u);
        }
      }, { root: null, rootMargin: '160px 0px' });
      for (var z = 0; z < items.length; z++) {
        items[z][0].setAttribute('data-preview-url', items[z][1]);
        io.observe(items[z][0]);
      }
      window.__BOOP_IO__ = io;      // 面板关闭时解绑，见 closeBoopPicker
    } else {
      var qi = 0;
      (function pump() {
        var n = 0;
        while (qi < items.length && n < 4) {
          fetchOne(items[qi][0], items[qi][1]);
          qi++;
          n++;
        }
        if (qi < items.length) window.setTimeout(pump, 200);
      })();
    }
  }

  /** 真的发出去。`emojiId` 空 = 不带表情（服务端约定的"两者都空"分支）。 */
  function doBoop(userId, emojiId, who) {
    var args = { userId: userId };
    if (emojiId) args.emojiId = emojiId;
    else args.inventoryItemId = '';
    return cmd('app__vrchat_boop_send', args)
      .then(function () { toast(emojiId ? '已戳回去了' : '已戳一戳'); closeBoopPicker(); })
      .catch(function (err) {
        toast('戳失败了：' + (window.VRCX ? VRCX.describeError(err) : String(err)));
      });
  }

  /* ---------- 骨架屏 ---------- */

  /**
   * 桥模式下、真实数据到位前，用骨架条顶掉 index.html 里的静态样例
   * —— 否则真机每次冷启动都先闪一两秒"示例好友 A…G"。
   * 浏览器演示（无桥）**不**走这里：样例就是它的内容，不是加载闪屏。
   */
  function paintSkeleton() {
    if (!(window.VRCX && window.VRCX.available)) return;
    if (state.loaded) return;
    var skel = function (n) {
      var f = frag();
      for (var i = 0; i < n; i++) {
        var row = document.createElement('div');
        row.className = 'skel';
        var av = document.createElement('span');
        av.className = 'skel__av';
        var lines = document.createElement('span');
        lines.className = 'skel__lines';
        var l1 = document.createElement('span');
        l1.className = 'skel__line';
        var l2 = document.createElement('span');
        l2.className = 'skel__line';
        lines.appendChild(l1);
        lines.appendChild(l2);
        row.appendChild(av);
        row.appendChild(lines);
        f.appendChild(row);
      }
      return f;
    };
    var targets = ['feedList', 'friendList', 'homeLatest', 'favBody'];
    for (var i = 0; i < targets.length; i++) {
      var box = $(targets[i]);
      if (box) { clear(box); box.appendChild(skel(5)); }
    }
  }

  /* ------------------------------------------------------------------ 接线 */

  /**
   * 名字取值 —— 受「隐藏昵称」开关控制。
   * 开着一律用 username（账号名），关掉用 displayName（昵称）再兜 username。
   * ⚠️ 兜底**不能**返回 id：屏幕上出现 `usr_` 开头的裸 id 是本项目红线
   * （有些账号连 username 都没有，那就叫"未命名好友"，也不要吐 id）。
   */
  function displayNameOf(rec) {
    if (!rec) return '';
    if (PREFS.hideNames) {
      return safe(rec.username, '') || safe(rec.displayName, '') || '未命名好友';
    }
    return safe(rec.displayName, '') || safe(rec.username, '') || '未命名好友';
  }

  /**
   * `location`（形如 `wrld_xxx:12345~hidden(usr_y)~region(jp)`）里的实例号 → `12345`。
   * ⚠️ **只返回实例号本身**。同一份字符串里的世界 id 绝不能带出来——
   * "屏幕上一个裸 id 都不能有" 是硬红线；宁可返回空也不能把整串 location 打印出去。
   */
  function instanceNumber(location) {
    var s = String(location || '');
    var m = /:(\d+)(?:[~?].*)?$/.exec(s);
    return m ? m[1] : '';
  }

  /**
   * 「关于」页那四个开关的接线。
   *
   * ⚠️ 这四个以前是**纯装饰**：index.html 里写着 `role="switch"`，
   * 但全 scripts 目录 grep 不到任何绑定，点了零反应（2026-09-29 逐个确认）。
   * 这里每一条都对上一个**真的会改变的行为**，见 PREFS 的注释。
   */
  function wireSettingSwitches() {
    var spec = {
      swReduceMotion: 'reduceMotion',
      swInstanceId: 'instanceId',
      swHideNames: 'hideNames',
      swRelativeTime: 'relativeTime'
    };
    for (var id in spec) {
      if (!Object.prototype.hasOwnProperty.call(spec, id)) continue;
      (function (id, key) {
        var el = $(id);
        if (!el) return;
        function paint() {
          var on = !!PREFS[key];
          el.className = 'switch' + (on ? ' is-on' : '');
          el.setAttribute('aria-checked', on ? 'true' : 'false');
        }
        function toggle(ev) {
          if (ev) ev.preventDefault();
          PREFS[key] = !PREFS[key];
          paint();
          savePrefs();
          applyPrefs();
          /* 这三项改变的是**渲染结果**，不重渲就看不到变化 → 又变成"点了没反应"。
             reduceMotion 只动 <html> 的 class，CSS 立刻生效，不用重渲。 */
          if (key !== 'reduceMotion') { renderFeed(); renderFriends(); }
        }
        paint();
        el.addEventListener('click', toggle);
        /* 键盘可达：`role="switch"` 得自己管 Enter/Space（原生只认 checkbox） */
        el.addEventListener('keydown', function (ev) {
          if (ev.key === 'Enter' || ev.key === ' ' || ev.key === 'Spacebar') toggle(ev);
        });
      })(id, spec[id]);
    }
  }

  function wire() {
    loadPrefs();
    applyPrefs();
    wireSettingSwitches();
    /* 个人页「外观」3 档主题：选中即切换 data-theme 并存盘（2026-09-30 接线）。 */
    var tbox = $('themeChips');
    if (tbox) {
      var tchips = tbox.querySelectorAll('.chip');
      for (var ti = 0; ti < tchips.length; ti++) {
        (function (chip) {
          chip.addEventListener('click', function () {
            var v = chip.getAttribute('data-theme-value');
            if (!v || THEME_VALUES.indexOf(v) < 0) return;
            PREFS.theme = v;
            savePrefs();
            applyPrefs();   // 内部会 setAttribute('data-theme') + 重绘高亮
          });
        })(tchips[ti]);
      }
    }
    /* 主页「查看全部」：展开/收起被截断的剩余好友聚集地。
       ⚠️ 不用下面的 `click()` 帮手：它在这段之后才被 `var click = ...` 赋值，
       此刻调用会拿到 hoisted 但未初始化的 undefined。直接用 addEventListener。 */
    var hm = $('homePlacesMore');
    if (hm) hm.addEventListener('click', function () {
      homePlacesExpanded = !homePlacesExpanded;
      renderHome();
    });
    var chips = document.querySelectorAll('#favChips .chip');
    for (var i = 0; i < chips.length; i++) {
      (function (chip) {
        var kind = chip.getAttribute('data-fav-kind');
        if (!kind) return;
        chip.addEventListener('click', function () { setFavKind(kind); });
      })(chips[i]);
    }
    // 本地搜索：只在已加载数据上过滤，不重新打接口（服务端 quick_search 未实现）。
    // 输入框在 controls 里，render* 只清列表容器，所以值与监听一直都在。
    var feedSearch = $('feedSearch');
    if (feedSearch) feedSearch.addEventListener('input', function () {
      state.search.feed = feedSearch.value.trim().toLowerCase();
      renderFeed();
    });
    var friendSearch = $('friendSearch');
    if (friendSearch) friendSearch.addEventListener('input', function () {
      state.search.friends = friendSearch.value.trim().toLowerCase();
      renderFriends();
    });
    /* 动态类型筛选：全部/位置/状态/简介/模型/上线/下线（0.2.26 接线） */
    var fchips2 = document.querySelectorAll('.chip[data-feed-kind]');
    for (var fi2 = 0; fi2 < fchips2.length; fi2++) {
      (function (chip) {
        chip.addEventListener('click', function () {
          state.feedFilter = chip.getAttribute('data-feed-kind') || '';
          for (var k = 0; k < fchips2.length; k++) {
            fchips2[k].classList.toggle('is-on', fchips2[k] === chip);
          }
          renderFeed();
        });
      })(fchips2[fi2]);
    }
    /* 好友筛选档：全部/同房间/在线/活跃/离线（0.2.13 之前是死的） */
    var fchips = document.querySelectorAll('.chip[data-f-filter]');
    for (var fi = 0; fi < fchips.length; fi++) {
      (function (chip) {
        chip.addEventListener('click', function () {
          state.fFilter = chip.getAttribute('data-f-filter') || '';
          for (var k = 0; k < fchips.length; k++) {
            fchips[k].classList.toggle('is-on', fchips[k] === chip);
          }
          renderFriends();
        });
      })(fchips[fi]);
    }
    var click = function (id, fn) {
      var node = $(id);
      if (node) node.addEventListener('click', function () { fn(); });
    };
    click('dBoop', boop);
    click('dRequest', requestInvite);
    click('dInvite', inviteJoin);
    /* 收藏（0.2.31-t4）：详情面板的「收藏」+ 收藏页「新建收藏夹」+ 两个新面板的关闭/保存。 */
    click('dFav', function () {
      if (!detailTarget || !detailTarget.id) { toast('先选一个好友。'); return; }
      openFavPick('friend', detailTarget.id, detailTarget.name || '');
    });
    click('favNewGroup', openFavGroupCreate);
    click('favPickClose', function () { show($('favPickPanel'), false); });
    click('favEditClose', function () { show($('favEditPanel'), false); });
    click('favEditSave', favEditSave);
    var visBox = $('favEditVis');
    if (visBox) visBox.addEventListener('click', function (e) {
      var chip = e.target.closest ? e.target.closest('.chip') : null;
      if (!chip) return;
      favEditCtx.visibility = chip.getAttribute('data-vis') || 'private';
      paintFavVisChips();
    });
    /* 共同好友按需拉（app__user_mutual_friends_list_get 会真的打一次 VRChat），
       所以不跟面板一起自动发，只在点这颗按钮时发。 */
    click('dMutualBtn', loadMutualFriends);
    click('detailClose', closeDetail);
    // 全局搜索面板：服务端 quick_search_query 有就分组结果，没有就本地过滤
    click('searchBtn', openSearchPanel);
    click('searchClose', closeSearchPanel);
    var searchInput = $('searchInput');
    if (searchInput) searchInput.addEventListener('input', function () {
      var q = searchInput.value.trim();
      if (searchTimer) clearTimeout(searchTimer);
      searchTimer = setTimeout(function () { runSearch(q); }, SEARCH_DEBOUNCE);
    });
    /* 搜索档位：我的（目录/本地） vs 全网（VRChat 实时）。切档立即用当前词重查。 */
    var scopeBox = $('searchScope');
    if (scopeBox) scopeBox.addEventListener('click', function (e) {
      var chip = e.target.closest ? e.target.closest('.chip') : null;
      if (!chip) return;
      var scope = chip.getAttribute('data-scope');
      if (!scope || scope === state.search.scope) return;
      state.search.scope = scope;
      var kids = scopeBox.querySelectorAll('.chip');
      for (var i = 0; i < kids.length; i++) {
        kids[i].classList.toggle('is-on', kids[i] === chip);
      }
      var hint = $('searchHint');
      if (hint) show(hint, false);   // 切到全网后"本地过滤"提示不再成立
      var q = (searchInput && searchInput.value || '').trim();
      if (searchTimer) clearTimeout(searchTimer);
      if (q) runSearch(q); else runSearch('');
    });
    var searchPanel = $('searchPanel');
    if (searchPanel) searchPanel.addEventListener('click', function (e) {
      if (e.target === searchPanel) closeSearchPanel();
    });
    // 通知中心：打开拉一次 + 未读角标；"全部标为已读"只写本地（location=local）
    click('notificationsBtn', openNotifPanel);
    click('notifClose', closeNotifPanel);
    click('notifMarkSeen', markAllNotifsSeen);
    /* 两个新面板（通知详情 / 戳一戳表情）的关闭与动作按钮 —— 2026-09-29。
       ⚠️ 别漏：这两个面板的 id 在测试夹具里是没有的，漏接就是"打不开/关不掉"。 */
    click('ndClose', closeNotifDetail);
    click('boopClose', closeBoopPicker);
    click('boopSend', function () { doBoop(boopChoice.userId, boopChoice.emojiId, ''); });
    click('boopPlain', function () { doBoop(boopChoice.userId, '', ''); });
    var ndPanelEl = $('notifDetailPanel');
    if (ndPanelEl) ndPanelEl.addEventListener('click', function (e) {
      if (e.target === ndPanelEl) closeNotifDetail();
    });
    var boopPanelEl = $('boopPanel');
    if (boopPanelEl) boopPanelEl.addEventListener('click', function (e) {
      if (e.target === boopPanelEl) closeBoopPicker();
    });
    var notifPanel = $('notifPanel');
    if (notifPanel) notifPanel.addEventListener('click', function (e) {
      if (e.target === notifPanel) closeNotifPanel();
    });
    // 真实数据到位前用骨架条顶掉静态样例（浏览器演示无桥，不走）
    paintSkeleton();
    /* ⚠️ 取数可能在 wire() 之前就已经失败（失败桥/极速失败：catch 比
       DOMContentLoaded 先跑）—— 骨架画上去之后要立刻换成失败提示，
       否则骨架条永远闪烁（0.2.19 失败档实测）。 */
    if (state.tried && state.error) paintLoadFailed();
    var scrim = document.querySelector('.scrim');
    if (scrim) scrim.addEventListener('click', closeDetail);
    /* 切页一律收起详情 —— 详情属于「点开它的那一屏」。
       ⚠️ 实测（USB 真机 a6943611 / 0.2.6-t1）：动态里点开好友 -> 切到「好友」页，
       抽屉仍悬在屏上，跨屏残留（连切 5 个 tab 都不关）。
       根因：navitem 的切页是 main.js 的 goto() 干的，它只管 page 的 hidden 与
       .is-wide / .is-on，**完全不知道 #detail 的存在**；而 #detail 的显隐归这里管
       （applyDetailVis/detailOpen）。两条线不通 -> 没人收面板。
       用 document 级委托补上，而不是改 goto()：出图脚本那条
       `#tabp,bare,detail,t-friends` 是 **boot 时 hash 驱动**的 goto，不经 click，
       所以详情仍能在切换前打开（tabp_open 的限宽抽屉断言不受影响）。 */
    document.addEventListener('click', function (e) {
      var n = e.target;
      while (n && n !== document) {
        if (n.getAttribute && n.getAttribute('data-goto')) { closeDetail(); return; }
        n = n.parentElement;
      }
    });
    /* ⚠️ 这里**不要**再把 #detail 挪去 body（曾经的 floatDetail()）—— 详见
       floatDetail 定义处的说明：一挪走它就出不了右栏了。 */
  }

  /** 退出登录 / 换服务器之后，屏上必须回到样例 + 横幅，而不是留着上一个人的数据。 */
  function reset() {
    state.loaded = false;
    state.tried = false;
    state.error = '';
    state.me = null;
    state.myAvatars = [];
    state.feedRows = [];
    state.roster = { order: [], byId: {} };
    state.worldNames = {};
    state.fav = { kind: 'world', rows: { world: [], avatar: [], friend: [] }, loaded: {} };
    state.favBaseline = null;
    state.favError = '';
    /* ⚠️ `group` 不能漏：漏了群组收藏夹的名字就再也补不回来（这里以前就漏过） */
    state.favNames = { world: {}, avatar: {}, group: {} };
    state.browse = [];
    location.reload();
  }

  function onState(s) {
    if (!s) return;
    if (s.supported && s.supported.length) state.supported = s.supported;
    /* realtime 会话 id 到手/更新 = 之前的名单基线是对着空 wsid 拉的、必然被
       服务端对账拒掉（全员 offline 的根因）。已加载过就定向重拉名单再重画。 */
    if (s.streamWebsocket && s.streamWebsocket !== state.streamWsid) {
      state.streamWsid = s.streamWebsocket;
      if (state.loaded && !state.busy) {
        loadRoster().then(function () { renderAll(); }).catch(function () {});
      }
    }
    if (!s.authenticated) {
      /* 未登录 / 掉线：屏上现在是样例。横幅由 conn.js 管，这里不要抢 ——
         但已经画过真实数据的屏必须复位，否则退出登录后还留着别人的好友名单。 */
      if (state.loaded) reset();
      return;
    }
    state.userId = s.userId || state.userId;
    if (!state.loaded && !state.busy) loadAll();
  }

  if (!window.VRCXCon) return;   // 闸门脚本没起来 → 没有登录态可依赖
  window.VRCXCon.onChange(onState);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', wire);
  } else {
    wire();
  }

  // 调试口子：无头回归探针读它，产品里不被引用
  window.__SCREENS__ = {
    state: state,
    load: loadAll,
    render: renderAll,
    safe: safe,
    openDetail: fillDetail,   // screens_tools 的关系图节点点击走这里
    /* VRChat 端点也要给出去：screens_tools 的抓取按钮要带它，而这函数是本
       IIFE 的私有的 —— 跨文件直接喊名字会 ReferenceError，且被点击处理器吞掉
       （表现为"按钮点了没反应"，0.2.21 实踩）。 */
    connEndpoint: connEndpoint,
    connWsid: connWsid,
    /* 判定类的纯函数也导出给回归探针：状态灯的语义（五档 × 实心/空心）
       光靠"截图看着差不多"验不出来，必须逐条比对桌面版的判定链。 */
    statusDotClass: statusDotClass,
    statusTip: statusTip,
    statusLabel: statusLabel,
    statusRank: statusRank,
    instanceNumber: instanceNumber,
    notifTarget: notifTarget,
    notifTypeText: notifTypeText,
    boopChoice: boopChoice,
    /* ⚠️ 面板开/关也导出给回归探针：只读 `VRCX_DEFAULT_EMOJIS` 常量验不出
       "默认选中到底选了哪一项"（常量对了而代码没用它，照样是没修）。
       真开一次面板再读 boopChoice，才能锁住"默认不是 Angry"。 */
    openBoopPicker: openBoopPicker,
    closeBoopPicker: closeBoopPicker,
    /* 收藏增删改（0.2.31-t4）：openFavPick 给 screens_tools 的世界详情「收藏」按钮用；
       其余导出给回归探针（选中态/上下文只读 DOM 验不出来）。 */
    openFavPick: openFavPick,
    openFavGroupEdit: openFavGroupEdit,
    refreshFavorites: refreshFavorites,
    favPickCtx: favPickCtx,
    favEditCtx: favEditCtx,
    findFavFvrt: findFavFvrt,
    /* 「更多资料」也导出给回归探针：这一段的价值全在**白名单**上 ——
       "哪些字段没被渲染"用截图看不出来，必须让探针去扫 DOM。
       见 _shots/audit_statusdot.py 的「更多资料」段。 */
    loadExtraProfile: loadExtraProfile,
    loadMutualFriends: loadMutualFriends,
    urlHost: urlHost,
    loadLocal: function () {  // 会话失效（401）时本地库读取也要跑：通知/配置不依赖 VRChat
      loadNotifs();
      loadConfigs();
    }
  };
})();
