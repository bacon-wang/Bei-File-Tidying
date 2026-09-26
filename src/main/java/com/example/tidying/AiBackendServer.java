package com.example.tidying;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/** Stateless local API service that keeps the upstream model key off the client. */
public final class AiBackendServer {
  private static final int MAX_REQUEST_BYTES = 1_000_000;
  private AiBackendServer() {}

  record Settings(String host, int port, String upstreamBaseUrl, String apiKey) {
    static Settings load() throws IOException {
      Properties server = readProperties(Path.of("server.properties"));
      String key = System.getenv("BEI_AI_API_KEY");
      if (key == null || key.isBlank()) key = server.getProperty("ai.api-key", "").trim();
      String baseUrl = server.getProperty("ai.base-url", "https://www.fhl.mom").trim().replaceAll("/+$", "");
      int port;
      try { port = Integer.parseInt(server.getProperty("server.port", "8787").trim()); }
      catch (NumberFormatException e) { throw new IOException("server.port 必须是端口号", e); }
      return new Settings(server.getProperty("server.host", "127.0.0.1").trim(), port, baseUrl, key);
    }
  }

  private static Properties readProperties(Path path) throws IOException {
    Properties properties = new Properties();
    if (Files.isRegularFile(path)) {
      try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(reader); }
    }
    return properties;
  }

  public static void main(String[] args) throws Exception {
    HttpServer server = start(Settings.load());
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    System.out.println("Bei File Tidying AI 服务已启动: http://" + server.getAddress().getHostString()
        + ":" + server.getAddress().getPort());
  }

  static HttpServer start(Settings settings) throws IOException {
    if (settings.apiKey() == null || settings.apiKey().isBlank()) {
      throw new IOException("后端缺少 AI API Key：设置 BEI_AI_API_KEY 或 server.properties 中的 ai.api-key");
    }
    URI upstream;
    try { upstream = URI.create(settings.upstreamBaseUrl()); }
    catch (IllegalArgumentException e) { throw new IOException("ai.base-url 无效", e); }
    if (!"http".equalsIgnoreCase(upstream.getScheme())
        && !"https".equalsIgnoreCase(upstream.getScheme())) throw new IOException("ai.base-url 必须是 HTTP 地址");
    HttpServer server = HttpServer.create(new InetSocketAddress(settings.host(), settings.port()), 0);
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    server.createContext("/health", exchange -> {
      if (!"GET".equals(exchange.getRequestMethod())) { send(exchange, 405, "application/json", "{}".getBytes(StandardCharsets.UTF_8)); return; }
      send(exchange, 200, "application/json", "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
    });
    server.createContext("/responses", exchange -> forward(exchange, client, settings, "responses"));
    server.createContext("/chat/completions", exchange -> forward(exchange, client, settings, "chat/completions"));
    server.start();
    return server;
  }

  private static void forward(HttpExchange exchange, HttpClient client, Settings settings, String path) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      send(exchange, 405, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
      return;
    }
    byte[] body;
    try (InputStream input = exchange.getRequestBody()) { body = input.readNBytes(MAX_REQUEST_BYTES + 1); }
    if (body.length > MAX_REQUEST_BYTES) {
      send(exchange, 413, "application/json", "{\"error\":\"request too large\"}".getBytes(StandardCharsets.UTF_8));
      return;
    }
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(settings.upstreamBaseUrl() + "/" + path))
          .timeout(Duration.ofSeconds(90))
          .header("Authorization", "Bearer " + settings.apiKey())
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
      send(exchange, response.statusCode(), contentType, response.body());
      System.out.println("AI 转发完成: " + path + " HTTP " + response.statusCode());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      send(exchange, 502, "application/json", "{\"error\":\"upstream interrupted\"}".getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      send(exchange, 502, "application/json", "{\"error\":\"upstream unavailable\"}".getBytes(StandardCharsets.UTF_8));
      System.err.println("AI 转发失败: " + e.getClass().getSimpleName());
    }
  }

  private static void send(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(code, body.length);
    try (var output = exchange.getResponseBody()) { output.write(body); }
  }
}
