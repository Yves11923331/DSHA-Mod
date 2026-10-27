package com.deepseekharness.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.R;

/**
 * 外观背景：只作用在 DSHA-Mod 的原生界面。
 *
 * <p>207 按用户要求砍到最小完备集，页面上只有三件事：
 * <b>选一张图（自定义）· 调遮罩 · 清除回默认</b>。
 * 预设（深色/暖色/透明）、品红测试、链路自检、重新应用、路径展示全部移除；
 * 排障信息仍由 {@link DshAppearance#apply} 自动写到
 * {@code /sdcard/Download/DSHA/appearance-apply.txt}，不再占用界面。
 */
public class AppearanceActivity extends AppCompatActivity {
  private final ActivityResultLauncher<String> picker =
      registerForActivityResult(new ActivityResultContracts.GetContent(), this::onPicked);
  /** GetContent 在个别 ROM 上打不开相册，保留一条系统文件选择器兜底（界面上不单列按钮）。 */
  private final ActivityResultLauncher<String[]> docPicker =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri != null) onPicked(uri);
            else toast("没有选图");
          });
  /** 选图后进裁剪页；裁剪页自己写图与配置，这里只负责刷新。 */
  private final ActivityResultLauncher<Intent> cropLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (result.getResultCode() == RESULT_OK) {
              toast("图片已应用");
              recreate();
            }
          });

  private FrameLayout shell;
  private TextView status;
  private TextView scrimLabel;
  private SeekBar scrimBar;
  /** 210：横屏 DSH 舞台壁纸（独立配置段 landscape）。 */
  private TextView landscapeStatus;
  private TextView landscapeScrimLabel;
  private SeekBar landscapeScrimBar;
  /** 本次选图要写进哪个区域：app = 原生界面，landscape = 横屏舞台。 */
  private String pickArea = "app";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    if (DshAppearance.isTransparent(this, "app"))
      setTheme(R.style.Theme_DeepseekHarness_Translucent);
    super.onCreate(savedInstanceState);

    shell = new FrameLayout(this);
    shell.setId(R.id.app_shell);

    ImageView background = new ImageView(this);
    background.setId(R.id.app_background);
    background.setScaleType(ImageView.ScaleType.CENTER_CROP);
    background.setContentDescription(null);
    background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    shell.addView(
        background,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    View scrim = new View(this);
    scrim.setId(R.id.app_scrim);
    shell.addView(
        scrim,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(20);
    root.setPadding(pad, dp(14), pad, dp(30));
    scroll.addView(root);
    shell.addView(
        scroll,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    setContentView(shell);
    DshAppearance.applyEdgeToEdge(this);
    insetScroll(scroll);

    Button close = action("← 返回", R.drawable.bg_btn_double, v -> finish());
    close.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    root.addView(close, new LinearLayout.LayoutParams(-1, -2));

    TextView title = new TextView(this);
    title.setText("外观背景");
    title.setTextSize(24);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setTextColor(getColor(R.color.text));
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
    tlp.topMargin = dp(10);
    root.addView(title, tlp);

    TextView sub = new TextView(this);
    sub.setText(
        "竖屏原生界面（启动页/设置/插件/数据/终端）与横屏 DSH 舞台各一张，可分开设置；"
            + "竖屏 DSH 网页不铺壁纸。");
    sub.setTextSize(12);
    sub.setTextColor(getColor(R.color.text_secondary));
    sub.setLineSpacing(0, 1.3f);
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
    slp.topMargin = dp(6);
    root.addView(sub, slp);

    status = new TextView(this);
    status.setTextSize(13);
    status.setTextColor(getColor(R.color.text));
    status.setLineSpacing(0, 1.3f);
    LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
    stLp.topMargin = dp(16);
    root.addView(status, stLp);

    // ① 自定义：选一张图
    Button upload = action("选一张图…", R.drawable.bg_btn_primary, v -> launchPicker("app"));
    upload.setTextColor(getColor(R.color.button_primary_text));
    root.addView(upload);

    // ② 遮罩
    scrimLabel = new TextView(this);
    scrimLabel.setTextSize(13);
    scrimLabel.setTextColor(getColor(R.color.text));
    LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, -2);
    scLp.topMargin = dp(22);
    root.addView(scrimLabel, scLp);

    scrimBar = new SeekBar(this);
    scrimBar.setMax(90);
    scrimBar.setProgress(Math.round(DshAppearance.currentScrim(this) * 100f));
    scrimBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            scrimLabel.setText("遮罩：" + progress + "%");
          }

          @Override
          public void onStartTrackingTouch(SeekBar bar) {}

          @Override
          public void onStopTrackingTouch(SeekBar bar) {
            DshAppearance.setScrim(AppearanceActivity.this, bar.getProgress() / 100f);
            DshAppearance.apply(AppearanceActivity.this, "app", null);
          }
        });
    root.addView(scrimBar, new LinearLayout.LayoutParams(-1, -2));

    // ③ 清除：回默认
    root.addView(
        action(
            "清除，回到默认",
            R.drawable.bg_btn_double,
            v -> {
              DshAppearance.clearImage(this);
              recreate();
            }));

    // ④ 横屏壁纸：DSH 横屏舞台专用，和上面的竖屏壁纸完全分开
    TextView section = new TextView(this);
    section.setText("横屏壁纸（DSH 横屏舞台）");
    section.setTextSize(16);
    section.setTypeface(null, android.graphics.Typeface.BOLD);
    section.setTextColor(getColor(R.color.text));
    LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(-1, -2);
    secLp.topMargin = dp(26);
    root.addView(section, secLp);

    TextView sectionHint = new TextView(this);
    sectionHint.setText("只铺在横屏 16:9 视口外那圈背景上；不选就用内置的深色科幻壁纸。");
    sectionHint.setTextSize(12);
    sectionHint.setTextColor(getColor(R.color.text_secondary));
    sectionHint.setLineSpacing(0, 1.3f);
    LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(-1, -2);
    shLp.topMargin = dp(6);
    root.addView(sectionHint, shLp);

    landscapeStatus = new TextView(this);
    landscapeStatus.setTextSize(13);
    landscapeStatus.setTextColor(getColor(R.color.text));
    landscapeStatus.setLineSpacing(0, 1.3f);
    LinearLayout.LayoutParams lsLp = new LinearLayout.LayoutParams(-1, -2);
    lsLp.topMargin = dp(12);
    root.addView(landscapeStatus, lsLp);

    Button uploadLandscape =
        action("选一张横屏图…", R.drawable.bg_btn_primary, v -> launchPicker("landscape"));
    uploadLandscape.setTextColor(getColor(R.color.button_primary_text));
    root.addView(uploadLandscape);

    landscapeScrimLabel = new TextView(this);
    landscapeScrimLabel.setTextSize(13);
    landscapeScrimLabel.setTextColor(getColor(R.color.text));
    LinearLayout.LayoutParams lscLp = new LinearLayout.LayoutParams(-1, -2);
    lscLp.topMargin = dp(18);
    root.addView(landscapeScrimLabel, lscLp);

    landscapeScrimBar = new SeekBar(this);
    landscapeScrimBar.setMax(90);
    landscapeScrimBar.setProgress(
        Math.round(DshAppearance.currentLandscapeScrim(this) * 100f));
    landscapeScrimBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            landscapeScrimLabel.setText("横屏遮罩：" + progress + "%");
          }

          @Override
          public void onStartTrackingTouch(SeekBar bar) {}

          @Override
          public void onStopTrackingTouch(SeekBar bar) {
            DshAppearance.setScrimArea(
                AppearanceActivity.this, "landscape", bar.getProgress() / 100f);
          }
        });
    root.addView(landscapeScrimBar, new LinearLayout.LayoutParams(-1, -2));

    root.addView(
        action(
            "清除横屏壁纸",
            R.drawable.bg_btn_double,
            v -> {
              DshAppearance.clearImageArea(this, "landscape");
              recreate();
            }));

    refreshSummary();
    DshAppearance.apply(this, "app", null);
  }

  @Override
  protected void onResume() {
    super.onResume();
    DshAppearance.apply(this, "app", null);
    DshAppearance.applyStatusBarVisibility(this);
    refreshSummary();
  }

  /** 外观页是自绘页面，没有 app_bar/bottom_nav，直接给滚动层补系统栏留白。 */
  private void insetScroll(View scroll) {
    final int baseTop = scroll.getPaddingTop();
    final int baseBottom = scroll.getPaddingBottom();
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        scroll,
        (v, insets) -> {
          androidx.core.graphics.Insets bars =
              insets.getInsets(
                  androidx.core.view.WindowInsetsCompat.Type.systemBars()
                      | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
          v.setPadding(
              v.getPaddingLeft(), baseTop + bars.top, v.getPaddingRight(), baseBottom + bars.bottom);
          return insets;
        });
  }

  private void launchPicker(String area) {
    pickArea = area == null ? "app" : area;
    status.setText("正在打开相册…");
    try {
      picker.launch("image/*");
    } catch (Throwable error) {
      try {
        docPicker.launch(new String[] {"image/*"});
      } catch (Throwable again) {
        new DshaDialogBuilder(this)
            .setTitle("相册打不开")
            .setMessage("两个选择器都没能启动：" + again.getClass().getSimpleName())
            .setPositiveButton("知道了", null)
            .show();
      }
    }
  }

  private void refreshSummary() {
    String now = DshAppearance.presetLabel(this);
    if (status != null) {
      if (DshAppearance.read(this).missing) {
        status.setText("图片文件已不在（可能被清理或换机丢失），请重新选一张。");
      } else if ("默认".equals(now)) {
        status.setText("当前：无壁纸。");
      } else {
        status.setText("当前：" + now);
      }
    }
    boolean image = DshAppearance.read(this).isImage();
    if (scrimLabel != null)
      scrimLabel.setText("遮罩：" + Math.round(DshAppearance.currentScrim(this) * 100f) + "%");
    if (scrimBar != null) {
      scrimBar.setEnabled(image);
      scrimBar.setAlpha(image ? 1f : 0.45f);
    }

    DshAppearance.Spec landscape = DshAppearance.readLandscape(this);
    if (landscapeStatus != null) {
      if (landscape.missing) {
        landscapeStatus.setText("横屏图文件已不在，请重新选一张。");
      } else if (landscape.isImage()) {
        landscapeStatus.setText(
            "横屏：已设置（遮罩 "
                + Math.round(DshAppearance.currentLandscapeScrim(this) * 100f)
                + "%）");
      } else {
        landscapeStatus.setText("横屏：使用内置科幻壁纸（可换成自己的图）。");
      }
    }
    if (landscapeScrimLabel != null)
      landscapeScrimLabel.setText(
          "横屏遮罩：" + Math.round(DshAppearance.currentLandscapeScrim(this) * 100f) + "%");
    if (landscapeScrimBar != null) {
      landscapeScrimBar.setEnabled(landscape.isImage());
      landscapeScrimBar.setAlpha(landscape.isImage() ? 1f : 0.45f);
    }
  }

  private void onPicked(Uri uri) {
    if (uri == null) {
      toast("没有选图");
      return;
    }
    // 先复制到缓存再进裁剪页：跨 Activity 传文件路径比传 content:// 稳。
    java.io.File picked = DshAppearance.copyPickToCache(this, uri);
    if (picked != null) {
      try {
        Intent intent = new Intent(this, ImageCropActivity.class);
        intent.putExtra(ImageCropActivity.EXTRA_PATH, picked.getAbsolutePath());
        intent.putExtra(ImageCropActivity.EXTRA_AREA, pickArea);
        cropLauncher.launch(intent);
        return;
      } catch (Throwable ignored) {
        // 裁剪页起不来就退回「直接导入」，不能让选图整个失败。
      }
    }
    DshAppearance.ImportResult result =
        DshAppearance.importImageDetailedArea(this, uri, pickArea);
    if (result.ok) {
      toast("图片已应用");
      recreate();
    } else {
      new DshaDialogBuilder(this)
          .setTitle("选图没成功")
          .setMessage(result.message)
          .setPositiveButton("知道了", null)
          .show();
    }
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
  }

  private Button action(String text, int background, View.OnClickListener click) {
    Button button = new Button(this);
    button.setText(text);
    button.setTextSize(14);
    button.setAllCaps(false);
    button.setTextColor(getColor(R.color.text));
    button.setBackgroundResource(background);
    button.setGravity(Gravity.CENTER);
    button.setMinHeight(dp(46));
    button.setOnClickListener(click);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(8);
    button.setLayoutParams(lp);
    return button;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
