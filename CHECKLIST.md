# Manual checks

What has not been verified yet, or was verified only on the macOS laptop with a local test server.
Tick an item once it has been checked on the real setup, and note anything odd next to it.

## Windows PC

- [x] `./gradlew build` works on Windows (with `dumCorePath` in `%USERPROFILE%\.gradle\gradle.properties`). Checked 2026-10-27, `dum-paper` included.
- [ ] `./gradlew :dum-fabric:runClient -Pjoin=localhost` joins a local server with DUM-SDK.
- [x] `./gradlew test` and `./gradlew :dum-fabric:runClientGameTest` pass on Windows (test server with `info` and `sdktest`, no cold-load mods). Checked 2026-10-27 against the local server (its whitelist has to let `Player0` in).
- [x] Cold load, first install: join, get the restart message, close the game, check that `mods\dum-cold-<id>.jar` appears and `dum-sdk\cold-apply.log` says `done`. 2026-10-27, MultiMC, dumcoldtest 0.2.0.
- [x] Cold load, update: publish a newer version, join, restart, check the new version runs (the `dumcoldtest` title mark shows it). 0.2.0 to 0.3.0, one jar left in `mods\`.
- [x] Cold load, removal: take the mod off the server, join, restart, check the jar is gone from `mods\`. Title mark gone too.
- [ ] Closing the game with the launcher's Kill button instead of the normal way: does the helper still apply the plan? If not, the next join asks for one more restart (acceptable, but good to know).
- [ ] A user folder with Cyrillic letters or spaces in the path: modules, cache and cold load still work.
- [ ] Safe mode: kill the game while modules run, start it again, the pause menu shows the safe mode status.

## Release signing key

- [x] On the PC, once: `./gradlew generateSigningKey` (done 2026-10-27).
- [ ] Keep a copy of `~/.dum/signing.key` somewhere safe (the author does this).
- [x] Put the printed public key into `Signing.RELEASE_KEY` (dum-network), bump the version, rebuild the mod and the plugin (0.12.0).
- [ ] A release-signed module is accepted by a client started with `-Prelease`.
- [ ] A dev-signed module is refused by a client started with `-Prelease` ("подпись не подходит").
- [x] `./gradlew :dum-fabric:releaseSdk` publishes the first release with the key (needs `gh auth login` on the PC). v0.12.0, 2026-10-27.

## Self-update of the mod

Checked on the Mac only in a dev client against a fake release (download, signature, toast, helper copies the jar).

- [x] In MultiMC: with release N installed, publish N+1; start the game, see the toast, close the game, and check that `mods` now has `DUM-SDK-<N+1>.jar` and no longer the old jar. 0.12.0 to 0.12.1 (2026-10-27): the new jar arrived, but the old one stayed, because the helper ran from it and Windows locks a jar a JVM runs from. Fixed in 0.12.2 (the helper runs from a copy). 0.12.2 to 0.12.3: the new jar arrived and the old one was deleted.
- [x] The next start runs N+1 (`/sdk` shows it) and the server lets it in. 0.12.1, 2026-10-27; leaving the server no longer hangs the loading screen.
- [ ] A client whose protocol is too old: the server's message says the mod updates itself after a restart, and it does.

## The players' real client

- [ ] MultiMC instance with the author's mods (Sodium, Iris, Lithium, ModernFix, EntityCulling, Xaero's maps, Litematica, Plasmo Voice, Zoomify, BetterF3 and the rest): the game starts, joins, modules run.
- [ ] DUM-SDK's own mixins (packets in and out, bundle packets) do not clash with other mods: the `sdktest` packet counters move, no errors in the log.
- [ ] Iris with the shader pack the author actually uses: `sdktest` shapes from `WorldDraw` are visible, the `info` death flag is visible.
- [x] Module keys appear in Controls under DUM, rebinding survives a restart. Checked by the author 2026-10-27.
- [ ] Russian game language: texts of the pause menu status, the download bar and the accessory slots.

## Server (VDS)

- [ ] The plugin starts next to DUM-Core on the VDS, `/dumsdk` lists the modules.
- [ ] `plugman reload DUM-SDK` with players online: nobody is kicked, new joins still get the handshake and modules.
- [ ] DUM-Auth-TG: a player who still has to confirm a new IP in Telegram is handled by DUM-Auth-TG first; a player without the mod gets the DUM-SDK message.
- [ ] Plasmo Voice keeps working (it uses its own plugin channels and UDP on the same port).
- [ ] Download over the real network: the progress bar shows, a few MB of modules arrive well within `install-timeout-seconds` (120).
- [ ] `messages.yml` on the VDS is old: the new texts (install errors, restart) still show, taken from the jar.
- [ ] The daily 03:00 restart: players rejoin without new downloads (modules come from the cache).

## Gameplay of the `info` demo

- [x] Accessory slots: a compass and a clock go in, other items are refused. Checked by the author 2026-10-27; swapping with the cursor item not reported.
- [x] Death without keepInventory: the compass and the clock from the slots drop with the rest; with keepInventory they stay. Checked by the author 2026-10-27.
- [ ] The slots keep their items across rejoins (checked 2026-10-27) and server restarts (not checked yet).
- [ ] The death flag shows over the last death point (checked 2026-10-27); only in the right dimension and moving after a new death, not checked yet.

## Open questions

- [ ] Which operating systems the friends use (Windows for sure; macOS, Linux?). Each one deserves one pass of the cold load checks.
