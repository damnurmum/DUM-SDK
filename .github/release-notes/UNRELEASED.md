# Unreleased

## Releases

- Name the client mod in releases and builds `DUM-SDK-Fabric-X.Y.Z.jar` (with `DUM-SDK-Fabric-X.Y.Z.jar.sig`) next to `DUM-SDK-Paper-X.Y.Z.jar`, and label the release files "Fabric mod (client)" and "Paper plugin (server)". The self-updater looks for the new name and installs the update into `mods/` under it. Clients up to 0.15.1 look for `DUM-SDK.jar` and do not update themselves to this release.
