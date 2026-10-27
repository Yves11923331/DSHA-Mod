package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;

/**
 * 屏幕常亮（设置项，默认关）。
 *
 * <p>做法就是给窗口加 {@code FLAG_KEEP_SCREEN_ON}：不申请 WAKE_LOCK（那需要权限、还得自己管生命周期），
 * 标志随窗口存在，Activity 销毁/退出 App 自动失效，不会出现「退出了还一直亮着」。
 *
 * <p>生效范围：App 内所有 Activity（原生界面 + DSH 竖屏网页 + 横屏虚拟屏），
 * 由 {@link ModernAndroidUi} 在 onActivityResumed 统一挂。
 */
public final class ScreenAwake {
  private static final String PREFS = "screen_awake";
  private static final String KEY = "keep_on";

  private ScreenAwake() {}

  public static boolean enabled(Context context) {
    try {
      return context
          .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .getBoolean(KEY, false);
    } catch (Throwable error) {
      return false;
    }
  }

  public static void setEnabled(Context context, boolean on) {
    try {
      context
          .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .edit()
          .putBoolean(KEY, on)
          .commit();
    } catch (Throwable ignored) {
    }
  }

  /** 按设置给窗口挂/摘 FLAG_KEEP_SCREEN_ON。重复调用无副作用。 */
  public static void apply(Activity activity) {
    if (activity == null || activity.isFinishing()) return;
    try {
      if (enabled(activity)) {
        activity
            .getWindow()
            .addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      } else {
        activity
            .getWindow()
            .clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      }
    } catch (Throwable ignored) {
    }
  }
}
