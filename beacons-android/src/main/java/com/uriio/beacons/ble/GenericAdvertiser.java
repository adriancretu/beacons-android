package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseData;
import android.os.Build;

import com.uriio.beacons.model.Beacon;

/**
* Generic BLE advertiser
*/
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class GenericAdvertiser extends Advertiser {
    private final AdvertiseData mAdvertiseData;
    private AdvertiseData mAdvertiseScanResponse = null;
    private String mAdvertiseName;

    /**
     * Creates a BLE advertiser. It does not start any actual transmission.
     * @param advertisersManager     BLE Advertisers manager, or null for none.
     * @param advertiseMode          BLE advertise mode
     * @param txPowerLevel           BLE TX power level
     */
    public GenericAdvertiser(AdvertiseData advertiseData, AdvertiseData scanResponse,
            AdvertisersManager advertisersManager,
            @Beacon.AdvertiseMode int advertiseMode,
            @Beacon.AdvertiseTxPower int txPowerLevel,
            boolean connectable, String advertiseName)
    {
        super(advertisersManager, advertiseMode, txPowerLevel, connectable);

        mAdvertiseData = advertiseData;
        mAdvertiseScanResponse = scanResponse;
        mAdvertiseName = advertiseName;
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
        if (null != mAdvertiseData && mAdvertiseData.getIncludeDeviceName()
                || null != mAdvertiseScanResponse && mAdvertiseScanResponse.getIncludeDeviceName()) {
            return null == mAdvertiseName ? Build.MODEL : mAdvertiseName;
        }

        return null;
    }
}