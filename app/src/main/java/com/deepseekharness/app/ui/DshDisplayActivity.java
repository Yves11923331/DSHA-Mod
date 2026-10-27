package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;

/**
 * 独立的横屏 DSH 显示页。
 *
 * <p>为什么不复用 {@link WebPreviewActivity} 的实例：它是 {@code singleTask}，竖屏 Web 已经
 * 开着时再 startActivity 只会走 onNewIntent，{@code EXTRA_LANDSCAPE} 被忽略，结果还是竖屏。
 * 这里用不同 taskAffinity 的独立 Activity + {@link #landscapeHost()} 强制进入横屏分支。
 */
public class DshDisplayActivity extends WebPreviewActivity {
  public static Intent intent(Context context, String url) {
    Intent intent =
        WebPreviewActivity.intent(context, url, null).setClass(context, DshDisplayActivity.class);
    // 留在 MainActivity 的任务里：finish() 就回启动页，不会落回桌面。
    intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    return intent;
  }

  @Override
  protected boolean landscapeHost() {
    return true;
  }

  @Override
  protected void onResume() {
    super.onResume();
    WebFullscreenUi.applyImmersiveSystemBars(this);
    // Dock 的 setupWindow 在 onResume 重来一次；背景/透明窗口也一起重应用。
    DshAppearance.apply(this, "landscape", null);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) WebFullscreenUi.applyImmersiveSystemBars(this);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // 系统返回键直接退出横屏页，而不是在 DSH 网页历史里后退。
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                landscapeBack();
              }
            });
  }
}
