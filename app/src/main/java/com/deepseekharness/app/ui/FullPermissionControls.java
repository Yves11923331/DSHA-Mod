package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.util.NativeConsentPolicy;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Local DSHA ASK confirmation toggle. It never requests or changes a device/system capability.
 *
 * <p>224：整块换成和设置页同一套行 —— 一行一件事（开关行 / 条款行 / 恢复行 /
 * 重建行 / 诊断行），尺寸与图标由 {@link GrantRow} 统一，不再出现 18sp 标题、
 * 20dp 内边距和默认样式按钮。
 */
final class FullPermissionControls implements AutoCloseable {
  private final Context app;
  private final Runnable policyChanged;
  private final GrantRow globalRow;
  private final MaterialSwitch global;
  private final TextView status;
  /** 保存失败 / 需要重选时才出现的两行「重新选择」。 */
  private final LinearLayout recoveryGroup;

  private final GrantRow recoveryOnRow;
  private final GrantRow recoveryOffRow;
  /** 「重新设置本机确认」行（含分隔线）。 */
  private final LinearLayout rebuildGroup;

  private final GrantRow rebuildRow;
  private final GrantRow diagnosticsRow;
  private boolean rendering, closed;

  FullPermissionControls(Fragment owner, LinearLayout host, Runnable policyChanged) {
    app = owner.requireContext().getApplicationContext();
    this.policyChanged = policyChanged;
    Context ctx = owner.requireContext();

    host.addView(GrantRow.section(ctx, UiText.choose("全局确认", "Global confirmation")));
    LinearLayout card = GrantRow.card(ctx);

    // 1) 总开关行
    globalRow =
        GrantRow.row(
            ctx,
            R.drawable.ic_ui_shield,
            UiText.choose("跳过 DSHA 安全确认", "Skip DSHA safety confirmations"),
            "",
            true,
            false);
    global = globalRow.toggle;
    status = globalRow.subtitle;
    card.addView(globalRow.root);

    // 2) 完整条款（原来 150 字说明收进弹窗，行里只留一句）
    card.addView(GrantRow.divider(ctx));
    final String fullHint =
        UiText.choose(
            "开启后，已获准能力的操作不再询问 DSHA 安全确认；关闭后恢复各项确认规则。此开关仅保存在本机，不随备份迁移。文件访问、Root、ADB、短信、定位、传感器和 Android 系统权限仍按各自授权设置生效，独立拒绝或暂停仍然有效。需要填写的业务内容、恢复范围和保存位置仍由你选择。",
            "When enabled, operations using authorized capabilities skip DSHA safety confirmations. Turning it off restores confirmation rules. This choice stays on this device and is excluded from backups. File access, Root, ADB, SMS, location, sensors and Android permissions keep their separate grants; explicit denial or pause remains effective. Business inputs, restore scope and save destinations still require your selection.");
    GrantRow terms =
        GrantRow.row(
            ctx,
            R.drawable.ic_recovery_document,
            UiText.choose("完整条款", "Full terms"),
            UiText.choose(
                "已授权能力不再弹安全确认；独立拒绝与系统权限仍各自生效",
                "Authorized capabilities skip confirmations; explicit denial and system permissions still apply"));
    terms.root.setOnClickListener(
        v -> CardSheet.show(ctx, UiText.choose("全局权限", "Global permission"), fullHint));
    card.addView(terms.root);

    // 3) 记录损坏 / 需要重选：两行「重新选择」
    recoveryGroup = new LinearLayout(ctx);
    recoveryGroup.setOrientation(LinearLayout.VERTICAL);
    recoveryOnRow =
        GrantRow.row(
            ctx,
            R.drawable.ic_recovery_refresh,
            UiText.choose("重新选择开启并保存", "Choose on and save"),
            UiText.choose("保存到本机，旧请求立即失效", "Saved on this device; old requests become invalid"));
    recoveryOnRow.root.setOnClickListener(
        view -> {
          if (!closed) save(true);
        });
    recoveryOffRow =
        GrantRow.row(
            ctx,
            R.drawable.ic_recovery_refresh,
            UiText.choose("重新选择关闭并保存", "Choose off and save"),
            UiText.choose("保存到本机，旧请求立即失效", "Saved on this device; old requests become invalid"));
    recoveryOffRow.root.setOnClickListener(
        view -> {
          if (!closed) save(false);
        });
    recoveryGroup.addView(GrantRow.divider(ctx));
    recoveryGroup.addView(recoveryOnRow.root);
    recoveryGroup.addView(GrantRow.divider(ctx));
    recoveryGroup.addView(recoveryOffRow.root);
    card.addView(recoveryGroup);

    // 4) 重新设置本机确认（只在记录可重建时出现）
    rebuildGroup = new LinearLayout(ctx);
    rebuildGroup.setOrientation(LinearLayout.VERTICAL);
    rebuildRow =
        GrantRow.row(
            ctx,
            R.drawable.ic_recovery_wrench,
            UiText.choose("重新设置本机确认", "Set up local confirmation again"),
            UiText.choose("保留异常记录，把本机确认重建为关闭", "Keep the invalid record and rebuild as off"));
    rebuildRow.root.setOnClickListener(
        view -> {
          if (closed) return;
          var candidate = new DeviceGrants(app).prepareConsentRecovery();
          if (candidate == null) {
            refresh();
            return;
          }
          // This is a user choice about local authority and must never skip its native
          // confirmation.
          new DshaDialogBuilder(ctx, false)
              .setTitle(UiText.choose("重新设置本机确认", "Set up local confirmation again"))
              .setMessage(
                  UiText.choose(
                      "保留当前异常记录，并将本机全局确认设置重建为关闭。旧的排队确认会失效；不会恢复旧授权。完成后如需免除 DSHA 确认，请自行开启开关。其他能力、数据和文件不受影响。",
                      "Keep the current invalid record and rebuild local global confirmation as off. Old queued confirmations become invalid; no old grant is restored. You can then enable the switch yourself. Other capability settings, data and files are unaffected."))
              .setNegativeButton(UiText.choose("取消", "Cancel"), null)
              .setPositiveButton(
                  UiText.choose("保留原件并重建为关闭", "Keep original and rebuild as off"),
                  (dialog, which) -> {
                    if (closed) return;
                    new DeviceGrants(app).rebuildConsentDisabled(candidate);
                    policyChanged.run();
                    refresh();
                  })
              .show();
        });
    rebuildGroup.addView(GrantRow.divider(ctx));
    rebuildGroup.addView(rebuildRow.root);
    card.addView(rebuildGroup);

    // 5) 诊断详情
    card.addView(GrantRow.divider(ctx));
    diagnosticsRow =
        GrantRow.row(
            ctx,
            R.drawable.ic_recovery_database,
            UiText.choose("诊断详情", "Diagnostic details"),
            UiText.choose("查看本机确认记录的读取与保存状态", "Read and save state of the local confirmation record"));
    diagnosticsRow.root.setOnClickListener(
        view -> {
          if (closed) return;
          CardSheet.show(
              ctx,
              UiText.choose("全局权限诊断", "Global permission diagnostics"),
              diagnosticText(new DeviceGrants(app).consentSnapshot()));
        });
    card.addView(diagnosticsRow.root);

    global.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (rendering || closed) return;
          save(enabled);
        });
    host.addView(card, new LinearLayout.LayoutParams(-1, -2));
    refresh();
  }

  private void save(boolean enabled) {
    new DeviceGrants(app).setGlobalAllowed(enabled);
    policyChanged.run();
    refresh();
  }

  void refresh() {
    if (closed) return;
    var snapshot = new DeviceGrants(app).consentSnapshot();
    rendering = true;
    try {
      boolean hasSwitch = snapshot.hasSwitchValue();
      globalRow.root.setVisibility(hasSwitch ? View.VISIBLE : View.GONE);
      if (hasSwitch) {
        global.setChecked(snapshot.readState() == NativeConsentPolicy.ReadState.ON);
      }
    } finally {
      rendering = false;
    }
    recoveryGroup.setVisibility(
        snapshot.readState() == NativeConsentPolicy.ReadState.SAVE_FAILED
                || snapshot.readState() == NativeConsentPolicy.ReadState.RECONFIRM_REQUIRED
            ? View.VISIBLE
            : View.GONE);
    rebuildGroup.setVisibility(snapshot.canRebuild() ? View.VISIBLE : View.GONE);
    String description =
        switch (snapshot.readState()) {
          case ON ->
              UiText.choose(
                  "已开启：免 DSHA 安全确认；各项能力授权保持独立",
                  "On: skip DSHA confirmation; capability grants remain separate");
          case OFF ->
              UiText.choose("已关闭：按各项 DSHA 确认规则", "Off: follow individual DSHA confirmation rules");
          case UNCONFIGURED ->
              UiText.choose(
                  "已关闭（默认）：按各项 DSHA 确认规则",
                  "Off (default): follow individual DSHA confirmation rules");
          case MALFORMED ->
              UiText.choose(
                  "本机确认设置记录损坏：未重置记录或恢复旧授权",
                  "Local confirmation record is malformed; no reset or grant restored");
          case READ_UNAVAILABLE ->
              UiText.choose(
                  "暂时无法读取本机确认设置；这不代表你已关闭",
                  "Cannot read local confirmation settings; this does not mean you turned them off");
          case CHANGING ->
              UiText.choose(
                  "正在更新本机确认设置；旧请求已失效",
                  "Updating local confirmation settings; old requests are invalid");
          case SAVE_FAILED ->
              UiText.choose(
                  "本次选择保存或核对失败：确认豁免未生效；不会自动恢复旧授权",
                  "Saving or checking this selection failed; confirmation bypass is unavailable; no old grant restored");
          case RECONFIRM_REQUIRED ->
              UiText.choose(
                  "本机设置现已可读；旧确认授权仍已失效，请重新选择并保存",
                  "Local settings are readable again; old consent remains invalid. Choose and save again");
        };
    if (!description.contentEquals(status.getText())) status.setText(description);
  }

  static String diagnosticText(NativeConsentPolicy.Snapshot snapshot) {
    return UiText.format(
        "%s / %s / revision=%s\n%s%s",
        snapshot.readState(),
        snapshot.saveState(),
        snapshot.revision(),
        UiText.choose(
            "revision 用于使旧请求失效，不代表开关状态。普通启动、停止和界面刷新不会修改本机确认设置。",
            "The revision invalidates old requests; it is not the switch state. Normal startup, stopping and UI refresh do not change this local setting."),
        snapshot.saveState() == NativeConsentPolicy.SaveState.CACHE_VERIFIED
            ? UiText.choose(
                "\n仅同进程缓存核对，未验证重开后的落盘值",
                "\nSame-process cache checked; persistence after reopening is unverified")
            : "");
  }

  void pause() {}

  void resume() {
    refresh();
  }

  void cancelForLocationChange() {
    refresh();
  }

  @Override
  public void close() {
    closed = true;
  }
}
