import UIKit
import WebKit

/// 两个网页页（主页 / 入口独立页）共用的「外壳」能力。
///
/// 集中放一处的原因：UA 伪装、data 参数编码、外部协议跳转、进度条样式这些规则
/// 一旦两个页面写得不一样，同一个站点就会对两个页面给出不同版本（例如把商城页当成内置浏览器降级），
/// 排查起来非常麻烦。改这里两个页面一起生效。
enum WebShell {

    /// 顶部细进度条高度，与安卓版的 3dp 对应
    static let progressBarHeight: CGFloat = 3

    /// 进度条颜色：安卓主题 Theme.Material.Light 默认 colorAccent（青绿），保持一致
    static let progressTintColor = UIColor(red: 0x00 / 255.0, green: 0x96 / 255.0,
                                           blue: 0x88 / 255.0, alpha: 1)

    // MARK: - WebView 组装

    /// 共用的 WebView 配置：开放 JS / 本地存储 / 视频自动播放，与安卓设置对齐
    static func makeConfiguration() -> WKWebViewConfiguration {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.allowsInlineMediaPlayback = true
        // 短视频需要自动播放，不强制用户手势（对应安卓 setMediaPlaybackRequiresUserGesture(false)）
        config.mediaTypesRequiringUserActionForPlayback = []
        return config
    }

    /// 给已建好的 WebView 套上共用外壳设置
    static func applyShell(to webView: WKWebView,
                           navigationDelegate: WKNavigationDelegate?,
                           uiDelegate: WKUIDelegate?) {
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.navigationDelegate = navigationDelegate
        webView.uiDelegate = uiDelegate
        // 伪装成主流 Chrome 手机浏览器，降低被站点判定为内置浏览器而降级的概率
        webView.customUserAgent = chromeUserAgent()
        // 侧滑返回 = 安卓硬件返回键的 iOS 对应能力
        webView.allowsBackForwardNavigationGestures = true
        // 页面顶部由约束决定（标题栏下方），不让系统再叠加一次内边距，避免内容整体下移
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.backgroundColor = .white
        webView.isOpaque = true
    }

    /// 直接造一个套好外壳的 WebView
    static func makeWebView(navigationDelegate: WKNavigationDelegate?,
                            uiDelegate: WKUIDelegate?) -> WKWebView {
        let webView = WKWebView(frame: .zero, configuration: makeConfiguration())
        applyShell(to: webView, navigationDelegate: navigationDelegate, uiDelegate: uiDelegate)
        return webView
    }

    /// 顶部 3pt 细进度条
    static func makeProgressBar() -> UIProgressView {
        let bar = UIProgressView(progressViewStyle: .bar)
        bar.translatesAutoresizingMaskIntoConstraints = false
        bar.progressTintColor = progressTintColor
        bar.trackTintColor = .clear
        return bar
    }

    /// 监听加载进度：走完自动隐藏（对应安卓 onProgressChanged 里 newProgress >= 100 隐藏）
    static func observeProgress(of webView: WKWebView,
                                into bar: UIProgressView) -> NSKeyValueObservation {
        return webView.observe(\.estimatedProgress, options: [.new]) { webView, _ in
            bar.progress = Float(webView.estimatedProgress)
            bar.isHidden = webView.estimatedProgress >= 1.0
        }
    }

    /// 用系统默认 UA 里的平台信息拼出标准 Chrome 手机端 UA（与安卓 buildChromeUserAgent 策略一致）
    static func chromeUserAgent() -> String {
        return "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    // MARK: - 与网页约定的数据格式

    /// 与安卓 Base64.NO_WRAP | Base64.URL_SAFE 等价：URL 安全字母表，保留 '=' 补位。
    /// 用 URL 安全字母表是为了避免 + / = 在地址里被二次转义导致网页端解不开。
    static func base64Url(_ text: String) -> String {
        return Data(text.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
    }

    /// 把字符串转成 JS 字符串字面量：注入的 JSON 自带引号，直接拼接会把脚本拼坏
    static func jsString(_ raw: String) -> String {
        var out = "\""
        for scalar in raw.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out + "\""
    }

    /// 版本号（与安卓 versionName 对应），网页端用它判断客户端是否过旧
    static func appVersion() -> String {
        return (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? ""
    }

    // MARK: - 链接处理

    /// http(s) 一律留在 App 内；其它协议（tg://、tel:、intent: 等）交给系统处理。
    ///
    /// - Returns: true 表示已由 App 接管，WebView 不再加载该地址
    static func handleExternal(_ urlString: String?) -> Bool {
        guard let urlString = urlString else { return false }
        if urlString.hasPrefix("http://") || urlString.hasPrefix("https://") {
            return false
        }
        guard let url = URL(string: urlString) else { return true }
        // 直接 open（不用 canOpenURL，它需要预先声明协议白名单，会漏掉很多自定义协议）
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
        return true
    }

    private init() {
        // 纯工具类型，不允许实例化
    }
}
