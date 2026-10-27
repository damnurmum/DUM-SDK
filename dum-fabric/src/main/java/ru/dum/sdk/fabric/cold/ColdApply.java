package ru.dum.sdk.fabric.cold;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A separate process that changes mods/ after the game has exited: a running game holds its mod jars open,
 * and Windows lets nobody replace or delete them until then. Uses nothing but the JDK.
 * Arguments: the game's process id and the plan file, one operation per line:
 * {@code copy<TAB>from<TAB>to} or {@code delete<TAB>path}.
 */
public final class ColdApply {

    private ColdApply() {
    }

    public static void main(String[] args) throws Exception {
        long gamePid = Long.parseLong(args[0]);
        Path plan = Path.of(args[1]);
        ProcessHandle.of(gamePid).ifPresent(game -> game.onExit().join());
        Thread.sleep(1000);
        if (!Files.exists(plan)) return;
        List<String> lines = Files.readAllLines(plan, StandardCharsets.UTF_8);
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(plan.resolveSibling("cold-apply.log"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            log.println(LocalDateTime.now() + " applying " + plan);
            for (String line : lines) {
                if (line.isBlank()) continue;
                String[] op = line.split("\t");
                for (int attempt = 1; ; attempt++) {
                    try {
                        apply(op);
                        log.println("  done: " + line);
                        break;
                    } catch (IOException e) {
                        // An antivirus or a slow exit may still hold the file
                        if (attempt == 10) {
                            log.println("  failed: " + line + ": " + e);
                            break;
                        }
                        Thread.sleep(1000);
                    }
                }
            }
        }
        Files.deleteIfExists(plan);
    }

    private static void apply(String[] op) throws IOException {
        switch (op[0]) {
            case "copy" -> {
                // Through a file Fabric ignores, so a crash never leaves half a jar in mods/
                Path to = Path.of(op[2]);
                Path temp = to.resolveSibling(to.getFileName() + ".part");
                Files.copy(Path.of(op[1]), temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            case "delete" -> Files.deleteIfExists(Path.of(op[1]));
            default -> throw new IllegalArgumentException("Unknown operation " + op[0]);
        }
    }
}
