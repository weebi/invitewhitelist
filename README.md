# Invite Whitelist

> [!WARNING]
> This entire project has been heavily written using Claude and GitHub Copilot.
> I am not a Java developer, so the code and documentation may contain mistakes,
> rough edges, or assumptions that need correcting. Your mileage may vary - review,
> test, and verify everything before relying on it in a production server.

A Fabric server-side mod for Minecraft **26.2** that replaces manual `/whitelist add`
with shareable invite links.

Flow:

1. Someone with permission runs `/invite create` (optionally with a use
   limit / expiry) - no OP required, see **Permissions** below.
2. The mod generates a code like `AB7K-X92P` and prints a link:
   `https://yourserver.example/join/AB7K-X92P`
3. You send that link to the person.
4. They open it, type their Minecraft username, and submit.
5. The mod resolves that username to a Mojang account (via the server's
   own profile cache, the same mechanism `/whitelist add` uses) and adds
   the UUID to the vanilla `whitelist.json`.
6. Depending on how the invite was created, it then expires, runs out of
   uses, or keeps working for the next person.
7. The mod remembers **who invited whom**, so you can later look up who's
   responsible for a given player, list everyone a specific person has
   invited, and remove any of their invitees from the whitelist.

No web hosting, database, or external service required - the mod runs its
own small HTTP server inside the Minecraft server process.

## Requirements

- Minecraft 26.2 (Java Edition), dedicated server
- Fabric Loader 0.19.3+
- Fabric API 0.152.1+26.2 (or newer for 26.2)
- Java 25 to build and run
- Gradle 9.5.1 (or use the wrapper, see below) with Fabric Loom 1.17
- Optional but recommended: [LuckPerms](https://luckperms.net/) (or any
  other mod compatible with `fabric-permissions-api`) if you want to grant
  invite permissions to non-op players. `fabric-permissions-api` itself is
  bundled inside this mod's jar, so you don't need to install it separately.

## Project layout

```
invite-whitelist/
  build.gradle
  settings.gradle
  gradle.properties
  src/
    main/
      java/is/huut/invitewhitelist/
        InviteWhitelistMod.java   - mod entrypoint, wires everything together
        InviteConfig.java         - config/invitewhitelist/config.json
        InviteManager.java        - invite storage + lifecycle (invites.json)
        Invite.java                - one invite's data
        InviteCommand.java        - /invite create|list|info|revoke|delete|whois|invited|remove
        InvitePermissions.java    - permission node constants + checks
        InviteHttpServer.java     - the /join/<code> web page + redemption
        DurationParser.java       - parses "7d", "12h", "permanent", etc.
      resources/
        fabric.mod.json
```

## Building

This repo ships the actual Gradle wrapper scripts and `gradle-wrapper.properties`
(already pinned to Gradle 9.5.1), but not the wrapper **jar** itself - that's
a binary file, so it has to be generated once locally:

```bash
cd invite-whitelist
gradle wrapper
./gradlew build
```

`gradle wrapper` (any Gradle you already have installed, any version) reads
the `gradle-wrapper.properties` already in this repo and downloads the exact
matching jar - you don't need to pass `--gradle-version` yourself. After that,
**always build with `./gradlew`** (or `gradlew.bat` on Windows), never a bare
`gradle` command - if a bare `gradle` on your system is a different version
than 9.5.1, you'll hit errors like `Could not resolve net.fabricmc:fabric-loom`
because Loom 1.17.x specifically requires Gradle 9.5+.

The built mod jar will be at `build/libs/invite-whitelist-1.2.1.jar`.

If you use IntelliJ IDEA, just open the folder as a Gradle project - IDEA
will bootstrap Gradle itself if it's missing.

## Installing on your server

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2 on your server.
2. Drop in `fabric-api-0.152.1+26.2.jar` (or newer for 26.2) and this
   mod's jar into your server's `mods/` folder.
3. Start the server once to generate `config/invitewhitelist/config.json`,
   then stop it.
4. Edit that config (see below), especially `publicBaseUrl`.
5. Start the server again.

## Config (`config/invitewhitelist/config.json`)

```json
{
  "httpPort": 8642,
  "bindAddress": "0.0.0.0",
  "publicBaseUrl": "http://localhost:8642",
  "autoEnableWhitelist": true,
  "cloudflareTurnstileSiteKey": "",
  "cloudflareTurnstileSecretKey": "",
  "backgroundImageUrl": "",
  "customCssUrl": "",
  "faviconUrl": "",
  "serverAddress": "",
  "serverVersion": "",
  "successMessage": ""
}
```

- `httpPort` / `bindAddress` - where the embedded join server listens.
- `publicBaseUrl` - the address you want printed in invite links. If you're
  running behind a reverse proxy (recommended, see below), point this at
  the proxy's public URL, e.g. `https://join.yourserver.example`.
- `autoEnableWhitelist` - if true, the mod flips `white-list=true` for you
  on startup so you don't forget to actually turn the whitelist on.
- `cloudflareTurnstileSiteKey` - your Cloudflare Turnstile site key. When set,
  the join page will display a Turnstile challenge before whitelist redemption.
- `cloudflareTurnstileSecretKey` - your Cloudflare Turnstile secret key. The
  server validates the Turnstile token before adding a player to the whitelist.
- `backgroundImageUrl` - background image shown behind the join card. Either a
  full URL (`https://example.com/bg.jpg`) or a path served from
  `config/invitewhitelist/web/` (see below), e.g. `/assets/background.jpg`.
  Blank = no background image.
- `customCssUrl` - an extra stylesheet loaded after the built-in styles, so
  its rules can override them. Same URL rules as `backgroundImageUrl`. Blank
  = disabled.
- `faviconUrl` - browser tab icon for the join pages. Same URL rules as
  `backgroundImageUrl`. Blank = browser default.
- `serverAddress` / `serverVersion` - shown as a copy-to-clipboard info box on
  the success page after someone is whitelisted, e.g. `play.example.com` and
  `1.21.4 Fabric`. Either can be left blank to omit that row; leaving both
  blank hides the info box entirely.
- `successMessage` - custom HTML shown on the success page instead of the
  default "You've been whitelisted" text. Supports the placeholders
  `{player}`, `{server_address}`, and `{server_version}`. This is **not**
  escaped, so you can use links/formatting - only put trusted content here.
  Blank = use the built-in default message.

> Note: Cloudflare Turnstile is optional. Set both keys to non-empty values to
> enable verification. If either key is blank, the join form will work without
> a Turnstile challenge.

### Local background/CSS/favicon files

If you'd rather not host an image or stylesheet externally, drop it into
`config/invitewhitelist/web/` (created automatically on first start) and
reference it as `/assets/<filename>` in `backgroundImageUrl`, `customCssUrl`,
or `faviconUrl` - e.g. a file at `config/invitewhitelist/web/background.jpg`
is served at `/assets/background.jpg`.

## Permissions

Every `/invite` subcommand is gated by its own permission node instead of a
single OP check, using [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api)
(bundled in this mod's jar):

| Node | Controls |
|---|---|
| `invitewhitelist.create` | `/invite create` |
| `invitewhitelist.list` | `/invite list` |
| `invitewhitelist.info` | `/invite info <code>` |
| `invitewhitelist.revoke` | `/invite revoke <code>` |
| `invitewhitelist.delete` | `/invite delete <code>` |
| `invitewhitelist.whois` | `/invite whois <player>` |
| `invitewhitelist.invited` | `/invite invited [player]` |
| `invitewhitelist.remove` | `/invite remove <player>` |
| `invitewhitelist.reload` | `/invite reload` |
| `invitewhitelist.admin` | Bypasses the "only your own invites" restriction (see below) |

**Without a permission manager installed**, every node falls back to
requiring vanilla OP level 3 - so out of the box, behavior is identical to
before (OPs only). Install LuckPerms (or another compatible mod) to grant
individual nodes to non-op players, e.g.:

```
/lp user Steve permission set invitewhitelist.create true
/lp user Steve permission set invitewhitelist.list true
/lp user Steve permission set invitewhitelist.invited true
/lp user Steve permission set invitewhitelist.remove true
```

That gives Steve the ability to create invites, see his own invite list,
see who he's invited, and remove people he invited - without making him an
operator, and without letting him touch anyone else's invites.

### Ownership restriction

Players who have `invitewhitelist.admin` (OPs get this via the level-3
fallback) can see and manage **every** invite. Players who don't are
restricted to invites and invitees that trace back to themselves:

- `/invite list` only shows invites *they* created.
- `/invite info` / `revoke` / `delete` fail with "you can only manage
  invites you created" for codes they didn't generate.
- `/invite whois <player>` only works for players redeemed through an invite
  they created; admins can look up any known player.
- `/invite invited <player>` only works for themselves unless they're an
  admin (running plain `/invite invited` always means "show me my own
  invitees").
- `/invite remove <player>` only works if that player was whitelisted
  through an invite *they* personally created.

## Commands

- `/invite create` - unlimited uses, never expires.
- `/invite create <uses>` - limited to `<uses>` redemptions.
- `/invite create <uses> <expires>` - also expires after a duration like
  `7d`, `12h`, `30m`, `1d12h`, or `permanent`.
- `/invite list` - show invites and their status (your own, or everyone's
  if you're an admin).
- `/invite info <code>` - status of one invite, plus who's redeemed it.
- `/invite revoke <code>` - immediately invalidate a code (kept for records).
- `/invite delete <code>` - remove a code entirely.
- `/invite whois <player>` - shows who invited a given player and when.
- `/invite invited [player]` - lists everyone a player has invited. Omit
  the player argument to see your own invitees.
- `/invite remove <player>` - removes a player from the vanilla whitelist
  and disconnects them if they're currently online.
- `/invite reload` - reloads `config.json` from disk and restarts the embedded
  HTTP server so configuration changes take effect without restarting Minecraft.

## Security notes / trust model

- This does **not** cryptographically verify that the person filling in the
  form owns that Minecraft account - it just resolves the username to a
  UUID (the same lookup `/whitelist add` does) and whitelists that UUID.
  That's fine, because the *real* authentication still happens when
  Minecraft itself connects: whitelisting a UUID only lets that specific
  Microsoft/Mojang account log in, it doesn't let the form-filler log in
  as someone else.
- Invite codes are 8 characters from a 33-character alphabet (~1.5×10^12
  combinations), so brute-forcing a code isn't practical, but the server
  has no built-in protection against someone spidering random codes -
  there's a light 2-second per-IP cooldown on submissions, nothing more.
  For a public-facing box, put this behind a reverse proxy and add real
  rate limiting / TLS there.
- That per-IP cooldown (and the IP sent to Cloudflare for Turnstile
  verification) is read from the `X-Forwarded-For`/`X-Real-IP` headers set
  by the reverse proxy in front of it, falling back to the direct socket
  address if neither is present. This is only safe when the embedded
  `httpPort` itself is **not** reachable directly from outside (only
  through the proxy) - otherwise a client could set those headers itself
  and bypass the cooldown entirely.
- POST bodies to `/join/*` are capped at 16 KB and the embedded server
  enforces a 30-second request/response timeout, to keep a slow or
  oversized request from tying up memory or a connection indefinitely.
- The embedded server is plain HTTP with no TLS. Use a reverse proxy
  (Caddy, nginx, Cloudflare Tunnel, etc.) in front of it for HTTPS, and
  set `publicBaseUrl` to the proxy's address.
- Treat invite links like the vanilla whitelist itself: only send them to
  people you actually want on the server.
