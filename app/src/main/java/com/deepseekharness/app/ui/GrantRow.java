package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatImageView;
import com.deepseekharness.app.R;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 设备授权页里「代码生成的行」。
 *
 * <p>静态行写在 {@code fragment_device_grants.xml}，动态行（屏幕确认方式、规则、撤销、
 * 全局确认……）以前是代码里现攒的按钮和 {@code RadioGroup}，字号/内边距和设置页对不上，
 * 整页看起来像两个 App。这里把「设置页那一行」的尺寸固化成一份实现：
 *
 * <ul>
 *   <li>行：13dp 内边距、最小 64dp、24dp 主色线性图标、标题 13sp 粗体、
 *       说明 12sp 次要色（上方 4dp）、右侧 16dp 箭头或 MaterialSwitch；
 *   <li>段标题：11sp 粗体次要色，上 18dp / 下 8dp；
 *   <li>分隔线：1dp {@code @color/line}，整宽（与设置页一致）。
 * </ul>
 *
 * <p>只提供这三样，不引入新色值、新圆角、新字号 —— 一致性靠「没有第二种写法」。
 */
final class GrantRow {

  final LinearLayout root;
  final TextView title;
  final TextView subtitle;
  final AppCompatImageView chevron;
  /** 只有 {@code withSwitch} 时才非空。 */
  final MaterialSwitch toggle;

  private GrantRow(LinearLayout root, TextView title, TextView subtitle, AppCompatImageView chevron,
      MaterialSwitch toggle) {
    this.root = root;
    this.title = title;
    this.subtitle = subtitle;
    this.chevron = chevron;
    this.toggle = toggle;
  }

  /** 段标题：与设置页「工作区 / 体验 / 开发者」同一尺寸。 */
  static TextView section(Context ctx, String label) {
    TextView view = new TextView(ctx);
    view.setText(label);
    view.setTextSize(11);
    view.setTextColor(ctx.getColor(R.color.text_muted));
    view.setIncludeFontPadding(false);
    view.setTypeface(null, android.graphics.Typeface.BOLD);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(ctx, 18);
    lp.bottomMargin = dp(ctx, 8);
    view.setLayoutParams(lp);
    return view;
  }

  /** 卡片容器：白卡 + 1px 边，和静态卡片同一个 drawable。 */
  static LinearLayout card(Context ctx) {
    LinearLayout card = new LinearLayout(ctx);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackgroundResource(R.drawable.bg_card);
    card.setLayoutParams(
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
    return card;
  }

  static View divider(Context ctx) {
    View line = new View(ctx);
    line.setBackgroundColor(ctx.getColor(R.color.line));
    line.setLayoutParams(
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)));
    return line;
  }

  static GrantRow row(Context ctx, int iconRes, String title, String subtitle) {
    return row(ctx, iconRes, title, subtitle, false, true);
  }

  static GrantRow row(Context ctx, int iconRes, String title, String subtitle, boolean withSwitch,
      boolean withChevron) {
    LinearLayout root = new LinearLayout(ctx);
    root.setOrientation(LinearLayout.HORIZONTAL);
    root.setGravity(Gravity.CENTER_VERTICAL);
    root.setMinimumHeight(dp(ctx, 64));
    root.setPadding(dp(ctx, 13), dp(ctx, 13), dp(ctx, 13), dp(ctx, 13));
    // 开关行也整行可点（和设置页一致：点行 = 切开关）；带箭头的行由调用方接管点击。
    boolean clickable = withChevron || withSwitch;
    root.setClickable(clickable);
    root.setFocusable(clickable);
    if (clickable) {
      int ripple = selectable(ctx);
      if (ripple != 0) root.setBackgroundResource(ripple);
    }

    AppCompatImageView icon = new AppCompatImageView(ctx);
    icon.setImageResource(iconRes);
    icon.setColorFilter(ctx.getColor(R.color.primary));
    root.addView(icon, new LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)));

    LinearLayout texts = new LinearLayout(ctx);
    texts.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams textsLp =
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    textsLp.leftMargin = dp(ctx, 12);
    root.addView(texts, textsLp);

    TextView titleView = new TextView(ctx);
    titleView.setText(title);
    titleView.setTextSize(13);
    titleView.setTextColor(ctx.getColor(R.color.text));
    titleView.setIncludeFontPadding(false);
    titleView.setTypeface(null, android.graphics.Typeface.BOLD);
    texts.addView(titleView);

    TextView subtitleView = new TextView(ctx);
    subtitleView.setText(subtitle);
    subtitleView.setTextSize(12);
    subtitleView.setTextColor(ctx.getColor(R.color.text_secondary));
    subtitleView.setIncludeFontPadding(false);
    subtitleView.setMaxLines(2);
    subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams subLp =
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
    subLp.topMargin = dp(ctx, 4);
    texts.addView(subtitleView, subLp);

    MaterialSwitch switchView = null;
    if (withSwitch) {
      switchView = new MaterialSwitch(ctx);
      switchView.setMinimumHeight(dp(ctx, 44));
      switchView.setShowText(false);
      root.addView(
          switchView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
              LinearLayout.LayoutParams.WRAP_CONTENT));
      if (!withChevron) {
        // 只有开关：整行点击 = 切开关（设置页同一行为）。
        final MaterialSwitch rowSwitch = switchView;
        root.setOnClickListener(v -> rowSwitch.toggle());
      }
    }

    AppCompatImageView chevronView = null;
    if (withChevron) {
      chevronView = new AppCompatImageView(ctx);
      chevronView.setImageResource(R.drawable.ic_ui2_chevron);
      chevronView.setColorFilter(ctx.getColor(R.color.text_muted));
      chevronView.setContentDescription(title);
      LinearLayout.LayoutParams chevronLp =
          new LinearLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16));
      if (switchView != null) chevronLp.leftMargin = dp(ctx, 6);
      root.addView(chevronView, chevronLp);
    }

    root.setLayoutParams(
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
    return new GrantRow(root, titleView, subtitleView, chevronView, switchView);
  }

  static void setSubtitle(GrantRow row, String text) {
    if (row == null || row.subtitle == null) return;
    // 页面每 2 秒刷一次；文本没变就别重绘。
    if (text != null && !text.contentEquals(row.subtitle.getText())) row.subtitle.setText(text);
    row.subtitle.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
  }

  private static int selectable(Context ctx) {
    try {
      android.util.TypedValue tv = new android.util.TypedValue();
      ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
      return tv.resourceId;
    } catch (Throwable ignored) {
      return 0;
    }
  }

  static int dp(Context ctx, int value) {
    return Math.round(value * ctx.getResources().getDisplayMetrics().density);
  }
}
