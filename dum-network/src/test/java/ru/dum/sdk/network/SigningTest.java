package ru.dum.sdk.network;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SigningTest {

    @Test
    void verifiesOnlyTheSignedBytesWithTheRightKey() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        byte[] jar = "jar bytes".getBytes();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(jar);
        byte[] signature = signer.sign();

        assertTrue(Signing.verify(jar, signature, publicKey));
        assertFalse(Signing.verify("jar bytez".getBytes(), signature, publicKey));
        assertFalse(Signing.verify(jar, signature, Base64.getEncoder().encodeToString(other.getPublic().getEncoded())));
        assertFalse(Signing.verify(jar, new byte[64], publicKey));
        assertFalse(Signing.verify(jar, signature, ""));
    }
}
