package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseData;
import android.os.Build;
import android.os.ParcelUuid;

import com.uriio.beacons.ble.gatt.EddystoneGattService;
import com.uriio.beacons.model.Beacon;

/**
* Advertise as an Eddystone Advertiser
*/
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class EddystoneAdvertiser extends Advertiser {
    //region Flags used for persistence - they should never be changed
    public static final int FLAG_EDDYSTONE = 0;
    //endregion

    // Really 0xFEAA, but Android seems to prefer the expanded 128-bit UUID version
    private static final ParcelUuid EDDYSTONE_SERVICE_UUID = ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB");

    public static final byte FRAME_UID = 0x00;
    public static final byte FRAME_URL = 0x10;
    public static final byte FRAME_TLM = 0x20;
    public static final byte FRAME_EID = 0x30;

    private final AdvertiseData mAdvertiseData;
    private AdvertiseData mAdvertiseScanResponse = null;
    private final byte[] mServiceData;

    /**
     * Creates an Eddystone BLE advertiser. It does not start any actual transmission.
     * @param frameData              Frame data (without service data frame type or TX power bytes)
     * @param pos                    Frame data offset
     * @param len                    Frame data size
     * @param advertisersManager    BLE Advertise manager for the new beacon, or null for none.
     * @param advertiseMode          BLE advertise mode
     * @param txPowerLevel           BLE TX power level
     * @param flags                  Configuration flags
     */
    public EddystoneAdvertiser(byte[] frameData, int pos, int len,
                               AdvertisersManager advertisersManager,
                               @Beacon.AdvertiseMode int advertiseMode,
                               @Beacon.AdvertiseTxPower int txPowerLevel,
                               boolean connectable,
                               int flags)
    {
        super(advertisersManager, advertiseMode, txPowerLevel, connectable);
        byte txPower = AdvertisersManager.getZeroDistanceTxPower(txPowerLevel);

        mServiceData = new byte[2 + len];

        switch (flags >>> 4) {
            case Beacon.EDDYSTONE_URL:
                mServiceData[0] = FRAME_URL;
                break;
            case Beacon.EDDYSTONE_UID:
                mServiceData[0] = FRAME_UID;
                break;
            case Beacon.EDDYSTONE_EID:
                mServiceData[0] = FRAME_EID;
                break;
        }

        mServiceData[1] = txPower;
        System.arraycopy(frameData, pos, mServiceData, 2, len);

        // an advertisement packet can have at most 31 bytes
        mAdvertiseData = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .setIncludeDeviceName(false)
                .addServiceData(EDDYSTONE_SERVICE_UUID, mServiceData)
                .addServiceUuid(EDDYSTONE_SERVICE_UUID)
                .build();

        if (connectable) {
            mAdvertiseScanResponse = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .setIncludeTxPowerLevel(true)
                    .addServiceUuid(new ParcelUuid(EddystoneGattService.UUID_EDDYSTONE_GATT_SERVICE))
                    .build();
        }
    }

    @Override
    public AdvertiseData getAdvertiseData() {
        return mAdvertiseData;
    }

    @Override
    public AdvertiseData getAdvertiseScanResponse() {
        return mAdvertiseScanResponse;
    }

    public byte[] getServiceData() {
        return mServiceData;
    }
}