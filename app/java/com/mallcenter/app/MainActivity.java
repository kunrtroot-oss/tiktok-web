package com.mallcenter.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/**
 * 把一个网页装进安卓 App 的外壳。
 *
 * <p>首页地址来自 {@link Endpoints}，以加密形式存放（不落明文），运行时还原后再加载。
 *
 * <p>启动流程分两段，避免中间出现白屏：
 *   1) 系统阶段：主题的 android:windowBackground 指向启动图，
 *      Activity 还没创建出来时就已经把启动图画在屏幕上；
 *   2) App 阶段：本类再叠一层同样的启动图盖住 WebView，
 *      等网页加载完成（或超时兜底）再淡出，两端画面完全一致，用户看不出接缝。
 *
 * <p>入口行（店铺中心 / 商品橱窗 / 订单详情）由 {@link ProfileEntranceRow} <b>注入到个人主页正文里</b>
 * ——位置在个人简介下面、视频列表上面，随页面一起滚动，和参考包一致（不是浮在网页上方）。
 * 只在个人主页显示；点击后跳到 {@link MallActivity} 这个独立网页页。
 */
public class MainActivity extends Activity {

    /**
     * 调试用：adb 指定首屏地址时用。
     * <p>例：{@code adb shell am start -n com.mallcenter.app/.MainActivity -e debug_url https://www.tiktok.com/@tiktok}
     * <p>只接受本站地址，外部应用塞进来的其它网址会被忽略，正式用户不会用到。
     */
    private static final String EXTRA_DEBUG_URL = "debug_url";

    /** 启动图最长停留时间：网络太差时也要让用户进得去 */
    private static final long SPLASH_TIMEOUT_MS = 8000L;
    /** 启动图淡出时长 */
    private static final long SPLASH_FADE_MS = 350L;

    private WebView webView;
    private ProgressBar progressBar;
    /** 盖在网页上的启动图，网页就绪后淡出 */
    private View splashView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 启动图是否已开始退场（防止重复触发淡出动画） */
    private boolean splashDismissed;
    /** 主页地址（运行时还原后缓存，用于判断是否停留在个人主页） */
    private String homeUrl;
    /** 上一次同步给网页的入口行状态，避免在同一个页面上反复注入 */
    private boolean entranceRowShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 用 FrameLayout 叠三层：网页层 + 顶部加载进度条 + 启动图（最后加的在最上面）
        FrameLayout root = new FrameLayout(this);

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        // 3dp 高的细进度条，避免遮挡页面内容
        int barHeight = (int) (3 * getResources().getDisplayMetrics().density);
        root.addView(progressBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight));

        // 启动图用背景图方式贴满整屏：与主题里的 windowBackground 用同一张图，
        // 从冷启动画面切到这一层时看不出任何跳变。
        splashView = new View(this);
        splashView.setBackgroundResource(R.drawable.splash);
        root.addView(splashView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        configureWebView();

        homeUrl = Endpoints.home();

        // 兜底：网页迟迟不返回也不把用户卡在启动图上
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dismissSplash();
            }
        }, SPLASH_TIMEOUT_MS);

        if (savedInstanceState != null) {
            // 旋屏/被系统回收后恢复现场，不重新加载首页；启动图立刻撤掉
            webView.restoreState(savedInstanceState);
            syncEntranceRow(webView.getUrl());
            dismissSplash();
        } else {
            webView.loadUrl(resolveFirstUrl());
        }
        setTitle(getString(R.string.app_name));
    }

    /**
     * 决定首屏加载哪个地址：默认首页；带调试参数且属于本站时用调试地址。
     * 只放行本站地址，避免外部应用通过该参数把 App 当跳板打开任意网页。
     */
    private String resolveFirstUrl() {
        String debugUrl = getIntent().getStringExtra(EXTRA_DEBUG_URL);
        if (debugUrl != null && debugUrl.startsWith(homeUrl)) {
            return debugUrl;
        }
        return homeUrl;
    }

    /** 打开入口对应的独立网页页，把地址、路线、入口名一并带过去 */
    private void openEntrance(Entrance entrance) {
        Intent intent = new Intent(this, MallActivity.class);
        intent.putExtra(MallActivity.EXTRA_URL, entrance.url);
        intent.putExtra(MallActivity.EXTRA_ROUTE, entrance.route);
        intent.putExtra(MallActivity.EXTRA_TITLE, getString(entrance.labelRes));
        startActivity(intent);
    }

    /**
     * 按路线标识找到对应入口；找不到就返回 null。
     *
     * <p>网页那边只送进来一个字符串，地址始终从 {@link Endpoints} 取，
     * 外部脚本即使伪造一个 route 也打不开白名单之外的页面。
     */
    private Entrance findEntrance(String route) {
        if (route == null) {
            return null;
        }
        for (Entrance entrance : Entrance.all()) {
            if (route.equals(entrance.route)) {
                return entrance;
            }
        }
        return null;
    }

    /**
     * 暴露给网页的桥：注入的入口行被点击时，网页调用 {@code window.android.openEntrance(route)}。
     *
     * <p>注意：这里的回调发生在 WebView 的 JS 线程，不是界面线程，
     * 所以打开页面这件事必须用 {@code runOnUiThread} 抛回界面线程再做。
     */
    private class EntranceBridge {

        @JavascriptInterface
        public void openEntrance(final String route) {
            final Entrance entrance = findEntrance(route);
            if (entrance == null) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // 必须写 MainActivity.this：桥里同名的方法会遮住外层方法
                    MainActivity.this.openEntrance(entrance);
                }
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        // 短视频需要自动播放，不强制用户手势
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 伪装成主流 Chrome 手机浏览器 UA，降低被站点判定为内置浏览器而降级的概率
        settings.setUserAgentString(buildChromeUserAgent());

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // 注册 JS 桥：网页里注入的入口行点一下，就通过它回到原生打开 MallActivity
        webView.addJavascriptInterface(new EntranceBridge(), "android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 整页重新加载后网页里的注入内容已随旧文档一起消失，
                // 因此这里先清掉「已注入」的记录，强制重新注入一次
                entranceRowShown = false;
                syncEntranceRow(url);
                dismissSplash();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // 单页应用内部跳转（如 pushState 改 URL）不会触发 onPageFinished，
                // 这里补一次同步，保证在个人主页与其它页面之间切换时入口行能及时出现/消失
                syncEntranceRow(url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // 网页申请摄像头/麦克风时直接放行，保证拍摄、直播等能力可用
                request.grant(request.getResources());
            }
        });
    }

    /** 让启动图淡出并移除。多次调用只生效一次（超时兜底与加载完成可能同时触发） */
    private void dismissSplash() {
        if (splashDismissed || splashView == null) {
            return;
        }
        splashDismissed = true;
        mainHandler.removeCallbacksAndMessages(null);
        splashView.animate()
                .alpha(0f)
                .setDuration(SPLASH_FADE_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        // 动画结束后彻底摘掉，避免它长期占着一层绘制开销
                        if (splashView != null && splashView.getParent() instanceof ViewGroup) {
                            ((ViewGroup) splashView.getParent()).removeView(splashView);
                        }
                        splashView = null;
                    }
                })
                .start();
    }

    /**
     * 让网页里的入口行与当前页面一致：个人主页注入出来，其它页面移除。
     *
     * <p>入口行是网页里的一段 DOM（位置在个人简介下方），所以「显示/隐藏」是通过
     * {@link ProfileEntranceRow} 注入脚本完成的，原生这边不再往界面上加任何控件，
     * 页面也不会被下压或遮挡。
     *
     * <p>同一个状态不重复注入，避免在个人主页里反复执行脚本。
     */
    private void syncEntranceRow(String url) {
        boolean show = isProfilePage(url);
        if (show == entranceRowShown) {
            return;
        }
        entranceRowShown = show;
        ProfileEntranceRow.apply(webView, this, show);
    }

    /**
     * http(s) 链接一律留在 App 内；其它协议（tg://、intent://、tel: 等）交给系统处理。
     *
     * @return true 表示已由 App 自己处理，WebView 不再加载
     */
    private boolean handleUrl(String url) {
        if (url == null) {
            return false;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            // 没有可处理该协议的应用时静默忽略，避免崩溃
        }
        return true;
    }

    /** 用系统默认 UA 里的平台信息拼出一个标准 Chrome 手机端 UA */
    private String buildChromeUserAgent() {
        String defaultUa = WebSettings.getDefaultUserAgent(this);
        String platform = "";
        int start = defaultUa.indexOf(" (");
        int end = defaultUa.indexOf(") AppleWebKit");
        if (start >= 0 && end > start) {
            platform = defaultUa.substring(start, end + 1);
        }
        return "Mozilla/5.0" + platform
                + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    }

    /**
     * 判断当前 URL 是否停留在个人主页（Profile）。
     * tiktok 的 Profile 页有两类：
     *  1) 自己的主页 /profile（点底部 Profile 标签进入，可能带 ?/# 参数）；
     *  2) 别人的主页 /@用户名（用户名后不再有 /video/ 等子路径）。
     * 首页、视频页、发现页等其它页面都不算 Profile。
     */
    private boolean isProfilePage(String url) {
        if (url == null || homeUrl == null) {
            return false;
        }
        String base = homeUrl.endsWith("/") ? homeUrl.substring(0, homeUrl.length() - 1) : homeUrl;
        if (!url.startsWith(base)) {
            return false;
        }
        String rest = url.substring(base.length());
        // 自己的主页：/profile（可能带 ?/# 参数）
        if (rest.equals("/profile") || rest.startsWith("/profile?") || rest.startsWith("/profile#")) {
            return true;
        }
        // 别人的主页：/@username（用户名后不再有 / 子路径）
        if (!rest.startsWith("/@")) {
            return false;
        }
        String afterUser = rest.substring(2);  // 去掉 "/@"，剩下用户名及可能的参数
        if (afterUser.isEmpty()) {
            return false;
        }
        // 用户名后若还有 / 子路径（如 /@user/video/123），则不是纯 Profile 页
        return afterUser.indexOf('/') < 0;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 返回键优先回退网页历史，回到底再退出 App
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        // 先摘掉延时任务，避免 Activity 销毁后 Handler 仍持有引用
        mainHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        entranceRowShown = false;
        splashView = null;
        super.onDestroy();
    }
}
