package com.deepseekharness.app.ui;

import android.content.Context;

/**
 * 217「改版说明与声明」——DSHA-Mod 这个非官方共存改版是什么、基于什么、改了什么、免责边界。
 *
 * <p><b>放在哪、为什么</b>：不进首页、不弹启动提示，只挂在两个「关于」入口里
 * （顶栏 → 关于对话框、设置 → 关于页）。理由是这类文字的信息价值只在「有人专门找」时才生效，
 * 平时占界面就是噪音；但完全不写又会让第三方分不清这是官方包还是改版包 —— 尤其它和官方版
 * 包名不同、签名不同、却能并排安装。
 *
 * <p>所以三层：应用内两处一句话入口（本类）→ 完整声明（就是这里的 {@link #TEXT}）→
 * 源码根目录的 {@code NOTICE-改版说明.md}（给接手的人和分发方看）。
 */
public final class ModNotice {

  private ModNotice() {}

  public static final String TITLE = "改版说明与声明";

  public static final String TEXT =
      "DSHA-Mod 是 DeepSeek Harness Android（DSHA）的非官方共存改版。\n"
          + "\n"
          + "【这是什么】\n"
          + "包名 dsh.android.client.coexist，与官方版 dsh.android.client 的数据完全独立，可同时安装。\n"
          + "本改版由个人维护，与 DSHA 上游及其作者没有隶属关系，也不代表上游立场。\n"
          + "\n"
          + "【基于什么】\n"
          + "上游项目：DSH-APP/DSHA（MIT License，Copyright © 2026 qiannianhuanxiang）。\n"
          + "上游的版权与许可声明完整保留；本改版对上游代码的修改同样以 MIT 许可提供。\n"
          + "内置 DSH 运行时的具体版本见「关于」页的 DSH 一栏。\n"
          + "\n"
          + "【改了什么】\n"
          + "· 竖屏界面：白卡 + 1px 边、零阴影、24dp 主色线性图标、内置壁纸（深浅主题各一张）；\n"
          + "· 横屏：16:9 虚拟屏（三档视口、深色 HUD、登录导航与竖屏同一套策略）；\n"
          + "· 社区插件生态页：官方/社区/本地来源筛选，远端目录不可达时回退内置快照；\n"
          + "· 启动页「白场」：88dp 图标、17sp 标题、底部 2px 进度线，1.2 秒内收尾；\n"
          + "· 页面度量探针：设置 → 开发者，把当前页控件坐标/字号/颜色写成 JSON，只落本机。\n"
          + "\n"
          + "【声明】\n"
          + "1. 本改版为个人修改版本，非官方发布，不提供任何形式的担保；\n"
          + "2. 使用本改版产生的配置、数据与环境风险由使用者自行承担，重要数据请自行备份；\n"
          + "3. 本改版签名与官方不同，不能与官方版互相覆盖安装，数据也不互通；\n"
          + "4. 本改版的问题请反馈给改版维护者，不要提交到上游仓库或官方社区；\n"
          + "5. 若上游作者对本改版提出异议，将停止分发。\n"
          + "\n"
          + "MIT License 全文随源码提供（仓库根目录 LICENSE）。";

  /** 弹出完整说明。Material 对话框的正文自带滚动，短屏/大字体下不会截断。 */
  public static void show(Context context) {
    new DshaDialogBuilder(context)
        .setTitle(TITLE)
        .setMessage(TEXT)
        .setPositiveButton(
            com.deepseekharness.app.util.UiText.choose("关闭", "Close"), null)
        .show();
  }
}
