package com.uriio.beacons.eid;

import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import java.security.GeneralSecurityException;

/**
 * Local EID service provider.
 * Created on 4/16/2016.
 */
public class LocalEIDResolver implements EIDResolver {
    private final Curve25519 mEC;
    private Curve25519KeyPair mKeyPair;

    public LocalEIDResolver() {
        mEC = Curve25519.getInstance(Curve25519.BEST);

        // fixme - read keypair from storage
        mKeyPair = mEC.generateKeyPair();
    }

    public Curve25519KeyPair generateKeyPair() {
        return mEC.generateKeyPair();
    }

    @Override
    public RegisterParams queryRegistrationParams() {
        RegisterParams registerParams = new RegisterParams();

        registerParams.publicKey = getPublicKey();
        registerParams.minRotationScale = 0;
        registerParams.maxRotationScale = 15;

        return registerParams;
    }

    /**
     * Server-side beacon registration
     * @param beaconPublicKey    Advertiser public key
     * @param rotationExponent   Advertiser time rotation scaler
     * @param timeCounter        Advertiser time counter
     * @param ephemeralId        Advertiser Ephemeral ID
     * @return Registration result
     */
    @Override
    public boolean registerBeacon(byte[] beaconPublicKey, byte rotationExponent, int timeCounter, byte[] ephemeralId) {
        // this should yield the exact same result as EIDUtils.computeSharedSecret from the beacon's perspective
        byte[] sharedSecret = mEC.calculateAgreement(beaconPublicKey, mKeyPair.getPrivateKey());
        byte[] identityKey;
        try {
            identityKey = EIDUtils.computeIdentityKey(sharedSecret, mKeyPair.getPublicKey(), beaconPublicKey);
        } catch (GeneralSecurityException e) {
            return false;
        }

        byte[] eid;
        try {
            eid = EIDUtils.computeEID(identityKey, timeCounter, rotationExponent);
        } catch (GeneralSecurityException e) {
            return false;
        }

        // validate that the received EID is correct
        for (int i = 0; i < 8; i++) {
            if (eid[i] != ephemeralId[i]) return false;
        }

        // registration checks passed

        // FIXME: 4/16/2016 Register EID... somehow.... maybe set-up a local http server?
        // ...add eid to a lookup db?! what should THAT return on lookup? the public key?

        return true;
    }

    byte[] getPublicKey() {
        return mKeyPair.getPublicKey();
    }
}