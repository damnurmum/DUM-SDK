# Manual checks

What has not been verified yet, or was verified only on the macOS laptop with a local test server.
Tick an item once it has been checked on the real setup, and note anything odd next to it.

Historical checks used local SDK build labels from 0.14.x through 0.17.0 before release 0.14.0 was prepared. Those labels identify the builds tested, not separate published releases. The accumulated SDK changes are released together as 0.14.0; ordinary development does not increase the release version.

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

## Session-scoped runtime (0.14.0)

- [x] `./gradlew build` passes on macOS. Unit tests reject catalogs before WELCOME, incompatible and duplicate handshakes, and completion callbacks from an old connection. Registered Fabric listeners are passive when the connection is inactive. Checked on 2026-10-30.
- [x] The real client game test joins local SDK Paper, receives a tagged vanilla item from the external Paper fixture, and checks the active module's item name/model and wings/armor callbacks without mutating the carrier. Checked on 2026-10-30.
- [x] An optional module's failure removes its definitions and equipment callbacks. Rejoining restores a fresh healthy session; leaving removes its effects. Checked on 2026-10-30.
- [x] `./gradlew :dum-fabric:runClientGameTest -PtestServer=localhost:25565 -PplainServer=localhost:25566` passes. On the second Paper server, with no plugins, the SDK session stays inactive, cached modules do not start, and item/equipment presentation falls back to vanilla. Checked on 2026-10-30.
- [ ] Visually inspect hot wings/armor rendering, custom model assets and compatibility with Sodium/Iris and other mods. This run checked API results and successful mixin initialization, not a visual comparison.
- [ ] Repeat the 0.14.0 runtime session checks on Windows and Linux, and manually enter singleplayer after leaving an SDK server.

## Server-scoped Fabric mods (0.14.0)

- [x] `./gradlew build :dum-fabric:compileGametestJava` passes on macOS. Shared contract tests reject legacy/missing/ambiguous entrypoints, startup initializers and a missing SDK dependency. Checked on 2026-10-30.
- [x] First install of external `dumcoldtest` 0.4.0 through local Paper: the client requests restart without activating the mod; after exit the helper installs its jar and reports `done`. Checked on 2026-10-30 with `-PcoldInstallTest` and a persistent external game directory.
- [x] The real cold title mixin activates only on its accepted catalog; a second SDK server with an empty catalog joins without a forced restart and keeps it inactive. Checked on 2026-10-30 with `-PcoldTest -PotherSdkServer=localhost:25567`.
- [x] The same loaded mod stays passive on a Paper server with no plugins and in a newly created singleplayer world. Its managed tick count does not advance there. Checked on 2026-10-30.
- [x] Reconnect creates a fresh context; the old context stays inactive and refuses late registrations. Leave and intentional required-mod failure call disable and cleanup once; failure disconnects the player. Checked on 2026-10-30.
- [x] `./gradlew build :dum-fabric:runClientGameTest -PtestServer=localhost:25565 -PplainServer=localhost:25566` still passes with the hot fixtures and no cold mod in the catalog. Checked on 2026-10-30 after the cold lifecycle changes.
- [ ] Repeat the 0.14.0 cold session/install checks on Windows and Linux; test Sodium/Iris compatibility.
- [ ] Validate a real cold mod with native registry entries. The integration fixture checks a mixin and managed lifecycle, not removal of native registrations or global Fabric assets (those remain loaded).

## Standalone Paper plugin (0.14.1)

- [x] `./gradlew build :dum-fabric:compileGametestJava -PdumCorePath=/does-not-exist` passes on macOS, including Paper and its unit tests. No private/composite Core build is used. Checked on 2026-10-30.
- [x] SDK message tests verify first-start creation, old-file overrides, missing-key fallback, empty strings, lists, unparsed placeholders and reload without rewriting the user's file. Checked on 2026-10-30.
- [x] Local Paper starts with only DUM-SDK installed; `/plugins` lists one plugin, and `/dumsdk` renders a custom local message. Compiled plugin classes contain no `ru/dum/core` references; plugin metadata declares no Core dependency. Checked on 2026-10-30.
- [x] `./gradlew :dum-fabric:runClientGameTest -PstandaloneTest -PtestServer=localhost:25568` passes. Three client joins, two `/dumsdk reload` disconnects/reconnects and bundled Russian reconnect text work without Core or gameplay fixtures. The old config omits `handshake.enabled` and install timeout, and the local messages file overrides only `core.info`. Checked on 2026-10-30.
- [x] Existing client game test passes against the standalone server with only the external DUM-Info and DUM-Runtime-Test fixture plugins added (no Core): signed module download, tagged items through `DumSdkApi`, equipment callbacks, optional failure, disconnect cleanup and reconnect. Checked on 2026-10-30.
- [ ] Repeat the standalone plugin checks on Windows/Linux and the live setup.

## Actions release workflow (0.14.1)

- [x] `release.yml` passes actionlint and all its shell scripts pass `bash -n`. Local preflight checks cover manual/tag runs, tag mismatch, mismatched tag commit, an existing release, missing/blank notes, invalid versions and GitHub API failure. Checked on 2026-10-30 without publishing.
- [x] `./gradlew --no-daemon prepareRelease` builds/tests all projects and prepares client, signature, standalone Paper jar and SHA-256 sums. Signing via `DUM_SIGNING_KEY` succeeds; the globally configured dev key is refused. Independent OpenSSL verification and checksum checks pass; neither jar contains private key bytes. Checked on 2026-10-30.
- [x] Repository Actions secret `DUM_SIGNING_KEY` is configured; the private value is not stored in source or release artifacts. Checked on 2026-10-30.
- [x] First real [Release workflow](https://github.com/damnurmum/DUM-SDK/actions/runs/41116835996) passed for tag `v0.14.1` on 2026-10-30. Ubuntu built/tested all projects, used the Actions secret to sign the client, and published four assets with the versioned description. Downloaded release checksums, GitHub asset digests and Ed25519 signature were independently verified; both jars report 0.14.1, and Paper metadata/classes contain no Core dependency/references. The ordinary Build workflow also passed.

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

- [ ] The standalone plugin starts on the VDS without requiring DUM-Core, `/dumsdk` lists the modules.
- [ ] `plugman reload DUM-SDK` with players online: nobody is kicked, new joins still get the handshake and modules.
- [ ] DUM-Auth-TG: a player who still has to confirm a new IP in Telegram is handled by DUM-Auth-TG first; a player without the mod gets the DUM-SDK message.
- [ ] Plasmo Voice keeps working (it uses its own plugin channels and UDP on the same port).
- [ ] Download over the real network: the progress bar shows, a few MB of modules arrive well within `install-timeout-seconds` (120).
- [ ] `messages.yml` on the VDS is old: the new texts (install errors, restart) still show, taken from the jar.
- [ ] The daily 03:00 restart: players rejoin without new downloads (modules come from the cache).

## Open questions

- [x] The friends will mainly use Windows; Linux and macOS are possible fallback systems. Confirmed by the author on 2026-10-30. If either fallback system is used, run one cold load check on that system.
