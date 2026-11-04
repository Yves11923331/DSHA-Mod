package com.deepseekharness.app.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.saf.SafGrants;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.List;

/**
 * 设备能力授权 → 跨应用文件访问。
 *
 * <p>只管「授权簿」：选哪些目录、允不允许助手写、怎么撤销。真实读写走 3090 桥
 * （{@code /app/saf/*}）与 {@link SafBrowseFragment}；授权本身由系统 DocumentsUI 发放，
 * DSHA 不持 MANAGE_DOCUMENTS，也不改任何外部 APK。
 */
public final class SafAccessFragment extends Fragment {

  private LinearLayout grants;
  private TextView empty;
  private TextView limit;

  private final ActivityResultLauncher<Uri> picker =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocumentTree(),
          uri -> {
            if (uri != null) addGrant(uri);
          });

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View root = inflater.inflate(R.layout.fragment_saf_access, container, false);
    Context ctx = requireContext();

    ((TextView) root.findViewById(R.id.saf_intro))
        .setText(
            UiText.choose(
                "授权来自系统文档选择器：只有自己提供文档服务的应用（或被 MT 一类工具注入过的应用）会出现在里面，普通应用的私有目录不会出现——那是系统隔离，不是功能没生效。授权后，助手可以读写你选中的那个文件夹。",
                "Grants come from the system document picker. Only apps that expose document services (or were patched by tools like MT) show up; other apps' private folders never do — that is system isolation, not a broken switch. Once granted, the assistant can read and write the folder you picked."));

    ((TextView) root.findViewById(R.id.saf_grants_label))
        .setText(UiText.choose("已授权目录", "Granted folders"));

    empty = root.findViewById(R.id.saf_empty);
    empty.setText(UiText.choose("还没有授权任何目录。", "No folders granted yet."));

    limit = root.findViewById(R.id.saf_limit);
    ((Button) root.findViewById(R.id.saf_add))
        .setText(UiText.choose("选择文件夹", "Choose folder"));
    root.findViewById(R.id.saf_add).setOnClickListener(button -> picker.launch(null));

    grants = root.findViewById(R.id.saf_grants);
    render(ctx);
    return root;
  }

  @Override
  public void onResume() {
    super.onResume();
    if (getView() != null) render(requireContext());
  }

  private void render(Context ctx) {
    grants.removeAllViews();
    List<SafGrants.Grant> list = SafGrants.list(ctx);
    empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    limit.setText(
        UiText.format(
            "最多 %d 个目录；当前 %d 个。授权只读写在 DSHA 内生效，系统设置里也能撤销。",
            SafGrants.MAX_TREES,
            list.size()));
    LayoutInflater inflater = LayoutInflater.from(ctx);
    for (SafGrants.Grant grant : list) grants.addView(row(ctx, inflater, grant));
  }

  private View row(Context ctx, LayoutInflater inflater, SafGrants.Grant grant) {
    View item = inflater.inflate(R.layout.item_saf_grant, grants, false);
    ((TextView) item.findViewById(R.id.saf_grant_title)).setText(grant.summary());
    TextView sub = item.findViewById(R.id.saf_grant_sub);
    sub.setText(stateText(grant));

    MaterialSwitch write = item.findViewById(R.id.saf_grant_write);
    write.setContentDescription(UiText.choose("允许助手写入", "Allow assistant writes"));
    write.setChecked(grant.writable && grant.valid);
    write.setEnabled(grant.valid);
    write.setVisibility(grant.valid ? View.VISIBLE : View.GONE);
    write.setOnCheckedChangeListener(
        (button, checked) -> {
          SafGrants.setWritable(ctx, grant.uri, checked);
          sub.setText(stateText(currentOf(ctx, grant)));
        });

    Button browse = item.findViewById(R.id.saf_grant_browse);
    browse.setText(UiText.choose("浏览", "Browse"));
    browse.setEnabled(grant.valid);
    browse.setOnClickListener(
        button ->
            SettingsPanes.open(
                this, SafBrowseFragment.forTree(grant.uri, grant.summary()), "saf-browse"));

    Button remove = item.findViewById(R.id.saf_grant_remove);
    remove.setText(UiText.choose("移除", "Remove"));
    remove.setOnClickListener(button -> confirmRemove(ctx, grant));
    return item;
  }

  private SafGrants.Grant currentOf(Context ctx, SafGrants.Grant known) {
    for (SafGrants.Grant grant : SafGrants.list(ctx)) if (grant.id.equals(known.id)) return grant;
    return known;
  }

  private String stateText(SafGrants.Grant grant) {
    if (!grant.valid)
      return UiText.choose(
          "已失效：目标应用可能已卸载，或授权被系统撤销", "Expired: app uninstalled or grant revoked");
    return grant.writable
        ? UiText.choose("可读写", "Read and write")
        : UiText.choose("只读（已关闭助手写入）", "Read only (assistant writes off)");
  }

  /** 拉起系统选择器并固化授权券；写权限拿不到就退回只读，不假装可写。 */
  private void addGrant(Uri tree) {
    Context ctx = requireContext();
    if (SafGrants.ownProvider(ctx, tree)) {
      toast(UiText.choose("不能选 DSHA 自己的目录", "DSHA's own folder is not allowed"));
      return;
    }
    ContentResolver resolver = ctx.getContentResolver();
    boolean writable = true;
    try {
      resolver.takePersistableUriPermission(
          tree,
          Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (SecurityException noWrite) {
      writable = false;
      try {
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (SecurityException denied) {
        toast(UiText.choose("没有拿到授权：请重新选择", "No permission granted: try again"));
        return;
      }
    }
    if (!SafGrants.remember(ctx, tree, writable)) {
      toast(UiText.choose("授权数量已到上限，请先移除一个", "Grant limit reached; remove one first"));
      return;
    }
    render(ctx);
    toast(
        writable
            ? UiText.choose("已授权：助手可以读写这个目录", "Granted: assistant can read and write")
            : UiText.choose("已授权（只读）：对方没给写权限", "Granted read-only: provider has no write"));
  }

  private void confirmRemove(Context ctx, SafGrants.Grant grant) {
    new DshaDialogBuilder(ctx)
        .setTitle(UiText.choose("移除这个目录？", "Remove this folder?"))
        .setMessage(
            UiText.text(
                grant.summary()
                    + "\n\n"
                    + UiText.choose(
                        "移除后助手不能再读写它，系统里的授权也会一并撤销。",
                        "The assistant loses access and the system grant is released.")))
        .setPositiveButton(
            UiText.choose("移除", "Remove"),
            (dialog, which) -> {
              SafGrants.forget(ctx, grant.uri);
              render(ctx);
            })
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }

  private void toast(String text) {
    if (!isAdded()) return;
    Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
  }
}
