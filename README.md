# DUM-SDK

A Fabric client mod and a Paper server plugin that let a server deliver signed client-side modules to its players at join time, without rebuilding the mod.

Work in progress. Done so far:

- HELLO / WELCOME handshake on the `dum:sdk` channel during the configuration phase. Players without the mod are disconnected before they enter the world.
- Modules may carry `assets/` (textures, models, sounds, lang): while a module runs, its jar is a client resource pack on top of the others, loaded before the player enters the world.
- Modules can add keys (Controls, category DUM; bindings kept between sessions) and client-only entities in the world, such as display entities with models from their assets.
- Cold load: a server-scoped Fabric mod can provide its own mixins and early registrations. SDK 0.14.0 requires it to declare a `dum-sdk:server` entrypoint implementing `DumServerMod`, without startup entrypoints. The client verifies its signature, dependencies and loaded hash; installation/updates require a game restart. Only the current accepted catalog enables its managed effects. Mixins must check `ServerSession.active(modId)`; classes and native registrations remain loaded, so arbitrary third-party mods cannot be automatically suspended. See [the cold mod contract](docs/modules.md#fabric-mods-cold-load).
- Diagnostics: `/sdk` in game lists the running modules with the time their code takes (ms per second), Fabric mods from the server, mods waiting for a restart and the modules that were turned off with their errors. A module that throws is turned off with a toast.
- Modules can listen to any Fabric API event (`DumContext.on`), see and drop every packet the client receives or sends (`onReceive` / `onSend`), and draw in the world (`WorldDraw` has shapes that also show with Iris shader packs).
- Modules talk to their server side over `dum:sdk` (`DumContext.sendToServer` / `onServerMessage`) and can draw on and take clicks from game screens.
- Session-scoped appearance hooks let hot modules select armor/wings rendering and define models and names for tagged vanilla items. Their effects require a successful SDK handshake, module installation and play join; they stop on disconnect and stay off on ordinary servers. This does not hot-load arbitrary mixins or add native registry types.
- Client modules come from the server: signed jars in `plugins/DUM-SDK/modules` are sent to the client in the configuration phase, checked (SHA-256 and Ed25519 signature), cached in `.minecraft/dum-sdk/cache` and never taken back to an older version. They are loaded, each in its own class loader, while on the server and unloaded on leaving. A failing module is turned off without taking the game down; after a crash with modules running, the next launch is in safe mode. A server with modules does not let a client in safe mode join (playing without them would be playing by other rules): the player is told to restart the game.

## Layout

| Project | What it is |
|---|---|
| `dum-network` | Wire format of the `dum:sdk` channel, shared by both sides |
| `dum-fabric` | The client mod (Minecraft 26.2, Fabric) |
| `dum-paper` | The standalone server plugin (Paper 26.2) |

Examples and test fixtures are maintained outside this repository. In the author's workspace they live in the sibling `DUM-SDK-Examples` directory: `info`, its separate Paper plugin, `sdktest` and the cold-load test mod.

## Writing a module

The full guide for module authors is [docs/modules.md](docs/modules.md). In short:

A module is a plain jar with `dum-module.json` at its root:

```json
{
	"id": "example",
	"version": "0.1.0",
	"apiVersion": 1,
	"entrypoint": "com.example.ExampleModule",
	"dependencies": { "other-module": ">=1.0.0" },
	"loadMode": "hot"
}
```

The entrypoint implements `ru.dum.sdk.api.DumModule`. It may use any game or Fabric class directly; whatever it registers through `DumContext` is removed by the SDK when the module is turned off.

## Signing

Clients run only modules signed with the key whose public half is in `Signing.RELEASE_KEY` (`dum-network`). Development clients (`runClient`) also accept `Signing.DEV_KEY`.

1. Once, on the machine that signs: `./gradlew generateSigningKey`. The private key goes to `~/.dum/signing.key`; keep a copy somewhere safe and never commit it. Put the printed public key into `Signing.RELEASE_KEY`.
2. In `~/.gradle/gradle.properties`: `dumModulesDir=<server>/plugins/DUM-SDK/modules` (and `dumSigningKey=<file>` for a key elsewhere).
3. In the module's own project, run its signing and deployment task (the external examples provide `./gradlew :<project>:deploy`). The server keeps offering its published catalog, including after a Paper restart, until `/dumsdk reload` publishes the staged jars. That command disconnects online players so everyone gets the new catalog when they rejoin. On the first startup after upgrading, the current `modules` folder becomes the initial published catalog.

`./gradlew :dum-fabric:runClient -Prelease` refuses dev-signed modules, like a player's client. `-Psodium` and `-Piris` add Sodium 0.9.2 and Iris 1.11.4 to the dev client (shader packs go into `dum-fabric/run/shaderpacks`).

## Building

Gradle runs on Java 25 and downloads it if needed (`gradle/gradle-daemon-jvm.properties`).

```
./gradlew build
```

The current checkout builds all three projects, including `dum-paper`, without private dependencies or a `dumCorePath` setting. The Paper plugin loads on Paper 26.2 without DUM-Core and owns its messages and handshake lifecycle.

DUM-Core independence starts with 0.14.1. The older v0.14.0 Paper artifact still requires DUM-Core. Existing `config.yml`, `messages.yml`, published catalogs and the `DumSdkApi` service remain supported.

`./gradlew :dum-fabric:runClient -Pjoin=localhost` starts a dev client and connects to a local server.

## Releasing the mod

The mod updates itself from GitHub Releases: at every game start it checks the latest release and installs a newer `DUM-SDK.jar` only if its signature matches the release key.

Releases are published by [Release](.github/workflows/release.yml). Only when the author requests a release, update `version` in `gradle.properties`, prepare `.github/release-notes/<version>.md` from `.github/release-notes/UNRELEASED.md`, commit and push. Then run **Actions > Release > Run workflow** on `main`, or push the matching `v<version>` tag. Ordinary commits and branch pushes do not publish or change the version.

The workflow builds and tests all projects, signs the client with repository secret `DUM_SIGNING_KEY`, verifies it against `Signing.RELEASE_KEY`, and publishes `DUM-SDK.jar`, `DUM-SDK.jar.sig`, `DUM-SDK-Paper-<version>.jar` and `SHA256SUMS.txt`. Missing descriptions, mismatched tags/versions and existing releases fail before publication. The release description comes directly from the versioned notes file.

`./gradlew --no-daemon prepareRelease` prepares the same files locally in `build/release` without publishing. It accepts the key from `DUM_SIGNING_KEY`, `-PdumSigningKey=<file>` or `~/.dum/signing.key`. See [release instructions](.github/release-notes/README.md).

`./gradlew :dum-fabric:runClient -PupdateTest=<url of a release JSON>` runs the self-update in a dev client against a fake release (dev-signed jars are accepted then).

## Tests

- `./gradlew test`: unit tests of the wire format, signatures, the event bridge, module dependencies and version order.
- `./gradlew :dum-fabric:runClientGameTest [-PtestServer=host:port] [-PplainServer=host:port]`: an end-to-end client game test. Start a test server first (DUM-SDK, the separately built `info`, `sdktest` 0.4.0+ and `DUM-Runtime-Test` fixtures, no cold-load mods). It checks handshake, item/equipment presentation, optional module failure and cleanup. An optional second server without plugins checks that effects stay off after switching servers. Screenshots land in `dum-fabric/build/run/clientGameTest/screenshots`.
- `./gradlew :dum-fabric:runClientGameTest -PstandaloneTest -PtestServer=localhost:25568`: checks a Paper server with only DUM-SDK and an empty published catalog. Give the test player `Player0` operator permission. It joins, runs `/dumsdk`, reloads twice and reconnects, exercising the standalone handshake and messages without external fixtures.
- Cold mod lifecycle integration uses `-PcoldInstallTest` then `-PcoldTest`, with a persistent `-PgameDir` and the external `dumcoldtest` 0.4.0+ fixture. See [test setup and commands](docs/modules.md#development).

## Manual checks

What still has to be verified on the real setup (Windows PC, VDS, the players' clients) is listed in [CHECKLIST.md](CHECKLIST.md).

## License

MIT, see [LICENSE](LICENSE).
