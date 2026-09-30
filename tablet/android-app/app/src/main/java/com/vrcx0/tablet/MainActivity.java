package com.vrcx0.tablet;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 真机预览壳（开发期），**不是**另一个产品。
 *
 * 界面本身是一套 HTML/CSS（与手机端共用同一套 M3 令牌与组件），
 * 这里把它装进一个全屏 WebView，并把 {@link DataBridge} 挂上去 ——
 * 网页通过 {@code window.VRCXNative} 访问数据面。
 *
 * 合并后的定位：平板不是独立模式，只是同一套 UI 在容器变宽后的自然重排。
 * 正式交付时这一层并进手机端 App（com.vrcx0.android）的一个尺寸档，本壳退役；
 * 眼下沿用独立 applicationId 只是为了让预览壳不与手机端互相覆盖安装。
 *
 * `index.html#device` 是 main.js 里的「真机全屏」模式：去掉演示者外壳，
 * 容器尺寸跟随窗口，于是 `@container app` 的断点由真实屏幕宽度驱动
 * （竖屏底部 Tab、横屏侧边导轨）。
 *
 * 刻意不引 AndroidX / Compose：界面在网页里，这一层只有"壳 + 桥"两件事，
 * 依赖面越小越稳（也绕开本机 Maven 拉不到新依赖的问题）。
 */
public class MainActivity extends Activity {

    /** 让出的系统栏区域显示它 —— 观感上仍是"铺满整屏"，只是内容不钻到栏底下。 */
    private static final int SURFACE = 0xFFFAF8FF;

    private WebView web;
    private DataBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 让 viewport meta 生效：CSS 像素宽度 == 设备宽度，
        // 容器查询的断点才会落在设计好的宽度上。
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        // ⚠️⚠️ 这三个 **不足以** 关掉双指缩放！它们只关掉 WebView 自带的缩放**控件**
        // （那个 +/- 悬浮条），手势捏合照样生效 —— 真机上"何时何地都能双指放大"
        // 就是这么来的。真正拦得住捏合的是 index.html 里 viewport meta 的
        // `maximum-scale=1, user-scalable=no`（外加 app.css 里 touch-action 兜底）。
        // 这两组要一起留着，别觉得下面三行是多余的、也别只留三行。
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        // 桥与数据面都是 https，且资源全在 assets 里 —— 不需要允许混合内容。
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // ⚠️ 只在 debuggable 包上打开 WebView 远程调试（CDP）。
        // 没有这一句的话 /proc/net/unix 里根本不会出现 webview_devtools_remote_*，
        // _shots/device_probe.py 连不上 —— 真机排障只能靠截图瞎猜（实踩：2026-09-30）。
        // release 正式分发不带此标志，调试端口不会暴露。
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // 更像原生：长按不弹上下文菜单、不显示滚动回弹光晕。
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(SURFACE);

        bridge = new DataBridge(new ServerConfig(this), web);
        // 必须在 loadUrl 之前注册，否则页面第一帧里 window.VRCXNative 还不存在，
        // bridge.js 会误判成"不在 App 内运行"。
        web.addJavascriptInterface(bridge, DataBridge.JS_NAME);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 只做一件事：告诉页面桥已就绪，方便它在控制台/UI 上区分
                // "配置没填" 和 "根本没在 App 里跑"。失败不影响页面。
                view.evaluateJavascript(
                        "window.__vrcxBridgeReady && window.__vrcxBridgeReady();", null);
            }
        });

        web.loadUrl("file:///android_asset/index.html#device");

        /*
         * targetSdk 35 起系统**强制** edge-to-edge：窗口铺满整屏，网页会画到状态栏与
         * 导航栏底下（实测首屏顶栏整条被状态栏压住）。让位要在**父容器**上加 padding。
         *
         * ⚠️⚠️ 不能让 WebView 自己 setPadding —— 上一版就是那样写的，真机上**完全无效**：
         *   网页由 Chromium 的合成层铺满整个 View，View 的 padding 只影响它的背景，
         *   不会把页面内容推下来。后果不只是难看：顶栏那颗连接状态点正好落进状态栏的
         *   触摸区，点它只会被系统拉下通知栏 —— 而它就是连接闸门的唯一入口，
         *   于是"连不上服务器"就变成了"连入口都没有"。父容器吃 insets 是实打实
         *   缩小 WebView 的布局尺寸，与内容怎么合成无关，一定生效。
         *
         * 顺带一个好处：这里**不覆盖** WebView 自身的 onApplyWindowInsets（insets 照常
         * 往下传，不 consume），Chromium 仍拿得到自己的那份 —— env(safe-area-inset-*)
         * 与"键盘弹出时把聚焦的输入框滚进可视区"都还归它管。
         *
         * 键盘要单独并进来：`ime()` 不算 systemBars，而 targetSdk 35 下 manifest 里的
         * adjustResize 已经不生效 —— 不并的话闸门里的密码/验证码框会被键盘盖住，
         * 且页面不会缩，滚都滚不出来。
         */
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(SURFACE);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            // getSystemWindowInset* 在 API 30 起废弃（改用 getInsets(Type)），
            // 但 API 26~29 上仍只有它可用 —— 上面按版本分流，这里只是压噪音。
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left;
                int top;
                int right;
                int bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = Math.max(bars.bottom, ime.bottom);
                } else {
                    // API 26~29：键盘高度本来就算在 "system window inset bottom" 里。
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(left, top, right, bottom);
                return insets;
            }
        });

        setContentView(root);
        root.requestApplyInsets();
    }

    @Override
    // onBackPressed 在 API 33+ 已废弃（推荐 OnBackInvokedCallback），但那个 API
    // 要求 AndroidX activity 或自行注册回调 —— 对一个只负责"能返回网页上一页"的
    // 壳来说不值得拉依赖。行为正确，抑制噪音即可。
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.shutdown();
            bridge = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
