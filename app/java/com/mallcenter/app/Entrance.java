package com.mallcenter.app;

/**
 * 个人主页入口条上的一个入口：图标 + 名称 + 目标地址 + 路线标识。
 *
 * 入口的增减、改名、换地址都在 {@link #all()} 里一处完成，界面代码不需要改动。
 */
final class Entrance {

    /** 图标资源（普通/按下两种状态已由 drawable 的 selector 处理） */
    final int iconRes;
    /** 名称文案资源 */
    final int labelRes;
    /** 目标地址（已含 route 参数，用户信息由 MallActivity 再追加） */
    final String url;
    /** 路线标识，随地址与用户信息一起传给网页 */
    final String route;

    Entrance(int iconRes, int labelRes, String url, String route) {
        this.iconRes = iconRes;
        this.labelRes = labelRes;
        this.url = url;
        this.route = route;
    }

    /**
     * 个人主页上的三个入口，顺序与参考包一致：店铺中心 / 商品橱窗 / 订单详情。
     *
     * 这里只放「用户最常点」的三项；商家入驻等偏B端的入口不在此列（地址仍保留在
     * {@link Endpoints#merchant()} 中，需要时可再往数组里追加，界面会自动按数量等分排布）。
     */
    static Entrance[] all() {
        return new Entrance[]{
                new Entrance(R.drawable.ic_entry_shop_center, R.string.entry_shop_center,
                        Endpoints.shopCenter(), Endpoints.ROUTE_SHOP_CENTER),
                new Entrance(R.drawable.ic_entry_showcase, R.string.entry_showcase,
                        Endpoints.showcase(), Endpoints.ROUTE_SHOWCASE),
                new Entrance(R.drawable.ic_entry_orders, R.string.entry_orders,
                        Endpoints.orders(), Endpoints.ROUTE_ORDERS),
        };
    }
}
