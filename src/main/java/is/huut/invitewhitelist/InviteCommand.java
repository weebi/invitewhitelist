package is.huut.invitewhitelist;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserWhiteList;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Registers "/invite create|list|info|revoke|delete|whois|invited|remove|reload".
 *
 * Every subcommand is gated by its own permission node (see
 * {@link InvitePermissions}) rather than a single OP check, so you can grant
 * individual nodes to non-op players through LuckPerms (or any other
 * fabric-permissions-api-compatible permission manager). Without a
 * permission manager installed, everything falls back to requiring OP
 * level 3, same as before.
 *
 * Non-admins (players without invitewhitelist.admin) can only view/manage
 * invites and invitees that trace back to themselves.
 */
public final class InviteCommand {
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private InviteCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("invite")
                .then(Commands.literal("create")
                        .requires(source -> InvitePermissions.has(source, InvitePermissions.CREATE))
                        .executes(ctx -> create(ctx, -1, null))
                        .then(Commands.argument("uses", IntegerArgumentType.integer(1))
                                .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "uses"), null))
                                .then(Commands.argument("expires", StringArgumentType.word())
                                        .executes(ctx -> create(
                                                ctx,
                                                IntegerArgumentType.getInteger(ctx, "uses"),
                                                StringArgumentType.getString(ctx, "expires"))))))
                .then(Commands.literal("list")
                        .requires(source -> InvitePermissions.has(source, InvitePermissions.LIST))
                        .executes(InviteCommand::list))
                .then(Commands.literal("info")
                        .requires(source -> InvitePermissions.has(source, InvitePermissions.INFO))
                        .then(Commands.argument("code", StringArgumentType.word())
                        .suggests(InviteCommand::suggestAccessibleCodes)
                                .executes(InviteCommand::info)))
                .then(Commands.literal("revoke")
                        .requires(source -> InvitePermissions.has(source, InvitePermissions.REVOKE))
                        .then(Commands.argument("code", StringArgumentType.word())
                        .suggests(InviteCommand::suggestAccessibleCodes)
                                .executes(InviteCommand::revoke)))
                .then(Commands.literal("delete")
                        .requires(source -> InvitePermissions.has(source, InvitePermissions.DELETE))
                        .then(Commands.argument("code", StringArgumentType.word())
                        .suggests(InviteCommand::suggestAccessibleCodes)
                                .executes(InviteCommand::delete)))
                .then(Commands.literal("whois")
                        .requires(source -> InvitePermissions.has(source, InvitePermissions.WHOIS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(InviteCommand::whois)))
                .then(Commands.literal("invited")
                        .requires(source -> InvitePermissions.has(source, InvitePermissions.INVITED))
                        .executes(ctx -> invited(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(InviteCommand::suggestInvitedPlayers)
                                .executes(ctx -> invited(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("remove")
                        .requires(source -> InvitePermissions.has(source, InvitePermissions.REMOVE))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(InviteCommand::remove)))
                .then(Commands.literal("reload")
                        .requires(source -> InvitePermissions.has(source, InvitePermissions.RELOAD))
                        .executes(InviteCommand::reload)));
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        InviteWhitelistMod.ConfigReloadResult result = InviteWhitelistMod.reloadConfig();
        if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message()), true);
            return 1;
        }

        source.sendFailure(Component.literal(result.message()));
        return 0;
    }

    // ---- create ---------------------------------------------------------

    private static int create(CommandContext<CommandSourceStack> ctx, int maxUses, String expiresRaw) {
        CommandSourceStack source = ctx.getSource();

        Long expiresAt = null;
        if (expiresRaw != null) {
            try {
                long millis = DurationParser.parseToMillis(expiresRaw);
                if (millis > 0) {
                    expiresAt = System.currentTimeMillis() + millis;
                }
            } catch (IllegalArgumentException e) {
                source.sendFailure(Component.literal(e.getMessage()));
                return 0;
            }
        }

        String createdByName = source.getTextName();
        String createdByUuid = currentPlayerUuid(source);

        Invite invite = InviteWhitelistMod.getManager().create(createdByName, createdByUuid, maxUses, expiresAt, null);
        String url = InviteWhitelistMod.getConfig().publicBaseUrl + "/join/" + invite.code;

        Component message = Component.literal("Invite created: " + invite.code + "\nSend this link: ")
            .append(Component.literal(url)
                .withStyle(style -> style
                    .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                    .withUnderlined(true)))
            .append(Component.literal("\n" + describe(invite)));
        source.sendSuccess(() -> message, false);
        return 1;
    }

    // ---- list / info / revoke / delete -----------------------------------

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        boolean admin = InvitePermissions.isAdmin(source);
        String selfUuid = currentPlayerUuid(source);

        List<Invite> sorted = InviteWhitelistMod.getManager().all().stream()
                .filter(i -> admin || i.isOwnedBy(selfUuid))
                .sorted(Comparator.comparingLong((Invite i) -> i.createdAt).reversed())
                .toList();

        if (sorted.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No invites to show."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Invites (" + sorted.size() + "):"), false);
        for (Invite invite : sorted) {
            source.sendSuccess(() -> Component.literal(describe(invite)), false);
        }
        return sorted.size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String code = StringArgumentType.getString(ctx, "code");
        Invite invite = InviteWhitelistMod.getManager().get(code);
        if (invite == null) {
            source.sendFailure(Component.literal("No invite with code " + code));
            return 0;
        }
        if (!InvitePermissions.isAdmin(source) && !invite.isOwnedBy(currentPlayerUuid(source))) {
            source.sendFailure(Component.literal("You can only inspect invites you created."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(describe(invite)), false);
        if (!invite.redemptions.isEmpty()) {
            StringBuilder sb = new StringBuilder("Redeemed by:");
            for (Invite.Redemption r : invite.redemptions) {
                sb.append("\n - ").append(r.username).append(" (")
                        .append(DATE_FORMAT.format(Instant.ofEpochMilli(r.redeemedAt))).append(")");
            }
            source.sendSuccess(() -> Component.literal(sb.toString()), false);
        }
        return 1;
    }

    private static int revoke(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String code = InviteManager.normalize(StringArgumentType.getString(ctx, "code"));
        Invite invite = InviteWhitelistMod.getManager().get(code);
        if (invite == null) {
            source.sendFailure(Component.literal("No invite with code " + code));
            return 0;
        }
        if (!InvitePermissions.isAdmin(source) && !invite.isOwnedBy(currentPlayerUuid(source))) {
            source.sendFailure(Component.literal("You can only revoke invites you created."));
            return 0;
        }

        InviteWhitelistMod.getManager().revoke(code);
        source.sendSuccess(() -> Component.literal("Revoked invite " + code), true);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String code = InviteManager.normalize(StringArgumentType.getString(ctx, "code"));
        Invite invite = InviteWhitelistMod.getManager().get(code);
        if (invite == null) {
            source.sendFailure(Component.literal("No invite with code " + code));
            return 0;
        }
        if (!InvitePermissions.isAdmin(source) && !invite.isOwnedBy(currentPlayerUuid(source))) {
            source.sendFailure(Component.literal("You can only delete invites you created."));
            return 0;
        }

        InviteWhitelistMod.getManager().delete(code);
        source.sendSuccess(() -> Component.literal("Deleted invite " + code), true);
        return 1;
    }

    // ---- whois / invited / remove ----------------------------------------

    private static int whois(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "player");

        Optional<GameProfile> profileOpt = resolveProfile(source, name);
        if (profileOpt.isEmpty()) {
            source.sendFailure(Component.literal("No known account named " + name));
            return 0;
        }
        GameProfile profile = profileOpt.get();

        Optional<InviteManager.InviterLookup> lookup = InviteWhitelistMod.getManager().findInviteFor(profile.id());
        if (lookup.isEmpty()) {
            if (!InvitePermissions.isAdmin(source)) {
                source.sendFailure(Component.literal("You can only look up players invited through your own invites."));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    profile.name() + " has no recorded invite (added manually, or before this mod was installed)."), false);
            return 0;
        }

        InviteManager.InviterLookup found = lookup.get();
        if (!InvitePermissions.isAdmin(source)
                && !found.invite.isOwnedBy(currentPlayerUuid(source))) {
            source.sendFailure(Component.literal("You can only look up players invited through your own invites."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                found.redemption.username + " was invited by " + found.invite.createdBy
                        + " using code " + found.invite.code + " on "
                        + DATE_FORMAT.format(Instant.ofEpochMilli(found.redemption.redeemedAt))), false);
        return 1;
    }

    private static int invited(CommandContext<CommandSourceStack> ctx, String targetName) {
        CommandSourceStack source = ctx.getSource();
        boolean admin = InvitePermissions.isAdmin(source);
        String selfUuid = currentPlayerUuid(source);

        String targetUuid;
        String displayName;

        if (targetName == null) {
            if (selfUuid == null) {
                source.sendFailure(Component.literal("Console has no invitees of its own - specify a player name."));
                return 0;
            }
            targetUuid = selfUuid;
            displayName = source.getTextName();
        } else {
            boolean askingAboutSelf = targetName.equalsIgnoreCase(source.getTextName());
            if (!admin && !askingAboutSelf) {
                source.sendFailure(Component.literal("You don't have permission to view other players' invitees."));
                return 0;
            }
            Optional<GameProfile> profileOpt = resolveProfile(source, targetName);
            if (profileOpt.isEmpty()) {
                source.sendFailure(Component.literal("No known account named " + targetName));
                return 0;
            }
            targetUuid = profileOpt.get().id().toString();
            displayName = profileOpt.get().name();
        }

        List<InviteManager.InviteeRecord> invitees = InviteWhitelistMod.getManager().findInvitedBy(targetUuid);
        if (invitees.isEmpty()) {
            String finalDisplayName = displayName;
            source.sendSuccess(() -> Component.literal(finalDisplayName + " hasn't invited anyone yet."), false);
            return 0;
        }

        String finalDisplayName = displayName;
        source.sendSuccess(() -> Component.literal(
                finalDisplayName + " has invited " + invitees.size() + " player(s):"), false);
        for (InviteManager.InviteeRecord record : invitees) {
            source.sendSuccess(() -> Component.literal(
                    " - " + record.username + " (via " + record.inviteCode + ", "
                            + DATE_FORMAT.format(Instant.ofEpochMilli(record.redeemedAt)) + ")"), false);
        }
        return invitees.size();
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "player");
        boolean admin = InvitePermissions.isAdmin(source);

        Optional<GameProfile> profileOpt = resolveProfile(source, name);
        if (profileOpt.isEmpty()) {
            source.sendFailure(Component.literal("No known account named " + name));
            return 0;
        }
        GameProfile profile = profileOpt.get();

        if (!admin) {
            String selfUuid = currentPlayerUuid(source);
            boolean invitedByMe = InviteWhitelistMod.getManager().findInviteFor(profile.id())
                    .map(lookup -> lookup.invite.isOwnedBy(selfUuid))
                    .orElse(false);
            if (!invitedByMe) {
                source.sendFailure(Component.literal("You can only remove players that you personally invited."));
                return 0;
            }
        }

        MinecraftServer server = source.getServer();
        PlayerList playerList = server.getPlayerList();
        UserWhiteList whitelist = playerList.getWhiteList();
        whitelist.remove(new NameAndId(profile));

        ServerPlayer online = playerList.getPlayer(profile.id());
        if (online != null) {
            online.connection.disconnect(Component.literal("You have been removed from the whitelist."));
        }

        source.sendSuccess(() -> Component.literal(
                profile.name() + " has been removed from the whitelist."), true);
        return 1;
    }

    // ---- helpers ----------------------------------------------------------

    private static CompletableFuture<Suggestions> suggestAccessibleCodes(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CommandSourceStack source = ctx.getSource();
        boolean admin = InvitePermissions.isAdmin(source);
        String selfUuid = currentPlayerUuid(source);
        Set<String> codes = new LinkedHashSet<>();

        InviteWhitelistMod.getManager().all().stream()
                .filter(invite -> admin || invite.isOwnedBy(selfUuid))
                .sorted(Comparator.comparingLong((Invite invite) -> invite.createdAt).reversed())
                .map(invite -> invite.code)
                .forEach(codes::add);

        for (String code : codes) {
            builder.suggest(code);
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestInvitedPlayers(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CommandSourceStack source = ctx.getSource();
        boolean admin = InvitePermissions.isAdmin(source);
        String selfUuid = currentPlayerUuid(source);
        Set<String> players = new LinkedHashSet<>();

        InviteWhitelistMod.getManager().all().stream()
                .filter(invite -> admin || invite.isOwnedBy(selfUuid))
                .filter(invite -> !invite.redemptions.isEmpty())
                .map(invite -> invite.createdBy)
                .filter(name -> name != null && !name.isBlank())
                .forEach(players::add);

        for (String player : players) {
            builder.suggest(player);
        }
        return builder.buildFuture();
    }

    private static Optional<GameProfile> resolveProfile(CommandSourceStack source, String username) {
        return source.getServer().services().profileResolver().fetchByName(username);
    }

    private static String currentPlayerUuid(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player.getUUID().toString();
        }
        return null;
    }

    private static String describe(Invite invite) {
        StringBuilder sb = new StringBuilder();
        sb.append(invite.code).append(" - ");
        if (invite.revoked) {
            sb.append("REVOKED");
        } else if (invite.isExpired()) {
            sb.append("EXPIRED");
        } else if (invite.isExhausted()) {
            sb.append("EXHAUSTED");
        } else {
            sb.append("ACTIVE");
        }
        sb.append(" | uses: ").append(invite.uses).append(invite.maxUses >= 0 ? "/" + invite.maxUses : "/unlimited");
        sb.append(" | expires: ").append(invite.expiresAt != null
                ? DATE_FORMAT.format(Instant.ofEpochMilli(invite.expiresAt))
                : "never");
        sb.append(" | by: ").append(invite.createdBy);
        return sb.toString();
    }
}
