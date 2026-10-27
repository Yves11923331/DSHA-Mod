package com.deepseekharness.app.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import com.deepseekharness.app.R;

/**
 * 215 启动页（参考 b-2 星曜奇门「设计-启动页 / 01 白场」）。
 *
 * <p>它只是一层盖在最上面的 View，不是新的 Activity：启动契约（welcomed → Welcome →
 * MainActivity）一点没动，入口检查也照常跑。这样既拿到「第一帧就是干净白场」的观感，
 * 又不用为 1.2 秒的动画多开一条 Activity 生命周期。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li>图标 88dp <b>不放大</b>，标题 17sp —— 靠留白撑气质，放大只会暴露锯齿；
 *   <li>只用 {@code alpha / scale / translation}，不动 filter、不叠阴影，低端机不掉帧；
 *   <li>整段 ≤1.2s（0→1.0s 走完，1.0→1.18s 淡出），超过就让位给真实界面。
 * </ul>
 *
 * <p>进程内只显示一次：Activity 因旋转/主题重建时会再走 onCreate，但用户不该再看一遍启动页。
 */
final class SplashOverlay {

  private static final long INTRO_MS = 1000;
  private static final long FADE_MS = 180;
  private static final long TOTAL_MS = INTRO_MS + FADE_MS;
  private static boolean shown;

  private SplashOverlay() {}

  static void showOnce(Activity activity) {
    if (shown || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
    shown = true;
    try {
      show(activity);
    } catch (RuntimeException ignored) {
      // 启动页属于装饰：失败了不能把 App 也带走。
    }
  }

  private static void show(Activity activity) {
    View splash =
        LayoutInflater.from(activity).inflate(R.layout.view_splash, null, false);
    ViewGroup.LayoutParams params =
        new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    activity.addContentView(splash, params);

    float density = activity.getResources().getDisplayMetrics().density;
    View icon = splash.findViewById(R.id.splashIcon);
    View title = splash.findViewById(R.id.splashTitle);
    View subtitle = splash.findViewById(R.id.splashSub);
    View line = splash.findViewById(R.id.splashLine);

    // 图标：opacity 0→1 + scale .975→1，0.9s
    if (icon != null) {
      icon.setAlpha(0f);
      icon.setScaleX(0.975f);
      icon.setScaleY(0.975f);
      icon.animate()
          .alpha(1f)
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(900)
          .setInterpolator(new PathInterpolator(0.22f, 0.7f, 0.2f, 1f))
          .start();
    }

    // 标题/副标题：translateY(7dp)→0 + 淡入，延迟 240ms
    float lift = 7f * density;
    animateRise(title, lift, 240);
    animateRise(subtitle, lift, 300);

    // 底部 2px 进度线：scaleX 0→1（从左端起）
    if (line != null) {
      line.setPivotX(0f);
      line.setScaleX(0f);
      line.animate()
          .scaleX(1f)
          .setStartDelay(520)
          .setDuration(480)
          .setInterpolator(new PathInterpolator(0.3f, 0f, 0.2f, 1f))
          .start();
    }

    splash.postDelayed(
        () -> {
          if (splash.getParent() == null) return;
          splash
              .animate()
              .alpha(0f)
              .setDuration(FADE_MS)
              .withEndAction(
                  () -> {
                    ViewGroup parent = (ViewGroup) splash.getParent();
                    if (parent != null) parent.removeView(splash);
                  })
              .start();
        },
        INTRO_MS);
  }

  private static void animateRise(View view, float lift, long delay) {
    if (view == null) return;
    view.setAlpha(0f);
    view.setTranslationY(lift);
    view.animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(delay)
        .setDuration(400)
        .setInterpolator(new PathInterpolator(0.22f, 0.7f, 0.2f, 1f))
        .start();
  }

  /** 供本地测试断言「整段不超过 1.2s」。 */
  static long totalMillis() {
    return TOTAL_MS;
  }
}
