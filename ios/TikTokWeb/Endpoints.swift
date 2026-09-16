import Foundation

/// 全 App 外部地址的唯一出处（全部加密存放，运行时不落明文），
/// 与安卓版 `Endpoints.java` 一一对应，两个平台的密文 / 种子必须保持一致。
///
/// 集中放一处的原因：地址以后要调整，只改这个文件；入口条、独立页共用同一份，
/// 不会出现「某个页面还连着旧地址」的不一致问题。
///
/// 四个入口采用「一个站点 + route 参数」的方式区分，与参考包一致：
/// 网页端读到 route 就知道该渲染哪个页面，不需要为每个入口单独部署站点。
enum Endpoints {

    // MARK: - 密文与种子
    /// 首页：官方 TikTok 网页
    private static let HOME_ENC: [Int32] = [
        65, 108, 134, 246, 27, 92, 229, 90, 128, 197, 111, 130,
        167, 149, 17, 123, 237, 107, 15, 84, 176, 98, 171,
    ]
    private static let HOME_SEED = Int32(bitPattern: 0x6D2B79F5)

    /// 店铺中心
    private static let SHOP_CENTER_ENC: [Int32] = [
        194, 28, 67, 63, 105, 178, 254, 46, 185, 121, 70, 135,
        54, 231, 48, 174, 78, 202, 61, 243, 41, 113, 226, 133,
        207, 190, 4, 184, 201, 60, 1, 47, 182, 171, 88, 57,
        180, 254, 47, 124,
    ]
    private static let SHOP_CENTER_SEED = Int32(bitPattern: 0x27D4EB2F)

    /// 商品橱窗
    private static let SHOWCASE_ENC: [Int32] = [
        187, 120, 235, 138, 97, 15, 63, 26, 207, 35, 204, 119,
        158, 51, 165, 74, 108, 97, 239, 100, 28, 42, 73, 171,
        65, 92, 125, 41, 134, 143, 166, 105, 0, 22, 185, 170,
        181, 31, 104,
    ]
    private static let SHOWCASE_SEED = Int32(bitPattern: 0x85EBCA6B)

    /// 订单详情
    private static let ORDERS_ENC: [Int32] = [
        78, 15, 93, 156, 225, 144, 177, 136, 5, 15, 186, 125,
        188, 62, 159, 158, 252, 45, 175, 146, 136, 24, 155, 113,
        169, 176, 254, 230, 221, 71, 1, 37, 115, 14, 197, 156,
        187, 229, 7,
    ]
    private static let ORDERS_SEED = Int32(bitPattern: 0xC2B2AE35)

    /// 商家入驻（当前入口条只放三个入口，此地址保留备用，暂不展示）
    private static let MERCHANT_ENC: [Int32] = [
        27, 26, 149, 180, 60, 217, 116, 118, 183, 192, 160, 170,
        187, 106, 18, 206, 232, 103, 207, 235, 35, 85, 20, 167,
        140, 177, 152, 214, 138, 9, 113, 240, 224, 168, 132, 251,
        246, 2, 183, 58, 71, 201,
    ]
    private static let MERCHANT_SEED = Int32(bitPattern: 0x2545F491)

    // MARK: - 路线标识
    /// 网页端按这个值决定渲染哪个页面，改这里要同步通知网页端
    static let routeShopCenter = "shopCenter"
    static let routeShowcase = "goodsList"
    static let routeOrders = "orderList"
    static let routeMerchant = "merchantJoin"

    // MARK: - 对外取地址
    static func home() -> String { decode(HOME_ENC, HOME_SEED) }
    static func shopCenter() -> String { decode(SHOP_CENTER_ENC, SHOP_CENTER_SEED) }
    static func showcase() -> String { decode(SHOWCASE_ENC, SHOWCASE_SEED) }
    static func orders() -> String { decode(ORDERS_ENC, ORDERS_SEED) }
    static func merchant() -> String { decode(MERCHANT_ENC, MERCHANT_SEED) }

    /// 还原地址：线性同余推进密钥流，逐字符异或解密（与安卓 `Enc.decode` 算法完全一致）。
    /// 用 `&*` / `&+` 让溢出按 32 位回绕，模拟 Java 的 int 行为。
    private static func decode(_ data: [Int32], _ seed: Int32) -> String {
        var key = seed
        var scalars = String.UnicodeScalarView()
        for item in data {
            key = key &* 1103515245 &+ 12345
            let byte = UInt32(bitPattern: key) >> 16
            let value = UInt32(bitPattern: item ^ Int32(byte & 0xFF)) & 0xFF
            if let scalar = UnicodeScalar(value) {
                scalars.append(scalar)
            }
        }
        return String(scalars)
    }

    /// 纯工具类型，不允许实例化。
    /// 空枚举本身也构造不出来，这里用 fatalError 收尾是为了让编译器确认初始化路径已闭合。
    private init() {
        fatalError("Endpoints 只提供静态能力，不允许实例化")
    }
}
