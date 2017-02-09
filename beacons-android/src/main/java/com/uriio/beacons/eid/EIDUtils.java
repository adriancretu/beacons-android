package com.uriio.beacons.eid;

import android.annotation.SuppressLint;
import android.support.annotation.NonNull;

import com.uriio.beacons.Util;

import org.whispersystems.curve25519.Curve25519;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

/**
 * EID helper methods, mainly crypto.
 * Created on 4/29/2016.
 */
public class EIDUtils {

    /**
     * Computes an Ephemeral ID.
     * @param key                 AES key (Advertiser Identity Key). The first 16 bytes are used.
     * @param timeCounter         Advertiser time counter
     * @param rotationExponent    Advertiser rotation exponent (0 to 15)
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
        @SuppressLint("GetInstance")  // spec says it has to be ECB, ignore lint warning
        Cipher aes = Cipher.getInstance(transformation);
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, 0, 16, "AES"));

        byte[] tempKey = aes.doFinal(new byte[] {
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xff,
                0x00, 0x00,
                (byte) ((timeCounter >>> 24) & 0xff),
                (byte) ((timeCounter >>> 16) & 0xff)
        });

        // clear K lowest bits
        timeCounter = timeCounter >>> rotationExponent << rotationExponent;

        // reset cipher with a new encryption key
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(tempKey, "AES"));
        return aes.doFinal(new byte[]{
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
     * @param beaconPrivateKey    Advertiser private key
     * @return Shared secret between server and client
     */
    public static byte[] computeSharedSecret(byte[] serverPublicKey, byte[] beaconPrivateKey) {
        // this should yield the exact same result as in EIDResolver.registerBeacon
//        Util.log("Server public key: " + Util.binToHex(serverPublicKey));
//        Util.log("Advertiser private key: " + Util.binToHex(beaconPrivateKey));
        return Curve25519.getInstance(Curve25519.BEST).calculateAgreement(serverPublicKey, beaconPrivateKey);
    }

    public static byte[] computeIdentityKey(byte[] sharedSecret, byte[] serverPublicKey,
                                            byte[] beaconPublicKey) throws InvalidKeyException, NoSuchAlgorithmException {
        if (Util.isZeroBuffer(sharedSecret)) {
            throw new InvalidKeyException("Shared secret is zero");
        }

        byte[] salt = new byte[serverPublicKey.length + beaconPublicKey.length];
        System.arraycopy(serverPublicKey, 0, salt, 0, serverPublicKey.length);
        System.arraycopy(beaconPublicKey, 0, salt, serverPublicKey.length, beaconPublicKey.length);

        Mac mac = Mac.getInstance("hmacSHA256");

        // hkdf extract
        mac.init(new SecretKeySpec(salt, "hmacSHA256"));
        byte[] pseudoRandomKey = mac.doFinal(sharedSecret);

        // hkdf expand
        mac.reset();
        mac.init(new SecretKeySpec(pseudoRandomKey, "hmacSHA256"));

        return mac.doFinal(new byte[]{1});
    }

    /**
     * Atempts EID beacon registration.
     * @param eidServer           EID server
     * @param publicKey           Advertiser public key
     * @param privateKey          Advertiser private key
     * @param rotationExponent    EID rotation exponent (0 to 15)
     * @return  Result of registration, or null if registration failed.
     * @throws GeneralSecurityException
     */
    public static RegistrationResult register(EIDResolver eidServer, byte[] publicKey,
                                              byte[] privateKey, byte rotationExponent) throws GeneralSecurityException {
        RegisterParams registerParams = eidServer.queryRegistrationParams();
        if (null == registerParams) return null;

        byte[] sharedSecret = computeSharedSecret(registerParams.publicKey, privateKey);
        byte[] identityKey = computeIdentityKey(sharedSecret, registerParams.publicKey, publicKey);

        int now = (int) (System.currentTimeMillis() / 1000);
        // https://github.com/google/eddystone/blob/master/eddystone-eid/eid-computation.md#implementation-guidelines
        int timeCounter = now & ~0xffff | 65280;

        // save the offset between current time and time counter so we can restore correctly
        int timeOffset = now - timeCounter;

        byte[] firstEID = computeEID(identityKey, timeCounter, rotationExponent);
        if (!eidServer.registerBeacon(publicKey, rotationExponent, timeCounter, firstEID)) {
            return null;
        }

        return new RegistrationResult(identityKey, timeOffset);
    }
}
