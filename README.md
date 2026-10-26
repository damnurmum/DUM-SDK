# DUM-SDK

A Fabric client mod and a Paper server plugin that let a server deliver signed client-side modules to its players at join time, without rebuilding the mod.

Work in progress. Done so far:

- HELLO / WELCOME handshake on the `dum:sdk` channel during the configuration phase. Players without the mod are disconnected before they enter the world.
- Modules talk to their server side over `dum:sdk` (`DumContext.sendToServer` / `onServerMessage`) and can draw on and take clicks from game screens.
- Client modules: jars in `.minecraft/dum-sdk/modules` are loaded, each in its own class loader, while connected to a DUM-SDK server, and unloaded on leaving. A failing module is turned off without taking the game down; after a crash with modules running, the next launch is in safe mode.

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

The entrypoint implements `ru.dum.sdk.api.DumModule`. It may use any game or Fabric class directly; whatever it registers through `DumContext` is removed by the SDK when the module is turned off. `./gradlew :dum-example-module:install` puts the example into the dev client.

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
