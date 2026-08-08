package is.huut.invitewhitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds all invites in memory, backed by config/invitewhitelist/invites.json.
 * Every mutating method is synchronized on this instance, and redemption is
 * done as an atomic "reserve" step so two people can't race the last use of
 * a limited-use invite through the HTTP server's thread pool.
 */
public class InviteManager {
    // Excludes 0/O and 1/I to avoid codes that are ambiguous to read/type.
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Invite>>() {
    }.getType();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path storageFile;
    private final Map<String, Invite> invites = new ConcurrentHashMap<>();

    public InviteManager() {
        this.storageFile = InviteConfig.configDir().resolve("invites.json");
        load();
    }

    private void load() {
        try {
            Files.createDirectories(storageFile.getParent());
            if (Files.exists(storageFile)) {
                try (Reader reader = Files.newBufferedReader(storageFile)) {
                    Map<String, Invite> loaded = GSON.fromJson(reader, MAP_TYPE);
                    if (loaded != null) {
                        invites.putAll(loaded);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load invites.json", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(storageFile.getParent());
            Path tmp = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(invites, MAP_TYPE, writer);
            }
            Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save invites.json", e);
        }
    }

    private String generateCode() {
        String code;
        do {
            code = randomGroup() + "-" + randomGroup();
        } while (invites.containsKey(code));
        return code;
    }

    private String randomGroup() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    public synchronized Invite create(String createdBy, String createdByUuid, int maxUses, Long expiresAt, String note) {
        String code = generateCode();
        Invite invite = new Invite(code, createdBy, createdByUuid, System.currentTimeMillis(), expiresAt, maxUses, note);
        invites.put(code, invite);
        save();
        return invite;
    }

    public Invite get(String code) {
        return invites.get(normalize(code));
    }

    public Collection<Invite> all() {
        return invites.values();
    }

    public synchronized boolean revoke(String code) {
        Invite invite = get(code);
        if (invite == null) return false;
        invite.revoked = true;
        save();
        return true;
    }

    public synchronized boolean delete(String code) {
        boolean removed = invites.remove(normalize(code)) != null;
        if (removed) save();
        return removed;
    }

    /**
     * Atomically checks that the invite is currently usable and, if so,
     * immediately counts a use against it. Call {@link #releaseReservation}
     * if the redemption fails after this point (bad username, whitelist
     * write failure, etc.) to give the use back. On success, call
     * {@link #recordRedemption} to attribute the join to this invite.
     */
    public synchronized RedeemTicket reserve(String code) {
        Invite invite = get(code);
        if (invite == null) return RedeemTicket.error(RedeemResult.NOT_FOUND);
        if (invite.revoked) return RedeemTicket.error(RedeemResult.REVOKED);
        if (invite.isExpired()) return RedeemTicket.error(RedeemResult.EXPIRED);
        if (invite.isExhausted()) return RedeemTicket.error(RedeemResult.EXHAUSTED);

        invite.uses++;
        save();
        return RedeemTicket.ok(invite);
    }

    public synchronized void releaseReservation(String code) {
        Invite invite = get(code);
        if (invite != null && invite.uses > 0) {
            invite.uses--;
            save();
        }
    }

    /** Records that a player successfully redeemed an invite - who they are and when. */
    public synchronized void recordRedemption(String code, UUID playerUuid, String username) {
        Invite invite = get(code);
        if (invite != null) {
            invite.redemptions.add(new Invite.Redemption(playerUuid.toString(), username, System.currentTimeMillis()));
            save();
        }
    }

    /** Finds which invite (and redemption record) got a specific player onto the whitelist, if known. */
    public Optional<InviterLookup> findInviteFor(UUID playerUuid) {
        String uuidStr = playerUuid.toString();
        return invites.values().stream()
                .flatMap(invite -> invite.redemptions.stream()
                        .filter(r -> r.uuid.equals(uuidStr))
                        .map(r -> new InviterLookup(invite, r)))
                .max(Comparator.comparingLong(lookup -> lookup.redemption.redeemedAt));
    }

    /** Lists everyone a given player has personally invited (across all of their invites), most recent first. */
    public List<InviteeRecord> findInvitedBy(String inviterUuid) {
        List<InviteeRecord> result = new ArrayList<>();
        for (Invite invite : invites.values()) {
            if (inviterUuid != null && inviterUuid.equals(invite.createdByUuid)) {
                for (Invite.Redemption r : invite.redemptions) {
                    result.add(new InviteeRecord(r.username, r.uuid, r.redeemedAt, invite.code));
                }
            }
        }
        result.sort(Comparator.comparingLong((InviteeRecord rec) -> rec.redeemedAt).reversed());
        return result;
    }

    public static String normalize(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    public enum RedeemResult {OK, NOT_FOUND, REVOKED, EXPIRED, EXHAUSTED}

    public static final class RedeemTicket {
        public final RedeemResult result;
        public final Invite invite;

        private RedeemTicket(RedeemResult result, Invite invite) {
            this.result = result;
            this.invite = invite;
        }

        static RedeemTicket ok(Invite invite) {
            return new RedeemTicket(RedeemResult.OK, invite);
        }

        static RedeemTicket error(RedeemResult result) {
            return new RedeemTicket(result, null);
        }
    }

    public static final class InviterLookup {
        public final Invite invite;
        public final Invite.Redemption redemption;

        InviterLookup(Invite invite, Invite.Redemption redemption) {
            this.invite = invite;
            this.redemption = redemption;
        }
    }

    public static final class InviteeRecord {
        public final String username;
        public final String uuid;
        public final long redeemedAt;
        public final String inviteCode;

        InviteeRecord(String username, String uuid, long redeemedAt, String inviteCode) {
            this.username = username;
            this.uuid = uuid;
            this.redeemedAt = redeemedAt;
            this.inviteCode = inviteCode;
        }
    }
}
