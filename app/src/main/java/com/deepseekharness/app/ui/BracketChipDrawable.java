package com.deepseekharness.app.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 横屏三档按钮的「四角括号」样式（212 用户选定 C 款）。
 *
 * <p>风格：直角矩形 + 极淡边框 + 四个 L 形括号；选中时括号亮青、多画一圈半透明粗线当辉光，
 * 内部压一层深蓝。全部用 Canvas 直接画，不依赖图片资源，深色舞台上边缘不发灰。
 *
 * <p>尺寸按 {@link #onBoundsChange(Rect)} 的可用空间自适应（44×26dp 这种小尺寸下
 * 括号固定 7dp、线宽 1.5dp，不会随宽度拉伸变形）。
 */
final class BracketChipDrawable extends Drawable {

  private static final int COLOR_BORDER_OFF = 0x4D8AA6C0;
  private static final int COLOR_BORDER_ON = 0xB37FD8FF;
  private static final int COLOR_BRACKET_OFF = 0x8C6FD3FF;
  private static final int COLOR_BRACKET_ON = 0xFF9BEAFF;
  private static final int COLOR_FILL_OFF = 0x12081018;
  private static final int COLOR_FILL_ON = 0xB81A2A3A;
  private static final int COLOR_GLOW = 0x596FD3FF;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private final boolean selected;
  private float density = 1f;

  BracketChipDrawable(boolean selected) {
    this.selected = selected;
    paint.setStyle(Paint.Style.FILL);
  }

  void setDensity(float density) {
    this.density = Math.max(1f, density);
    invalidateSelf();
  }

  @Override
  public void draw(Canvas canvas) {
    Rect bounds = getBounds();
    if (bounds.isEmpty()) return;
    float inset = dp(0.75f);
    rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);

    // ① 填充
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(selected ? COLOR_FILL_ON : COLOR_FILL_OFF);
    canvas.drawRect(rect, paint);

    // ② 选中时先画一圈半透明粗括号当辉光
    if (selected) {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(3.5f));
      paint.setColor(COLOR_GLOW);
      drawCorners(canvas, rect);
    }

    // ③ 极淡边框
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(1f));
    paint.setColor(selected ? COLOR_BORDER_ON : COLOR_BORDER_OFF);
    canvas.drawRect(rect, paint);

    // ④ 四角括号
    paint.setStrokeWidth(dp(1.6f));
    paint.setColor(selected ? COLOR_BRACKET_ON : COLOR_BRACKET_OFF);
    drawCorners(canvas, rect);
  }

  /** 四个 L 形（左上、右上、左下、右下）。 */
  private void drawCorners(Canvas canvas, RectF r) {
    float len = Math.min(dp(7f), Math.min(r.width(), r.height()) * 0.34f);
    canvas.drawLine(r.left, r.top, r.left + len, r.top, paint);
    canvas.drawLine(r.left, r.top, r.left, r.top + len, paint);
    canvas.drawLine(r.right, r.top, r.right - len, r.top, paint);
    canvas.drawLine(r.right, r.top, r.right, r.top + len, paint);
    canvas.drawLine(r.left, r.bottom, r.left + len, r.bottom, paint);
    canvas.drawLine(r.left, r.bottom, r.left, r.bottom - len, paint);
    canvas.drawLine(r.right, r.bottom, r.right - len, r.bottom, paint);
    canvas.drawLine(r.right, r.bottom, r.right, r.bottom - len, paint);
  }

  private float dp(float value) {
    return value * density;
  }

  @Override
  public void setAlpha(int alpha) {
    paint.setAlpha(alpha);
  }

  @Override
  public void setColorFilter(ColorFilter colorFilter) {
    paint.setColorFilter(colorFilter);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
