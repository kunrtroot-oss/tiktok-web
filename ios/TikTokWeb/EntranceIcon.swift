import UIKit

/// 入口图标的画法（订单详情 / 商品橱窗 / 店铺中心）。
///
/// 路径坐标与安卓版 `app/res/drawable/ic_entry_*.xml` 里的 vector 是同一套 24×24 画布，
/// 运行时按需要的尺寸画成 templated 图片：颜色由 tintColor 决定，等于安卓那边
/// 「常态深色 → 按下变品牌红」的 selector，两端看到的造型完全一致。
///
/// 为什么不用 SF Symbols：造型与参考包对不上，且部分符号要 iOS 17 才有。
/// 为什么不用 PNG：一张图要备 1x/2x/3x 三份，以后改线条还得重新导图。
enum EntranceIcon {

    case shopCenter
    case showcase
    case orders
    case merchant

    /// 与安卓 vector 的 viewport 对齐，路径坐标可以直接照搬
    private static let canvas: CGFloat = 24
    /// 线宽，与安卓一致
    private static let lineWidth: CGFloat = 1.7

    /// 生成图标（默认 26pt，与安卓入口条的图标尺寸一致），可直接用 tintColor 变色
    func image(size: CGFloat = 26) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))
        let drawn = renderer.image { context in
            // 把画布缩放到 24 单位坐标系，后面路径里的数字就能照抄 vector 的定义
            context.cgContext.scaleBy(x: size / Self.canvas, y: size / Self.canvas)
            UIColor.black.setStroke()
            for path in paths {
                path.lineWidth = Self.lineWidth
                path.lineCapStyle = .round
                path.lineJoinStyle = .round
                path.stroke()
            }
        }
        // 转成 templated：忽略图片自身颜色，只取形状，颜色交给 tintColor
        return drawn.withRenderingMode(.alwaysTemplate)
    }

    /// 本图标包含的所有线条
    private var paths: [UIBezierPath] {
        switch self {
        case .shopCenter:
            // 雨棚 + 两面墙 + 门
            return [
                polyline([(3.2, 10.2), (4.8, 4.6), (19.2, 4.6), (20.8, 10.2)], closed: true),
                polyline([(5.6, 10.2), (5.6, 19.4), (18.4, 19.4), (18.4, 10.2)]),
                polyline([(10.2, 19.4), (10.2, 14.4), (13.8, 14.4), (13.8, 19.4)]),
            ]
        case .showcase:
            // 箱体轮廓 + 上盖折线 + 正面封条
            return [
                polyline([(12, 3.4), (20.4, 7.7), (20.4, 16.3),
                          (12, 20.6), (3.6, 16.3), (3.6, 7.7)], closed: true),
                polyline([(3.6, 7.7), (12, 12), (20.4, 7.7)]),
                polyline([(12, 12), (12, 20.6)]),
            ]
        case .orders:
            // 单据板 + 顶部夹子 + 两行文字
            return [
                polyline([(8.2, 5.4), (5.4, 5.4), (5.4, 20.6),
                          (18.6, 20.6), (18.6, 5.4), (15.8, 5.4)]),
                polyline([(9.4, 3.4), (14.6, 3.4), (14.6, 7.2), (9.4, 7.2)], closed: true),
                polyline([(8.6, 11.8), (15.4, 11.8)]),
                polyline([(8.6, 15.6), (12.6, 15.6)]),
            ]
        case .merchant:
            // 盾牌（认证/资质）+ 加号（入驻）
            return [
                shield(),
                polyline([(12, 9.6), (12, 15.4)]),
                polyline([(9.1, 12.5), (14.9, 12.5)]),
            ]
        }
    }

    /// 按 24×24 坐标把一串点连成折线；closed 为真时首尾相接
    private func polyline(_ points: [(CGFloat, CGFloat)], closed: Bool = false) -> UIBezierPath {
        let path = UIBezierPath()
        for (index, item) in points.enumerated() {
            let point = CGPoint(x: item.0, y: item.1)
            if index == 0 {
                path.move(to: point)
            } else {
                path.addLine(to: point)
            }
        }
        if closed {
            path.close()
        }
        return path
    }

    /// 盾牌外形：两腰是直线，底部两段是曲线，这里用贝塞尔曲线还原 vector 里的 C 指令
    private func shield() -> UIBezierPath {
        let path = UIBezierPath()
        path.move(to: CGPoint(x: 12, y: 3.2))
        path.addLine(to: CGPoint(x: 19.2, y: 6.4))
        path.addLine(to: CGPoint(x: 19.2, y: 12.4))
        path.addCurve(to: CGPoint(x: 12, y: 20.8),
                      controlPoint1: CGPoint(x: 19.2, y: 16.6),
                      controlPoint2: CGPoint(x: 16.2, y: 19.6))
        path.addCurve(to: CGPoint(x: 4.8, y: 12.4),
                      controlPoint1: CGPoint(x: 7.8, y: 19.6),
                      controlPoint2: CGPoint(x: 4.8, y: 16.6))
        path.addLine(to: CGPoint(x: 4.8, y: 6.4))
        path.close()
        return path
    }
}
