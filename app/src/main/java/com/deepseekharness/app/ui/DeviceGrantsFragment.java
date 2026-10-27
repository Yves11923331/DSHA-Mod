package com.deepseekharness.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;
import com.google.android.material.materialswitch.MaterialSwitch;

/** 设备通道、系统权限与能力预授权的唯一设置页；沿用历史偏好键。 */
public final class DeviceGrantsFragment extends Fragment {
  private MaterialSwitch sms;
  private TextView smsStatus;
  /** 屏幕操作卡片里的动态行（与设置页同一套尺寸，见 {@link GrantRow}）。 */
  private GrantRow screenModeRow;

  private GrantRow screenRulesRow;
  private GrantRow screenPauseRow;
  private GrantRow screenRevokeRow;
  private boolean updating;
  private int adbStatusRequest;
  private FullPermissionControls fullPermissions;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable refreshConnections =
      new Runnable() {
        @Override
        public void run() {
          if (!isResumed() || getView() == null) return;
          refreshChannelLabels();
          if (fullPermissions != null) fullPermissions.refresh();
          main.postDelayed(this, 2000);
        }
      };

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    View view = inflater.inflate(R.layout.fragment_device_grants, container, false);
    Context ctx = requireContext();
    fullPermissions =
        new FullPermissionControls(
            this,
            (android.widget.LinearLayout) ((android.widget.ScrollView) view).getChildAt(0),
            this::refreshNativePolicyControls);
    for (int id :
        new int[] {
          R.id.device_root_status, R.id.workspace_shizuku_status, R.id.config_adb_status
        }) {
      TextView detail = view.findViewById(id);
      detail.setOnClickListener(
          v ->
              CardSheet.show(
                  requireContext(),
                  com.deepseekharness.app.util.UiText.choose("设备通道状态", "Device channel status"),
                  detail.getText().toString()));
    }
    ((TextView) view.findViewById(R.id.computer_use_hint))
        .setText(
            com.deepseekharness.app.util.UiText.choose(
                "让助手读屏、点击和输入。先完成屏幕操作设置，再在对话中描述任务。",
                "Let the assistant observe, tap and type. Set up screen control, then describe the task in a conversation."));
    view.findViewById(R.id.computer_use_accessibility)
        .setOnClickListener(
            button ->
                startActivity(new Intent(requireContext(), AccessibilitySetupActivity.class)));
    android.widget.LinearLayout screenCard =
        (android.widget.LinearLayout) view.findViewById(R.id.computer_use_hint).getParent();
    addScreenPolicyControls(ctx, screenCard);

    // 221：行式布局 —— 整行或右侧箭头点击，转发给原来那些隐藏按钮的 performClick()。
    // 这样 840 行里的绑定逻辑一行都不用改，只是把入口从「按钮」换成「行」。
    bindRow(view, R.id.row_root, R.id.row_root_go, R.id.device_root_verify);
    bindRow(view, R.id.row_shizuku, R.id.row_shizuku_go, R.id.workspace_shizuku_auth);
    bindRow(view, R.id.row_adb, R.id.row_adb_go, R.id.config_adb_pair);
    bindRow(view, R.id.row_a11y, R.id.row_a11y_go, R.id.computer_use_accessibility);
    bindRow(view, R.id.row_battery, R.id.row_battery_go, R.id.config_battery_opt);
    // 三个开关行：整行点击 = 切开关（和设置页一致），不是只有那个小开关能点。
    bindSwitchRow(view, R.id.row_sms, R.id.device_sms);
    bindSwitchRow(view, R.id.row_location, R.id.config_cap_location);
    bindSwitchRow(view, R.id.row_sensors, R.id.config_cap_sensors);
    // Shizuku 长按 = 打开 Shizuku App（原来那个独立按钮）
    android.view.View shizukuRow = view.findViewById(R.id.row_shizuku);
    if (shizukuRow != null)
      shizukuRow.setOnLongClickListener(
          v -> {
            android.view.View open = view.findViewById(R.id.device_shizuku_open);
            if (open != null) open.performClick();
            return true;
          });
    CompoundButton root = view.findViewById(R.id.config_root_shell);
    root.setChecked(new ConfigStore(ctx).isRootShellAllowed());
    root.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          new ConfigStore(ctx).setRootShellAllowed(enabled);
          if (!enabled) com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).revoke();
          syncSettings();
          refreshChannelLabels();
        });
    view.findViewById(R.id.device_root_verify)
        .setOnClickListener(
            button -> {
              if (!RootShell.enabled(ctx)) {
                toast(com.deepseekharness.app.util.UiText.text("请先启用「允许 Root Shell」"));
                return;
              }
              if (!RootShell.present()) {
                toast(
                    com.deepseekharness.app.util.UiText.text(
                        "未找到 su；请确认手机已 root，且 root 管理器允许 DSHA 使用"));
                return;
              }
              verify(
                  button,
                  view.findViewById(R.id.device_root_status),
                  () -> RootShell.exec(ctx.getApplicationContext(), "id", -1));
            });
    view.findViewById(R.id.device_shizuku_open).setOnClickListener(button -> openShizuku());
    view.findViewById(R.id.workspace_shizuku_auth)
        .setOnClickListener(
            button -> {
              if (!ShizukuShell.isAvailable()) {
                button.setEnabled(false);
                com.deepseekharness.app.ShizukuManagerCompat.reconnect(
                    requireActivity(),
                    () -> {
                      if (!isAdded()) return;
                      button.setEnabled(true);
                      if (ShizukuShell.isAvailable())
                        ShizukuShell.requestPermission(
                            (code, result) -> {
                              if (isAdded()) refreshChannelLabels();
                            });
                      else toast(ShizukuShell.userStatus(ctx));
                      refreshChannelLabels();
                    });
                return;
              }
              if (ShizukuShell.hasPermission()) {
                verify(
                    button,
                    view.findViewById(R.id.workspace_shizuku_status),
                    () -> ShizukuShell.exec("id"));
              } else
                ShizukuShell.requestPermission(
                    (code, result) -> {
                      if (isAdded()) refreshChannelLabels();
                    });
            });
    CompoundButton sensors = view.findViewById(R.id.config_cap_sensors);
    sensors.setChecked(pref(ctx, "cap_sensors", false));
    sensors.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (!updating) savePreference("cap_sensors", enabled);
        });
    CompoundButton location = view.findViewById(R.id.config_cap_location);
    location.setChecked(pref(ctx, "cap_location", false));
    location.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          if (fullPermissions != null) fullPermissions.cancelForLocationChange();
          savePreference("cap_location", enabled);
          if (enabled
              && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(
                new String[] {
                  android.Manifest.permission.ACCESS_FINE_LOCATION,
                  android.Manifest.permission.ACCESS_COARSE_LOCATION
                },
                104);
        });
    CompoundButton adb = view.findViewById(R.id.config_adb_enable);
    adb.setChecked(pref(ctx, "adb_enabled", false));
    adb.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          com.deepseekharness.app.HttpShellService.smsPolicyChanged(ctx);
          DeviceBridgeService.adbIntentChanging();
          savePreference("adb_enabled", enabled);
          if (!enabled) com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).revoke();
          if (enabled) {
            DeviceBridgeService.apply(ctx);
            if (getActivity() instanceof MainActivity)
              ((MainActivity) getActivity()).requestLocalNetwork();
          } else ctx.stopService(new Intent(ctx, DeviceBridgeService.class));
          syncSettings();
          refreshAdbStatus(view.findViewById(R.id.config_adb_status));
        });
    view.findViewById(R.id.config_adb_pair)
        .setOnClickListener(
            button -> {
              if (android.os.Build.VERSION.SDK_INT < 30) {
                new com.deepseekharness.app.ui.DshaDialogBuilder(ctx)
                    .setTitle(com.deepseekharness.app.util.UiText.text("当前系统没有配对码接口"))
                    .setMessage(
                        com.deepseekharness.app.util.UiText.text(
                            "无线调试配对码需要 Android 11+。Android 6—10 可使用 Shizuku、已授权的 root，或由电脑开启 ADB TCP 通道。"))
                    .setPositiveButton(com.deepseekharness.app.util.UiText.text("知道了"), null)
                    .show();
              } else startActivity(new Intent(ctx, AdbPairActivity.class));
            });
    view.findViewById(R.id.config_all_files).setOnClickListener(button -> openAllFilesAccess(ctx));
    view.findViewById(R.id.config_battery_opt).setOnClickListener(button -> openBatteryOpt(ctx));
    sms = view.findViewById(R.id.device_sms);
    smsStatus = view.findViewById(R.id.device_sms_status);
    sms.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          updateSms();
          if (!enabled) {
            saveSms(false);
            return;
          }
          new com.deepseekharness.app.ui.DshaDialogBuilder(ctx, true)
              .setTitle(com.deepseekharness.app.util.UiText.text("持续允许读取短信？"))
              .setMessage(
                  com.deepseekharness.app.util.UiText.text(
                      "当前 DSHA 环境内的助手和插件将能通过已授权的 root 或 ADB 查询短信，包括正文、号码和可能存在的验证码。\n\n后续短信查询不再逐条询问。关闭后立即阻止新查询。"))
              .setPositiveButton(
                  com.deepseekharness.app.util.UiText.text("允许短信读取"),
                  (dialog, which) -> saveSms(true))
              .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
              .show();
        });
    updateSms();
    return view;
  }

  /**
   * 屏幕操作的动态行。
   *
   * <p>以前这里是「一段长说明 + 原生 RadioGroup + 三个默认按钮」，和设置页的行式卡片
   * 完全两套皮肤。现在全部换成 {@link GrantRow}：一行一件事，点行开弹窗，开关就地生效。
   * 说明文字没丢，挪进「确认规则说明」那一行的 {@link CardSheet}。
   */
  private void addScreenPolicyControls(Context ctx, android.widget.LinearLayout card) {
    // 1) 确认方式：一行 + 单选弹窗
    screenModeRow =
        GrantRow.row(
            ctx,
            com.deepseekharness.app.R.drawable.ic_ui_tasks,
            com.deepseekharness.app.util.UiText.choose("确认方式", "Confirmation mode"),
            "");
    screenModeRow.root.setOnClickListener(v -> showScreenModeChooser(ctx));
    card.addView(screenModeRow.root);
    card.addView(GrantRow.divider(ctx));

    // 2) 应用确认规则
    screenRulesRow =
        GrantRow.row(
            ctx,
            com.deepseekharness.app.R.drawable.ic_ui2_auto,
            com.deepseekharness.app.util.UiText.choose("应用确认规则", "App confirmation rules"),
            "");
    screenRulesRow.root.setOnClickListener(v -> showScreenRules());
    card.addView(screenRulesRow.root);
    card.addView(GrantRow.divider(ctx));

    // 3) 暂停 / 恢复（开关行：开 = 已暂停）
    screenPauseRow =
        GrantRow.row(
            ctx,
            com.deepseekharness.app.R.drawable.ic_recovery_clock,
            com.deepseekharness.app.util.UiText.choose(
                "暂停助手屏幕操作", "Pause assistant screen actions"),
            com.deepseekharness.app.util.UiText.choose(
                "暂停期间读屏与点按全部拒绝", "Reading and tapping are refused while paused"),
            true,
            false);
    screenPauseRow.toggle.setOnCheckedChangeListener(
        (button, checked) -> {
          if (updating) return;
          com.deepseekharness.app.HttpShellService.setScreenPaused(ctx, checked);
          refreshScreenPolicy();
        });
    card.addView(screenPauseRow.root);
    card.addView(GrantRow.divider(ctx));

    // 4) 撤销预授权（破坏性操作，先问一句）
    screenRevokeRow =
        GrantRow.row(
            ctx,
            com.deepseekharness.app.R.drawable.ic_recovery_refresh,
            com.deepseekharness.app.util.UiText.choose("撤销屏幕预授权", "Revoke screen preapprovals"),
            com.deepseekharness.app.util.UiText.choose(
                "清空已记住的应用规则，并恢复为全部手动", "Clear remembered app rules and confirm every action again"));
    screenRevokeRow.root.setOnClickListener(v -> confirmRevokeScreen(ctx));
    card.addView(screenRevokeRow.root);
    card.addView(GrantRow.divider(ctx));

    // 5) 说明（原来那段 100 多字的正文，收进弹窗）
    GrantRow details =
        GrantRow.row(
            ctx,
            com.deepseekharness.app.R.drawable.ic_recovery_document,
            com.deepseekharness.app.util.UiText.choose("确认规则说明", "How confirmation works"),
            com.deepseekharness.app.util.UiText.choose(
                "只影响 DSHA 的确认，不授予系统权限", "Only affects DSHA confirmations; grants no system permission"));
    details.root.setOnClickListener(
        v ->
            CardSheet.show(
                ctx,
                com.deepseekharness.app.util.UiText.choose(
                    "屏幕确认规则", "Screen confirmation rules"),
                com.deepseekharness.app.util.UiText.choose(
                    "这里设置的是 DSHA 自己的屏幕确认，不会授予任何 Android 系统权限。\n\n全部手动：每一次读屏、点按都要确认。\n按应用自定义：为指定应用单独放行，没配置的应用仍然询问。\n全部允许：已获准的屏幕操作不再确认，包含银行、支付等敏感界面。\n\n规则保存在本机，不随备份迁移；随时可以撤销，撤销后恢复为全部手动。全局权限开关只决定要不要弹 DSHA 安全确认，与这里的规则互不替代。",
                    "These are DSHA's own screen confirmations and grant no Android permission.\n\nConfirm every action: each read and tap is confirmed.\nCustomize by app: allow selected apps; unconfigured apps still ask.\nAllow all: authorized screen actions skip confirmation, including sensitive screens such as banks and payments.\n\nRules stay on this device and are excluded from backups. Revoke them at any time to restore Confirm every action. The global permission switch only controls DSHA safety confirmations and does not replace these rules.")));
    card.addView(details.root);

    // 6) 虚拟屏（能力可用时）
    if (com.deepseekharness.app.vscreen.VirtualScreenManager.supported(ctx)) {
      card.addView(GrantRow.divider(ctx));
      GrantRow virtualScreen =
          GrantRow.row(
              ctx,
              com.deepseekharness.app.R.drawable.ic_ui2_box,
              com.deepseekharness.app.util.UiText.choose("打开虚拟屏", "Open virtual screen"),
              com.deepseekharness.app.util.UiText.choose(
                  "需要已连接的设备通道；页内有创建说明",
                  "Needs a connected device channel; instructions are on that page"));
      virtualScreen.root.setOnClickListener(
          v ->
              startActivity(
                  new Intent(ctx, com.deepseekharness.app.vscreen.VirtualScreenActivity.class)));
      card.addView(virtualScreen.root);
    }
    refreshScreenPolicy();
  }

  /** 确认方式：单选弹窗（不用 RadioGroup —— 设置页没有第二种写法）。 */
  private void showScreenModeChooser(Context ctx) {
    var grants = new DeviceGrants(ctx);
    var policy = grants.screenPolicy();
    String[] labels = {
      com.deepseekharness.app.util.UiText.choose("全部手动", "Confirm every action"),
      com.deepseekharness.app.util.UiText.choose("按应用自定义", "Customize by app"),
      com.deepseekharness.app.util.UiText.choose("全部允许", "Allow all")
    };
    int checked = 0;
    for (int i = 0; i < com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.values().length;
        i++) {
      if (com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.values()[i] == policy.mode) {
        checked = i;
        break;
      }
    }
    new DshaDialogBuilder(ctx)
        .setTitle(com.deepseekharness.app.util.UiText.choose("确认方式", "Confirmation mode"))
        .setSingleChoiceItems(
            labels,
            checked,
            (dialog, which) -> {
              dialog.dismiss();
              var current = new DeviceGrants(ctx).screenPolicy();
              if (!new DeviceGrants(ctx)
                  .setScreenPolicy(
                      com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.values()[which],
                      current.rules))
                toast(com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
              refreshScreenPolicy();
            })
        .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
        .show();
  }

  /** 撤销是破坏性的：先确认，再清空规则并暂停。 */
  private void confirmRevokeScreen(Context ctx) {
    new DshaDialogBuilder(ctx)
        .setTitle(com.deepseekharness.app.util.UiText.choose("撤销屏幕预授权", "Revoke screen preapprovals"))
        .setMessage(
            com.deepseekharness.app.util.UiText.choose(
                "将清空已记住的应用确认规则，并暂停助手屏幕操作。之后需要重新设置才能继续读屏、点按。",
                "This clears remembered app rules and pauses assistant screen actions. Set it up again before the assistant can read the screen or tap."))
        .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
        .setPositiveButton(
            com.deepseekharness.app.util.UiText.choose("撤销并暂停", "Revoke and pause"),
            (dialog, which) -> {
              boolean persisted =
                  new DeviceGrants(ctx)
                      .setScreenPolicy(
                          com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.MANUAL,
                          java.util.Map.of());
              com.deepseekharness.app.HttpShellService.setScreenPaused(ctx, true);
              com.deepseekharness.app.HttpShellService.revokeScreenGrant(ctx);
              refreshScreenPolicy();
              toast(
                  persisted
                      ? com.deepseekharness.app.util.UiText.choose(
                          "已暂停屏幕操作并撤销预授权", "Screen actions paused and preapprovals revoked")
                      : com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
            })
        .show();
  }

  private void refreshScreenPolicy() {
    if (screenModeRow == null || !isAdded()) return;
    var ctx = requireContext();
    var grants = new DeviceGrants(ctx);
    var policy = grants.screenPolicy();
    boolean paused = com.deepseekharness.app.HttpShellService.screenPaused();
    updating = true;
    try {
      if (screenPauseRow != null && screenPauseRow.toggle != null) {
        screenPauseRow.toggle.setChecked(paused);
      }
    } finally {
      updating = false;
    }
    GrantRow.setSubtitle(
        screenModeRow,
        switch (policy.mode) {
          case MANUAL ->
              com.deepseekharness.app.util.UiText.choose(
                  "全部手动：每次读屏、点按都要确认", "Confirm every action: each read and tap is confirmed");
          case CUSTOM ->
              com.deepseekharness.app.util.UiText.choose(
                  "按应用自定义：未配置的应用仍然询问", "Customize by app: unconfigured apps still ask");
          case ALLOW_ALL ->
              com.deepseekharness.app.util.UiText.choose(
                  "全部允许：敏感界面也不再确认", "Allow all: even sensitive screens skip confirmation");
        });
    int rules = policy.rules.size();
    GrantRow.setSubtitle(
        screenRulesRow,
        rules <= 0
            ? com.deepseekharness.app.util.UiText.choose(
                "未配置单独放行或询问的应用", "No app-specific allow or ask rule yet")
            : com.deepseekharness.app.util.UiText.format(
                com.deepseekharness.app.util.UiText.choose(
                    "已配置 %s 个应用", "%s app rule(s) configured"),
                String.valueOf(rules)));
    GrantRow.setSubtitle(
        screenPauseRow,
        paused
            ? com.deepseekharness.app.util.UiText.choose(
                "已暂停：读屏与点按全部拒绝", "Paused: reading and tapping are refused")
            : com.deepseekharness.app.util.UiText.choose(
                "运行中：按上面的确认方式执行", "Active: follows the confirmation mode above"));
    GrantRow.setSubtitle(
        screenRevokeRow,
        com.deepseekharness.app.util.UiText.choose(
            "清空已记住的应用规则，并恢复为全部手动", "Clear remembered app rules and confirm every action again"));
  }

  private void refreshNativePolicyControls() {
    refreshScreenPolicy();
    View page = getView();
    if (page == null || !isAdded()) return;
    updating = true;
    ((CompoundButton) page.findViewById(R.id.config_root_shell))
        .setChecked(new ConfigStore(requireContext()).isRootShellAllowed());
    ((CompoundButton) page.findViewById(R.id.config_adb_enable))
        .setChecked(pref(requireContext(), "adb_enabled", false));
    ((CompoundButton) page.findViewById(R.id.config_cap_sensors))
        .setChecked(pref(requireContext(), "cap_sensors", false));
    ((CompoundButton) page.findViewById(R.id.config_cap_location))
        .setChecked(pref(requireContext(), "cap_location", false));

    updating = false;
    updateSms();
  }

  private void showScreenRules() {
    var policy = new DeviceGrants(requireContext()).screenPolicy();
    String[] packages = policy.rules.keySet().toArray(new String[0]);
    String[] labels = new String[packages.length];
    for (int i = 0; i < packages.length; i++)
      labels[i] =
          packages[i]
              + " · "
              + (policy.rules.get(packages[i])
                      == com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW
                  ? com.deepseekharness.app.util.UiText.choose("允许", "Allow")
                  : com.deepseekharness.app.util.UiText.choose("询问", "Ask"));
    new DshaDialogBuilder(requireContext())
        .setTitle(com.deepseekharness.app.util.UiText.choose("应用确认规则", "App confirmation rules"))
        .setItems(labels, (dialog, which) -> editScreenRule(packages[which]))
        .setPositiveButton(
            com.deepseekharness.app.util.UiText.choose("添加应用", "Add app"),
            (dialog, which) -> editScreenRule(""))
        .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
        .show();
  }

  private void editScreenRule(String existing) {
    var ctx = requireContext();
    var content = new android.widget.LinearLayout(ctx);
    content.setOrientation(android.widget.LinearLayout.VERTICAL);
    var pkg = new androidx.appcompat.widget.AppCompatEditText(ctx);
    pkg.setSingleLine(true);
    pkg.setHint(com.deepseekharness.app.util.UiText.choose("精确应用包名", "Exact app package name"));
    pkg.setText(existing);
    pkg.setEnabled(existing.isEmpty());
    content.addView(pkg);
    var allow = new MaterialSwitch(ctx);
    allow.setText(
        com.deepseekharness.app.util.UiText.choose(
            "允许该应用免确认（含敏感界面）", "Allow this app without confirmation, including sensitive screens"));
    allow.setChecked(
        new DeviceGrants(ctx).screenPolicy().rules.get(existing)
            == com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW);
    content.addView(allow);
    var scroll = new android.widget.ScrollView(ctx);
    scroll.setFillViewport(true);
    scroll.addView(content);
    var builder =
        new DshaDialogBuilder(ctx)
            .setTitle(
                com.deepseekharness.app.util.UiText.choose(
                    "编辑应用确认规则", "Edit app confirmation rules"))
            .setView(scroll)
            .setPositiveButton(
                com.deepseekharness.app.util.UiText.text("保存"),
                (dialog, which) -> {
                  String key = pkg.getText().toString().trim();
                  if (!com.deepseekharness.app.util.ScreenConfirmationPolicy.validPackage(key)) {
                    toast(
                        com.deepseekharness.app.util.UiText.choose(
                            "应用包名无效，规则未保存", "Invalid package name. Rule was not saved"));
                    return;
                  }
                  var grants = new DeviceGrants(ctx);
                  var current = grants.screenPolicy();
                  var rules = new java.util.LinkedHashMap<>(current.rules);
                  rules.put(
                      key,
                      allow.isChecked()
                          ? com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW
                          : com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ASK);
                  if (!grants.setScreenPolicy(current.mode, rules))
                    toast(com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
                  refreshScreenPolicy();
                })
            .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null);
    if (!existing.isEmpty())
      builder.setNeutralButton(
          com.deepseekharness.app.util.UiText.choose("删除规则", "Remove rule"),
          (dialog, which) -> {
            var grants = new DeviceGrants(ctx);
            var current = grants.screenPolicy();
            var rules = new java.util.LinkedHashMap<>(current.rules);
            rules.remove(existing);
            if (!grants.setScreenPolicy(current.mode, rules))
              toast(com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
            refreshScreenPolicy();
          });
    builder.show();
  }

  private void savePreference(String key, boolean enabled) {
    boolean success =
        requireContext()
            .getSharedPreferences(Constants.PREFS, 0)
            .edit()
            .putBoolean(key, enabled)
            .commit();
    if (success && !enabled) com.deepseekharness.app.DeviceSense.revoke(requireContext(), key);
    if (!success) toast(com.deepseekharness.app.util.UiText.text("设置保存失败，请重试"));
  }

  private void syncSettings() {
    final Context app = requireContext().getApplicationContext();
    final View page = getView();
    new Thread(
            () -> {
              String result = AdbBridge.applySettings(app, HarnessController.get(app).proot());
              main.post(
                  () -> {
                    if (!isAdded() || page == null || getView() != page) return;
                    TextView status = page.findViewById(R.id.config_guard_status);
                    status.setVisibility(View.VISIBLE);
                    status.setText(
                        com.deepseekharness.app.util.UiText.format(
                            "原生授权即时生效；%s", result.replaceFirst("^SETTINGS_[A-Z]+: ", "")));
                  });
            },
            "device-settings")
        .start();
  }

  private void verify(View button, TextView status, java.util.function.Supplier<String> probe) {
    final View page = getView();
    button.setEnabled(false);
    status.setText(com.deepseekharness.app.util.UiText.text("正在验证设备通道…"));
    status.setTag(Boolean.TRUE);
    new Thread(
            () -> {
              String output;
              try {
                output = probe.get();
              } catch (Throwable error) {
                output = com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(error));
              }
              final String result = output;
              main.post(
                  () -> {
                    if (!isAdded() || getView() != page) return;
                    button.setEnabled(true);
                    status.setTag(null);
                    new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                        .setTitle(com.deepseekharness.app.util.UiText.text("设备通道验证"))
                        .setMessage(com.deepseekharness.app.util.UiText.raw(result))
                        .setPositiveButton(com.deepseekharness.app.util.UiText.text("知道了"), null)
                        .show();
                    refreshChannelLabels();
                  });
            },
            "device-channel-probe")
        .start();
  }

  private void openShizuku() {
    Intent launch = com.deepseekharness.app.ShizukuManagerCompat.launchIntent(requireContext());
    if (launch == null) {
      toast(com.deepseekharness.app.util.UiText.text("尚未安装 Shizuku，请安装后启动服务"));
      return;
    }
    try {
      startActivity(launch);
    } catch (RuntimeException e) {
      toast(
          com.deepseekharness.app.util.UiText.format(
              "无法打开 Shizuku：%s", e.getClass().getSimpleName()));
    }
  }

  private void refreshChannelLabels() {
    View page = getView();
    if (page == null) return;
    TextView shizuku = page.findViewById(R.id.workspace_shizuku_status);
    TextView root = page.findViewById(R.id.device_root_status);
    if (shizuku.getTag() == null)
      setTextIfChanged(
          shizuku,
          com.deepseekharness.app.util.UiStateText.render(
              ShizukuShell.userStatus(requireContext())));
    setTextIfChanged(
        page.findViewById(R.id.workspace_shizuku_auth),
        ShizukuShell.hasPermission()
            ? com.deepseekharness.app.util.UiText.text("验证 Shizuku 连接")
            : com.deepseekharness.app.util.UiText.text("授权 Shizuku"));
    if (root.getTag() == null) setTextIfChanged(root, RootShell.status(requireContext()));
  }

  private void saveSms(boolean enabled) {
    boolean saved = new DeviceGrants(requireContext()).setSmsReadAllowed(enabled);
    updateSms();
    toast(
        !saved
            ? com.deepseekharness.app.util.UiText.text("授权保存失败，请重试")
            : enabled
                ? com.deepseekharness.app.util.UiText.text("短信查询已预授权")
                : com.deepseekharness.app.util.UiText.choose("已关闭短信读取", "SMS reading disabled"));
  }

  private void updateSms() {
    if (sms == null || !isAdded()) return;
    boolean allowed = new DeviceGrants(requireContext()).smsReadAllowed();
    updating = true;
    sms.setChecked(allowed);
    updating = false;
    setTextIfChanged(
        smsStatus,
        allowed
            ? com.deepseekharness.app.util.UiText.text("已预授权 · 关闭可撤销")
            : com.deepseekharness.app.util.UiText.choose(
                "已关闭 · 查询会被阻止", "Off · queries are blocked"));
    int secondary = requireContext().getColor(R.color.text_secondary);
    if (smsStatus.getCurrentTextColor() != secondary) smsStatus.setTextColor(secondary);
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request != 104 || !isAdded()) return;
    boolean granted =
        requireContext().checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            || requireContext()
                    .checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    if (!granted) {
      savePreference("cap_location", false);
      if (getView() != null)
        ((CompoundButton) getView().findViewById(R.id.config_cap_location)).setChecked(false);
      new DshaDialogBuilder(requireContext())
          .setTitle(
              com.deepseekharness.app.util.UiText.choose(
                  "需要系统定位权限", "Location permission required"))
          .setMessage(
              com.deepseekharness.app.util.UiText.choose(
                  "位置读取保持关闭。请在系统应用权限中允许定位，再返回这里开启；如果系统定位服务关闭，也需要先开启系统定位。",
                  "Location reading remains off. Allow location in system app permissions, then return and enable it here. System location services must also be enabled."))
          .setNegativeButton(com.deepseekharness.app.util.UiText.choose("暂不开启", "Not now"), null)
          .setPositiveButton(
              com.deepseekharness.app.util.UiText.choose("打开系统权限设置", "Open app permissions"),
              (dialog, which) -> {
                try {
                  startActivity(
                      new Intent(
                          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          android.net.Uri.parse("package:" + requireContext().getPackageName())));
                } catch (RuntimeException missing) {
                  toast(
                      com.deepseekharness.app.util.UiText.choose(
                          "请在系统设置中打开 DSHA 应用权限", "Open DSHA permissions in system settings"));
                }
              })
          .show();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshNativePolicyControls();
    if (fullPermissions != null) fullPermissions.resume();
    ShizukuShell.ensureBound(requireContext());
    updateSms();
    main.removeCallbacks(refreshConnections);
    main.post(refreshConnections);
    View page = getView();
    if (page != null) {
      refreshAllFilesStatus(page.findViewById(R.id.config_all_files_status));
      refreshA11yStatus(page.findViewById(R.id.config_a11y_status));
      refreshAdbStatus(page.findViewById(R.id.config_adb_status));
    }
  }

  @Override
  public void onPause() {
    if (fullPermissions != null) fullPermissions.pause();
    main.removeCallbacks(refreshConnections);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    if (fullPermissions != null) {
      fullPermissions.close();
      fullPermissions = null;
    }
    adbStatusRequest++;
    sms = null;
    smsStatus = null;
    super.onDestroyView();
  }

  private boolean pref(Context ctx, String key, boolean fallback) {
    return ctx.getSharedPreferences(Constants.PREFS, 0).getBoolean(key, fallback);
  }

  private void toast(String value) {
    if (getContext() != null) Toast.makeText(getContext(), value, Toast.LENGTH_LONG).show();
  }

  /** 后台读 ADB 通道真实状态（key/deps/端口 + 保活服务的连接状态），刷到状态栏。 */
  private boolean adbStatusBusy;

  private void refreshAdbStatus(final TextView status) {
    // 上一次还在算就别再起线程（这个页面每 2 秒刷一次，避免线程堆积）。
    if (adbStatusBusy) return;
    adbStatusBusy = true;
    final Context app = requireContext().getApplicationContext();
    final View page = getView();
    final android.app.Activity activity = getActivity();
    if (activity == null) return;
    final int request = ++adbStatusRequest;
    new Thread(
            () -> {
              final String text;
              try {
                text = computeAdbStatus(app);
              } catch (Throwable error) {
                adbStatusBusy = false;
                return;
              }
              activity.runOnUiThread(
                  () -> {
                    adbStatusBusy = false;
                    if (request != adbStatusRequest
                        || status == null
                        || getView() != page
                        || !isAdded()) return;
                    setTextIfChanged(status, com.deepseekharness.app.util.UiText.status(text));
                    if (text.startsWith("环境任务进行中") && isResumed())
                      status.postDelayed(
                          () -> {
                            if (request == adbStatusRequest && getView() == page && isResumed())
                              refreshAdbStatus(status);
                          },
                          1500);
                  });
            },
            "adb-status")
        .start();
  }

  private String computeAdbStatus(Context app) {
    try {
      if (!pref(app, "adb_enabled", false))
        return com.deepseekharness.app.util.UiText.text("ADB 已关闭。开启后才会保持连接。");
      String bridge = DeviceBridgeService.adbState;
      String detail = DeviceBridgeService.adbDetail == null ? "" : DeviceBridgeService.adbDetail;
      HarnessController hc = HarnessController.get(app);
      String st = hc.proot().isEnvironmentReady() ? AdbBridge.status(hc.proot()) : "env:not_ready";
      if (st.startsWith("ENVIRONMENT_BUSY") || "environment_busy".equals(bridge))
        return "环境任务进行中，ADB 暂停检查，完成后自动重试。" + (detail.isEmpty() ? "" : "\n" + detail);
      boolean key = st.contains("key=YES");
      String port = "?";
      int p = st.indexOf("port=");
      if (p >= 0) port = st.substring(p + 5).trim();
      if ("need_pair".equals(bridge)) {
        return com.deepseekharness.app.util.UiText.text("配对已失效，请重新配对。");
      } else if ("reconnecting".equals(bridge)) {
        return com.deepseekharness.app.util.UiText.format(
            "正在重连无线调试…%s", (detail.isEmpty() ? "" : "\n" + detail));
      } else if ("connected".equals(bridge)) {
        return com.deepseekharness.app.util.UiText.format(
            "连接已验证 · 端口 %s%s", port, (detail.isEmpty() ? "" : "\n" + detail));
      } else if (key) {
        return com.deepseekharness.app.util.UiText.format(
            "配对密钥已保存，连接尚未验证。%s", (detail.isEmpty() ? "" : "\n" + detail));
      } else {
        return com.deepseekharness.app.util.UiText.text("尚未配对：点下方「ADB 无线配对」，配对后还会验证连接。");
      }
    } catch (Throwable e) {
      return com.deepseekharness.app.util.UiText.format("ADB 状态读取失败：%s", e.getMessage());
    }
  }

  private void openBatteryOpt(Context ctx) {
    openBatterySettings(
        ctx,
        this::startActivity,
        () -> toast(com.deepseekharness.app.util.UiText.text("无法打开电池优化设置")));
  }

  /** 只在用户点击后导航到系统设置；不申请或自动修改电池优化白名单。 */
  static void openBatterySettings(
      Context context, java.util.function.Consumer<Intent> open, Runnable failed) {
    try {
      open.accept(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
      return;
    } catch (RuntimeException unavailable) {
      // OEM may omit or restrict the general battery settings Activity.
    }
    try {
      open.accept(
          new Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:" + context.getPackageName())));
    } catch (RuntimeException unavailable) {
      failed.run();
    }
  }

  /** 「所有文件访问」入口：Android 11+ 跳系统 MANAGE 设置；Android 6-10 请求 WRITE_EXTERNAL_STORAGE。 */
  private void openAllFilesAccess(Context ctx) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      try {
        Intent i =
            new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        startActivity(i);
      } catch (Throwable e) {
        try {
          startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        } catch (Throwable e2) {
          toast(com.deepseekharness.app.util.UiText.format("打开设置失败：%s", e2.getMessage()));
        }
      }
      return;
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      // Android 6-10：运行时请求 WRITE_EXTERNAL_STORAGE（Android 10 作用域存储下尽力而为）
      if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
          != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 501);
      } else {
        toast(com.deepseekharness.app.util.UiText.text("存储权限已授予，容器可访问手机存储"));
      }
      return;
    }
    toast(com.deepseekharness.app.util.UiText.text("当前系统无需存储权限"));
  }

  /** 刷新「所有文件访问权限」状态行（含从系统设置返回后的更新）。 */
  /** 把一行的点击（含右侧箭头）转发给某个隐藏按钮，让旧绑定逻辑继续生效。 */
  /** 文本没变就不 setText：这个页面每 2 秒刷一次，别让相同内容反复触发 measure/draw。 */
  private static void setTextIfChanged(TextView view, String text) {
    if (view == null || text == null) return;
    if (!text.contentEquals(view.getText())) view.setText(text);
  }

  /** 开关行：点整行等于点开关，开关自己的监听照旧生效。 */
  private void bindSwitchRow(android.view.View root, int rowId, int switchId) {
    android.view.View row = root.findViewById(rowId);
    android.view.View sw = root.findViewById(switchId);
    if (row == null || sw == null) return;
    row.setOnClickListener(v -> sw.performClick());
  }

  private void bindRow(android.view.View root, int rowId, int chevronId, int actionId) {
    android.view.View action = root.findViewById(actionId);
    if (action == null) return;
    android.view.View.OnClickListener click = v -> action.performClick();
    android.view.View row = root.findViewById(rowId);
    if (row != null) row.setOnClickListener(click);
    android.view.View chevron = root.findViewById(chevronId);
    if (chevron != null) chevron.setOnClickListener(click);
  }

  private void refreshAllFilesStatus(TextView status) {
    if (status == null) return;
    boolean granted;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      granted = Environment.isExternalStorageManager();
    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      granted =
          requireContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
              == android.content.pm.PackageManager.PERMISSION_GRANTED;
    } else {
      granted = true;
    }
    setTextIfChanged(
        status,
        granted
            ? com.deepseekharness.app.util.UiText.choose(
                "已开启：可读写系统允许的共享存储；其他应用私有目录仍受系统限制",
                "Allowed: shared storage permitted by Android; other apps' private directories remain restricted")
            : com.deepseekharness.app.util.UiText.text("未开启：仅能访问 App 私有目录；去系统设置开启后可访问全部文件"));
    // 224：说明文字统一用次要色（和设置页一致），状态交给开关和措辞表达。
    int secondary = status.getResources().getColor(R.color.text_secondary, null);
    if (status.getCurrentTextColor() != secondary) status.setTextColor(secondary);
  }

  /** 分别显示系统开关与本次实际连接；开启开关不代表服务已经绑定。 */
  private void refreshA11yStatus(TextView status) {
    if (status == null) return;
    String st = DshaAccessibilityService.enabledState(requireContext());
    boolean enabled = "YES".equals(st);
    boolean connected = enabled && DshaAccessibilityService.connected();
    if (enabled) {
      setTextIfChanged(
          status,
          connected
              ? com.deepseekharness.app.util.UiText.choose(
                  "已连接，可以在对话中使用 Computer Use。",
                  "Connected. Computer Use is available in conversations.")
              : com.deepseekharness.app.util.UiText.choose(
                  "开关已开启，正在等待系统连接；可重新开关服务。",
                  "Enabled, waiting for the system to connect. Try toggling the service."));
    } else if ("NO".equals(st)) {
      setTextIfChanged(status, com.deepseekharness.app.util.UiText.text("未开启 · 点上方按两步完成设置"));
    } else {
      setTextIfChanged(status, com.deepseekharness.app.util.UiText.text("尚未连接 · 点上方查看设置步骤"));
    }
    int secondary = status.getResources().getColor(R.color.text_secondary, null);
    if (status.getCurrentTextColor() != secondary) status.setTextColor(secondary);
  }
}
