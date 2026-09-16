package com.mallcenter.app;

import android.content.Context;
import android.util.Base64;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * 把「店铺中心 / 商品橱窗 / 订单详情」这一行插进个人主页的网页里，
 * 而不是浮在网页上方 —— 位置和参考包一致：在个人简介（bio）下面、视频列表上面，随页面一起滚动。
 *
 * <p>锚点用的是 tiktok 页面上的 {@code data-e2e} 标记（{@code user-item-list} / {@code user-bio}），
 * 这类标记是给自动化测试用的，比样式类名（如 {@code e1169s3h1}）稳定得多，页面改版时不容易失效。
 *
 * <p>本类负责三件事：
 * <ol>
 *   <li>{@link #apply(WebView, Context, boolean)}：个人主页就注入、其它页面就移除，可以反复调用；</li>
 *   <li>注入的内容自带一个 MutationObserver，页面局部重绘把入口行冲掉时会自动补回来；</li>
 *   <li>点击通过 JS 桥 {@code window.android.openEntrance(route)} 回到原生，
 *       由 {@link MainActivity} 打开 {@link MallActivity}（地址只认白名单里的 route）。</li>
 * </ol>
 *
 * <p>尺寸和颜色全部从 {@code res/values/entry_style.xml} 读，与文档里的数值保持一处定义。
 */
final class ProfileEntranceRow {

    /** 入口行本身的 DOM id */
    private static final String ROW_ID = "mc-entry-row";
    /** 注入的样式表 DOM id */
    private static final String STYLE_ID = "mc-entry-style";
    /** 挂在 window 上的命名空间，避免和页面自己的变量撞名 */
    private static final String NS = "__mcEntrance";

    /** 注入脚本只拼一次：里面含 6 张图的 base64，反复拼纯属浪费内存和 CPU */
    private static String cachedMountJs;

    private ProfileEntranceRow() {
    }

    /**
     * 让入口行与当前页面保持一致。
     *
     * @param show true=当前是个人主页（注入并显示），false=其它页面（移除）
     */
    static void apply(WebView webView, Context context, boolean show) {
        if (webView == null || context == null) {
            return;
        }
        webView.evaluateJavascript(show ? mountJs(context) : unmountJs(), null);
    }

    private static String mountJs(Context context) {
        if (cachedMountJs == null) {
            cachedMountJs = buildMountJs(context.getApplicationContext());
        }
        return cachedMountJs;
    }

    /** 移除入口行：把「应该显示」的开关关掉再摘掉节点，MutationObserver 便不会把它补回来 */
    private static String unmountJs() {
        return "(function(){var ns=window." + NS + ";if(!ns)return;ns.want=false;if(ns.unmount)ns.unmount();})();";
    }

    private static String buildMountJs(Context context) {
        Entrance[] entrances = Entrance.all();
        if (entrances.length == 0) {
            return "(function(){})();";
        }

        String heightCss = cssPx(context, R.dimen.entry_bar_height);
        String iconCss = cssPx(context, R.dimen.entry_icon_size);
        String gapCss = cssPx(context, R.dimen.entry_icon_label_gap);
        String labelCss = cssPx(context, R.dimen.entry_label_size);
        String minWidthCss = cssPx(context, R.dimen.entry_item_min_width);

        String barBg = cssColor(context, R.color.entry_bar_bg);
        String divider = cssColor(context, R.color.entry_bar_divider);
        String labelNormal = cssColor(context, R.color.entry_label_normal);
        String labelPressed = cssColor(context, R.color.entry_label_pressed);
        String itemPressedBg = cssColor(context, R.color.entry_item_pressed_bg);

        StringBuilder css = new StringBuilder(24576);
        StringBuilder html = new StringBuilder(1024);
        appendRowCss(css, heightCss, iconCss, gapCss, labelCss, minWidthCss,
                barBg, divider, labelNormal, labelPressed, itemPressedBg);

        for (Entrance entrance : entrances) {
            appendItemCssAndHtml(context, css, html, entrance);
        }

        StringBuilder js = new StringBuilder(css.length() + html.length() + 2048);
        js.append("(function(){var D=document;");
        js.append("var CSS='").append(escapeJs(css.toString())).append("';");
        js.append("var HTML='").append(escapeJs(html.toString())).append("';");
        js.append(scriptBody());
        js.append("})();");
        return js.toString();
    }

    /** 入口行与内部元素的样式：整行、单个入口、图标、文字（尺寸单位一律是网页 CSS 像素） */
    private static void appendRowCss(StringBuilder css, String heightPx, String iconPx, String gapPx,
                                     String labelPx, String minWidthPx, String barBg, String divider,
                                     String labelNormal, String labelPressed, String itemPressedBg) {
        css.append('#').append(ROW_ID).append('{')
                .append("display:flex;align-items:stretch;width:100%;box-sizing:border-box;")
                .append("flex:0 0 auto;margin:0;padding:0;")
                .append("height:").append(heightPx).append("px;")
                .append("min-height:").append(heightPx).append("px;")
                .append("background:").append(barBg).append(';')
                .append("border-bottom:1px solid ").append(divider).append(';')
                .append("font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,")
                .append("\"Helvetica Neue\",Arial,sans-serif;")
                .append('}');

        css.append('#').append(ROW_ID).append(" .mc-entry-item{")
                .append("flex:1 1 0;min-width:").append(minWidthPx).append("px;")
                .append("display:flex;align-items:center;justify-content:center;")
                .append("text-decoration:none;cursor:pointer;overflow:hidden;")
                .append("-webkit-tap-highlight-color:transparent;")
                .append("color:").append(labelNormal).append(";}");
        css.append('#').append(ROW_ID).append(" .mc-entry-item:active{background:")
                .append(itemPressedBg).append(";}");
        css.append('#').append(ROW_ID).append(" .mc-entry-item:active .mc-entry-label{color:")
                .append(labelPressed).append(";}");

        css.append('#').append(ROW_ID).append(" .mc-entry-icon{")
                .append("flex:0 0 auto;width:").append(iconPx).append("px;")
                .append("height:").append(iconPx).append("px;")
                .append("background-repeat:no-repeat;background-position:center;")
                .append("background-size:100% 100%;}");
        css.append('#').append(ROW_ID).append(" .mc-entry-label{")
                .append("margin-left:").append(gapPx).append("px;")
                .append("font-size:").append(labelPx).append("px;")
                .append("line-height:1;white-space:nowrap;color:").append(labelNormal).append(";}");
    }

    /** 一个入口：图标（普通态 + 按下态）拼进样式表，标签拼进 HTML */
    private static void appendItemCssAndHtml(Context context, StringBuilder css, StringBuilder html,
                                             Entrance entrance) {
        String item = '#' + ROW_ID + " .mc-entry-item[data-route=\"" + entrance.route + "\"]";
        String normalIcon = iconDataUri(context, entrance.iconRes);
        if (!normalIcon.isEmpty()) {
            css.append(item).append(" .mc-entry-icon{background-image:url(\"")
                    .append(normalIcon).append("\");}");
        }
        String pressedIcon = iconDataUri(context, entrance.iconPressedRes);
        if (!pressedIcon.isEmpty()) {
            css.append(item).append(":active .mc-entry-icon{background-image:url(\"")
                    .append(pressedIcon).append("\");}");
        }

        html.append("<a class=\"mc-entry-item\" data-route=\"").append(entrance.route).append("\">")
                .append("<span class=\"mc-entry-icon\"></span>")
                .append("<span class=\"mc-entry-label\">")
                .append(escapeHtml(context.getString(entrance.labelRes)))
                .append("</span></a>");
    }

    /** 注入脚本里固定的那部分逻辑：找锚点、挂载、卸载、被冲掉后自动补回 */
    private static String scriptBody() {
        return SCRIPT_TEMPLATE
                .replace("__ROW__", ROW_ID)
                .replace("__STYLE__", STYLE_ID)
                .replace("__NS__", NS);
    }

    /**
     * 固定脚本模板，三个 id 占位符在 {@link #scriptBody()} 里替换。
     *
     * <p>{@code anchor()} 找的是「个人资料头部」与「内容区（标签栏 + 视频网格）」之间那一层，
     * 把入口行插在它前面 —— 效果就是：在个人简介下方、内容标签栏上方，随页面一起滚动。
     * 三步依次降级，全部避开会变的样式哈希类名：
     * <ol>
     *   <li>从 {@code [data-e2e="user-item-list"]}（视频网格）往上爬，哪一层的「前一个兄弟」
     *       里含个人资料头部，就插在那一层前面；</li>
     *   <li>从资料头部往上爬，哪一层的「下一个兄弟」里含视频网格，就插在那个兄弟前面；</li>
     *   <li>页面刚打开、网格还没渲染时，直接插在内容标签栏（{@code DivVideoFeedTab}）之前。</li>
     * </ol>
     * 三步都拿不到时返回 {@code null}：宁可不显示，也不要把行糊在简介标题容器里。
     */
    private static final String SCRIPT_TEMPLATE =
            "var ns=window.__NS__||(window.__NS__={want:false});"
                    + "ns.want=true;"
                    + "function style(){"
                    + "if(D.getElementById('__STYLE__'))return;"
                    + "var s=D.createElement('style');s.id='__STYLE__';s.textContent=CSS;"
                    + "(D.head||D.documentElement).appendChild(s);"
                    + "}"
                    + "var HDR='[data-e2e=\"user-bio\"],[data-e2e=\"user-title\"],"
                    + "[data-e2e=\"user-subtitle\"],[data-e2e=\"followers-count\"],"
                    + "[data-e2e=\"likes-count\"],[data-e2e=\"edit-profile-button\"]';"
                    + "function anchor(){"
                    + "var list=D.querySelector('[data-e2e=\"user-item-list\"]');"
                    + "var cur,ps,nx;"
                    + "if(list){"
                    + "for(cur=list;cur&&cur!==D.body;cur=cur.parentElement){"
                    + "ps=cur.previousElementSibling;"
                    + "if(ps&&ps.querySelector(HDR))return{el:cur};}}"
                    + "var hdr=D.querySelector(HDR);"
                    + "if(hdr&&list){"
                    + "for(cur=hdr;cur&&cur!==D.body;cur=cur.parentElement){"
                    + "nx=cur.nextElementSibling;"
                    + "if(nx&&nx.querySelector('[data-e2e=\"user-item-list\"]'))return{el:nx};}}"
                    + "var tabs=D.querySelector('[class*=\"DivVideoFeedTab\"]');"
                    + "if(tabs)return{el:tabs};"
                    + "return null;"
                    + "}"
                    + "function mount(){"
                    + "if(!ns.want)return;"
                    + "var a=anchor();"
                    + "var old=D.getElementById('__ROW__');"
                    + "if(!a||!a.el.parentNode){"
                    + "if(old&&old.parentElement)old.parentElement.removeChild(old);"
                    + "return;}"
                    + "if(old&&old.nextElementSibling===a.el)return;"
                    + "if(old&&old.parentElement)old.parentElement.removeChild(old);"
                    + "style();"
                    + "var row=D.createElement('div');"
                    + "row.id='__ROW__';"
                    + "row.innerHTML=HTML;"
                    + "var items=row.querySelectorAll('.mc-entry-item');"
                    + "for(var i=0;i<items.length;i++){"
                    + "items[i].addEventListener('click',function(ev){"
                    + "ev.preventDefault();ev.stopPropagation();"
                    + "var r=this.getAttribute('data-route');"
                    + "if(window.android&&window.android.openEntrance)window.android.openEntrance(r);"
                    + "});}"
                    + "a.el.parentNode.insertBefore(row,a.el);"
                    + "}"
                    + "ns.mount=mount;"
                    + "ns.unmount=function(){var old=D.getElementById('__ROW__');"
                    + "if(old&&old.parentElement)old.parentElement.removeChild(old);};"
                    + "if(!ns.observer&&D.body){"
                    + "ns.observer=new MutationObserver(function(){"
                    + "if(ns.pending)return;"
                    + "ns.pending=true;"
                    + "setTimeout(function(){ns.pending=false;mount();},0);"
                    + "});"
                    + "ns.observer.observe(D.body,{childList:true,subtree:true});"
                    + "}"
                    + "mount();";

    /**
     * 把资源里的尺寸换算成网页能用的 CSS 像素（带小数）。
     *
     * <p>{@code getDimensionPixelSize()} 返回的是<b>物理像素</b>（dp × density）；
     * 而 TikTok 页面的 viewport 是 {@code width=device-width, initial-scale=1}，
     * 网页里的 1 个 CSS 像素正好等于 1 个 dp。差一个 density 倍（本机 2.75），
     * 直接塞进 CSS 会让整行、图标、字号全部放大 2.75 倍，三个入口也会挤出屏幕。
     */
    private static String cssPx(Context context, int dimenRes) {
        float px = context.getResources().getDimensionPixelSize(dimenRes);
        float dp = px / context.getResources().getDisplayMetrics().density;
        return trimNumber(dp);
    }

    /** 数字转字符串：去掉多余的小数位，避免 CSS 里出现 36.000000000000004 这种值 */
    private static String trimNumber(float value) {
        BigDecimal rounded = new BigDecimal(Float.toString(value))
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return rounded.toPlainString();
    }

    /** 把 {@code #AARRGGBB} 的资源颜色转成网页能用的 rgba() */
    private static String cssColor(Context context, int colorRes) {
        int color = context.getColor(colorRes);
        return String.format(Locale.US, "rgba(%d,%d,%d,%.3f)",
                (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF,
                ((color >>> 24) & 0xFF) / 255f);
    }

    /** 把 drawable 的原始字节读成 data URI，网页里直接当图片用，不额外发网络请求 */
    private static String iconDataUri(Context context, int drawableRes) {
        InputStream in = null;
        try {
            in = context.getResources().openRawResource(drawableRes);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return "data:image/png;base64," + Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            // 取不到图就退化成纯文字入口，点击不受影响
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // 关闭失败无需处理
                }
            }
        }
    }

    /** 把 CSS / HTML 安全地塞进 JS 的单引号字符串 */
    private static String escapeJs(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '\'':
                    out.append("\\'");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
