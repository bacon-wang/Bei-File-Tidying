package com.example.tidying;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistorySafetyTest {
  @TempDir Path sandbox;

  @Test void linkedMetadataDirectoryCannotReceiveHistory() throws Exception {
    Path root = Files.createDirectory(sandbox.resolve("sense"));
    Path inbox = Files.createDirectory(root.resolve("Inbox"));
    Path documents = Files.createDirectory(root.resolve("Documents"));
    Path outside = Files.createDirectory(sandbox.resolve("outside"));
    Path source = Files.writeString(inbox.resolve("notes.md"), "notes");
    Files.createSymbolicLink(root.resolve(".bei-file-tidying"), outside);

    FileTidyingAssistant.execute(List.of(new FileTidyingAssistant.Plan(source,
        documents.resolve("notes.md"), "test", "READY")), root, inbox, false);

    assertTrue(Files.exists(source));
    assertFalse(Files.exists(documents.resolve("notes.md")));
    assertFalse(Files.exists(outside.resolve("history")));
  }

  @Test void linkedSourceParentCannotReceiveUndoneFile() throws Exception {
    Path root = Files.createDirectory(sandbox.resolve("sense"));
    Path inbox = Files.createDirectory(root.resolve("Inbox"));
    Path temp = Files.createDirectory(inbox.resolve("Temp"));
    Path documents = Files.createDirectory(root.resolve("Documents"));
    Path source = Files.writeString(temp.resolve("notes.md"), "notes");
    Path destination = documents.resolve("notes.md");
    FileTidyingAssistant.execute(List.of(new FileTidyingAssistant.Plan(source, destination, "test", "READY")),
        root, inbox, true);
    assertFalse(Files.exists(temp));
    Path outside = Files.createDirectory(sandbox.resolve("outside"));
    Files.createSymbolicLink(temp, outside);

    FileTidyingAssistant.undoLast(root);

    assertTrue(Files.exists(destination));
    assertFalse(Files.exists(outside.resolve("notes.md")));
    Files.delete(temp);
    FileTidyingAssistant.undoLast(root);
    assertTrue(Files.exists(source));
  }

  @Test void linkedHistoryDirectoryCannotBeReadForUndo() throws Exception {
    Path root = Files.createDirectory(sandbox.resolve("sense"));
    Path inbox = Files.createDirectory(root.resolve("Inbox"));
    Path documents = Files.createDirectory(root.resolve("Documents"));
    Path source = Files.writeString(inbox.resolve("notes.md"), "notes");
    Path destination = documents.resolve("notes.md");
    FileTidyingAssistant.execute(List.of(new FileTidyingAssistant.Plan(source, destination, "test", "READY")),
        root, inbox, false);
    Path historyDirectory = root.resolve(".bei-file-tidying/history");
    Path outside = Files.createDirectory(sandbox.resolve("outside"));
    Path externalHistory = outside.resolve("history");
    Files.move(historyDirectory, externalHistory);
    byte[] original = Files.readAllBytes(externalHistory.resolve("last-operation.json"));
    Files.createSymbolicLink(historyDirectory, externalHistory);

    FileTidyingAssistant.undoLast(root);

    assertTrue(Files.exists(destination));
    assertFalse(Files.exists(source));
    assertArrayEquals(original, Files.readAllBytes(externalHistory.resolve("last-operation.json")));
  }

  @Test void linkedSourceParentCannotSupplyFileForApply() throws Exception {
    Path root = Files.createDirectory(sandbox.resolve("sense"));
    Path inbox = Files.createDirectory(root.resolve("Inbox"));
    Path temp = Files.createDirectory(inbox.resolve("Temp"));
    Path documents = Files.createDirectory(root.resolve("Documents"));
    Path source = Files.writeString(temp.resolve("notes.md"), "original");
    Path destination = documents.resolve("notes.md");
    Path outside = Files.createDirectory(sandbox.resolve("outside"));
    Files.delete(source);
    Files.delete(temp);
    Files.writeString(outside.resolve("notes.md"), "external");
    Files.createSymbolicLink(temp, outside);

    FileTidyingAssistant.execute(List.of(new FileTidyingAssistant.Plan(source, destination, "test", "READY")),
        root, inbox, false);

    assertEquals("external", Files.readString(outside.resolve("notes.md")));
    assertFalse(Files.exists(destination));
  }
}
