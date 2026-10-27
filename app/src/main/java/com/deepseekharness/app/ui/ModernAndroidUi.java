package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.deepseekharness.app.R;

/** 统一处理 Android 15+ 强制铺满窗口后的状态栏、挖孔和输入法区域。 */
public final class ModernAndroidUi implements Application.ActivityLifecycleCallbacks {
  @Override
  public void onActivityPostCreated(Activity activity, Bundle saved) {
    applyInsets(activity);
  }

  /**
   * 自己铺满整屏、自己给控件补 inset 的页面。
   *
   * <p>这些页面把「壁纸 / 虚拟屏」当作整块底图，系统栏区域必须真的露出底图：
   * 一旦这里再给 {@code android.R.id.content} 加一层 systemBars/displayCutout/ime padding，
   * 就会出现三种真机症状（209 修）：竖屏壁纸上下被顶出白边、横屏左侧多出一条挖孔安全区白条、
   * 键盘弹出时 content 被 ime 高度压小导致整块网页「缩一下」。
   */
  private static boolean fillsSystemBars(Activity activity) {
    return activity instanceof MainActivity
        || activity instanceof DockDisplayActivity
        || activity instanceof AppearanceActivity
        || activity instanceof ImageCropActivity;
  }

  private void applyInsets(Activity activity) {
    if (activity instanceof WebFullscreenUi.Host) return;
    View content = activity.findViewById(android.R.id.content);
    if (content == null) return;
    if (android.os.Build.VERSION.SDK_INT >= 29) content.setForceDarkAllowed(false);
    WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
    final boolean selfManaged = fillsSystemBars(activity);
    int color = activity.getColor(R.color.surface);
    activity
        .getWindow()
        .setNavigationBarColor(
            selfManaged
                ? android.graphics.Color.TRANSPARENT
                : android.os.Build.VERSION.SDK_INT >= 26
                    ? color
                    : activity.getColor(R.color.terminal_surface));
    // 自己铺满的页面：content 背景必须透明，否则系统栏区域会露出这层实色而不是壁纸。
    content.setBackgroundColor(selfManaged ? android.graphics.Color.TRANSPARENT : color);
    boolean light = ColorUtils.calculateLuminance(color) > 0.5;
    androidx.core.view.WindowInsetsControllerCompat controller =
        WindowCompat.getInsetsController(activity.getWindow(), content);
    controller.setAppearanceLightStatusBars(light);
    controller.setAppearanceLightNavigationBars(light);
    if (selfManaged) {
      // 页面自己负责 inset（MainActivity: app_bar/bottom_nav；Dock: 沉浸式虚拟屏；
      // 外观页/裁剪页: 自己的滚动层或预览层），这里只把窗口切成 edge-to-edge。
      return;
    }
    final int left = content.getPaddingLeft(), top = content.getPaddingTop();
    final int right = content.getPaddingRight(), bottom = content.getPaddingBottom();
    ViewCompat.setOnApplyWindowInsetsListener(
        content,
        (view, insets) -> {
          androidx.core.graphics.Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
          view.setPadding(
              left + bars.left,
              top + bars.top,
              right + bars.right,
              bottom + Math.max(bars.bottom, keyboard));
          return WindowInsetsCompat.CONSUMED;
        });
    ViewCompat.requestApplyInsets(content);
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle saved) {
    if (android.os.Build.VERSION.SDK_INT < 29)
      activity
          .getWindow()
          .getDecorView()
          .post(
              () -> {
                if (!activity.isFinishing()) applyInsets(activity);
              });
  }

  @Override
  public void onActivityStarted(Activity activity) {}

  @Override
  public void onActivityResumed(Activity activity) {
    // 屏幕常亮（设置项）：App 内所有页面统一挂，退出 App 随窗口自动失效。
    ScreenAwake.apply(activity);
  }

  @Override
  public void onActivityPaused(Activity activity) {}

  @Override
  public void onActivityStopped(Activity activity) {}

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle out) {}

  @Override
  public void onActivityDestroyed(Activity activity) {}
}
