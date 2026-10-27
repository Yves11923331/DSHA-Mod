package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 横屏 DSH 显示的纯几何单测：真机 2675×1197 @3.5 下三档的缩放与居中。 */
public class DshStageGeometryTest {
  private static final float DENSITY = 3.5f;
  private static final int AREA_W = 2675;
  private static final int AREA_H = 1197;

  @Test
  public void modeOneFillsSixteenByNineArea() {
    assertEquals(4480, DshStageGeometry.layoutWidth(0, DENSITY));
    assertEquals(2520, DshStageGeometry.layoutHeight(0, DENSITY));
    assertEquals(0.475f, DshStageGeometry.scale(0, DENSITY, AREA_W, AREA_H), 0.0005f);
    int[] offset = DshStageGeometry.centerOffset(0, DENSITY, AREA_W, AREA_H);
    // 2128 宽居中 → 左右各 273.5，取整 273 / 274
    assertEquals(273, offset[0]);
    assertEquals(0, offset[1]);
    assertEquals(1.0f, DshStageGeometry.textScale(0), 0.0001f);
  }

  @Test
  public void modeTwoFillsAreaWithBiggerText() {
    assertEquals(3360, DshStageGeometry.layoutWidth(1, DENSITY));
    assertEquals(1890, DshStageGeometry.layoutHeight(1, DENSITY));
    assertEquals(0.6333f, DshStageGeometry.scale(1, DENSITY, AREA_W, AREA_H), 0.001f);
    int[] offset = DshStageGeometry.centerOffset(1, DENSITY, AREA_W, AREA_H);
    assertEquals(273, offset[0]);
    assertEquals(0, offset[1]);
    assertEquals(1.3333f, DshStageGeometry.textScale(1), 0.001f);
  }

  @Test
  public void modeThreeKeepsFourByThreeInsideSixteenByNine() {
    assertEquals(3360, DshStageGeometry.layoutWidth(2, DENSITY));
    assertEquals(2520, DshStageGeometry.layoutHeight(2, DENSITY));
    assertEquals(0.475f, DshStageGeometry.scale(2, DENSITY, AREA_W, AREA_H), 0.0005f);
    int[] offset = DshStageGeometry.centerOffset(2, DENSITY, AREA_W, AREA_H);
    // 1596 宽居中 → 左右各 539.5，取整 539 / 540
    assertEquals(539, offset[0]);
    assertEquals(0, offset[1]);
    assertEquals(1.0f, DshStageGeometry.textScale(2), 0.0001f);
  }

  @Test
  public void invalidInputsDoNotDivideByZero() {
    assertEquals(0f, DshStageGeometry.scale(0, 0f, AREA_W, AREA_H), 0f);
    assertEquals(0f, DshStageGeometry.scale(0, DENSITY, 0, AREA_H), 0f);
    assertEquals(0f, DshStageGeometry.scale(0, DENSITY, AREA_W, 0), 0f);
    assertEquals(0, DshStageGeometry.clampMode(-5));
    assertEquals(2, DshStageGeometry.clampMode(99));
  }
}
