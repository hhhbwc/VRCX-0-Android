/*
 * 连接闸门 + 连接状态。
 *
 * ## 步骤顺序照手机端 LoginScreen.kt（服务端实际允许的顺序）
 *
 *   1. 填服务器地址（指纹默认折叠 —— 局域网明文连接不需要它）
 *   2. 连上后在这台服务器上占一个名额：服务器还没有用户 → 「创建第一个用户」；
 *      已有用户 → 「加入这台服务器」，需要运维给的邀请码
 *   3. 选一个服务器已保存凭据的账号（免密），或用用户名密码登录
 *   4. 二步验证是模态，没答完不能继续
 *
 * 为什么顺序不能反：**每一个 /v1/auth/* 路由都要求租户凭据**，
 * 所以第 3 步之前必须有第 2 步。把密码表单提前显示，只会给出一个必然 401 的按钮
 * （手机端 LoginScreen 的注释里写了同一条理由）。
 *
 * ## 只在 App 内启用
 *
 * 只认 #device（App 里的全屏模式）。浏览器打开 demo 时 window.VRCX.available 为
 * false，这里直接退出 —— 页面照旧用示例数据渲染，出图与四档布局断言不受影响。
 *
 * ## 顺带把连接状态反映到顶栏
 *
 * 顶栏那颗色点与地址/在线数此前是写死的假数据；这里改成真实状态，
 * 并把「已登录 + 我的 userId」发布到 window.VRCXCon 供各屏取数。
 */
(function () {
  'use strict';

  function inDeviceMode() {
    return (location.hash || '').indexOf('device') >= 0;
  }

  if (!window.VRCX || !window.VRCX.available) return;   // 浏览器：不介入
  if (!inDeviceMode()) return;                           // 演示档位：不介入

  var el = {};
  var state = {
    configured: false,      // 有服务器地址
    hasToken: false,        // 已领到本机租户凭据（= 能调 /v1/auth/*）
    reachable: false,       // /v1/health 通
    hasTenants: false,      // 服务器上已有用户 → 领取名额需要邀请码
    authenticated: false,   // 已登录 VRChat 账号
    userId: '',
    displayName: '',
    appVersion: '',
    supportedCommands: 0,
    supported: [],          // /v1/health 的 commands.supported 原表 —— 客户端按它决定走服务端还是本地兜底
    accounts: [],
    loginAvailable: true,
    pwdOpen: false,         // 用户名密码表单是否展开
    attemptId: '',          // 2FA 挑战
    methods: [],
    streamWebsocket: '',  // 事件流下发的 realtime 会话 id（baseline 对账凭证）
    vrchatEndpoint: ''    // auth/status 下发的 VRChat API 端点（baseline 对账凭证之一）
  };

  var listeners = [];

  function notify() {
    for (var i = 0; i < listeners.length; i++) {
      try { listeners[i](state); } catch (e) { /* 订阅方自己的问题，不阻塞别人 */ }
    }
  }

  /* ---- 事件流 /v1/stream（M2）：好友上下线/动态等推送到达后触发全量对账。
     协议（对齐 1.0 系列 EventStreamClient.kt）：
       · 传输在原生产生：DataBridge.openStream() 起原生 WebSocket 客户端，token 走
         Authorization 头（不进 query，凭据不再泄漏），TLS 复用 PinnedTls 证书钉
         （自签可连，WebView 直连会被系统库拒）；
       · 原生把收到的帧与状态经 window.__vrcxStreamFrame / __vrcxStreamOpen /
         __vrcxStreamClose 推回本页（对原生缺失是防御式的 typeof 检查）；
       · 帧是 FLAT {kind, event, payload, seq}：hello / event / lagged；
       · 服务端不重放：lagged 或重连之后必须全量重查（调用方的责任）。 ---- */

  var stream = { nativeOpen: false, wanted: false, backoff: 1000, timer: null,
                 refreshTimer: null, everOpened: false, lastEvent: '',
                 connected: false, frames: [] };

  function streamOpen() {
    if (!window.VRCXNative || typeof window.VRCXNative.openStream !== 'function') return;
    stream.wanted = true;
    streamDial();
  }

  function streamDial() {
    if (!stream.wanted || stream.nativeOpen) return;
    if (!window.VRCXNative || typeof window.VRCXNative.openStream !== 'function') {
      streamRetry();
      return;
    }
    stream.nativeOpen = true;
    try {
      window.VRCXNative.openStream();
    } catch (e) {
      stream.nativeOpen = false;
      streamRetry();
    }
  }

  /* 原生推下来的帧（一条完整 text 消息）。逻辑与原 new WebSocket 的 onmessage 一致。 */
  function onStreamFrame(text) {
    var frame = null;
    try { frame = JSON.parse(text); } catch (err) { return; }
    if (!frame || typeof frame.kind !== 'string') return;
    stream.frames.push(text);               // 环形缓冲：侦查/验收用
    if (stream.frames.length > 12) stream.frames.shift();
    /* 会话标识：事件流会把当前 realtime 会话 id 推下来，
       baseline 请求不带它就被服务端对账拒掉（Superseded）。
       两个来源：hello 帧的 websocket 字段（2026-09-27 服务端补的，
       连上就有，不用等事件）与自身 user-update 事件里的
       currentUserWebsocket（旧服务器的兜底来源）。 */
    var wsid = frame.websocket
      || (frame.payload && frame.payload.currentUserWebsocket)
      || frame.currentUserWebsocket || '';
    if (wsid && wsid !== state.streamWebsocket) {
      state.streamWebsocket = String(wsid);
      notify();   // 学到新会话 id 要立刻让订阅方知道（名单重拉靠它触发）
    }
    if (frame.kind === 'lagged') { reconcileAll(true); return; }
    if (frame.kind === 'event') {
      stream.lastEvent = String(frame.event || '');
      scheduleRefresh();
    }
    /* kind === 'hello'：握手帧（protocolVersion），无需动作 */
  }

  function onStreamOpen() {
    stream.connected = true;                     // 调试口子读它，别只读不写
    stream.backoff = 1000;
    if (stream.everOpened) reconcileAll(true);   // 重连成功 = 先对账（静默：屏上已有数据）
    stream.everOpened = true;
  }

  function onStreamClose() {
    stream.connected = false;
    stream.nativeOpen = false;
    if (stream.wanted) streamRetry();
  }

  function onStreamError(msg) {
    if (window.console) console.warn('[stream] error', msg);
  }

  window.__vrcxStreamFrame = onStreamFrame;
  window.__vrcxStreamOpen = onStreamOpen;
  window.__vrcxStreamClose = onStreamClose;
  window.__vrcxStreamError = onStreamError;

  function streamRetry() {
    if (!stream.wanted || stream.timer) return;
    stream.timer = setTimeout(function () {
      stream.timer = null;
      streamDial();
    }, stream.backoff);
    stream.backoff = Math.min(stream.backoff * 2, 30000);   // 1s→30s 封顶
  }

  /** 事件 → 全量对账。防抖 1.2s 之外还要**节流**：好友上下线成串到达的晚上，
      1.2s 一次全量重拉（8+ 条命令 + 基线）会把路由器打爆、横幅常驻 ——
      两次对账至少隔 15s，期间的事件等下一轮一起算（数据本来就带增量）。 */
  var RECONCILE_MIN_MS = 15000;
  stream.lastReconcile = 0;
  function scheduleRefresh() {
    if (stream.refreshTimer) return;
    var wait = 1200;
    var since = Date.now() - stream.lastReconcile;
    if (since < RECONCILE_MIN_MS) wait = RECONCILE_MIN_MS - since;
    stream.refreshTimer = setTimeout(function () {
      stream.refreshTimer = null;
      stream.lastReconcile = Date.now();
      reconcileAll(true);
    }, wait);
  }

  /** 全量对账。1.0 的既定语义：重连/滞后/事件后由调用方重跑查询。 */
  function reconcileAll(silent) {
    var screens = window.__SCREENS__;
    if (screens && typeof screens.load === 'function') screens.load(silent);
    else if (state.authenticated) connect();
  }

  function streamStopAll() {
    stream.wanted = false;
    if (stream.timer) { clearTimeout(stream.timer); stream.timer = null; }
    if (stream.refreshTimer) { clearTimeout(stream.refreshTimer); stream.refreshTimer = null; }
    if (window.VRCXNative && typeof window.VRCXNative.closeStream === 'function') {
      try { window.VRCXNative.closeStream(); } catch (e) { /* 原生已关 */ }
    }
    stream.nativeOpen = false;
  }

  /* ---------------------------------------------------------------- DOM */

  function $(id) { return document.getElementById(id); }

  function cache() {
    el.gate = $('gate');
    el.addr = $('g-addr');
    el.pinWrap = $('g-pin-wrap');
    el.pin = $('g-pin');
    el.pinWarn = $('g-pin-warn');
    el.pinToggle = $('g-pin-toggle');
    el.connect = $('g-connect');
    el.status = $('g-status');
    el.discovered = $('g-discovered');

    el.claim = $('g-claim');
    el.claimTitle = $('g-claim-title');
    el.claimDesc = $('g-claim-desc');
    el.label = $('g-label');
    el.inviteWrap = $('g-invite-wrap');
    el.invite = $('g-invite');
    el.claimGo = $('g-claim-go');

    el.pick = $('g-pick');
    el.accounts = $('g-accounts');

    el.pwd = $('g-pwd');
    el.pwdToggle = $('g-pwd-toggle');
    el.pwdForm = $('g-pwd-form');
    el.user = $('g-user');
    el.pass = $('g-pass');
    el.login = $('g-login');

    el.close = $('g-close');
    el.forget = $('g-forget');

    /* 二次确认：在闸门**外面**（个人页的"退出登录 / 忘记此服务器"用它），
       所以它不随闸门一起隐藏 —— 见 index.html 那段注释。 */
    el.confirm = $('confirm');
    el.confirmTitle = $('confirm-title');
    el.confirmBody = $('confirm-body');
    el.confirmOk = $('confirm-ok');

    el.modal = $('g-2fa');
    el.code2fa = $('g-2fa-code');
    el.methods2fa = $('g-2fa-methods');
    el.err2fa = $('g-2fa-error');
    el.go2fa = $('g-2fa-go');

    el.conn = document.querySelector('.app__topbar .conn');
    el.addrText = document.querySelector('.app__topbar .topbar__addr');
    el.onlineText = document.querySelector('.app__topbar .topbar__online');

    /* 「个人」页：头像是谁由登录态决定，不由样例数据决定 */
    el.meCard = $('meCard');
    el.meAvatar = $('meAvatar');
    el.meName = $('meName');
    el.meUser = $('meUser');
    el.meStatus = $('meStatus');
    el.meModelsHead = $('meModelsHead');
    el.meModelsGrid = $('meModelsGrid');

    /* 顶栏那颗"我"的头像，以及"演示数据"标识 —— 都不许写死一个第三方昵称 */
    el.topAvatar = $('topAvatar');
    el.demobar = $('demobar');
    el.demoText = $('demobar-text');

    /* 「个人」页的账号键值行（当前模型 / 主页世界 / 人称代词 / 状态文字 / 信任等级）。
       它们全是**账号数据**，未登录时必须整块收起来 —— 样例里那几行属于某个具体账号，
       照着显示等于替一个不存在的"我"编了份资料。 */
    el.meKv = $('meKv');
  }

  function show(node, on) { if (node) node.hidden = !on; }

  function setStatus(text, kind) {
    if (!el.status) return;
    el.status.textContent = text || '';
    el.status.classList.toggle('is-error', kind === 'error');
    el.status.classList.toggle('is-ok', kind === 'ok');
  }

  /**
   * 本次会话里用户点过「先看看界面」没有。
   *
   * 为什么要它：冷启动会先弹闸门再探活，探活回来如果发现仍未登录还会再 open() 一次
   * —— 用户要是这期间点了「先看看界面」，闸门会糊回他脸上。它不是持久标记，
   * 冷启动一律清零（"看一眼"只对本次启动有效）。
   */
  var peeked = false;

  function open() { if (el.gate) el.gate.hidden = false; }
  function close() { if (el.gate) el.gate.hidden = true; }

  /* -------------------------------------------------------------- 二次确认 */

  /**
   * 破坏性/不可逆的动作先问一遍（与手机端 AlertDialog 同语义、同文案）。
   *
   * 不用 window.confirm()：那是 WebView 的系统弹窗，长相与 App 无关，
   * 文案也不能按动作定制（"退出"和"忘记"要说的不是同一件事）。
   * 结构复用闸门模态，所以外观本来就是同一套。
   */
  var confirmAction = null;

  function askConfirm(opts, action) {
    if (!el.confirm) { return Promise.resolve(action()); }   // 模态没渲染出来就别拦着
    if (el.confirmTitle) el.confirmTitle.textContent = opts.title || '';
    if (el.confirmBody) el.confirmBody.textContent = opts.body || '';
    if (el.confirmOk) {
      el.confirmOk.textContent = opts.okLabel || '确定';
      // 两个按钮长得一样才是问题：破坏性动作走标准错误色（与个人页那颗
      // "忘记此服务器"同形），普通动作用填充主色。
      el.confirmOk.className = opts.danger ? 'btn' : 'btn btn--filled';
      el.confirmOk.style.color = opts.danger ? 'var(--md-error)' : '';
      el.confirmOk.style.borderColor = opts.danger ? 'var(--md-error)' : '';
    }
    confirmAction = action;
    show(el.confirm, true);
    return Promise.resolve(true);
  }

  function closeConfirm() {
    show(el.confirm, false);
    confirmAction = null;
  }

  /** 点「取消」——什么都不做，动作丢弃。 */
  function cancelConfirm() { closeConfirm(); }

  /** 点确认：先关模态再执行（动作失败时错误写在闸门状态行上，不藏在弹窗里）。 */
  function acceptConfirm() {
    var action = confirmAction;
    closeConfirm();
    if (!action) return Promise.resolve(false);
    return Promise.resolve().then(action).catch(function (err) {
      setStatus(window.VRCX.describeError(err), 'error');
      return false;
    });
  }

  /* 破坏性动作的确认框也借给别的脚本用（screens.js 的「移除收藏」）。
     模态没渲染出来时 askConfirm 本来就直接执行动作，语义不变。 */
  window.__ASK_CONFIRM__ = askConfirm;

  /* ---------------------------------------------------------------- 指纹校验 */
  /**
   * 与手机端 TlsPinning.parse/warning 同语义的轻量版：
   * 指纹解出来必须是 32 字节（SHA-256）。格式不对就**挡住「连接」按钮** ——
   * 带着一个永不匹配的钉去握手，只会以一条语焉不详的证书错误浮出来，
   * 而"没填指纹"和"填了个坏指纹"在用户眼里长得一样。
   */
  function pinState(raw) {
    var v = String(raw || '').replace(/\s+/g, '');
    if (!v) return { kind: 'none' };
    v = v.replace(/^sha256[/:]/i, '').replace(/:/g, '');
    if (!/^[A-Za-z0-9+/]+={0,2}$/.test(v)) {
      return { kind: 'unusable', error: '指纹不是合法的 Base64。' };
    }
    var bytes = Math.floor(v.replace(/=+$/, '').length * 3 / 4);
    if (bytes !== 32) {
      return { kind: 'unusable', error: '指纹解出来是 ' + bytes + ' 字节；SPKI SHA-256 应该是 32 字节。' };
    }
    return { kind: 'pin' };
  }

  function refreshPinAdvice() {
    if (!el.pinWarn) return;
    var addr = (el.addr && el.addr.value || '').trim();
    var ps = pinState(el.pin && el.pin.value);
    var https = /^https:\/\//i.test(addr);

    if (ps.kind === 'unusable') {
      el.pinWarn.textContent = ps.error;
      el.pinWarn.classList.add('is-error');
      el.pinWarn.hidden = false;
      if (el.pin) el.pin.classList.add('is-error');
    } else if (https && ps.kind === 'none') {
      el.pinWarn.textContent = '这是 https 地址但没填指纹。服务器若用自签证书会被系统信任库拒绝 —— 请填入它打印的 SPKI 指纹。';
      el.pinWarn.classList.remove('is-error');
      el.pinWarn.hidden = false;
      if (el.pin) el.pin.classList.remove('is-error');
    } else {
      el.pinWarn.hidden = true;
      el.pinWarn.textContent = '';
      if (el.pin) el.pin.classList.remove('is-error');
    }
    if (el.connect) el.connect.disabled = !addr || ps.kind === 'unusable';
  }

  /* ---------------------------------------------------------------- 顶栏 */

  /**
   * 顶栏状态点。注意 .conn--off 等类名来自 app.css，直接用，
   * 不另造一套状态色（否则两个地方会各自漂移）。
   */
  function paintTopbar(kind, onlineText) {
    if (el.conn) {
      el.conn.classList.remove('conn--off', 'conn--idle', 'conn--reconnect');
      if (kind === 'off') el.conn.classList.add('conn--off');
      else if (kind === 'idle') el.conn.classList.add('conn--idle');
      else if (kind === 'reconnect') el.conn.classList.add('conn--reconnect');
      el.conn.setAttribute('aria-label', onlineText || '');
    }
    if (el.onlineText && onlineText) el.onlineText.textContent = onlineText;
    if (el.addrText) el.addrText.textContent = prettyAddress();
  }

  /** 只显示主机名（端口非默认时才带），避免顶栏被一整串 URL 撑破。 */
  function prettyAddress() {
    var raw = (el.addr && el.addr.value || '').trim();
    if (!raw) return '未配置';
    var m = raw.match(/^https?:\/\/([^/]+)/i);
    if (!m) return raw;
    var authority = m[1];
    var scheme = raw.slice(0, raw.indexOf('://')).toLowerCase();
    authority = authority.replace(/:8790$/, scheme === 'http' ? ':8790' : '');
    return authority;
  }

  /* -------------------------------------------------------------- 个人页 */

  /**
   * 「个人」页的头像 / 昵称 / 账号句柄，**唯一来源是当前登录账号**。
   *
   * 为什么不写死一个样例名：这一页自称是"我的账号"。写死名字就等于宣称
   * 某个第三方是你的账号 —— 而样例数据里被写死的那个名字，往往同时又出现在
   * 动态流里当好兄弟，于是同一个人既被标成"我"又被标成"好友"。
   *
   * 拿不到账号时（没配服务器 / 没登录 / 掉线）就如实说"未登录"，
   * 并把一切账号数据（键值行、我的模型）收起来 —— 没有账号就没有值。
   */
  function paintMe() {
    if (!el.meCard || !el.meName) return;

    var signedIn = !!(state.authenticated && (state.displayName || state.userId));

    // 句柄（username）从账号列表里按 userId 取；列表拉不到就只显示昵称。
    var acc = null;
    for (var i = 0; i < state.accounts.length; i++) {
      if (state.accounts[i] && state.accounts[i].userId === state.userId) {
        acc = state.accounts[i];
        break;
      }
    }
    var handle = (acc && acc.username) || '';
    var name = state.displayName || (acc ? accountName(acc) : '');

    el.meCard.classList.toggle('is-anon', !signedIn);
    /* 账号数据整体跟着登录态：没账号就没有"我的模型""我的状态文字"可言。
       （曾经只藏了模型网格，那五行键值还照旧摆着 —— 于是一个未登录的人
       会看到别人的模型名和状态文字。） */
    show(el.meKv, signedIn);
    if (el.meModelsHead) el.meModelsHead.hidden = !signedIn;
    if (el.meModelsGrid) el.meModelsGrid.hidden = !signedIn;

    if (el.meUser) { el.meUser.textContent = handle; el.meUser.hidden = !handle; }

    // 顶栏那颗头像跟着同一个判据：没登录就是占位符。
    // 曾经它写死成某个人名的首字 —— 那等于宣称某第三方就是当前账号。
    if (el.topAvatar) {
      el.topAvatar.textContent = signedIn ? (name || '·').slice(0, 1).toUpperCase() : '·';
    }

    if (signedIn) {
      el.meName.textContent = name || '已登录';
      if (el.meAvatar) el.meAvatar.textContent = (name || '·').slice(0, 1).toUpperCase();
      // 在线状态 / 状态文字要等数据面，这里没有就不编 —— 空着比编一句好
      if (el.meStatus) { el.meStatus.textContent = ''; el.meStatus.hidden = true; }
    } else {
      el.meName.textContent = '未登录';
      if (el.meAvatar) el.meAvatar.textContent = '·';
      if (el.meStatus) {
        el.meStatus.hidden = false;
        el.meStatus.textContent = state.hasToken
          ? '这台设备已连上服务器，但还没有登录账号'
          : '这台设备还没有登录服务器';
      }
    }
  }

  /* ---------------------------------------------------------------- 渲染 */

  /** 账号行的显示名**绝不用裸 id**（usr_… 不许出现在屏幕上）。 */
  function accountName(acc) {
    return acc.displayName || acc.username || '账号';
  }

  function renderAccounts() {
    if (!el.accounts) return;
    el.accounts.textContent = '';
    for (var i = 0; i < state.accounts.length; i++) {
      /* jshint loopfunc:true */
      (function (acc) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'gate__account';

        var name = accountName(acc);
        var av = document.createElement('span');
        av.className = 'gate__account-av';
        av.textContent = name.slice(0, 1).toUpperCase();

        var txt = document.createElement('span');
        txt.className = 'gate__account-txt';
        var nm = document.createElement('span');
        nm.className = 'gate__account-name';
        nm.textContent = name;
        txt.appendChild(nm);
        if (acc.username) {
          var sub = document.createElement('span');
          sub.className = 'gate__account-sub';
          sub.textContent = acc.username;
          txt.appendChild(sub);
        }

        btn.appendChild(av);
        btn.appendChild(txt);
        btn.addEventListener('click', function () { pickAccount(acc.userId); });
        el.accounts.appendChild(btn);
      })(state.accounts[i]);
    }
  }

  /** 把 state 映射到可见性 —— 所有显示/隐藏只在这里决定，别散落在各动作里。 */
  function render() {
    var addr = (el.addr && el.addr.value || '').trim();

    // 个人页的身份跟着登录态走 —— 挂在这里，就不必在每个动作里各叫一次
    paintMe();

    // 第 2 步：连上了但还没有凭据 → 占名额
    var canClaim = !!addr && state.reachable && !state.hasToken;
    show(el.claim, canClaim);
    if (canClaim) {
      var needInvite = state.hasTenants;
      if (el.claimTitle) el.claimTitle.textContent = needInvite ? '加入这台服务器' : '创建第一个用户';
      if (el.claimDesc) {
        el.claimDesc.textContent = needInvite
          ? '这台服务器已经有用户了，需要运维提供的邀请码'
          : '这台服务器还没有任何用户，你现在就是第一个';
      }
      show(el.inviteWrap, needInvite);
      if (el.claimGo) {
        el.claimGo.textContent = needInvite ? '加入' : '创建';
        var labelOk = !!(el.label && el.label.value.trim());
        var inviteOk = !needInvite || !!(el.invite && el.invite.value.trim());
        el.claimGo.disabled = !labelOk || !inviteOk;
        // 灰化（.btn:disabled）只解决"看得出不能点"，还得说清**差什么**。
        // 少了这句，用户看到的就是"点了没反应" —— 2026-09-29 真机实测：
        // 「平板」只是 placeholder、输入框空的，于是「加入」恒禁用。
        // 就着上面第 525-529 行刚写好的那条 support 文案追加，不新增 DOM：
        // render() 每次都会把 textContent 重设一遍，所以不会累积。
        if (el.claimDesc && el.claimGo.disabled) {
          var miss = [];
          if (!labelOk) miss.push('设备名称');
          if (!inviteOk) miss.push('邀请码');
          el.claimDesc.textContent += '（还要填：' + miss.join('、') + '）';
        }
      }
    }

    // 第 3 步：有凭据 + 服务器可达 → 选账号 / 用户名密码
    var canAuth = state.hasToken && state.reachable;
    var hasAccounts = state.accounts.length > 0;
    show(el.pick, canAuth && hasAccounts);
    show(el.pwd, canAuth);
    if (canAuth) {
      if (el.pwdTitle) {
        el.pwdTitle.textContent = hasAccounts ? '登录 VRChat 账号' : '登录 VRChat 账号';
      }
      // 有账号时把密码表折叠起来（常见路径是点名字），没有账号时直接展开
      show(el.pwdToggle, hasAccounts && !state.pwdOpen);
      show(el.pwdForm, state.pwdOpen);
      if (el.login) {
        el.login.disabled = !(el.user && el.user.value.trim()) || !(el.pass && el.pass.value);
      }
    }

    if (el.discovered) {
      show(el.discovered, state.supportedCommands > 0);
      if (state.supportedCommands > 0) {
        el.discovered.textContent = '已发现 ' + state.supportedCommands + ' 条命令';
      }
    }

    // 逃生口：连上过服务器就允许先看看界面（未配置时不存在）
    show(el.close, state.reachable || state.hasToken);

    // 演示数据标识：只在**未登录**时由这里管。
    // 已登录时所有权交给 screens.js —— 它要么把真实数据画上五屏并撤掉横幅，
    // 要么写明取数失败。两边都改这条横幅就会互相盖（先画的被后画的冲掉）。
    if (el.demoText && !state.authenticated) {
      el.demoText.textContent = '演示数据 · 未登录，这里显示的不是你的数据';
      show(el.demobar, true);
    }

    refreshPinAdvice();
    notify();
  }

  /* ---------------------------------------------------------------- 配置 */

  function loadConfig() {
    var cfg = window.VRCX.config();
    if (!cfg) return;
    if (el.addr && !el.addr.value) el.addr.value = cfg.serverAddress || '';
    if (el.pin && !el.pin.value) el.pin.value = cfg.certificatePin || '';
    // 与手机端一致：只有已经存了指纹才一开始就展开那一栏
    if (el.pinWrap) el.pinWrap.hidden = !(cfg.certificatePin || '').trim();
    syncPinToggleLabel();
    state.configured = !!cfg.complete;
    state.hasToken = !!cfg.hasToken;
  }

  function collectConfig() {
    return {
      serverAddress: (el.addr && el.addr.value || '').trim(),
      certificatePin: (el.pin && el.pin.value || '').trim()
    };
  }

  function syncPinToggleLabel() {
    if (!el.pinToggle || !el.pinWrap) return;
    el.pinToggle.textContent = el.pinWrap.hidden ? '服务器用了自签证书' : '隐藏证书指纹';
  }

  /* ---------------------------------------------------------------- 动作 */

  /** 第 1 步：保存地址（+指纹）并探活。 */
  function connect() {
    var cfg = collectConfig();
    if (!cfg.serverAddress) {
      setStatus('先填服务器地址。', 'error');
      return Promise.resolve(false);
    }
    if (pinState(cfg.certificatePin).kind === 'unusable') {
      setStatus('证书指纹格式不对，先改掉再连接。', 'error');
      return Promise.resolve(false);
    }

    var out = window.VRCX.saveConfig(cfg);
    if (out && out.error) {
      setStatus(out.error, 'error');
      return Promise.resolve(false);
    }
    if (el.pin) el.pin.value = (out && out.certificatePin) || '';
    state.configured = !!out.complete;
    state.hasToken = !!out.hasToken;

    setStatus('正在连接 ' + prettyAddress() + ' …');
    paintTopbar('reconnect', '连接中');
    render();

    return window.VRCX.health().then(function (h) {
      state.reachable = true;
      state.appVersion = h.appVersion || '';
      var supported = (h.commands && h.commands.supported) || [];
      state.supported = supported;
      state.supportedCommands = supported.length;
      // 服务器上已有用户 → 领取名额需要邀请码（手机端 needsInvite = health.hasTenants）
      state.hasTenants = !!(h.tenants && h.tenants.registered > 0);
      paintTopbar('idle', state.hasToken ? '未登录' : '未连接');
      setStatus('已连上 ' + prettyAddress() + ' · 协议 v' + (h.protocolVersion || '?')
        + ' · 应用 ' + (h.appVersion || '?')
        + (h.runtime && h.runtime.started === false ? '（运行时未启动）' : ''), 'ok');
      render();
      return state.hasToken ? refreshAuth() : false;
    }).catch(function (err) {
      state.reachable = false;
      paintTopbar('off', '连不上');
      setStatus(window.VRCX.describeError(err), 'error');
      render();
      return false;
    });
  }

  /** 第 2 步：领/建本机名额。成功即拿到凭据（存在原生侧）。 */
  function claim() {
    var label = (el.label && el.label.value.trim()) || '平板';
    var invite = (el.invite && el.invite.value.trim()) || '';
    if (state.hasTenants && !invite) {
      setStatus('这台服务器已经有用户了，需要邀请码。', 'error');
      return Promise.resolve(false);
    }
    setStatus('正在' + (state.hasTenants ? '加入' : '创建') + '…');
    return window.VRCX.claimTenant(label, invite).then(function (cred) {
      state.hasToken = true;
      setStatus('已在这台服务器上占好名额（' + (cred.label || label) + '）。凭据存在设备上，网页看不到它。', 'ok');
      return refreshAuth();
    }).catch(function (err) {
      setStatus(window.VRCX.describeError(err), 'error');
      return false;
    });
  }

  /** 第 3 步（前置）：确认凭据还有效，然后拉账号列表。 */
  function refreshAuth() {
    return window.VRCX.authStatus().then(function (s) {
      state.authenticated = !!s.authenticated;
      state.userId = s.userId || '';
      state.displayName = s.displayName || '';
      state.loginAvailable = s.loginAvailable !== false;
      state.vrchatEndpoint = s.endpoint || state.vrchatEndpoint;
      if (state.authenticated) {
        paintTopbar('on', '在线 · ' + (s.displayName || '已登录'));
        setStatus('已登录' + (s.displayName ? '：' + s.displayName : '') + '。', 'ok');
        streamOpen();
      } else {
        streamStopAll();
        paintTopbar('idle', '未登录');
      }
      return loadAccounts();
    }).catch(function (err) {
      // 401 是"凭据失效"，不是"连不上"，两者要分开说 —— 处理方式不同：
      // 前者要重新领名额，后者要查地址/指纹。
      if (err && err.status === 401) {
        state.hasToken = false;
        state.accounts = [];
        window.VRCX.clearToken();
        streamStopAll();
        // 凭据失效 = 必须重新领名额，而领名额只在闸门里（手机端此时进 NEED_CLAIM）。
        // 不推出来，用户就停在主界面上，个人页写着"未登录"却没有任何入口。
        open();
        paintTopbar('idle', '凭据失效');
        setStatus('凭据已失效（401）。这台设备的 token 可能已被吊销或轮换，请重新领取名额。', 'error');
      } else {
        paintTopbar('off', '连不上');
        setStatus(window.VRCX.describeError(err), 'error');
      }
      render();
      return false;
    });
  }

  function loadAccounts() {
    return window.VRCX.accounts().then(function (s) {
      state.accounts = (s && s.accounts) || [];
      if (s && s.loginAvailable === false) {
        setStatus('服务器暂时不接受登录（可能已有其他登录在进行，或失败次数过多）。', 'error');
      }
      // 没有可点的账号 → 直接把用户名密码展开（手机端 autoShowPassword 同义）
      if (state.accounts.length === 0) state.pwdOpen = true;
      render();
      return true;
    }).catch(function () {
      // 账号列表拿不到不该挡住登录：退回用户名密码这条路
      state.accounts = [];
      state.pwdOpen = true;
      render();
      return false;
    });
  }

  /** 点一个已保存凭据的账号 → 免密登录。 */
  function pickAccount(userId) {
    if (!userId) return Promise.resolve(false);
    setStatus('正在登录…');
    paintTopbar('reconnect', '登录中');
    return window.VRCX.loginWithAccount(userId)
      .then(handleOutcome)
      .catch(function (err) {
        setStatus(window.VRCX.describeError(err), 'error');
        paintTopbar('idle', '未登录');
        return false;
      });
  }

  /** 用户名密码登录（会要求服务器保存凭据，以便下次免密）。 */
  function loginPwd() {
    var username = (el.user && el.user.value.trim()) || '';
    var password = (el.pass && el.pass.value) || '';
    if (!username || !password) {
      setStatus('填用户名和密码。', 'error');
      return Promise.resolve(false);
    }
    setStatus('正在登录…');
    paintTopbar('reconnect', '登录中');
    return window.VRCX.login(username, password, true)
      .then(handleOutcome)
      .catch(function (err) {
        setStatus(window.VRCX.describeError(err), 'error');
        paintTopbar(state.hasToken ? 'idle' : 'off', '未登录');
        return false;
      });
  }

  /** AuthOutcome 的判别键是 status（服务端 #[serde(tag = "status")]），四种之一。 */
  function handleOutcome(outcome) {
    var status = outcome && outcome.status;
    if (status === 'authenticated') {
      state.authenticated = true;
      state.userId = outcome.userId || '';
      state.displayName = outcome.displayName || '';
      state.attemptId = '';
      hide2fa();
      if (el.pass) el.pass.value = '';
      state.pwdOpen = false;
      paintTopbar('on', '在线 · ' + (outcome.displayName || '已登录'));
      setStatus('登录成功' + (outcome.displayName ? '：' + outcome.displayName : '') + '。', 'ok');
      state.pwdOpen = false;
      render();
      close();
      smoke();
      return true;
    }
    if (status === 'challenge') {
      state.attemptId = outcome.attemptId || '';
      state.methods = outcome.methods || [];
      show2fa();
      paintTopbar('idle', '待验证');
      return false;
    }
    if (status === 'cancelled') {
      setStatus('登录已取消。', 'error');
      paintTopbar('idle', '未登录');
      return false;
    }
    // failed（或形状不认识）
    setStatus('登录失败：' + ((outcome && (outcome.reason || outcome.error)) || '未知原因'), 'error');
    paintTopbar('idle', '未登录');
    return false;
  }

  /* 二步验证模态 */
  function show2fa() {
    if (el.methods2fa) {
      var m = state.methods.length ? '验证方式：' + state.methods.join(' / ') : '';
      el.methods2fa.textContent = m;
      el.methods2fa.hidden = !m;
    }
    if (el.err2fa) { el.err2fa.hidden = true; el.err2fa.textContent = ''; }
    show(el.modal, true);
    if (el.code2fa) { el.code2fa.value = ''; el.code2fa.focus(); }
  }

  function hide2fa() {
    show(el.modal, false);
    if (el.code2fa) el.code2fa.value = '';
  }

  function submit2fa() {
    if (!state.attemptId) {
      if (el.err2fa) { el.err2fa.textContent = '还没有待验证的登录尝试。'; el.err2fa.hidden = false; }
      return Promise.resolve(false);
    }
    var code = (el.code2fa && el.code2fa.value.trim()) || '';
    if (!code) {
      if (el.err2fa) { el.err2fa.textContent = '填验证码。'; el.err2fa.hidden = false; }
      return Promise.resolve(false);
    }
    // 默认用 challenge 给的第一个方法；不同方法只是标签不同，都在同一个接口上。
    var method = state.methods[0] || 'totp';
    if (el.err2fa) { el.err2fa.hidden = true; el.err2fa.textContent = ''; }
    return window.VRCX.twoFactor(state.attemptId, method, code)
      .then(function (outcome) {
        var ok = handleOutcome(outcome);
        if (!ok && el.err2fa && !state.attemptId) {
          el.err2fa.textContent = '验证没有通过，请重试。';
          el.err2fa.hidden = false;
        }
        return ok;
      })
      .catch(function (err) {
        if (el.err2fa) {
          el.err2fa.textContent = window.VRCX.describeError(err);
          el.err2fa.hidden = false;
        }
        return false;
      });
  }

  /**
   * 退出登录：断开 VRChat 账号，**保留**这台设备在本服务器上的租户
   * （与手机端 ProfileScreen.kt 的 AlertDialog 一字不差）。
   *
   * 退完把闸门推回来 —— "换一个账号登录"那件事只在闸门里做（选账号免密通道
   * 也在那儿）。不推回来，用户就停在一台"未登录、又没有任何登录入口"的界面上。
   */
  function signOut() {
    return askConfirm({
      title: '退出登录？',
      body: '会断开当前 VRChat 账号，但保留这台设备在此服务器上的租户。',
      okLabel: '退出'
    }, function () {
      setStatus('正在退出登录…');
      return window.VRCX.authLogout().then(function () {
        setStatus('已退出登录。挑一个账号重新登录即可。', 'ok');
        return refreshAuth();   // 重新读真实状态：登录态 + 服务器上可选的账号
      }).then(function () {
        open();
        return true;
      }).catch(function (err) {
        setStatus('退出登录失败：' + window.VRCX.describeError(err), 'error');
        return false;
      });
    });
  }

  /**
   * 忘记此服务器：清掉地址、指纹、租户凭据与登录态，然后把闸门推回面前。
   *
   * ⚠️ 必须 `open()`：配置清了之后闸门是唯一的入口，不推出来用户就守着
   * 一台"未配置、又没入口"的 App —— 这正是它此前"点了没反应"时最要命的地方。
   */
  function forget() {
    return askConfirm({
      title: '忘记此服务器？',
      body: '会清除服务器地址、租户凭据和当前会话，需要重新连接和登录。',
      okLabel: '忘记',
      danger: true
    }, forgetNow);
  }

  function forgetNow() {
    window.VRCX.forgetAll();
    if (el.addr) el.addr.value = '';
    if (el.pin) el.pin.value = '';
    if (el.user) el.user.value = '';
    if (el.pass) el.pass.value = '';
    if (el.label) el.label.value = '';
    if (el.invite) el.invite.value = '';
    state.hasToken = false;
    state.reachable = false;
    streamStopAll();
    state.authenticated = false;
    state.userId = '';
    state.displayName = '';
    state.accounts = [];
    state.pwdOpen = false;
    state.attemptId = '';
    hide2fa();
    paintTopbar('off', '未连接');
    setStatus('已清除本机配置与凭据。填上服务器地址即可重新连接。', 'ok');
    render();
    open();
    return Promise.resolve(true);
  }

  /** 冒烟：真拉一次快照，证明"桥 → 原生 → 钉 → 数据面"整条路是通的。 */
  function smoke() {
    return window.VRCX.snapshot().then(function (snap) {
      var roster = snap && snap.authenticatedRuntimePhase && snap.authenticatedRuntimePhase.friendBaseline;
      var count = roster ? roster.count : 0;
      if (el.onlineText) el.onlineText.textContent = '好友 ' + count;
      setStatus('数据面通了：好友名单 ' + count + ' 条。', 'ok');
      state.friendCount = count;
      notify();
      return true;
    }).catch(function (err) {
      setStatus('快照拉取失败：' + window.VRCX.describeError(err), 'error');
      return false;
    });
  }

  /* ---------------------------------------------------------------- 装配 */

  function wire() {
    var click = function (id, fn) {
      var node = $(id);
      if (node) node.addEventListener('click', function () { fn(); });
    };
    var input = function (id, fn) {
      var node = $(id);
      if (node) node.addEventListener('input', fn);
    };

    click('g-connect', function () { connect(); });
    click('g-claim-go', function () { claim(); });
    click('g-login', function () { loginPwd(); });
    click('g-2fa-go', function () { submit2fa(); });
    click('g-forget', function () { forget(); });
    click('g-close', function () {
      // 只是本次会话里"看一眼"：关掉就走，不留持久标记 ——
      // 没登录的话下次冷启动 boot() 会把闸门重新推出来。
      peeked = true;
      close();
    });

    /* 个人页「账号」那两颗按钮 —— 它们此前没有 id、也没有任何监听器，
       点了毫无反应，而这正是"退出/换服务器"的唯一出口。 */
    click('me-signout', function () { signOut(); });
    click('me-forget', function () { forget(); });

    /* 二次确认自己的两颗按钮 */
    click('confirm-cancel', cancelConfirm);
    click('confirm-ok', acceptConfirm);

    // Esc 关确认框（与详情抽屉的 Esc 行为一致）
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && el.confirm && !el.confirm.hidden) {
        e.preventDefault();
        cancelConfirm();
      }
    });
    click('g-pwd-toggle', function () { state.pwdOpen = true; render(); });
    click('g-pin-toggle', function () {
      if (!el.pinWrap) return;
      el.pinWrap.hidden = !el.pinWrap.hidden;
      syncPinToggleLabel();
      refreshPinAdvice();
    });

    input('g-addr', function () { refreshPinAdvice(); if (el.addrText) el.addrText.textContent = prettyAddress(); });
    input('g-pin', function () { refreshPinAdvice(); });
    input('g-label', render);
    input('g-invite', render);
    input('g-user', render);
    input('g-pass', render);

    // 点顶栏那颗点 = 打开连接设置（顶栏本来就有这个指示点，给它一个入口）
    if (el.conn) {
      el.conn.style.cursor = 'pointer';
      el.conn.setAttribute('role', 'button');
      el.conn.setAttribute('tabindex', '0');
      el.conn.addEventListener('click', open);
      el.conn.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
      });
    }

    // 回车即提交，省掉在平板上挪手指
    [['g-addr', connect], ['g-pin', connect], ['g-label', claim], ['g-invite', claim],
     ['g-pass', loginPwd], ['g-2fa-code', submit2fa]]
      .forEach(function (pair) {
        var node = $(pair[0]);
        if (node) node.addEventListener('keydown', function (e) {
          if (e.key === 'Enter') { e.preventDefault(); pair[1](); }
        });
      });
  }

  function boot() {
    cache();
    if (!el.gate) return;   // 页面里没有闸门（理论上不会）
    wire();
    loadConfig();

    render();

    // 连地址都还没有 → 闸门是唯一入口，也没必要探活。
    var hasAddr = !!((el.addr && el.addr.value) || (el.pin && el.pin.value));
    if (!hasAddr) {
      open();
      paintTopbar('off', '未连接');
      setStatus('填服务器地址后点「连接」。服务器用自签证书时还需要证书指纹（SPKI）。');
      render();
      return;
    }

    // ⚠️ 判据是"有没有地址"，**不是** state.configured ——
    //    configured = 原生 isComplete() = 地址 + 凭据齐备，而"地址已存、人还没加入
    //    这台服务器"（hasToken=false）恰恰是最需要探活的一档：不探活就不知道服务器上
    //    有没有租户、要不要邀请码，闸门只能显示一句静态提示。
    // ⚠️ 旧判据是 `!state.configured && !seen`：只看"配没配地址"就决定放不放人，
    //    于是地址配好、人没登录也照样进主界面，个人页写着"未登录"。
    //    手机端不是这样：Phase.READY 之前只挂 LoginScreen。
    // ⚠️ 没凭据时先把闸门推出来，免得主界面先闪一下再被盖住。
    if (!state.hasToken) open();

    connect().then(function () {
      if (state.authenticated) close();
      else if (!peeked) open();
    });
  }

  /**
   * 给各屏用的连接门面。屏不需要知道桥怎么用，只要：
   *   VRCXCon.onChange(fn)  —— 连接/登录状态变化时被叫；
   *   VRCXCon.get()         —— 当前状态；
   *   VRCXCon.ready()       —— 可以开始取数了吗（已登录）。
   */
  window.VRCXCon = {
    get: function () { return state; },
    ready: function () { return state.authenticated; },
    onChange: function (fn) { listeners.push(fn); return fn; },
    open: open,
    close: close,
    refresh: function () { return connect(); },
    smoke: smoke
  };

  /* 调试口子：真机验收读事件流状态（产品逻辑不依赖它）。 */
  window.__VRCXSTREAM__ = {
    status: function () {
      return { wanted: stream.wanted,
               socket: stream.connected ? 'open' : (stream.nativeOpen ? 'connecting' : 'none'),
               everOpened: stream.everOpened,
               backoff: stream.backoff, lastEvent: stream.lastEvent,
               wsid: state.streamWebsocket || '' };
    },
    frames: function () { return stream.frames.slice(); }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
