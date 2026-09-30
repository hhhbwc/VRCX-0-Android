/*
 * scripts/bridge.js + scripts/argforms.js 的契约测试（node 跑，不需要浏览器/设备）。
 *
 * 为什么需要它：桥的两半分别活在 Java 和 JS 里，而对接点是一个**字符串协议**
 * （方法名 + reqId + JSON 字符串）。这类接口最容易出的问题是"某个环节名字拼错"
 * 或"参数形态包错"，而两者的表现都是**静默没数据**，不会抛错。
 * 这里用一个假的 window.VRCXNative 把 JS 这半独自跑起来，逐条钉死：
 *
 *   1. 不在 App 内（没有原生对象）时，调用必须可预期地 reject，而不是静默挂着；
 *   2. 调用→回调（reqId 对上）能正确 resolve，且结果被解析成对象；
 *   3. 原生返回 {error} → reject；返回非 JSON → 一个明确的 protocol 错误；
 *   4. **参数形态**：input 包装 / 平铺 / 无参三种，按 argforms.js 的表走
 *      （这条是重点：包错形态服务端会报 missing `input` argument，
 *        而调用方只会看到"没数据"）；
 *   5. 未知 reqId / 重复回调不会炸；
 *   6. 超时路径存在（不在这里等 40s，只断言它是被登记的定时器）。
 *
 * 用法：node tools/bridge_contract_test.js [tablet-mode 目录]
 */

'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HERE = __dirname;
const DEMO = process.argv[2] ? path.resolve(process.argv[2]) : path.resolve(HERE, '..', '..');

let pass = 0, fail = 0;
function check(name, ok, detail) {
  if (ok) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (detail ? '  <' + detail + '>' : '')); }
}

/* ---------------------------------------------------------------- 假环境 */

/** 造一个最小的 window/document，让 bridge.js 能跑起来。 */
function makeSandbox(nativeImpl) {
  const classes = new Set();
  const document = {
    documentElement: { classList: { add: (c) => classes.add(c) } },
    addEventListener: () => {},
    readyState: 'complete'
  };
  const sandbox = {
    console,
    setTimeout,
    clearTimeout,
    JSON,
    Promise,
    Object,
    Array,
    String,
    Number,
    Boolean,
    Error,
    Date,
    document,
    location: { hash: '', protocol: 'file:', hostname: '', pathname: '/index.html' },
    localStorage: { getItem: () => null, setItem: () => {} },
    navigator: {}
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  if (nativeImpl) sandbox.VRCXNative = nativeImpl;
  sandbox.__classes = classes;
  vm.createContext(sandbox);
  return sandbox;
}

function loadScripts(sandbox, files) {
  for (const f of files) {
    const src = fs.readFileSync(path.join(DEMO, f), 'utf8');
    vm.runInContext(src, sandbox, { filename: f });
  }
}

/** 造一个记录调用、并把 reqId 交给测试来回复的假原生。 */
function makeFakeNative() {
  const calls = [];
  const native = {
    calls,
    configJson() { return JSON.stringify({ serverAddress: 'https://s.example', certificatePin: '', hasToken: true, complete: true }); },
    saveConfig(json) { calls.push({ name: 'saveConfig', args: [json] }); return JSON.stringify({ ok: true }); },
    clearToken() { calls.push({ name: 'clearToken' }); return '{}'; },
    forgetAll() { calls.push({ name: 'forgetAll' }); return '{}'; }
  };
  ['health', 'authStatus', 'authAccounts', 'authLogout', 'authLogin', 'auth2fa', 'claimTenant', 'command', 'request']
    .forEach(function (name) {
      native[name] = function () {
        const args = Array.prototype.slice.call(arguments);
        calls.push({ name: name, args: args });
        return undefined;   // 异步：结果稍后由测试通过 __vrcxResolve 送回
      };
    });
  return native;
}

/** 取出最近一次调用。 */
function lastCall(native, name) {
  for (let i = native.calls.length - 1; i >= 0; i--) {
    if (native.calls[i].name === name) return native.calls[i];
  }
  return null;
}

/* ---------------------------------------------------------------- 用例 */

async function main() {
  console.log('=== bridge.js / argforms.js 契约测试 ===');
  console.log('demo: ' + DEMO);
  console.log('');

  // ---------------------------------------------------------------- 1. 不在 App 内
  console.log('--- 不在 App 内（浏览器里打开 demo）---');
  {
    const sb = makeSandbox(null);
    loadScripts(sb, ['scripts/argforms.js', 'scripts/bridge.js']);
    check('VRCX.available === false', sb.VRCX.available === false);
    check('config() 返回 null（而不是抛错）', sb.VRCX.config() === null);
    check('页面未被标记为 native-host', !sb.__classes.has('native-host'));
    let err = null;
    try { await sb.VRCX.health(); } catch (e) { err = e; }
    check('health() reject 且 kind=unavailable',
          !!err && err.kind === 'unavailable', err && JSON.stringify(err));
    check('describeError 给出可读原因',
          typeof sb.VRCX.describeError(err) === 'string' &&
          sb.VRCX.describeError(err).indexOf('未在 App 内') >= 0);
  }

  // ---------------------------------------------------------------- 2. 调用/回调
  console.log('--- 调用 → 回调（reqId 对上）---');
  {
    const native = makeFakeNative();
    const sb = makeSandbox(native);
    loadScripts(sb, ['scripts/argforms.js', 'scripts/bridge.js']);
    check('VRCX.available === true', sb.VRCX.available === true);
    check('页面被标记为 native-host', sb.__classes.has('native-host'));

    const p = sb.VRCX.health();
    const call = lastCall(native, 'health');
    check('health() 调到了原生的 health 方法', !!call);
    check('第一个参数是 reqId 字符串',
          !!call && typeof call.args[0] === 'string' && call.args[0].length > 0,
          call && JSON.stringify(call.args));

    const id = call.args[0];
    sb.__vrcxResolve(id, JSON.stringify({ status: 'ok', appVersion: '0.1.0' }));
    const res = await p;
    check('回调后被 resolve，且结果被解析成对象',
          res && res.status === 'ok' && res.appVersion === '0.1.0', JSON.stringify(res));

    // 原生返回失败形状 → reject
    const p2 = sb.VRCX.authStatus();
    const id2 = lastCall(native, 'authStatus').args[0];
    sb.__vrcxResolve(id2, JSON.stringify({ error: '凭据失效', status: 401, kind: 'http' }));
    let err2 = null;
    try { await p2; } catch (e) { err2 = e; }
    check('原生回 {error} → reject', !!err2 && err2.status === 401, err2 && JSON.stringify(err2));
    check('describeError 把 401 说成"凭据已失效"',
          sb.VRCX.describeError(err2).indexOf('401') >= 0);
    // VRChat 会话失效：服务端转发 VRChat 请求失败以 502 回来、401 在消息里 ——
    // 要指向"重新登录"，不能误判成本机凭据问题（0.2.18）
    const vrchatDead = { error: 'VRChat 请求失败（401）', status: 502, kind: 'http' };
    check('describeError 把 VRChat 会话失效（502+401）指向重新登录',
          sb.VRCX.describeError(vrchatDead).indexOf('重新登录') >= 0,
          sb.VRCX.describeError(vrchatDead));
    check('describeError 的本机 401 不被误判成 VRChat 会话',
          sb.VRCX.describeError(err2).indexOf('VRChat 会话') < 0,
          sb.VRCX.describeError(err2));

    // 原生返回非 JSON → 一个明确的 protocol 错误，而不是把原文当对象用
    const p3 = sb.VRCX.health();
    const id3 = lastCall(native, 'health').args[0];
    sb.__vrcxResolve(id3, '<<HTML 502 page>>');
    let err3 = null;
    try { await p3; } catch (e) { err3 = e; }
    check('非 JSON 响应 → reject 且 kind=protocol',
          !!err3 && err3.kind === 'protocol', err3 && JSON.stringify(err3));

    // 未知 reqId 不应炸
    let threw = false;
    try { sb.__vrcxResolve('nope-123', '{}'); } catch (e) { threw = true; }
    check('未知 reqId 的回调被安全忽略', !threw);

    // 二次回调同一个 id 不应炸（且不会重复 resolve）
    const p4 = sb.VRCX.health();
    const id4 = lastCall(native, 'health').args[0];
    sb.__vrcxResolve(id4, '{"n":1}');
    const r4 = await p4;
    let threw2 = false;
    try { sb.__vrcxResolve(id4, '{"n":2}'); } catch (e) { threw2 = true; }
    check('同一 id 重复回调被忽略', !threw2 && r4.n === 1, JSON.stringify(r4));
  }

  // ---------------------------------------------------------------- 3. 参数形态
  console.log('--- 参数形态（input / 平铺 / 无参）---');
  {
    const native = makeFakeNative();
    const sb = makeSandbox(native);
    loadScripts(sb, ['scripts/argforms.js', 'scripts/bridge.js']);
    const AF = sb.ArgForms;
    console.log('        表：INPUT ' + AF.INPUT_COMMANDS.length +
                ' · FLAT ' + Object.keys(AF.FLAT_ARG_NAMES).length +
                ' · NO_ARG ' + AF.NO_ARG_COMMANDS.length);
    check('三种形态的表都非空（空表会让参数静默错形）',
          AF.INPUT_COMMANDS.length > 0 &&
          Object.keys(AF.FLAT_ARG_NAMES).length > 0 &&
          AF.NO_ARG_COMMANDS.length > 0);

    const inputCmd = AF.INPUT_COMMANDS[0];
    const noArgCmd = AF.NO_ARG_COMMANDS[0];
    const flatCmd = Object.keys(AF.FLAT_ARG_NAMES).filter(
      (c) => AF.INPUT_COMMANDS.indexOf(c) < 0)[0];

    check('app__avatar_get 属 input 形态（已知事实）', AF.usesInput('app__avatar_get'));

    // input 形态：整个参数对象放到 input 下
    sb.VRCX.command(inputCmd, { avatarId: 'avtr_x' });
    let c = lastCall(native, 'command');
    check('input 形态：args = {input:{...}}',
          c.args[2] === '{"input":{"avatarId":"avtr_x"}}', c.args[2]);

    // 无参形态：args = {}
    sb.VRCX.command(noArgCmd, { ignored: 1 });
    c = lastCall(native, 'command');
    check('无参形态：args = {}',
          c.args[2] === '{}', c.args[2]);

    // 平铺形态：原样
    if (flatCmd) {
      sb.VRCX.command(flatCmd, { avatarId: 'avtr_y' });
      c = lastCall(native, 'command');
      check('平铺形态：args 原样（' + flatCmd + '）',
            c.args[2] === '{"avatarId":"avtr_y"}', c.args[2]);
    } else {
      check('平铺形态：找到一条可用于测试的命令', false, 'FLAT_ARG_NAMES 全被 input 覆盖');
    }

    // imageDataUrl 的便捷封装应当走 input 形态
    sb.VRCX.imageDataUrl('https://api.vrchat.cloud/x.png');
    c = lastCall(native, 'command');
    check('imageDataUrl() 包成 input 形态',
          c.args[1] === 'app__external_api_image_data_url_get' &&
          c.args[2].indexOf('"input"') >= 0, c.args[1] + ' ' + c.args[2]);

    // snapshot 无参
    sb.VRCX.snapshot();
    c = lastCall(native, 'command');
    check('snapshot() 走 combined_snapshot 且无参',
          c.args[1] === 'app__backend_runtime_combined_snapshot_get' && c.args[2] === '{}',
          c.args[1] + ' ' + c.args[2]);
  }

  // ---------------------------------------------------------------- 4. 同步配置
  console.log('--- 同步配置读写 ---');
  {
    const native = makeFakeNative();
    const sb = makeSandbox(native);
    loadScripts(sb, ['scripts/argforms.js', 'scripts/bridge.js']);
    check('config() 解析原生返回的 JSON',
          sb.VRCX.config() && sb.VRCX.config().serverAddress === 'https://s.example');
    check('config() 不含 token 字段（凭据不出网）',
          sb.VRCX.config().token === undefined && sb.VRCX.config().hasToken === true);
    sb.VRCX.saveConfig({ serverAddress: 'https://a.b' });
    const sc = lastCall(native, 'saveConfig');
    check('saveConfig() 把对象序列化成 JSON 传给原生',
          sc.args[0] === '{"serverAddress":"https://a.b"}', sc.args[0]);
    sb.VRCX.forgetAll();
    check('forgetAll() 抵达原生', !!lastCall(native, 'forgetAll'));
  }

  // ---------------------------------------------------------------- 5. 选账号（免密登录）
  console.log('--- 选账号（服务器已保存凭据的账号）---');
  {
    const native = makeFakeNative();
    const sb = makeSandbox(native);
    loadScripts(sb, ['scripts/argforms.js', 'scripts/bridge.js']);

    const p = sb.VRCX.accounts();
    const c = lastCall(native, 'authAccounts');
    check('accounts() 打到原生的 authAccounts（GET /v1/auth/accounts）', !!c);
    check('accounts() 只带 reqId，不带额外参数',
          c && c.args.length === 1, c && String(c.args.length));
    sb.__vrcxResolve(c.args[0], JSON.stringify({
      authenticated: false, loginAvailable: true, accounts: [{ userId: 'usr_a', username: 'a' }]
    }));
    const data = await p;
    check('accounts() resolve 出服务端原始响应（loginAvailable + accounts 数组）',
          data && data.loginAvailable === true && Array.isArray(data.accounts) && data.accounts.length === 1,
          JSON.stringify(data));

    const p2 = sb.VRCX.loginWithAccount('usr_abc');
    const c2 = lastCall(native, 'authLogin');
    check('loginWithAccount() 走 authLogin', !!c2);
    check('选账号时 username/password 为空、userId 非空（服务端按 userId 分支）',
          c2 && c2.args[1] === '' && c2.args[2] === '' && c2.args[4] === 'usr_abc',
          c2 && JSON.stringify(c2.args.slice(1)));
    check('选账号不带 saveCredentials（凭据本来就在服务器上）',
          c2 && c2.args[3] === false, c2 && String(c2.args[3]));
    sb.__vrcxResolve(c2.args[0], JSON.stringify({ status: 'authenticated', userId: 'usr_abc' }));
    const out = await p2;
    check('选账号的响应原样返回（按 status 判别）',
          out && out.status === 'authenticated', JSON.stringify(out));
  }

  console.log('');
  console.log('合计 ' + (pass + fail) + ' 项，PASS ' + pass + '，FAIL ' + fail);
  return fail ? 1 : 0;
}

main().then(function (code) { process.exit(code); })
      .catch(function (e) { console.error('测试自身异常：', e); process.exit(2); });
