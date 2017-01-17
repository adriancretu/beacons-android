package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseData;
import android.os.Build;

/**
* Advertise as an Apple iBeacon
*/
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class iBeaconAdvertiser extends Advertiser {
    public static final int FLAG_APPLE = 0;
    public static final int FLAG_ALT_BEACON = 2;

    private static final int COMPANY_ID_APPLE    = 0x004C;

    // iBeacon - 21 bytes of data follow
    private static final int IBEACON_INDICATOR   = 0x0215;
    private static final int ALTBEACON_INDICATOR = 0xBEAC;

    private final AdvertiseData mAdvertiseData;

    public iBeaconAdvertiser(SettingsProvider provider, byte[] proximityUUID, int major, int minor, int flags) {
        super(provider);

        byte measuredPower = AdvertisersManager.getZeroDistanceTxPower(provider.getTxPowerLevel());
        measuredPower -= 41;

        int indicator = FLAG_APPLE == flags ? IBEACON_INDICATOR : ALTBEACON_INDICATOR;

        byte[] manufacturerData = new byte[] {
                (byte) (indicator >>> 8),
                (byte) (indicator & 0xFF),

                // ProximityUUID, 16 bytes
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,

                // Major
                (byte) (major >>> 8), (byte) major,

                // Minor
                (byte) (minor >>> 8), (byte) minor,

                // Measured Power
                measuredPower
        };

        // set the proximity uuid bytes
        System.arraycopy(proximityUUID, 0, manufacturerData, 2, 16);

        // an advertisement packet can have at most 31 bytes; iBeacon uses 30
        mAdvertiseData = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .setIncludeDeviceName(false)
                .addManufacturerData(COMPANY_ID_APPLE, manufacturerData)
                .build();
    }

    @Override
    public AdvertiseData getAdvertiseData() {
        return mAdvertiseData;
    }
}