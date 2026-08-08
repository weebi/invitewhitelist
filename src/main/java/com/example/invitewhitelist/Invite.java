package com.example.invitewhitelist;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain data holder for a single invite. Instances of this class are
 * serialized directly to/from JSON by Gson, so field names double as the
 * on-disk schema in invites.json - don't rename them casually.
 */
public class Invite {
    public String code;

    /** Display name of whoever created this invite (may go stale if they're renamed). */
    public String createdBy;

    /** UUID of whoever created this invite, or null if created from console. */
    public String createdByUuid;

    public long createdAt;

    /** Epoch millis after which the invite stops working, or null for "never". */
    public Long expiresAt;

    /** Maximum number of redemptions, or -1 for unlimited. */
    public int maxUses;

    /** How many times this invite has already been redeemed. */
    public int uses;

    public boolean revoked;

    /** Optional free-text label set by whoever created the invite. */
    public String note;

    /** One entry per player who has redeemed this invite - who joined and when. */
    public List<Redemption> redemptions = new ArrayList<>();

    // No-arg constructor required by Gson.
    public Invite() {
    }

    public Invite(String code, String createdBy, String createdByUuid, long createdAt,
                  Long expiresAt, int maxUses, String note) {
        this.code = code;
        this.createdBy = createdBy;
        this.createdByUuid = createdByUuid;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.maxUses = maxUses;
        this.uses = 0;
        this.revoked = false;
        this.note = note;
    }

    public boolean isExpired() {
        return expiresAt != null && System.currentTimeMillis() > expiresAt;
    }

    public boolean isExhausted() {
        return maxUses >= 0 && uses >= maxUses;
    }

    public boolean isUsable() {
        return !revoked && !isExpired() && !isExhausted();
    }

    public boolean isOwnedBy(String playerUuid) {
        return playerUuid != null && playerUuid.equals(createdByUuid);
    }

    /** Records that a specific player redeemed this invite, and when. */
    public static class Redemption {
        public String uuid;
        public String username;
        public long redeemedAt;

        public Redemption() {
        }

        public Redemption(String uuid, String username, long redeemedAt) {
            this.uuid = uuid;
            this.username = username;
            this.redeemedAt = redeemedAt;
        }
    }
}
