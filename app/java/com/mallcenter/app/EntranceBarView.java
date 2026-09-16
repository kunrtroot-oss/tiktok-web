package com.mallcenter.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 个人主页顶部的一排入口（店铺中心 / 商品橱窗 / 订单详情）。
 *
 * <p>参考包的做法是往「个人主页的原生视图树」里插一条入口栏；我们的个人主页是 TikTok 官方网页，
 * 改不了它的 DOM，所以改成把这一栏盖在网页上方、只在个人主页显示，用户看到的效果是一致的：
 * 进入个人主页就出现一条图标入口，离开就消失。
 *
 * <p>单个入口的排布与参考包一致：<b>圆角方框图标在左、文字在右</b>，整组在入口内居中。
 * 图标直接用参考包原图（{@code res/drawable-nodpi/entry_icon_*.png}）：普通态是浅灰方框 +
 * 黑色线稿，按下态方框转黑。所以这里<b>不给图标染色</b>，一染就把方框和线稿一起染掉了。
 *
 * <p>尺寸按参考包截图的实测比例定：图标约占栏高的二分之一，文字略小于图标，
 * 图标与文字之间留约半个图标的间距。改这些数字前请先看截图比例，不要凭手感调。
 *
 * <p>用 {@link HorizontalScrollView} 而不是固定均分，是为了以后增加入口时能横向滑动，
 * 不至于把图标挤变形。
 */
class EntranceBarView extends HorizontalScrollView {

    /** 栏高：参考包实测约 30dp，这里取 36dp 保证手指点得中 */
    private static final int BAR_HEIGHT_DP = 36;
    /** 图标边长：约为栏高的一半（参考包实测比例） */
    private static final int ICON_SIZE_DP = 18;
    /** 图标与文字之间的间距 */
    private static final int ICON_LABEL_GAP_DP = 8;
    /** 文字字号 */
    private static final int LABEL_SIZE_SP = 12;
    /** 单个入口的最小宽度：屏幕很窄时也不会挤成一团 */
    private static final int MIN_ITEM_WIDTH_DP = 84;

    /** 点击某个入口时回调给 Activity，由 Activity 负责打开独立页面 */
    interface OnEntranceClickListener {
        void onEntranceClick(Entrance entrance);
    }

    private final OnEntranceClickListener listener;

    EntranceBarView(Context context, OnEntranceClickListener listener) {
        super(context);
        this.listener = listener;
        setHorizontalScrollBarEnabled(false);
        // 入口数量少时撑满整屏；数量多时自然变成可横向滑动
        setFillViewport(true);
        setBackgroundResource(R.drawable.bg_entry_bar);
        setOverScrollMode(OVER_SCROLL_NEVER);
        addView(buildRow(context), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 入口条的推荐高度（dp 转 px），由调用方设置 LayoutParams 时使用 */
    static int barHeightPx(Context context) {
        return dp(context, BAR_HEIGHT_DP);
    }

    private View buildRow(Context context) {
        Entrance[] list = Entrance.all();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 屏幕宽度均分成「入口个数」份；不足最小宽度时按最小宽度走，允许横向滑动
        int evenWidth = getScreenWidthPx() / list.length;
        int itemWidth = Math.max(evenWidth, dp(context, MIN_ITEM_WIDTH_DP));

        // 只给文字取色：图标自带颜色，不需要（也不应该）染色
        ColorStateList labelTint = context.getResources()
                .getColorStateList(R.color.entry_label_tint, context.getTheme());

        for (Entrance entrance : list) {
            row.addView(createItem(context, entrance, itemWidth, labelTint));
        }
        return row;
    }

    /**
     * 单个入口 = 图标（左） + 文字（右），左图右字与参考包一致。
     *
     * <p>按下反馈有两条：整块的背景高亮（主题里的 selectableItemBackground），
     * 以及图标自己换成黑色方框版本——图标资源是 selector，靠 duplicateParentState 跟着父控件走。
     */
    private View createItem(Context context, Entrance entrance, int width,
                            ColorStateList labelTint) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        applySelectableBackground(context, item);

        ImageView icon = new ImageView(context);
        icon.setImageResource(entrance.iconRes);
        // 子控件跟随父控件状态，按下时图标换成黑色方框版本
        icon.setDuplicateParentStateEnabled(true);
        item.addView(icon, new LinearLayout.LayoutParams(
                dp(context, ICON_SIZE_DP), dp(context, ICON_SIZE_DP)));

        TextView label = new TextView(context);
        label.setText(entrance.labelRes);
        label.setTextColor(labelTint);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE_SP);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.leftMargin = dp(context, ICON_LABEL_GAP_DP);
        item.addView(label, labelParams);

        item.setLayoutParams(new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT));
        item.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // 回调里再判断一次非空，防止 Activity 已销毁时被点击
                if (listener != null) {
                    listener.onEntranceClick(entrance);
                }
            }
        });
        return item;
    }

    /** 取主题里的「可点击项」背景（按下有水波纹/高亮），拿不到就留空，不影响功能 */
    private void applySelectableBackground(Context context, View item) {
        TypedValue value = new TypedValue();
        boolean found = context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, value, true);
        if (found && value.resourceId != 0) {
            item.setBackgroundResource(value.resourceId);
        }
    }

    private int getScreenWidthPx() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return metrics.widthPixels > 0 ? metrics.widthPixels : dp(getContext(), 360);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
