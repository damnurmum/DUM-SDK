# Unreleased

## Client runtime

- Drop vanilla's remembered last mouse click when a module unloads if it points at one of the module's screens. Minecraft 26.2 keeps that screen for double-click detection, which kept the unloaded module's classes in memory until the next click on another screen.
