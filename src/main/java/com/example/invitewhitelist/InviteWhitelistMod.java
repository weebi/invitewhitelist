package com.example.invitewhitelist;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InviteWhitelistMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "invitewhitelist";
    public static final Logger LOGGER = Logger.getLogger(MOD_ID);

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
                LOGGER.info("[InviteWhitelist] Listening on " + config.bindAddress + ":" + config.httpPort
                        + " (public URL base: " + config.publicBaseUrl + ")");
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE,
                        "[InviteWhitelist] Failed to start the join HTTP server on port " + config.httpPort
                                + ". Is something else already using that port?", e);
            }

            if (config.autoEnableWhitelist && !server.isUsingWhitelist()) {
                server.setUsingWhitelist(true);
                LOGGER.info("[InviteWhitelist] Enabled the vanilla whitelist automatically "
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
