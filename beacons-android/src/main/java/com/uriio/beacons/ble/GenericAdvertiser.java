package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseData;
import android.os.Build;

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
     */
    public GenericAdvertiser(SettingsProvider provider, AdvertiseData advertiseData,
                             AdvertiseData scanResponse, String advertiseName) {
        super(provider);

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