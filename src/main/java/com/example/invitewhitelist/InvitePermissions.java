package com.example.invitewhitelist;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;

/**
 * Central place for every permission node this mod checks, backed by
 * fabric-permissions-api (works with LuckPerms or any other compatible
 * permission manager; falls back to vanilla op-level checks if no
 * permission manager is installed).
 */
public final class InvitePermissions {
    public static final String CREATE = "invitewhitelist.create";
    public static final String LIST = "invitewhitelist.list";
    public static final String INFO = "invitewhitelist.info";
    public static final String REVOKE = "invitewhitelist.revoke";
    public static final String DELETE = "invitewhitelist.delete";
    public static final String WHOIS = "invitewhitelist.whois";
    public static final String INVITED = "invitewhitelist.invited";
    public static final String REMOVE = "invitewhitelist.remove";

    /** Bypasses the "only your own invites/invitees" restriction and can manage everyone's. */
    public static final String ADMIN = "invitewhitelist.admin";

    /**
     * Op level required for a node when no permission manager (e.g. LuckPerms)
     * is installed, or hasn't explicitly granted/denied that node. This keeps
     * "OP-only" as the default out-of-the-box behavior; installing a
     * permission manager and granting these nodes is what lets you hand out
     * access without full OP.
     */
    private static final int FALLBACK_OP_LEVEL = 3;

    private InvitePermissions() {
    }

    public static boolean has(CommandSourceStack source, String node) {
        return Permissions.check(source, node, FALLBACK_OP_LEVEL);
    }

    public static boolean isAdmin(CommandSourceStack source) {
        return has(source, ADMIN);
    }
}
