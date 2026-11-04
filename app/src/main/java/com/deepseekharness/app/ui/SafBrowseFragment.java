package com.deepseekharness.app.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.saf.SafTree;
import com.deepseekharness.app.util.UiText;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 跨应用文件访问 · 浏览页：在一条已授权 tree 里只读地看目录与文本文件。
 *
 * <p>这一页是给用户自查用的；助手的真实读写走 3090 桥的 {@code /app/saf/*}。
 * 页面不提供写、删、改名入口——避免用户在别人的应用目录里误操作。
 */
public final class SafBrowseFragment extends Fragment {
  private static final String ARG_TREE = "saf_tree";
  private static final String ARG_TITLE = "saf_title";
  private static final int PREVIEW_LIMIT = 64 * 1024;

  private SafTree tree;
  private String current = "";
  private final List<SafTree.Entry> entries = new ArrayList<>();
  private BaseAdapter adapter;
  private TextView pathView;
  private TextView statusView;
  private Button upButton;

  public static SafBrowseFragment forTree(Uri tree, String title) {
    SafBrowseFragment fragment = new SafBrowseFragment();
    Bundle args = new Bundle();
    args.putString(ARG_TREE, tree.toString());
    args.putString(ARG_TITLE, title);
    fragment.setArguments(args);
    return fragment;
  }

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View root = inflater.inflate(R.layout.fragment_saf_browse, container, false);
    Context ctx = requireContext();
    pathView = root.findViewById(R.id.saf_browse_path);
    statusView = root.findViewById(R.id.saf_browse_status);
    upButton = root.findViewById(R.id.saf_browse_up);
    upButton.setText(UiText.choose("上一级", "Up"));
    upButton.setOnClickListener(button -> load(parentOf(current)));
    ((Button) root.findViewById(R.id.saf_browse_refresh))
        .setText(UiText.choose("刷新", "Refresh"));
    root.findViewById(R.id.saf_browse_refresh).setOnClickListener(button -> load(current));

    ListView list = root.findViewById(R.id.saf_browse_list);
    adapter =
        new BaseAdapter() {
          @Override
          public int getCount() {
            return entries.size();
          }

          @Override
          public SafTree.Entry getItem(int position) {
            return entries.get(position);
          }

          @Override
          public long getItemId(int position) {
            return position;
          }

          @Override
          public View getView(int position, View convertView, ViewGroup parent) {
            View row =
                convertView != null
                    ? convertView
                    : LayoutInflater.from(ctx).inflate(R.layout.item_saf_document, parent, false);
            SafTree.Entry entry = getItem(position);
            ((TextView) row.findViewById(R.id.saf_doc_name)).setText(entry.name);
            ((TextView) row.findViewById(R.id.saf_doc_meta)).setText(meta(ctx, entry));
            ((ImageView) row.findViewById(R.id.saf_doc_icon))
                .setImageResource(
                    entry.directory ? R.drawable.ic_ui2_folder : R.drawable.ic_transfer_document);
            row.findViewById(R.id.saf_doc_chevron)
                .setVisibility(entry.directory ? View.VISIBLE : View.GONE);
            return row;
          }
        };
    list.setAdapter(adapter);
    list.setOnItemClickListener((parent, view, position, id) -> open(entries.get(position)));

    Bundle args = requireArguments();
    String treeUri = args.getString(ARG_TREE, "");
    String title = args.getString(ARG_TITLE, "");
    try {
      tree = new SafTree(requireContext().getContentResolver(), Uri.parse(treeUri));
    } catch (SafTree.Failure failure) {
      pathView.setText(title);
      statusView.setText(describe(failure));
      return root;
    }
    load("");
    return root;
  }

  private void load(String path) {
    if (tree == null) return;
    current = path == null ? "" : path;
    pathView.setText(
        (getArguments() == null ? "" : getArguments().getString(ARG_TITLE, ""))
            + (current.isEmpty() ? "" : " / " + current));
    upButton.setEnabled(!current.isEmpty());
    try {
      List<SafTree.Entry> listed = tree.list(current, SafTree.MAX_ENTRIES);
      entries.clear();
      entries.addAll(listed);
      adapter.notifyDataSetChanged();
      int directories = 0;
      for (SafTree.Entry entry : entries) if (entry.directory) directories++;
      statusView.setText(
          UiText.format(
              "共 %d 项（目录 %d）。最多显示 %d 项；文本预览上限 64 KB。",
              entries.size(),
              directories,
              SafTree.MAX_ENTRIES));
    } catch (SafTree.Failure failure) {
      entries.clear();
      adapter.notifyDataSetChanged();
      statusView.setText(describe(failure));
    }
  }

  private void open(SafTree.Entry entry) {
    if (entry.directory) {
      load(current.isEmpty() ? entry.name : current + "/" + entry.name);
      return;
    }
    preview(entry);
  }

  private void preview(SafTree.Entry entry) {
    String path = current.isEmpty() ? entry.name : current + "/" + entry.name;
    byte[] data;
    try {
      data = tree.read(path, 0, PREVIEW_LIMIT);
    } catch (SafTree.Failure failure) {
      statusView.setText(describe(failure));
      return;
    }
    if (!SafTree.looksText(data)) {
      statusView.setText(
          UiText.choose(
              "这是二进制文件，页面不预览；助手可以用 /app/saf/pull 取走。",
              "Binary file: not previewed here. The assistant can take it with /app/saf/pull."));
      return;
    }
    String text = new String(data, StandardCharsets.UTF_8);
    boolean truncated = data.length >= PREVIEW_LIMIT;
    new DshaDialogBuilder(requireContext())
        .setTitle(entry.name)
        .setMessage(
            text
                + (truncated
                    ? "\n\n" + UiText.choose("（已截断到 64 KB）", "(truncated at 64 KB)")
                    : ""))
        .setPositiveButton(UiText.choose("关闭", "Close"), null)
        .show();
  }

  private String meta(Context ctx, SafTree.Entry entry) {
    if (entry.directory) return UiText.choose("文件夹", "Folder");
    StringBuilder out = new StringBuilder();
    if (entry.size >= 0) out.append(Formatter.formatShortFileSize(ctx, entry.size)).append("  ");
    if (entry.modified > 0)
      out.append(
          new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
              .format(new Date(entry.modified)));
    return out.toString().trim();
  }

  private static String parentOf(String path) {
    if (path == null || path.isEmpty()) return "";
    int cut = path.lastIndexOf('/');
    return cut <= 0 ? "" : path.substring(0, cut);
  }

  private String describe(SafTree.Failure failure) {
    switch (failure.code) {
      case "PERMISSION_DENIED":
        return UiText.choose(
            "授权已失效或被系统拒绝：回到上一页移除它再重新选。",
            "Permission lost or denied: remove the grant and pick it again.");
      case "NOT_FOUND":
        return UiText.choose("目标不存在：应用可能已卸载。", "Not found: the app may be uninstalled.");
      case "PROVIDER_UNAVAILABLE":
      case "PROVIDER_ERROR":
        return UiText.choose(
            "目标应用没有响应：先打开那个应用再刷新。",
            "The app did not respond: open it first, then refresh.");
      case "UNSUPPORTED":
        return UiText.choose("这个文档服务不支持该操作。", "This document provider does not support it.");
      case "OFFSET_OUT_OF_RANGE":
        return UiText.choose("读取位置超出文件大小。", "Offset is beyond the end of the file.");
      default:
        return failure.getMessage() == null ? failure.code : failure.getMessage();
    }
  }
}
