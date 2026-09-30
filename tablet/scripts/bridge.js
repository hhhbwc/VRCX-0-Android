/*
 * 数据面桥接 —— 把原生的 window.VRCXNative 包成 Promise API 暴露为 window.VRCX。
 *
 * ## 为什么请求不能直接在网页里发
 *
 * 服务器是自签证书，客户端靠 **公钥 SPKI 指纹**确认对端身份。网页里的
 * fetch / EventSource 做不到：它们只走系统信任库，自签证书直接被拒，
 * 浏览器也不给任何 pin API。所以请求必须由原生发出（net/PinnedTls.java），
 * 这个文件就是那层的 JS 门面。
 *
 * ## 两个运行环境
 *
 * 同一份 HTML 要同时活在两种地方：
 *   - 平板 App 里：window.VRCXNative 存在 → 真实数据；
 *   - 浏览器里（演示/出图/回归）：不存在 → VRCX.available === false，
 *     页面继续用示例数据渲染，不报错、不弹窗。演示件因此仍然能被打开和截图。
 *
 * 这解释了一个刻意的取舍：所有方法在不可用时 reject 一个带 error 的对象，
 * 而调用方（conn.js / 各屏）负责把它显示成"未连接"而不是崩溃。
 */
(function () {
  'use strict';

  var native = null;
  if (typeof window.VRCXNative === 'object' && window.VRCXNative !== null) {
    native = window.VRCXNative;
  }

  var pending = Object.create(null);
  var seq = 0;

  /* ---------------------------------------------------------------- 原生回调入口 */

  /**
   * 原生把结果送回这里。payloadJson 永远是一个 JSON 字符串：
   *   - 成功：服务端原始响应体；
   *   - 失败：{ error, kind, status? }
   * 所以判成败只看有没有 error 字段，不必分辨 HTTP 语义。
   */
  window.__vrcxResolve = function (id, payloadJson) {
    var entry = pending[id];
    if (!entry) return;               // 已超时/已取消，丢弃
    delete pending[id];

    var data;
    try {
      data = payloadJson ? JSON.parse(payloadJson) : {};
    } catch (e) {
      data = {
        error: '原生返回的不是合法 JSON：' + String(payloadJson).slice(0, 200),
        kind: 'protocol'
      };
    }
    if (data && typeof data === 'object' && data.error) entry.reject(data);
    else entry.resolve(data);
  };

  /** 原生在页面加载完成后回调：标记一下，方便样式与诊断区分"在 App 内"。 */
  window.__vrcxBridgeReady = function () {
    document.documentElement.classList.add('native-host');
    if (window.VRCX && typeof window.VRCX.onReady === 'function') {
      try { window.VRCX.onReady(); } catch (e) { /* 页面自己的事，不往上抛 */ }
    }
  };

  /* ---------------------------------------------------------------- 调用 */

  var TIMEOUT_MS = 40000;

  function call(name, args) {
    return new Promise(function (resolve, reject) {
      if (!native || typeof native[name] !== 'function') {
        reject({
          error: '未在 App 内运行：数据面要求证书指纹校验，网页里发不出这种请求。',
          kind: 'unavailable'
        });
        return;
      }
      var id = 'r' + (++seq);
      var timer = setTimeout(function () {
        if (pending[id]) {
          delete pending[id];
          reject({ error: '请求超时（' + TIMEOUT_MS + 'ms）', kind: 'timeout' });
        }
      }, TIMEOUT_MS);

      pending[id] = {
        resolve: function (v) { clearTimeout(timer); resolve(v); },
        reject: function (e) { clearTimeout(timer); reject(e); }
      };

      try {
        native[name].apply(native, [id].concat(args || []));
      } catch (e) {
        clearTimeout(timer);
        delete pending[id];
        // 同步抛出的通常是桥本身的问题（方法签名不符），值得单独标一类。
        reject({ error: '桥调用失败: ' + (e && e.message ? e.message : e), kind: 'bridge' });
      }
    });
  }

  /* ---------------------------------------------------------------- 同步配置 */

  function readConfig() {
    if (!native || typeof native.configJson !== 'function') return null;
    try { return JSON.parse(native.configJson()); } catch (e) { return null; }
  }

  function invokeSync(name, args) {
    if (!native || typeof native[name] !== 'function') {
      return { error: '未在 App 内运行', kind: 'unavailable' };
    }
    try {
      var raw = native[name].apply(native, args || []);
      return JSON.parse(raw);
    } catch (e) {
      return { error: '配置操作失败: ' + (e && e.message ? e.message : e), kind: 'bridge' };
    }
  }

  /* ---------------------------------------------------------------- 对外 */

  var VRCX = {
    /** 是否运行在平板 App 内（浏览器里为 false）。 */
    available: !!native,

    /** 页面可覆盖：桥就绪时被调用一次。 */
    onReady: null,

    /** 当前配置快照（不含 token，只有 hasToken）。浏览器里返回 null。 */
    config: readConfig,

    /** 保存地址/指纹/token。返回最新配置，或 { error }。 */
    saveConfig: function (obj) {
      return invokeSync('saveConfig', [JSON.stringify(obj || {})]);
    },

    /** 只丢凭据、保留地址（服务器吊销了 token 时用）。 */
    clearToken: function () { return invokeSync('clearToken', []); },

    /** 连地址带指纹一起忘掉。 */
    forgetAll: function () { return invokeSync('forgetAll', []); },

    /* -------- 数据面 -------- */

    /** GET /v1/health —— 公开，用来判断可达与版本。 */
    health: function () { return call('health', []); },

    /** GET /v1/auth/status —— 用 200/401 判断凭据是否仍有效。 */
    authStatus: function () { return call('authStatus', []); },

    /**
     * GET /v1/auth/accounts —— 服务器上已保存凭据的账号（「选择账号」那段用）。
     * 返回 { authenticated, loginAvailable, currentUserId, accounts: [...] }，
     * accounts 每项是 { userId, displayName?, username?, iconUrl? }，不含任何凭据。
     */
    accounts: function () { return call('authAccounts', []); },

    authLogout: function () { return call('authLogout', []); },

    /**
     * 登录。resolve 出来的是原始的 AuthOutcome：
     *   { status: 'authenticated', userId, displayName, endpoint }
     *   { status: 'challenge', attemptId, methods: [...] }
     *   { status: 'failed', reason, kind }
     * 判别键是服务端的 status（#[serde(tag = "status")]），别自己另造。
     */
    login: function (username, password, saveCredentials, userId) {
      return call('authLogin', [username, password, !!saveCredentials, userId || '']);
    },

    /**
     * 选一个服务器上已保存凭据的账号登录 —— 不需要密码。
     * 服务端 LoginRequest 里 userId 非空即走这条分支（此时忽略 username/password）。
     */
    loginWithAccount: function (userId) {
      return VRCX.login('', '', false, userId);
    },

    /** 二次验证。method 取 challenge 给的 methods 之一。 */
    twoFactor: function (attemptId, method, code) {
      return call('auth2fa', [attemptId, method, code]);
    },

    /**
     * 领一个租户名额。成功后凭据存在**原生侧**，返回体里不带 token ——
     * 网页永远看不到它，也就无从泄漏。
     */
    claimTenant: function (label, inviteCode) {
      return call('claimTenant', [label || '平板', inviteCode || '']);
    },

    /**
     * 跑一条业务命令。args 传平铺的参数对象即可 ——
     * 服务端要的形态（input 包装 / 平铺 / 无参）由 argforms.js 那张表决定，
     * 弄错形态会在服务端报 missing `input` argument，而且很容易被当成"没数据"。
     */
    command: function (name, args) {
      var shape = window.ArgForms ? window.ArgForms.encode(name, args || {}) : (args || {});
      return call('command', [name, JSON.stringify(shape)]);
    },

    /** 逃生口：需要尚未单独封装的路由时用，仍走同一套指纹与凭据。 */
    request: function (method, path, body) {
      return call('request', [method, path, body ? JSON.stringify(body) : '']);
    },

    /* -------- 便利封装 -------- */

    /** 已登录用户的完整运行时快照（好友名单 / 收藏 / 自己的资料都在里面）。 */
    snapshot: function () {
      return VRCX.command('app__backend_runtime_combined_snapshot_get', {});
    },

    /**
     * VRChat 的图片 URL 必须经服务器代理才能取到：那些 URL 对没有账号
     * 会话 cookie 的人是 403，而 cookie 在服务器上。返回 data:image/... 字符串。
     */
    imageDataUrl: function (url) {
      return VRCX.command('app__external_api_image_data_url_get', { url: url });
    },

    /** 把失败对象整理成一句能直接显示的话。 */
    describeError: function (err) {
      if (!err) return '未知错误';
      if (typeof err === 'string') return err;
      var text = err.error || err.message || JSON.stringify(err);
      if (err.kind === 'unavailable') return text;
      if (err.status === 401) return '凭据已失效（401）—— 需要重新领取或轮换 token。';
      if (err.status === 501) return '服务器没有实现这条命令（501）：' + text;
      /* VRChat 会话失效：服务器的 VRChat 登录死了，**不是**本机凭据问题 ——
         服务端转发 VRChat 请求失败时以 502 回来，401 藏在消息体里
         （0.2.18：抓共同好友图把会话打到被吊销时，用户看到的应是
         "去重新登录"，而不是误导性的"凭据失效"）。 */
      if (err.kind === 'http' && err.status === 502
          && /VRChat 请求失败（401）|Missing Credentials|session expired/i.test(text)) {
        return '服务器的 VRChat 会话已失效 —— 去「个人」页退出登录后重新登录一次就好。';
      }
      if (err.kind === 'transport') return '连不上服务器：' + text;
      return text;
    }
  };

  window.VRCX = VRCX;

  // 桥已注册（addJavascriptInterface 在 loadUrl 之前完成），所以这里通常已经可用；
  // 若页面被以 file:// 直接打开（浏览器），可用性为 false，走示例数据路径。
  if (native) document.documentElement.classList.add('native-host');
})();
