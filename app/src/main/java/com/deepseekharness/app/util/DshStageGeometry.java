package com.deepseekharness.app.util;

/**
 * 横屏 DSH 显示的纯几何：三档 CSS 视口、缩放与居中。
 *
 * <p>WebView 的布局尺寸 = CSS 视口 × density，再用 View 缩放压进可用区域；
 * 先按 density 倍率渲染再缩小 = 超采样，清晰度最好（与 b-3 Dock 同一套做法）。
 *
 * <p>这里只放数字，不 import 任何 Android API，便于单测。
 */
public final class DshStageGeometry {
  /**
   * 三档纯放大：1280×720（×1.00）/ 1024×576（×1.25）/ 854×480（×1.50）。
   *
   * <p>三档都是 16:9，所以每一档都铺满舞台；字号倍率 = 1280 / 宽度，
   * 与屏幕密度无关（密度在缩放里约掉了），换机型关系不变。
   */
  public static final int[] MODE_W = {1280, 1024, 854};

  public static final int[] MODE_H = {720, 576, 480};

  public static final int MODE_COUNT = MODE_W.length;

  public static final int DEFAULT_MODE = 0;

  private DshStageGeometry() {}

  public static int clampMode(int mode) {
    return mode < 0 ? 0 : Math.min(mode, MODE_COUNT - 1);
  }

  public static int cssWidth(int mode) {
    return MODE_W[clampMode(mode)];
  }

  public static int cssHeight(int mode) {
    return MODE_H[clampMode(mode)];
  }

  /** WebView 布局宽度（物理 px）。CSS 宽度 × density。 */
  public static int layoutWidth(int mode, float density) {
    return Math.round(cssWidth(mode) * density);
  }

  /** WebView 布局高度（物理 px）。CSS 高度 × density。 */
  public static int layoutHeight(int mode, float density) {
    return Math.round(cssHeight(mode) * density);
  }

  /**
   * 缩放比例：把「布局尺寸」塞进可用区域，取 min，保证不变形、多余方向留边。
   *
   * @return 0 表示参数无效（调用方应跳过这一帧）
   */
  public static float scale(int mode, float density, int areaW, int areaH) {
    if (areaW <= 0 || areaH <= 0 || density <= 0) return 0f;
    return Math.min(
        areaW / (float) layoutWidth(mode, density), areaH / (float) layoutHeight(mode, density));
  }

  /** 居中偏移（物理 px）：{left, top}。 */
  public static int[] centerOffset(int mode, float density, int areaW, int areaH) {
    float scale = scale(mode, density, areaW, areaH);
    int viewW = Math.round(layoutWidth(mode, density) * scale);
    int viewH = Math.round(layoutHeight(mode, density) * scale);
    return new int[] {Math.max(0, (areaW - viewW) / 2), Math.max(0, (areaH - viewH) / 2)};
  }

  /** 文字相对模式1的放大倍数（模式高度越小，字越大）。 */
  public static float textScale(int mode) {
    return MODE_H[DEFAULT_MODE] / (float) cssHeight(mode);
  }
}
