package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.PluginSource;
import com.deepseekharness.app.util.UiText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 215「社区插件生态」页：页头 + 来源筛选（官方 / 社区 / 本地）+ 插件卡 + 空态。
 *
 * <p>为什么单独一个原生页面，而不是继续用 WebView 打开 dsha.cc：插件相关操作全在原生
 * {@link PluginRepository} 里（安装队列、进度、启用状态），网页里点火一次要跨进程回来，
 * 失败了也只能 toast。这里直接列出目录条目，点「安装」就交给同一个仓库，状态和插件页一致。
 *
 * <p>数据分三层：
 * <ol>
 *   <li>内置快照（{@link #BUILTIN}）：离线可用，也是镜像拉不到时的兜底；
 *   <li>远端目录 {@code <SITE_URL>plugins.json}：格式见 {@link #parse}，成功时覆盖内置快照；
 *   <li>本地已装插件：来自 {@link PluginRepository}，只显示用户/第三方来源。
 * </ol>
 *
 * <p>镜像拉取失败时**不清空列表**，而是保留内置快照并显示「镜像不可达，已回退官方源」，
 * 这是 215 的空态口径：空态说的是「这一层没有东西」，不是「整页坏了」。
 */
public final class CommunityPluginsActivity extends AppCompatActivity {

  /** 来源：官方 / 社区 / 本地。顺序即筛选条顺序。 */
  private static final int ORIGIN_OFFICIAL = 0;
  private static final int ORIGIN_COMMUNITY = 1;
  private static final int ORIGIN_LOCAL = 2;

  private static final String CATALOG_PATH = "plugins.json";
  private static final int MAX_CATALOG_BYTES = 256 * 1024;

  /**
   * 内置快照。远端没有目录（或 404）时用它，保证页面永远有内容。
   * 条目格式：{名称, 简介, 版本, 图标 key, 安装来源}
   */
  private static final String[][] BUILTIN = {
    {
      "dsh-web-mobile",
      "移动端 Web 界面：竖屏布局、底部导航与侧栏适配。",
      "3.2.1",
      "web",
      "dsh-web-mobile"
    },
    {
      "dsh-plugin-files",
      "工作区文件浏览与批量操作，支持预览与拖拽整理。",
      "1.8.0",
      "files",
      "dsh-plugin-files"
    },
    {
      "dsh-plugin-usage",
      "Token 用量与会话成本统计，按模型与时间段汇总。",
      "1.4.2",
      "chart",
      "dsh-plugin-usage"
    },
    {
      "dsh-plugin-mcp",
      "MCP 服务接入与工具清单管理。",
      "0.9.7",
      "globe",
      "dsh-plugin-mcp"
    },
    {
      "dsh-plugin-whale",
      "桌面宠物小鲸鱼：随任务状态游动，回答完成时喷一口气。",
      "0.9.1",
      "star",
      "dsh-plugin-whale"
    },
    {
      "dsh-plugin-websearch",
      "聚合多引擎网页搜索，结果去重后交给模型引用。",
      "1.2.0",
      "globe",
      "dsh-plugin-websearch"
    },
    {
      "dsh-plugin-notes",
      "会话内速记与归档，支持 Markdown 与标签检索。",
      "0.6.4",
      "note",
      "dsh-plugin-notes"
    },
    {
      "dsh-plugin-canvas",
      "画布式提示词编排，把多步任务摆成一张图。",
      "0.4.0",
      "chart",
      "dsh-plugin-canvas"
    }
  };

  /** 一条目录数据；远端解析和本地扫描共用。 */
  private static final class Entry {
    final String name, description, version, installSource;
    final int icon, origin;
    boolean installed;

    Entry(String name, String description, String version, int icon, int origin, String source) {
      this.name = name;
      this.description = description == null ? "" : description;
      this.version = version == null ? "" : version;
      this.icon = icon;
      this.origin = origin;
      this.installSource = source == null ? "" : source;
    }
  }

  private final List<Entry> official = new ArrayList<>();
  private final List<Entry> community = new ArrayList<>();
  private final List<Entry> local = new ArrayList<>();
  private final List<Entry> shown = new ArrayList<>();
  private final Adapter adapter = new Adapter();

  private RecyclerView list;
  private View empty, notice;
  private TextView emptyTitle, emptyDesc, emptyAction, noticeText;
  private TextView filterOfficial, filterCommunity, filterLocal;
  private int filter = ORIGIN_OFFICIAL;
  private boolean mirrorFailed;
  private int loadGeneration;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.activity_community_plugins);
    list = findViewById(R.id.cpList);
    empty = findViewById(R.id.cpEmpty);
    notice = findViewById(R.id.cpNotice);
    emptyTitle = findViewById(R.id.cpEmptyTitle);
    emptyDesc = findViewById(R.id.cpEmptyDesc);
    emptyAction = findViewById(R.id.cpEmptyAction);
    noticeText = findViewById(R.id.cpNoticeText);
    filterOfficial = findViewById(R.id.cpFilterOfficial);
    filterCommunity = findViewById(R.id.cpFilterCommunity);
    filterLocal = findViewById(R.id.cpFilterLocal);

    list.setLayoutManager(new LinearLayoutManager(this));
    list.setItemAnimator(null);
    list.setAdapter(adapter);

    findViewById(R.id.cpBack).setOnClickListener(v -> finish());
    findViewById(R.id.cpRefresh).setOnClickListener(v -> loadCatalog());
    filterOfficial.setOnClickListener(v -> select(ORIGIN_OFFICIAL));
    filterCommunity.setOnClickListener(v -> select(ORIGIN_COMMUNITY));
    filterLocal.setOnClickListener(v -> select(ORIGIN_LOCAL));
    emptyAction.setOnClickListener(v -> {
      if (filter == ORIGIN_LOCAL) openPluginPage();
      else loadCatalog();
    });

    // 内置快照先落地，页面立即可用；远端结果回来再覆盖，避免"先白屏再跳列表"。
    fillBuiltin();
    rebuildLocal();
    select(ORIGIN_OFFICIAL);
    loadCatalog();
  }

  @Override
  protected void onResume() {
    super.onResume();
    // 从插件页装完回来，已装标记要跟着变。
    rebuildLocal();
    render();
  }

  private void select(int origin) {
    filter = origin;
    markFilter(filterOfficial, origin == ORIGIN_OFFICIAL);
    markFilter(filterCommunity, origin == ORIGIN_COMMUNITY);
    markFilter(filterLocal, origin == ORIGIN_LOCAL);
    render();
  }

  private void markFilter(TextView chip, boolean selected) {
    chip.setSelected(selected);
    chip.setTextColor(getColor(selected ? R.color.primary : R.color.text_secondary));
  }

  private List<Entry> current() {
    if (filter == ORIGIN_COMMUNITY) return community;
    if (filter == ORIGIN_LOCAL) return local;
    return official;
  }

  /** 把来源表 + 已装标记重新算一遍，刷新列表与空态。 */
  private void render() {
    if (list == null) return;
    List<Entry> source = current();
    shown.clear();
    shown.addAll(source);
    adapter.notifyDataSetChanged();

    boolean blank = shown.isEmpty();
    list.setVisibility(blank ? View.GONE : View.VISIBLE);
    empty.setVisibility(blank ? View.VISIBLE : View.GONE);
    if (blank) bindEmpty();

    notice.setVisibility(mirrorFailed ? View.VISIBLE : View.GONE);
    if (mirrorFailed)
      noticeText.setText(
          UiText.choose(
              "刚才没连上社区镜像，列表用的是内置快照；网络恢复后点右上角刷新。",
              "The community mirror was unreachable, so this list uses the built-in snapshot. "
                  + "Tap refresh once the network is back."));
  }

  private void bindEmpty() {
    boolean isLocal = filter == ORIGIN_LOCAL;
    if (mirrorFailed) {
      emptyTitle.setText(
          UiText.choose("镜像不可达，已回退官方源", "Mirror unreachable · fell back to official source"));
      emptyDesc.setText(
          isLocal
              ? UiText.choose(
                  "本地还没有导入过插件包。可以从文件里选一份 ZIP / TAR.GZ 试试。",
                  "No local plugin has been imported yet. Pick a ZIP / TAR.GZ file to start.")
              : UiText.choose(
                  "列表来自内置快照，安装时会重新解析插件来源。",
                  "This list comes from the built-in snapshot; the source is resolved again on install."));
      emptyAction.setText(isLocal ? UiText.choose("导入本地插件", "Import a local plugin") : UiText.choose("重试", "Retry"));
    } else {
      emptyTitle.setText(
          isLocal ? UiText.choose("没有本地插件", "No local plugins") : UiText.choose("这个来源暂时是空的", "This source is empty"));
      emptyDesc.setText(
          isLocal
              ? UiText.choose(
                  "导入过的 ZIP / TAR.GZ 插件包会显示在这里。",
                  "Imported ZIP / TAR.GZ plugin packages will show up here.")
              : UiText.choose("换一个来源看看，或稍后刷新。", "Try another source, or refresh later."));
      emptyAction.setText(isLocal ? UiText.choose("去插件页导入", "Import on the plugins page") : UiText.choose("刷新", "Refresh"));
    }
  }

  /** 内置快照 → 官方 / 社区两组。 */
  private void fillBuiltin() {
    official.clear();
    community.clear();
    for (int i = 0; i < BUILTIN.length; i++) {
      String[] row = BUILTIN[i];
      int origin = i < 4 ? ORIGIN_OFFICIAL : ORIGIN_COMMUNITY;
      Entry entry = new Entry(row[0], row[1], row[2], iconFor(row[3]), origin, row[4]);
      if (origin == ORIGIN_OFFICIAL) official.add(entry);
      else community.add(entry);
    }
  }

  /**
   * 拉远端目录。失败不显示错误页：内置快照已经在了，只把 {@link #mirrorFailed} 置上，
   * 由页面底部的提示条和空态文案说明「这是回退结果」。
   */
  private void loadCatalog() {
    fillBuiltin();
    rebuildLocal();
    render();
    final int generation = ++loadGeneration;
    new Thread(
            () -> {
              List<Entry> remote = null;
              try {
                remote = parse(fetch(Constants.SITE_URL + CATALOG_PATH));
              } catch (Throwable ignored) {
              }
              final List<Entry> catalog = remote;
              runOnUiThread(
                  () -> {
                    if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                    if (catalog == null) {
                      mirrorFailed = true;
                    } else {
                      mirrorFailed = false;
                      official.clear();
                      community.clear();
                      for (Entry entry : catalog) {
                        if (entry.origin == ORIGIN_OFFICIAL) official.add(entry);
                        else if (entry.origin == ORIGIN_COMMUNITY) community.add(entry);
                      }
                    }
                    rebuildLocal();
                    render();
                  });
            },
            "community-catalog")
        .start();
  }

  /** 本地已装插件：只要用户/第三方来源，官方内核和随 DSH 提供的不混进「本地」。 */
  private void rebuildLocal() {
    local.clear();
    PluginRepository.State state =
        PluginRepository.get(getApplicationContext()).state().getValue();
    if (state == null || state.items == null) return;
    for (PluginRepository.Item item : state.items) {
      if (item.builtin || item.official || item.runtimeProvided) continue;
      if (item.name == null || item.name.isEmpty()) continue;
      local.add(
          new Entry(
              item.name,
              item.description == null ? "" : item.description,
              item.version,
              iconForName(item.name),
              ORIGIN_LOCAL,
              ""));
    }
    markInstalled(official);
    markInstalled(community);
    for (Entry entry : local) entry.installed = true;
  }

  private void markInstalled(List<Entry> group) {
    PluginRepository.State state =
        PluginRepository.get(getApplicationContext()).state().getValue();
    if (state == null || state.items == null) {
      for (Entry entry : group) entry.installed = false;
      return;
    }
    for (Entry entry : group) {
      entry.installed = false;
      for (PluginRepository.Item item : state.items) {
        if (entry.name.equals(item.name)) {
          entry.installed = true;
          break;
        }
      }
    }
  }

  private void install(Entry entry) {
    if (entry.installed) {
      openPluginPage();
      return;
    }
    PluginRepository repository = PluginRepository.get(getApplicationContext());
    if (repository.isBusy()) {
      toast(UiText.choose("还有插件任务在跑，稍等一下", "A plugin task is still running"));
      return;
    }
    String source = entry.installSource.isEmpty() ? entry.name : entry.installSource;
    try {
      PluginSource parsed = PluginSource.parse(source);
      repository.install(parsed);
      toast(
          UiText.format(
              "已把 %s 交给插件仓库，进度在「插件」页看",
              entry.name));
    } catch (IllegalArgumentException error) {
      toast(error.getMessage());
    }
  }

  private void openPluginPage() {
    startActivity(
        new android.content.Intent(this, MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("open_plugins", true));
    finish();
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  // ---------------------------------------------------------------- 远端目录

  private String fetch(String address) {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(address).openConnection();
      connection.setConnectTimeout(900);
      connection.setReadTimeout(1800);
      connection.setRequestProperty("Accept", "application/json");
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
      try (InputStream in = connection.getInputStream()) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
          out.write(buffer, 0, read);
          if (out.size() > MAX_CATALOG_BYTES) return null;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
      }
    } catch (Throwable ignored) {
      return null;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  /**
   * 目录格式：
   *
   * <pre>{"plugins":[{"name":"…","description":"…","version":"1.0.0",
   *   "origin":"official|community","source":"npm 包名或链接","icon":"web|files|chart|star|globe|note"}]}</pre>
   *
   * 任何一条缺名称或缺安装来源就跳过 —— 宁可不显示，也不给一个点了没反应的按钮。
   */
  private List<Entry> parse(String json) {
    if (json == null || json.isEmpty()) return null;
    try {
      JSONArray array = new JSONObject(json).optJSONArray("plugins");
      if (array == null || array.length() == 0) return null;
      List<Entry> out = new ArrayList<>();
      for (int i = 0; i < array.length(); i++) {
        JSONObject row = array.optJSONObject(i);
        if (row == null) continue;
        String name = row.optString("name").trim();
        String source = row.optString("source").trim();
        if (name.isEmpty() || source.isEmpty()) continue;
        String origin = row.optString("origin", "community").trim().toLowerCase(Locale.ROOT);
        out.add(
            new Entry(
                name,
                row.optString("description"),
                row.optString("version"),
                iconFor(row.optString("icon")),
                "official".equals(origin) ? ORIGIN_OFFICIAL : ORIGIN_COMMUNITY,
                source));
      }
      return out.isEmpty() ? null : out;
    } catch (Throwable ignored) {
      return null;
    }
  }

  // ---------------------------------------------------------------- 图标映射

  private static int iconFor(String key) {
    if (key == null) return R.drawable.ic_plug_web;
    switch (key.trim().toLowerCase(Locale.ROOT)) {
      case "files":
        return R.drawable.ic_plug_files;
      case "chart":
        return R.drawable.ic_plug_chart;
      case "star":
        return R.drawable.ic_plug_star;
      case "globe":
        return R.drawable.ic_plug_globe;
      case "note":
        return R.drawable.ic_plug_note;
      default:
        return R.drawable.ic_plug_web;
    }
  }

  /** 本地插件没有目录图标，按名字里的关键词挑一个，认不出来就用文档图标。 */
  private static int iconForName(String name) {
    String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
    if (value.contains("file") || value.contains("fs")) return R.drawable.ic_plug_files;
    if (value.contains("web") || value.contains("browser")) return R.drawable.ic_plug_web;
    if (value.contains("chart") || value.contains("usage") || value.contains("stat"))
      return R.drawable.ic_plug_chart;
    if (value.contains("search") || value.contains("websearch")) return R.drawable.ic_plug_globe;
    if (value.contains("note") || value.contains("memo")) return R.drawable.ic_plug_note;
    return R.drawable.ic_plug_note;
  }

  // ---------------------------------------------------------------- 列表

  private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

    final class Holder extends RecyclerView.ViewHolder {
      final android.widget.ImageView icon;
      final TextView name, badge, description, meta, action;

      Holder(View view) {
        super(view);
        icon = view.findViewById(R.id.cpIcon);
        name = view.findViewById(R.id.cpName);
        badge = view.findViewById(R.id.cpBadge);
        description = view.findViewById(R.id.cpDesc);
        meta = view.findViewById(R.id.cpMeta);
        action = view.findViewById(R.id.cpAction);
      }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
      return new Holder(
          LayoutInflater.from(parent.getContext())
              .inflate(R.layout.item_community_plugin, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
      Entry entry = shown.get(position);
      holder.icon.setImageResource(entry.icon);
      holder.name.setText(entry.name);
      holder.description.setText(
          entry.description.isEmpty()
              ? UiText.choose("第三方插件", "Third-party plugin")
              : entry.description);
      bindBadge(holder.badge, entry.origin);
      holder.meta.setText(metaOf(entry));
      bindAction(holder.action, entry);
      holder.itemView.setContentDescription(entry.name);
      holder.itemView.setOnClickListener(v -> install(entry));
    }

    @Override
    public int getItemCount() {
      return shown.size();
    }
  }

  private void bindBadge(TextView badge, int origin) {
    if (origin == ORIGIN_COMMUNITY) {
      badge.setText(UiText.choose("社区", "Community"));
      badge.setBackgroundResource(R.drawable.bg_badge_community);
      badge.setTextColor(getColor(R.color.ok));
    } else if (origin == ORIGIN_LOCAL) {
      badge.setText(UiText.choose("本地", "Local"));
      badge.setBackgroundResource(R.drawable.bg_badge_local);
      badge.setTextColor(getColor(R.color.text_secondary));
    } else {
      badge.setText(UiText.choose("官方", "Official"));
      badge.setBackgroundResource(R.drawable.bg_badge_official);
      badge.setTextColor(getColor(R.color.primary));
    }
  }

  private void bindAction(TextView action, Entry entry) {
    if (entry.installed) {
      action.setText(UiText.choose("已安装", "Installed"));
      action.setTextColor(getColor(R.color.text_muted));
      action.setBackgroundResource(R.drawable.bg_pill_muted);
      return;
    }
    action.setText(UiText.choose("安装", "Install"));
    action.setTextColor(getColor(R.color.primary));
    action.setBackgroundResource(R.drawable.bg_pill_action);
  }

  private String metaOf(Entry entry) {
    String version = entry.version.isEmpty() ? "" : "v" + entry.version;
    String where =
        entry.origin == ORIGIN_OFFICIAL
            ? UiText.choose("官方源", "Official source")
            : entry.origin == ORIGIN_COMMUNITY
                ? UiText.choose("社区源", "Community source")
                : UiText.choose("本地导入", "Imported locally");
    return version.isEmpty() ? where : version + " · " + where;
  }
}
