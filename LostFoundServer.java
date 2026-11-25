import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Campus Lost & Found - backend.
 * Uses only the JDK (Java 11 or newer). Data is saved in the "data" folder.
 *
 * Run:  javac LostFoundServer.java
 *       java LostFoundServer
 * Open: http://localhost:8080
 */
public class LostFoundServer {

    // Owner password. Change it, or set an environment variable named ADMIN_KEY.
    static final String ADMIN_KEY = System.getenv().getOrDefault("ADMIN_KEY", "admin123");
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    static final Path DATA = Paths.get("data");
    static final Path UPLOADS = DATA.resolve("uploads");

    // Three collections, each a list of records (a record is a map of text fields).
    static final Map<String, List<Map<String, String>>> db = new HashMap<>();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(UPLOADS);
        for (String name : new String[]{"items", "claims", "suggestions"}) {
            db.put(name, load(name));
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", LostFoundServer::handle);
        server.start();
        System.out.println("Campus Lost & Found is running.");
        System.out.println("Open http://localhost:" + PORT + " in your browser.");
        System.out.println("Owner password: " + ADMIN_KEY);
    }

    // ---------- Routing ----------

    static void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            String method = x.getRequestMethod();

            if (path.equals("/") && method.equals("GET")) {
                send(x, 200, "text/html; charset=utf-8", Files.readAllBytes(Paths.get("public", "index.html")));
                return;
            }
            if (path.startsWith("/uploads/") && method.equals("GET")) {
                String name = path.substring("/uploads/".length());
                if (!name.matches("[A-Za-z0-9-]+\\.jpg") || !Files.exists(UPLOADS.resolve(name))) {
                    send(x, 404, "text/plain", "Not found".getBytes(UTF_8));
                    return;
                }
                send(x, 200, "image/jpeg", Files.readAllBytes(UPLOADS.resolve(name)));
                return;
            }
            String[] seg = path.split("/"); // ["", "api", collection, id, action]
            if (seg.length >= 3 && seg[1].equals("api") && db.containsKey(seg[2])) {
                api(x, method, seg);
                return;
            }
            send(x, 404, "text/plain", "Not found".getBytes(UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
            json(x, 500, "{\"error\":\"Server error\"}");
        }
    }

    static void api(HttpExchange x, String method, String[] seg) throws IOException {
        String coll = seg[2];
        boolean admin = ADMIN_KEY.equals(x.getRequestHeaders().getFirst("X-Admin-Key"));
        Map<String, String> query = parseForm(x.getRequestURI().getRawQuery());
        String by = query.getOrDefault("by", "");

        synchronized (db) {
            List<Map<String, String>> list = db.get(coll);

            // List records
            if (seg.length == 3 && method.equals("GET")) {
                if (coll.equals("suggestions") && !admin) {
                    json(x, 403, "{\"error\":\"Only the owner can read suggestions\"}");
                    return;
                }
                StringBuilder sb = new StringBuilder("[");
                for (int i = list.size() - 1; i >= 0; i--) {
                    Map<String, String> r = list.get(i);
                    Map<String, String> view = new LinkedHashMap<>(r);
                    boolean show = admin || by.equals(r.get("createdBy"));
                    if (coll.equals("claims")) {
                        Map<String, String> item = find(db.get("items"), r.get("itemId"));
                        show = show || (item != null && by.equals(item.get("createdBy")));
                    }
                    if (!show) { // hide private contact details from other people
                        view.remove("contact");
                        view.remove("message");
                    }
                    sb.append(toJson(view));
                    if (i > 0) sb.append(",");
                }
                json(x, 200, sb.append("]").toString());
                return;
            }

            // Add a record
            if (seg.length == 3 && method.equals("POST")) {
                Map<String, String> r = parseForm(new String(x.getRequestBody().readAllBytes(), UTF_8));
                r.put("id", UUID.randomUUID().toString());
                r.put("createdAt", String.valueOf(System.currentTimeMillis()));
                if (coll.equals("items")) {
                    r.put("status", "open");
                    savePhoto(r);
                }
                list.add(r);
                save(coll);
                json(x, 200, toJson(r));
                return;
            }

            // Change or delete one record
            if (seg.length >= 4) {
                Map<String, String> item = find(list, seg[3]);
                if (item == null) {
                    json(x, 404, "{\"error\":\"Not found\"}");
                    return;
                }
                boolean allowed = admin || by.equals(item.get("createdBy"));
                if (!allowed) {
                    json(x, 403, "{\"error\":\"Only the person who posted this can change it\"}");
                    return;
                }
                if (method.equals("DELETE")) {
                    list.remove(item);
                    if (coll.equals("items")) {
                        db.get("claims").removeIf(c -> seg[3].equals(c.get("itemId")));
                        save("claims");
                        if (item.get("photo") != null) Files.deleteIfExists(UPLOADS.resolve(item.get("photo")));
                    }
                    save(coll);
                    json(x, 200, "{\"ok\":true}");
                    return;
                }
                if (method.equals("POST") && seg.length == 5 && seg[4].equals("return")) {
                    Map<String, String> f = parseForm(new String(x.getRequestBody().readAllBytes(), UTF_8));
                    item.put("status", "returned");
                    item.put("returnedTo", f.getOrDefault("to", ""));
                    item.put("returnedId", f.getOrDefault("pid", ""));
                    item.put("returnedAt", new java.sql.Date(System.currentTimeMillis()).toString());
                    save(coll);
                    json(x, 200, toJson(item));
                    return;
                }
            }
            json(x, 400, "{\"error\":\"Bad request\"}");
        }
    }

    // ---------- Storage ----------

    static List<Map<String, String>> load(String name) throws IOException {
        List<Map<String, String>> list = new ArrayList<>();
        Path file = DATA.resolve(name + ".db");
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, UTF_8)) {
                if (!line.isBlank()) list.add(parseForm(line));
            }
        }
        return list;
    }

    static void save(String name) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Map<String, String> r : db.get(name)) lines.add(encode(r));
        Files.write(DATA.resolve(name + ".db"), lines, UTF_8);
    }

    // The photo arrives as a data URL; save it as a .jpg file.
    static void savePhoto(Map<String, String> r) throws IOException {
        String photo = r.remove("photo");
        if (photo != null && photo.startsWith("data:image") && photo.contains(",")) {
            byte[] bytes = Base64.getDecoder().decode(photo.substring(photo.indexOf(',') + 1));
            if (bytes.length < 3_000_000) {
                String file = r.get("id") + ".jpg";
                Files.write(UPLOADS.resolve(file), bytes);
                r.put("photo", file);
            }
        }
    }

    // ---------- Helpers ----------

    static Map<String, String> find(List<Map<String, String>> list, String id) {
        for (Map<String, String> r : list) if (r.get("id").equals(id)) return r;
        return null;
    }

    static Map<String, String> parseForm(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, i), UTF_8), URLDecoder.decode(pair.substring(i + 1), UTF_8));
        }
        return m;
    }

    static String encode(Map<String, String> r) {
        StringJoiner j = new StringJoiner("&");
        for (Map.Entry<String, String> e : r.entrySet()) {
            j.add(URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8));
        }
        return j.toString();
    }

    static String toJson(Map<String, String> m) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) b.append(",");
            first = false;
            b.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
        }
        return b.append("}").toString();
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c < 32 ? ' ' : c);
            }
        }
        return b.toString();
    }

    static void json(HttpExchange x, int code, String body) throws IOException {
        send(x, code, "application/json; charset=utf-8", body.getBytes(UTF_8));
    }

    static void send(HttpExchange x, int code, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(code, body.length);
        x.getResponseBody().write(body);
        x.close();
    }
}
