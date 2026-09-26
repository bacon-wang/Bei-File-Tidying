package com.example.tidying;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CacheSafetyTest {
  @TempDir Path sandbox;

  @Test
  void linkedCacheDirectoryIsNeitherReadNorWritten() throws Exception {
    Path root = Files.createDirectory(sandbox.resolve("sense"));
    Path outside = Files.createDirectory(sandbox.resolve("outside"));
    Path inbox = Files.createDirectory(root.resolve("Inbox"));
    Path documents = Files.createDirectory(root.resolve("Documents"));
    Path archives = Files.createDirectory(root.resolve("Archives"));
    Path source = inbox.resolve("notes.md");
    Files.writeString(source, "notes");
    var config = new FileTidyingAssistant.Config(root, inbox, 10_000,
        "https://example.invalid", "key", "model", "medium", "responses");
    List<Path> directories = List.of(inbox, documents, archives);

    var initial = new PlanningCache(root, config, directories, false);
    initial.put(source, "", config,
        new FileTidyingAssistant.Classification("Documents", false, "first"),
        new FileTidyingAssistant.Plan(source, documents.resolve("notes.md"), "first", "READY"));
    initial.save();
    Path cacheDirectory = root.resolve(".bei-file-tidying");
    Path externalDirectory = outside.resolve("cache");
    Files.move(cacheDirectory, externalDirectory);
    Path externalFile = externalDirectory.resolve("classifications.properties");
    byte[] original = Files.readAllBytes(externalFile);
    Files.createSymbolicLink(cacheDirectory, externalDirectory);

    var linked = new PlanningCache(root, config, directories, false);
    assertNull(linked.get(source, "", config), "A cache through a linked parent must not be read");
    linked.put(source, "", config,
        new FileTidyingAssistant.Classification("Archives", false, "second"),
        new FileTidyingAssistant.Plan(source, archives.resolve("notes.md"), "second", "READY"));
    linked.save();
    assertArrayEquals(original, Files.readAllBytes(externalFile), "A linked parent must not be written");
    try (var files = Files.list(externalDirectory)) {
      assertEquals(List.of(externalFile), files.toList());
    }
  }
}
