import UIKit

/// 个人主页顶部的一排入口（店铺中心 / 商品橱窗 / 订单详情）。
///
/// 参考包的做法是往「个人主页的原生视图树」里插一条入口栏；我们的个人主页是 TikTok 官方网页，
/// 改不了它的 DOM，所以改成把这一栏放在网页上方、只在个人主页显示：
/// 用户看到的效果一致——进入个人主页就出现一条图标入口，离开就消失。
///
/// 单个入口的排布与参考包一致：圆角方框图标在左、文字在右，整组在入口内居中；
/// 图标是参考包原图，按下时换成「方框转黑」的版本，所以全程不设 tintColor。
///
/// 用 UIScrollView + UIStackView（对应安卓的 HorizontalScrollView），
/// 以后增加入口时可以横向滑动，不会把图标挤变形。
final class EntranceBarView: UIView {

    /// 入口条高度，与安卓版 36dp 对应
    static let barHeight: CGFloat = 36
    /// 图标与文字之间的间距，与安卓版 8dp 对应
    static let iconLabelGap: CGFloat = 8
    /// 底色：参考包截图实测是浅灰（约 #D5D5D3），与安卓 bg_entry_bar 一致
    static let barBackground = UIColor(red: 0xD5 / 255.0, green: 0xD5 / 255.0,
                                       blue: 0xD3 / 255.0, alpha: 1)
    /// 按下时文字的高亮色（品牌红），与安卓 selector 的 state_pressed 颜色一致
    static let highlightColor = UIColor(red: 0xFE / 255.0, green: 0x2C / 255.0,
                                        blue: 0x55 / 255.0, alpha: 1)
    /// 文字常态色，与安卓 selector 的默认颜色一致（图标颜色由图片负责，不在这里）
    static let normalColor = UIColor(red: 0x16 / 255.0, green: 0x18 / 255.0,
                                     blue: 0x23 / 255.0, alpha: 1)
    /// 单个入口的最小宽度：屏幕很窄时也不会挤成一团
    private static let minItemWidth: CGFloat = 84

    /// 点击某个入口时回调，由外层负责打开独立页面
    var onSelect: ((Entrance) -> Void)?

    private let scrollView = UIScrollView()
    private let rowStack = UIStackView()

    init() {
        super.init(frame: .zero)
        setup()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    // MARK: - 布局
    private func setup() {
        // 浅灰底 + 底部一条发丝线分割线：看起来像页面自带的一栏，而不是浮在网页上的补丁
        backgroundColor = Self.barBackground
        addBottomHairline()

        scrollView.showsHorizontalScrollIndicator = false
        scrollView.alwaysBounceHorizontal = false
        // 入口条紧贴安全区顶部，不要系统再额外算内边距
        scrollView.contentInsetAdjustmentBehavior = .never
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scrollView)

        rowStack.axis = .horizontal
        rowStack.distribution = .fillEqually
        rowStack.alignment = .fill
        rowStack.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(rowStack)

        // 内容宽度至少撑满整屏（入口少时均分整屏），入口多时自然变成横向可滑动
        let fillWidth = rowStack.widthAnchor.constraint(
            greaterThanOrEqualTo: scrollView.frameLayoutGuide.widthAnchor)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),

            rowStack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            rowStack.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            rowStack.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            rowStack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            rowStack.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor),
            fillWidth,
        ])

        for entrance in Entrance.all() {
            let item = EntranceItemView(entrance: entrance)
            item.addTarget(self, action: #selector(tapItem(_:)), for: .touchUpInside)
            item.widthAnchor.constraint(
                greaterThanOrEqualToConstant: Self.minItemWidth).isActive = true
            rowStack.addArrangedSubview(item)
        }
    }

    /// 底部 1 物理像素的浅灰线，与安卓的 1dp 分割线对应
    private func addBottomHairline() {
        let hairline = UIView()
        hairline.backgroundColor = UIColor(white: 0, alpha: 0x14 / 255.0)
        hairline.translatesAutoresizingMaskIntoConstraints = false
        addSubview(hairline)
        NSLayoutConstraint.activate([
            hairline.leadingAnchor.constraint(equalTo: leadingAnchor),
            hairline.trailingAnchor.constraint(equalTo: trailingAnchor),
            hairline.bottomAnchor.constraint(equalTo: bottomAnchor),
            hairline.heightAnchor.constraint(equalToConstant: 1 / UIScreen.main.scale),
        ])
    }

    @objc private func tapItem(_ sender: EntranceItemView) {
        onSelect?(sender.entrance)
    }
}

/// 单个入口 = 图标（左） + 文字（右），整体可点；按下时图标换成黑色方框版本、文字变红
private final class EntranceItemView: UIControl {

    /// 图标边长，与安卓入口条一致（约占栏高的一半，参考包实测比例）
    private static let iconSize: CGFloat = 18
    /// 文字字号，与安卓入口条一致
    private static let labelFontSize: CGFloat = 12

    let entrance: Entrance

    private let iconView = UIImageView()
    private let label = UILabel()

    init(entrance: Entrance) {
        self.entrance = entrance
        super.init(frame: .zero)
        setupIcon(entrance.icon)
        setupLabel(entrance.title)
        layoutContent()
        // 初始为常态（浅灰方框 + 深色文字）
        applyHighlight(false)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isHighlighted: Bool {
        didSet {
            applyHighlight(isHighlighted)
        }
    }

    private func setupIcon(_ icon: EntranceIcon) {
        iconView.contentMode = .scaleAspectFit
        iconView.isUserInteractionEnabled = false
        // 不设 tintColor：图标是「浅灰方框 + 黑色线稿」的双色图，染色会毁掉造型
        iconView.image = icon.image(pressed: false)
    }

    private func setupLabel(_ title: String) {
        label.text = title
        label.font = .systemFont(ofSize: Self.labelFontSize)
        label.textAlignment = .center
        label.numberOfLines = 1
        label.isUserInteractionEnabled = false
    }

    private func layoutContent() {
        // 与参考包一致：图标在左、文字在右，整组在入口内居中
        let row = UIStackView(arrangedSubviews: [iconView, label])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = EntranceBarView.iconLabelGap
        row.isUserInteractionEnabled = false

        addSubview(row)
        row.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            row.centerXAnchor.constraint(equalTo: centerXAnchor),
            row.centerYAnchor.constraint(equalTo: centerYAnchor),
            iconView.widthAnchor.constraint(equalToConstant: Self.iconSize),
            iconView.heightAnchor.constraint(equalToConstant: Self.iconSize),
        ])
    }

    private func applyHighlight(_ highlighted: Bool) {
        // 按下态 = 换图（方框转黑），不是染色
        iconView.image = entrance.icon.image(pressed: highlighted)
        label.textColor = highlighted ? EntranceBarView.highlightColor : EntranceBarView.normalColor
    }
}
