package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 横屏 DSH 显示的运行时口子：让 App 自己的终端/agent 改横屏外观，不用重打包。
 *
 * <p>文件位置（优先 /sdcard，其次 App 自己的 rootfs）：
 * <ul>
 *   <li>Download/DSHA/dsh-a-landscape.json —— 默认档 / 胶囊 / 控件 / UA / 手势锁</li>
 *   <li>Download/DSHA/dsh-a-landscape.css —— 页面样式注入</li>
 *   <li>Download/DSHA/dsh-a-landscape.js —— 页面脚本注入</li>
 * </ul>
 *
 * <p>改完回到横屏显示点胶囊「↻」（或重开横屏）即重新读取；文件不存在时全部走默认值。
 */
final class LandscapeConfig {
  private static final String BASE = "dsh-a-landscape";
  private static final int MAX_BYTES = 256 * 1024;

  static final String CAPSULE_RIGHT = "right";
  static final String CAPSULE_LEFT = "left";
  static final String CAPSULE_HIDE = "hide";

  final int defaultMode;
  final String capsule;
  final boolean chrome;
  final boolean desktopUa;
  final boolean scrollLock;
  private final File css;
  private final File js;

  private LandscapeConfig(
      int defaultMode,
      String capsule,
      boolean chrome,
      boolean desktopUa,
      boolean scrollLock,
      File css,
      File js) {
    this.defaultMode = defaultMode;
    this.capsule = capsule;
    this.chrome = chrome;
    this.desktopUa = desktopUa;
    this.scrollLock = scrollLock;
    this.css = css;
    this.js = js;
  }

  static LandscapeConfig load(Context context) {
    File directory = directory(context);
    int defaultMode = 0;
    String capsule = CAPSULE_RIGHT;
    boolean chrome = true;
    boolean desktopUa = true;
    boolean scrollLock = true;
    String text = read(new File(directory, BASE + ".json"));
    if (!text.isEmpty()) {
      try {
        JSONObject value = new JSONObject(text);
        defaultMode = Math.max(0, Math.min(2, value.optInt("defaultMode", 0)));
        String requested = value.optString("capsule", CAPSULE_RIGHT);
        if (CAPSULE_LEFT.equals(requested)
            || CAPSULE_HIDE.equals(requested)
            || CAPSULE_RIGHT.equals(requested)) capsule = requested;
        chrome = value.optBoolean("chrome", true);
        desktopUa = value.optBoolean("desktopUa", true);
        scrollLock = value.optBoolean("scrollLock", true);
      } catch (Exception ignored) {
        // 配置写坏了就退回默认值，不让横屏显示起不来。
      }
    }
    return new LandscapeConfig(
        defaultMode,
        capsule,
        chrome,
        desktopUa,
        scrollLock,
        new File(directory, BASE + ".css"),
        new File(directory, BASE + ".js"));
  }

  String css() {
    return read(css);
  }

  String js() {
    return read(js);
  }

  private static File directory(Context context) {
    File sdcard = new File(Environment.getExternalStorageDirectory(), "Download/DSHA");
    if (new File(sdcard, BASE + ".json").isFile()) return sdcard;
    File guest = new File(context.getFilesDir(), "linux/ubuntu/root/.dsh");
    if (new File(guest, BASE + ".json").isFile()) return guest;
    return sdcard;
  }

  private static String read(File file) {
    try {
      if (file == null || !file.isFile() || file.length() <= 0 || file.length() > MAX_BYTES)
        return "";
      byte[] bytes = Files.readAllBytes(file.toPath());
      if (bytes.length > MAX_BYTES) return "";
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      return "";
    }
  }
}
