import UIKit
import WebKit

/// 主页面：把官方 TikTok 网页装进 App 外壳（与安卓版 MainActivity 一一对应）。
///
/// 与参考包的关系：参考包是往「个人主页的原生视图树」里插一条横向入口栏；
/// 我们的个人主页就是 TikTok 官方网页，改不了它的 DOM，
/// 所以改成在网页上方叠一条原生入口栏，**只在个人主页显示**，显示时把网页整体下压（不遮挡页面内容），
/// 用户看到的效果与参考包一致：一进个人主页就出现一排入口，离开就消失。
///
/// 四个入口点开后进入独立网页页（MallViewController），不污染主页的浏览历史，
/// 与参考包「个人主页 → 独立商城页 → 返回个人主页」的结构一致。
final class ViewController: UIViewController {

    // MARK: - 常量（数值与安卓版保持一致）
    /// 启动图最长停留时间：网络再差也要让用户进得去
    private static let splashTimeout: TimeInterval = 8
    /// 启动图淡出时长
    private static let splashFade: TimeInterval = 0.35

    // MARK: - 视图
    private var webView: WKWebView!
    private let progressBar = WebShell.makeProgressBar()
    private let entranceBar = EntranceBarView()
    private var splashView: UIView?

    private lazy var backButton = UIBarButtonItem(image: UIImage(systemName: "chevron.backward"),
                                                 style: .plain,
                                                 target: self,
                                                 action: #selector(tapBack))

    // MARK: - 状态
    /// 主页地址（运行时还原后缓存，用于判断是否停留在个人主页）
    private let homeUrl = Endpoints.home()
    private var splashDismissed = false
    private var splashTimer: Timer?
    private var urlObservation: NSKeyValueObservation?
    private var progressObservation: NSKeyValueObservation?
    private var backObservation: NSKeyValueObservation?
    /// 网页顶边距约束：入口栏出现时把网页整体下压 74pt
    private var contentTopConstraint: NSLayoutConstraint!
    /// 进度条顶边距约束：与网页同步下压，始终贴在网页上方
    private var progressTopConstraint: NSLayoutConstraint!

    // MARK: - 生命周期
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .white
        navigationItem.title = Self.appDisplayName
        setupWebView()
        buildLayout()
        observeWebView()
        startSplashCountdown()
        loadHome()
    }

    deinit {
        // 8 秒兜底定时器可能还在排队，销毁时及时撤掉
        splashTimer?.invalidate()
    }

    // MARK: - 组装
    private func setupWebView() {
        webView = WebShell.makeWebView(navigationDelegate: self, uiDelegate: self)
    }

    private func loadHome() {
        guard let url = URL(string: homeUrl) else { return }
        webView.load(URLRequest(url: url))
    }

    /// 叠四层：网页 → 顶部进度条 → 入口栏 → 启动图（后加的在上面）
    private func buildLayout() {
        view.addSubview(webView)
        view.addSubview(progressBar)
        view.addSubview(entranceBar)
        entranceBar.isHidden = true
        entranceBar.onSelect = { [weak self] entrance in
            self?.openEntrance(entrance)
        }

        let safeTop = view.safeAreaLayoutGuide.topAnchor
        contentTopConstraint = webView.topAnchor.constraint(equalTo: safeTop)
        progressTopConstraint = progressBar.topAnchor.constraint(equalTo: safeTop)

        NSLayoutConstraint.activate([
            contentTopConstraint,
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            progressTopConstraint,
            progressBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            progressBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            progressBar.heightAnchor.constraint(equalToConstant: WebShell.progressBarHeight),

            entranceBar.topAnchor.constraint(equalTo: safeTop),
            entranceBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            entranceBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            entranceBar.heightAnchor.constraint(equalToConstant: EntranceBarView.barHeight),
        ])

        addSplashOverlay()
    }

    /// 启动图：与系统启动页用同一张背景 + 同一个 logo，从冷启动画面切过来看不出跳变
    private func addSplashOverlay() {
        let splash = UIView()
        splash.backgroundColor = UIColor(named: "LaunchBackground") ?? .black
        splash.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(splash)

        // 不给尺寸约束，用图片自身尺寸居中，保证与系统启动页的 logo 大小完全一致
        let logo = UIImageView(image: UIImage(named: "LaunchLogo"))
        logo.contentMode = .center
        logo.translatesAutoresizingMaskIntoConstraints = false
        splash.addSubview(logo)

        NSLayoutConstraint.activate([
            // 与安卓一致：启动图铺在内容区（标题栏下方），不盖住标题栏
            splash.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            splash.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            splash.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            splash.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            logo.centerXAnchor.constraint(equalTo: splash.centerXAnchor),
            logo.centerYAnchor.constraint(equalTo: splash.centerYAnchor),
        ])
        splashView = splash
    }

    // MARK: - 观察
    private func observeWebView() {
        progressObservation = WebShell.observeProgress(of: webView, into: progressBar)

        // 重点：监听 url 变化对应安卓的 doUpdateVisitedHistory。
        // 单页应用内部跳转（如 TikTok 用 pushState 换地址）不会触发「加载完成」回调，
        // 不监听这里就会出现「已经进了个人主页但入口栏没出来」的漏刷。
        urlObservation = webView.observe(\.url, options: [.new]) { [weak self] webView, _ in
            self?.refreshEntranceBar(webView.url?.absoluteString)
        }

        // 网页内有历史时给一个返回箭头：安卓用硬件返回键，iOS 没有，补一个看得见的入口
        backObservation = webView.observe(\.canGoBack, options: [.new]) { [weak self] webView, _ in
            self?.navigationItem.leftBarButtonItem = webView.canGoBack ? self?.backButton : nil
        }
    }

    private func startSplashCountdown() {
        splashTimer = Timer.scheduledTimer(withTimeInterval: Self.splashTimeout, repeats: false) { [weak self] _ in
            self?.dismissSplash()
        }
    }

    // MARK: - 启动图
    /// 让启动图淡出并移除。加载完成、加载失败、超时兜底三处都可能触发，只生效一次
    private func dismissSplash() {
        guard !splashDismissed, let splash = splashView else { return }
        splashDismissed = true
        splashTimer?.invalidate()
        splashTimer = nil
        UIView.animate(withDuration: Self.splashFade, animations: {
            splash.alpha = 0
        }, completion: { [weak self] _ in
            // 动画结束后彻底摘掉，避免它长期占着一层绘制开销
            splash.removeFromSuperview()
            self?.splashView = nil
        })
    }

    // MARK: - 入口栏
    /// 入口栏只在个人主页出现，其余页面隐藏；显示时把网页整体下压，不遮挡页面顶部内容
    private func refreshEntranceBar(_ urlString: String?) {
        let show = isProfilePage(urlString)
        if entranceBar.isHidden == show {
            entranceBar.isHidden = !show
        }
        applyContentTopInset(show ? EntranceBarView.barHeight : 0)
    }

    /// 调整网页与进度条的上边距：入口栏占位时整体下移，
    /// 相当于把入口「插进页面顶部」，与参考包的效果一致（而不是盖住内容）
    private func applyContentTopInset(_ inset: CGFloat) {
        guard contentTopConstraint.constant != inset else { return }
        contentTopConstraint.constant = inset
        progressTopConstraint.constant = inset
    }

    /// 打开入口对应的独立网页页，把地址、路线、入口名一并带过去
    private func openEntrance(_ entrance: Entrance) {
        guard let navigationController = navigationController else { return }
        navigationController.pushViewController(MallViewController(entrance: entrance), animated: true)
    }

    // MARK: - URL 判断
    /// 判断当前地址是否停留在个人主页（Profile）。与安卓 isProfilePage 规则完全一致：
    ///  1) 自己的主页 /profile（可能带 ?/# 参数）；
    ///  2) 别人的主页 /@用户名（用户名后不再有 /video/ 等子路径）。
    private func isProfilePage(_ urlString: String?) -> Bool {
        guard let urlString = urlString else { return false }
        let base = homeUrl.hasSuffix("/") ? String(homeUrl.dropLast()) : homeUrl
        guard urlString.hasPrefix(base) else { return false }
        let rest = String(urlString.dropFirst(base.count))

        if rest == "/profile" || rest.hasPrefix("/profile?") || rest.hasPrefix("/profile#") {
            return true
        }
        guard rest.hasPrefix("/@") else { return false }
        let afterUser = String(rest.dropFirst(2))   // 去掉 "/@"，剩下用户名及可能的参数
        guard !afterUser.isEmpty else { return false }
        // 用户名后若还有 / 子路径（如 /@user/video/123），就不是纯个人主页
        return !afterUser.contains("/")
    }

    // MARK: - 交互
    @objc private func tapBack() {
        if webView.canGoBack {
            webView.goBack()
        }
    }

    private static var appDisplayName: String {
        return (Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String) ?? "TikTok"
    }
}

// MARK: - WKNavigationDelegate
extension ViewController: WKNavigationDelegate {

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        // 页面加载完成：刷新入口栏可见性，并撤掉启动图
        refreshEntranceBar(webView.url?.absoluteString)
        dismissSplash()
    }

    /// 加载失败也要撤掉启动图，否则用户会被卡在一张静态图上
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        dismissSplash()
    }

    func webView(_ webView: WKWebView,
                 didFailProvisionalNavigation navigation: WKNavigation!,
                 withError error: Error) {
        dismissSplash()
    }

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
extension ViewController: WKUIDelegate {

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
