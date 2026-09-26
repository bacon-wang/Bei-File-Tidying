package com.example.tidying;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class EfficientPlanningTest {
  @TempDir Path root;

  @Test
  void clearFilesStayLocalAndMetadataRequestsExcludeContent() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Files.createDirectories(root.resolve("Pictures"));
    Files.createDirectories(root.resolve("Documents"));
    Files.createDirectories(root.resolve("Archives"));
    Files.writeString(inbox.resolve("trip-photo.png"), "photo");
    Files.writeString(inbox.resolve("invoice.pdf"), "invoice");
    Files.writeString(inbox.resolve("project-backup.zip"), "backup");
    Files.writeString(inbox.resolve("notes.md"), "DO_NOT_SEND_THIS_BODY");
    try (MockAi ai = new MockAi(body -> new Reply(200, answer("Inbox/notes.md", "Work", false)))) {
      var plans = analyze(ai.config(root, inbox));
      assertEquals(4, plans.stream().filter(p -> p.status().equals("READY")).count());
      assertEquals(1, ai.requests.size());
      String request = ai.requests.get(0);
      assertFalse(request.contains("DO_NOT_SEND_THIS_BODY"));
      assertFalse(request.contains("trip-photo.png"));
      assertFalse(request.contains("invoice.pdf"));
      assertFalse(request.contains("project-backup.zip"));
      assertTrue(Files.exists(inbox.resolve("notes.md")), "Planning must not move files");
    }
  }

  @Test
  void onlyRequestedTextGetsOneBoundedContentPass() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Files.writeString(inbox.resolve("a.txt"), "TEXT_MARKER " + "x".repeat(2000) + "NEVER_SEND_TAIL");
    Files.writeString(inbox.resolve("b.txt"), "PRIVATE_B_BODY");
    Files.writeString(inbox.resolve("c.pdf"), "BINARY_BODY");
    try (MockAi ai = new MockAi(body -> new Reply(200, body.contains("TEXT_MARKER")
        ? answer("Inbox/a.txt", "Documents", false)
        : "[" + item("Inbox/a.txt", "", true) + "," + item("Inbox/b.txt", "Documents", false)
            + "," + item("Inbox/c.pdf", "", true) + "]"))) {
      var plans = analyze(ai.config(root, inbox));
      assertEquals(2, ai.requests.size());
      assertFalse(ai.requests.get(0).contains("TEXT_MARKER"));
      assertTrue(ai.requests.get(1).contains("TEXT_MARKER"));
      assertFalse(ai.requests.get(1).contains("Inbox/b.txt"));
      assertTrue(ai.requests.stream().noneMatch(s -> s.contains("NEVER_SEND_TAIL")
          || s.contains("PRIVATE_B_BODY") || s.contains("BINARY_BODY")));
      assertEquals(2, plans.stream().filter(p -> p.status().equals("READY")).count());
      assertEquals("REVIEW", plans.get(2).status());
    }
  }

  @Test
  void failedBatchDoesNotFanOutIntoSingleFileRequests() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    for (int i = 0; i < 10; i++) Files.writeString(inbox.resolve("file-" + i + ".txt"), "body");
    try (MockAi ai = new MockAi(body -> new Reply(200, "not json"))) {
      var plans = analyze(ai.config(root, inbox));
      assertEquals(2, ai.requests.size(), "Only one batch retry is allowed");
      assertTrue(plans.stream().allMatch(p -> p.status().equals("REVIEW")));
    }
    try (MockAi ai = new MockAi(body -> new Reply(429, "{}"))) {
      var plans = analyze(ai.config(root, inbox));
      assertEquals(1, ai.requests.size(), "Rate limits must not trigger a retry storm");
      assertTrue(plans.stream().allMatch(p -> p.reason().contains("429")));
    }
  }

  @Test
  void retryOnlyContainsMissingFiles() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Files.writeString(inbox.resolve("a.txt"), "a");
    Files.writeString(inbox.resolve("b.txt"), "b");
    try (MockAi ai = new MockAi(body -> new Reply(200, body.contains("Inbox/a.txt")
        ? answer("Inbox/a.txt", "Documents", false) : answer("Inbox/b.txt", "Documents", false)))) {
      var plans = analyze(ai.config(root, inbox));
      assertEquals(2, ai.requests.size());
      assertFalse(ai.requests.get(1).contains("Inbox/a.txt"));
      assertEquals(2, plans.stream().filter(p -> p.status().equals("READY")).count());
    }
  }

  @Test
  void scaleUsesLocalRulesWithoutLosingPerFilePlans() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Files.createDirectories(root.resolve("Pictures"));
    for (int i = 0; i < 180; i++) Files.writeString(inbox.resolve("trip-photo-" + i + ".png"), "photo");
    for (int i = 0; i < 4; i++) Files.writeString(inbox.resolve("unknown-" + i + ".txt"), "PRIVATE_BODY");
    try (MockAi ai = new MockAi(body -> {
      String[] items = new String[4];
      for (int i = 0; i < 4; i++) items[i] = item("Inbox/unknown-" + i + ".txt", "Documents", false);
      return new Reply(200, "[" + String.join(",", items) + "]");
    })) {
      long started = System.nanoTime();
      var plans = analyze(ai.config(root, inbox));
      assertEquals(184, plans.size());
      assertTrue(plans.stream().allMatch(p -> p.status().equals("READY")));
      assertEquals(1, ai.requests.size(), "180 local files plus four unresolved files need one batch");
      assertFalse(ai.requests.get(0).contains("trip-photo"));
      System.out.printf("184-file synthetic scenario: 1 request, %.2f seconds%n", (System.nanoTime() - started) / 1e9);
    }
  }

  @Test
  void requestBudgetIsSharedByConcurrentBatches() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    for (int i = 0; i < 30; i++) Files.writeString(inbox.resolve("unknown-" + i + ".txt"), "body");
    try (MockAi ai = new MockAi(body -> new Reply(200, "[]"))) {
      var base = ai.config(root, inbox);
      var config = new FileTidyingAssistant.Config(root, inbox, base.maxBytes(), base.baseUrl(), base.apiKey(),
          base.model(), base.reasoningEffort(), base.wireApi(), 3, 10, 2, true, 1, 12000);
      var plans = analyze(config);
      assertEquals(1, ai.requests.size());
      assertEquals(30, plans.size());
      assertTrue(plans.stream().allMatch(p -> p.status().equals("REVIEW")));
      assertTrue(plans.stream().anyMatch(p -> p.reason().contains("预算")));
    }
  }

  @Test
  void rulesDeferWhenContextOrDestinationsAreAmbiguous() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Path pictures = Files.createDirectories(root.resolve("Pictures"));
    var directories = FileTidyingAssistant.existingDirectories(root, inbox);
    assertNull(FileTidyingAssistant.localClassification(inbox.resolve("image.png"), root, inbox, directories));
    Path project = Files.createDirectories(inbox.resolve("Project"));
    assertNull(FileTidyingAssistant.localClassification(project.resolve("trip-photo.png"), root, inbox, directories));
    Files.createDirectories(root.resolve("Photos"));
    assertNull(FileTidyingAssistant.localClassification(inbox.resolve("trip-photo.png"), root, inbox,
        FileTidyingAssistant.existingDirectories(root, inbox)));
    Files.createDirectories(pictures.resolve("Trips"));
    assertNull(FileTidyingAssistant.localClassification(inbox.resolve("trip-photo.png"), root, inbox,
        List.of(pictures, pictures.resolve("Trips"))));
  }

  @Test
  void usageCountsReportedTokensOnceAndDoesNotEstimateMissingUsage() {
    var stats = new FileTidyingAssistant.AnalysisStats(20);
    stats.recordUsage("data: {\"usage\":null}\n\ndata: {\"usage\":{\"input_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":5},\"output_tokens\":7}}\n\n");
    stats.recordUsage("{\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":3}}");
    stats.recordUsage("{\"output_text\":\"no usage\"}");
    stats.recordUsage("{\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}");
    assertEquals(2, stats.usageResponses);
    assertEquals(30, stats.inputTokens);
    assertEquals(10, stats.outputTokens);
  }

  @Test
  void inputLimitsSplitBatchesAndRelevantDeepDirectoriesStayVisible() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Path deep = Files.createDirectories(root.resolve("Learning/Engineering/Programming/Java"));
    for (int i = 0; i < 100; i++) Files.createDirectories(root.resolve("Unrelated-" + i + "-" + "x".repeat(30)));
    List<Path> files = new java.util.ArrayList<>();
    for (int i = 0; i < 12; i++) {
      Path file = inbox.resolve("java-notes-" + i + "-" + "a".repeat(140) + ".md");
      Files.writeString(file, "not needed"); files.add(file);
    }
    var directories = FileTidyingAssistant.existingDirectories(root, inbox);
    String context = FileTidyingAssistant.directoryContext(java.util.Set.copyOf(files), root, directories);
    assertTrue(context.contains("Learning/Engineering/Programming/Java/"));
    assertTrue(context.length() <= FileTidyingAssistant.CONTEXT_CHARS);
    try (MockAi ai = new MockAi(body -> {
      String prompt = FileTidyingAssistant.extract(body, "text");
      List<String> items = files.stream().filter(p -> prompt.contains("source=" + root.relativize(p)))
          .map(p -> item(root.relativize(p).toString(), root.relativize(deep).toString(), false)).toList();
      return new Reply(200, "[" + String.join(",", items) + "]");
    })) {
      var base = ai.config(root, inbox);
      var config = new FileTidyingAssistant.Config(root, inbox, base.maxBytes(), base.baseUrl(), base.apiKey(),
          base.model(), base.reasoningEffort(), base.wireApi(), 3, 100, 2, true, 20, 4096);
      var plans = analyze(config);
      assertTrue(ai.requests.size() > 1, "Character budget must split before file-count limit");
      assertTrue(ai.requests.stream().allMatch(body -> FileTidyingAssistant.extract(body, "text").length() <= 4096));
      assertEquals(12, plans.stream().filter(p -> p.status().equals("READY")).count());
    }
  }

  private List<FileTidyingAssistant.Plan> analyze(FileTidyingAssistant.Config config) throws Exception {
    return FileTidyingAssistant.planAll(FileTidyingAssistant.targetFiles(config.target()), config, root,
        FileTidyingAssistant.existingDirectories(root, config.target()));
  }

  static String item(String source, String destination, boolean needsContent) {
    return "{\"source\":\"" + source + "\",\"destination\":\"" + destination
        + "\",\"createDirectory\":true,\"needsContent\":" + needsContent + ",\"reason\":\"test\"}";
  }

  static String answer(String source, String destination, boolean needsContent) {
    return "[" + item(source, destination, needsContent) + "]";
  }

  record Reply(int status, String content) {}

  static class MockAi implements AutoCloseable {
    final List<String> requests = new CopyOnWriteArrayList<>();
    final HttpServer server;

    MockAi(Function<String, Reply> respond) throws Exception {
      server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/responses", exchange -> {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(body);
        Reply reply = respond.apply(body);
        String event = "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"content\":[{\"text\":\""
            + FileTidyingAssistant.escape(reply.content()) + "\"}]}],\"usage\":{\"input_tokens\":100,\"output_tokens\":20}}}\n\n";
        byte[] bytes = (reply.status() == 200 ? event : "{}").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
      });
      server.start();
    }

    FileTidyingAssistant.Config config(Path root, Path target) {
      return new FileTidyingAssistant.Config(root, target, 10_000,
          "http://127.0.0.1:" + server.getAddress().getPort(), "test", "gpt-6-luna", "medium", "responses");
    }

    @Override public void close() { server.stop(0); }
  }
}
