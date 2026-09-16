package com.mallcenter.app;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
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
 * 首页地址与「商城」入口地址均以加密形式存放（不落明文），运行时还原后再加载。
 *
 * 启动流程分两段，避免中间出现白屏：
 *   1) 系统阶段：主题的 android:windowBackground 指向启动图，
 *      Activity 还没创建出来时就已经把启动图画在屏幕上；
 *   2) App 阶段：本类再叠一层同样的启动图盖住 WebView，
 *      等网页加载完成（或超时兜底）再淡出，两端画面完全一致，用户看不出接缝。
 */
public class MainActivity extends Activity {

    /** 首页地址的加密数据：与密钥流逐字符异或后的结果 */
    private static final int[] HOME_ENC = {
            65, 108, 134, 246, 27, 92, 229, 90, 128, 197, 111, 130, 167,
            149, 17, 123, 237, 107, 15, 84, 176, 98, 171
    };
    /** 首页地址的密钥流种子 */
    private static final int HOME_SEED = 0x6D2B79F5;

    /** 「商城」入口地址的加密数据 */
    private static final int[] MALL_ENC = {
            254, 229, 207, 21, 21, 17, 48, 50, 139, 196, 72, 134, 163,
            64, 125, 74, 41, 206, 191, 124, 89, 8
    };
    /** 「商城」入口地址的密钥流种子 */
    private static final int MALL_SEED = 0x1B873593;

    /** 「商家」入口地址的加密数据 */
    private static final int[] MERCHANT_ENC = {
            163, 156, 130, 188, 4, 84, 169, 46, 150, 75, 91, 156, 231,
            244, 223, 191, 163, 83, 67, 99, 35, 183
    };
    /** 「商家」入口地址的密钥流种子 */
    private static final int MERCHANT_SEED = 0x9E3779B9;

    /** 启动图最长停留时间：网络太差时也要让用户进得去 */
    private static final long SPLASH_TIMEOUT_MS = 8000L;
    /** 启动图淡出时长 */
    private static final long SPLASH_FADE_MS = 350L;

    /**
     * 还原地址。线性同余推进密钥流，逐字符异或解密。
     * 这样明文 URL 不会以常量形式出现在 dex 中，无法用 strings 直接搜到。
     */
    private static String restore(int[] data, int seed) {
        StringBuilder sb = new StringBuilder(data.length);
        int k = seed;
        for (int i = 0; i < data.length; i++) {
            k = k * 1103515245 + 12345;          // 溢出即自然取模 2^32
            sb.append((char) (data[i] ^ ((k >>> 16) & 0xFF)));
        }
        return sb.toString();
    }

    private WebView webView;
    private ProgressBar progressBar;
    /** 盖在网页上的启动图，网页就绪后淡出 */
    private View splashView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 启动图是否已开始退场（防止重复触发淡出动画） */
    private boolean splashDismissed;
    /** 主页地址（运行时还原后缓存，用于判断同域下的页面，如个人主页 Profile） */
    private String homeUrl;
    /** 商城地址（运行时还原后缓存，用于标题与入口跳转） */
    private String mallUrl;
    /** 商家地址（运行时还原后缓存，用于标题与入口跳转） */
    private String merchantUrl;

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

        homeUrl = restore(HOME_ENC, HOME_SEED);
        mallUrl = restore(MALL_ENC, MALL_SEED);
        merchantUrl = restore(MERCHANT_ENC, MERCHANT_SEED);

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
            dismissSplash();
        } else {
            webView.loadUrl(homeUrl);
        }
        setTitle(getString(R.string.app_name));
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
                // 页面加载完成后：同步标题、返回键，刷新菜单入口，并撤掉启动图
                syncTitle(url);
                updateBackButton(url);
                invalidateOptionsMenu();
                dismissSplash();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // 单页应用内部跳转（如 pushState 改 URL）不会触发 onPageFinished，
                // 这里补一次刷新，保证商城入口离开主页后能及时隐藏
                invalidateOptionsMenu();
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // 左上角返回键：退回上一页（商城/商家页退回主页或 Profile）
        if (item.getItemId() == android.R.id.home) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            }
            return true;
        }
        if (item.getItemId() == R.id.action_mall) {
            webView.loadUrl(mallUrl);
            setTitle(getString(R.string.mall));
            return true;
        }
        if (item.getItemId() == R.id.action_merchant) {
            webView.loadUrl(merchantUrl);
            setTitle(getString(R.string.merchant));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 菜单每次要显示前调用：商城、商家入口只在个人主页（Profile）可见，其余页面一律隐藏 */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem mall = menu.findItem(R.id.action_mall);
        MenuItem merchant = menu.findItem(R.id.action_merchant);
        String url = webView != null ? webView.getUrl() : null;
        boolean show = isProfilePage(url);
        if (mall != null) {
            mall.setVisible(show);
        }
        if (merchant != null) {
            merchant.setVisible(show);
        }
        return true;
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

    /** 在商城页或商家页显示左上角返回键，方便退回主页/Profile；其它页面隐藏 */
    private void updateBackButton(String url) {
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(isMallPage(url) || isMerchantPage(url));
        }
    }

    /**
     * 根据当前页面同步标题栏文字：
     * 商城页显示“Shop”、商家页显示“Merchant”，其余页面恢复显示 App 名。
     */
    private void syncTitle(String url) {
        if (isMallPage(url)) {
            setTitle(getString(R.string.mall));
        } else if (isMerchantPage(url)) {
            setTitle(getString(R.string.merchant));
        } else {
            setTitle(getString(R.string.app_name));
        }
    }

    /** 判断当前 URL 是否停留在商城页面（商城地址本身或仅带 ?/# 参数） */
    private boolean isMallPage(String url) {
        return isTargetPage(url, mallUrl);
    }

    /** 判断当前 URL 是否停留在商家页面（商家地址本身或仅带 ?/# 参数） */
    private boolean isMerchantPage(String url) {
        return isTargetPage(url, merchantUrl);
    }

    /** 判断 URL 是否属于指定入口地址本身（允许末尾 / 或 ?/# 参数） */
    private boolean isTargetPage(String url, String target) {
        if (url == null || target == null) {
            return false;
        }
        String base = target.endsWith("/") ? target.substring(0, target.length() - 1) : target;
        if (!url.startsWith(base)) {
            return false;
        }
        String rest = url.substring(base.length());
        return rest.isEmpty() || rest.equals("/")
                || rest.startsWith("/?") || rest.startsWith("/#");
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
        splashView = null;
        super.onDestroy();
    }
}
