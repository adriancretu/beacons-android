package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseData;
import android.os.Build;

/**
* Advertise as an Apple iBeacon
*/
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class AppleBeacon extends Beacon {
    public static final int FLAG_APPLE = 0;
    public static final int FLAG_ALT_BEACON = 2;

    private static final byte[] ESTIMOTE_UUID = {
            (byte) 0xB9, 0x40, 0x7F, 0x30, (byte) 0xF5, (byte) 0xF8, 0x46, 0x6E,
            (byte) 0xAF, (byte) 0xF9, 0x25, 0x55, 0x6B, 0x57, (byte) 0xFE, 0x6D
    };
    private static final byte[] KONTAKT_UUID = {
            (byte) 0xf7, (byte) 0x82, 0x6d, (byte) 0xa6, 0x4f, (byte) 0xa2, 0x4e, (byte) 0x98,
            (byte) 0x80, 0x24, (byte) 0xbc, 0x5b, 0x71, (byte) 0xe0, (byte) 0x89, 0x3e
    };

    private final AdvertiseData mAdvertiseData;

    public AppleBeacon(BLEAdvertiseManager bleAdvertiseManager, int mode, byte txPowerLevel, byte[] proximityUUID, int major, int minor, int flags) {
        super(bleAdvertiseManager, mode, txPowerLevel);

        byte measuredPower = bleAdvertiseManager.getZeroDistanceTxPower(txPowerLevel);
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
                .addManufacturerData(0x004C, manufacturerData)
                .build();
    }

    @Override
    public AdvertiseData getAdvertiseData() {
        return mAdvertiseData;
    }
}