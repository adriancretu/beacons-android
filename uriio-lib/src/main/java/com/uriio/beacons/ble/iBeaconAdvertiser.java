package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseData;
import android.os.Build;

import com.uriio.beacons.model.Beacon;

/**
* Advertise as an Apple iBeacon
*/
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class iBeaconAdvertiser extends Advertiser {
    public static final int FLAG_APPLE = 0;
    public static final int FLAG_ALT_BEACON = 2;

    private static final int COMPANY_ID_APPLE = 0x004C;

    private final AdvertiseData mAdvertiseData;

    public iBeaconAdvertiser(AdvertisersManager advertisersManager,
                             @Beacon.AdvertiseMode int mode,
                             @Beacon.AdvertiseTxPower int txPowerLevel,
                             byte[] proximityUUID, int major, int minor, int flags, boolean connectable) {
        super(advertisersManager, mode, txPowerLevel, connectable);

        byte measuredPower = advertisersManager.getZeroDistanceTxPower(txPowerLevel);
        measuredPower -= 41;

        int indicator = 0x0215;  // flag this as an iBeacon - 21 bytes of data follow
        if (flags == FLAG_ALT_BEACON) indicator = 0xBEAC;

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