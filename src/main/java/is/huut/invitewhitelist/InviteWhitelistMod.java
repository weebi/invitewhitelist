package is.huut.invitewhitelist;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class InviteWhitelistMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "invitewhitelist";
    private static final String LOG_PREFIX = "InviteWhitelist: ";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static InviteConfig config;
    private static InviteManager manager;
    private static InviteHttpServer httpServer;
    private static MinecraftServer server;

    @Override
    public void onInitializeServer() {
        config = InviteConfig.load();
        manager = new InviteManager();

        InviteCommand.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            InviteWhitelistMod.server = server;
            httpServer = new InviteHttpServer(server, manager, config);
            try {
                httpServer.start();
                LOGGER.info(LOG_PREFIX + "Listening on {}:{} (public URL base: {})",
                    config.bindAddress, config.httpPort, config.publicBaseUrl);
            } catch (IOException e) {
                LOGGER.error(LOG_PREFIX + "Failed to start the join HTTP server on port {}. Is something else already using that port?",
                    config.httpPort, e);
            }

            if (config.autoEnableWhitelist && !server.isUsingWhitelist()) {
                server.setUsingWhitelist(true);
                LOGGER.info(LOG_PREFIX + "Enabled the vanilla whitelist automatically "
                    + "(set autoEnableWhitelist=false in config.json to disable this).");
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (httpServer != null) {
                httpServer.stop();
            }
            InviteWhitelistMod.server = null;
        });
    }

    public static InviteConfig getConfig() {
        return config;
    }

    public static InviteManager getManager() {
        return manager;
    }

    /**
     * Reloads the disk configuration and restarts the embedded HTTP server so
     * changes to its bind address or port take effect without a full restart.
     */
    public static synchronized ConfigReloadResult reloadConfig() {
        if (server == null) {
            return new ConfigReloadResult(false, "The server is not running.");
        }

        InviteConfig oldConfig = config;
        InviteHttpServer oldHttpServer = httpServer;
        InviteConfig newConfig;
        try {
            newConfig = InviteConfig.load();
        } catch (RuntimeException e) {
            return new ConfigReloadResult(false, "Failed to load config.json: " + errorMessage(e));
        }

        if (oldHttpServer != null) {
            oldHttpServer.stop();
        }

        InviteHttpServer newHttpServer = new InviteHttpServer(server, manager, newConfig);
        try {
            newHttpServer.start();
            config = newConfig;
            httpServer = newHttpServer;
            LOGGER.info(LOG_PREFIX + "Reloaded configuration from disk; listening on {}:{} (public URL base: {})",
                    config.bindAddress, config.httpPort, config.publicBaseUrl);
            return new ConfigReloadResult(true, "Invite configuration reloaded from disk.");
        } catch (IOException e) {
            LOGGER.error(LOG_PREFIX + "Failed to start the HTTP server with the reloaded configuration.", e);
            try {
                InviteHttpServer restoredHttpServer = new InviteHttpServer(server, manager, oldConfig);
                restoredHttpServer.start();
                httpServer = restoredHttpServer;
            } catch (IOException restoreError) {
                httpServer = null;
                LOGGER.error(LOG_PREFIX + "Could not restore the previous HTTP configuration.", restoreError);
            }
            return new ConfigReloadResult(false,
                    "Failed to apply config.json: " + errorMessage(e) + ". The previous configuration was restored if possible.");
        }
    }

    private static String errorMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    public record ConfigReloadResult(boolean success, String message) {
    }
}
