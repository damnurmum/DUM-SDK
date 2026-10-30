# Writing DUM-SDK modules

DUM-SDK runs code that the server hands out. There are two kinds:

| | Module (hot load) | Fabric mod (cold load) |
|---|---|---|
| What it is | A plain jar with `dum-module.json` | A normal Fabric mod jar with `fabric.mod.json` |
| When it runs | Right after joining; unloaded on leaving | After the game restarts, until it is removed |
| Mixins, registries, early hooks | No | Yes |
| Use it for | Almost everything: HUD, screens, keys, world rendering, events, packets, resources | What a module cannot reach: changing the game's own code |

Both are signed with the author's key and delivered by the server; clients refuse anything else.

## Fair play: the rules every module follows

The server cannot know what code really runs on a player's computer: a changed client can say anything about itself, its version, its hashes, its modules. Signatures, the protocol check, required modules and the safe mode refusal keep out accidents and easy tricks, not a determined cheater. What keeps the game fair is how modules are built:

1. **The server decides the rules.** The client side shows things and takes input; anything that matters for the game (what may be done, rewards, costs, cooldowns, limits, where items go, what is stored) is decided and stored on the server side, in the plugin. The client sends what the player did ("clicked slot 1"), never the result ("now I have the compass in slot 1"). The `info` accessories are the example: the server checks the item on the cursor, moves it and keeps it in the player's data; the client only draws what the server sent.
2. **The server checks every message.** Treat each message from a client as possibly forged: check its size and values, that the player may do this now (game mode, open screen, distance, cooldown), and do nothing on anything unexpected.
3. **Do not send the client more than the player may know.** Whatever reaches the client can be read by a changed one, whether the module shows it or not. Send only what the player would see or know in the game anyway; for things like hidden ores, other players' positions or a chest's contents, the server works them out and sends only the result the player is allowed (a glow on the blocks a perk reveals, not the ore map around).
4. **Data the vanilla client has anyway is fine to use** (own position, world time, the last death point, the blocks and entities the game sent): a module that shows it gives nothing new.

A module that would break these rules to work is a sign that its logic belongs on the server.

## A module

### Project

Copy `dum-example-module`. Its build compiles against Minecraft (Loom, no mappings: 26.2 is not obfuscated), Fabric API and the SDK:

```kotlin
dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.2")
    compileOnly(project(":dum-fabric"))
}
```

Add the project to the `deploy` list in the root `build.gradle.kts`.

### dum-module.json

```json
{
	"id": "info",
	"version": "0.4.0",
	"apiVersion": 1,
	"entrypoint": "ru.dum.sdk.example.InfoModule",
	"dependencies": { "other-module": ">=1.0.0" }
}
```

- `id`: lowercase letters, digits, `-` and `_`. Also the namespace of the module's assets.
- `version`: semantic version. Clients never go back to an older version of a module they have run, so publish fixes as new versions.
- `apiVersion`: the SDK API the module is built for (`ModuleManager.API_VERSION`, now 1).
- `dependencies`: other modules, with Fabric version predicates. Dependencies start first and stop last.
- `required` (default `true`): without the module the player would play by other rules. If it does not start, the server refuses the join; if it fails in game, the player is disconnected with the reason. Set `false` only for modules that change nothing in the game, such as pure cosmetics.

### Lifecycle and threads

`DumModule.onEnable(DumContext)` runs on the render thread once the configuration phase is over (before the player is in the world), `onDisable()` when the player leaves or the game closes. Each module has its own class loader; after `onDisable` the SDK removes everything registered through the context and drops the loader. In a development client the log says whether the module was fully unloaded: if not, something still holds a reference to its classes (a listener registered directly with Fabric, a thread, a static field of a game class).

Unless stated otherwise, callbacks run on the render thread.

A module that throws anywhere the SDK calls it is turned off and `/sdk` shows the error. If it is required (or a required module depends on it), the player is disconnected from the server; otherwise the player gets a toast and the other modules keep running.

### DumContext

| Method | What it gives |
|---|---|
| `id()`, `logger()`, `dataDir()` | The module's id, a logger, a folder for files that survive restarts |
| `onTick(Runnable)` | End of every client tick |
| `hud(HudElement)` | Drawing over the HUD |
| `screen(ScreenLayer)` | Drawing on any game screen (after its background) and taking mouse clicks |
| `key(name, defaultKey, onPress)` | A key in Controls under DUM; title from the lang key `key.<module>.<name>`; bindings are kept |
| `addEntity(Entity)` | A client-only entity, such as a display entity; gone with the level, check `isRemoved()` |
| `on(Event, listener)` | Any Fabric API event: world rendering, input, interaction, lifecycle... |
| `onReceive(Class, Predicate)` | Every packet of that type from the server, before the game handles it; return false to drop it |
| `onSend(Class, Predicate)` | Every packet of that type the client sends; return false to drop it (called on the sending thread) |
| `onServerMessage(Consumer<byte[]>)`, `sendToServer(byte[])` | Talking to the module's server side |

`Screens.containerBounds(screen)` gives the window of a container screen. A module may also use any game or Fabric class directly; then cleaning up is its own job.

### Resources

Anything under `assets/` in the module jar (textures, models, item definitions, sounds, lang files) is loaded as a client resource pack while the module runs: fixed on top, loaded before the player enters the world. Use the module id as the namespace: `assets/info/textures/...`. No `pack.mcmeta` is needed.

Player-visible text belongs in `assets/<id>/lang/ru_ru.json`; put the same Russian text into `en_us.json` so players with an English game see it too.

### Drawing in the world

Listen to `LevelRenderEvents.COLLECT_SUBMITS` and submit geometry with `submitCustomGeometry`. The pose starts at the camera: translate by the block position minus `gameRenderer.mainCamera().position()`.

Shader packs (Iris) draw only the game's regular render pipelines. `WorldDraw` has lines, boxes and quads (also seen through blocks) that work with and without shaders. Debug pipelines (`RenderTypes.debugQuads`, `debugFilledBox`, the fill of gizmo boxes) are not drawn with shaders; gizmo lines are. Shaders may tint colors.

For objects with models, prefer display entities (`addEntity`): the game draws them like any entity, with every renderer.

### Packets

`onReceive` sees what the server tells the client: damage, sounds, particles, entities, blocks, chat, deaths, dimension changes. `onSend` sees what the player does: attacks, digging, using items, movement. Dropping a packet can desync the client from the server; the server stays the authority on game rules.

## The server side

A plugin that serves a client module depends on DUM-SDK in its `paper-plugin.yml`:

```yaml
dependencies:
  server:
    DUM-SDK:
      load: BEFORE
      required: true
      join-classpath: true
```

```java
DumSdkApi sdk = DumSdkApi.get();
sdk.onModuleMessage(plugin, "info", (player, data) -> { /* main thread */ });
sdk.sendToModule(player, "info", bytes); // from PlayerJoinEvent on
```

The module's data format is its own business; follow the [fair play rules](#fair-play-the-rules-every-module-follows): the server decides and checks, and sends only what the player may know (the `info` accessories send only "slot N was clicked", and the server decides what happens).

DUM-SDK enforces part of the rules itself, so a module cannot forget them:

- A message reaches the server side only from a player who was given the module, at most `module-messages.per-second` on average (`burst` at once) per player and module; the rest is dropped, and a client that keeps flooding is disconnected. Whatever the handler throws is logged and stays there.
- Modules listed in `ops-only` (config of DUM-SDK) reach operators only: everyone else does not get them in the catalog, cannot download their jars, and `sendToModule` sends them nothing. `hasModule(player, id)` tells a plugin what a player has.

The server itself should not leak either: Paper's anti-xray hides ores the player cannot see in the chunks it sends, with a per-world list for the Nether (ancient debris, nether gold, quartz).

## Publishing

1. In `~/.gradle/gradle.properties`: `dumModulesDir=<server>/plugins/DUM-SDK/modules`, and `dumSigningKey=<file>` if the key is not `~/.dum/signing.key`.
2. `./gradlew :<project>:deploy` signs the jar and copies it with its `.sig` there.
3. `/dumsdk reload` on the server publishes the staged jars and disconnects online players with a message to rejoin. Rejoining players download changed modules once and then use the cache. A whole Fabric mod still needs a game restart after the rejoin. Until publication, even a Paper restart keeps using the last published catalog. On the first startup after upgrading, the existing `modules` folder becomes the initial published catalog. If a staged jar is invalid, publication fails and the old catalog stays active.

## Fabric mods (cold load)

Any Fabric mod jar can be published the same way: a jar with `fabric.mod.json` and no `dum-module.json` is treated as a whole mod (see `dum-cold-test`). On join the client checks its signature, its `depends` (Minecraft, Fabric Loader, other mods) and that the player has no copy of the same mod. If the set of such mods changed, the player is disconnected with "restart the game"; a helper process puts the jars into `mods/` as `dum-cold-<id>.jar` (or removes them) after the game exits.

## Development

- `./gradlew :dum-fabric:runClient -Pjoin=localhost` starts a dev client and joins a local server. Dev clients also accept modules signed with the dev key.
- `-Prelease` makes the dev client refuse dev-signed modules, like a player's client.
- `-Psodium`, `-Piris` add Sodium and Iris; shader packs go into `dum-fabric/run/shaderpacks`.
- `dum-test-module` (`sdktest`) exercises rendering, events, packets and failures; `dum-cold-test` exercises cold load.
- `/sdk` in game: modules, time they take, Fabric mods from the server, pending restarts, failures.
- `./gradlew :dum-fabric:runClientGameTest` joins a running test server and checks the SDK end to end; a module's own checks can follow the same pattern (`dum-fabric/src/gametest`).
