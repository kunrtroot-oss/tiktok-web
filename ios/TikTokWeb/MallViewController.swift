import UIKit
import WebKit

/// 订单详情 / 商品橱窗 / 店铺中心 这些入口点开后进入的独立网页页
/// （与安卓版 `MallActivity.java` 一一对应，也就是参考包里的那个 LwbActivity）。
///
/// 为什么不直接在主页的 WebView 里跳过去？
/// 入口栏只在个人主页出现，跳去商城后用户还要能退回个人主页；分成两个页面后，
/// 商城页有自己的浏览历史，返回时直接回到主页原来的位置，体验和参考包一致。
///
/// 给网页传参的方式与参考包对齐：把一份 JSON（路线、平台、版本、语言、时区、用户信息）
/// 用 URL 安全 Base64 编码后拼在地址的 `data=` 参数上，网页端解析后就知道该渲染哪个业务页、
/// 当前客户端是什么环境。原生能力用 JS 桥暴露给网页，桥名固定 `android`（与安卓一致），
/// 网页里写 `window.android.closeWindow()` 这种代码两端都能跑，不用为 iOS 单独写一套。
///
/// **已知缺口：** 参考包能给出登录用户的 customID / 昵称 / 头像，是因为它本身就是 TikTok 客户端；
/// 我们的账号体系在 TikTok 官方网页里，原生侧拿不到这些字段，所以统一回传空串。
/// 网页端若要展示用户信息，需要自己走登录流程，或后续由服务端通过 TikTok 开放接口补齐。
final class MallViewController: UIViewController {

    /// JS 桥的名字：网页用 `window.android.xxx` 调用原生。名字与安卓完全一致，不用改前端代码
    private static let bridgeName = "android"

    private let entrance: Entrance

    private var webView: WKWebView!
    private let progressBar = WebShell.makeProgressBar()
    private var progressObservation: NSKeyValueObservation?
    private var backObservation: NSKeyValueObservation?

    private lazy var backButton = UIBarButtonItem(image: UIImage(systemName: "chevron.backward"),
                                                 style: .plain,
                                                 target: self,
                                                 action: #selector(tapBack))

    // MARK: - 初始化

    init(entrance: Entrance) {
        self.entrance = entrance
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        // 本页全部用代码创建视图，若从 Storyboard 进来说明接错了，直接报错暴露问题
        fatalError("MallViewController 仅支持代码创建")
    }

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        // 标题显示入口名称（店铺中心 / 商品橱窗 ...），与安卓 ActionBar 标题一致
        title = entrance.title
        view.backgroundColor = .white

        setupWebView()
        buildLayout()
        observeWebView()
        loadTarget()
    }

    deinit {
        // webView 的观察者不会自动失效，销毁前清掉，避免回调打到已释放的控制器上
        progressObservation?.invalidate()
        backObservation?.invalidate()
        webView?.stopLoading()
        webView?.navigationDelegate = nil
        webView?.uiDelegate = nil
    }

    // MARK: - 组装

    private func setupWebView() {
        // 与主页共用同一个 Cookie 容器（WKWebView 默认都用 WKWebsiteDataStore.default()），
        // 所以网页端的登录态、语言偏好在两个页面之间是连续的，不用额外同步
        let webView = WebShell.makeWebView(navigationDelegate: self, uiDelegate: self)
        let controller = webView.configuration.userContentController

        // 先注入 window.android 桥对象，再让页面开始加载脚本，保证网页执行时桥一定已存在
        controller.addUserScript(WKUserScript(source: bridgeShimScript(),
                                              injectionTime: .atDocumentStart,
                                              forMainFrameOnly: false))
        // 用弱引用代理注册，避免「本页 → webView → 配置 → userContentController → 本页」形成强引用环
        controller.add(WeakScriptMessageHandler(delegate: self), name: Self.bridgeName)

        self.webView = webView
    }

    /// 叠两层：网页铺满 → 顶部 3pt 进度条（后加的在上面）
    private func buildLayout() {
        view.addSubview(webView)
        view.addSubview(progressBar)

        let safeTop = view.safeAreaLayoutGuide.topAnchor
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: safeTop),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            progressBar.topAnchor.constraint(equalTo: safeTop),
            progressBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            progressBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            progressBar.heightAnchor.constraint(equalToConstant: WebShell.progressBarHeight),
        ])
    }

    // MARK: - 加载

    /// 加载目标地址，并把客户端信息作为 data 参数拼上去
    private func loadTarget() {
        guard let url = URL(string: withData(entrance.url)) else { return }
        webView.load(URLRequest(url: url))
    }

    /// 组装最终地址：原有参数（如 route）保留，再追加 data= 一段 Base64 的 JSON。
    /// 分隔符规则与安卓一致：地址里已经有 ? 就用 &，否则用 ?
    private func withData(_ url: String) -> String {
        let separator = url.contains("?") ? "&" : "?"
        return url + separator + "data=" + WebShell.base64Url(buildDataPayload())
    }

    /// data 参数的内容。字段名、顺序都与安卓 `buildDataPayload` 完全一致，
    /// 这样网页端一套解析逻辑两端通用，联调时也能直接对比两端的原始串。
    private func buildDataPayload() -> String {
        // 平台标记是两端唯一的差异点，网页端据此做平台差异化（如唤起 App、客服地址）
        let fields: [(String, String)] = [
            ("route", entrance.route),
            ("tkplatform", "ios"),
            ("versonDate", WebShell.appVersion()),
            ("lang", currentLanguageCode()),
            ("timezone", TimeZone.current.identifier),
            // 下面四项需要登录用户信息，原生侧暂无法获取，先给空串占位，保证网页端字段齐全
            ("customID", ""),
            ("nickname", ""),
            ("avatar", ""),
            ("tiktok_id", ""),
        ]
        // 用 WebShell.jsString 做值转义：它转义 " \ 换行 制表 控制符，产生的字面量正好也是合法 JSON 字符串
        let body = fields
            .map { "\(WebShell.jsString($0.0)):\(WebShell.jsString($0.1))" }
            .joined(separator: ",")
        return "{" + body + "}"
    }

    /// 语言码（如 "zh"、"en"），对应安卓 `Locale.getDefault().getLanguage()`
    private func currentLanguageCode() -> String {
        if #available(iOS 16.0, *) {
            return Locale.current.language.languageCode?.identifier ?? ""
        } else {
            // iOS 16 起 languageCode 被弃用，这里已被上面的分支排除，不会产生弃用告警
            return Locale.current.languageCode ?? ""
        }
    }

    /// 注入到网页里的桥对象：把 `window.android.xxx` 的调用转成原生消息。
    ///
    /// 为什么不能像安卓那样直接挂一个原生对象？iOS 的 WKWebView 没有 addJavascriptInterface 这种能力，
    /// 必须自己用脚本造一个同名对象，再把调用转成消息发给原生，网页端代码才能不改动地两端通用。
    private func bridgeShimScript() -> String {
        // tiktokusrinfo 在安卓上是同步返回字符串的，iOS 消息机制只有异步，
        // 所以这里把 payload 直接内联进脚本，让它同步返回同一个值，两端行为一致
        let payloadLiteral = WebShell.jsString(buildDataPayload())
        return """
        (function() {
            if (window.android) { return; }
            var payload = \(payloadLiteral);
            window.android = {
                closeWindow: function() {
                    window.webkit.messageHandlers.\(Self.bridgeName).postMessage("closeWindow");
                },
                goCustomerService: function() {
                    window.webkit.messageHandlers.\(Self.bridgeName).postMessage("goCustomerService");
                },
                tiktokusrinfo: function() {
                    return payload;
                }
            };
        })();
        """
    }

    // MARK: - 观察

    private func observeWebView() {
        progressObservation = WebShell.observeProgress(of: webView, into: progressBar)

        // 网页内有历史时让返回键先退网页，回到第一页才退回个人主页
        backObservation = webView.observe(\.canGoBack, options: [.new]) { [weak self] webView, _ in
            guard let self = self else { return }
            // 返回箭头常驻（iOS 没硬件返回键，隐藏了就退不回个人主页），
            // 只用无障碍描述区分「退网页」还是「退回个人主页」
            self.backButton.accessibilityLabel = webView.canGoBack ? "返回上一页" : "返回个人主页"
            self.navigationItem.leftBarButtonItem = self.backButton
        }
        navigationItem.leftBarButtonItem = backButton
    }

    // MARK: - 交互

    /// 安卓那边返回键的处理顺序是「能退网页就退网页，否则关掉本页」，这里保持一致。
    /// 注意：iOS 没有硬件返回键，而且如果照抄安卓的「不能退网页就隐藏返回键」，
    /// 用户会退不回个人主页，所以返回箭头常驻，只是行为随页内历史变化。
    @objc private func tapBack() {
        if webView.canGoBack {
            webView.goBack()
        } else {
            navigationController?.popViewController(animated: true)
        }
    }
}

// MARK: - WKScriptMessageHandler（JS 桥）

extension MallViewController: WKScriptMessageHandler {

    func userContentController(_ userContentController: WKUserContentController,
                              didReceive message: WKScriptMessage) {
        guard message.name == Self.bridgeName else { return }
        // 网页可能传字符串，也可能传对象（{method: "closeWindow"}），两种都兼容，避免前端写法稍有不同就调不通
        let method: String
        if let text = message.body as? String {
            method = text
        } else if let dict = message.body as? [String: Any] {
            method = (dict["method"] as? String)
                ?? (dict["action"] as? String)
                ?? (dict["func"] as? String)
                ?? ""
        } else {
            method = ""
        }

        switch method {
        case "closeWindow":
            closeWindow()
        case "tiktokusrinfo":
            // 页面通常同步取过一次（见注入脚本），这里只是兜底：主动把信息推给网页
            pushUserInfoToPage()
        case "goCustomerService":
            // 与安卓一致：暂时没有确定的客服地址，保底实现为空，保证网页调用不报错
            break
        default:
            break
        }
    }

    /// 关闭本页回到个人主页（对应安卓 finish()）
    private func closeWindow() {
        if let navigationController = navigationController, navigationController.viewControllers.count > 1 {
            navigationController.popViewController(animated: true)
        } else {
            dismiss(animated: true, completion: nil)
        }
    }

    /// 把客户端信息回推给网页（对应安卓 tiktokusrinfo 的异步兜底）
    private func pushUserInfoToPage() {
        let script = "window.onTikTokUserInfo && window.onTikTokUserInfo(\(WebShell.jsString(buildDataPayload())));"
        webView.evaluateJavaScript(script, completionHandler: nil)
    }
}

// MARK: - WKNavigationDelegate

extension MallViewController: WKNavigationDelegate {

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if WebShell.handleExternal(navigationAction.request.url?.absoluteString) {
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }
}

// MARK: - WKUIDelegate

extension MallViewController: WKUIDelegate {

    /// 网页申请摄像头/麦克风时直接放行（对应安卓 onPermissionRequest 里 request.grant）
    @available(iOS 15.0, *)
    func webView(_ webView: WKWebView,
                 requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                 initiatedByFrame frame: WKFrameInfo,
                 type: WKMediaCaptureType,
                 decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        decisionHandler(.grant)
    }
}

// MARK: - 弱引用消息代理

/// WKUserContentController 会强引用注册进来的 handler。
/// 如果直接用控制器自身注册，就会形成「控制器 → webView → 配置 → userContentController → 控制器」的引用环，
/// 这个页面永远释放不掉（内存泄漏）。套一层弱引用代理即可断开环。
private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {

    private weak var delegate: WKScriptMessageHandler?

    init(delegate: WKScriptMessageHandler) {
        self.delegate = delegate
    }

    func userContentController(_ userContentController: WKUserContentController,
                              didReceive message: WKScriptMessage) {
        delegate?.userContentController(userContentController, didReceive: message)
    }
}
