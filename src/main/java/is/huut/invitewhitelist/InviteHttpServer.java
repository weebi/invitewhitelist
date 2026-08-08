package is.huut.invitewhitelist;

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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
        httpServer = HttpServer.create(new InetSocketAddress(config.bindAddress, config.httpPort), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        httpServer.setExecutor(executor);
        httpServer.createContext("/join/", this::handleJoin);
        httpServer.createContext("/", exchange ->
                respond(exchange, 200, "text/plain; charset=utf-8", "Invite Whitelist server is running."));
        httpServer.start();
    }

    public void stop() {
        if (httpServer != null) httpServer.stop(0);
        if (executor != null) executor.shutdownNow();
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

        String body = ("""
                <form method="POST" action="/join/%s">
                  <label for="username">Minecraft username</label>
                  <input id="username" name="username" type="text" maxlength="16"
                         pattern="[A-Za-z0-9_]{2,16}" required autofocus autocomplete="off">
                  <button type="submit">Join whitelist</button>
                </form>
                <p class="hint">This adds your Minecraft account's UUID to the server whitelist.
                Use your exact in-game username (the one you log in with).</p>
                """).formatted(escape(invite.code));

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

        respond(exchange, 200, "text/html; charset=utf-8", page("You're whitelisted!",
                "<p><strong>" + escape(profile.name()) + "</strong> has been added to the whitelist.</p>"
                        + "<p>You can close this page and join the server now.</p>"));
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

    private static String page(String title, String bodyHtml) {
        return ("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
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
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>%s</h1>
                    %s
                  </div>
                </body>
                </html>
                """).formatted(escape(title), escape(title), bodyHtml);
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
