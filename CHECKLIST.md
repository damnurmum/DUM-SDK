# Manual checks

What has not been verified yet, or was verified only on the macOS laptop with a local test server.
Tick an item once it has been checked on the real setup, and note anything odd next to it.

## Windows PC

- [x] `./gradlew build` works on Windows (with `dumCorePath` in `%USERPROFILE%\.gradle\gradle.properties`). Checked 2026-10-27, `dum-paper` included.
- [x] On Windows, `./gradlew :dum-fabric:runClient -Pjoin=localhost` joins a local server with DUM-SDK. Confirmed by the author on 2026-10-30.
- [x] `./gradlew test` and `./gradlew :dum-fabric:runClientGameTest` pass on Windows (test server with `info` and `sdktest`, no cold-load mods). Checked 2026-10-27 against the local server (its whitelist has to let `Player0` in).
- [x] Cold load, first install: join, get the restart message, close the game, check that `mods\dum-cold-<id>.jar` appears and `dum-sdk\cold-apply.log` says `done`. 2026-10-27, MultiMC, dumcoldtest 0.2.0.
- [x] Cold load, update: publish a newer version, join, restart, check the new version runs (the `dumcoldtest` title mark shows it). 0.2.0 to 0.3.0, one jar left in `mods\`.
- [x] Cold load, removal: take the mod off the server, join, restart, check the jar is gone from `mods\`. Title mark gone too.
- [x] Closing the game with the launcher's Kill button instead of the normal way: does the helper still apply the plan? If not, the next join asks for one more restart (acceptable, but good to know). Yes: MultiMC's Kill leaves the helper alone and it applies the plan (2026-10-27).
- [x] A user folder with Cyrillic letters or spaces in the path: modules, cache and cold load still work. 2026-10-27: the game test with `-PgameDir=...\Тест папка\игра` (download, cache, class loaders, module resource packs), and the ColdApply helper copying and deleting jars with Cyrillic names in a folder with spaces.
- [x] Safe mode: kill the game while modules run, start it again, the pause menu shows the safe mode status. Checked by the author 2026-10-27 (MultiMC Kill).
- [x] Safe mode and joining (0.12.6): in safe mode the server refuses the join with "Перезапусти игру"; after one more restart the join works and the modules run. Checked by the author 2026-10-27.
- [x] Server side limits (0.14.0): module messages only from players given the module, rate limit per player and module (unit tests), `ops-only` modules not offered to other players (game test with sdktest ops-only: the client got only info). Checked 2026-10-27.
- [x] Rate limit with a real flood: a changed client spamming a module gets disconnected with "прислал серверу слишком много сообщений". Confirmed by the author on 2026-10-30.
- [x] Required modules (0.13.0): a required module failing in game disconnects the player with the reason. Checked 2026-10-27 with the game test and sdktest built as required; an optional one (sdktest as shipped) only turns itself off.

## macOS laptop

- [x] `./gradlew :dum-fabric:runClient -Pjoin=localhost` joined the local Paper server on 2026-10-30. The client connected to DUM-SDK 0.14.2 and started `info` 0.4.1 and `sdktest` 0.3.0.

## Release signing key

- [x] On the PC, once: `./gradlew generateSigningKey` (done 2026-10-27).
- [x] Keep a copy of `~/.dum/signing.key` somewhere safe. Confirmed by the author on 2026-10-30.
- [x] Put the printed public key into `Signing.RELEASE_KEY` (dum-network), bump the version, rebuild the mod and the plugin (0.12.0).
- [x] A release-signed module is accepted by a client started with `-Prelease`. Confirmed by the author on 2026-10-30.
- [x] A dev-signed module is refused by a client started with `-Prelease` ("подпись не подходит"). Confirmed by the author on 2026-10-30.
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
- [x] Russian game language: the author confirmed Russian text in the pause menu status and accessory slots on 2026-10-30.
- [x] The module download bar shows Russian text during a fresh download. Confirmed by the author on 2026-10-30.

## Local Paper (module publication)

- [x] On local Paper 0.15.0 before persistent publication was added, `/dumsdk reload` disconnected the player with the update message; the author rejoined and `info` worked. Confirmed by the author on 2026-10-30. Rejoin after the persistent publication change has not been checked in the client.
- [x] On local Paper 0.15.0, the published catalog survived a server restart while `sdktest.jar` was absent from the staging folder. `/dumsdk` still listed `info` and `sdktest`; after `/dumsdk reload`, it listed only `info`. Restoring the staged jar and running the command again restored both modules. Checked on 2026-10-30.
- [x] On local Paper 0.15.0, a staged jar without a signature made `/dumsdk reload` fail; `/dumsdk` still listed the previous two modules. Checked on 2026-10-30.

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
- [x] The slots keep their items across rejoins (checked 2026-10-27) and server restarts. Checked on 2026-10-30 with the local Paper server and DUM-SDK 0.14.2: the author reconnected in the same client after a server restart, and both items and their information lines remained.
- [x] The death flag shows over the last death point and moves after a new death. Checked by the author after two deaths on the local server on 2026-10-30. It was hidden in the Nether and visible again after returning to the Overworld.

## Open questions

- [x] The friends will mainly use Windows; Linux and macOS are possible fallback systems. Confirmed by the author on 2026-10-30. If either fallback system is used, run one cold load check on that system.
