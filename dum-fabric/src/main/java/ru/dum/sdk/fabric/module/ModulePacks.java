package ru.dum.sdk.fabric.module;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/**
 * The assets/ of running modules as client resource packs: always on, on top, not movable in the pack screen,
 * like a server pack. A module jar is the pack itself; the SDK supplies the metadata, so no pack.mcmeta is needed.
 */
final class ModulePacks implements RepositorySource {

    private static final PackSelectionConfig SELECTION = new PackSelectionConfig(true, Pack.Position.TOP, true);

    /** Module id to its jar, for modules with assets. Render thread only. */
    private Map<String, Path> packs = Map.of();
    private boolean installed;

    @Override
    public void loadPacks(Consumer<Pack> consumer) {
        packs.forEach((id, jar) -> {
            Component title = Component.literal("DUM-SDK: " + id);
            consumer.accept(new Pack(
                    new PackLocationInfo("dum-sdk/" + id, title, PackSource.SERVER, Optional.empty()),
                    new FilePackResources.FileResourcesSupplier(jar),
                    new Pack.Metadata(title, PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of()),
                    SELECTION));
        });
    }

    /** Switches to the packs of these modules and reloads resources if that changes anything. */
    CompletableFuture<Void> set(Map<String, Path> modules) {
        Map<String, Path> next = Map.copyOf(modules);
        if (next.equals(packs)) return CompletableFuture.completedFuture(null);
        install();
        packs = next;
        return Minecraft.getInstance().reloadResourcePacks();
    }

    /** Adds this source to the game's pack repository, once. The repository does not take sources after creation. */
    private void install() {
        if (installed) return;
        PackRepository repository = Minecraft.getInstance().getResourcePackRepository();
        Set<RepositorySource> sources = new LinkedHashSet<>(repository.sources);
        sources.add(this);
        repository.sources = sources;
        installed = true;
    }

    static boolean hasAssets(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream().anyMatch(entry -> entry.getName().startsWith("assets/"));
        } catch (IOException e) {
            return false;
        }
    }
}
