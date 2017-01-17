package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseData;
import android.os.Build;
import android.os.ParcelUuid;

import com.uriio.beacons.ble.gatt.EddystoneGattService;

/**
* Advertise as an Eddystone beacon
*/
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class EddystoneAdvertiser extends Advertiser {
    // 16-bit 0xFEAA expanded to a BLE 128-bit UUID
    private static final ParcelUuid EDDYSTONE_SERVICE_UUID = parcelUuidFromShortUUID(0xFEAA);

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
     */
    public EddystoneAdvertiser(SettingsProvider provider, byte frameType, byte[] frameData,
                               int pos, int len)
    {
        super(provider);

        mServiceData = new byte[2 + len];

        mServiceData[0] = frameType;
        mServiceData[1] = FRAME_TLM == frameType ? 0
                : AdvertisersManager.getZeroDistanceTxPower(provider.getTxPowerLevel());
        System.arraycopy(frameData, pos, mServiceData, 2, len);

        // an advertisement packet can have at most 31 bytes
        mAdvertiseData = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(EDDYSTONE_SERVICE_UUID, mServiceData)
                .addServiceUuid(EDDYSTONE_SERVICE_UUID)
                .build();

        if (provider.isConnectable()) {
            mAdvertiseScanResponse = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .setIncludeTxPowerLevel(false)  // allows 3 more bytes for device name
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

    @Override
    public String getAdvertisedLocalName() {
        if (null != mAdvertiseScanResponse && mAdvertiseScanResponse.getIncludeDeviceName()) {
            // fix device name so it fits into the scan record
            int maxNameLen = 8 + 3;  // we've got 3 bytes more if TX is not advertised
            return Build.MODEL.length() <= maxNameLen ? Build.MODEL : Build.MODEL.substring(0, maxNameLen);
        }

        return null;
    }

    public byte[] getServiceData() {
        return mServiceData;
    }
}