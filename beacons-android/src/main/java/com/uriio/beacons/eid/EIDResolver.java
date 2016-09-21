package com.uriio.beacons.eid;

/**
 * EID resolver contract methods.
 * Created on 5/4/2016.
 */
public interface EIDResolver {
    boolean registerBeacon(byte[] beaconPublicKey, byte rotationExponent, int timeCounter, byte[] ephemeralId);
    RegisterParams queryRegistrationParams();
}
