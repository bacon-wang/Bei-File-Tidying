package com.example.tidying;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class GroupCacheTest {
  @TempDir Path root;

  @Test void numberedSeriesUsesOneGroupAnswerAndSeparatesOutlier() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox/Java Course"));
    Files.createDirectories(root.resolve("Learning/Java"));
    Files.createDirectories(root.resolve("Documents"));
    for (int i = 1; i <= 24; i++) Files.writeString(inbox.resolve("Java-Lecture-%03d.pdf".formatted(i)), "pdf");
    Path outlier = inbox.resolve("Python-Lecture-999.pdf");
    Files.writeString(outlier, "unrelated pdf");
    try (var ai = new EfficientPlanningTest.MockAi(body -> {
      String prompt = FileTidyingAssistant.extract(body, "text");
      Matcher source = Pattern.compile("source=([^,\\n]+)").matcher(prompt);
      assertTrue(source.find());
      boolean group = prompt.contains("groupPrefix=");
      String answer = "[{\"source\":\"" + source.group(1) + "\",\"destination\":\""
          + (group ? "Learning/Java" : "Documents")
          + "\",\"createDirectory\":false,\"needsContent\":false,\"uniform\":" + group
          + ",\"reason\":\"test\"}]";
      return new EfficientPlanningTest.Reply(200, answer);
    })) {
      var plans = analyze(ai.config(root, inbox), false);
      assertEquals(25, plans.size());
      assertEquals(2, ai.requests.size(), "24 related files should use one group request");
      String groupPrompt = ai.requests.stream().map(s -> FileTidyingAssistant.extract(s, "text"))
          .filter(s -> s.contains("groupPrefix=")).findFirst().orElseThrow();
      assertTrue(groupPrompt.contains("Java-Lecture-024.pdf"), "Every member must be visible to the model");
      assertFalse(groupPrompt.contains("Python-Lecture-999.pdf"));
      for (int i = 1; i <= 24; i++) {
        Path file = inbox.resolve("Java-Lecture-%03d.pdf".formatted(i));
        assertEquals(root.resolve("Learning/Java").resolve(file.getFileName()), planFor(plans, file).target());
      }
      assertEquals(root.resolve("Documents/Python-Lecture-999.pdf"), planFor(plans, outlier).target());
    }
  }

  @Test void uncertainGroupFallsBackWithinRequestBudget() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox/Java Course"));
    Files.createDirectories(root.resolve("Documents"));
    for (int i = 1; i <= 12; i++) Files.writeString(inbox.resolve("Java-Lecture-%03d.pdf".formatted(i)), "pdf");
    try (var ai = new EfficientPlanningTest.MockAi(body -> {
      String prompt = FileTidyingAssistant.extract(body, "text");
      if (prompt.contains("groupPrefix=")) {
        Matcher source = Pattern.compile("source=([^,\\n]+)").matcher(prompt);
        assertTrue(source.find());
        return new EfficientPlanningTest.Reply(200, "[{\"source\":\"" + source.group(1)
            + "\",\"destination\":\"Documents\",\"createDirectory\":false,\"uniform\":false,\"reason\":\"mixed\"}]");
      }
      return new EfficientPlanningTest.Reply(200, "[]");
    })) {
      var base = ai.config(root, inbox);
      var config = new FileTidyingAssistant.Config(root, inbox, base.maxBytes(), base.baseUrl(), base.apiKey(),
          base.model(), base.reasoningEffort(), base.wireApi(), 3, 10, 2, true, 2, 12000);
      var plans = analyze(config, false);
      assertTrue(ai.requests.size() <= 2);
      assertEquals(12, plans.size());
      assertTrue(plans.stream().allMatch(p -> p.status().equals("REVIEW") && p.source().equals(p.target())));
    }
  }

  @Test void cacheInvalidatesFileDirectoryModelAndExplicitRefresh() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Files.createDirectories(root.resolve("Documents"));
    Path file = inbox.resolve("notes.md");
    Files.writeString(file, "first");
    try (var ai = new EfficientPlanningTest.MockAi(body ->
        new EfficientPlanningTest.Reply(200, EfficientPlanningTest.answer("Inbox/notes.md", "Documents", false)))) {
      var config = ai.config(root, inbox);
      assertEquals("READY", planFor(analyze(config, false), file).status());
      assertEquals("READY", planFor(analyze(config, false), file).status());
      assertEquals(1, ai.requests.size());
      Files.writeString(file, "second");
      Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));
      analyze(config, false);
      assertEquals(2, ai.requests.size());
      Files.createDirectories(root.resolve("New Category"));
      analyze(config, false);
      assertEquals(3, ai.requests.size());
      var otherModel = new FileTidyingAssistant.Config(root, inbox, config.maxBytes(), config.baseUrl(), config.apiKey(),
          "another-model", config.reasoningEffort(), config.wireApi());
      analyze(otherModel, false);
      assertEquals(4, ai.requests.size());
      analyze(otherModel, true);
      assertEquals(5, ai.requests.size());
    }
  }

  @Test void addedMemberInvalidatesWholeGroupCache() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox/Java Course"));
    Files.createDirectories(root.resolve("Documents"));
    for (int i = 1; i <= 8; i++) Files.writeString(inbox.resolve("Java-Lecture-%03d.pdf".formatted(i)), "pdf");
    try (var ai = new EfficientPlanningTest.MockAi(body -> {
      String prompt = FileTidyingAssistant.extract(body, "text");
      assertTrue(prompt.contains("groupPrefix="));
      Matcher source = Pattern.compile("source=([^,\\n]+)").matcher(prompt);
      assertTrue(source.find());
      return new EfficientPlanningTest.Reply(200, "[{\"source\":\"" + source.group(1)
          + "\",\"destination\":\"Documents\",\"createDirectory\":false,\"uniform\":true,\"reason\":\"series\"}]");
    })) {
      var config = ai.config(root, inbox);
      assertEquals(8, analyze(config, false).size());
      assertEquals(8, analyze(config, false).size());
      assertEquals(1, ai.requests.size());
      Files.writeString(inbox.resolve("Java-Lecture-009.pdf"), "pdf");
      var plans = analyze(config, false);
      assertEquals(2, ai.requests.size());
      assertEquals(9, plans.stream().filter(p -> p.status().equals("READY")).count());
      assertTrue(Files.exists(inbox.resolve("Java-Lecture-001.pdf")), "Planning alone must not move files");
    }
  }

  @Test void cachedSuggestionStillDetectsSameNameConflict() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Path destination = Files.createDirectories(root.resolve("Documents"));
    Path source = inbox.resolve("notes.md");
    Files.writeString(source, "notes");
    try (var ai = new EfficientPlanningTest.MockAi(body ->
        new EfficientPlanningTest.Reply(200, EfficientPlanningTest.answer("Inbox/notes.md", "Documents", false)))) {
      var config = ai.config(root, inbox);
      assertEquals("READY", planFor(analyze(config, false), source).status());
      Files.writeString(destination.resolve("notes.md"), "already here");
      var conflict = planFor(analyze(config, false), source);
      assertEquals("CONFLICT", conflict.status());
      assertEquals(1, ai.requests.size());
      assertEquals("notes", Files.readString(source));
      assertEquals("already here", Files.readString(destination.resolve("notes.md")));
    }
  }

  @Test void invalidResultsAreNotCachedAndUnsafeDestinationsAreRejected() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Files.createDirectories(root.resolve("Documents"));
    Files.writeString(inbox.resolve("notes.md"), "notes");
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var ai = new EfficientPlanningTest.MockAi(body -> new EfficientPlanningTest.Reply(200,
        calls.incrementAndGet() <= 2 ? "[]" : EfficientPlanningTest.answer("Inbox/notes.md", "Documents", false)))) {
      var config = ai.config(root, inbox);
      assertEquals("REVIEW", planFor(analyze(config, false), inbox.resolve("notes.md")).status());
      assertEquals(2, ai.requests.size());
      assertEquals("READY", planFor(analyze(config, false), inbox.resolve("notes.md")).status());
      assertEquals(3, ai.requests.size());
    }
    Path outside = Files.createTempDirectory("tidying-outside-");
    Files.delete(root.resolve("Documents"));
    Files.createSymbolicLink(root.resolve("Documents"), outside);
    assertNull(FileTidyingAssistant.resolveDestination("Documents", root, inbox));
    assertNull(FileTidyingAssistant.resolveDestination(".bei-file-tidying/history", root, inbox));
  }

  @Test void sameSizeAndTimestampTextChangeInvalidatesCache() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Files.createDirectories(root.resolve("Documents"));
    Path file = inbox.resolve("notes.md");
    Files.writeString(file, "one");
    try (var ai = new EfficientPlanningTest.MockAi(body ->
        new EfficientPlanningTest.Reply(200, EfficientPlanningTest.answer("Inbox/notes.md", "Documents", false)))) {
      var config = ai.config(root, inbox);
      analyze(config, false);
      FileTime before = Files.getLastModifiedTime(file);
      Files.writeString(file, "two");
      Files.setLastModifiedTime(file, before);
      analyze(config, false);
      assertEquals(2, ai.requests.size());
    }
  }

  @Test void executeRechecksDestinationAfterPlanBeforeMoving() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Path documents = Files.createDirectories(root.resolve("Documents"));
    Path outside = Files.createDirectories(root.resolve("Outside"));
    Path source = inbox.resolve("notes.md");
    Files.writeString(source, "notes");
    var plan = new FileTidyingAssistant.Plan(source, documents.resolve("notes.md"), "test", "READY");
    Files.delete(documents);
    Files.createSymbolicLink(documents, outside);
    FileTidyingAssistant.execute(List.of(plan), root, inbox, true);
    assertTrue(Files.exists(source));
    assertFalse(Files.exists(outside.resolve("notes.md")));
  }

  @Test void memberRowsInGroupAnswerTriggerPerFileFallback() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox/Java Course"));
    Files.createDirectories(root.resolve("Learning/Java"));
    Files.createDirectories(root.resolve("Documents"));
    for (int i = 1; i <= 8; i++) Files.writeString(inbox.resolve("Java-Lecture-%03d.pdf".formatted(i)), "pdf");
    try (var ai = new EfficientPlanningTest.MockAi(body -> {
      String prompt = FileTidyingAssistant.extract(body, "text");
      if (prompt.contains("groupPrefix=")) {
        assertTrue(prompt.contains("members are context, not separate inputs"));
        return new EfficientPlanningTest.Reply(200, "["
            + EfficientPlanningTest.item("Inbox/Java Course/Java-Lecture-001.pdf", "Learning/Java", false)
                .replace("\"reason\":", "\"uniform\":true,\"reason\":") + ","
            + EfficientPlanningTest.item("Inbox/Java Course/Java-Lecture-002.pdf", "Documents", false)
                .replace("\"reason\":", "\"uniform\":false,\"reason\":") + "]");
      }
      String answer = java.util.stream.IntStream.rangeClosed(1, 8)
          .mapToObj(i -> EfficientPlanningTest.item("Inbox/Java Course/Java-Lecture-%03d.pdf".formatted(i),
              i == 1 ? "Learning/Java" : "Documents", false)).collect(java.util.stream.Collectors.joining(","));
      return new EfficientPlanningTest.Reply(200, "[" + answer + "]");
    })) {
      var base = ai.config(root, inbox);
      var config = new FileTidyingAssistant.Config(root, inbox, base.maxBytes(), base.baseUrl(), base.apiKey(),
          base.model(), base.reasoningEffort(), base.wireApi(), 3, 10, 2, true, 3, 12000);
      var plans = analyze(config, false);
      assertEquals(3, ai.requests.size());
      assertEquals(root.resolve("Learning/Java/Java-Lecture-001.pdf"), planFor(plans, inbox.resolve("Java-Lecture-001.pdf")).target());
      assertEquals(root.resolve("Documents/Java-Lecture-002.pdf"), planFor(plans, inbox.resolve("Java-Lecture-002.pdf")).target());
    }
  }

  @Test void genericReportsAndProjectFilesDoNotBecomeGroups() throws Exception {
    Path reports = Files.createDirectories(root.resolve("Inbox/Mixed"));
    Path project = Files.createDirectories(root.resolve("Inbox/Project/assets"));
    Files.writeString(project.getParent().resolve("pom.xml"), "project");
    java.util.Set<Path> files = new java.util.LinkedHashSet<>();
    for (int i = 1; i <= 8; i++) {
      files.add(Files.writeString(reports.resolve("Report-%03d.pdf".formatted(i)), "mixed"));
      files.add(Files.writeString(project.resolve("Java-Lecture-%03d.pdf".formatted(i)), "asset"));
    }
    assertTrue(FileTidyingAssistant.numberedGroups(files, root).isEmpty());
  }

  @Test void perFileCacheIsUsedAfterUncertainGroup() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox/Java Course"));
    Files.createDirectories(root.resolve("Documents"));
    for (int i = 1; i <= 8; i++) Files.writeString(inbox.resolve("Java-Lecture-%03d.pdf".formatted(i)), "pdf");
    try (var ai = new EfficientPlanningTest.MockAi(body -> {
      String prompt = FileTidyingAssistant.extract(body, "text");
      if (prompt.contains("groupPrefix=")) return new EfficientPlanningTest.Reply(200,
          "[{\"source\":\"Inbox/Java Course/Java-Lecture-001.pdf\",\"destination\":\"\",\"uniform\":false}]");
      String answer = java.util.stream.IntStream.rangeClosed(1, 8)
          .mapToObj(i -> EfficientPlanningTest.item("Inbox/Java Course/Java-Lecture-%03d.pdf".formatted(i),
              "Documents", false)).collect(java.util.stream.Collectors.joining(","));
      return new EfficientPlanningTest.Reply(200, "[" + answer + "]");
    })) {
      var config = ai.config(root, inbox);
      assertEquals(8, analyze(config, false).stream().filter(p -> p.status().equals("READY")).count());
      assertEquals(2, ai.requests.size());
      assertEquals(8, analyze(config, false).stream().filter(p -> p.status().equals("READY")).count());
      assertEquals(2, ai.requests.size(), "Per-file fallback results should prevent another group request");
    }
  }

  @Test void invalidGroupDestinationFallsBackWithoutAbortingPlan() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox/Java Course"));
    Files.createDirectories(root.resolve("Documents"));
    for (int i = 1; i <= 8; i++) Files.writeString(inbox.resolve("Java-Lecture-%03d.pdf".formatted(i)), "pdf");
    try (var ai = new EfficientPlanningTest.MockAi(body -> {
      String prompt = FileTidyingAssistant.extract(body, "text");
      if (prompt.contains("groupPrefix=")) return new EfficientPlanningTest.Reply(200,
          "[{\"source\":\"Inbox/Java Course/Java-Lecture-001.pdf\",\"destination\":\"Bad"
              + (char) 0 + "Path\",\"createDirectory\":true,\"uniform\":true}]");
      String answer = java.util.stream.IntStream.rangeClosed(1, 8)
          .mapToObj(i -> EfficientPlanningTest.item("Inbox/Java Course/Java-Lecture-%03d.pdf".formatted(i),
              "Documents", false)).collect(java.util.stream.Collectors.joining(","));
      return new EfficientPlanningTest.Reply(200, "[" + answer + "]");
    })) {
      var plans = analyze(ai.config(root, inbox), false);
      assertEquals(2, ai.requests.size());
      assertTrue(plans.stream().allMatch(p -> p.status().equals("READY") && p.target().startsWith(root.resolve("Documents"))));
    }
  }

  @Test void changedFileDuringMetadataRequestIsReviewedAndNotCached() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Files.createDirectories(root.resolve("Documents"));
    Files.createDirectories(root.resolve("Archives"));
    Path file = Files.writeString(inbox.resolve("notes.md"), "one");
    FileTime originalTime = Files.getLastModifiedTime(file);
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var ai = new EfficientPlanningTest.MockAi(body -> {
      int call = calls.incrementAndGet();
      if (call == 1) {
        try {
          Files.writeString(file, "two");
          Files.setLastModifiedTime(file, originalTime);
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
      }
      return new EfficientPlanningTest.Reply(200,
          EfficientPlanningTest.answer("Inbox/notes.md", call == 1 ? "Documents" : "Archives", false));
    })) {
      var config = ai.config(root, inbox);
      var first = planFor(analyze(config, false), file);
      assertEquals("REVIEW", first.status());
      assertEquals(file, first.target());
      assertEquals("READY", planFor(analyze(config, false), file).status());
      assertEquals(root.resolve("Archives/notes.md"), planFor(analyze(config, false), file).target());
      assertEquals(2, ai.requests.size(), "Changed source must not cache the first answer");
    }
  }

  @Test void changedFileDuringContentRequestIsReviewed() throws Exception {
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Files.createDirectories(root.resolve("Documents"));
    Path file = Files.writeString(inbox.resolve("notes.md"), "initial");
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var ai = new EfficientPlanningTest.MockAi(body -> {
      int call = calls.incrementAndGet();
      if (call == 2) {
        try { Files.writeString(file, "updated content"); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
      }
      return new EfficientPlanningTest.Reply(200,
          EfficientPlanningTest.answer("Inbox/notes.md", call == 1 ? "" : "Documents", call == 1));
    })) {
      var plan = planFor(analyze(ai.config(root, inbox), false), file);
      assertEquals("REVIEW", plan.status());
      assertEquals(file, plan.target());
      assertEquals(2, ai.requests.size());
    }
  }

  private List<FileTidyingAssistant.Plan> analyze(FileTidyingAssistant.Config config, boolean refresh) throws Exception {
    return FileTidyingAssistant.planAll(FileTidyingAssistant.targetFiles(config.target()), config, root,
        FileTidyingAssistant.existingDirectories(root, config.target()), refresh);
  }

  private static FileTidyingAssistant.Plan planFor(List<FileTidyingAssistant.Plan> plans, Path source) {
    return plans.stream().filter(p -> p.source().equals(source)).findFirst().orElseThrow();
  }
}
