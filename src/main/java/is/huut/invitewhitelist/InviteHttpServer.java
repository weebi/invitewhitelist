package is.huut.invitewhitelist;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal, dependency-free HTTP server (built on the JDK's own
 * com.sun.net.httpserver) that serves the "/join/{code}" page and handles
 * the whitelist redemption itself.
 *
 * This deliberately does not do TLS. Put it behind a reverse proxy
 * (nginx/Caddy/Cloudflare Tunnel/etc.) if you want HTTPS, and point
 * InviteConfig.publicBaseUrl at that proxy's public address.
 */
public class InviteHttpServer {
    private static final Logger LOGGER = InviteWhitelistMod.LOGGER;
    private static final long RATE_LIMIT_MILLIS = 2000; // per-IP cooldown on POST /join/*
    private static final String TURNTSTILE_VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private static final String TURNTSTILE_SCRIPT_URL = "https://challenges.cloudflare.com/turnstile/v0/api.js";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    private final MinecraftServer minecraftServer;
    private final InviteManager inviteManager;
    private final InviteConfig config;
    private final Map<String, Long> lastRequestByIp = new ConcurrentHashMap<>();

    private HttpServer httpServer;
    private ExecutorService executor;

    public InviteHttpServer(MinecraftServer minecraftServer, InviteManager inviteManager, InviteConfig config) {
        this.minecraftServer = minecraftServer;
        this.inviteManager = inviteManager;
        this.config = config;
    }

    public void start() throws IOException {
        Files.createDirectories(webAssetsDir());
        httpServer = HttpServer.create(new InetSocketAddress(config.bindAddress, config.httpPort), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        httpServer.setExecutor(executor);
        httpServer.createContext("/join/", this::handleJoin);
        httpServer.createContext("/assets/", this::handleAssets);
        httpServer.createContext("/", exchange ->
                respond(exchange, 200, "text/plain; charset=utf-8", "Invite Whitelist server is running."));
        httpServer.start();
    }

    /** Local folder admins can drop a background image / custom.css / favicon into. */
    private static Path webAssetsDir() {
        return InviteConfig.configDir().resolve("web");
    }

    private void handleAssets(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
            return;
        }

        Path base = webAssetsDir().normalize();
        String prefix = "/assets/";
        String path = exchange.getRequestURI().getPath();
        String rawName = path.length() > prefix.length() ? path.substring(prefix.length()) : "";
        String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);

        Path target = base.resolve(name).normalize();
        if (name.isEmpty() || !target.startsWith(base) || !Files.isRegularFile(target)) {
            respond(exchange, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }

        byte[] bytes = Files.readAllBytes(target);
        exchange.getResponseHeaders().set("Content-Type", contentTypeFor(target));
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String contentTypeFor(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        return "application/octet-stream";
    }

    public void stop() {
        if (httpServer != null) httpServer.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private boolean isTurnstileEnabled() {
        return config != null && config.isTurnstileEnabled();
    }

    private boolean verifyTurnstile(String remoteAddress, String responseToken) throws IOException, InterruptedException {
        String requestBody = "secret=" + URLEncoder.encode(config.cloudflareTurnstileSecretKey, StandardCharsets.UTF_8)
                + "&response=" + URLEncoder.encode(responseToken, StandardCharsets.UTF_8);
        if (remoteAddress != null && !remoteAddress.isBlank() && !"unknown".equals(remoteAddress)) {
            requestBody += "&remoteip=" + URLEncoder.encode(remoteAddress, StandardCharsets.UTF_8);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TURNTSTILE_VERIFY_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            LOGGER.warn("InviteWhitelist: Turnstile verification failed with HTTP {} and body {}",
                    response.statusCode(), response.body());
            return false;
        }

        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        return body.has("success") && body.get("success").getAsBoolean();
    }

    private void handleJoin(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath(); // /join/CODE
            String prefix = "/join/";
            String rawCode = path.length() > prefix.length() ? path.substring(prefix.length()) : "";
            String code = InviteManager.normalize(URLDecoder.decode(rawCode, StandardCharsets.UTF_8));

            switch (exchange.getRequestMethod()) {
                case "GET" -> handleGet(exchange, code);
                case "POST" -> handlePost(exchange, code);
                default -> respond(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
            }
        } catch (Exception e) {
            LOGGER.warn("InviteWhitelist: Error handling invite request", e);
            try {
                respond(exchange, 500, "text/plain; charset=utf-8", "Internal server error");
            } catch (IOException ignored) {
            }
        }
    }

    private void handleGet(HttpExchange exchange, String code) throws IOException {
        if (code == null || code.isEmpty()) {
            respond(exchange, 404, "text/html; charset=utf-8",
                    page("Invite not found", "<p>Missing invite code.</p>"));
            return;
        }

        Invite invite = inviteManager.get(code);
        if (invite == null) {
            respond(exchange, 404, "text/html; charset=utf-8", page("Invite not found",
                    "<p>This invite code doesn't exist. Double check the link you were given.</p>"));
            return;
        }
        if (!invite.isUsable()) {
            respond(exchange, 410, "text/html; charset=utf-8", page("Invite no longer valid",
                    "<p>" + statusMessage(invite) + "</p>"));
            return;
        }

        String turnstileScript = "";
        String turnstileWidget = "";
        if (isTurnstileEnabled()) {
            turnstileScript = "<script src=\"" + TURNTSTILE_SCRIPT_URL + "\" async defer></script>";
            turnstileWidget = "<div class=\"cf-turnstile\" data-sitekey=\"" + escape(config.cloudflareTurnstileSiteKey) + "\"></div>";
        }

        String body = ("""
                %s
                <form method="POST" action="/join/%s">
                  <label for="username">Minecraft username</label>
                  <input id="username" name="username" type="text" maxlength="16"
                         pattern="[A-Za-z0-9_]{2,16}" required autofocus autocomplete="off">
                  %s
                  <button type="submit">Join whitelist</button>
                </form>
                <p class="hint">This adds your Minecraft account's UUID to the server whitelist.
                Use your exact in-game username (the one you log in with).</p>
                """).formatted(turnstileScript, escape(invite.code), turnstileWidget);

        respond(exchange, 200, "text/html; charset=utf-8", page("Join the server", body));
    }

    private void handlePost(HttpExchange exchange, String code) throws IOException {
        String remote = exchange.getRemoteAddress() != null
                ? exchange.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
        if (isRateLimited(remote)) {
            respond(exchange, 429, "text/html; charset=utf-8", page("Slow down",
                    "<p>Too many attempts. Wait a couple of seconds and try again.</p>"));
            return;
        }

        if (code == null || code.isEmpty()) {
            respond(exchange, 404, "text/html; charset=utf-8",
                    page("Invite not found", "<p>Missing invite code.</p>"));
            return;
        }

        Map<String, String> form = parseForm(exchange);
        if (isTurnstileEnabled()) {
            String turnstileResponse = form.getOrDefault("cf-turnstile-response", "").trim();
            if (turnstileResponse.isEmpty()) {
                respond(exchange, 400, "text/html; charset=utf-8", page("Verification required",
                        "<p>Please complete the Turnstile verification before joining.</p>"));
                return;
            }
            try {
                if (!verifyTurnstile(remote, turnstileResponse)) {
                    respond(exchange, 400, "text/html; charset=utf-8", page("Verification failed",
                            "<p>The Turnstile verification did not succeed. Please try again.</p>"));
                    return;
                }
            } catch (Exception e) {
                LOGGER.warn("InviteWhitelist: Turnstile verification error", e);
                respond(exchange, 500, "text/html; charset=utf-8", page("Verification error",
                        "<p>Unable to verify the Turnstile response right now. Please try again later.</p>"));
                return;
            }
        }

        String username = form.getOrDefault("username", "").trim();
        if (username.isEmpty() || !username.matches("[A-Za-z0-9_]{2,16}")) {
            respond(exchange, 400, "text/html; charset=utf-8", page("Invalid username",
                    "<p>That doesn't look like a valid Minecraft username. Go back and try again.</p>"));
            return;
        }

        InviteManager.RedeemTicket ticket = inviteManager.reserve(code);
        if (ticket.result != InviteManager.RedeemResult.OK) {
            respond(exchange, 410, "text/html; charset=utf-8", page("Invite no longer valid",
                    "<p>" + redeemErrorMessage(ticket.result) + "</p>"));
            return;
        }

        Optional<GameProfile> profileOpt = minecraftServer.services().profileResolver().fetchByName(username);
        if (profileOpt.isEmpty()) {
            inviteManager.releaseReservation(code);
            respond(exchange, 404, "text/html; charset=utf-8", page("Account not found",
                    "<p>Couldn't find a Minecraft account named \"" + escape(username)
                            + "\". Check the spelling and try again.</p>"));
            return;
        }

        GameProfile profile = profileOpt.get();

        try {
            // Mutating the whitelist off the main server thread is asking for
            // trouble, so hop onto it and wait for the result.
            minecraftServer.submit(() -> {
                PlayerList playerList = minecraftServer.getPlayerList();
                UserWhiteList whitelist = playerList.getWhiteList();
                whitelist.add(new UserWhiteListEntry(new NameAndId(profile)));
            }).get();
        } catch (Exception e) {
            inviteManager.releaseReservation(code);
            LOGGER.warn("InviteWhitelist: Failed to add {} to the whitelist", profile.name(), e);
            respond(exchange, 500, "text/html; charset=utf-8", page("Something went wrong",
                    "<p>The server couldn't update the whitelist. Ask the server owner to check the logs.</p>"));
            return;
        }

        inviteManager.recordRedemption(code, profile.id(), profile.name());

        LOGGER.info("InviteWhitelist: Redeemed invite {} for {} ({})", code, profile.name(), profile.id());

        respond(exchange, 200, "text/html; charset=utf-8", page("You're whitelisted!", successBody(profile)));
    }

    private String successBody(GameProfile profile) {
        String message = config.successMessage != null && !config.successMessage.isBlank()
                ? config.successMessage
                : "<p><strong>{player}</strong> has been added to the whitelist.</p>"
                        + "<p>You can close this page and join the server now.</p>";
        message = message.replace("{player}", escape(profile.name()))
                .replace("{server_address}", escape(config.serverAddress))
                .replace("{server_version}", escape(config.serverVersion));

        boolean hasAddress = config.serverAddress != null && !config.serverAddress.isBlank();
        boolean hasVersion = config.serverVersion != null && !config.serverVersion.isBlank();
        if (!hasAddress && !hasVersion) {
            return message;
        }

        StringBuilder infobox = new StringBuilder("<div class=\"infobox\">");
        if (hasAddress) {
            infobox.append("<div class=\"infobox-row\"><span>Server address</span>")
                    .append("<code onclick=\"navigator.clipboard.writeText(this.textContent)\" title=\"Click to copy\">")
                    .append(escape(config.serverAddress)).append("</code></div>");
        }
        if (hasVersion) {
            infobox.append("<div class=\"infobox-row\"><span>Version</span>")
                    .append("<code onclick=\"navigator.clipboard.writeText(this.textContent)\" title=\"Click to copy\">")
                    .append(escape(config.serverVersion)).append("</code></div>");
        }
        infobox.append("</div>");
        return message + infobox;
    }

    private boolean isRateLimited(String remoteAddress) {
        long now = System.currentTimeMillis();
        Long last = lastRequestByIp.put(remoteAddress, now);
        return last != null && (now - last) < RATE_LIMIT_MILLIS;
    }

    private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> result = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String statusMessage(Invite invite) {
        if (invite.revoked) return "This invite has been revoked by an admin.";
        if (invite.isExpired()) return "This invite has expired.";
        if (invite.isExhausted()) return "This invite has already been used the maximum number of times.";
        return "This invite is not currently valid.";
    }

    private static String redeemErrorMessage(InviteManager.RedeemResult result) {
        return switch (result) {
            case NOT_FOUND -> "This invite code doesn't exist.";
            case REVOKED -> "This invite has been revoked by an admin.";
            case EXPIRED -> "This invite has expired.";
            case EXHAUSTED -> "This invite has already been used the maximum number of times.";
            case OK -> "";
        };
    }

    private String page(String title, String bodyHtml) {
        String backgroundStyle = "";
        if (config != null && config.backgroundImageUrl != null && !config.backgroundImageUrl.isBlank()) {
            backgroundStyle = """
                    body { background-image: url('%s'); background-size: cover;
                           background-position: center; background-attachment: fixed; }
                    body::before { content: ""; position: fixed; inset: 0;
                           background: rgba(0,0,0,0.45); z-index: -1; }
                    """.formatted(escapeCssUrl(config.backgroundImageUrl));
        }

        String faviconLink = "";
        if (config != null && config.faviconUrl != null && !config.faviconUrl.isBlank()) {
            faviconLink = "<link rel=\"icon\" href=\"" + escape(config.faviconUrl) + "\">";
        }

        String customCssLink = "";
        if (config != null && config.customCssUrl != null && !config.customCssUrl.isBlank()) {
            customCssLink = "<link rel=\"stylesheet\" href=\"" + escape(config.customCssUrl) + "\">";
        }

        return ("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  %s
                  <style>
                    body { font-family: system-ui, sans-serif; background: #1b1b1f; color: #eee;
                           display: flex; align-items: center; justify-content: center;
                           min-height: 100vh; margin: 0; }
                    .card { background: #26262c; padding: 2rem; border-radius: 12px; max-width: 420px;
                            width: 90%%; box-shadow: 0 10px 30px rgba(0,0,0,0.4); }
                    h1 { font-size: 1.4rem; margin-top: 0; }
                    label { display: block; font-size: 0.9rem; margin-bottom: 0.3rem; color: #ccc; }
                    input { width: 100%%; padding: 0.6rem; margin: 0 0 1rem; border-radius: 6px;
                            border: 1px solid #444; background: #1b1b1f; color: #eee; box-sizing: border-box; }
                    button { width: 100%%; padding: 0.7rem; border-radius: 6px; border: none;
                             background: #4caf50; color: white; font-weight: 600; cursor: pointer; }
                    button:hover { background: #43a047; }
                    .hint { font-size: 0.85rem; color: #999; }
                    .infobox { margin-top: 1.2rem; border: 1px solid #3a3a42; border-radius: 8px; overflow: hidden; }
                    .infobox-row { display: flex; align-items: center; justify-content: space-between;
                                   padding: 0.6rem 0.9rem; border-bottom: 1px solid #3a3a42; }
                    .infobox-row:last-child { border-bottom: none; }
                    .infobox-row span { font-size: 0.85rem; color: #999; }
                    .infobox-row code { background: #1b1b1f; color: #eee; padding: 0.25rem 0.5rem;
                                         border-radius: 4px; cursor: pointer; font-size: 0.9rem; }
                    %s
                  </style>
                  %s
                </head>
                <body>
                  <div class="card">
                    <h1>%s</h1>
                    %s
                  </div>
                </body>
                </html>
                """).formatted(escape(title), faviconLink, backgroundStyle, customCssLink, escape(title), bodyHtml);
    }

    /**
     * Percent-encodes characters that would let a config value break out of the
     * single-quoted CSS url('...') it's interpolated into (or out of the
     * enclosing <style> tag entirely).
     */
    private static String escapeCssUrl(String url) {
        return url.replace("'", "%27").replace("\"", "%22").replace("</style", "");
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
