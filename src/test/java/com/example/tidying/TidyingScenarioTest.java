package com.example.tidying;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TidyingScenarioTest {
  @Test
  void smallMessyFolderIsPlannedAndTidied() throws Exception {
    Path root = Files.createTempDirectory("bei-file-tidying-scenario-");
    Path target = Files.createDirectories(root.resolve("Inbox"));
    Files.createDirectories(root.resolve("Pictures"));
    Files.createDirectories(root.resolve("Documents"));
    Files.createDirectories(root.resolve("Work"));
    Files.createDirectories(root.resolve("Archive"));
    Path temp = Files.createDirectories(target.resolve("Temp"));
    Path unsorted = Files.createDirectories(target.resolve("Unsorted"));

    Path photo = target.resolve("trip-photo.png");
    Path meeting = target.resolve("meeting-notes.md");
    Path invoice = temp.resolve("invoice.pdf");
    Path backup = unsorted.resolve("project-backup.zip");
    Path hidden = target.resolve(".DS_Store");
    Files.writeString(photo, "image");
    Files.writeString(meeting, "meeting notes");
    Files.writeString(invoice, "pdf");
    Files.writeString(backup, "zip");
    Files.writeString(hidden, "metadata");

    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/responses", exchange -> {
      exchange.getRequestBody().readAllBytes();
      requests.incrementAndGet();
      String answer = "["
          + "{\"source\":\"Inbox/Temp/invoice.pdf\",\"destination\":\"Documents\",\"createDirectory\":false,\"reason\":\"invoice\"},"
          + "{\"source\":\"Inbox/Unsorted/project-backup.zip\",\"destination\":\"Archive\",\"createDirectory\":false,\"reason\":\"archive\"},"
          + "{\"source\":\"Inbox/meeting-notes.md\",\"destination\":\"Work\",\"createDirectory\":false,\"reason\":\"notes\"},"
          + "{\"source\":\"Inbox/trip-photo.png\",\"destination\":\"Pictures\",\"createDirectory\":false,\"reason\":\"photo\"}"
          + "]";
      String escaped = answer.replace("\\", "\\\\").replace("\"", "\\\"");
      String response = "data: {\"type\":\"response.output_text.done\",\"text\":\"" + escaped + "\"}\n\ndata: {\"type\":\"response.completed\"}\n\n";
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    });
    server.start();

    try {
      var config = new FileTidyingAssistant.Config(root, target, 10_000,
          "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "gpt-6-luna", "medium", "responses");
      var directories = FileTidyingAssistant.existingDirectories(root, target);
      List<Path> files = FileTidyingAssistant.targetFiles(target);
      assertEquals(4, files.size(), "隐藏文件不应进入整理计划");

      List<FileTidyingAssistant.Plan> plans = FileTidyingAssistant.planAll(files, config, root, directories);
      assertEquals(1, requests.get(), "同一批文件应只发送一次 AI 请求");
      assertEquals(4, plans.stream().filter(p -> "READY".equals(p.status())).count());
      assertTrue(plans.stream().anyMatch(p -> p.target().equals(root.resolve("Pictures/trip-photo.png"))));
      assertTrue(plans.stream().anyMatch(p -> p.target().equals(root.resolve("Work/meeting-notes.md"))));
      assertTrue(plans.stream().anyMatch(p -> p.target().equals(root.resolve("Documents/invoice.pdf"))));
      assertTrue(plans.stream().anyMatch(p -> p.target().equals(root.resolve("Archive/project-backup.zip"))));

      FileTidyingAssistant.execute(plans, root, target, true);
      assertTrue(Files.exists(root.resolve("Pictures/trip-photo.png")));
      assertTrue(Files.exists(root.resolve("Work/meeting-notes.md")));
      assertTrue(Files.exists(root.resolve("Documents/invoice.pdf")));
      assertTrue(Files.exists(root.resolve("Archive/project-backup.zip")));
      assertTrue(Files.exists(hidden));
      assertFalse(Files.exists(temp));
      assertFalse(Files.exists(unsorted));
      assertTrue(Files.exists(FileTidyingAssistant.historyFile(root)));

      FileTidyingAssistant.undoLast(root);
      assertTrue(Files.exists(photo));
      assertTrue(Files.exists(meeting));
      assertTrue(Files.exists(invoice));
      assertTrue(Files.exists(backup));
      assertTrue(Files.isDirectory(temp));
      assertTrue(Files.isDirectory(unsorted));
      assertFalse(Files.exists(root.resolve("Pictures/trip-photo.png")));
      assertTrue(FileTidyingAssistant.readHistory(FileTidyingAssistant.historyFile(root)).undone());
    } finally {
      server.stop(0);
    }
  }
}
