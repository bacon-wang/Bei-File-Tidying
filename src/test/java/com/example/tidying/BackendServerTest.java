package com.example.tidying;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BackendServerTest {
  @Test void healthEndpointWorksWithoutDatabase() throws Exception {
    try (RunningServer server = runningUpstream()) {
      HttpServer backend = AiBackendServer.start(new AiBackendServer.Settings("127.0.0.1", 0,
          server.url(), "secret"));
      try {
        HttpResponse<String> response = request(backend, "GET", "/health", "");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("ok"));
      } finally { backend.stop(0); }
    }
  }

  @Test void clientCanPlanThroughBackendWithoutAnApiKey() throws Exception {
    Path root = Files.createTempDirectory("backend-client-root");
    Path inbox = Files.createDirectories(root.resolve("Inbox"));
    Path documents = Files.createDirectories(root.resolve("Documents"));
    Files.writeString(inbox.resolve("notes.md"), "notes");
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/responses", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      String answer = "[{\"source\":\"Inbox/notes.md\",\"destination\":\"Documents\",\"createDirectory\":false,\"needsContent\":false,\"reason\":\"notes\"}]";
      byte[] body = ("{\"output_text\":\"" + FileTidyingAssistant.escape(answer) + "\"}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
    });
    upstream.start();
    HttpServer backend = AiBackendServer.start(new AiBackendServer.Settings("127.0.0.1", 0,
        "http://127.0.0.1:" + upstream.getAddress().getPort(), "secret"));
    try {
      var config = new FileTidyingAssistant.Config(root, inbox, 10_000,
          "http://127.0.0.1:" + backend.getAddress().getPort(), "", "gpt-6-luna", "medium", "responses",
          3, 10, 1, true, 5, 12000, true);
      var plan = FileTidyingAssistant.plan(inbox.resolve("notes.md"), config, root,
          FileTidyingAssistant.existingDirectories(root, inbox));
      assertEquals("READY", plan.status());
      assertEquals(documents.resolve("notes.md"), plan.target());
      assertEquals("Bearer secret", authorization.get());
    } finally { backend.stop(0); upstream.stop(0); }
  }

  @Test void backendAddsServerKeyAndRelaysUpstreamResponse() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/responses", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] body = "{\"output_text\":\"[]\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
    });
    upstream.start();
    HttpServer backend = AiBackendServer.start(new AiBackendServer.Settings("127.0.0.1", 0,
        "http://127.0.0.1:" + upstream.getAddress().getPort(), "secret"));
    try {
      HttpResponse<String> response = request(backend, "POST", "/responses", "{\"x\":1}");
      assertEquals(200, response.statusCode());
      assertEquals("Bearer secret", authorization.get());
      assertTrue(response.body().contains("output_text"));
    } finally { backend.stop(0); upstream.stop(0); }
  }

  private static HttpResponse<String> request(HttpServer server, String method, String path, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path));
    if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.ofString(body)); else builder.GET();
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static RunningServer runningUpstream() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/responses", exchange -> exchange.sendResponseHeaders(404, -1));
    server.start();
    return new RunningServer(server);
  }

  private record RunningServer(HttpServer server) implements AutoCloseable {
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public void close() { server.stop(0); }
  }
}
