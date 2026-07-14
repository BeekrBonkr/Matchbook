package com.slg.matchbook.io;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Uploads text to any Hastebin-compatible server.
 *
 * POST /documents with raw text body → JSON response {"key":"abc123"}
 * View URL: {server}/{key}
 *
 * No API key required.
 */
public final class HasteUploader {

    private final String serverUrl;
    private final HttpClient client;

    public HasteUploader(String serverUrl) {
        this.serverUrl = validate(serverUrl == null || serverUrl.isBlank()
                ? "https://hastebin.com"
                : serverUrl.stripTrailing().replaceAll("/+$", ""));
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Only http(s) URLs are ever intended here; reject anything else (e.g. file:, jar:) up front. */
    private static String validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid export_upload.server URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("export_upload.server must be an http(s) URL, got: " + url);
        }
        return url;
    }

    /**
     * @param content text to upload
     * @return public URL of the paste
     */
    public String upload(String content) throws IOException, InterruptedException {
        if (content == null || content.isEmpty()) {
            throw new IOException("Nothing to upload (content was empty)");
        }

        URI endpoint = URI.create(serverUrl + "/documents");

        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Hastebin returned HTTP " + resp.statusCode());
        }

        String body = resp.body() == null ? "" : resp.body().trim();
        try {
            String key = JsonParser.parseString(body).getAsJsonObject().get("key").getAsString();
            return serverUrl + "/" + key;
        } catch (Exception e) {
            throw new IOException("Unexpected response from hastebin server: " + body);
        }
    }
}
