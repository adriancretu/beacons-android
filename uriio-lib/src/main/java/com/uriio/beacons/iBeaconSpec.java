package com.uriio.beacons;

/**
 * Created on 5/21/2016.
 */
public class iBeaconSpec extends BeaconSpec {
    private byte[] uuid;
    private int major;
    private int minor;

    /**
     * Apple iBeacon spec.
     */
    public iBeaconSpec(byte[] uuid, int major, int minor, @AdvertiseMode int mode,
                       @AdvertiseTxPower int txPowerLevel, String name) {
        super(IBEACON, mode, txPowerLevel, name);
        this.uuid = uuid;
        this.major = major;
        this.minor = minor;
    }

    public byte[] getUuid() {
        return uuid;
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }
}
