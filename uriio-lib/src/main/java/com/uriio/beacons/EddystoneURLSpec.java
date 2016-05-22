package com.uriio.beacons;

/**
 * Created on 5/21/2016.
 */
public class EddystoneURLSpec extends BeaconSpec {
    private final String mURL;

    public EddystoneURLSpec(String url, @AdvertiseMode int mode, @AdvertiseTxPower int txPowerLevel, String name) {
        super(EDDYSTONE_URL, mode, txPowerLevel, name);
        this.mURL = url;
    }

    public String getURL() {
        return mURL;
    }
}
