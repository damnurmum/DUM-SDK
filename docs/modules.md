# Writing DUM-SDK modules

DUM-SDK runs code that the server hands out. There are two kinds:

| | Module (hot load) | Fabric mod (cold load) |
|---|---|---|
| What it is | A plain jar with `dum-module.json` | A normal Fabric mod jar with `fabric.mod.json` |
| When it runs | Right after joining; unloaded on leaving | Loaded at startup; effects enabled only for the accepted server catalog |
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

Create a separate Gradle project for the module. It compiles against Minecraft (Loom, no mappings: 26.2 is not obfuscated), Fabric API and a built SDK jar. The examples are maintained outside this repository; the author's workspace has them in the sibling `DUM-SDK-Examples` directory.

```kotlin
dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.2")
    compileOnly(files("../DUM-SDK/dum-fabric/build/libs/DUM-SDK-0.14.1.jar"))
}
```

Build the SDK jar first (`./gradlew :dum-fabric:jar` in the SDK checkout). The presentation APIs and server-scoped cold mod contract require SDK 0.14.0 or newer. Keep the module's build, signing and deployment tasks in its own project.

### dum-module.json

```json
{
	"id": "info",
	"version": "0.4.0",
	"apiVersion": 1,
	"entrypoint": "com.example.InfoModule",
	"dependencies": { "other-module": ">=1.0.0" }
}
```

- `id`: lowercase letters, digits, `-` and `_`. Also the namespace of the module's assets.
- `version`: semantic version. Clients never go back to an older version of a module they have run, so publish fixes as new versions.
- `apiVersion`: the SDK API the module is built for (`ModuleManager.API_VERSION`, now 1).
- `dependencies`: other modules, with Fabric version predicates. Dependencies start first and stop last.
- `name`, `description`, `authors` (optional, unreleased menu): display name, description string and an array of author names for the DUM-SDK Modifications page. Omitted names fall back to `id`; missing descriptions/authors are not invented. Unsupported presentation shapes are ignored and do not stop an otherwise valid legacy module. Descriptions are displayed verbatim, so use the language intended for players.
- `required` (default `true`): without the module the player would play by other rules. If it does not start, the server refuses the join; if it fails in game, the player is disconnected with the reason. Set `false` only for modules that change nothing in the game, such as pure cosmetics.

### Lifecycle and threads

`DumModule.onEnable(DumContext)` runs on the render thread during configuration, after its jar is installed and before the player enters the world. `onDisable()` runs when the player leaves or the game closes. Each module has its own class loader; after `onDisable` the SDK removes everything registered through the context and drops the loader. In a development client the log says whether the module was fully unloaded: if not, something still holds a reference to its classes (a listener registered directly with Fabric, a thread, a static field of a game class).

The client accepts a catalog only after a compatible WELCOME on that same connection. Registration happens during installation; callbacks become active only after installation succeeds and the player enters the server. Disconnect deactivates them immediately, before resource cleanup. A new connection gets a new installer; old downloads and queued startup callbacks cannot start modules on the next server. Cached jars alone never enable a module. Ordinary servers and singleplayer have no active SDK session.

Unless stated otherwise, callbacks run on the render thread.

A module that throws anywhere the SDK calls it is turned off and the Errors tab in Settings > DUM-SDK shows the error (`/sdk` opens the menu). If it is required (or a required module depends on it), the player is disconnected from the server; otherwise the player gets a toast and the other modules keep running.

### DumContext

| Method | What it gives |
|---|---|
| `id()`, `logger()`, `dataDir()` | The module's id, a logger, a folder for files that survive restarts |
| `active()` | Whether this exact context is enabled in the current play session |
| `onClose(Runnable)` | Cleanup on unload, in reverse registration order |
| `onTick(Runnable)` | End of every client tick |
| `hud(HudElement)` | Drawing over the HUD |
| `screen(ScreenLayer)` | Drawing on any game screen (after its background) and taking mouse clicks |
| `equipmentAppearance(EquipmentAppearance)` | Select the item rendered by the armor or wings pass; does not change actual equipment |
| `item(ItemDefinition)` | Register a session-local item model and default name for a tagged vanilla carrier |
| `key(name, defaultKey, onPress)` | A key in Controls under DUM; title from the lang key `key.<module>.<name>`; bindings are kept |
| `addEntity(Entity)` | A client-only entity, such as a display entity; gone with the level, check `isRemoved()` |
| `on(Event, listener)` | Any Fabric API event: world rendering, input, interaction, lifecycle... |
| `onReceive(Class, Predicate)` | Every packet of that type from the server, before the game handles it; return false to drop it |
| `onSend(Class, Predicate)` | Every packet of that type the client sends; return false to drop it (called on the sending thread) |
| `onServerMessage(Consumer<byte[]>)`, `sendToServer(byte[])` | Talking to the module's server side |

`Screens.containerBounds(screen)` gives the window of a container screen. A module may also use any game or Fabric class directly; then cleaning up is its own job.

### Equipment and item presentation (SDK 0.14.0)

`equipmentAppearance` receives an `EquipmentRenderLayer` (`ARMOR` or `WINGS`) and the equipped stack. Return that stack unchanged to pass through, `ItemStack.EMPTY` to hide that pass, or another stack for the vanilla layer to draw. Do not mutate the input. Handlers run in module dependency order and are removed when the module stops. The SDK's permanent mixins pass vanilla values through outside an active session.

`context.item(new ItemDefinition("token", modelIdentifier, displayName))` registers `<module id>:token`. On Paper, `ModuleItems.create(moduleId, "token", vanillaCarrier)` clones the carrier and adds the `dum-sdk:item` PDC string. `ModuleItems.id(stack)` reads it for server-side validation. The client uses the matching active module's model and default name; a server/anvil custom name is preserved. The inventory stack is not mutated by rendering. Without that module, the underlying vanilla appearance is used.

These definitions do not add native registry entries, item physics or server gameplay. The server owns the real item and validates its use. The prototype supports names and models; other properties and additional native types need separate work.

`ServerSession.active()` reports whether this connection completed handshake, installation and play join. For a cold mod use `ServerSession.active("my_mod")` instead: another SDK server may not have that mod in its catalog. `context.active()` refers to that exact context and stays false after unload, even when the same mod rejoins. Hot and cold registrations through `DumContext` are scoped automatically.

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
2. In the module's own build, run its signing/deployment task. The external example projects provide `./gradlew :<project>:deploy`, which signs the jar and copies it with its `.sig` there.
3. `/dumsdk reload` on the server publishes the staged jars and disconnects online players with a message to rejoin. Rejoining players download changed modules once and then use the cache. A whole Fabric mod still needs a game restart after the rejoin. Until publication, even a Paper restart keeps using the last published catalog. On the first startup after upgrading, the existing `modules` folder becomes the initial published catalog. If a staged jar is invalid, publication fails and the old catalog stays active.

## Fabric mods (cold load)

Starting with SDK 0.14.0, server-delivered Fabric mods must implement the cooperative server-scoped contract. An ordinary third-party Fabric mod is not automatically made server-scoped. Both publication and the client reject jars without this metadata:

```json
{
  "schemaVersion": 1,
  "id": "my_mod",
  "version": "1.0.0",
  "environment": "client",
  "entrypoints": {"dum-sdk:server": ["example.MyServerMod"]},
  "depends": {"dum-sdk": ">=0.14.0", "minecraft": "~26.2", "fabricloader": ">=0.19.5"}
}
```

The SDK dependency must require 0.14.0 or newer: v0.13.0 does not provide this API.

Declare exactly one `dum-sdk:server` entrypoint implementing `DumServerMod`. Other entrypoints (including `main`, `client` and `preLaunch`) are refused. Mixins are allowed. The SDK verifies the signature, dependencies, metadata, conflicts and the loaded jar's hash against the catalog. A missing or changed wanted mod requires a restart; the helper installs `mods/dum-cold-<id>.jar` after exit. Upgrade legacy cold mods before upgrading a published catalog; legacy jars will fail validation.

`DumServerMod` has the same `onEnable(DumContext)` and `onDisable()` lifecycle as a hot module, but Fabric owns its class loader. Preparation happens on the render thread after verified configuration; managed callbacks remain passive until play join. The SDK creates a fresh context for each connection, clears its registrations on leave, and invokes `onDisable` and `context.onClose` cleanup. All cold mods are required: entrypoint startup failure refuses joining, and callback failure disconnects rather than silently changing gameplay rules. Fabric can reuse an entrypoint instance, so reset per-session state in `onEnable`.

Every permanent mixin must guard its effect with `ServerSession.active("my_mod")` and leave vanilla behavior unchanged when false. For asynchronous work retain `context.active()` instead; a check by ID alone cannot distinguish an old connection from a new one. Use `context.onClose(cleanup)` for screens, sounds, tasks or state created outside managed SDK APIs. Cleanup runs once, in reverse registration order, after the context has become inactive; one throwing cleanup does not prevent subsequent cleanup callbacks. Do not let asynchronous work mutate the game after its context closes.

Classes, mixins and native registries remain loaded for the JVM lifetime. The contract is a development rule for signed, reviewed code, not a Java sandbox: metadata cannot prove that a mixin, static initializer or direct Fabric listener obeys it. Native mod assets also remain available through Fabric; keep assets namespaced and do not replace vanilla/global assets. Native registered types must not introduce effects outside the mod's accepted session. Use hot module resource packs and runtime item definitions for content that must be physically removed on leave.

Connecting to an ordinary server or singleplayer never enables a cold mod. Connecting to an SDK server whose catalog lacks a compliant loaded mod leaves it inactive and schedules its file for removal after exit, without requiring an immediate restart. Returning to a server that wants the same still-loaded jar cancels that removal plan. Legacy loaded mods absent from a catalog still require a restart, since they have no scoped lifecycle.

## Development

The Paper plugin is standalone starting with 0.14.1: build it without DUM-Core or `dumCorePath`. Published v0.14.0 still requires DUM-Core. For a fixture-free integration test, run Paper with only the checkout's SDK jar, an empty catalog and operator `Player0`, then use `./gradlew :dum-fabric:runClientGameTest -PstandaloneTest -PtestServer=localhost:25568`.

- `./gradlew :dum-fabric:runClient -Pjoin=localhost` starts a dev client and joins a local server. Dev clients also accept modules signed with the dev key.
- `-Prelease` makes the dev client refuse dev-signed modules, like a player's client.
- `-Psodium`, `-Piris` add Sodium and Iris; shader packs go into `dum-fabric/run/shaderpacks`.
- The external `DUM-SDK-Examples` projects provide `sdktest` for rendering, events, packets and failures, and `dumcoldtest` for cold load. The SDK game test requires the external `info` and `sdktest` 0.4.0+ jars and `DUM-Runtime-Test` Paper fixture on its test server.
- Settings > DUM-SDK (`/sdk` shortcut, unreleased): modules, time they take, Fabric mods from the server, pending restarts, updater status, persistent error codes and local crash/log files.
- `./gradlew :dum-fabric:runClientGameTest` joins a running test server and checks the SDK end to end; a module's own checks can follow the same pattern (`dum-fabric/src/gametest`).
- Add `-PplainServer=localhost:25566` with a second Paper server without plugins to check that session effects remain off after switching from the SDK server to an ordinary one.

Cold session integration tests use the external `dumcoldtest` 0.4.0+ fixture, with SDK 0.14.0 or newer built and installed on the main test server. Use a persistent game directory outside `build/run/clientGameTest` (the test task deletes its default directory before each run):

1. `./gradlew :dum-fabric:runClientGameTest -PcoldInstallTest -PtestServer=localhost:25565 -PgameDir=/absolute/path/to/cold-client`: checks restart refusal and the helper install plan. Wait for `dum-sdk/cold-apply.log` to report `done` after the game exits.
2. `./gradlew :dum-fabric:runClientGameTest -PcoldTest -PtestServer=localhost:25565 -PotherSdkServer=localhost:25567 -PplainServer=localhost:25566 -PgameDir=/absolute/path/to/cold-client`: checks the actual cold mixin, reconnect, old context invalidation, cleanup, a second SDK server without this mod, a server without plugins, singleplayer and required-mod failure cleanup. The second SDK server must publish an empty catalog.
