package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseData;
import android.os.Build;
import android.os.ParcelUuid;

/**
* Advertise as an Eddystone Beacon
*/
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class EddystoneBeacon extends Beacon {
    //region Flags used for persistence - they should never be changed
    public static final int FLAG_EDDYSTONE = 0;

    public static final int FLAG_FRAME_URL = 0;
    public static final int FLAG_FRAME_UID = 1;
    public static final int FLAG_FRAME_EID = 2;
    //endregion

    // Really 0xFEAA, but Android seems to prefer the expanded 128-bit UUID version
    private static final ParcelUuid EDDYSTONE_SERVICE_UUID = ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB");

    private AdvertiseData mAdvertiseData = null;
    private final byte[] mServiceData;

    /**
     * Creates an Eddystone BLE advertiser.
     * @param data                   Service data
     * @param pos                    Service data offset
     * @param len                    Service data size
     * @param bleAdvertiseManager    BLE Advertise manager for the new beacon
     * @param advertiseMode          BLE advertise mode
     * @param txPowerLevel           BLE TX power level
     * @param flags                  Configuration flags
     */
    public EddystoneBeacon(byte[] data, int pos, int len, BLEAdvertiseManager bleAdvertiseManager, int advertiseMode, int txPowerLevel, int flags) {
        super(bleAdvertiseManager, advertiseMode, txPowerLevel);
        byte txPower = bleAdvertiseManager.getZeroDistanceTxPower(txPowerLevel);

        mServiceData = new byte[2 + len];

        switch (flags >>> 4) {
            case FLAG_FRAME_URL:
                mServiceData[0] = 0x10;
                break;
            case FLAG_FRAME_UID:
                mServiceData[0] = 0x00;
                break;
            case FLAG_FRAME_EID:
                mServiceData[0] = 0x30;
                break;
        }

        mServiceData[1] = txPower;
        System.arraycopy(data, pos, mServiceData, 2, len);

        // an advertisement packet can have at most 31 bytes
        mAdvertiseData = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .setIncludeDeviceName(false)
                .addServiceData(EDDYSTONE_SERVICE_UUID, mServiceData)
                .addServiceUuid(EDDYSTONE_SERVICE_UUID)
                .build();
    }

    @Override
    public AdvertiseData getAdvertiseData() {
        return mAdvertiseData;
    }

    public byte[] getServiceData() {
        return mServiceData;
    }
}