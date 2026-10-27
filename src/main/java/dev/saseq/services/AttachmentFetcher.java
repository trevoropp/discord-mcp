package dev.saseq.services;

import net.dv8tion.jda.api.utils.FileUpload;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads files from URLs so the send tools can attach them to Discord messages. Discord
 * accepts up to 10 attachments per message; 8 MB each is safe for every server (the limit for
 * a non-boosted server is 10 MB). Discord CDN links, Google Drive share links and any public
 * HTTP(S) URL work; a file that only exists inside an agent's workspace has to be put somewhere
 * with a URL first.
 */
@Component
public class AttachmentFetcher {

    private static final int MAX_FILES = 10;
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Parses a comma- or newline-separated list of URLs and downloads each into a FileUpload. */
    public List<FileUpload> fetch(String urls) {
        List<FileUpload> uploads = new ArrayList<>();
        if (urls == null || urls.isBlank()) {
            return uploads;
        }
        String[] parts = urls.split("[,\\n]");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (!p.isBlank()) {
                list.add(p.trim());
            }
        }
        if (list.size() > MAX_FILES) {
            throw new IllegalArgumentException("At most " + MAX_FILES + " attachments per message (got " + list.size() + ")");
        }
        for (String url : list) {
            uploads.add(download(url));
        }
        return uploads;
    }

    private FileUpload download(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Not a valid URL: " + url);
        }
        if (uri.getScheme() == null || !(uri.getScheme().equals("https") || uri.getScheme().equals("http"))) {
            throw new IllegalArgumentException("Attachment URLs must be http(s): " + url);
        }
        HttpRequest request = HttpRequest.newBuilder(normalizeDriveLink(uri))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not download " + url + ": " + ex.getMessage());
        }
        if (response.statusCode() >= 300) {
            throw new IllegalArgumentException("Could not download " + url + ": HTTP " + response.statusCode());
        }
        byte[] data = response.body();
        if (data.length == 0) {
            throw new IllegalArgumentException("Downloaded file is empty: " + url);
        }
        if (data.length > MAX_BYTES) {
            throw new IllegalArgumentException("Attachment is " + (data.length / (1024 * 1024)) + " MB; the limit is 8 MB: " + url);
        }
        return FileUpload.fromData(data, fileName(uri, response));
    }

    /** Turns a Google Drive share link into its direct-download form; other URLs pass through. */
    private static URI normalizeDriveLink(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (host.endsWith("drive.google.com") && path.startsWith("/file/d/")) {
            String id = path.substring("/file/d/".length()).split("/")[0];
            return URI.create("https://drive.google.com/uc?export=download&id=" + id);
        }
        return uri;
    }

    private static String fileName(URI uri, HttpResponse<byte[]> response) {
        String disposition = response.headers().firstValue("content-disposition").orElse("");
        int i = disposition.toLowerCase().indexOf("filename=");
        if (i >= 0) {
            String name = disposition.substring(i + "filename=".length()).trim();
            if (name.startsWith("\"")) {
                int end = name.indexOf('"', 1);
                name = end > 0 ? name.substring(1, end) : name.substring(1);
            } else {
                name = name.split(";")[0].trim();
            }
            if (!name.isBlank()) {
                return name;
            }
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (!last.isBlank() && last.contains(".")) {
            return last;
        }
        String type = response.headers().firstValue("content-type").orElse("").toLowerCase();
        String ext = type.startsWith("image/png") ? ".png" : type.startsWith("image/jpeg") ? ".jpg"
                : type.startsWith("image/gif") ? ".gif" : type.startsWith("application/pdf") ? ".pdf"
                : type.startsWith("text/") ? ".txt" : "";
        return (last.isBlank() ? "attachment" : last) + ext;
    }
}
