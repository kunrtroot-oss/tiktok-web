import Foundation

/// 个人主页入口条上的一个入口：图标 + 名称 + 目标地址 + 路线标识。
///
/// 与安卓版 `Entrance.java` 一一对应：入口的增减、改名、换地址都在 `all()` 里一处完成，
/// 界面代码不需要改动，两端也不会出现「某个入口只在一边存在」的不一致。
struct Entrance {

    /// 图标造型（对应一组 entry_icon_*.png 参考包原图）
    let icon: EntranceIcon
    /// 名称文案，需与安卓 `app/res/values/strings.xml` 里的 entry_* 保持一致
    let title: String
    /// 目标地址（已含 route 参数，用户信息由 MallViewController 再追加）
    let url: String
    /// 路线标识，随地址与用户信息一起传给网页
    let route: String

    /// 个人主页上的三个入口，顺序与参考包截图一致：店铺中心 / 商品橱窗 / 订单详情。
    ///
    /// 与安卓 `Entrance.all()` 保持逐项一致（名称、顺序、地址、route 都不能只在一边改）。
    static func all() -> [Entrance] {
        return [
            Entrance(icon: .shopCenter, title: "店铺中心",
                     url: Endpoints.shopCenter(), route: Endpoints.routeShopCenter),
            Entrance(icon: .showcase, title: "商品橱窗",
                     url: Endpoints.showcase(), route: Endpoints.routeShowcase),
            Entrance(icon: .orders, title: "订单详情",
                     url: Endpoints.orders(), route: Endpoints.routeOrders),
        ]
    }
}
