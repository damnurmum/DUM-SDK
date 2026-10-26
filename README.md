# DUM-SDK

A Fabric client mod and a Paper server plugin that let a server deliver signed client-side modules to its players at join time, without rebuilding the mod.

Work in progress. Done so far:

- HELLO / WELCOME handshake on the `dum:sdk` channel during the configuration phase. Players without the mod are disconnected before they enter the world.
- Modules talk to their server side over `dum:sdk` (`DumContext.sendToServer` / `onServerMessage`) and can draw on and take clicks from game screens.
- Client modules come from the server: signed jars in `plugins/DUM-SDK/modules` are sent to the client in the configuration phase, checked (SHA-256 and Ed25519 signature), cached in `.minecraft/dum-sdk/cache` and never taken back to an older version. They are loaded, each in its own class loader, while on the server and unloaded on leaving. A failing module is turned off without taking the game down; after a crash with modules running, the next launch is in safe mode.

## Layout

| Project | What it is |
|---|---|
| `dum-network` | Wire format of the `dum:sdk` channel, shared by both sides |
| `dum-fabric` | The client mod (Minecraft 26.2, Fabric) |
| `dum-paper` | The server plugin (Paper 26.2), built on DUM-Core |
| `dum-example-module` | `info`, a client module: accessory slots for a compass and a clock, and their lines on the HUD (server side: `accessories` in `dum-paper`) |

## Writing a module

A module is a plain jar with `dum-module.json` at its root:

```json
{
	"id": "info",
	"version": "0.1.0",
	"apiVersion": 1,
	"entrypoint": "ru.dum.sdk.example.InfoModule",
	"dependencies": { "other-module": ">=1.0.0" },
	"loadMode": "hot"
}
```

The entrypoint implements `ru.dum.sdk.api.DumModule`. It may use any game or Fabric class directly; whatever it registers through `DumContext` is removed by the SDK when the module is turned off.

## Signing

Clients run only modules signed with the key whose public half is in `Signing.RELEASE_KEY` (`dum-network`). Development clients (`runClient`) also accept `Signing.DEV_KEY`.

1. Once, on the machine that signs: `./gradlew generateSigningKey`. The private key goes to `~/.dum/signing.key`; keep a copy somewhere safe and never commit it. Put the printed public key into `Signing.RELEASE_KEY`.
2. In `~/.gradle/gradle.properties`: `dumModulesDir=<server>/plugins/DUM-SDK/modules` (and `dumSigningKey=<file>` for a key elsewhere).
3. `./gradlew :dum-example-module:deploy` signs the module and puts it there; `/dumsdk reload` offers it to players on their next join.

`./gradlew :dum-fabric:runClient -Prelease` refuses dev-signed modules, like a player's client.

## Building

Gradle runs on Java 25 and downloads it if needed (`gradle/gradle-daemon-jvm.properties`).

```
./gradlew build
```

`dum-paper` depends on DUM-Core, which is not public. It is built only when `dumCorePath` in `~/.gradle/gradle.properties` points at a DUM-Core checkout:

```
dumCorePath=/path/to/DUM-Core
```

`./gradlew :dum-fabric:runClient -Pjoin=localhost` starts a dev client and connects to a local server.

## License

MIT, see [LICENSE](LICENSE).
