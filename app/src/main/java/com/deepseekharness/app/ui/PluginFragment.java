package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.util.PluginSource;
import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;

import java.util.ArrayList;
import com.deepseekharness.app.util.PluginSort;
import java.util.List;
import java.util.Locale;

/** 插件市场入口与已装插件管理；耗时任务由 Activity 范围的 Repository 承接。 */
public class PluginFragment extends Fragment {
  private PluginRepository repository;
  private PluginRepository.State current;
  private View root, header, footer;
  private boolean renderedMarket;
  private EditText linkInput, search;
  private TextView linkHint;
  private CheckBox hideBuiltin;
  /** 221：不再有「市场/已安装」两个 tab —— 整页就是「添加插件 + 已安装」。 */
  private boolean market = false;
  /** 「从链接安装」那一块是否展开（默认收起，省 163dp 首屏）。 */
  private boolean linkOpen;
  private final java.util.Set<String> expandedPlugins = new java.util.HashSet<>();
  private PluginSort.Mode sortOrder = PluginSort.Mode.NAME_ASC;
  private final List<PluginRepository.Item> visibleItems = new ArrayList<>();
  private List<PluginRepository.Item> renderedItems;
  private String renderedQuery;
  private PluginSort.Mode renderedSort;
  private boolean renderedHideBuiltin, renderedBusy;
  private final Adapter adapter = new Adapter();
  private ArrayList<String> pendingExports = new ArrayList<>();
  private android.net.Uri pendingImport;

  /** 应用级 Repository 拥有失效修订号；页面只记住自己已渲染的修订。 */
  private long refreshedRevision = -1;

  private final android.os.Handler refreshHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable refreshInvalidated = this::refreshInstalledIfNeeded;
  private String environmentNotice;

  /** 只在首次读取或安全启动失效后同步一次；等待中的轮询只读内存状态。 */
  private void refreshInstalledIfNeeded() {
    refreshHandler.removeCallbacks(refreshInvalidated);
    if (root == null || !isResumed() || market) return;
    if (pluginRefreshBlocked()) {
      refreshHandler.postDelayed(refreshInvalidated, 500);
      return;
    }
    long installedRevision = repository.installedRevision();
    if (refreshedRevision == installedRevision) return;
    // 失败由操作结果明确显示，用户可手动重试；不在失败后无限全量刷新。
    refreshedRevision = installedRevision;
    syncInstalledState();
  }

  // 调试自测替换这两个边界，验证刷新时机，不触发真实插件注册或容器任务。
  boolean pluginRefreshBlocked() {
    String blocked = repository.environmentBlockMessage();
    if (!blocked.isEmpty()) {
      PluginRepository.State shown = repository.state().getValue();
      if (!repository.isBusy()) {
        environmentNotice = blocked;
        if (shown == null || !blocked.equals(shown.message)) repository.selectionMessage(blocked);
      }
      return true;
    }
    if (environmentNotice != null) {
      PluginRepository.State shown = repository.state().getValue();
      if (!repository.isBusy() && shown != null && environmentNotice.equals(shown.message))
        repository.selectionMessage(
            com.deepseekharness.app.util.UiText.text("环境任务已结束；当前显示缓存列表，可点「刷新」同步插件状态"));
      environmentNotice = null;
    }
    com.deepseekharness.app.core.HarnessController controller =
        com.deepseekharness.app.core.HarnessController.get(requireContext());
    return repository.isBusy() || controller.isStarting() || controller.isStopping();
  }

  void syncInstalledState() {
    repository.refresh();
  }

  private final ActivityResultLauncher<android.content.Intent> importPicker =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (repository == null) repository = PluginRepository.get(requireContext());
            android.content.Intent data = result.getData();
            android.net.Uri uri = data == null ? null : data.getData();
            if (uri == null
                && data != null
                && data.getClipData() != null
                && data.getClipData().getItemCount() > 0)
              uri = data.getClipData().getItemAt(0).getUri();
            if (result.getResultCode() != android.app.Activity.RESULT_OK || uri == null) {
              repository.selectionMessage(
                  com.deepseekharness.app.util.UiText.text("未选择文件。可再次点击导入插件包。"));
              return;
            }
            if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) {
              repository.selectionMessage(
                  com.deepseekharness.app.util.UiText.text("文件管理器返回的地址无法读取，请改用系统文件选择器。"));
              return;
            }
            if ("file".equals(uri.getScheme())
                && android.os.Build.VERSION.SDK_INT < 30
                && requireContext()
                        .checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
              pendingImport = uri;
              PluginFragment.this.readPermission.launch(
                  android.Manifest.permission.READ_EXTERNAL_STORAGE);
              return;
            }
            repository.importArchive(uri);
          });
  private final ActivityResultLauncher<String> readPermission =
      registerForActivityResult(
          new ActivityResultContracts.RequestPermission(),
          allowed -> {
            android.net.Uri selected = pendingImport;
            pendingImport = null;
            if (allowed && selected != null) repository.importArchive(selected);
            else
              repository.selectionMessage(
                  com.deepseekharness.app.util.UiText.text("未获得文件读取权限，请改用系统文件选择器导入。"));
          });
  private final ActivityResultLauncher<String> exportPicker =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("application/gzip"),
          uri -> {
            if (uri != null && !pendingExports.isEmpty())
              repository.exportArchives(new ArrayList<>(pendingExports), uri);
            pendingExports.clear();
          });

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
    return inflater.inflate(R.layout.fragment_plugins, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
    root = view;
    RecyclerView list = view.findViewById(R.id.pluginList);
    list.setLayoutManager(new LinearLayoutManager(requireContext()));
    // Header/footer 是 RecyclerView 条目，必须在设置 LayoutManager 后 inflate。
    // RecyclerView.generateLayoutParams() 在父级尚未有 LayoutManager 时会直接抛出
    // InflateException，导致点击“插件”导航后整个 Activity 崩溃。
    header = getLayoutInflater().inflate(R.layout.plugin_list_header, list, false);
    footer = getLayoutInflater().inflate(R.layout.plugin_list_footer, list, false);
    renderedItems = null;
    find(R.id.statusText).setOnClickListener(v -> BackgroundTasksActivity.open(requireContext()));
    sortOrder = new com.deepseekharness.app.core.ConfigStore(requireContext()).getPluginSort();
    repository = installedRepository();
    if (getArguments() != null && getArguments().getBoolean("show_installed", false))
      market = false;
    if (saved != null) {
      market = saved.getBoolean("market", true);
      if (saved.containsKey("sortOrder"))
        sortOrder = PluginSort.Mode.parse(saved.getString("sortOrder"));
      else if (saved.getBoolean("enabledFirst")) sortOrder = PluginSort.Mode.ENABLED_FIRST;
      ArrayList<String> names = saved.getStringArrayList("pendingExports");
      if (names != null) pendingExports = names;
      String imported = saved.getString("pendingImport");
      if (imported != null) pendingImport = android.net.Uri.parse(imported);
    }
    linkInput = find(R.id.appbar_github_input);
    linkHint = find(R.id.pluginLinkHint);
    search = find(R.id.pluginSearch);
    hideBuiltin = find(R.id.chkHideBuiltin);
    // 221：默认隐藏官方/内置/随包插件（只留用户自己装的），状态持久化；
    // 控件本身不显示，入口在「排序与筛选」弹窗里。
    hideBuiltin.setChecked(
        new com.deepseekharness.app.core.ConfigStore(requireContext()).getPluginHideBuiltin());
    // 单一回收列表负责整页滚动，标题和操作区也是列表条目。
    list.setItemAnimator(null);
    list.setAdapter(adapter);
    find(R.id.btnPluginWebsite).setOnClickListener(v -> openPluginWebsite());
    // 221：链接安装默认收起，点这一行展开/收起（省首屏 163dp）
    find(R.id.pluginLinkToggle).setOnClickListener(v -> setLinkOpen(!linkOpen));
    find(R.id.btnSort).setOnClickListener(v -> showSortOptions());
    find(R.id.btnRefresh).setOnClickListener(v -> repository.refresh());
    find(R.id.btnPluginUpdates).setOnClickListener(v -> repository.checkUpdates(null));
    find(R.id.btnPluginDownloadSource).setOnClickListener(v -> showDownloadSources());
    updateDownloadSourceLabel();
    ((TextView) find(R.id.pluginDownloadHint))
        .setText(
            com.deepseekharness.app.util.UiText.text("自动选择可用的 npm 源；镜像失败时回退官方源。GitHub 使用官方直连。"));
    find(R.id.btnCancelPluginTask).setOnClickListener(v -> repository.cancelTask());
    find(R.id.btnPluginRestore)
        .setOnClickListener(
            v ->
                new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                    .setTitle(com.deepseekharness.app.util.UiText.text("恢复第三方插件？"))
                    .setMessage(
                        com.deepseekharness.app.util.UiText.text(
                            "恢复安全启动前已启用的插件；之后手动禁用的插件保持禁用。恢复后重启 Web 生效。"))
                    .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
                    .setPositiveButton(
                        com.deepseekharness.app.util.UiText.text("恢复"),
                        (d, which) -> repository.safeMode(false, null))
                    .show());
    find(R.id.btnPluginInstall).setOnClickListener(v -> installLink());
    find(R.id.btnPluginPaste).setOnClickListener(v -> pasteLink());
    find(R.id.btnImport).setOnClickListener(v -> chooseImport(false));
    find(R.id.btnExport).setOnClickListener(v -> chooseExport());
    hideBuiltin.setOnCheckedChangeListener((v, checked) -> render());
    search.addTextChangedListener(watcher(this::render));
    search.setOnEditorActionListener(
        (v, action, event) -> {
          if (action != EditorInfo.IME_ACTION_DONE
              && (event == null
                  || event.getKeyCode() != KeyEvent.KEYCODE_ENTER
                  || event.getAction() != KeyEvent.ACTION_UP)) return false;
          search.clearFocus();
          android.view.inputmethod.InputMethodManager keyboard =
              (android.view.inputmethod.InputMethodManager)
                  requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
          if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
          return true;
        });
    linkInput.addTextChangedListener(watcher(this::recognizeLink));
    linkInput.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == EditorInfo.IME_ACTION_GO
              || action == EditorInfo.IME_ACTION_DONE
              || (event != null
                  && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                  && event.getAction() == KeyEvent.ACTION_UP)) {
            installLink();
            return true;
          }
          return false;
        });
    TextView status = find(R.id.statusText);
    status.setOnClickListener(v -> showPluginStatus());
    repository
        .state()
        .observe(
            getViewLifecycleOwner(),
            state -> {
              current = state;
              render();
            });
    recognizeLink();
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshInstalledIfNeeded();
  }

  PluginRepository installedRepository() {
    return PluginRepository.get(requireContext());
  }

  @Override
  public void onPause() {
    refreshHandler.removeCallbacks(refreshInvalidated);
    super.onPause();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle state) {
    super.onSaveInstanceState(state);
    state.putBoolean("market", market);
    state.putString("sortOrder", sortOrder.name());
    state.putStringArrayList("pendingExports", pendingExports);
    if (pendingImport != null) state.putString("pendingImport", pendingImport.toString());
  }

  @Override
  public void onDestroyView() {
    refreshHandler.removeCallbacks(refreshInvalidated);
    ((RecyclerView) find(R.id.pluginList)).setAdapter(null);
    root = null;
    header = null;
    footer = null;
    linkInput = null;
    search = null;
    linkHint = null;
    hideBuiltin = null;
    super.onDestroyView();
  }

  private <T extends View> T find(int id) {
    T result = root.findViewById(id);
    if (result == null && header != null) result = header.findViewById(id);
    if (result == null && footer != null) result = footer.findViewById(id);
    return result;
  }

  private static TextWatcher watcher(Runnable callback) {
    return new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        callback.run();
      }

      @Override
      public void afterTextChanged(Editable e) {}
    };
  }

  private void updateDownloadSourceLabel() {
    com.deepseekharness.app.util.PluginDownloadSource source =
        new com.deepseekharness.app.core.ConfigStore(requireContext()).getPluginDownloadSource();
    String[] labels = downloadSourceLabels();
    ((TextView) find(R.id.btnPluginDownloadSource))
        .setText(com.deepseekharness.app.util.UiText.format("下载源：%s", labels[source.ordinal()]));
  }

  private String[] downloadSourceLabels() {
    return new String[] {
      com.deepseekharness.app.util.UiText.text("自动选择（推荐）"),
      com.deepseekharness.app.util.UiText.text("npm 官方源"),
      com.deepseekharness.app.util.UiText.text("npmmirror 国内镜像")
    };
  }

  private void showDownloadSources() {
    if (repository.isBusy()) return;
    com.deepseekharness.app.core.ConfigStore config =
        new com.deepseekharness.app.core.ConfigStore(requireContext());
    new DshaDialogBuilder(requireContext())
        .setTitle(com.deepseekharness.app.util.UiText.text("插件下载源"))
        .setSingleChoiceItems(
            downloadSourceLabels(),
            config.getPluginDownloadSource().ordinal(),
            (dialog, which) -> {
              if (!repository.isBusy()) {
                config.setPluginDownloadSource(
                    com.deepseekharness.app.util.PluginDownloadSource.values()[which]);
                updateDownloadSourceLabel();
              }
              dialog.dismiss();
            })
        .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
        .show();
  }

  private void showPluginStatus() {
    if (current == null || current.message.isEmpty()) return;
    var dialog =
        new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
            .setTitle(com.deepseekharness.app.util.UiText.text("插件操作结果"))
            .setMessage(com.deepseekharness.app.util.UiStateText.render(current.message));
    if (!repository.isBusy() && repository.preview().getValue() != null) {
      dialog
          .setPositiveButton(
              com.deepseekharness.app.util.UiText.choose("重试安装", "Retry installation"),
              (d, w) -> repository.confirmPreview())
          .setNeutralButton(
              com.deepseekharness.app.util.UiText.choose("清理临时包", "Remove temporary package"),
              (d, w) -> repository.discardPreview())
          .setNegativeButton(com.deepseekharness.app.util.UiText.text("关闭"), null);
    } else dialog.setPositiveButton(com.deepseekharness.app.util.UiText.text("关闭"), null);
    dialog.show();
  }

  private void recognizeLink() {
    if (root == null) return;
    String input = linkInput.getText().toString();
    boolean valid = false;
    try {
      PluginSource source = PluginSource.parse(input);
      linkHint.setText(com.deepseekharness.app.util.UiText.format("已识别：%s", source.description()));
      valid = true;
    } catch (IllegalArgumentException error) {
      linkHint.setText(
          input.trim().isEmpty()
              ? com.deepseekharness.app.util.UiText.text("支持仓库、分支/子目录、Release 下载和压缩包直链")
              : error.getMessage());
    }
    find(R.id.btnPluginInstall).setEnabled(valid && !repository.isBusy());
  }

  /** 221：展开/收起「从链接安装」的输入区；chevron 跟着转，展开时自动聚焦。 */
  private void setLinkOpen(boolean open) {
    linkOpen = open;
    View section = find(R.id.pluginLinkSection);
    if (section != null) section.setVisibility(open ? View.VISIBLE : View.GONE);
    View chevron = find(R.id.pluginLinkChevron);
    if (chevron != null) chevron.setRotation(open ? 180f : 0f);
    if (!open) {
      View input = find(R.id.appbar_github_input);
      if (input != null) input.clearFocus();
      return;
    }
    View input = find(R.id.appbar_github_input);
    if (input != null) {
      input.requestFocus();
      android.view.inputmethod.InputMethodManager keyboard =
          (android.view.inputmethod.InputMethodManager)
              requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
      if (keyboard != null) keyboard.showSoftInput(input, 0);
    }
  }

  private void openPluginWebsite() {
    try {
      startActivity(new android.content.Intent(requireContext(), CommunityPluginsActivity.class));
    } catch (RuntimeException error) {
      toast(
          com.deepseekharness.app.util.UiText.format(
              "无法打开插件页（%s），请稍后重试", error.getClass().getSimpleName()));
    }
  }

  private void installLink() {
    if (repository.isBusy()) return;
    try {
      PluginSource source = PluginSource.parse(linkInput.getText().toString());
      android.view.inputmethod.InputMethodManager keyboard =
          (android.view.inputmethod.InputMethodManager)
              requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
      if (keyboard != null) keyboard.hideSoftInputFromWindow(linkInput.getWindowToken(), 0);
      linkInput.clearFocus();
      repository.install(source);
    } catch (IllegalArgumentException error) {
      toast(error.getMessage());
    }
  }

  private void chooseImport(boolean alternative) {
    if (repository.isBusy()) {
      toast(com.deepseekharness.app.util.UiText.text("请等待当前插件操作完成后再导入"));
      return;
    }
    View focus = requireActivity().getCurrentFocus();
    if (focus != null) {
      android.view.inputmethod.InputMethodManager keyboard =
          (android.view.inputmethod.InputMethodManager)
              requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
      if (keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
      focus.clearFocus();
    }
    repository.selectionMessage(com.deepseekharness.app.util.UiText.text("请选择 ZIP / TAR.GZ 插件包。"));
    try {
      importPicker.launch(PluginFilePicker.intent(requireContext(), alternative));
    } catch (android.content.ActivityNotFoundException error) {
      if (!alternative) {
        chooseImport(true);
        return;
      }
      repository.selectionMessage(
          com.deepseekharness.app.util.UiText.text("未找到可用的文件选择器，请启用系统「文件」应用后重试。"));
      toast(com.deepseekharness.app.util.UiText.text("没有可用的文件选择器"));
    } catch (RuntimeException error) {
      repository.selectionMessage(
          com.deepseekharness.app.util.UiText.format(
              "无法打开文件选择器，请检查系统文件应用：%s", error.getClass().getSimpleName()));
    }
  }

  private void pasteLink() {
    ClipboardManager clipboard =
        (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
    ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
    if (clip == null || clip.getItemCount() == 0) {
      toast(com.deepseekharness.app.util.UiText.text("剪贴板没有链接"));
      return;
    }
    CharSequence text = clip.getItemAt(0).coerceToText(requireContext());
    if (text != null) linkInput.setText(text);
  }

  private void render() {
    if (root == null || current == null) return;

    // 221：整页只有「添加插件」+「已安装」，没有 tab 切换
    find(R.id.btnPluginRestore).setVisibility(repository.isSafeMode() ? View.VISIBLE : View.GONE);

    boolean hasMessage = !current.message.isEmpty();
    find(R.id.taskBar).setVisibility((current.busy || hasMessage) ? View.VISIBLE : View.GONE);
    find(R.id.pluginBusy).setVisibility(current.busy ? View.VISIBLE : View.GONE);
    find(R.id.statusText).setVisibility(hasMessage ? View.VISIBLE : View.GONE);

    android.widget.ProgressBar progress = find(R.id.pluginBusy);
    progress.setIndeterminate(current.percent < 0);
    if (current.percent >= 0) progress.setProgress(current.percent);

    find(R.id.btnCancelPluginTask).setVisibility(current.busy ? View.VISIBLE : View.GONE);
    find(R.id.btnCancelPluginTask).setEnabled(current.cancellable);
    ((TextView) find(R.id.statusText)).setText(UiStateText.render(current.message));

    for (int id :
        new int[] {
          R.id.btnImport,
          R.id.btnExport,
          R.id.btnRefresh,
          R.id.btnPluginUpdates,
          R.id.btnPluginRestore,
          R.id.btnPluginDownloadSource
        }) find(id).setEnabled(!current.busy);

    // 统计：已启用 X · 共 Y 个
    int total = current.items.size();
    int enabledCount = 0;
    for (PluginRepository.Item item : current.items) if (item.enabled) enabledCount++;
    ((TextView) find(R.id.pluginStat))
        .setText(
            !repository.hasKnownItems()
                ? UiText.text(current.busy ? "正在读取插件…" : "插件列表尚未确认")
                : total == 0
                    ? UiText.text("还没有安装插件")
                    : UiText.format("已启用 %d · 共 %d 个", enabledCount, total));

    // 排序按钮的状态写在 contentDescription 里（图标按钮没有文字）
    android.view.View sort = find(R.id.btnSort);
    sort.setContentDescription(getString(R.string.plugin_sort_title) + " · " + sortLabels()[sortOrder.ordinal()]);

    String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
    boolean changed =
        renderedItems != current.items
            || !query.equals(renderedQuery)
            || renderedSort != sortOrder
            || renderedHideBuiltin != hideBuiltin.isChecked();
    boolean rebind = changed || renderedBusy != current.busy;
    // 阶段进度每 400 ms 更新；列表内容未变时只更新状态，保留滚动和开关。
    if (changed) {
      visibleItems.clear();
      for (PluginRepository.Item item : current.items) {
        if (hideBuiltin.isChecked() && (item.builtin || item.official || item.runtimeProvided))
          continue;
        if (!(item.name + " " + item.description).toLowerCase(Locale.ROOT).contains(query))
          continue;
        visibleItems.add(item);
      }
      visibleItems.sort(
          PluginSort.comparator(
              sortOrder, it -> it.name, it -> it.enabled, it -> it.updateAvailable));
      renderedItems = current.items;
      renderedQuery = query;
      renderedSort = sortOrder;
      renderedHideBuiltin = hideBuiltin.isChecked();
    }
    renderedBusy = current.busy;

    TextView empty = find(R.id.pluginEmpty);
    empty.setVisibility(visibleItems.isEmpty() ? View.VISIBLE : View.GONE);
    empty.setText(
        current.busy
            ? UiText.text("正在读取插件…")
            : !repository.hasKnownItems()
                ? UiText.text("插件列表尚未确认，请点右上角刷新重试")
                : query.isEmpty()
                    ? UiText.text("还没有插件。用上面的「添加插件」装一个吧")
                    : UiText.text("没有符合条件的插件"));
    if (rebind) adapter.notifyDataSetChanged();
    recognizeLink();
  }

  private String[] sortLabels() {
    return new String[] {
      getString(R.string.plugin_sort_az),
      getString(R.string.plugin_sort_za),
      getString(R.string.plugin_sort_enabled),
      getString(R.string.plugin_sort_updates)
    };
  }

  /** 221：「排序与筛选」——排序单选 + 隐藏官方/内置开关，合在一个弹窗里。 */
  private void showSortOptions() {
    final String[] labels = sortLabels();
    final android.content.Context ctx = requireContext();
    final float density = getResources().getDisplayMetrics().density;
    int pad = (int) (20 * density + 0.5f);

    android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
    box.setOrientation(android.widget.LinearLayout.VERTICAL);
    box.setPadding(pad, (int) (6 * density + 0.5f), pad, 0);

    final android.widget.RadioGroup group = new android.widget.RadioGroup(ctx);
    for (int i = 0; i < labels.length; i++) {
      android.widget.RadioButton item = new android.widget.RadioButton(ctx);
      item.setId(i + 1);
      item.setText(labels[i]);
      item.setTextSize(14);
      item.setPadding(0, (int) (11 * density + 0.5f), 0, (int) (11 * density + 0.5f));
      group.addView(item);
    }
    group.check(sortOrder.ordinal() + 1);
    box.addView(group);

    final android.widget.CheckBox hide = new android.widget.CheckBox(ctx);
    hide.setText(UiText.text("隐藏官方与内置插件"));
    hide.setTextSize(13);
    hide.setChecked(hideBuiltin.isChecked());
    hide.setPadding(0, (int) (14 * density + 0.5f), 0, (int) (10 * density + 0.5f));
    box.addView(hide);

    new DshaDialogBuilder(ctx)
        .setTitle(UiText.choose("排序与筛选", "Sort and filter"))
        .setView(box)
        .setNegativeButton(UiText.text("取消"), null)
        .setPositiveButton(
            UiText.text("应用"),
            (dialog, which) -> {
              int checked = group.getCheckedRadioButtonId();
              if (checked >= 1 && checked <= PluginSort.Mode.values().length) {
                sortOrder = PluginSort.Mode.values()[checked - 1];
                new com.deepseekharness.app.core.ConfigStore(ctx).setPluginSort(sortOrder);
              }
              boolean hideNow = hide.isChecked();
              new com.deepseekharness.app.core.ConfigStore(ctx).setPluginHideBuiltin(hideNow);
              if (hideBuiltin.isChecked() == hideNow) render();
              else hideBuiltin.setChecked(hideNow); // 监听里会 render
            })
        .show();
  }

  private void chooseExport() {
    if (current == null || repository.isBusy()) return;
    List<String> names = new ArrayList<>();
    for (PluginRepository.Item item : current.items) if (item.exportable) names.add(item.name);
    if (names.isEmpty()) {
      toast(com.deepseekharness.app.util.UiText.text("没有可导出的插件"));
      return;
    }
    boolean[] checked = new boolean[names.size()];
    AlertDialog dialog =
        new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
            .setTitle(com.deepseekharness.app.util.UiText.text("选择要导出的插件"))
            .setMultiChoiceItems(
                names.toArray(new String[0]),
                checked,
                (d, which, value) -> {
                  checked[which] = value;
                  boolean any = false;
                  for (boolean selected : checked) any |= selected;
                  ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(any);
                })
            .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
            .setPositiveButton(
                com.deepseekharness.app.util.UiText.text("选择保存位置"),
                (d, which) -> {
                  ArrayList<String> selected = new ArrayList<>();
                  for (int i = 0; i < checked.length; i++)
                    if (checked[i]) selected.add(names.get(i));
                  beginExport(selected);
                })
            .create();
    dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false));
    dialog.show();
  }

  private void beginExport(ArrayList<String> names) {
    if (names.isEmpty() || repository.isBusy()) return;
    pendingExports = names;
    String name =
        names.size() == 1 ? names.get(0).replaceAll("[^A-Za-z0-9._-]", "_") : "DSHA-plugins";
    String stamp =
        new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
    try {
      exportPicker.launch(name + "-" + stamp + ".tar.gz");
    } catch (Exception error) {
      pendingExports.clear();
      toast(com.deepseekharness.app.util.UiText.text("无法打开保存位置选择器"));
    }
  }

  private void toggle(PluginRepository.Item item, boolean enabled) {
    if (repository.isBusy()) {
      adapter.notifyDataSetChanged();
      return;
    }
    if (item.official && !enabled) {
      new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext(), true)
          .setTitle(com.deepseekharness.app.util.UiText.text("禁用官方核心？"))
          .setMessage(
              item.name + com.deepseekharness.app.util.UiText.text(" 是 Web 运行所需的核心，禁用后页面可能无法启动。"))
          .setPositiveButton(
              com.deepseekharness.app.util.UiText.text("禁用"),
              (d, which) -> repository.setEnabled(item, false))
          .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
          .setOnDismissListener(d -> adapter.notifyDataSetChanged())
          .show();
    } else repository.setEnabled(item, enabled);
  }

  private void itemActions(PluginRepository.Item item) {
    List<PluginAction> actions = new ArrayList<>();
    actions.add(PluginAction.DETAIL);
    actions.add(PluginAction.COPY_NAME);
    if (!item.source.isEmpty()) actions.add(PluginAction.COPY_SOURCE);
    if (item.exportable) actions.add(PluginAction.EXPORT);
    if (item.updatable) actions.add(PluginAction.CHECK);
    if (item.updateAvailable) actions.add(PluginAction.UPDATE);
    if (!item.rollbackVersion.isEmpty()) actions.add(PluginAction.ROLLBACK);
    if (item.deletable) actions.add(PluginAction.DELETE);
    new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
        .setTitle(item.name)
        .setItems(
            actions.stream().map(action -> actionLabel(action, item)).toArray(String[]::new),
            (d, which) -> {
              PluginAction action = actions.get(which);
              if (action == PluginAction.DETAIL) {
                new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                    .setTitle(item.name)
                    .setMessage(
                        com.deepseekharness.app.util.UiText.format(
                            "%s\n\n版本：%s%s%s%s%s%s",
                            item.builtin
                                ? com.deepseekharness.app.util.UiStateText.render(item.description)
                                : item.description,
                            item.version,
                            (item.location.isEmpty()
                                ? ""
                                : com.deepseekharness.app.util.UiText.format(
                                    "\n位置：%s", item.location)),
                            (item.source.isEmpty()
                                ? ""
                                : com.deepseekharness.app.util.UiText.format(
                                    "\n来源：%s", item.source)),
                            (item.latestVersion.isEmpty()
                                ? ""
                                : com.deepseekharness.app.util.UiText.format(
                                    "\n上次检查版本：%s", item.latestVersion)),
                            (item.updateMessage.isEmpty()
                                ? ""
                                : "\n"
                                    + com.deepseekharness.app.util.UiStateText.render(
                                        item.updateMessage)),
                            (item.rollbackVersion.isEmpty()
                                ? ""
                                : com.deepseekharness.app.util.UiText.format(
                                    "\n可回退：%s", item.rollbackVersion))))
                    .setPositiveButton(com.deepseekharness.app.util.UiText.text("关闭"), null)
                    .setNeutralButton(
                        com.deepseekharness.app.util.UiText.choose("能力声明", "Capabilities"),
                        (dialog, button) ->
                            repository.inspectCapabilities(
                                item,
                                details -> {
                                  if (!isAdded()) return;
                                  new DshaDialogBuilder(requireContext())
                                      .setTitle(item.name)
                                      .setMessage(details)
                                      .setPositiveButton(
                                          com.deepseekharness.app.util.UiText.text("关闭"), null)
                                      .show();
                                }))
                    .show();
              } else if (action == PluginAction.CHECK) {
                repository.checkUpdates(item);
              } else if (action == PluginAction.UPDATE) {
                repository.prepareUpdate(item);
              } else if (action == PluginAction.ROLLBACK) {
                new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext(), true)
                    .setTitle(com.deepseekharness.app.util.UiText.text("回退插件？"))
                    .setMessage(
                        item.name
                            + com.deepseekharness.app.util.UiText.text("：")
                            + item.version
                            + " → "
                            + item.rollbackVersion
                            + com.deepseekharness.app.util.UiText.text(
                                "\n只恢复插件文件，当前启用状态和对话数据保留；重启 Web 生效。"))
                    .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
                    .setPositiveButton(
                        com.deepseekharness.app.util.UiText.text("回退"),
                        (confirm, button) -> repository.rollback(item))
                    .show();
              } else if (action == PluginAction.EXPORT) {
                ArrayList<String> names = new ArrayList<>();
                names.add(item.name);
                beginExport(names);
              } else if (action == PluginAction.DELETE) {
                confirmDelete(item);
              } else {
                ClipboardManager clipboard =
                    (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null)
                  clipboard.setPrimaryClip(
                      ClipData.newPlainText(
                          com.deepseekharness.app.util.UiText.text("插件"),
                          action == PluginAction.COPY_NAME ? item.name : item.source));
                toast(com.deepseekharness.app.util.UiText.text("已复制"));
              }
            })
        .show();
  }

  private enum PluginAction {
    DETAIL,
    COPY_NAME,
    COPY_SOURCE,
    EXPORT,
    CHECK,
    UPDATE,
    ROLLBACK,
    DELETE
  }

  private String actionLabel(PluginAction action, PluginRepository.Item item) {
    return switch (action) {
      case DETAIL -> com.deepseekharness.app.util.UiText.text("查看详情");
      case COPY_NAME -> com.deepseekharness.app.util.UiText.text("复制插件名称");
      case COPY_SOURCE -> com.deepseekharness.app.util.UiText.text("复制来源链接");
      case EXPORT -> com.deepseekharness.app.util.UiText.text("导出插件包");
      case CHECK -> com.deepseekharness.app.util.UiText.text("检查插件更新");
      case UPDATE -> com.deepseekharness.app.util.UiText.format("更新至 %s", item.latestVersion);
      case ROLLBACK -> com.deepseekharness.app.util.UiText.format("回退至 %s", item.rollbackVersion);
      case DELETE -> com.deepseekharness.app.util.UiText.text("删除插件");
    };
  }

  private void confirmDelete(PluginRepository.Item item) {
    if (!item.deletable || repository.isBusy()) {
      toast(com.deepseekharness.app.util.UiText.text("请等待当前插件操作完成"));
      return;
    }
    new DshaDialogBuilder(requireContext(), true)
        .setTitle(com.deepseekharness.app.util.UiText.choose("删除插件？", "Delete plugin?"))
        .setMessage(
            com.deepseekharness.app.util.UiText.format(
                "%s 将从加载路径移除；原件保留在数据页的保留区。对话、其他插件和外部源码目录保留。", item.name))
        .setNegativeButton(com.deepseekharness.app.util.UiText.choose("取消", "Cancel"), null)
        .setPositiveButton(
            com.deepseekharness.app.util.UiText.choose("删除", "Delete"),
            (dialog, which) -> {
              if (!repository.isBusy()) repository.delete(item);
            })
        .show();
  }

  private void toast(String message) {
    if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
  }

  private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
    class Holder extends RecyclerView.ViewHolder {
      final android.widget.ImageView icon;
      final TextView name, version, badge, desc, status, stateDot;
      final android.widget.CompoundButton toggle;

      Holder(View view) {
        super(view);
        icon = view.findViewById(R.id.pluginIcon);
        name = view.findViewById(R.id.pluginName);
        version = view.findViewById(R.id.pluginVersion);
        badge = view.findViewById(R.id.pluginBadge);
        desc = view.findViewById(R.id.pluginDesc);
        status = view.findViewById(R.id.pluginStatus);
        stateDot = view.findViewById(R.id.pluginStateDot);
        toggle = view.findViewById(R.id.pluginSwitch);
      }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
      if (type != 1) {
        View content = type == 0 ? header : footer;
        if (content.getParent() instanceof ViewGroup)
          ((ViewGroup) content.getParent()).removeView(content);
        return new Holder(content);
      }
      return new Holder(
          LayoutInflater.from(parent.getContext()).inflate(R.layout.item_plugin, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
      if (getItemViewType(position) != 1) return;
      PluginRepository.Item item = visibleItems.get(position - 1);

      holder.icon.setImageResource(pluginIcon(item));
      holder.icon.setAlpha(item.enabled ? 1f : 0.4f);

      holder.name.setText(item.name);
      holder.version.setText(item.version.isEmpty() ? "" : "v" + item.version);
      holder.desc.setText(pluginDescription(item));
      bindBadge(holder.badge, item);

      holder.status.setText(pluginState(item));
      holder.stateDot.setTextColor(
          requireContext()
              .getColor(
                  !item.available ? R.color.warn : item.enabled ? R.color.ok : R.color.text_muted));

      holder.toggle.setVisibility(item.dynamic ? View.GONE : View.VISIBLE);
      holder.toggle.setOnCheckedChangeListener(null);
      holder.toggle.setChecked(item.enabled);
      holder.toggle.setContentDescription(
          UiText.format(item.enabled ? "禁用 %s" : "启用 %s", item.name));
      holder.toggle.jumpDrawablesToCurrentState();
      holder.toggle.setEnabled(!repository.isBusy() && (item.available || item.enabled));
      holder.toggle.setOnCheckedChangeListener(
          (v, checked) -> {
            if (checked != item.enabled) toggle(item, checked);
          });

      // 221：点整张卡打开操作菜单（详情/复制/导出/更新/回退/删除），开关单独拦截。
      holder.itemView.setOnClickListener(v -> itemActions(item));
      holder.itemView.setContentDescription(item.name);
    }

    @Override
    public int getItemViewType(int position) {
      return position == 0 ? 0 : position == getItemCount() - 1 ? 2 : 1;
    }

    @Override
    public int getItemCount() {
      return 2 + visibleItems.size();
    }
  }

  // ---------------------------------------------------------------- 221 卡片展示

  /** 徽标：官方 / 随包 / 本地。 */
  private void bindBadge(TextView badge, PluginRepository.Item item) {
    if (item.official || item.builtin) {
      badge.setText(UiText.choose("官方", "Official"));
      badge.setBackgroundResource(R.drawable.bg_badge_official);
      badge.setTextColor(requireContext().getColor(R.color.primary));
    } else if (item.runtimeProvided) {
      badge.setText(UiText.choose("随包", "Bundled"));
      badge.setBackgroundResource(R.drawable.bg_badge_local);
      badge.setTextColor(requireContext().getColor(R.color.text_secondary));
    } else {
      badge.setText(UiText.choose("本地", "Local"));
      badge.setBackgroundResource(R.drawable.bg_badge_community);
      badge.setTextColor(requireContext().getColor(R.color.ok));
    }
  }

  private String pluginDescription(PluginRepository.Item item) {
    if (!item.description.isEmpty()) {
      return item.builtin ? UiStateText.render(item.description) : UiText.raw(item.description);
    }
    return item.official
        ? UiText.text("官方核心")
        : item.runtimeProvided
            ? UiText.choose("随 DSH 安装提供", "Provided by DSH")
            : item.builtin ? UiText.text("DSHA 内置插件") : UiText.text("第三方插件");
  }

  private String pluginState(PluginRepository.Item item) {
    String base;
    if (!item.available) base = UiText.text("实体缺失，请重新导入");
    else if (item.dynamic)
      base = UiText.text(item.enabled ? "临时插件 · 已运行" : "临时插件 · 未运行");
    else if (item.enabled) base = UiText.text("已启用");
    else if (item.detected) base = UiText.text("已检测，可开启");
    else base = UiText.text("已禁用");
    if (item.updateAvailable && !item.latestVersion.isEmpty())
      base = base + UiText.format(" · 可更新至 %s", item.latestVersion);
    return base;
  }

  /** 卡片图标：按名字挑一个 24dp 线性图标，认不出来就用插件拼图。 */
  private int pluginIcon(PluginRepository.Item item) {
    String n = item.name == null ? "" : item.name.toLowerCase(Locale.ROOT);
    if (n.contains("file") || n.contains("fs")) return R.drawable.ic_plug_files;
    if (n.contains("web") || n.contains("browser")) return R.drawable.ic_plug_web;
    if (n.contains("chart") || n.contains("usage") || n.contains("stat"))
      return R.drawable.ic_plug_chart;
    if (n.contains("search")) return R.drawable.ic_plug_globe;
    if (n.contains("note") || n.contains("memo")) return R.drawable.ic_plug_note;
    if (n.contains("whale") || n.contains("star")) return R.drawable.ic_plug_star;
    return R.drawable.ic_plugins;
  }
}
