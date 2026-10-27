package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 215+「页面度量」探针：把当前页面的 View 树摊平成纯数据 JSON，写进
 * {@code /sdcard/Download/DSHA/ui-probe/}，供外部（agent / 调试脚本）读取。
 *
 * <p><b>为什么不是截图</b>：截图只能看到「像不像」，看不到「到底是几 dp」。
 * 这个项目 214~216 反复踩的就是这件事 —— 看不到真机，只能拿 HTML 复刻猜设计值，
 * 装上去才发现 padding 差 2dp、字号被字体缩放放大、控件被裁掉。View 树是 App 自己
 * 的内存结构，遍历它不需要任何权限、不依赖无障碍、也不需要 ADB，拿到的还全是精确数字。
 *
 * <p><b>开关</b>：设置 → 体验 → 页面度量，默认关；关着时连 callbacks 里的判断都只是
 * 一次 SharedPreferences 读，不遍历、不写盘。
 *
 * <p><b>采集口径</b>
 * <ul>
 *   <li>几何：屏幕坐标 px + dp（dp 按 density 换算，圆形误差可忽略）；
 *   <li>盒模型：padding / margin(LayoutParams) / 尺寸规格；
 *   <li>文字：textSize(px+sp)、颜色、行高、行数、省略数（判断"有没有被截断"）；
 *   <li>状态：visibility / alpha / enabled / selected / clickable / elevation；
 *   <li>背景：ColorDrawable / GradientDrawable 能读到颜色、圆角、描边，其余只记类型；
 *   <li>环境：屏幕尺寸、density、fontScale、insets、深浅色、是否 edge-to-edge。
 * </ul>
 *
 * <p><b>隐私</b>：密码框一律 {@code [password]}；普通文本超过 {@link #TEXT_LIMIT} 字符截断并附原长度。
 * 这里只记录本 App 自己的窗口，不碰其它应用。
 */
public final class UiProbe {

  private static final String PREFS = "ui_probe";
  private static final String KEY_ENABLED = "enabled";
  private static final String TAG = "DSHA_UI_PROBE";

  /** 落盘目录：和 appearance-apply.txt 同一处，App 有 MANAGE_EXTERNAL_STORAGE。 */
  private static final String DIR = "/sdcard/Download/DSHA/ui-probe/";
  private static final String LATEST = "latest.json";
  private static final int KEEP_FILES = 12;
  /** 整个快照目录的总大小上限：超了就从最旧的删起（份数管不住单份很大的情况）。 */
  private static final long KEEP_TOTAL_BYTES = 8L * 1024L * 1024L;

  /** 防止超大页面把 JSON 撑爆：深度、节点数、单页字节都有上限。 */
  private static final int MAX_DEPTH = 24;
  private static final int MAX_NODES = 1500;
  private static final int MAX_BYTES = 2 * 1024 * 1024;

  private static final int TEXT_LIMIT = 60;

  /** 同一页面 600ms 内只抓一次：切页/旋转/多回调时不重复写盘。 */
  private static final long THROTTLE_MS = 600;
  /** onResume 后再等一帧多一点，让布局/动画落定。 */
  private static final long SETTLE_MS = 220;

  private static long lastCaptureAt;
  private static String lastCaptureKey = "";
  private static boolean installed;

  private UiProbe() {}

  // ------------------------------------------------------------------ 开关

  public static boolean enabled(Context context) {
    try {
      return context
          .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .getBoolean(KEY_ENABLED, false);
    } catch (Throwable error) {
      return false;
    }
  }

  public static void setEnabled(Context context, boolean on) {
    try {
      context
          .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .edit()
          .putBoolean(KEY_ENABLED, on)
          .commit();
    } catch (Throwable ignored) {
    }
  }

  // ------------------------------------------------------------------ 挂载

  /**
   * 注册一次全局回调：任何 Activity resume 时（开关打开）抓一份。
   *
   * <p>做成 Application 级而不是往每个页面里塞代码 —— 以后新增页面自动就有，不会漏。
   */
  public static synchronized void install(Application application) {
    if (installed || application == null) return;
    installed = true;
    application.registerActivityLifecycleCallbacks(
        new Application.ActivityLifecycleCallbacks() {
          @Override
          public void onActivityResumed(Activity activity) {
            if (!enabled(activity)) return;
            schedule(activity, "resume");
          }

          @Override
          public void onActivityCreated(Activity activity, Bundle saved) {}

          @Override
          public void onActivityStarted(Activity activity) {}

          @Override
          public void onActivityPaused(Activity activity) {}

          @Override
          public void onActivityStopped(Activity activity) {}

          @Override
          public void onActivitySaveInstanceState(Activity activity, Bundle out) {}

          @Override
          public void onActivityDestroyed(Activity activity) {}
        });
  }

  /** 手动抓一次（调试入口用；不受节流影响）。 */
  public static void captureNow(Activity activity, String reason) {
    if (activity == null) return;
    lastCaptureAt = 0;
    schedule(activity, reason == null ? "manual" : reason);
  }

  private static void schedule(Activity activity, String reason) {
    if (activity.isFinishing() || activity.isDestroyed()) return;
    View decor = activity.getWindow().getDecorView();
    if (decor == null) return;
    decor.postDelayed(
        () -> {
          try {
            capture(activity, reason);
          } catch (Throwable error) {
            android.util.Log.w(TAG, "capture failed: " + error.getClass().getSimpleName());
          }
        },
        SETTLE_MS);
  }

  // ------------------------------------------------------------------ 采集

  private static void capture(Activity activity, String reason) {
    if (activity.isFinishing() || activity.isDestroyed()) return;
    View decor = activity.getWindow().getDecorView();
    if (decor == null) return;

    long now = SystemClock.elapsedRealtime();
    String key = activity.getClass().getName();
    if (key.equals(lastCaptureKey) && now - lastCaptureAt < THROTTLE_MS) return;
    lastCaptureKey = key;
    lastCaptureAt = now;

    long started = System.nanoTime();
    JSONObject root = new JSONObject();
    try {
      JSONObject env = environment(activity, decor);
      Counter counter = new Counter();
      JSONObject tree = node(decor, "0", 0, decor, counter);
      root.put("schema", "dsha-ui-probe/1");
      root.put("capturedAt", stamp());
      root.put("reason", reason);
      root.put("activity", activity.getClass().getName());
      root.put("page", activity.getClass().getSimpleName());
      root.put("env", env);
      root.put("tree", tree);
      JSONObject stats = new JSONObject();
      stats.put("nodes", counter.visited);
      stats.put("kept", counter.kept);
      stats.put("skipped", counter.skipped);
      stats.put("truncated", counter.truncated);
      stats.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
      root.put("stats", stats);
    } catch (Throwable error) {
      try {
        root.put("error", error.getClass().getSimpleName());
      } catch (Throwable ignored) {
      }
    }
    persist(root, activity.getClass().getSimpleName());
  }

  /** 屏幕/密度/字体缩放/insets —— 判断布局是否被环境改形，全靠这一段。 */
  private static JSONObject environment(Activity activity, View decor) throws Exception {
    android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
    android.graphics.Point real = new android.graphics.Point();
    if (android.os.Build.VERSION.SDK_INT >= 30) {
      activity.getDisplay().getRealSize(real);
    } else {
      activity.getWindowManager().getDefaultDisplay().getRealSize(real);
    }
    JSONObject env = new JSONObject();
    env.put("sdk", android.os.Build.VERSION.SDK_INT);
    env.put("screenPx", new JSONArray().put(real.x).put(real.y));
    env.put("screenDp", new JSONArray().put(dp(real.x, metrics)).put(dp(real.y, metrics)));
    env.put("density", round(metrics.density));
    env.put("densityDpi", metrics.densityDpi);
    env.put("fontScale", round(activity.getResources().getConfiguration().fontScale));
    env.put(
        "night",
        (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES);
    env.put(
        "orientation",
        activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ? "landscape"
            : "portrait");
    env.put("windowPx", new JSONArray().put(decor.getWidth()).put(decor.getHeight()));
    env.put("windowDp", new JSONArray().put(dp(decor.getWidth(), metrics)).put(dp(decor.getHeight(), metrics)));

    JSONObject insets = new JSONObject();
    try {
      WindowInsets wi = decor.getRootWindowInsets();
      if (wi != null) {
        insets.put("statusBar", wi.getInsets(WindowInsets.Type.statusBars()).bottom);
        insets.put("navigationBar", wi.getInsets(WindowInsets.Type.navigationBars()).bottom);
        insets.put("ime", wi.getInsets(WindowInsets.Type.ime()).bottom);
        insets.put("displayCutout", wi.getInsets(WindowInsets.Type.displayCutout()).top);
      }
    } catch (Throwable ignored) {
    }
    env.put("insetsPx", insets);
    return env;
  }

  /** 递归把一棵 View 树摊平成 JSON。 */
  private static JSONObject node(
      View view, String path, int depth, View decor, Counter counter) throws Exception {
    if (view == null) return null;
    if (counter.visited++ > MAX_NODES) {
      counter.truncated = true;
      return null;
    }

    boolean visible = view.getVisibility() == View.VISIBLE;
    int width = view.getWidth();
    int height = view.getHeight();
    // 折叠掉「不显示且没有尺寸」的占位节点：它们在真机上是噪音，在排查里基本没用。
    if (!visible && width <= 0 && height <= 0 && depth > 0) {
      counter.skipped++;
      return null;
    }

    android.util.DisplayMetrics metrics = decor.getResources().getDisplayMetrics();
    int[] loc = new int[2];
    view.getLocationOnScreen(loc);

    JSONObject out = new JSONObject();
    out.put("p", path);
    out.put("cls", simpleName(view.getClass().getName()));
    String idName = idName(view);
    if (idName != null) out.put("id", idName);

    out.put("b", ints(loc[0], loc[1], loc[0] + width, loc[1] + height));
    out.put(
        "d",
        new JSONArray()
            .put(dp(loc[0], metrics))
            .put(dp(loc[1], metrics))
            .put(dp(loc[0] + width, metrics))
            .put(dp(loc[1] + height, metrics)));
    out.put("s", ints(width, height));
    out.put("sd", new JSONArray().put(dp(width, metrics)).put(dp(height, metrics)));

    JSONArray padding =
        ints(
            view.getPaddingLeft(),
            view.getPaddingTop(),
            view.getPaddingRight(),
            view.getPaddingBottom());
    if (hasAny(padding)) out.put("pad", padding);

    ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp != null) {
      out.put("lp", new JSONArray().put(spec(lp.width)).put(spec(lp.height)));
      if (lp instanceof ViewGroup.MarginLayoutParams margin) {
        JSONArray m =
            ints(margin.leftMargin, margin.topMargin, margin.rightMargin, margin.bottomMargin);
        if (hasAny(m)) out.put("mar", m);
        if (margin instanceof android.widget.LinearLayout.LayoutParams linear && linear.weight != 0)
          out.put("weight", round(linear.weight));
      }
    }

    out.put("vis", view.getVisibility() == View.VISIBLE ? "V" : view.getVisibility() == View.INVISIBLE ? "IN" : "G");
    if (view.getAlpha() != 1f) out.put("alpha", round(view.getAlpha()));
    if (!view.isEnabled()) out.put("en", false);
    if (view.isSelected()) out.put("sel", true);
    if (view.isClickable()) out.put("clk", true);
    if (view.getElevation() != 0f) out.put("elev", round(dp((int) view.getElevation(), metrics)));

    int scrollX = view.getScrollX();
    int scrollY = view.getScrollY();
    if (scrollX != 0 || scrollY != 0) out.put("scroll", ints(scrollX, scrollY));

    Drawable background = view.getBackground();
    if (background != null) out.put("bg", describeDrawable(background, metrics));

    if (view instanceof TextView text) fillText(out, text, metrics);
    if (view instanceof ImageView image) fillImage(out, image);

    // 是否落在窗口可见区里：被裁掉 / 完全在屏外时，肉眼看的"没显示"就有了数字解释。
    out.put(
        "inView",
        visible && width > 0 && height > 0 && loc[0] < decor.getWidth() && loc[1] < decor.getHeight()
            && loc[0] + width > 0 && loc[1] + height > 0);

    counter.kept++;

    if (view instanceof ViewGroup group && depth < MAX_DEPTH) {
      JSONArray children = new JSONArray();
      for (int i = 0; i < group.getChildCount(); i++) {
        JSONObject child = node(group.getChildAt(i), path + "/" + i, depth + 1, decor, counter);
        if (child != null) children.put(child);
      }
      if (children.length() > 0) out.put("c", children);
    } else if (view instanceof ViewGroup) {
      counter.truncated = true;
    }
    return out;
  }

  /** TextView：字号/颜色/行高/行数/省略数，这四项能诊断「字是不是被换行截断」。 */
  private static void fillText(JSONObject out, TextView text, android.util.DisplayMetrics metrics)
      throws Exception {
    CharSequence value = text.getText();
    if (value != null && value.length() > 0) out.put("txt", safeText(text, value));

    JSONObject size = new JSONObject();
    size.put("px", round(text.getTextSize()));
    size.put("sp", round(text.getTextSize() / metrics.scaledDensity));
    out.put("ts", size);
    out.put("tc", color(text.getCurrentTextColor()));
    out.put("lh", round(dp((int) text.getLineHeight(), metrics)));

    int lines = text.getLineCount();
    if (lines > 0) out.put("lines", lines);
    try {
      android.text.Layout layout = text.getLayout();
      if (layout != null && layout.getLineCount() > 0) {
        int ellipsis = layout.getEllipsisCount(0);
        if (ellipsis > 0) out.put("ell", ellipsis);
      }
    } catch (Throwable ignored) {
    }
    if (text.getMaxLines() > 0) out.put("maxLines", text.getMaxLines());
    if (text.getGravity() != 0) out.put("gravity", gravityName(text.getGravity()));
    if (text.getTypeface() != null && text.getTypeface().isBold()) out.put("bold", true);
  }

  private static void fillImage(JSONObject out, ImageView image) throws Exception {
    out.put(
        "scale",
        switch (image.getScaleType()) {
          case CENTER -> "center";
          case CENTER_CROP -> "crop";
          case CENTER_INSIDE -> "inside";
          case FIT_CENTER -> "fit";
          case FIT_XY -> "fitXY";
          case MATRIX -> "matrix";
          default -> "?";
        });
    Drawable drawable = image.getDrawable();
    if (drawable != null) {
      JSONObject src = new JSONObject();
      src.put("type", simpleName(drawable.getClass().getName()));
      android.graphics.Rect bounds = drawable.getBounds();
      if (bounds.width() > 0)
        src.put("bounds", ints(bounds.left, bounds.top, bounds.right, bounds.bottom));
      out.put("src", src);
    }
  }

  /** 背景：能读多少读多少，读不出来的老实记类型。 */
  private static JSONObject describeDrawable(Drawable drawable, android.util.DisplayMetrics metrics)
      throws Exception {
    JSONObject bg = new JSONObject();
    bg.put("type", simpleName(drawable.getClass().getName()));
    if (drawable instanceof ColorDrawable colorDrawable) {
      bg.put("color", color(colorDrawable.getColor()));
      return bg;
    }
    if (drawable instanceof GradientDrawable gradient) {
      try {
        android.content.res.ColorStateList fill = gradient.getColor();
        if (fill != null) bg.put("color", color(fill.getDefaultColor()));
      } catch (Throwable ignored) {
      }
      try {
        float radius = gradient.getCornerRadius();
        if (radius > 0) bg.put("cornerDp", round(dp((int) radius, metrics)));
      } catch (Throwable ignored) {
      }
      // 描边宽/色不是公开 API（getStrokeWidth/getStrokeColor 不在 android.jar 里），
      // 只能反射读私有字段；Android 12+ 的 hidden-API 限制可能拒绝，失败就只留颜色+圆角。
      try {
        java.lang.reflect.Field widthField = GradientDrawable.class.getDeclaredField("mStrokeWidth");
        widthField.setAccessible(true);
        int strokeWidth = widthField.getInt(gradient);
        if (strokeWidth > 0) {
          JSONObject stroke = new JSONObject();
          stroke.put("widthDp", round(dp(strokeWidth, metrics)));
          try {
            java.lang.reflect.Field paintField = GradientDrawable.class.getDeclaredField("mStrokePaint");
            paintField.setAccessible(true);
            android.graphics.Paint paint = (android.graphics.Paint) paintField.get(gradient);
            if (paint != null) stroke.put("color", color(paint.getColor()));
          } catch (Throwable ignoredPaint) {
          }
          bg.put("stroke", stroke);
        }
      } catch (Throwable ignoredStroke) {
      }
      return bg;
    }
    if (drawable instanceof android.graphics.drawable.RippleDrawable ripple) {
      // 涟漪只是交互反馈；把它里面那层真实背景也摊出来，否则"白卡"会只显示 RippleDrawable。
      try {
        Drawable inner = ripple.getDrawable(0);
        if (inner != null) bg.put("inner", describeDrawable(inner, metrics));
      } catch (Throwable ignored) {
      }
    }
    return bg;
  }

  // ------------------------------------------------------------------ 落盘

  private static void persist(JSONObject root, String page) {
    final String json;
    try {
      json = root.toString();
    } catch (Throwable error) {
      return;
    }
    if (json.length() > MAX_BYTES) return;
    final String safePage = page == null || page.isEmpty() ? "page" : page;
    final String name =
        new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date())
            + "-"
            + safePage
            + ".json";
    new Thread(
            () -> {
              try {
                File dir = new File(DIR);
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                write(new File(dir, name), json);
                write(new File(dir, LATEST), json);
                prune(dir);
              } catch (Throwable error) {
                android.util.Log.w(TAG, "persist failed: " + error.getClass().getSimpleName());
              }
            },
            "ui-probe")
        .start();
  }

  private static void write(File file, String json) throws Exception {
    try (FileOutputStream out = new FileOutputStream(file)) {
      out.write(json.getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }

  /**
   * 只留最近 KEEP_FILES 份快照，且整个目录不超过 KEEP_TOTAL_BYTES；latest.json 常驻。
   *
   * <p>两道限制都要：份数管不住「单份很大」，总大小管不住「份数不多但每份都大」。
   */
  private static void prune(File dir) {
    try {
      File[] files = dir.listFiles((d, n) -> n.endsWith(".json") && !LATEST.equals(n));
      if (files == null || files.length == 0) return;
      List<File> sorted = new ArrayList<>(Arrays.asList(files));
      sorted.sort(Comparator.comparing(File::getName)); // 名字带时间戳，升序=旧→新
      int removeByCount = Math.max(0, sorted.size() - KEEP_FILES);
      for (int i = 0; i < removeByCount; i++) sorted.get(i).delete();
      for (int i = removeByCount; i < sorted.size(); i++) {
        if (totalBytes(dir) <= KEEP_TOTAL_BYTES) break;
        sorted.get(i).delete();
      }
    } catch (Throwable ignored) {
    }
  }

  /** 目录内快照 + latest.json 的当前总字节数。 */
  private static long totalBytes(File dir) {
    long total = 0;
    try {
      File[] all = dir.listFiles((d, n) -> n.endsWith(".json"));
      if (all != null) {
        for (File f : all) total += f.length();
      }
    } catch (Throwable ignored) {
    }
    return total;
  }

  // ------------------------------------------------------------------ 小工具

  private static final class Counter {
    int visited;
    int kept;
    int skipped;
    boolean truncated;
  }

  private static String stamp() {
    return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).format(new Date());
  }

  private static String simpleName(String className) {
    int dot = className.lastIndexOf('.');
    String name = dot >= 0 ? className.substring(dot + 1) : className;
    int dollar = name.lastIndexOf('$');
    return dollar >= 0 ? name.substring(dollar + 1) : name;
  }

  private static String idName(View view) {
    int id = view.getId();
    if (id == View.NO_ID) return null;
    try {
      return view.getResources().getResourceEntryName(id);
    } catch (Throwable error) {
      return null;
    }
  }

  private static String safeText(TextView text, CharSequence value) {
    if (text instanceof EditText edit
        && (edit.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD)
            == InputType.TYPE_TEXT_VARIATION_PASSWORD) return "[password]";
    if ((text.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD)
        == InputType.TYPE_TEXT_VARIATION_PASSWORD) return "[password]";
    String raw = value.toString();
    String flat = raw.replace('\n', '⏎');
    if (TextUtils.isEmpty(flat)) return "";
    if (flat.length() <= TEXT_LIMIT) return flat;
    return flat.substring(0, TEXT_LIMIT) + "…(len=" + flat.length() + ")";
  }

  private static String gravityName(int gravity) {
    List<String> parts = new ArrayList<>();
    int horizontal = gravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
    int vertical = gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK;
    if (horizontal == android.view.Gravity.CENTER_HORIZONTAL) parts.add("ch");
    else if (horizontal == android.view.Gravity.RIGHT) parts.add("r");
    if (vertical == android.view.Gravity.CENTER_VERTICAL) parts.add("cv");
    else if (vertical == android.view.Gravity.BOTTOM) parts.add("b");
    return String.join("|", parts);
  }

  private static String spec(int value) {
    if (value == ViewGroup.LayoutParams.MATCH_PARENT) return "match";
    if (value == ViewGroup.LayoutParams.WRAP_CONTENT) return "wrap";
    return String.valueOf(value);
  }

  private static String color(int argb) {
    return String.format(Locale.ROOT, "#%08X", argb);
  }

  private static JSONArray ints(int... values) {
    JSONArray array = new JSONArray();
    for (int value : values) array.put(value);
    return array;
  }

  private static boolean hasAny(JSONArray array) {
    for (int i = 0; i < array.length(); i++) if (array.optInt(i) != 0) return true;
    return false;
  }

  private static double dp(int px, android.util.DisplayMetrics metrics) {
    if (metrics.density <= 0) return 0;
    return round(px / (double) metrics.density);
  }

  private static double round(double value) {
    return Math.round(value * 100d) / 100d;
  }
}
