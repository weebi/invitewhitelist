package is.huut.invitewhitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple JSON config stored at config/invitewhitelist/config.json.
 */
public class InviteConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Port the embedded join server listens on. */
    public int httpPort = 8642;

    /** Interface to bind to. Use "0.0.0.0" to listen on all interfaces. */
    public String bindAddress = "0.0.0.0";

    /**
     * Base URL players are sent. This is what actually gets emailed/DM'd to
     * people, so it should be the address your server (or reverse proxy) is
     * reachable at from the outside, e.g. "https://join.yourserver.example"
     * or "http://your.ip:8642" if you're not using a reverse proxy.
     */
    public String publicBaseUrl = "http://localhost:8642";

    /** If true, automatically turns on white-list=true when the server starts. */
    public boolean autoEnableWhitelist = true;

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("invitewhitelist");
    }

    public static Path configFile() {
        return configDir().resolve("config.json");
    }

    public static InviteConfig load() {
        Path file = configFile();
        try {
            Files.createDirectories(configDir());
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    InviteConfig cfg = GSON.fromJson(reader, InviteConfig.class);
                    return cfg != null ? cfg : new InviteConfig();
                }
            } else {
                InviteConfig cfg = new InviteConfig();
                cfg.save();
                return cfg;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load invitewhitelist config", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(configDir());
            try (Writer writer = Files.newBufferedWriter(configFile())) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save invitewhitelist config", e);
        }
    }
}
