package com.slg.matchbook.io;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal Pastebin uploader.
 *
 * Uses the official Pastebin API endpoint:
 *   https://pastebin.com/api/api_post.php
 *
 * API docs: https://pastebin.com/doc_api
 */
public final class PastebinUploader {

    private static final URI ENDPOINT = URI.create("https://pastebin.com/api/api_post.php");

    private final HttpClient client;
    private final String apiDevKey;
    private final String apiUserKey; // optional

    public PastebinUploader(String apiDevKey, String apiUserKey) {
        this.apiDevKey = apiDevKey;
        this.apiUserKey = apiUserKey;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param content CSV text
     * @param title paste title
     * @param unlisted true -> api_paste_private=1
     * @param expire e.g. "1H", "1D", "1W", "N" (never)
     * @return URL to paste
     */
    public String upload(String content, String title, boolean unlisted, String expire) throws IOException, InterruptedException {
        if (apiDevKey == null || apiDevKey.isBlank()) {
            throw new IOException("Pastebin api_dev_key is not configured");
        }
        if (content == null || content.isEmpty()) {
            throw new IOException("Nothing to upload (CSV was empty)");
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("api_dev_key", apiDevKey);
        params.put("api_option", "paste");
        params.put("api_paste_code", content);

        if (title != null && !title.isBlank()) {
            params.put("api_paste_name", title);
        }
        // 0=public, 1=unlisted, 2=private (private requires user key)
        params.put("api_paste_private", unlisted ? "1" : "0");

        if (expire != null && !expire.isBlank()) {
            params.put("api_paste_expire_date", expire);
        }

        // If a valid user key is provided, paste will be created under that account.
        if (apiUserKey != null && !apiUserKey.isBlank()) {
            params.put("api_user_key", apiUserKey);
        }

        String body = formEncode(params);

        HttpRequest req = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String text = resp.body() == null ? "" : resp.body().trim();

        // Pastebin returns the URL on success, otherwise a "Bad API request" string.
        if (text.startsWith("Bad API request")) {
            throw new IOException(text);
        }
        if (!text.startsWith("http")) {
            throw new IOException("Unexpected Pastebin response: " + text);
        }

        return text;
    }

    private static String formEncode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
