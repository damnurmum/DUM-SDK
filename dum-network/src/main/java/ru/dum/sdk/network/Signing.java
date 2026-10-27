package ru.dum.sdk.network;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Module signatures: Ed25519 over the jar bytes. Private keys never leave the machines that sign
 * (./gradlew generateSigningKey); the public keys are here.
 */
public final class Signing {

    /** The author's key, generated on the PC (the private half is only there, in ~/.dum/signing.key). */
    private static final String RELEASE_KEY = "MCowBQYDK2VwAyEAFVj14rQPqVSmXm7AXrSOYuz86u9OrFvdqHCCne2FCEI=";

    /** A test key from the laptop. Accepted only in a development environment (Loom runClient). */
    private static final String DEV_KEY = "MCowBQYDK2VwAyEAn14ozgWDOdkBpWfFikAhlGZag4a/YwhpXpxvDnr0tZQ=";

    private Signing() {
    }

    public static boolean verify(byte[] data, byte[] signature, boolean allowDevKey) {
        return verify(data, signature, RELEASE_KEY) || (allowDevKey && verify(data, signature, DEV_KEY));
    }

    /** True when only the dev key fits, so release clients will refuse it. */
    public static boolean isDevSigned(byte[] data, byte[] signature) {
        return !verify(data, signature, RELEASE_KEY) && verify(data, signature, DEV_KEY);
    }

    static boolean verify(byte[] data, byte[] signature, String publicKey) {
        if (publicKey.isEmpty()) return false;
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
