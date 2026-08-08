package is.huut.invitewhitelist;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class InviteWhitelistMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "invitewhitelist";
    private static final String LOG_PREFIX = "InviteWhitelist: ";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static InviteConfig config;
    private static InviteManager manager;
    private InviteHttpServer httpServer;

    @Override
    public void onInitializeServer() {
        config = InviteConfig.load();
        manager = new InviteManager();

        InviteCommand.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
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
        });
    }

    public static InviteConfig getConfig() {
        return config;
    }

    public static InviteManager getManager() {
        return manager;
    }
}
