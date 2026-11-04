package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.UiText;

/**
 * 设置 · 实验功能。
 *
 * <p>目前只承载一个开关：应用沙箱访问。开启后，Agent 可以通过系统文档选择器
 * （{@code ACTION_OPEN_DOCUMENT_TREE}）向其它应用申请目录授权，并在拿到授权后只读浏览其私有目录。
 * 授权本身由系统 DocumentsUI 发放，DSHA 不持有 MANAGE_DOCUMENTS，也不修改任何外部 APK。</p>
 *
 * <p>开关状态只落 {@link ConfigStore#isSandboxReadEnabled()}；页面不缓存授权，每次恢复时重读。</p>
 */
public class ExperimentalFragment extends Fragment {

  private View root;
  private ConfigStore config;
  private CompoundButton sandbox;
  private TextView sandboxSub;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    root = inflater.inflate(R.layout.fragment_experimental, container, false);
    config = HarnessController.get(requireContext()).config();

    InfoHint.bind(
        root.findViewById(R.id.experimental_intro),
        UiText.text("实验功能"),
        UiText.text(
            "这里放置尚在验证中的能力。开关默认关闭，开启前请确认你了解它的影响范围；关闭后相关入口立即失效。"));

    ((TextView) root.findViewById(R.id.experimental_label_sandbox))
        .setText(UiText.text("跨应用访问"));

    ((TextView) root.findViewById(R.id.experimental_sandbox_title))
        .setText(UiText.text("应用沙箱访问"));

    sandboxSub = root.findViewById(R.id.experimental_sandbox_sub);
    sandbox = root.findViewById(R.id.experimental_sandbox);
    sandbox.setContentDescription(UiText.text("应用沙箱访问"));
    sandbox.setChecked(config.isSandboxReadEnabled());
    renderSandboxSub(sandbox.isChecked());
    sandbox.setOnCheckedChangeListener(
        (button, checked) -> {
          config.setSandboxReadEnabled(checked);
          renderSandboxSub(checked);
        });
    bindRowToggle(R.id.experimental_sandbox_text, sandbox);
    return root;
  }

  private void renderSandboxSub(boolean enabled) {
    sandboxSub.setText(
        enabled
            ? UiText.text("已开启：经系统授权后可只读浏览其它应用的数据目录")
            : UiText.text("经系统授权，只读浏览其它应用的数据目录"));
  }

  /** 点行内文字也能切换开关（与设置页其它开关行一致）。 */
  private void bindRowToggle(int textColumn, CompoundButton target) {
    root.findViewById(textColumn)
        .setOnClickListener(
            v -> {
              if (target.isEnabled()) target.toggle();
            });
  }
}
