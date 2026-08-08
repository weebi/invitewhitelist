# Invite Whitelist

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

Minecraft 26.2 ships unobfuscated with official Mojang mappings baked in,
so there's no separate "Yarn mappings" dependency to configure - the code
in this project already uses the official (Mojang) class/method names
directly (`PlayerList`, `UserWhiteList`, `CommandSourceStack`, etc.). This
also means Loom doesn't remap anything for 26.1+, so dependencies in
`build.gradle` use plain `implementation` rather than the older
`modImplementation`/`modApi` (those configurations no longer exist on the
non-remapping Loom plugin).

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

The built mod jar will be at `build/libs/invite-whitelist-1.0.0.jar`.

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
  "autoEnableWhitelist": true
}
```

- `httpPort` / `bindAddress` - where the embedded join server listens.
- `publicBaseUrl` - the address you want printed in invite links. If you're
  running behind a reverse proxy (recommended, see below), point this at
  the proxy's public URL, e.g. `https://join.yourserver.example`.
- `autoEnableWhitelist` - if true, the mod flips `white-list=true` for you
  on startup so you don't forget to actually turn the whitelist on.

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
- The embedded server is plain HTTP with no TLS. Use a reverse proxy
  (Caddy, nginx, Cloudflare Tunnel, etc.) in front of it for HTTPS, and
  set `publicBaseUrl` to the proxy's address.
- Treat invite links like the vanilla whitelist itself: only send them to
  people you actually want on the server.

## Known things to double-check after building

I don't have a way to compile against the real Minecraft 26.2 jar in this
environment, so while every API used here (`PlayerList.getWhiteList()`,
`UserWhiteList.remove(GameProfile)`, `MinecraftServer.getProfileCache()`,
`CommandSourceStack`/`Commands`, `ServerLifecycleEvents`,
`CommandRegistrationCallback`, `ServerPlayer.connection.disconnect(...)`,
`Permissions.check(...)` from fabric-permissions-api) is based on confirmed
26.2/official-mappings sources, there are a couple of spots worth a quick
look if `./gradlew build` complains:

- `PlayerList.setUsingWhiteList(boolean)` - the getter `isUsingWhitelist()`
  is confirmed; the setter name is inferred from Mojang's usual naming
  pattern. If it doesn't compile, check the actual setter name in
  `PlayerList` (via your IDE's autocomplete once the Minecraft jar is
  downloaded) and adjust `InviteWhitelistMod.java`.
- `CommandSourceStack.getTextName()` - used to record who created an
  invite. If renamed, swap it for whatever method returns the command
  sender's display name as a `String`.
- `fabric_permissions_api_version=0.7.0` in `gradle.properties` is the
  version documented for Minecraft 26.1 at the time of writing; check
  [lucko/fabric-permissions-api's version matrix](https://github.com/lucko/fabric-permissions-api/blob/master/USAGE.md#version-matrix)
  for whether a newer build targets 26.2 specifically before you build.

Everything else (whitelist file writes, HTTP server, invite storage,
config, permission-node plumbing) is plain Java/JDK code with no
Minecraft-version sensitivity.

## Troubleshooting

**`Could not find method modImplementation() for arguments [...]`** - this
means the build script is using the old `modImplementation`/`modApi`
configurations, which don't exist on Minecraft 26.1+'s non-remapping Loom
plugin (there's nothing to remap anymore, so Loom doesn't add "mod"
variants of the dependency configurations). Fix: use plain `implementation`
for all dependencies, as this project's `build.gradle` already does.

**`Could not resolve net.fabricmc:fabric-loom:1.17.x` / plugin API version
mismatch (e.g. "consumer needed ... 9.5.0" but got "9.2.0")** - this means
the build ran under a Gradle version older than what Loom 1.17.x requires
(Gradle 9.5+). It happens if you run a bare `gradle build` with whatever
Gradle you already had installed instead of `./gradlew build`. Fix: run
`gradle wrapper` once (regenerates the wrapper jar to match the pinned
9.5.1 in `gradle-wrapper.properties`), then always use `./gradlew`/`gradlew.bat`.
