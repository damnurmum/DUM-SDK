# DUM-SDK

A Fabric client mod and a Paper server plugin that let a server deliver signed client-side modules to its players at join time, without rebuilding the mod.

Work in progress. Done so far: the HELLO / WELCOME handshake on the `dum:sdk` channel during the configuration phase. Players without the mod are disconnected before they enter the world.

## Layout

| Project | What it is |
|---|---|
| `dum-network` | Wire format of the `dum:sdk` channel, shared by both sides |
| `dum-fabric` | The client mod (Minecraft 26.2, Fabric) |
| `dum-paper` | The server plugin (Paper 26.2), built on DUM-Core |

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
