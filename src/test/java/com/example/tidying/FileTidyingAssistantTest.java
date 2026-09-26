package com.example.tidying;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileTidyingAssistantTest {
  @Test
  void treeDepthAndCompletedEventFallbackWork() throws Exception {
    Path root = Files.createTempDirectory("tidying-tree-root");
    Files.createDirectories(root.resolve("Pictures/Trips/2026"));
    Files.createDirectories(root.resolve(".git/objects"));
    String tree = FileTidyingAssistant.structure(root, 2);
    assertTrue(tree.contains("Pictures/"));
    assertTrue(tree.contains("Trips/"));
    assertFalse(tree.contains("2026/"));
    assertFalse(tree.contains(".git/"));

    String answer = "[{\"source\":\"Inbox/a.txt\",\"destination\":\"Documents\",\"createDirectory\":true,\"reason\":\"notes\"}]";
    String event = "data: {\"type\":\"response.completed\",\"output\":[{\"content\":[{\"text\":\""
        + FileTidyingAssistant.escape(answer) + "\"}]}]}\n\n";
    assertEquals(answer, FileTidyingAssistant.responseContent(event, "responses"));
  }

  @Test
  void undoRemovesNewlyCreatedDestinationDirectories() throws Exception {
    Path root = Files.createTempDirectory("tidying-new-folder-root");
    Path target = Files.createDirectories(root.resolve("Inbox"));
    Path source = target.resolve("invoice.pdf");
    Files.writeString(source, "invoice");
    Path destination = root.resolve("Documents/Invoices/invoice.pdf");
    FileTidyingAssistant.execute(List.of(new FileTidyingAssistant.Plan(source, destination, "invoice", "READY")), root, target, true);
    assertTrue(Files.exists(destination));
    FileTidyingAssistant.undoLast(root);
    assertTrue(Files.exists(source));
    assertFalse(Files.exists(root.resolve("Documents")));
  }

  @Test
  void localFallbackAndNewFolderPlanWork() throws Exception {
    Path root = Files.createTempDirectory("tidying-root");
    Path target = Files.createDirectories(root.resolve("Downloads"));
    Path pictures = Files.createDirectories(root.resolve("Pictures"));
    Path image = target.resolve("holiday.jpg");
    Files.writeString(image, "image");
    Path nested = Files.createDirectories(target.resolve("old")).resolve("notes.txt");
    Files.writeString(nested, "text");
    var config = new FileTidyingAssistant.Config(root, target, 10_000, "", "", "gpt-6-luna", "medium", "responses");
    var directories = FileTidyingAssistant.existingDirectories(root, target);

    var imagePlan = FileTidyingAssistant.plan(image, config, root, directories);
    assertEquals("READY", imagePlan.status());
    assertEquals(pictures.resolve("holiday.jpg"), imagePlan.target());
    assertEquals(2, FileTidyingAssistant.targetFiles(target).size());
    assertNull(FileTidyingAssistant.resolveDestination("../Pictures", root, target));
    assertEquals(root.resolve("NewFolder"), FileTidyingAssistant.resolveDestination("NewFolder", root, target));
    assertEquals(root.resolve("Downloads/NewFolder"), FileTidyingAssistant.resolveDestination("Downloads/NewFolder", root, target));
    assertTrue(FileTidyingAssistant.suggestsCurrentDirectory("Downloads/old", root, nested));

    Path pdf = target.resolve("report.pdf");
    Files.writeString(pdf, "pdf");
    var newFolderPlan = FileTidyingAssistant.plan(pdf, config, root, directories);
    assertEquals("READY", newFolderPlan.status());
    assertEquals(root.resolve("Documents/report.pdf"), newFolderPlan.target());
    FileTidyingAssistant.execute(List.of(newFolderPlan));
    assertTrue(Files.exists(root.resolve("Documents/report.pdf")));
  }

  @Test
  void aiSuccessAndUnauthorizedResponsesAreHandled() throws Exception {
    Path root = Files.createTempDirectory("tidying-ai-root");
    Path target = Files.createDirectories(root.resolve("Downloads"));
    Files.createDirectories(root.resolve("Pictures"));
    Path image = target.resolve("holiday.jpg");
    Files.writeString(image, "image");
    var directories = FileTidyingAssistant.existingDirectories(root, target);

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/responses", exchange -> {
      boolean unauthorized = "Bearer bad".equals(exchange.getRequestHeaders().getFirst("Authorization"));
      int status = unauthorized ? 401 : 200;
      String answer = EfficientPlanningTest.answer("Downloads/holiday.jpg", "Pictures", false);
      String response = unauthorized ? "{}" : "data: {\"type\":\"response.output_text.done\",\"text\":\""
          + FileTidyingAssistant.escape(answer) + "\"}\n\n";
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    });
    server.start();
    try {
      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
      var aiConfig = new FileTidyingAssistant.Config(root, target, 10_000, baseUrl, "good", "gpt-6-luna", "medium", "responses");
      var aiResult = FileTidyingAssistant.plan(image, aiConfig, root, directories);
      assertEquals("READY", aiResult.status());
      assertEquals(root.resolve("Pictures/holiday.jpg"), aiResult.target());

      var badConfig = new FileTidyingAssistant.Config(root, target, 10_000, baseUrl, "bad", "gpt-6-luna", "medium", "responses");
      var badPlan = FileTidyingAssistant.plan(image, badConfig, root, directories);
      assertEquals("FAILED", badPlan.status());
      assertTrue(badPlan.reason().contains("401"));
    } finally {
      server.stop(0);
    }
  }
}
