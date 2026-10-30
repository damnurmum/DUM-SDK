package ru.dum.sdk.network;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerModContractTest {
    private static String validate(String json) {
        return ServerModContract.entrypoint(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void acceptsOnlyTheExplicitScopedEntrypointAndSdkDependency() {
        assertEquals("example.ServerMod", validate("""
                {"entrypoints":{"dum-sdk:server":["example.ServerMod"]},"depends":{"dum-sdk":"*"}}
                """));
    }

    @Test
    void refusesLegacyInitializersMissingDependenciesAndAmbiguousEntrypoints() {
        for (String invalid : new String[] {
                "{}",
                "{\"entrypoints\":{\"dum-sdk:server\":[\"X\"]}}",
                "{\"entrypoints\":{\"client\":[\"X\"]},\"depends\":{\"dum-sdk\":\"*\"}}",
                "{\"entrypoints\":{\"dum-sdk:server\":[\"X\"],\"main\":[\"Boot\"]},\"depends\":{\"dum-sdk\":\"*\"}}",
                "{\"entrypoints\":{\"dum-sdk:server\":[]},\"depends\":{\"dum-sdk\":\"*\"}}",
                "{\"entrypoints\":{\"dum-sdk:server\":[\"X\",\"Y\"]},\"depends\":{\"dum-sdk\":\"*\"}}",
                "{\"entrypoints\":{\"dum-sdk:server\":[false]},\"depends\":{\"dum-sdk\":\"*\"}}",
                "{\"entrypoints\":{\"dum-sdk:server\":[\" \"]},\"depends\":{\"dum-sdk\":\"*\"}}"
        }) assertThrows(IllegalArgumentException.class, () -> validate(invalid), invalid);
    }
}
