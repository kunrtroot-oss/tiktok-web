import UIKit

/// 入口图标（店铺中心 / 商品橱窗 / 订单详情）。
///
/// 造型直接用参考包的原图 `entry_icon_*.png`（88×88，浅灰圆角方框 + 黑色线稿），
/// 按下态换成参考包配套的 `*_pressed`（方框转黑）。这些图与安卓
/// `app/res/drawable-nodpi/` 下的同名资源是同一份文件，两端看到的造型完全一致。
///
/// 为什么不再用代码画：参考包的方框是「浅灰框 + 透明内部 + 黑色线稿」的双色图，
/// 用 tintColor 只能整体染一个颜色，怎么调都做不出那个效果。
///
/// 注意：图标自带颜色，使用时<b>不要</b>再设 tintColor，否则会把方框和线稿一起染掉。
enum EntranceIcon {

    case shopCenter
    case showcase
    case orders

    /// 常态图片名（与安卓 drawable 名对应）
    private var normalName: String {
        switch self {
        case .shopCenter: return "entry_icon_shop"
        case .showcase: return "entry_icon_window"
        case .orders: return "entry_icon_order"
        }
    }

    /// 按下态图片名：参考包配套的「方框转黑」版本
    private var pressedName: String { normalName + "_pressed" }

    /// 取图标；`pressed` 为真时返回按下态图片
    func image(pressed: Bool = false) -> UIImage? {
        return UIImage(named: pressed ? pressedName : normalName)
    }
}
