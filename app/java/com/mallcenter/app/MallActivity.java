package com.mallcenter.app;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.MenuItem;
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

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 店铺/橱窗/订单/商家 这些入口点开后进入的独立网页页（相当于参考包里的 LwbActivity）。
 *
 * <p>为什么不直接在主页的 WebView 里跳过去？因为入口条只在个人主页出现，
 * 而跳转到商城后用户还能用系统返回键退回个人主页，两段浏览历史互不干扰，体验和参考包一致。
 *
 * <p>给网页传参的方式也和参考包对齐：把一份 JSON（路线、语言、时区、平台、版本等）
 * 用 URL 安全的 Base64 编码后拼在地址的 {@code data=} 参数上，网页端解析后就知道该渲染哪个业务页
 * 以及当前客户端环境。返回给网页的 JS 桥名字固定为 {@code android}，
 * 网页里用 {@code window.android.closeWindow()} 之类的写法就能调到原生方法。
 *
 * <p><b>已知缺口：</b>参考包能给出登录用户的 customID / 昵称 / 头像，是因为它本身就是 TikTok 客户端；
 * 我们的账号体系在 TikTok 官方网页里，原生侧拿不到这些字段，所以这里统一回传空串。
 * 网页端若要展示用户信息，需要自己走登录/授权流程，或后续由服务端通过 TikTok 开放接口补齐。
 */
public class MallActivity extends Activity {

    /** 目标地址（已含 route 参数） */
    static final String EXTRA_URL = "extra_url";
    /** 路线标识，随 data 一起传给网页 */
    static final String EXTRA_ROUTE = "extra_route";
    /** 标题栏文字（入口名称） */
    static final String EXTRA_TITLE = "extra_title";

    /** 传给网页的 Base64 采用 URL 安全字母表，避免 + / = 在地址栏里被转义 */
    private static final int BASE64_FLAGS = Base64.NO_WRAP | Base64.URL_SAFE;

    private WebView webView;
    private ProgressBar progressBar;
    private String route;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String url = getIntent() != null ? getIntent().getStringExtra(EXTRA_URL) : null;
        if (url == null || url.isEmpty()) {
            // 没有目标地址说明调用方传参不正确，直接退出，避免出现空白页
            finish();
            return;
        }
        route = getIntent().getStringExtra(EXTRA_ROUTE);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        setTitle(title != null ? title : getString(R.string.app_name));

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        int barHeight = (int) (3 * getResources().getDisplayMetrics().density);
        root.addView(progressBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight));

        setContentView(root);

        configureWebView();
        webView.loadUrl(withData(url));
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
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(buildChromeUserAgent());

        // 与主页共用一个 Cookie 容器，网页端的登录态、语言偏好等在两页之间是连续的
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // 网页通过这个名字调用原生方法（window.android.xxx），名字与参考包保持一致
        webView.addJavascriptInterface(new JsBridge(), "android");

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
                updateBackButton();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                updateBackButton();
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
                request.grant(request.getResources());
            }
        });
    }

    /**
     * 组装最终地址：原有参数（如 route）保留，再追加 data= 一段 Base64 的 JSON。
     * 网页端只需解析 data 就能拿到路线与客户端环境，不必依赖地址栏里的其它参数。
     */
    private String withData(String url) {
        String payload = buildDataPayload();
        String encoded = Base64.encodeToString(
                payload.getBytes(StandardCharsets.UTF_8), BASE64_FLAGS);
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "data=" + encoded;
    }

    /** data 参数的内容：字段名与参考包对齐，网页端可以按同一套解析逻辑处理 */
    private String buildDataPayload() {
        JSONObject json = new JSONObject();
        try {
            json.put("route", route != null ? route : "");
            json.put("tkplatform", "android");
            json.put("versonDate", appVersion());
            json.put("lang", Locale.getDefault().getLanguage());
            json.put("timezone", TimeZone.getDefault().getID());
            // 下面四项需要登录用户信息，原生侧暂无法获取，先给空串占位，保证网页端字段齐全
            json.put("customID", "");
            json.put("nickname", "");
            json.put("avatar", "");
            json.put("tiktok_id", "");
        } catch (Exception ignored) {
            // JSONObject 放基本类型不会失败，这里兜底避免极端情况下抛异常中断加载
        }
        return json.toString();
    }

    /** 取本机安装的版本号，网页端用它判断客户端是否过旧 */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** 与主页 WebView 用同一套 UA 伪装，避免同一站点对两个页面给出不同版本 */
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

    /** 非 http(s) 协议交给系统处理（如 tel:、tg:），返回 true 表示已接管 */
    private boolean handleUrl(String url) {
        if (url == null || url.startsWith("http://") || url.startsWith("https://")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            // 系统里没有能处理该协议的应用时静默忽略
        }
        return true;
    }

    /** 页内还有历史就显示返回键，能退回上一页；已经在第一页就隐藏 */
    private void updateBackButton() {
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(webView != null && webView.canGoBack());
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /**
     * 提供给网页调用的原生方法集合（JS 桥名固定为 android）。
     * 方法上必须带 @JavascriptInterface 注解，否则网页调用不到。
     */
    private class JsBridge {

        /** 网页里点「关闭」时调用，直接关掉本页回到个人主页 */
        @JavascriptInterface
        public void closeWindow() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }

        /** 网页启动时取一次客户端信息；字段与 data 参数保持一致 */
        @JavascriptInterface
        public String tiktokusrinfo() {
            return buildDataPayload();
        }

        /**
         * 客服入口。参考包里它会跳到站内客服页；我们暂时没有对应地址，
         * 先保底实现为空方法，保证网页调用时不报错，后续有地址再补跳转。
         */
        @JavascriptInterface
        public void goCustomerService() {
            // 预留：等客服地址确定后在此处跳转
        }
    }
}
