package com.uriio.beacons;

import android.util.Log;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Some utility stuff without a place of its own.
 * Created on 6/14/2015.
 */
public class Util {
    /**
     * Enables Log debug output. Unfortunately BuildConfig.DEBUG does not work for library projects.
     */
    private static final boolean VERBOSE = false;

    /**
     * Hex characters used for binary to hex conversion
     */
    private static final byte[] _hexAlphabet = "0123456789abcdef".getBytes();

    public static void log(String tag, String message) {
        if (VERBOSE) {
            if (null == message) Log.e(tag, "message = <null>");
            else Log.d(tag, message);
        }
    }

    public static void log(String message) {
        log("NoTag", message);
    }

    public static byte[] hexToBin(String hexStr) {
        byte[] raw = new byte[hexStr.length() / 2];

        byte[] hexStrBytes = hexStr.getBytes();
        for (int i = 0; i < raw.length; i++) {
            byte nibble1 = hexStrBytes[i * 2], nibble2 = hexStrBytes[i * 2 + 1];
            nibble1 = (byte) (nibble1 >= 'a' ? nibble1 - 'a' + 10 : nibble1 - '0');
            nibble2 = (byte) (nibble2 >= 'a' ? nibble2 - 'a' + 10 : nibble2 - '0');

            raw[i] = (byte) ((nibble1 << 4) | nibble2);
        }

        return raw;
    }

    public static UUID binToUUID(byte[] raw) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(raw);
        return new UUID(byteBuffer.getLong(), byteBuffer.getLong());
    }

    public static String binToHex(byte[] raw) {
        return binToHex(raw, 0, raw.length);
    }

    public static String binToHex(byte[] raw, int offset, int len) {
        byte[] hex = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            hex[i * 2] = _hexAlphabet[(0xff & raw[offset + i]) >>> 4];
            hex[i * 2 + 1] = _hexAlphabet[raw[offset + i] & 0x0f];
        }
        return new String(hex);
    }

    public static byte[] computeSha1Digest(byte[] data) {
        return computeDigest(data, "SHA-1");
    }

    public static byte[] computeSha256Digest(byte[] data) {
        return computeDigest(data, "SHA-256");
    }

    private static byte[] computeDigest(byte[] data, String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
