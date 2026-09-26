package com.example.tidying;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** Stores validated AI suggestions; every reuse still goes through buildPlan. */
final class PlanningCache {
  private static final String VERSION = "1";
  private final Path root;
  private final Path file;
  private final Properties entries = new Properties();
  private boolean dirty;

  PlanningCache(Path root, FileTidyingAssistant.Config config, List<Path> directories, boolean refresh) {
    this.root = root;
    file = root.resolve(".bei-file-tidying/classifications.properties");
    StringBuilder context = new StringBuilder(VERSION).append('\n').append(config.baseUrl()).append('\n')
        .append(config.model()).append('\n').append(config.reasoningEffort()).append('\n')
        .append(config.wireApi()).append('\n').append(digest(config.apiKey())).append('\n')
        .append(config.maxBytes()).append('\n')
        .append(FileTidyingAssistant.relative(root, config.target())).append('\n');
    for (Path directory : directories) context.append(FileTidyingAssistant.relative(root, directory)).append('\n');
    String contextHash = digest(context.toString());
    entries.setProperty("context", contextHash);
    if (refresh) { dirty = true; return; }
    if (!Files.isDirectory(file.getParent(), LinkOption.NOFOLLOW_LINKS)
        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Properties saved = new Properties();
      saved.load(reader);
      if (contextHash.equals(saved.getProperty("context"))) entries.putAll(saved);
    } catch (Exception ignored) {
      FileTidyingAssistant.status("分类缓存无法读取，本次重新分析");
    }
  }

  FileTidyingAssistant.Classification get(Path source, String groupSignature,
                                           FileTidyingAssistant.Config config) {
    String raw = entries.getProperty("item." + encode(FileTidyingAssistant.relative(root, source)));
    if (raw == null) return null;
    try {
      String[] parts = raw.split("\\t", -1);
      if (parts.length != 5 || !signature(source, config).equals(parts[0])
          || !groupSignature.equals(decode(parts[1]))) return null;
      String destination = decode(parts[2]);
      if (destination.isBlank()) return null;
      String reason = decode(parts[4]);
      if (!List.of("true", "false", "null").contains(parts[3])) return null;
      Boolean create = parts[3].equals("null") ? null : Boolean.valueOf(parts[3]);
      return new FileTidyingAssistant.Classification(destination, create, reason);
    } catch (Exception ignored) { return null; }
  }

  void put(Path source, String groupSignature, FileTidyingAssistant.Config config,
           FileTidyingAssistant.Classification suggestion, FileTidyingAssistant.Plan plan) {
    if (suggestion == null || suggestion.needsContent() || suggestion.destination() == null
        || !FileTidyingAssistant.validSuggestion(plan)) return;
    try {
      String reason = suggestion.reason() == null ? "AI 建议" : suggestion.reason();
      if (reason.length() > 160) reason = reason.substring(0, 160);
      String value = signature(source, config) + '\t' + encode(groupSignature) + '\t'
          + encode(suggestion.destination()) + '\t' + String.valueOf(suggestion.createDirectory())
          + '\t' + encode(reason);
      entries.setProperty("item." + encode(FileTidyingAssistant.relative(root, source)), value);
      dirty = true;
    } catch (Exception ignored) { /* A cache miss is safe; the plan remains usable. */ }
  }

  void save() {
    if (!dirty) return;
    try {
      Path directory = file.getParent();
      if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("缓存目录不是普通目录");
      Path temp = Files.createTempFile(directory, "classifications-", ".tmp");
      try {
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
          entries.store(writer, "Bei File Tidying AI suggestions");
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("缓存目录已变化");
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) {
          Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally { Files.deleteIfExists(temp); }
    } catch (IOException e) {
      FileTidyingAssistant.status("分类缓存保存失败: " + e.getMessage());
    }
  }

  private static String signature(Path source, FileTidyingAssistant.Config config) throws IOException {
    BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isRegularFile()) throw new IOException("文件已变化");
    String preview = attrs.size() <= config.maxBytes() && FileTidyingAssistant.isText(source)
        ? digest(FileTidyingAssistant.readText(source, FileTidyingAssistant.CONTENT_CHARS)) : "";
    return digest(attrs.size() + "\n" + attrs.lastModifiedTime().toMillis() + "\n"
        + String.valueOf(attrs.fileKey()) + "\n" + preview);
  }

  static String digest(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }
}
