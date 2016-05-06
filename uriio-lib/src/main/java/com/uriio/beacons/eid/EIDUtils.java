package com.uriio.beacons.eid;

import android.support.annotation.NonNull;

import org.whispersystems.curve25519.Curve25519;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

/**
 * EID helper methods, mainly crypto.
 * Created on 4/29/2016.
 */
public class EIDUtils {

    /**
     * Computes an Ephemeral ID.
     * @param key                 AES key (Beacon Identity Key)
     * @param timeCounter         Beacon time counter
     * @param rotationExponent    Beacon rotation exponent (0 to 15)
     * @return Final ephemeral key of 16 bytes, of which only the first 8 bytes should be used.
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws BadPaddingException
     * @throws IllegalBlockSizeException
     */
    @NonNull
    public static byte[] computeEID(byte[] key, int timeCounter, byte rotationExponent) throws GeneralSecurityException {
//        String transformation = "AES/CBC/PKCS5Padding";
        String transformation = "AES/ECB/NoPadding";
        Cipher aes = Cipher.getInstance(transformation);
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));

        byte[] tempKey = aes.doFinal(new byte[] {
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xff,
                0x00, 0x00,
                (byte) ((timeCounter >>> 24) & 0xff),
                (byte) ((timeCounter >>> 16) & 0xff)
        });

        // clear K lowest bits
        // FIXME: 4/16/2016 check to see what happens if we try to shift with more than 63 bits. The Universe should implode.
        timeCounter = timeCounter >>> rotationExponent << rotationExponent;

        // reset cipher with a new encryption key
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(tempKey, "AES"));
        return aes.doFinal(new byte[] {
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                rotationExponent,
                (byte) ((timeCounter >>> 24) & 0xff),
                (byte) ((timeCounter >>> 16) & 0xff),
                (byte) ((timeCounter >>> 8) & 0xff),
                (byte) (timeCounter & 0xff)
        });
    }

    /**
     * Client-side shared-secret agreement
     * @param beaconPrivateKey    Beacon private key
     * @return Shared secret between server and client
     */
    public static byte[] computeSharedKey(byte[] serverPublicKey, byte[] beaconPrivateKey) {
        // this should yield the exact same result as in EIDResolver.registerBeacon
        return Curve25519.getInstance(Curve25519.BEST).calculateAgreement(serverPublicKey, beaconPrivateKey);
    }
}
