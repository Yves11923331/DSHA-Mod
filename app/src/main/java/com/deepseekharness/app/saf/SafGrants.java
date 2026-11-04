package com.deepseekharness.app.saf;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.core.provider.DocumentsContractCompat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 跨应用文件访问的授权簿。
 *
 * <p>系统 {@link ContentResolver#getPersistedUriPermissions()} 是唯一真相；本地偏好只记两件事：
 * 哪些 tree 是「跨应用文件访问」加的（备份等其它功能的授权不会混进来），以及每个 tree 是否允许助手写入。
 * 授权本身由系统文档选择器发放，DSHA 不持 MANAGE_DOCUMENTS，也不修改任何外部 APK。
 */
public final class SafGrants {
  /** 授权数量上限：足够覆盖日常几个应用，又不至于把页面和桥接列表撑爆。 */
  public static final int MAX_TREES = 8;

  private static final String PREFS = "saf_grants";
  private static final String KEY_URIS = "uris";
  private static final String KEY_WRITABLE = "writable:";

  private SafGrants() {}

  /** 一条已授权目录；{@code valid=false} 表示系统里的授权已经没了（应用卸载或用户在系统里撤销）。 */
  public static final class Grant {
    public final String id;
    public final Uri uri;
    public final String app;
    public final String label;
    public final boolean writable;
    public final boolean valid;

    Grant(String id, Uri uri, String app, String label, boolean writable, boolean valid) {
      this.id = id;
      this.uri = uri;
      this.app = app;
      this.label = label;
      this.writable = writable;
      this.valid = valid;
    }

    /** 「应用 · 目录」；查不到时退回 URI 尾段，保证行上永远有可读的名字。 */
    public String summary() {
      String name = label == null || label.isEmpty() ? uri.getLastPathSegment() : label;
      return app == null || app.isEmpty() ? name : app + " · " + name;
    }
  }

  /** 给桥接层用的稳定短 id：授权 URI 的摘要前 12 位，agent 只认 id、不认 authority。 */
  public static String id(Uri uri) {
    if (uri == null) return "";
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(12);
      for (int i = 0; i < 6; i++) out.append(String.format(Locale.ROOT, "%02x", hash[i]));
      return out.toString();
    } catch (Exception unavailable) {
      return String.format(Locale.ROOT, "%08x", uri.toString().hashCode());
    }
  }

  public static List<Grant> list(Context context) {
    SharedPreferences prefs = prefs(context);
    Set<String> saved = new HashSet<>(prefs.getStringSet(KEY_URIS, new HashSet<>()));
    List<Grant> grants = new ArrayList<>();
    if (saved.isEmpty()) return grants;
    List<UriPermission> persisted = null;
    try {
      persisted = context.getContentResolver().getPersistedUriPermissions();
    } catch (Throwable ignored) {
      // 取不到系统授权时按「全部失效」展示，由用户移除；不隐藏条目，避免静默丢状态。
    }
    for (String value : saved) {
      Uri uri;
      try {
        uri = Uri.parse(value);
      } catch (Throwable bad) {
        continue;
      }
      if (!documentsTree(uri)) continue;
      boolean readable = false;
      boolean writablePermission = false;
      if (persisted != null)
        for (UriPermission permission : persisted) {
          if (!uri.equals(permission.getUri())) continue;
          readable |= permission.isReadPermission();
          writablePermission |= permission.isWritePermission();
        }
      grants.add(
          new Grant(
              id(uri),
              uri,
              appLabel(context, uri),
              documentLabel(context, uri),
              writablePermission && prefs.getBoolean(KEY_WRITABLE + uri, true),
              readable));
    }
    grants.sort(Comparator.comparing(grant -> grant.summary().toLowerCase(Locale.ROOT)));
    return grants;
  }

  public static Grant find(Context context, String id) {
    if (id == null || id.isEmpty()) return null;
    for (Grant grant : list(context)) if (grant.id.equals(id)) return grant;
    return null;
  }

  /** 桥接层用的轻量查找：不查应用名与目录名（那要跨进程问 Provider），只验系统券还在。 */
  public static Grant pick(Context context, String id) {
    if (id == null || id.isEmpty()) return null;
    SharedPreferences prefs = prefs(context);
    Set<String> saved = new HashSet<>(prefs.getStringSet(KEY_URIS, new HashSet<>()));
    if (saved.isEmpty()) return null;
    List<UriPermission> persisted;
    try {
      persisted = context.getContentResolver().getPersistedUriPermissions();
    } catch (Throwable unavailable) {
      return null;
    }
    for (String value : saved) {
      Uri uri;
      try {
        uri = Uri.parse(value);
      } catch (Throwable bad) {
        continue;
      }
      if (!documentsTree(uri) || !id(uri).equals(id)) continue;
      boolean readable = false;
      boolean writablePermission = false;
      for (UriPermission permission : persisted) {
        if (!uri.equals(permission.getUri())) continue;
        readable |= permission.isReadPermission();
        writablePermission |= permission.isWritePermission();
      }
      return new Grant(
          id(uri),
          uri,
          "",
          "",
          writablePermission && prefs.getBoolean(KEY_WRITABLE + uri, true),
          readable);
    }
    return null;
  }

  /** 记一条授权；已存在或到上限返回 false。调用方必须先拿到系统授权券。 */
  public static boolean remember(Context context, Uri tree, boolean writable) {
    if (!documentsTree(tree)) return false;
    SharedPreferences prefs = prefs(context);
    Set<String> saved = new HashSet<>(prefs.getStringSet(KEY_URIS, new HashSet<>()));
    if (!saved.contains(tree.toString()) && saved.size() >= MAX_TREES) return false;
    saved.add(tree.toString());
    prefs
        .edit()
        .putStringSet(KEY_URIS, saved)
        .putBoolean(KEY_WRITABLE + tree, writable)
        .apply();
    return true;
  }

  /** 撤销授权：先退系统券，再删本地记录。系统券已不在时也要把本地记录清掉。 */
  public static void forget(Context context, Uri tree) {
    if (tree == null) return;
    ContentResolver resolver = context.getContentResolver();
    try {
      resolver.releasePersistableUriPermission(
          tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (Throwable needsSplit) {
      try {
        resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (Throwable alreadyGone) {
        // 系统里已经撤销：本地记录照删。
      }
    }
    SharedPreferences prefs = prefs(context);
    Set<String> saved = new HashSet<>(prefs.getStringSet(KEY_URIS, new HashSet<>()));
    saved.remove(tree.toString());
    prefs.edit().putStringSet(KEY_URIS, saved).remove(KEY_WRITABLE + tree).apply();
  }

  public static void setWritable(Context context, Uri tree, boolean writable) {
    if (tree == null) return;
    prefs(context).edit().putBoolean(KEY_WRITABLE + tree, writable).apply();
  }

  /** 选到 DSHA 自己的 documents 根会形成自环：读取方和被读方是同一个进程。 */
  public static boolean ownProvider(Context context, Uri tree) {
    return tree != null
        && (context.getPackageName() + ".documents").equals(tree.getAuthority());
  }

  public static boolean documentsTree(Uri uri) {
    return uri != null
        && "content".equals(uri.getScheme())
        && DocumentsContractCompat.isTreeUri(uri);
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  /** 授权的 Provider 属于哪个应用：MT 注入的 Provider 跑在目标应用里，所以这里显示的就是目标应用名。 */
  private static String appLabel(Context context, Uri uri) {
    try {
      PackageManager manager = context.getPackageManager();
      ProviderInfo provider = manager.resolveContentProvider(uri.getAuthority(), 0);
      if (provider == null || provider.applicationInfo == null) return "";
      ApplicationInfo info = provider.applicationInfo;
      return String.valueOf(manager.getApplicationLabel(info));
    } catch (Throwable ignored) {
      return "";
    }
  }

  private static String documentLabel(Context context, Uri uri) {
    try {
      String docId = DocumentsContract.getTreeDocumentId(uri);
      Uri document = DocumentsContract.buildDocumentUriUsingTree(uri, docId);
      try (Cursor cursor =
          context
              .getContentResolver()
              .query(
                  document,
                  new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                  null,
                  null,
                  null)) {
        if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
      }
    } catch (Throwable ignored) {
      // 目标应用不在（或进程没起来）时退回到 URI 尾段。
    }
    return "";
  }
}
