package com.example.tidying;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Scans a target folder and tidies its loose files into existing folders under a sense root. */
public final class FileTidyingAssistant {
  private FileTidyingAssistant() {}

  record Config(Path senseRoot, Path target, long maxBytes, String baseUrl, String apiKey, String model,
                String reasoningEffort, String wireApi, int treeDepth, int batchSize,
                int batchConcurrency, boolean deleteEmptyDirectories) {
    Config(Path senseRoot, Path target, long maxBytes, String baseUrl, String apiKey, String model,
           String reasoningEffort, String wireApi) {
      this(senseRoot, target, maxBytes, baseUrl, apiKey, model, reasoningEffort, wireApi,
          3, 10, 2, true);
    }

    static Config load() throws IOException {
      Properties p = new Properties();
      Path file = Path.of("application.properties");
      if (Files.exists(file)) {
        try (Reader reader = Files.newBufferedReader(file)) {
          p.load(reader);
        }
      }
      String home = System.getProperty("user.home");
      String baseUrl = setting(p, "ai.base-url", "https://www.fhl.mom").replaceAll("/+$", "");
      return new Config(
          Path.of(p.getProperty("sense.root", home)),
          Path.of(p.getProperty("target.dir", home + "/Downloads")),
          Long.parseLong(p.getProperty("max.file.mb", "10")) * 1024 * 1024,
          baseUrl,
          p.getProperty("ai.api-key", ""),
          setting(p, "ai.model", "gpt-6-luna"),
          setting(p, "ai.reasoning-effort", "medium"),
          setting(p, "ai.wire-api", "responses"),
          integer(p, "sense.tree-depth", 3, 0, 20),
          integer(p, "ai.batch-size", 10, 1, 100),
          integer(p, "ai.batch-concurrency", 2, 1, 8),
          Boolean.parseBoolean(setting(p, "tidy.delete-empty-directories", "true")));
    }

    private static String setting(Properties p, String key, String fallback) {
      String value = p.getProperty(key);
      return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int integer(Properties p, String key, int fallback, int min, int max) {
      try {
        return Math.max(min, Math.min(max, Integer.parseInt(setting(p, key, String.valueOf(fallback)))));
      } catch (NumberFormatException e) {
        return fallback;
      }
    }
  }

  record Plan(Path source, Path target, String reason, String status) {}
  record Classification(String destination, Boolean createDirectory, String reason) {}
  record BatchClassification(String source, String destination, Boolean createDirectory, String reason) {}
  record MoveRecord(String source, String destination, long size, long modified) {}
  record History(List<MoveRecord> moved, List<String> deletedDirectories,
                 List<String> createdDirectories, boolean undone) {}

  public static void main(String[] args) throws Exception {
    status("正在加载配置...");
    Config config = Config.load();
    Path root = config.senseRoot().toAbsolutePath().normalize();
    Path target = config.target().toAbsolutePath().normalize();
    validateConfig(root, target);

    if (hasArgument(args, "--undo-last")) {
      undoLast(root);
      return;
    }
    validateAiConfig(config);

    status("正在感知根目录结构: " + root);
    List<Path> directories = existingDirectories(root, target);
    status("已发现 " + directories.size() + " 个既有文件夹");
    status("正在生成目录树（默认深度 " + config.treeDepth() + "）...");
    String structure = structure(root, config.treeDepth());
    status("正在扫描目标文件夹: " + target);
    List<Path> files = targetFiles(target);
    status("发现 " + files.size() + " 个待整理文件");
    System.out.printf("Bei File Tidying%n感知根目录: %s%n目标文件夹: %s%n", root, target);
    System.out.println("已感知的目录树:");
    System.out.print(structure);

    List<Plan> plans = planAll(files, config, root, directories, structure);
    if (plans.isEmpty()) {
      status("目标文件夹中没有可整理的文件");
      return;
    }
    print(plans, root);
    System.out.print("输入 APPLY 执行整理，输入 UNDO 撤销最近一次整理，其他内容取消: ");
    Scanner scanner = new Scanner(System.in);
    String answer = scanner.hasNextLine() ? scanner.nextLine() : "";
    String command = answer == null ? "" : answer.trim();
    if ("APPLY".equals(command)) execute(plans, root, target, config.deleteEmptyDirectories());
    else if ("UNDO".equals(command)) undoLast(root);
    else status("已取消，文件保持原位");
  }

  static void validateConfig(Path root, Path target) throws IOException {
    if (!Files.isDirectory(root)) throw new IOException("sense.root 不存在或不是目录: " + root);
    if (!Files.isDirectory(target)) throw new IOException("target.dir 不存在或不是目录: " + target);
    if (!target.startsWith(root) || target.equals(root)) {
      throw new IOException("target.dir 必须位于 sense.root 内部: " + target);
    }
  }

  static void validateAiConfig(Config config) throws IOException {
    if (config.apiKey().isBlank()) return;
    try {
      URI.create(config.baseUrl() + "/" + apiPath(config));
    } catch (IllegalArgumentException e) {
      throw new IOException("ai.base-url 不是有效的 HTTP 地址: " + config.baseUrl(), e);
    }
  }

  static List<Path> targetFiles(Path target) throws IOException {
    try (Stream<Path> stream = Files.walk(target)) {
      return stream.filter(path -> !Files.isSymbolicLink(path))
          .filter(Files::isRegularFile)
          .filter(path -> !path.getFileName().toString().startsWith("."))
          .sorted()
          .toList();
    }
  }

  static List<Path> existingDirectories(Path root, Path target) throws IOException {
    List<Path> directories = new ArrayList<>();
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
        if (!dir.equals(root) && (dir.getFileName().toString().startsWith(".") || Files.isSymbolicLink(dir))) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        if (!dir.equals(root)) directories.add(dir.toAbsolutePath().normalize());
        return FileVisitResult.CONTINUE;
      }
    });
    directories.sort(Comparator.naturalOrder());
    return directories;
  }

  static String structure(Path root) throws IOException {
    return structure(root, 3);
  }

  static String structure(Path root, int maxDepth) throws IOException {
    StringBuilder result = new StringBuilder();
    Path normalizedRoot = root.toAbsolutePath().normalize();
    result.append(normalizedRoot.getFileName()).append("/\n");
    appendTree(normalizedRoot, "", 0, Math.max(0, maxDepth), result);
    return result.toString();
  }

  private static void appendTree(Path directory, String prefix, int depth, int maxDepth, StringBuilder result) throws IOException {
    if (depth >= maxDepth) return;
    List<Path> children;
    try (Stream<Path> stream = Files.list(directory)) {
      children = stream.filter(path -> Files.isDirectory(path) && !Files.isSymbolicLink(path))
          .filter(path -> !path.getFileName().toString().startsWith("."))
          .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
          .toList();
    }
    for (int i = 0; i < children.size(); i++) {
      Path child = children.get(i);
      boolean last = i == children.size() - 1;
      result.append(prefix).append(last ? "└── " : "├── ").append(child.getFileName()).append("/\n");
      appendTree(child, prefix + (last ? "    " : "│   "), depth + 1, maxDepth, result);
    }
  }

  static List<Plan> planAll(List<Path> files, Config config, Path root,
                            List<Path> directories, String structure) {
    if (files.isEmpty()) return List.of();
    int batchSize = config.apiKey().isBlank() ? 1 : config.batchSize();
    List<List<Path>> batches = new ArrayList<>();
    for (int start = 0; start < files.size(); start += batchSize) {
      batches.add(files.subList(start, Math.min(files.size(), start + batchSize)));
    }

    int workerCount = Math.min(config.batchConcurrency(), batches.size());
    ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, workerCount));
    AtomicBoolean authenticationFailed = new AtomicBoolean(false);
    List<Future<List<Plan>>> futures = new ArrayList<>();
    for (int i = 0; i < batches.size(); i++) {
      int batchNumber = i + 1;
      List<Path> batch = batches.get(i);
      Callable<List<Plan>> task = () -> {
        if (authenticationFailed.get() && !config.apiKey().isBlank()) {
          return batch.stream().map(file -> new Plan(file, file, "跳过 AI 请求：前一个批次认证失败", "FAILED")).toList();
        }
        return planBatch(batch, batchNumber, batches.size(), config, root, directories, structure, authenticationFailed);
      };
      futures.add(executor.submit(task));
    }

    List<Plan> plans = new ArrayList<>();
    for (int i = 0; i < futures.size(); i++) {
      Future<List<Plan>> future = futures.get(i);
      try {
        plans.addAll(future.get());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        for (Path file : batches.get(i)) plans.add(new Plan(file, file, "批量分析被中断", "FAILED"));
      } catch (Exception e) {
        String reason = e.getCause() == null || e.getCause().getMessage() == null ? "批量分析失败" : e.getCause().getMessage();
        for (Path file : batches.get(i)) plans.add(new Plan(file, file, reason, "FAILED"));
      }
    }
    executor.shutdown();
    return plans;
  }

  private static List<Plan> planBatch(List<Path> files, int batchNumber, int totalBatches, Config config,
                                      Path root, List<Path> directories, String structure,
                                      AtomicBoolean authenticationFailed) {
    status("正在分析批次 (" + batchNumber + "/" + totalBatches + ")，包含 " + files.size() + " 个文件");
    if (config.apiKey().isBlank()) return files.stream().map(file -> plan(file, config, root, directories, structure)).toList();

    Map<Path, Plan> plans = new HashMap<>();
    List<Path> stableFiles = new ArrayList<>();
    for (Path file : files) {
      try {
        if (stable(file)) stableFiles.add(file);
        else plans.put(file, new Plan(file, file, "文件仍在变化", "FAILED"));
      } catch (Exception e) {
        plans.put(file, new Plan(file, file, "无法检查文件稳定性: " + e.getMessage(), "FAILED"));
      }
    }
    if (stableFiles.isEmpty()) return files.stream().map(plans::get).toList();
    try {
      Map<String, Classification> classifications;
      try {
        classifications = classifyBatch(stableFiles, config, root, structure);
      } catch (IOException e) {
        if (e.getMessage() == null || !e.getMessage().startsWith("AI 批量响应为空")) throw e;
        status("AI 批量响应为空，重试当前批次一次");
        classifications = classifyBatch(stableFiles, config, root, structure);
      }
      int fallbackCount = 0;
      for (Path file : stableFiles) {
        Classification classification = classifications.get(relative(root, file));
        if (classification == null) {
          fallbackCount++;
          plans.put(file, plan(file, config, root, directories, structure));
        } else {
          plans.put(file, buildPlan(file, classification, config, root));
        }
      }
      if (fallbackCount > 0) status("批次中有 " + fallbackCount + " 个文件缺少分类结果，已逐文件重试");
    } catch (Exception e) {
      if (isAiAuthenticationFailure(e.getMessage())) {
        authenticationFailed.set(true);
        return files.stream().map(file -> plans.getOrDefault(file, new Plan(file, file, e.getMessage(), "FAILED"))).toList();
      }
      status("批次请求失败，切换逐文件重试: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
      for (Path file : stableFiles) {
        if (authenticationFailed.get()) {
          plans.put(file, new Plan(file, file, "跳过 AI 请求：前一个文件已认证失败", "FAILED"));
          continue;
        }
        Plan filePlan = plan(file, config, root, directories, structure);
        plans.put(file, filePlan);
        if (isAiAuthenticationFailure(filePlan.reason())) authenticationFailed.set(true);
      }
    }
    long ready = plans.values().stream().filter(plan -> "READY".equals(plan.status())).count();
    status("批次完成 (" + batchNumber + "/" + totalBatches + ")，可整理 " + ready + "/" + files.size() + " 个文件");
    return files.stream().map(plans::get).toList();
  }

  static Plan plan(Path file, Config config, Path root, List<Path> directories, String structure) {
    try {
      if (!stable(file)) return new Plan(file, file, "文件仍在变化", "FAILED");
      Classification classification = classify(file, config, root, directories, structure);
      return buildPlan(file, classification, config, root);
    } catch (HttpTimeoutException e) {
      return new Plan(file, file, "AI 请求超时（90 秒）", "FAILED");
    } catch (Exception e) {
      return new Plan(file, file, e.getMessage() == null ? "整理失败" : e.getMessage(), "FAILED");
    }
  }

  static Plan buildPlan(Path file, Classification classification, Config config, Path root) {
    if (classification == null) return new Plan(file, file, "没有匹配的文件夹", "REVIEW");
    if (suggestsCurrentDirectory(classification.destination(), root, file)) {
      return new Plan(file, file, classification.reason(), "UNCHANGED");
    }
    Path destination = resolveDestination(classification.destination(), root, config.target().toAbsolutePath().normalize());
    if (destination == null) return new Plan(file, file, "AI 建议的目标目录不安全", "FAILED");
    if (!Files.isDirectory(destination) && Boolean.FALSE.equals(classification.createDirectory())) {
      return new Plan(file, file, "AI 未确认需要新建文件夹", "FAILED");
    }
    Path target = destination.resolve(file.getFileName()).normalize();
    if (Files.exists(target)) return new Plan(file, target, "目标文件已存在", "CONFLICT");
    return new Plan(file, target, classification.reason(), "READY");
  }

  static boolean stable(Path file) throws IOException, InterruptedException {
    long first = Files.size(file);
    Thread.sleep(300);
    return first == Files.size(file);
  }

  static Classification classify(Path file, Config config, Path root, List<Path> directories, String structure) throws Exception {
    if (config.apiKey().isBlank()) {
      String fallback = fallbackDestination(file, root, directories);
      status("未配置 API Key，使用扩展名规则: " + root.relativize(file));
      return new Classification(fallback, null, "未配置 API Key，按扩展名建议分类目录");
    }

    String prompt = "You are a file tidying assistant. The first principle is sensible file classification. "
        + "Prefer an existing directory when it is a good fit; otherwise propose a new, meaningful directory under the sensed root. "
        + "Return JSON only: {\"destination\":\"relative/path\",\"createDirectory\":true|false,\"reason\":\"...\"}. "
        + "The destination must be under the sensed root. It may be the target folder or any existing/new subfolder inside it when that produces a sensible structure.\n"
        + "Sensed root structure:\n" + structure
        + "Target file: " + root.relativize(file) + ", extension=" + extension(file) + ", bytes=" + Files.size(file);
    if (Files.size(file) <= config.maxBytes() && isText(file)) prompt += ", content=" + readText(file);

    String body = requestBody(config, prompt);
    HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/" + apiPath(config)))
        .timeout(Duration.ofSeconds(90))
        .header("Authorization", "Bearer " + config.apiKey())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    status("正在等待 AI 分类结果: " + root.relativize(file));
    HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    status("AI 已返回结果 (HTTP " + response.statusCode() + "): " + root.relativize(file));
    if (response.statusCode() / 100 != 2) throw new IOException(aiError(response.statusCode(), config.baseUrl()));
    String content = responseContent(response.body(), config.wireApi());
    String destination = extract(content, "destination");
    Boolean createDirectory = extractBoolean(content, "createDirectory");
    String reason = extract(content, "reason");
    if (destination == null || destination.isBlank()) throw new IOException("AI 返回缺少 destination");
    return new Classification(destination, createDirectory, reason == null ? "AI 建议" : reason);
  }

  static Map<String, Classification> classifyBatch(List<Path> files, Config config, Path root, String structure) throws Exception {
    StringBuilder prompt = new StringBuilder(
        "You are a file tidying assistant. Classify every input file into a sensible directory. "
            + "Prefer an existing directory when it is a good fit; otherwise propose a new meaningful directory under the sensed root. "
            + "Return JSON only as an array with exactly one object per input file: "
            + "[{\"source\":\"relative/path\",\"destination\":\"relative/path\",\"createDirectory\":true|false,\"reason\":\"...\"}]. "
            + "The source value must exactly match an input source. The destination must be under the sensed root. "
            + "It may be the target folder or any existing/new subfolder inside it when that produces a sensible structure.\n"
            + "Sensed root directory tree:\n" + structure + "\nInput files:\n");
    for (Path file : files) {
      prompt.append("- source=").append(relative(root, file))
          .append(", extension=").append(extension(file))
          .append(", bytes=").append(Files.size(file));
      if (Files.size(file) <= config.maxBytes() && isText(file)) {
        prompt.append(", content=").append(readText(file, 1200));
      }
      prompt.append('\n');
    }

    String body = requestBody(config, prompt.toString());
    HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/" + apiPath(config)))
        .timeout(Duration.ofSeconds(90))
        .header("Authorization", "Bearer " + config.apiKey())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    status("正在等待 AI 批量分类结果 (" + files.size() + " 个文件)");
    HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    status("AI 已返回批量结果 (HTTP " + response.statusCode() + ")");
    if (response.statusCode() / 100 != 2) throw new IOException(aiError(response.statusCode(), config.baseUrl()));

    String content = responseContent(response.body(), config.wireApi());
    if (content == null || content.isBlank()) {
      throw new IOException("AI 批量响应为空（" + finalResponseEvent(response.body()) + "）");
    }
    List<BatchClassification> classifications = parseBatchClassifications(content);
    Set<String> expected = new LinkedHashSet<>();
    for (Path file : files) expected.add(relative(root, file));
    Map<String, Classification> result = new HashMap<>();
    Set<String> duplicates = new LinkedHashSet<>();
    for (BatchClassification item : classifications) {
      if (item.source() == null || item.source().isBlank()) continue;
      String source = item.source().trim().replace('\\', '/');
      if (!expected.contains(source)) continue;
      if (result.containsKey(source)) {
        result.remove(source);
        duplicates.add(source);
        continue;
      }
      if (duplicates.contains(source)) continue;
      if (item.destination() == null || item.destination().isBlank()) continue;
      result.put(source, new Classification(item.destination(), item.createDirectory(),
          item.reason() == null ? "AI 建议" : item.reason()));
    }
    return result;
  }

  static List<BatchClassification> parseBatchClassifications(String content) throws IOException {
    if (content == null || content.isBlank()) throw new IOException("AI 批量响应为空");
    String json = content.trim();
    if (json.startsWith("```")) {
      int firstLine = json.indexOf('\n');
      int lastFence = json.lastIndexOf("```");
      if (firstLine >= 0 && lastFence > firstLine) json = json.substring(firstLine + 1, lastFence).trim();
    }
    List<String> objects = topLevelObjects(json);
    if (objects.isEmpty()) throw new IOException("AI 批量响应不是 JSON 数组");
    List<BatchClassification> result = new ArrayList<>();
    for (String object : objects) {
      result.add(new BatchClassification(extract(object, "source"), extract(object, "destination"),
          extractBoolean(object, "createDirectory"), extract(object, "reason")));
    }
    return result;
  }

  static List<String> topLevelObjects(String json) {
    int start = json.indexOf('[');
    int end = json.lastIndexOf(']');
    if (start < 0 || end <= start) {
      start = json.indexOf('{');
      end = json.lastIndexOf('}');
      if (start < 0 || end <= start) return List.of();
      return List.of(json.substring(start, end + 1));
    }
    List<String> objects = new ArrayList<>();
    boolean quoted = false;
    boolean escaped = false;
    int depth = 0;
    int objectStart = -1;
    for (int i = start + 1; i < end; i++) {
      char c = json.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\' && quoted) {
        escaped = true;
        continue;
      }
      if (c == '"') {
        quoted = !quoted;
        continue;
      }
      if (quoted) continue;
      if (c == '{') {
        if (depth == 0) objectStart = i;
        depth++;
      } else if (c == '}' && depth > 0) {
        depth--;
        if (depth == 0 && objectStart >= 0) {
          objects.add(json.substring(objectStart, i + 1));
          objectStart = -1;
        }
      }
    }
    return objects;
  }

  static Path resolveDestination(String relative, Path root, Path targetDirectory) {
    if (relative == null || relative.isBlank()) return null;
    String normalized = relative.trim().replace('\\', '/');
    if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) return null;
    Path relativePath = Path.of(normalized);
    for (Path part : relativePath) {
      if (part.toString().equals(".") || part.toString().equals("..")) return null;
    }
    Path candidate = root.resolve(relativePath).normalize();
    return candidate.startsWith(root) && !candidate.equals(root) ? candidate : null;
  }

  static boolean suggestsCurrentDirectory(String relative, Path root, Path file) {
    if (relative == null || relative.isBlank()) return false;
    String normalized = relative.trim().replace('\\', '/');
    if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) return false;
    Path candidate = root.resolve(normalized).normalize();
    return candidate.startsWith(root) && candidate.equals(file.getParent());
  }

  static String fallbackDestination(Path file, Path root, List<Path> directories) {
    Set<String> names = switch (extension(file)) {
      case "jpg", "jpeg", "png", "gif", "webp", "heic" -> Set.of("图片", "image", "images", "picture", "pictures", "photo", "photos");
      case "pdf", "doc", "docx", "txt", "md", "csv", "xlsx", "xls", "pptx" -> Set.of("文档", "document", "documents", "docs");
      case "zip", "rar", "7z", "tar", "gz" -> Set.of("压缩包", "archive", "archives", "compressed");
      case "mp4", "mov", "avi", "mkv" -> Set.of("视频", "video", "videos");
      case "mp3", "wav", "flac" -> Set.of("音频", "audio", "audios");
      default -> Set.of("其他", "other", "others");
    };
    return directories.stream()
        .filter(path -> names.contains(path.getFileName().toString().toLowerCase(Locale.ROOT)))
        .map(path -> root.relativize(path).toString())
        .findFirst()
        .orElseGet(() -> switch (extension(file)) {
          case "jpg", "jpeg", "png", "gif", "webp", "heic" -> "Pictures";
          case "pdf", "doc", "docx", "txt", "md", "csv", "xlsx", "xls", "pptx" -> "Documents";
          case "zip", "rar", "7z", "tar", "gz" -> "Archives";
          case "mp4", "mov", "avi", "mkv" -> "Videos";
          case "mp3", "wav", "flac" -> "Audio";
          default -> "Other";
        });
  }

  static void print(List<Plan> plans, Path root) {
    System.out.println("\n整理计划:");
    for (Plan plan : plans) {
      String folderNote = "READY".equals(plan.status()) && !Files.isDirectory(plan.target().getParent()) ? " [新建文件夹]" : "";
      System.out.printf("[%s] %s -> %s%s (%s)%n", plan.status(), root.relativize(plan.source()), root.relativize(plan.target()), folderNote, plan.reason());
    }
  }

  static void execute(List<Plan> plans) {
    execute(plans, null, null, false);
  }

  static void execute(List<Plan> plans, Path root, Path target, boolean deleteEmptyDirectories) {
    status("开始执行整理...");
    List<MoveRecord> moved = new ArrayList<>();
    List<String> deletedDirectories = new ArrayList<>();
    List<String> createdDirectories = new ArrayList<>();
    Path history = root == null ? null : historyFile(root);
    for (Plan plan : plans) {
      if (!"READY".equals(plan.status())) continue;
      try {
        status("正在创建目标目录并移动: " + plan.target());
        List<Path> newDirectories = missingDirectories(root, plan.target().getParent());
        Files.createDirectories(plan.target().getParent());
        Files.move(plan.source(), plan.target());
        if (root != null) {
          MoveRecord record = new MoveRecord(relative(root, plan.source()), relative(root, plan.target()),
              Files.size(plan.target()), Files.getLastModifiedTime(plan.target()).toMillis());
          moved.add(record);
          for (Path directory : newDirectories) {
            String relative = relative(root, directory);
            if (!createdDirectories.contains(relative)) createdDirectories.add(relative);
          }
          try {
            writeHistory(history, new History(moved, deletedDirectories, createdDirectories, false));
          } catch (IOException e) {
            moved.remove(record);
            Files.move(plan.target(), plan.source());
            for (int i = newDirectories.size() - 1; i >= 0; i--) {
              Path directory = newDirectories.get(i);
              createdDirectories.remove(relative(root, directory));
              try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
            }
            throw e;
          }
        }
        status("已整理: " + plan.source().getFileName());
      } catch (IOException e) {
        status("整理失败: " + plan.source().getFileName() + " - " + e.getMessage());
      }
    }
    if (deleteEmptyDirectories && target != null && !moved.isEmpty()) {
      try {
        List<Path> sources = moved.stream().map(item -> root.resolve(item.source())).toList();
        for (Path empty : deleteEmptyDirectories(target, sources)) {
          deletedDirectories.add(relative(root, empty));
          writeHistory(history, new History(moved, deletedDirectories, createdDirectories, false));
          status("已删除空文件夹: " + relative(root, empty));
        }
      } catch (IOException e) {
        status("清理空文件夹失败: " + e.getMessage());
      }
    }
    status("整理执行完成");
  }

  static List<Path> deleteEmptyDirectories(Path target, List<Path> sourceFiles) throws IOException {
    Path normalizedTarget = target.toAbsolutePath().normalize();
    Set<Path> candidates = new LinkedHashSet<>();
    for (Path source : sourceFiles) {
      Path current = source.toAbsolutePath().normalize().getParent();
      while (current != null && current.startsWith(normalizedTarget) && !current.equals(normalizedTarget)) {
        candidates.add(current);
        current = current.getParent();
      }
    }
    List<Path> ordered = new ArrayList<>(candidates);
    ordered.sort(Comparator.comparingInt(Path::getNameCount).reversed());
    List<Path> deleted = new ArrayList<>();
    for (Path directory : ordered) {
      if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) continue;
      boolean empty;
      try (Stream<Path> children = Files.list(directory)) {
        empty = children.findAny().isEmpty();
      }
      if (empty) {
        Files.deleteIfExists(directory);
        deleted.add(directory);
      }
    }
    return deleted;
  }

  static List<Path> missingDirectories(Path root, Path destination) {
    List<Path> missing = new ArrayList<>();
    if (root == null) return missing;
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path current = destination.toAbsolutePath().normalize();
    while (current.startsWith(normalizedRoot) && !current.equals(normalizedRoot) && !Files.exists(current)) {
      missing.add(current);
      current = current.getParent();
    }
    Collections.reverse(missing);
    return missing;
  }

  static Path historyFile(Path root) {
    return root.toAbsolutePath().normalize().resolve(".bei-file-tidying/history/last-operation.json");
  }

  static Path safeHistoryPath(Path root, String relativePath) {
    if (relativePath == null || relativePath.isBlank()) return null;
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path candidate = normalizedRoot.resolve(relativePath.replace('\\', '/')).normalize();
    return candidate.startsWith(normalizedRoot) && !candidate.equals(normalizedRoot) ? candidate : null;
  }

  static void writeHistory(Path file, History history) throws IOException {
      Files.createDirectories(file.getParent());
      StringBuilder json = new StringBuilder("{\"version\":2,\"undone\":")
          .append(history.undone()).append(",\"moved\":[");
      for (int i = 0; i < history.moved().size(); i++) {
        if (i > 0) json.append(',');
        MoveRecord item = history.moved().get(i);
        json.append("{\"source\":\"").append(escape(item.source())).append("\",\"destination\":\"")
            .append(escape(item.destination())).append("\",\"size\":").append(item.size())
            .append(",\"modified\":").append(item.modified()).append('}');
      }
      json.append("],\"deletedDirectories\":[");
      for (int i = 0; i < history.deletedDirectories().size(); i++) {
        if (i > 0) json.append(',');
        json.append('"').append(escape(history.deletedDirectories().get(i))).append('"');
      }
      json.append("],\"createdDirectories\":[");
      for (int i = 0; i < history.createdDirectories().size(); i++) {
        if (i > 0) json.append(',');
        json.append('"').append(escape(history.createdDirectories().get(i))).append('"');
      }
      json.append("]}");
      Path temp = Files.createTempFile(file.getParent(), "operation-", ".tmp");
      Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
      try {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
  }

  static void undoLast(Path root) {
    Path historyFile = historyFile(root);
    if (!Files.exists(historyFile)) {
      status("没有可撤销的整理记录");
      return;
    }
    try {
      History history = readHistory(historyFile);
      if (history.undone() || history.moved().isEmpty()) {
        status("没有可撤销的整理记录");
        return;
      }
      List<MoveRecord> unresolved = new ArrayList<>();
      for (int i = history.moved().size() - 1; i >= 0; i--) {
        MoveRecord item = history.moved().get(i);
        Path source = safeHistoryPath(root, item.source());
        Path destination = safeHistoryPath(root, item.destination());
        if (source == null || destination == null) {
          status("撤销失败，历史记录包含不安全路径");
          unresolved.add(item);
          continue;
        }
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(destination)) {
          status("撤销失败，目标文件不存在: " + item.destination());
          unresolved.add(item);
          continue;
        }
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
          status("撤销冲突，原位置已有文件: " + item.source());
          unresolved.add(item);
          continue;
        }
        if (Files.size(destination) != item.size()
            || Files.getLastModifiedTime(destination).toMillis() != item.modified()) {
          status("撤销冲突，目标文件已被修改: " + item.destination());
          unresolved.add(item);
          continue;
        }
        try {
          Files.createDirectories(source.getParent());
          Files.move(destination, source);
          status("已撤销: " + item.destination() + " -> " + item.source());
        } catch (IOException e) {
          status("撤销失败: " + item.destination() + " - " + e.getMessage());
          unresolved.add(item);
        }
      }
      for (String directory : history.deletedDirectories()) {
        Path path = safeHistoryPath(root, directory);
        if (path != null) Files.createDirectories(path);
      }
      List<String> created = new ArrayList<>(history.createdDirectories());
      created.sort(Comparator.comparingInt((String path) -> Path.of(path).getNameCount()).reversed());
      for (String directory : created) {
        Path path = safeHistoryPath(root, directory);
        if (path == null || Files.isSymbolicLink(path) || !Files.isDirectory(path)) continue;
        boolean empty;
        try (Stream<Path> children = Files.list(path)) {
          empty = children.findAny().isEmpty();
        }
        if (empty) {
          Files.delete(path);
          status("已清理新建空文件夹: " + directory);
        }
      }
      if (unresolved.isEmpty()) {
        writeHistory(historyFile, new History(List.of(), history.deletedDirectories(), history.createdDirectories(), true));
        status("撤销完成");
      } else {
        Collections.reverse(unresolved);
        writeHistory(historyFile, new History(unresolved, history.deletedDirectories(), history.createdDirectories(), false));
        status("撤销完成，但仍有 " + unresolved.size() + " 个冲突项");
      }
    } catch (IOException e) {
      status("撤销失败: " + e.getMessage());
    }
  }

  static History readHistory(Path file) throws IOException {
    String json = Files.readString(file, StandardCharsets.UTF_8);
    boolean undone = Boolean.TRUE.equals(extractBoolean(json, "undone"));
    List<MoveRecord> moved = new ArrayList<>();
    Matcher matcher = Pattern.compile("\\{\\s*\\\"source\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"\\s*,\\s*\\\"destination\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"\\s*,\\s*\\\"size\\\"\\s*:\\s*(\\d+)\\s*,\\s*\\\"modified\\\"\\s*:\\s*(\\d+)\\s*}").matcher(json);
    while (matcher.find()) moved.add(new MoveRecord(unescape(matcher.group(1)), unescape(matcher.group(2)),
        Long.parseLong(matcher.group(3)), Long.parseLong(matcher.group(4))));
    List<String> deleted = new ArrayList<>();
    Matcher deletedSection = Pattern.compile("\\\"deletedDirectories\\\"\\s*:\\s*\\[(.*?)]").matcher(json);
    if (deletedSection.find()) {
      Matcher item = Pattern.compile("\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(deletedSection.group(1));
      while (item.find()) deleted.add(unescape(item.group(1)));
    }
    List<String> created = new ArrayList<>();
    Matcher createdSection = Pattern.compile("\\\"createdDirectories\\\"\\s*:\\s*\\[(.*?)]").matcher(json);
    if (createdSection.find()) {
      Matcher item = Pattern.compile("\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(createdSection.group(1));
      while (item.find()) created.add(unescape(item.group(1)));
    }
    return new History(moved, deleted, created, undone);
  }

  static void status(String message) {
    System.out.printf("[%s] %s%n", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), message);
    System.out.flush();
  }

  static boolean isAiAuthenticationFailure(String reason) {
    return reason != null && (reason.contains("HTTP 401") || reason.contains("HTTP 403"));
  }

  static String aiError(int statusCode, String baseUrl) {
    String endpoint = baseUrl == null ? "未知接口" : baseUrl;
    return switch (statusCode) {
      case 401 -> "AI 认证失败 (HTTP 401)，当前接口为 " + endpoint + "；请检查 ai.api-key 是否属于该服务";
      case 403 -> "AI 访问被拒绝 (HTTP 403)，当前接口为 " + endpoint + "；请检查 API Key 权限和模型权限";
      case 429 -> "AI 请求过于频繁 (HTTP 429)，请稍后重试或检查额度";
      default -> "AI 请求失败 (HTTP " + statusCode + ")，接口为 " + endpoint;
    };
  }

  static String apiPath(Config config) {
    return "responses".equalsIgnoreCase(config.wireApi()) ? "responses" : "chat/completions";
  }

  static String requestBody(Config config, String prompt) {
    if ("responses".equalsIgnoreCase(config.wireApi())) {
      return "{\"model\":\"" + escape(config.model()) + "\",\"reasoning\":{\"effort\":\"" + escape(config.reasoningEffort()) + "\"},\"stream\":true,\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"" + escape(prompt) + "\"}]}]}";
    }
    return "{\"model\":\"" + escape(config.model()) + "\",\"reasoning_effort\":\"" + escape(config.reasoningEffort()) + "\",\"temperature\":0,\"messages\":[{\"role\":\"user\",\"content\":\"" + escape(prompt) + "\"}]}";
  }

  static String responseContent(String response, String wireApi) {
    if ("responses".equalsIgnoreCase(wireApi)) {
      if (response != null && response.contains("data:")) return streamedResponseContent(response);
      String outputText = extract(response, "output_text");
      return outputText == null ? extract(response, "text") : outputText;
    }
    return extract(response, "content");
  }

  static String streamedResponseContent(String response) {
    StringBuilder deltas = new StringBuilder();
    String completed = null;
    for (String line : response.split("\\R")) {
      if (!line.startsWith("data: ")) continue;
      String event = line.substring(6).trim();
      if (event.equals("[DONE]")) continue;
      String delta = extract(event, "delta");
      if (delta != null) deltas.append(delta);
      String done = extract(event, "text");
      if (event.contains("response.output_text.done") && done != null) return done;
      if (event.contains("response.completed") && done != null) completed = done;
    }
    if (!deltas.isEmpty()) return deltas.toString();
    return completed;
  }

  static String finalResponseEvent(String response) {
    String eventType = "没有可识别的 SSE 事件";
    for (String line : response.split("\\R")) {
      if (!line.startsWith("data: ")) continue;
      String type = extract(line.substring(6), "type");
      if (type != null) eventType = type;
    }
    return "最终事件: " + eventType;
  }

  static String extension(Path file) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  static boolean isText(Path file) { return Set.of("txt", "md", "csv", "json", "xml", "log").contains(extension(file)); }

  static String readText(Path file) throws IOException {
    return readText(file, 4000);
  }

  static String readText(Path file, int maxChars) throws IOException {
    String text = Files.readString(file, StandardCharsets.UTF_8);
    return text.substring(0, Math.min(maxChars, text.length()));
  }

  static String escape(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  static String extract(String json, String key) {
    if (json == null) return null;
    Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(json);
    return matcher.find() ? unescape(matcher.group(1)) : null;
  }

  static Boolean extractBoolean(String json, String key) {
    if (json == null) return null;
    Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(json);
    return matcher.find() ? Boolean.valueOf(matcher.group(1)) : null;
  }

  static String unescape(String text) {
    return text.replace("\\\\", "\\")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t");
  }

  static String relative(Path root, Path path) {
    return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
        .toString().replace('\\', '/');
  }

  static boolean hasArgument(String[] args, String expected) {
    for (String arg : args) if (expected.equals(arg)) return true;
    return false;
  }

}
