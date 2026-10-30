package ru.dum.sdk.network;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TransferValidationTest {
    private static Message.ModuleInfo info(String id, String version, byte[] hash, int size, byte[] signature) {
        return new Message.ModuleInfo(id, version, hash, size, signature, false);
    }

    @Test
    void rejectsAmbiguousOrInvalidCatalogsThroughTheWireDecoder() {
        var valid = info("info", "1.0.0", new byte[32], 10, new byte[64]);
        for (List<Message.ModuleInfo> catalog : Arrays.asList(
                List.of(valid, info("info", "2.0.0", hash(1), 10, new byte[64])),
                List.of(valid, info("other", "1.0.0", new byte[32], 10, new byte[64])),
                List.of(info("../escape", "1.0.0", hash(1), 10, new byte[64])),
                List.of(info("UpperCase", "1.0.0", hash(1), 10, new byte[64])),
                List.of(info("x", " ", hash(1), 10, new byte[64])),
                List.of(info("x", "1.0.0", hash(1), 0, new byte[64])),
                List.of(info("x", "1.0.0", hash(1), Protocol.MAX_MODULE_SIZE + 1, new byte[64])))) {
            assertEquals(ErrorCode.INVALID_CATALOG, assertThrows(ProtocolException.class,
                    () -> TransferValidation.catalog(catalog)).code());
        }
        // Mutate a valid serialized catalog: validation must also happen on decode, before installer allocation.
        byte[] encoded = Protocol.encode(new Message.Catalog(List.of(valid)));
        // Locate size after type, count, two modified-UTF strings and the fixed hash.
        int sizeOffset = 1 + 4 + 2 + 4 + 2 + 5 + 32;
        Arrays.fill(encoded, sizeOffset, sizeOffset + 4, (byte) 0);
        assertEquals(ErrorCode.INVALID_CATALOG, assertThrows(ProtocolException.class, () -> Protocol.decode(encoded)).code());
        byte[] invalidFlag = Protocol.encode(new Message.Catalog(List.of(valid)));
        invalidFlag[invalidFlag.length - 1] = 2;
        assertEquals(ErrorCode.INVALID_CATALOG, assertThrows(ProtocolException.class, () -> Protocol.decode(invalidFlag)).code());
    }

    @Test
    void rejectsInvalidFixedFieldsAndAllowsTheExistingSingleFileLimit() {
        assertThrows(ProtocolException.class, () -> TransferValidation.catalog(List.of(info("x", "1", new byte[31], 1, new byte[64]))));
        assertThrows(ProtocolException.class, () -> TransferValidation.catalog(List.of(info("x", "1", hash(1), 1, new byte[63]))));
        assertDoesNotThrow(() -> TransferValidation.catalog(List.of(info("x", "1", hash(1), Protocol.MAX_MODULE_SIZE, new byte[64]))));
        assertDoesNotThrow(() -> TransferValidation.catalog(List.of()));
    }

    @Test
    void installationResultsUseExplicitStableCodesAndRejectMalformedSuccess() {
        var failure = new Message.Ready(ErrorCode.SIGNATURE_MISMATCH, "info", "not signed");
        byte[] encoded = Protocol.encode(failure);
        assertEquals(6, encoded[1]);
        assertEquals(failure, Protocol.decode(encoded));
        encoded[1] = (byte) 255;
        assertEquals(ErrorCode.MALFORMED_MESSAGE, assertThrows(ProtocolException.class, () -> Protocol.decode(encoded)).code());
        assertThrows(ProtocolException.class, () -> Protocol.encode(new Message.Ready(ErrorCode.OK, "info", "")));
        assertThrows(ProtocolException.class, () -> Protocol.encode(new Message.Ready(ErrorCode.INVALID_MODULE, "", "x".repeat(Protocol.MAX_ERROR_DETAIL + 1))));
        assertEquals(Message.Ready.success(), Protocol.decode(Protocol.encode(Message.Ready.success())));
    }

    private static byte[] hash(int value) {
        byte[] result = new byte[32]; result[0] = (byte) value; return result;
    }
}
