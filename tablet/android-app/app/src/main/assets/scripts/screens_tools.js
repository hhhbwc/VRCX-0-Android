/* ============================================================
   screens_tools.js — 工具屏（0.2.9-t1 起搬运桌面端功能）

   范围（数据面命令全部由服务端实现，oss crates/remote-server）：
     · 游戏日志     app__game_log_sessions_query（桌面 /game-log）
     · 房间历史     app__instance_history_query（桌面 /instance-history）
     · 浏览历史     app__browse_history_query（桌面 /browse-history）
     · 我的群组     app__vrchat_group_user_groups_get（桌面 /tools/my-groups）
     · 详情·记录    app__friend_log_history_query（桌面 /social/friend-log）

   约定：
     · 与 screens.js 解耦 —— 这里只复用全局门面（VRCX / VRCXCon），
       需要小帮手就本地再写一份，不为复用把 screens.js 拆出口子。
     · screens.js 的 fillDetail 会派发 `vrcx:detail`（只带 id/name 指针），
       「记录」区的取数与渲染归本文件。
     · 红线同样适用：裸 id 永不上屏（scrubIds / 只画名字字段）。
     · 懒加载：点工具卡才取数；失败如实写 emptynote，不编数据。
   ============================================================ */
(function () {
  'use strict';

  function $(id) { return document.getElementById(id); }
  function clear(node) { while (node && node.firstChild) node.removeChild(node.firstChild); }
  function show(node, on) { if (node) node.hidden = !on; }
  function add(parent, child) { if (child) parent.appendChild(child); return parent; }
  function E(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined && text !== null) n.textContent = String(text);
    return n;
  }
  function safe(text, fallback) {
    var s = String(text === undefined || text === null ? '' : text);
    s = s.replace(/[\u0000-\u001f\u007f]/g, ' ').trim();
    return s || (fallback || '');
  }
  /** 红线兜底：把可能夹带的裸 id 洗掉（详见 screens.js 同名函数）。 */
  function scrubIds(text) {
    var s = String(text || '');
    if (/(?:usr|wrld|avtr|grp)_[A-Za-z0-9]/.test(s)) {
      s = s.replace(/(?:usr|wrld|avtr|grp)_[A-Za-z0-9]{3,}/g, '…');
    }
    return s;
  }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function timeText(createdAt) {
    var s = String(createdAt || '').trim();
    if (!s) return '';
    var d = new Date(s.replace(' ', 'T').replace(/Z?$/, 'Z'));
    if (isNaN(d.getTime())) d = new Date(s);
    if (!d || isNaN(d.getTime())) return '';
    var now = new Date();
    var hm = pad2(d.getHours()) + ':' + pad2(d.getMinutes());
    if (d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
        && d.getDate() === now.getDate()) return '今天 ' + hm;
    return pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + hm;
  }
  /** 秒 → 「2小时14分」/「8分」；0/空返回 ''。 */
  function durationText(sec) {
    var n = Number(sec);
    if (!isFinite(n) || n <= 0) return '';
    var h = Math.floor(n / 3600), m = Math.round((n % 3600) / 60);
    if (h > 0) return h + '小时' + (m > 0 ? m + '分' : '');
    if (m > 0) return m + '分';
    return '不到 1 分';
  }
  function thumbText(title) {
    var t = safe(title, '');
    return t.length <= 6 ? t : t.slice(0, 6);
  }

  var state = {
    userId: '',
    authenticated: false,
    loaded: {},            // page -> true（本会话已拉过）
    busy: {},
    gl: [],                // 游戏日志 sessions
    ih: [],                // 房间历史
    bh: [],                // 浏览历史
    bhSearch: '',
    bhTimer: 0,
    gr: []                 // 我的群组
  };

  function cmd(name, args) { return VRCX.command(name, args || {}); }

  /** {status, data} → 解出来的 JSON（VRChat 透传命令的回包形状）。 */
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

  function note(boxId, emptyId, text) {
    var empty = $(emptyId);
    if (empty) { empty.textContent = text; show(empty, !!text); }
  }

  /* ------------------------------------------------------------------ 加载 */

  function loadGameLog() {
    if (state.busy.gamelog) return;
    state.busy.gamelog = true;
    note(null, 'glEmpty', '');
    cmd('app__game_log_sessions_query', {
      search: '', filters: [], favoriteUserIds: [], dateFrom: '', dateTo: '',
      limit: 30, maxTableSize: 6, searchLimit: 10
    }).then(function (rows) {
      state.busy.gamelog = false;
      state.loaded.gamelog = true;
      state.gl = (rows || []).filter(function (r) { return r && typeof r === 'object'; });
      renderGameLog();
    }).catch(function (err) {
      state.busy.gamelog = false;
      note(null, 'glEmpty', window.VRCX ? window.VRCX.describeError(err) : '游戏日志读取失败。');
    });
  }

  function loadInstanceHistory() {
    if (state.busy.instancehistory) return;
    state.busy.instancehistory = true;
    note(null, 'ihEmpty', '');
    cmd('app__instance_history_query', {
      userId: state.userId, dateFrom: '', dateTo: '', limit: 40
    }).then(function (rows) {
      state.busy.instancehistory = false;
      state.loaded.instancehistory = true;
      state.ih = (rows || []).filter(function (r) { return r && typeof r === 'object'; });
      renderInstanceHistory();
    }).catch(function (err) {
      state.busy.instancehistory = false;
      note(null, 'ihEmpty', window.VRCX ? window.VRCX.describeError(err) : '房间历史读取失败。');
    });
  }

  function loadBrowse(search) {
    if (state.busy.browsehistory) return;
    state.busy.browsehistory = true;
    cmd('app__browse_history_query', {
      ownerUserId: state.userId, entityKind: null, cursor: null,
      limit: 60, search: search || '', dateFrom: '', dateTo: ''
    }).then(function (out) {
      state.busy.browsehistory = false;
      state.loaded.browsehistory = true;
      state.bh = (out && out.items) || [];
      renderBrowse();
    }).catch(function (err) {
      state.busy.browsehistory = false;
      if (!search) {
        note(null, 'bhEmpty', window.VRCX ? window.VRCX.describeError(err) : '浏览历史读取失败。');
      }
    });
  }

  function loadGroups() {
    if (state.busy.groups) return;
    state.busy.groups = true;
    note(null, 'grEmpty', '');
    /* ⚠️ 入参一律**平铺**：包装成 {input:...} 的是 ArgForms.encode（VRCX.command
       内部），这里再包一层就成 {input:{input:...}} —— 服务端 502
       `unknown field input, expected userId`，前端只表现为"没数据"。 */
    cmd('app__vrchat_group_user_groups_get', { userId: state.userId })
      .then(function (r) {
        state.busy.groups = false;
        state.loaded.groups = true;
        var list = unwrap(r);
        state.gr = (list || []).filter(function (g) { return g && typeof g === 'object'; });
        renderGroups();
      }).catch(function (err) {
        state.busy.groups = false;
        note(null, 'grEmpty', window.VRCX ? window.VRCX.describeError(err) : '群组读取失败。');
      });
  }

  /* ------------------------------------------------------------------ 渲染 */

  function rowCard() {
    return E('div', 'card card--low card--static toolrow');
  }

  function renderGameLog() {
    var box = $('glList');
    if (!box) return;
    clear(box);
    if (!state.gl.length) {
      note(null, 'glEmpty', state.loaded.gamelog ? '还没有游戏日志（要服务器旁边挂着游戏才会记）。' : '');
      return;
    }
    for (var i = 0; i < state.gl.length; i++) {
      var s = state.gl[i];
      var card = rowCard();
      var line = E('div', 'rowline');
      add(line, E('span', 'name', safe(s.worldName, '') || safe(s.groupName, '') || '（世界名未记录）'));
      var dur = durationText(s.duration);
      add(line, E('span', 'kind', dur));
      add(card, line);
      add(card, E('div', 'time', timeText(s.created_at || s.createdAt)));
      /* 同场的人：只画名字 + 时长（playerDurationRows 已按时间排序） */
      var players = s.playerDurationRows || [];
      var max = Math.min(players.length, 6);
      if (max > 0) {
        var who = [];
        for (var p = 0; p < max; p++) {
          var pn = safe(players[p] && players[p].displayName, '');
          if (pn) who.push(pn + (players[p].time > 0 ? ' ' + durationText(players[p].time) : ''));
        }
        if (who.length) add(card, E('div', 'body toolrow__sub', '同场：' + who.join(' · ')));
      }
      add(box, card);
    }
  }

  function renderInstanceHistory() {
    var box = $('ihList');
    if (!box) return;
    clear(box);
    if (!state.ih.length) {
      note(null, 'ihEmpty', state.loaded.instancehistory ? '还没有房间历史。' : '');
      return;
    }
    for (var i = 0; i < state.ih.length; i++) {
      var r = state.ih[i];
      var card = rowCard();
      var line = E('div', 'rowline');
      /* ⚠️ location 本体形如 wrld_…~region(jp)，绝不上屏 —— 只画世界名 */
      add(line, E('span', 'name', safe(r.worldName, '') || safe(r.groupName, '') || '（世界名未记录）'));
      add(line, E('span', 'kind', durationText(r.time)));
      add(card, line);
      add(card, E('div', 'time', timeText(r.created_at || r.createdAt)));
      add(box, card);
    }
  }

  var KIND_TEXT = { world: '世界', avatar: '模型', group: '群组', user: '用户' };

  function renderBrowse() {
    var box = $('bhList');
    if (!box) return;
    clear(box);
    note(null, 'bhEmpty', '');
    if (!state.bh.length) {
      note(null, 'bhEmpty', state.loaded.browsehistory
        ? (state.bhSearch ? '没有匹配的浏览记录。' : '还没有浏览记录。') : '');
      return;
    }
    for (var i = 0; i < state.bh.length; i++) {
      var it = state.bh[i];
      if (!it || !it.title) continue;
      var card = E('div', 'card card--low card--static toolrow');
      var line = E('div', 'rowline');
      add(line, E('span', 'name', safe(it.title, '')));
      add(line, E('span', 'kind', KIND_TEXT[it.entityKind] || ''));
      add(card, line);
      /* 世界行的浏览记录可点开世界详情 */
      if (it.entityKind === 'world' && it.entityId
          && window.__SCREENS_TOOLS__ && it.entityId) {
        card.style.cursor = 'pointer';
        (function (wid) {
          card.addEventListener('click', function () {
            openWorldPanel(wid);
          });
        })(it.entityId);
      }
      var bits = [];
      if (it.viewCount > 1) bits.push('浏览 ' + it.viewCount + ' 次');
      var when = timeText(it.lastViewedAt || it.last_viewed_at);
      if (when) bits.push(when);
      if (bits.length) add(card, E('div', 'time', bits.join(' · ')));
      add(box, card);
    }
  }

  function renderGroups() {
    var box = $('grList');
    if (!box) return;
    clear(box);
    if (!state.gr.length) {
      note(null, 'grEmpty', state.loaded.groups ? '还没有加入任何群组。' : '');
      return;
    }
    for (var i = 0; i < state.gr.length; i++) {
      var g = state.gr[i];
      var card = rowCard();
      var line = E('div', 'rowline');
      add(line, E('span', 'name', safe(g.name, '') || '（未命名的群组）'));
      if (g.memberId && g.isRepresenting) add(line, E('span', 'kind', '展示中'));
      add(card, line);
      var desc = scrubIds(safe(g.description, ''));
      if (desc) add(card, E('div', 'body toolrow__sub', desc));
      add(box, card);
    }
  }

  /* ------------------------------------------------------------------ 详情·记录 */

  var FRIEND_LOG_TYPE_TEXT = {
    displayName: '改名',
    trustLevel: '信任等级变化',
    avatar: '换模型',
    location: '换位置',
    online: '上线',
    offline: '下线',
    friendRequest: '好友申请',
    friend: '成为好友',
    unfriend: '解除好友'
  };

  function detailLogSeq() {
    return detailLogSeq.n = (detailLogSeq.n || 0) + 1;
  }

  function loadDetailLog(userId) {
    var seq = detailLogSeq();
    var box = $('dLog');
    var head = $('dLogHead');
    if (!box || !head) return;
    clear(box);
    show(head, false);
    show(box, false);
    if (!userId) return;
    var query = {
      userId: state.userId, targetUserId: userId,
      types: [], excludedTypes: [], dateFrom: '', dateTo: '',
      cursor: null, limit: 20
    };
    cmd('app__friend_log_history_query', { query: query }).then(function (rows) {
      if (seq !== detailLogSeq.n) return;   // 只吃最后一次点开
      var list = (rows || []).filter(function (r) { return r && typeof r === 'object'; });
      if (!list.length) return;             // 没有记录就整段收起
      for (var i = 0; i < list.length; i++) {
        var r = list[i];
        var card = rowCard();
        var line = E('div', 'rowline');
        var typeText = FRIEND_LOG_TYPE_TEXT[r.type] || safe(r.type, '变动');
        var text = typeText;
        if (r.type === 'displayName' && r.previousDisplayName) {
          text = safe(r.previousDisplayName, '') + ' → ' + safe(r.displayName, '');
        }
        add(line, E('span', 'kind', timeText(r.createdAt || r.created_at)));
        add(line, E('span', 'name', text));
        add(card, line);
        add(box, card);
      }
      show(head, true);
      show(box, true);
    }).catch(function () {
      /* 记录拉不到就收起 —— 详情面板的主体（动态/资料）已经够用 */
      if (seq === detailLogSeq.n) { show(head, false); show(box, false); }
    });
  }

  /* ------------------------------------------------------------------ 详情·备注 */

  var memoUserSeq = 0;

  function loadMemo(userId) {
    var seq = ++memoUserSeq;
    var section = $('dMemoSection');
    var editBtn = $('dMemoEdit');
    var text = $('dMemoText');
    var form = $('dMemoForm');
    if (!section || !text) return;
    show(section, false);
    show(form, false);
    show(editBtn, false);
    if (!userId) return;   // 非好友的动态用户没有 id，写不了备注
    state.memoUserId = userId;
    cmd('app__memo_get_user', { userId: userId }).then(function (out) {
      if (seq !== memoUserSeq) return;   // 只吃最后一次点开
      var memo = out && typeof out === 'object' ? safe(out.memo, '') : '';
      text.textContent = memo || '还没有备注。';
      if (memo) text.title = '编辑于 ' + timeText(out.editedAt);
      else text.title = '';
      show(section, true);
      show(editBtn, true);
    }).catch(function () {
      /* 备注读不到就整段收起 —— 主体（动态/资料/记录）已经够用 */
      if (seq === memoUserSeq) show(section, false);
    });
  }

  function saveMemo() {
    var userId = state.memoUserId;
    var input = $('dMemoInput');
    var text = $('dMemoText');
    var form = $('dMemoForm');
    var editBtn = $('dMemoEdit');
    if (!userId || !input || !text) return;
    var value = String(input.value || '').trim();
    cmd('app__memo_save_user', { userId: userId, memo: value }).then(function (out) {
      text.textContent = value ? scrubIds(value) : '还没有备注。';
      if (out && out.editedAt) text.title = '编辑于 ' + timeText(out.editedAt);
      show(form, false);
      show(text, true);
      show(editBtn, true);
    }).catch(function (err) {
      /* 保存失败不关表单，让用户改完重试 —— 错误就地写在按钮行后面 */
      var form2 = $('dMemoForm');
      if (form2) {
        var note = document.getElementById('dMemoError');
        if (!note) {
          note = document.createElement('p');
          note.className = 'emptynote';
          note.id = 'dMemoError';
          form2.appendChild(note);
        }
        note.textContent = window.VRCX ? window.VRCX.describeError(err) : '保存失败。';
        show(note, true);
      }
    });
  }

  /* ------------------------------------------------------------------ 活动与统计 */

  function loadActivity() {
    var rankBox = $('acRank');
    var rankEmpty = $('acRankEmpty');
    var bars = $('acBars');
    var acEmpty = $('acEmpty');
    var peak = $('acPeak');
    var range = $('acRange');
    if (!rankBox && !bars) return;
    if (rankBox) clear(rankBox);
    if (bars) clear(bars);
    show(rankEmpty, false);
    show(acEmpty, false);
    show(peak, false);
    if (range) range.textContent = '近 7 天';

    /* 头像使用排行（本地库聚合） */
    cmd('app__avatar_usage_ranking', { userId: state.userId, limit: 8 })
      .then(function (rows) {
        var list = (rows || []).filter(function (r) { return r && typeof r === 'object'; });
        if (!rankBox) return;
        clear(rankBox);
        if (!list.length) {
          rankEmpty.textContent = '还没有模型使用记录。';
          show(rankEmpty, true);
          return;
        }
        for (var i = 0; i < list.length; i++) {
          var r = list[i];
          var row = E('div', 'rankrow');
          add(row, E('span', 'rankrow__n', String(i + 1)));
          add(row, E('span', 'rankrow__name', safe(r.name, '') || '（未命名的模型）'));
          var t = durationText(r.timeSpent);
          add(row, E('span', 'rankrow__time', t || '—'));
          add(rankBox, row);
        }
      })
      .catch(function (err) {
        if (rankEmpty) {
          rankEmpty.textContent = window.VRCX ? window.VRCX.describeError(err) : '排行读取失败。';
          show(rankEmpty, true);
        }
      });

    /* 在线时段：168 桶 = 7 天 × 24 小时，按小时求和画一天内的分布 */
    cmd('app__activity_view', {
      ownerUserId: state.userId, targetUserId: state.userId, isSelf: true,
      rangeDays: 7, utcOffsetMinutes: -new Date().getTimezoneOffset(),
      nowMs: Date.now(), forceRefresh: false
    }).then(function (out) {
      if (!out || typeof out !== 'object') return;
      var buckets = out.rawBuckets || [];
      if (!out.hasAnyData || buckets.length < 24) {
        if (acEmpty) acEmpty.textContent = '这 7 天还没有在线记录。';
        show(acEmpty, true);
        return;
      }
      var hours = [];
      for (var h = 0; h < 24; h++) hours.push(0);
      for (var i = 0; i < buckets.length; i++) {
        hours[i % 24] += Number(buckets[i]) || 0;
      }
      var max = 0;
      for (var k = 0; k < 24; k++) if (hours[k] > max) max = hours[k];
      if (!max) {
        if (acEmpty) acEmpty.textContent = '这 7 天还没有在线记录。';
        show(acEmpty, true);
        return;
      }
      var box = $('acBars');
      if (box) {
        clear(box);
        var wrap = document.createElement('div');
        wrap.className = 'hourbars';
        for (var b = 0; b < 24; b++) {
          var bar = document.createElement('span');
          bar.className = 'bar';
          var v = Math.round(hours[b] / max * 100);
          bar.style.setProperty('--v', String(Math.max(2, v)));
          wrap.appendChild(bar);
        }
        add(box, wrap);
        var axis = document.createElement('div');
        axis.className = 'houraxis';
        ['0时', '6时', '12时', '18时', '23时'].forEach(function (t) {
          add(axis, E('span', null, t));
        });
        add(box, axis);
      }
      var hs = Number(out.peakHourStart), he = Number(out.peakHourEnd);
      if (peak && isFinite(hs) && isFinite(he)) {
        peak.textContent = '最活跃：' + pad2(hs) + ':00–' + pad2(he) + ':00（近 7 天 ' +
          Number(out.filteredEventCount || 0) + ' 条记录）';
        show(peak, true);
      }
    }).catch(function (err) {
      if (acEmpty) acEmpty.textContent = window.VRCX ? window.VRCX.describeError(err) : '时段读取失败。';
      show(acEmpty, true);
    });
  }

  /* ------------------------------------------------------------------ 接线 */

  /**
   * 懒加载/刷新策略（2026-09-27 修两个真 bug 后的口径）：
   *  · 本地四屏（游戏日志/房间历史/浏览历史/统计）走本地 SQLite，**每次进入都重拉**
   *    —— 数据会随服务器侧持续变化，一次性的 loaded 标记会让人对着旧数据起疑；
   *  · 群组走 VRChat 远程请求，保留会话级缓存（loaded 一次），避免反复打 API。
   *  · busy 期间忽略重复触发；渲染函数自己会整体重画，不会叠层。
   */
  var REMOTE_CACHED = { groups: true };

  function ensureLoaded(page) {
    if (!state.authenticated || state.busy[page]) return;
    if (REMOTE_CACHED[page] && state.loaded[page]) return;
    if (page === 'gamelog') loadGameLog();
    else if (page === 'instancehistory') loadInstanceHistory();
    else if (page === 'browsehistory') loadBrowse(state.bhSearch || '');
    else if (page === 'groups') loadGroups();
    else if (page === 'activity') loadActivity();
    else if (page === 'graph') loadGraph();
    else if (page === 'myavatars') loadMyAvatarsPage();
  }

  function wire() {
    /* 工具卡：main.js 的 data-goto 负责切页，这里补上懒加载 */
    /* 工具入口两处：更多页的 .toolnav 卡 + 动态页的「日志」chip（同一 data-tool 协议） */
    var cards = document.querySelectorAll('[data-tool]');
    for (var i = 0; i < cards.length; i++) {
      (function (card) {
        card.addEventListener('click', function () {
          ensureLoaded(card.getAttribute('data-tool'));
        });
      })(cards[i]);
    }
    /* 浏览历史搜索：服务端 search 字段；300ms 防抖后重查 */
    var bhSearch = $('bhSearch');
    if (bhSearch) bhSearch.addEventListener('input', function () {
      state.bhSearch = bhSearch.value.trim();
      if (state.bhTimer) clearTimeout(state.bhTimer);
      state.bhTimer = setTimeout(function () {
        if (state.authenticated) loadBrowse(state.bhSearch);
      }, 300);
    });
    /* 详情·记录 + 备注：screens.js 点开好友时派发 vrcx:detail */
    document.addEventListener('vrcx:detail', function (e) {
      var d = e && e.detail;
      loadDetailLog(d && d.id);
      loadMemo(d && d.id);
    });
    /* ⚠️ 深链：`#device,t-activity` 这类启动 hash 由 main.js 的 goto() 切页，
       它不知道懒加载的存在 —— 不在这里补一刀，深链直达的工具页就是一张
       永远空白的列表（2026-09-27 实测路径）。 */
    var want = (location.hash || '').match(/t-(gamelog|instancehistory|browsehistory|groups|activity|graph|myavatars)/);
    if (want) ensureLoaded(want[1]);
    /* 世界详情弹层 */
    var wclose = $('worldClose');
    if (wclose) wclose.addEventListener('click', function () { show($('worldPanel'), false); });
    var wpanel = $('worldPanel');
    if (wpanel) wpanel.addEventListener('click', function (e) {
      if (e.target === wpanel) show(wpanel, false);
    });
    /* 「收藏这个世界」（0.2.31-t4）：真正的弹层与写入在 screens.js
       （它管着收藏基线），这里只把"当前看的是哪个世界"递过去。 */
    var wfav = $('wFav');
    if (wfav) wfav.addEventListener('click', function () {
      var opener = window.__SCREENS__ && window.__SCREENS__.openFavPick;
      if (!currentWorld.id) { return; }   // 数据没读到时按钮本来就是隐藏的
      if (opener) opener('world', currentWorld.id, currentWorld.name);
    });

    /* 共同好友图：画布点击 + 抓取按钮 */
    var gcanvas = $('graphCanvas');
    if (gcanvas) gcanvas.addEventListener('click', graphTap);
    var gfetch = $('graphFetchBtn');
    if (gfetch) gfetch.addEventListener('click', startGraphFetch);
    var gcancel = $('graphCancelBtn');
    if (gcancel) gcancel.addEventListener('click', cancelGraphFetch);

    /* 备注表单 */
    var editBtn = $('dMemoEdit');
    if (editBtn) editBtn.addEventListener('click', function () {
      var input = $('dMemoInput');
      var text = $('dMemoText');
      var form = $('dMemoForm');
      var err = $('dMemoError');
      if (err) show(err, false);
      if (input && text && form) {
        input.value = text.textContent === '还没有备注。' ? '' : text.textContent;
        show(text, false);
        show(form, true);
        input.focus();
      }
    });
    var saveBtn = $('dMemoSave');
    if (saveBtn) saveBtn.addEventListener('click', saveMemo);
    var cancelBtn = $('dMemoCancel');
    if (cancelBtn) cancelBtn.addEventListener('click', function () {
      var text = $('dMemoText');
      var form = $('dMemoForm');
      var err = $('dMemoError');
      if (err) show(err, false);
      if (text && form) { show(form, false); show(text, true); }
    });
  }

  function onState(s) {
    if (!s) return;
    var wasAuthed = state.authenticated;
    state.authenticated = !!s.authenticated;
    state.userId = s.userId || state.userId;
    /* 本地库读取（通知/服务器配置）不依赖 VRChat 会话 —— 认证翻转就跑，
       别等 loadAll 成功：会话 401 时这些数据照样该上屏。 */
    if (!wasAuthed && state.authenticated) {
      if (window.__SCREENS__ && typeof window.__SCREENS__.loadLocal === 'function') {
        window.__SCREENS__.loadLocal();
      }
    }
    if (!s.authenticated) {
      state.loaded = {};
      state.busy = {};
      state.gl = [];
      state.ih = [];
      state.bh = [];
      state.gr = [];
      return;
    }
    /* 刚拿到登录态时补一次深链检查 —— wire() 那次 hash 检查跑在认证完成之前，
       ensureLoaded 的 authenticated 门会把它挡掉（本地四屏的"重进刷新"也在这里
       顺带成立：掉线重连后回到工具页就是新数据）。 */
    if (!wasAuthed) {
      var want = (location.hash || '').match(/t-(gamelog|instancehistory|browsehistory|groups|activity|graph|myavatars)/);
      if (want) ensureLoaded(want[1]);
    }
  }


  /* ------------------------------------------------------------------ 共同好友图 */

  var graph = { nodes: [], links: [], adj: [], nodeCount: 0, ready: false, fetching: false,
                lastCovered: -1, stableCount: 0,
                /* 选中态：selected = 节点下标（-1 = 没选）；lit = 相关节点的集合
                   （选中节点 + 它的直接邻居），null 表示没选中。渲染与命中测试
                   都读这两个，别再各自算一遍。 */
                selected: -1, lit: null,
                friendCount: 0, linkCount: 0,
                /* 抓取运行的真进度来自 fetch_start 回包（服务端同一 owner 已有
                   运行在跑时会**原样返回当前状态**）；runId 用来识别"我们那轮
                   已经结束，这次轮询顺手开了新的一轮"。friendIds 要留存 ——
                   读进度也得带全名单，否则会开出一轮只抓一两个人的新运行。 */
                runId: 0, friendIds: [], startedMs: 0,
                /* 上一帧「真的画出来几个名字」、选中的那个人有没有被标上 ——
                   drawGraph 每帧重写。画布上的文字取不回来，没有这两个计数
                   「不显示用户名」就只能靠肉眼，所以专门留出验收口径。 */
                drawnLabels: 0, labeledSel: -1 };

  /** 快照 -> 画布数据。名字查名单（裸 id 绝不上屏），颜色随在线状态。 */
  function loadGraph() {
    var status = $('graphStatus');
    if (status) status.textContent = '正在读取共同好友数据…';
    cmd('app__mutual_graph_snapshot_get', { userId: state.userId }).then(function (snap) {
      var friendIds = (snap && snap.friendIds) || [];
      var links = (snap && snap.links) || [];
      if (!friendIds.length) {
        if (status) status.textContent = '';
        var empty = $('graphEmpty');
        if (empty) {
          empty.textContent = '还没有共同好友数据 —— 抓取一次就有了（服务端逐个好友去 VRChat 拉，好友多时要等一会儿）。';
          show(empty, true);
        }
        show($('graphFetchRow'), true);
        return;
      }
      show($('graphEmpty'), false);
      show($('graphFetchRow'), false);
      buildGraph(friendIds, links);
      statusText(friendIds.length, links.length);
      drawGraph();
    }).catch(function (err) {
      if (status) status.textContent = window.VRCX ? window.VRCX.describeError(err) : '共同好友数据读取失败。';
    });
  }

  function statusText(nFriend, nLinks) {
    var status = $('graphStatus');
    if (status) status.textContent = nFriend + ' 位好友 · ' + nLinks + ' 条共同好友关系';
  }

  function buildGraph(friendIds, links) {
    /* 名单归 screens.js 所有 —— 走它的调试钩子拿，别复制第二份状态 */
    var scr = window.__SCREENS__ && window.__SCREENS__.state;
    var byId = (scr && scr.roster && scr.roster.byId) || {};
    var nodes = [];
    var index = {};
    var me = state.userId || 'me';
    nodes.push({ id: me, name: '我', me: true, state: 'online' });
    index[me] = 0;
    for (var i = 0; i < friendIds.length; i++) {
      var id = friendIds[i];
      if (!id || index[id] !== undefined) continue;
      var rec = byId[id];
      var name = (rec && (rec.displayName || rec.username)) || '好友';
      index[id] = nodes.length;
      nodes.push({ id: id, name: name, state: (rec && rec.state) || 'offline', deg: 0 });
    }
    var els = [];
    var adj = [];
    for (var z = 0; z < nodes.length; z++) adj.push({});
    for (var l = 0; l < links.length; l++) {
      var a = index[links[l].friendId], b = index[links[l].mutualId];
      if (a === undefined || b === undefined || a === b) continue;
      els.push([a, b]);
      /* 共同好友关系**去重**后计入度数：服务端同一对可能出现多条，
         不去重会让"相关好友 N 位"虚高、也会让标签阈值选错节点。 */
      adj[a][b] = true;
      adj[b][a] = true;
    }
    for (var q = 0; q < adj.length; q++) nodes[q].deg = Object.keys(adj[q]).length;
    graph.nodes = nodes;
    graph.links = els;
    graph.adj = adj;
    graph.nodeCount = nodes.length;
    graph.friendCount = friendIds.length;
    graph.linkCount = els.length;
    graph.selected = -1;
    graph.lit = null;
    simulateGraph();
  }

  /** 力导向布局（一次性算 260 tick，不进动画循环 —— 手机上省电）。 */
  function simulateGraph() {
    var n = graph.nodes, E = graph.links;
    var W = 900, H = 500;
    for (var i = 0; i < n.length; i++) {
      var ang = (i / n.length) * Math.PI * 2;
      n[i].x = W / 2 + Math.cos(ang) * (n[i].me ? 0 : 260);
      n[i].y = H / 2 + Math.sin(ang) * (n[i].me ? 0 : 160);
      n[i].vx = 0; n[i].vy = 0;
    }
    var rep = 2600, spring = 0.015, damp = 0.82;
    for (var t = 0; t < 260; t++) {
      for (var a = 0; a < n.length; a++) {
        for (var b = a + 1; b < n.length; b++) {
          var dx = n[b].x - n[a].x, dy = n[b].y - n[a].y;
          var d2 = dx * dx + dy * dy + 40;
          var d = Math.sqrt(d2);
          var f = rep / d2;
          var fx = dx / d * f, fy = dy / d * f;
          n[a].vx -= fx; n[a].vy -= fy;
          n[b].vx += fx; n[b].vy += fy;
        }
      }
      for (var e = 0; e < E.length; e++) {
        var pa = n[E[e][0]], pb = n[E[e][1]];
        var ddx = pb.x - pa.x, ddy = pb.y - pa.y;
        var dist = Math.sqrt(ddx * ddx + ddy * ddy) || 1;
        var fo = (dist - 70) * spring;
        var fxo = ddx / dist * fo, fyo = ddy / dist * fo;
        pa.vx += fxo; pa.vy += fyo;
        pb.vx -= fxo; pb.vy -= fyo;
      }
      for (var c = 0; c < n.length; c++) {
        var node = n[c];
        /* 向心重力：没有它节点会被斥力一路推画出画布（0.2.16 空白图根因之一） */
        node.vx += (W / 2 - node.x) * 0.0012;
        node.vy += (H / 2 - node.y) * 0.0012;
        node.vx *= damp; node.vy *= damp;
        node.x += Math.max(-14, Math.min(14, node.vx));
        node.y += Math.max(-14, Math.min(14, node.vy));
        if (node.me) { node.x = W / 2; node.y = H / 2; }
      }
    }
    graph.ready = true;
  }

  var PRESENCE_COLOR = { online: '#1d9e75', active: '#ba7517', offline: '#b6b6c2' };
  var PRESENCE_LABEL = { online: '在线', active: '活跃', offline: '离线' };
  var SEL_COLOR = '#0054d6';
  var DIM_COLOR = 'rgba(182,182,194,0.30)';

  /* ⚠️⚠️ 画布变换**只有这一处**定义：drawGraph 画、graphTap 反算、标签摆位全走它。
     0.2.13~0.2.15 的画布是把 900x500 布局空间**线性拉伸**到画布（sx=w/900, sy=h/500），
     所以那时 graphTap 里写 `*900 / *500` 是对的 —— 点得中。0.2.16 起 drawGraph 改成
     **等比 bbox 适配**（uniform scale + 居中偏移），graphTap 却只改了反算公式的一半、
     仍留着 900x500 → 点击坐标整体偏移（横向差 6%、纵向差 19%），靠上靠下的节点
     永远点不中。用户看到的就是"小点点点不动"，而 0.2.13~0.2.15 是能点的。 */
  function graphTransform(w, h) {
    var n = graph.nodes;
    var minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
    for (var i = 0; i < n.length; i++) {
      if (n[i].x < minX) minX = n[i].x;
      if (n[i].y < minY) minY = n[i].y;
      if (n[i].x > maxX) maxX = n[i].x;
      if (n[i].y > maxY) maxY = n[i].y;
    }
    var pad = 30;
    var spanX = (maxX - minX) || 1, spanY = (maxY - minY) || 1;
    var scale = Math.min((w - pad * 2) / spanX, (h - pad * 2) / spanY, 1.6);
    return {
      scale: scale,
      offX: pad + ((w - pad * 2) - spanX * scale) / 2 - minX * scale,
      offY: pad + ((h - pad * 2) - spanY * scale) / 2 - minY * scale
    };
  }

  function nodeRadius(node) {
    return node.me ? 7 : (3 + Math.min(4, (node.deg || 0) * 0.7));
  }

  /** 默认标名字的节点：我 + 度数最高的十几个（"枢纽"好友）。
      242 个节点全标会糊成一条黑带，全不标又认不出谁是谁 —— 折中。 */
  function labelThreshold() {
    var degs = [];
    for (var i = 1; i < graph.nodes.length; i++) degs.push(graph.nodes[i].deg || 0);
    if (!degs.length) return 9999;
    degs.sort(function (a, b) { return b - a; });
    var k = Math.min(degs.length - 1, 11);
    return Math.max(2, degs[k]);
  }

  function drawGraph() {
    var canvas = $('graphCanvas');
    if (!canvas || !graph.ready) return;
    var dpr = window.devicePixelRatio || 1;
    var w = canvas.clientWidth || 800, h = canvas.clientHeight || 420;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    var ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    var n = graph.nodes, E = graph.links;
    var T = graphTransform(w, h);
    function px(x) { return x * T.scale + T.offX; }
    function py(y) { return y * T.scale + T.offY; }
    var sel = graph.selected;
    var lit = graph.lit;
    var i2, e2, a2, b2;

    /* 1) 连线：没选中时统一淡灰；选中时"相关"深蓝加粗、其余压到几乎看不见。
       分两趟画是为了每趟只设一次 strokeStyle —— 1382 条线逐条改样式太慢。 */
    ctx.strokeStyle = sel < 0 ? 'rgba(67,70,84,0.18)' : 'rgba(67,70,84,0.06)';
    ctx.lineWidth = 0.6;
    ctx.beginPath();
    for (e2 = 0; e2 < E.length; e2++) {
      a2 = E[e2][0]; b2 = E[e2][1];
      if (sel >= 0 && (a2 === sel || b2 === sel)) continue;
      ctx.moveTo(px(n[a2].x), py(n[a2].y));
      ctx.lineTo(px(n[b2].x), py(n[b2].y));
    }
    ctx.stroke();
    if (sel >= 0) {
      ctx.strokeStyle = 'rgba(0,84,214,0.60)';
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      for (e2 = 0; e2 < E.length; e2++) {
        a2 = E[e2][0]; b2 = E[e2][1];
        if (a2 !== sel && b2 !== sel) continue;
        ctx.moveTo(px(n[a2].x), py(n[a2].y));
        ctx.lineTo(px(n[b2].x), py(n[b2].y));
      }
      ctx.stroke();
    }

    /* 2) 节点：不选中时全画；选中时先把"无关的"压成浅灰，再画相关的。
       选中环用「白垫底 + 蓝外圈」两笔，深浅背景上都看得见。 */
    function paintNode(idx, dim) {
      var nd = n[idx];
      var isSel = (idx === sel);
      var r = nodeRadius(nd) + (isSel ? 3 : 0);
      ctx.beginPath();
      ctx.arc(px(nd.x), py(nd.y), r, 0, Math.PI * 2);
      ctx.fillStyle = dim ? DIM_COLOR
        : (nd.me ? SEL_COLOR : (PRESENCE_COLOR[nd.state] || PRESENCE_COLOR.offline));
      ctx.fill();
      if (isSel) {
        ctx.lineWidth = 2.5;
        ctx.strokeStyle = '#ffffff';
        ctx.stroke();
        ctx.beginPath();
        ctx.arc(px(nd.x), py(nd.y), r + 2.5, 0, Math.PI * 2);
        ctx.lineWidth = 1.5;
        ctx.strokeStyle = SEL_COLOR;
        ctx.stroke();
      }
    }
    if (sel < 0) {
      for (i2 = 0; i2 < n.length; i2++) paintNode(i2, false);
    } else {
      for (i2 = 0; i2 < n.length; i2++) if (!lit[i2]) paintNode(i2, true);
      for (i2 = 0; i2 < n.length; i2++) if (lit[i2] && i2 !== sel) paintNode(i2, false);
      paintNode(sel, false);   /* 选中者最后画，邻居再多也不许盖住它 */
    }

    /* 3) 名字。选中时只标"相关的人"（正是用户要看的"有关的用户"）；
       没选中时标我 + 度数最高的十几个。放不下就跳过 —— 1600 个标签会糊成黑带。 */
    var th = labelThreshold();
    var rest = [];
    for (i2 = 1; i2 < n.length; i2++) {
      if ((sel >= 0) ? !!lit[i2] : ((n[i2].deg || 0) >= th)) rest.push(i2);
    }
    /* 度数高的先抢位置，选中的插到队首（最不该被挤掉） */
    rest.sort(function (a, b) { return (n[b].deg || 0) - (n[a].deg || 0); });
    if (sel >= 0) {
      var si = rest.indexOf(sel);
      if (si > 0) rest.splice(si, 1);
      rest.unshift(sel);
    }
    var placed = [];
    function collide(x0, y0, x1, y1) {
      for (var p = 0; p < placed.length; p++) {
        var q = placed[p];
        if (x0 < q[2] && x1 > q[0] && y0 < q[3] && y1 > q[1]) return true;
      }
      return false;
    }
    ctx.textBaseline = 'middle';
    ctx.textAlign = 'left';
    var queue = [0].concat(rest);
    for (var qi = 0; qi < queue.length; qi++) {
      var ni = queue[qi];
      var nn = n[ni];
      var isSel2 = (ni === sel);
      var label = String(nn.name || '');
      if (label.length > 10) label = label.slice(0, 9) + '…';
      var fs = isSel2 ? 13 : 11;
      ctx.font = (isSel2 ? '600 ' : '') + fs + 'px system-ui, -apple-system, "Noto Sans SC", sans-serif';
      var tw = ctx.measureText(label).width;
      var cx0 = px(nn.x), cy0 = py(nn.y);
      var rad = nodeRadius(nn) + (isSel2 ? 3 : 0) + 4;
      var lx = cx0 + rad;
      if (lx + tw > w - 4) lx = cx0 - rad - tw;          /* 右边放不下就摆左边 */
      var top = cy0 - fs / 2 - 1;
      if (collide(lx - 2, top, lx + tw + 2, top + fs + 2)) continue;
      placed.push([lx - 2, top, lx + tw + 2, top + fs + 2, ni]);
      /* 名字垫一层半透明底：密集处直接贴字会看不清；不做描边（小字号描边更糊） */
      ctx.fillStyle = isSel2 ? 'rgba(255,255,255,0.92)' : 'rgba(255,255,255,0.72)';
      ctx.fillRect(lx - 2, top, tw + 4, fs + 2);
      ctx.fillStyle = isSel2 ? SEL_COLOR : '#3a3d4a';
      ctx.fillText(label, lx, cy0);
    }
    /* 记下这一帧**真的画出来**几个名字。只记数量不记内容：无头断言要的是
       "选中的那个人有没有名字出现在画布上"，而画布上的文字是取不回来的。
       没有这个计数，「不显示用户名」这条就只能靠肉眼。 */
    graph.drawnLabels = placed.length;
    graph.labeledSel = (sel >= 0 && placed.some(function (r) { return r[4] === sel; })) ? sel : -1;
  }

  /** 选中 / 取消选中一个节点，并刷新状态行文案。 */
  function selectNode(i) {
    if (i === undefined || i === null || i < 0) {
      graph.selected = -1;
      graph.lit = null;
      statusText(graph.friendCount, graph.linkCount);
      drawGraph();
      return;
    }
    graph.selected = i;
    var lit = {};
    lit[i] = true;
    var adj = graph.adj[i] || {};
    for (var k in adj) if (Object.prototype.hasOwnProperty.call(adj, k)) lit[k] = true;
    graph.lit = lit;
    var node = graph.nodes[i];
    var status = $('graphStatus');
    if (status) {
      status.textContent = '已选中 ' + node.name + ' · ' + (PRESENCE_LABEL[node.state] || '')
        + ' · 与 ' + Object.keys(adj).length + ' 位好友有共同好友关系（再点一次打开资料，点空白取消）';
    }
    drawGraph();
  }

  function graphTap(e) {
    var canvas = $('graphCanvas');
    if (!canvas || !graph.ready) return;
    var rect = canvas.getBoundingClientRect();
    var mx = e.clientX - rect.left;
    var my = e.clientY - rect.top;
    var T = graphTransform(rect.width, rect.height);
    var n = graph.nodes;
    var best = -1, bestD = 1e9;
    for (var i = 0; i < n.length; i++) {
      var dx = n[i].x * T.scale + T.offX - mx;
      var dy = n[i].y * T.scale + T.offY - my;
      var d2 = dx * dx + dy * dy;
      if (d2 < bestD) { bestD = d2; best = i; }
    }
    /* ⚠️ 命中半径是**屏幕像素**，不是布局单位。布局里节点间距常常只有几个单位，
       按布局单位判命中会让相邻节点互相抢 —— 又一个"看着能点、其实点不中"。 */
    if (best < 0 || bestD > 26 * 26) {
      selectNode(-1);                       /* 点空白 = 取消选中 */
      return;
    }
    if (graph.selected === best) {
      /* 两步式：第一次点 = 高亮看清关系；再点同一点 = 打开资料。
         一次性直接开详情会把"高亮"这一步永远盖掉，而用户要的正是先看清关系。

         ⚠️⚠️ 详情抽屉**在图页上永远显示不出来**，实测（无头 CDP，innerWidth=1090）：
           `fillDetail()` 已把 `#detail` 的 hidden 去掉，`getComputedStyle().display`
           仍是 `none` —— 因为 app.css 双栏档有一条
           `.app__content.is-wide .detail { display: none }`，而图页恰好被打上
           `is-wide`（`wide = tab !== 'feed' && tab !== 'friends'`）。
           用户看到的就是"文案说再点一次看详情，点了没反应"。
         详情是**好友页**的常驻右栏，所以先切回好友页再开 —— 走的是和用户手点
         导航项完全相同的路径，不新增私有 API。 */
      var node = graph.nodes[best];
      if (!node.me && window.__SCREENS__ && window.__SCREENS__.openDetail) {
        var navFriends = document.querySelector('.navitem[data-goto="friends"]');
        if (navFriends) navFriends.click();
        window.__SCREENS__.openDetail(node.id, node.name);
      }
      return;
    }
    selectNode(best);
  }

  /* 验收钩子：无头断言要知道每个节点画在画布的哪个像素上。
     没有它就只能"按比例猜坐标点一下" —— 那正是这个图反复出问题的根源。 */
  window.__GRAPH__ = {
    info: function () {
      var c = $('graphCanvas');
      var rect = c ? c.getBoundingClientRect() : { width: 0, height: 0 };
      return {
        ready: graph.ready, nodeCount: graph.nodeCount, linkCount: graph.linkCount,
        friendCount: graph.friendCount, selected: graph.selected,
        threshold: labelThreshold(),
        labelCount: graph.drawnLabels || 0,
        labeledSel: (graph.labeledSel === undefined ? -1 : graph.labeledSel),
        canvas: { w: rect.width, h: rect.height, dpr: window.devicePixelRatio || 1 },
        transform: graphTransform(rect.width, rect.height)
      };
    },
    /** 第 i 个节点在**画布上的 CSS 像素坐标**（相对画布左上角）+ 页面绝对坐标 */
    at: function (i) {
      var c = $('graphCanvas');
      if (!c) return null;
      var rect = c.getBoundingClientRect();
      var T = graphTransform(rect.width, rect.height);
      var nd = graph.nodes[i];
      if (!nd) return null;
      return { i: i, name: nd.name, deg: nd.deg, state: nd.state,
               x: nd.x * T.scale + T.offX, y: nd.y * T.scale + T.offY,
               pageX: rect.left + nd.x * T.scale + T.offX,
               pageY: rect.top + nd.y * T.scale + T.offY };
    },
    find: function (name) {
      for (var i = 0; i < graph.nodes.length; i++) {
        if (graph.nodes[i].name === name) return window.__GRAPH__.at(i);
      }
      return null;
    },
    /** 度数最高的 k 个节点（探针最爱点"枢纽"，它们最容易点中） */
    top: function (k) {
      var idx = [];
      for (var i = 1; i < graph.nodes.length; i++) idx.push(i);
      idx.sort(function (a, b) { return (graph.nodes[b].deg || 0) - (graph.nodes[a].deg || 0); });
      var out = [];
      for (var j = 0; j < Math.min(k || 8, idx.length); j++) out.push(window.__GRAPH__.at(idx[j]));
      return out;
    },
    select: function (i) { selectNode(i); }
  };

  function startGraphFetch() {
    if (graph.fetching) return;
    /* ⚠️ 名单归 screens.js 所有（与 buildGraph 同一约定），别读本文件的 state ——
       这份 state 里根本没有 roster 字段，读它会 TypeError 并静默吞掉整个点击。 */
    var scr = window.__SCREENS__ && window.__SCREENS__.state;
    var order = (scr && scr.roster && scr.roster.order) || [];
    var friendIds = [];
    for (var i = 0; i < order.length; i++) friendIds.push(order[i]);
    /* ⚠️ 服务端硬校验 friendIds 非空 —— 空数组会直接被拒（0.2.16 修的就是它） */
    if (!friendIds.length) {
      var st0 = $('graphStatus');
      if (st0) st0.textContent = '名单还没加载好，稍等几秒再点。';
      return;
    }
    graph.fetching = true;
    graph.total = friendIds.length;
    graph.friendIds = friendIds;
    graph.startedMs = Date.now();
    graph.runId = 0;
    var status = $('graphStatus');
    if (status) status.textContent = '正在启动抓取（' + friendIds.length + ' 位好友）…';
    show($('graphEmpty'), false);
    show($('graphFetchRow'), false);
    show($('graphCancelRow'), true);
    cmd('app__mutual_graph_fetch_start', {
      ownerUserId: state.userId, endpoint: graphEndpoint(), friendIds: friendIds
    }).then(function (st) {
      graph.runId = (st && st.runId) || 0;
      graph.total = (st && st.totalFriends) || graph.total;
      paintProgress((st && st.processedFriends) || 0, graph.total, null);
      graph.pollTimer = setTimeout(pollGraph, 3000);
    }).catch(function (err) {
      graph.fetching = false;
      if (status) status.textContent = window.VRCX ? window.VRCX.describeError(err) : '抓取启动失败。';
      show($('graphFetchRow'), true);
      show($('graphCancelRow'), false);
    });
  }

  /** VRChat 端点：只有 screens.js 的钩子能拿到（详见上面 connEndpoint 的注释）。 */
  function graphEndpoint() {
    return (window.__SCREENS__ && window.__SCREENS__.connEndpoint)
      ? window.__SCREENS__.connEndpoint() : '';
  }

  function cancelGraphFetch() {
    graph.fetching = false;
    if (graph.pollTimer) clearTimeout(graph.pollTimer);
    cmd('app__mutual_graph_fetch_cancel', { ownerUserId: state.userId }).catch(function () {});
    var status = $('graphStatus');
    if (status) status.textContent = '已取消抓取（已抓到的部分仍会显示）。';
    show($('graphCancelRow'), false);
    show($('graphFetchRow'), true);
  }

  function paintProgress(covered, total, note) {
    show($('graphBar'), true);
    var bar = $('graphBarFill');
    if (bar && total > 0) bar.style.width = Math.min(100, Math.round(covered / total * 100)) + '%';
    var status = $('graphStatus');
    if (status) {
      status.textContent = note || ('抓取中… ' + covered + ' / ' + total + ' 位好友');
    }
  }

  /* ⚠️ 服务端是「整轮跑完才一次性提交快照」：snapshot_commit 在抓取循环**之后**
     （crates/application/src/social/mutual_graph_fetch/runtime.rs）。所以抓取期间
     snapshot_get 恒为空 —— 旧实现靠"快照连续 3 次没变"判完成，约 9 秒就宣布
     「抓取完成：已覆盖 0 / 242」，而服务端还要再跑约 3 分钟（242 人 × ~650ms）。
     用户看到的就是"点了没用"。
     正确的进度源是 fetch_start 的回包（MutualGraphFetchStatus）：服务端对同一
     owner 已在跑时**原样返回当前状态**，因此可以当只读进度查询用。 */
  function fetchMaxSec() {
    /* 服务端约 650ms/好友；给足余量，同时不无限等 */
    return Math.max(600, (graph.total || 0) * 4);
  }

  function mmss(sec) {
    var m = Math.floor(sec / 60), s = sec % 60;
    return m + ':' + (s < 10 ? '0' + s : s);
  }

  function pollGraph() {
    if (!graph.fetching) return;
    var elapsedSec = Math.max(1, Math.round((Date.now() - graph.startedMs) / 1000));
    if (elapsedSec > fetchMaxSec()) { finishFetch('抓取超时：服务端一直没有报完成。'); return; }
    cmd('app__mutual_graph_fetch_start', {
      ownerUserId: state.userId, endpoint: graphEndpoint(), friendIds: graph.friendIds
    }).then(function (st) {
      if (!graph.fetching) return;
      var runId = (st && st.runId) || 0;
      var phase = String((st && st.status) || '');
      /* runId 变了 = 我们那轮已经结束，而这次轮询顺手开出了一轮新的。
         立刻取消：新运行还没处理任何好友，取消不提交、不动快照。 */
      if (graph.runId && runId && runId !== graph.runId) {
        cmd('app__mutual_graph_fetch_cancel', { ownerUserId: state.userId }).catch(function () {});
        finishFetch(null);
        return;
      }
      var total = (st && st.totalFriends) || graph.total || 0;
      var done = (st && st.processedFriends) || 0;
      graph.total = total;
      if (phase === 'running' || phase === 'cancelling') {
        paintProgress(done, total,
          '抓取中… ' + done + ' / ' + total + ' 位好友（已用 ' + mmss(elapsedSec)
          + '；服务端逐个去 VRChat 拉，好友多时约 3 分钟）');
        graph.pollTimer = setTimeout(pollGraph, 3000);
        return;
      }
      finishFetch(phase === 'error'
        ? ('抓取失败：' + ((st && st.lastError) || '服务端未给原因'))
        : null);
    }).catch(function () {
      /* 单次读进度失败不该判死：继续轮询，靠超时兜底 */
      if (!graph.fetching) return;
      graph.pollTimer = setTimeout(pollGraph, 5000);
    });
  }

  /** 收尾：重新读快照把图真正画出来（数据是服务端完成时一次性提交的）。 */
  function finishFetch(errText) {
    if (graph.pollTimer) { clearTimeout(graph.pollTimer); graph.pollTimer = 0; }
    graph.fetching = false;
    show($('graphCancelRow'), false);
    show($('graphFetchRow'), true);
    var status = $('graphStatus');
    if (errText && status) status.textContent = errText;
    cmd('app__mutual_graph_snapshot_get', { userId: state.userId }).then(function (snap) {
      var friendIds = (snap && snap.friendIds) || [];
      var links = (snap && snap.links) || [];
      var covered = ((snap && snap.meta) || []).length;
      var total = graph.total || Math.max(covered, friendIds.length, 1);
      show($('graphBar'), true);
      var bar = $('graphBarFill');
      if (bar && total > 0) bar.style.width = Math.min(100, Math.round(covered / total * 100)) + '%';
      if (friendIds.length) {
        show($('graphEmpty'), false);
        buildGraph(friendIds, links);
        drawGraph();
        statusText(friendIds.length, links.length);
        graph.ready = true;
      } else {
        show($('graphEmpty'), true);
      }
      var st2 = $('graphStatus');
      if (st2 && !errText) {
        st2.textContent = friendIds.length
          ? ('抓取完成：已覆盖 ' + covered + ' / ' + total + ' 位好友。')
          : '抓取完成，但服务端没有返回共同好友数据。';
      }
    }).catch(function (err) {
      var st3 = $('graphStatus');
      if (st3) {
        st3.textContent = errText
          || (window.VRCX ? window.VRCX.describeError(err) : '共同好友数据读取失败。');
      }
    });
  }


  /* ------------------------------------------------------------------ 我的模型全量 */

  var maImgCache = {};

  /** 个人页只显示前 12 个；这页给全量（数据 = screens.js 已拉好的 my_avatars_get）。 */
  function loadMyAvatarsPage() {
    var scr = window.__SCREENS__ && window.__SCREENS__.state;
    var list = (scr && scr.myAvatars) || [];
    var grid = $('maGrid');
    var count = $('maCount');
    if (!grid) return;
    clear(grid);
    if (count) count.textContent = list.length ? ('共 ' + list.length + ' 个模型') : '';
    if (!list.length) {
      add(grid, E('p', 'emptynote', '还没有模型数据。'));
      return;
    }
    for (var i = 0; i < list.length; i++) {
      var a = list[i];
      if (!a || typeof a !== 'object') continue;
      var name = safe(a.name, '') || '未命名模型';
      var cell = E('div', 'card card--low favcell');
      var thumb = E('span', 'worldcard__thumb');
      thumb.textContent = name ? thumbText(name) : '?';
      add(cell, thumb);
      add(cell, E('span', 'worldcard__name', name));
      var t = durationText(a.$timeSpent || a.timeSpent);
      if (t) add(cell, E('span', 'favcell__sub', '累计 ' + t));
      add(grid, cell);
      /* 缩略图异步升级（顺序加载，避免几十张同时打服务器代理） */
      (function (thumbEl, avatar) {
        var url = safe(avatar.thumbnailImageUrl, '') || safe(avatar.imageUrl, '');
        if (!url) return;
        if (maImgCache[url]) {
          var done = document.createElement('img');
          done.alt = '';
          done.src = maImgCache[url];
          thumbEl.textContent = '';
          thumbEl.appendChild(done);
          return;
        }
        cmd('app__external_api_image_data_url_get', { url: url }).then(function (r) {
          var data = r && typeof r === 'object' ? (r.data || r) : null;
          var src = typeof data === 'string' && data.indexOf('data:') === 0 ? data : '';
          if (!src) return;
          maImgCache[url] = src;
          if (!thumbEl.isConnected) return;
          var im = document.createElement('img');
          im.alt = '';
          im.src = src;
          thumbEl.textContent = '';
          thumbEl.appendChild(im);
        }).catch(function () {});
      })(thumb, a);
    }
  }

  window.__SCREENS_TOOLS__ = {
    state: state,
    graph: graph,
    loadGraph: loadGraph
  };

  /* ------------------------------------------------------------------ 世界详情 */

  var worldImgCache = {};

  function worldHeroText(name) {
    var t = safe(name, '');
    return t.length <= 8 ? t : t.slice(0, 8);
  }

  /** 世界详情弹层：world_get 是 VRChat 透传（{status,data} 信封，data 为 JSON 串）。
      缩略图走服务器代理；裸 id 只活在闭包里。 */
  /* 当前打开的世界（id 只活在闭包里，屏幕不出现）——「收藏这个世界」用。 */
  var currentWorld = { id: '', name: '' };

  function openWorldPanel(worldId) {
    var panel = $('worldPanel');
    if (!panel || !worldId) return;
    currentWorld = { id: '', name: '' };
    var wfav = $('wFav');
    if (wfav) show(wfav, false);
    show(panel, true);
    var title = $('worldTitle');
    var hero = $('worldHero');
    var kv = $('worldKv');
    var desc = $('worldDesc');
    var err = $('worldErr');
    if (title) title.textContent = '世界详情';
    if (hero) { hero.textContent = '正在读取…'; hero.style.backgroundImage = ''; }
    if (kv) clear(kv);
    if (desc) { desc.textContent = ''; show(desc, false); }
    show(err, false);
    cmd('app__world_get', { worldId: worldId, force: false, full: true })
      .then(function (r) {
        var w = unwrap(r);
        if (!w || typeof w !== 'object' || !w.name) {
          if (err) { err.textContent = '世界详情没读到（可能还没缓存过）。'; show(err, true); }
          if (hero) hero.textContent = '世界';
          return;
        }
        var name = safe(w.name, '') || '未命名世界';
        if (title) title.textContent = name;
        currentWorld = { id: String(worldId), name: name };
        if (wfav) show(wfav, true);
        if (hero) {
          hero.textContent = worldHeroText(name);
          var turl = safe(w.thumbnailImageUrl, '') || safe(w.imageUrl, '');
          if (turl) {
            loadImageInto(hero, turl, function (src) {
              if (src) hero.style.backgroundImage = 'url(' + src + ')';
            });
          }
        }
        if (kv) {
          clear(kv);
          var rows = [
            ['作者', safe(w.authorName, '')],
            ['游览次数', w.visits > 0 ? String(w.visits) : ''],
            ['收藏人数', w.favorites > 0 ? String(w.favorites) : ''],
            ['容量', w.capacity > 0 ? String(w.capacity) + ' 人' : ''],
            ['发布状态', w.releaseStatus === 'public' ? '公开'
                       : w.releaseStatus === 'private' ? '私有' : ''],
            ['更新于', timeText(w.updated_at || w.updatedAt)]
          ];
          for (var i = 0; i < rows.length; i++) {
            if (!rows[i][1]) continue;
            var row = E('div', 'kv');
            add(row, E('span', 'kv__k', rows[i][0]));
            add(row, E('span', 'kv__v', scrubIds(rows[i][1])));
            add(kv, row);
          }
        }
        var d = scrubIds(safe(w.description, ''));
        if (desc) { desc.textContent = d || '（作者没有写简介）'; show(desc, true); }
      })
      .catch(function (e) {
        if (err) { err.textContent = window.VRCX ? window.VRCX.describeError(e) : '读取失败。'; show(err, true); }
        if (hero) hero.textContent = '世界';
      });
  }

  /** 服务器代理取图（data URL），带缓存；完成回调拿 src（可为空）。 */
  function loadImageInto(el, url, done) {
    if (worldImgCache[url]) { done(worldImgCache[url]); return; }
    cmd('app__external_api_image_data_url_get', { url: url }).then(function (r) {
      var data = r && typeof r === 'object' ? (r.data || r) : null;
      var src = typeof data === 'string' && data.indexOf('data:') === 0 ? data : '';
      if (src) worldImgCache[url] = src;
      done(src);
    }).catch(function () { done(''); });
  }

  window.__SCREENS_TOOLS__.openWorld = openWorldPanel;


  if (!window.VRCXCon) return;
  window.VRCXCon.onChange(onState);
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', wire);
  } else {
    wire();
  }
})();
