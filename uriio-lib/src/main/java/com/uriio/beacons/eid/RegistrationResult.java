package com.uriio.beacons.eid;

/**
 * Holds an successful EID registration result.
 * Created on 5/22/2016.
 */

public class RegistrationResult {

    private byte[] identityKey;
    private int timeOffset;

    public RegistrationResult(byte[] identityKey, int timeOffset) {
        this.identityKey = identityKey;
        this.timeOffset = timeOffset;
    }

    public byte[] getIdentityKey() {
        return identityKey;
    }

    public int getTimeOffset() {
        return timeOffset;
    }
}
