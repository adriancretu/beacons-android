package com.uriio.beacons;

/**
 * Created on 5/21/2016.
 */
public class EddystoneEIDSpec extends BeaconSpec {
    private byte[] identityKey;
    private byte rotationExponent;
    private int timeOffset;

    /**
     * EID spec.
     * @param identityKey         16-byte Identity Key
     * @param rotationExponent    EID rotation exponent (0 to 15)
     * @param timeOffset          Time offset to actual current timestamp, in seconds
     * @param mode                BLE transmit mode
     * @param txPowerLevel        BLE power level
     * @param name                Optional beacon name
     */
    public EddystoneEIDSpec(byte[] identityKey, byte rotationExponent, int timeOffset,
                            @AdvertiseMode int mode,
                            @AdvertiseTxPower int txPowerLevel, String name) {
        super(EDDYSTONE_EID, mode, txPowerLevel, name);
        this.identityKey = identityKey;
        this.rotationExponent = rotationExponent;
        this.timeOffset = timeOffset;
    }

    public byte[] getIdentityKey() {
        return identityKey;
    }

    public byte getRotationExponent() {
        return rotationExponent;
    }

    public int getTimeOffset() {
        return timeOffset;
    }
}