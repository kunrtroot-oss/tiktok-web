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
 * 个人主页顶部的一排入口（订单详情 / 商品橱窗 / 店铺中心）。
 *
 * <p>参考包的做法是往「个人主页的原生视图树」里插一条横向滚动栏；我们的个人主页是 TikTok 官方网页，
 * 改不了它的 DOM，所以改成把这一栏盖在网页上方、只在个人主页显示，用户看到的效果是一致的：
 * 进入个人主页就出现一条图标入口，离开就消失。
 *
 * <p>用 {@link HorizontalScrollView} 而不是固定均分，是为了以后增加第五个入口时能横向滑动，
 * 不至于把图标挤变形。
 */
class EntranceBarView extends HorizontalScrollView {

    /** 图标与文字之间的间距 */
    private static final int ICON_LABEL_GAP_DP = 6;
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
        return dp(context, 74);
    }

    private View buildRow(Context context) {
        Entrance[] list = Entrance.all();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 屏幕宽度均分成「入口个数」份；不足最小宽度时按最小宽度走，允许横向滑动
        int evenWidth = getScreenWidthPx() / list.length;
        int itemWidth = Math.max(evenWidth, dp(context, MIN_ITEM_WIDTH_DP));

        ColorStateList iconTint = context.getResources()
                .getColorStateList(R.color.entry_icon_tint, context.getTheme());
        ColorStateList labelTint = context.getResources()
                .getColorStateList(R.color.entry_label_tint, context.getTheme());

        for (Entrance entrance : list) {
            row.addView(createItem(context, entrance, itemWidth, iconTint, labelTint));
        }
        return row;
    }

    /** 单个入口 = 图标 + 文字，整体可点、可按下高亮 */
    private View createItem(Context context, Entrance entrance, int width,
                            ColorStateList iconTint, ColorStateList labelTint) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        applySelectableBackground(context, item);

        ImageView icon = new ImageView(context);
        icon.setImageResource(entrance.iconRes);
        icon.setImageTintList(iconTint);
        // 子控件跟随父控件状态，按下时图标与文字一起变色
        icon.setDuplicateParentStateEnabled(true);
        item.addView(icon, new LinearLayout.LayoutParams(
                dp(context, 26), dp(context, 26)));

        TextView label = new TextView(context);
        label.setText(entrance.labelRes);
        label.setTextColor(labelTint);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(context, ICON_LABEL_GAP_DP);
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
