package com.deepseekharness.app.saf;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import androidx.core.provider.DocumentsContractCompat;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 一条已授权 tree 上的只读/写入操作。
 *
 * <p>路径是相对授权根的普通路径（{@code /files/config.json}），逐段用
 * {@link DocumentsContract#buildChildDocumentsUriUsingTree} + 显示名解析成文档 id，
 * 因此不依赖各家 Provider 自己的 id 规则（MT 注入的 Provider、网盘、系统文档源都能用）。
 * 解析失败不猜、不跨根、不允许 {@code ..}。
 */
public final class SafTree {
  /** 单次列目录上限；超过只给前 limit 条，并告诉调用方被截断。 */
  public static final int MAX_ENTRIES = 500;
  /** 单次读取上限（与 /app/readfile 的 256 KiB 对齐）。 */
  public static final int MAX_READ = 256 * 1024;
  /** 单次写入上限。 */
  public static final long MAX_WRITE = 32L * 1024 * 1024;
  /** 路径段数上限，防止病态路径把解析拖成 O(n²)。 */
  private static final int MAX_SEGMENTS = 32;

  private static final String[] COLUMNS = {
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED
  };

  /** 失败原因；{@code code} 是给桥与界面共用的大写短码，别直接展示给用户。 */
  public static final class Failure extends Exception {
    public final String code;

    public Failure(String code, String detail) {
      super(detail == null || detail.isEmpty() ? code : code + ": " + detail);
      this.code = code;
    }
  }

  public static final class Entry {
    public final String docId;
    public final String name;
    public final String mime;
    public final boolean directory;
    public final long size;
    public final long modified;

    Entry(String docId, String name, String mime, long size, long modified) {
      this.docId = docId;
      this.name = name;
      this.mime = mime;
      this.directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
      this.size = size;
      this.modified = modified;
    }
  }

  private final ContentResolver resolver;
  private final Uri tree;
  private final String rootId;

  public SafTree(ContentResolver resolver, Uri tree) throws Failure {
    if (resolver == null || tree == null) throw new Failure("NOT_TREE", null);
    if (!"content".equals(tree.getScheme()) || !DocumentsContractCompat.isTreeUri(tree))
      throw new Failure("NOT_TREE", null);
    this.resolver = resolver;
    this.tree = tree;
    String id;
    try {
      id = DocumentsContract.getTreeDocumentId(tree);
    } catch (Throwable bad) {
      throw new Failure("NOT_TREE", null);
    }
    if (id == null || id.isEmpty()) throw new Failure("NOT_TREE", null);
    this.rootId = id;
  }

  public Uri uri() {
    return tree;
  }

  /** 授权根自身的条目信息（应用名之外再拿目录名与可读性）。 */
  public Entry root() throws Failure {
    return entry(rootId);
  }

  /** 列目录：目录在前、同类按名字排序；超过 limit 截断。 */
  public List<Entry> list(String path, int limit) throws Failure {
    String docId = resolve(path);
    List<Entry> entries = children(docId);
    entries.sort(
        (left, right) -> {
          if (left.directory != right.directory) return left.directory ? -1 : 1;
          return left.name.compareToIgnoreCase(right.name);
        });
    int cap = Math.max(1, Math.min(limit <= 0 ? MAX_ENTRIES : limit, MAX_ENTRIES));
    return entries.size() <= cap ? entries : new ArrayList<>(entries.subList(0, cap));
  }

  public Entry stat(String path) throws Failure {
    return entry(resolve(path));
  }

  /**
   * 读一段字节：{@code offset} 用于大文件分段读，{@code max} 上限 {@link #MAX_READ}。
   * 非可 seek 的 Provider 也能工作（按块丢弃前缀，不依赖 skip）。
   */
  public byte[] read(String path, long offset, int max) throws Failure {
    Entry entry = stat(path);
    if (entry.directory) throw new Failure("IS_DIRECTORY", entry.name);
    int cap = Math.max(1, Math.min(max <= 0 ? MAX_READ : max, MAX_READ));
    long skip = Math.max(0, offset);
    try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(documentUri(entry.docId), "r")) {
      if (descriptor == null) throw new Failure("UNREADABLE", entry.name);
      try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
        byte[] scratch = new byte[8192];
        long remaining = skip;
        while (remaining > 0) {
          int read = input.read(scratch, 0, (int) Math.min(scratch.length, remaining));
          if (read <= 0) throw new Failure("OFFSET_OUT_OF_RANGE", Long.toString(skip));
          remaining -= read;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(cap);
        byte[] buffer = new byte[Math.min(cap, 65536)];
        while (out.size() < cap) {
          int read = input.read(buffer, 0, Math.min(buffer.length, cap - out.size()));
          if (read <= 0) break;
          out.write(buffer, 0, read);
        }
        byte[] bytes = out.toByteArray();
        return bytes.length == 0 ? new byte[0] : bytes;
      }
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (FileNotFoundException missing) {
      throw new Failure("NOT_FOUND", entry.name);
    } catch (IOException error) {
      throw new Failure("IO_ERROR", error.getMessage());
    }
  }

  /** 覆盖写；{@code create} 为真时目标不存在会按路径新建（父目录必须已存在）。 */
  public long write(String path, byte[] data, boolean create) throws Failure {
    if (data == null) data = new byte[0];
    if (data.length > MAX_WRITE) throw new Failure("TOO_LARGE", Long.toString(data.length));
    String clean = normalize(path);
    if (clean.isEmpty()) throw new Failure("BAD_PATH", path);
    String docId = null;
    try {
      docId = resolve(clean);
    } catch (Failure missing) {
      if (!"NOT_FOUND".equals(missing.code) || !create) throw missing;
    }
    try {
      Uri target;
      if (docId != null) {
        Entry existing = entry(docId);
        if (existing.directory) throw new Failure("IS_DIRECTORY", existing.name);
        target = documentUri(docId);
      } else {
        int cut = clean.lastIndexOf('/');
        String parentPath = cut <= 0 ? "" : clean.substring(0, cut);
        String name = cut < 0 ? clean : clean.substring(cut + 1);
        if (name.isEmpty() || ".".equals(name) || "..".equals(name))
          throw new Failure("BAD_PATH", clean);
        String parentId = resolve(parentPath);
        target =
            DocumentsContract.createDocument(resolver, documentUri(parentId), mimeOf(name), name);
        if (target == null) throw new Failure("CREATE_FAILED", name);
      }
      try (OutputStream output = resolver.openOutputStream(target, "wt")) {
        if (output == null) throw new Failure("UNWRITABLE", null);
        output.write(data);
        output.flush();
      }
      return data.length;
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (FileNotFoundException missing) {
      throw new Failure("NOT_FOUND", clean);
    } catch (IOException error) {
      throw new Failure("IO_ERROR", error.getMessage());
    }
  }

  public void mkdir(String path) throws Failure {
    String clean = normalize(path);
    if (clean.isEmpty()) throw new Failure("BAD_PATH", path);
    int cut = clean.lastIndexOf('/');
    String parentPath = cut <= 0 ? "" : clean.substring(0, cut);
    String name = cut < 0 ? clean : clean.substring(cut + 1);
    if (name.isEmpty() || ".".equals(name) || "..".equals(name)) throw new Failure("BAD_PATH", clean);
    try {
      String parentId = resolve(parentPath);
      Uri created =
          DocumentsContract.createDocument(
              resolver,
              documentUri(parentId),
              DocumentsContract.Document.MIME_TYPE_DIR,
              name);
      if (created == null) throw new Failure("CREATE_FAILED", name);
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (FileNotFoundException missing) {
      throw new Failure("NOT_FOUND", parentPath);
    } catch (IOException error) {
      throw new Failure("IO_ERROR", error.getMessage());
    }
  }

  public void delete(String path) throws Failure {
    String docId = resolve(path);
    try {
      if (!DocumentsContract.deleteDocument(resolver, documentUri(docId)))
        throw new Failure("DELETE_FAILED", normalize(path));
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (FileNotFoundException missing) {
      throw new Failure("NOT_FOUND", normalize(path));
    }
  }

  /** 规范化：去掉首尾空白与开头斜杠；拒绝 {@code ..}、反斜杠与控制字符。 */
  public static String normalize(String path) throws Failure {
    String value = path == null ? "" : path.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    if (value.isEmpty()) return "";
    for (String segment : value.split("/")) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
        throw new Failure("BAD_PATH", path);
      if (segment.indexOf('\\') >= 0) throw new Failure("BAD_PATH", path);
      for (int i = 0; i < segment.length(); i++)
        if (segment.charAt(i) < ' ') throw new Failure("BAD_PATH", path);
    }
    if (value.split("/").length > MAX_SEGMENTS) throw new Failure("PATH_TOO_DEEP", path);
    return value;
  }

  // ---------------------------------------------------------------- 内部

  private String resolve(String path) throws Failure {
    String clean = normalize(path);
    if (clean.isEmpty()) return rootId;
    String docId = rootId;
    for (String segment : clean.split("/")) {
      Entry child = child(docId, segment);
      if (child == null) throw new Failure("NOT_FOUND", segment);
      docId = child.docId;
    }
    return docId;
  }

  private Entry child(String parentId, String name) throws Failure {
    for (Entry entry : children(parentId)) if (entry.name.equals(name)) return entry;
    return null;
  }

  private Entry entry(String docId) throws Failure {
    try (Cursor cursor =
        resolver.query(
            documentUri(docId), COLUMNS, null, null, null)) {
      if (cursor == null || !cursor.moveToFirst()) throw new Failure("NOT_FOUND", docId);
      return row(cursor);
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (IllegalArgumentException unsupported) {
      throw new Failure("UNSUPPORTED", unsupported.getMessage());
    } catch (RuntimeException error) {
      throw new Failure("PROVIDER_ERROR", error.getMessage());
    }
  }

  private List<Entry> children(String parentId) throws Failure {
    Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
    try (Cursor cursor = resolver.query(uri, COLUMNS, null, null, null)) {
      if (cursor == null) return new ArrayList<>();
      List<Entry> entries = new ArrayList<>();
      while (cursor.moveToNext() && entries.size() <= MAX_ENTRIES) entries.add(row(cursor));
      return entries;
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (IllegalArgumentException unsupported) {
      throw new Failure("UNSUPPORTED", unsupported.getMessage());
    } catch (RuntimeException error) {
      throw new Failure("PROVIDER_ERROR", error.getMessage());
    }
  }

  private Entry row(Cursor cursor) {
    String docId = cursor.isNull(0) ? "" : cursor.getString(0);
    String name = cursor.isNull(1) ? "" : cursor.getString(1);
    String mime = cursor.isNull(2) ? "" : cursor.getString(2);
    long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
    long modified = cursor.isNull(4) ? -1L : cursor.getLong(4);
    return new Entry(docId, name, mime, size, modified);
  }

  private Uri documentUri(String docId) {
    return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
  }

  /** 按扩展名猜 MIME；猜不到给 octet-stream（新建文档时 Provider 可能据此拒绝，也算明确失败）。 */
  public static String mimeOf(String name) {
    int dot = name.lastIndexOf('.');
    String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    switch (extension) {
      case "txt":
      case "log":
      case "md":
        return "text/plain";
      case "json":
        return "application/json";
      case "xml":
        return "text/xml";
      case "html":
        return "text/html";
      case "js":
        return "application/javascript";
      case "csv":
        return "text/csv";
      case "png":
        return "image/png";
      case "jpg":
      case "jpeg":
        return "image/jpeg";
      case "webp":
        return "image/webp";
      case "gif":
        return "image/gif";
      case "pdf":
        return "application/pdf";
      case "db":
      case "sqlite":
      case "sqlite3":
        return "application/x-sqlite3";
      case "zip":
        return "application/zip";
      default:
        return "application/octet-stream";
    }
  }

  /** 文本判定：无 NUL 且能按 UTF-8 解出（用于 read 端点与预览）。 */
  public static boolean looksText(byte[] data) {
    if (data == null) return false;
    int length = Math.min(data.length, 4096);
    for (int i = 0; i < length; i++) if (data[i] == 0) return false;
    try {
      String value = new String(Arrays.copyOf(data, length), java.nio.charset.StandardCharsets.UTF_8);
      return value.indexOf('\uFFFD') < 0 || data.length > length;
    } catch (Throwable ignored) {
      return false;
    }
  }
}
