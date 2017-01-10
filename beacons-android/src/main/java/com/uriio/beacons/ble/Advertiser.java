package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Build;
import android.os.ParcelUuid;
import android.support.annotation.NonNull;

import com.uriio.beacons.Util;
import com.uriio.beacons.model.Beacon;

import java.util.UUID;

/** Base class for BLE advertisers. */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public abstract class Advertiser extends AdvertiseCallback {
    private static final String TAG = "Advertiser";

    public static final int STATUS_WAITING  = 0;
    public static final int STATUS_RUNNING  = 1;
    public static final int STATUS_FAILED   = 2;
    public static final int STATUS_STOPPED  = 3;

    private final AdvertiseSettings mAdvertiseSettings;
    private final AdvertisersManager mAdvertisersManager;

    private AdvertiseSettings mSettingsInEffect = null;
    private int mStatus = STATUS_WAITING;

    /**
     * Creates a ParcelUUID for a 16-bit or 32-bit short UUID
     * @param serviceId    Short UUID, either 16 or 32-bit
     * @return             The corresponding 128-bit parcelled UUID
     */
    @NonNull
    public static ParcelUuid parcelUuidFromShortUUID(long serviceId) {
        return new ParcelUuid(new UUID(0x1000 | (serviceId << 32), 0x800000805F9B34FBL));
    }

    public Advertiser(AdvertisersManager advertiseManager,
                      @Beacon.AdvertiseMode int advertiseMode,
                      @Beacon.AdvertiseTxPower int txPowerLevel, boolean connectable) {
        mAdvertisersManager = advertiseManager;
        mAdvertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(advertiseMode)
                .setTxPowerLevel(txPowerLevel)
                .setConnectable(connectable)
                .build();
    }

    @Override
    public void onStartSuccess(AdvertiseSettings settingsInEffect) {
        mStatus = STATUS_RUNNING;
        mSettingsInEffect = settingsInEffect;

        if (null != mAdvertisersManager) {
            mAdvertisersManager.onAdvertiserStarted(this);
        }
    }

    @Override
    public void onStartFailure(int errorCode) {
        Util.log(TAG + " Advertise start/stop failed! Error code: " + errorCode + " - " + getErrorName(errorCode));

        mStatus = STATUS_FAILED;

        if (null != mAdvertisersManager) {
            mAdvertisersManager.onAdvertiserFailed(this, errorCode);
        }
    }

    public AdvertiseSettings getAdvertiseSettings() {
        return mAdvertiseSettings;
    }

    public abstract AdvertiseData getAdvertiseData();

    public AdvertiseData getAdvertiseScanResponse() {
        return null;
    }

    public String getAdvertisedLocalName() {
        return null;
    }

    public AdvertiseSettings getSettingsInEffect() {
        return mSettingsInEffect;
    }

    public int getStatus() {
        return mStatus;
    }

    public static String getErrorName(int errorCode) {
        switch (errorCode) {
        case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
            return "ADVERTISE_FAILED_FEATURE_UNSUPPORTED";
        case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
            return "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS";
        case ADVERTISE_FAILED_ALREADY_STARTED:
            return "ADVERTISE_FAILED_ALREADY_STARTED";
        case ADVERTISE_FAILED_DATA_TOO_LARGE:
            return "ADVERTISE_FAILED_DATA_TOO_LARGE";
        case ADVERTISE_FAILED_INTERNAL_ERROR:
            return "ADVERTISE_FAILED_INTERNAL_ERROR";
        }
        return "Error " + errorCode;
    }

    public void setStoppedState() {
        mStatus = STATUS_STOPPED;
    }

    public void startAdvertising(BluetoothLeAdvertiser bleAdvertiser) {
        AdvertiseData advertiseData = getAdvertiseData();
        if (null != advertiseData) {
            try {
                bleAdvertiser.startAdvertising(getAdvertiseSettings(), advertiseData,
                        getAdvertiseScanResponse(), this);
            } catch (IllegalStateException e) {
                // tried to start advertising after Bluetooth was turned off
                // let upper level notice that BT is off instead of reporting an error
            }
        }
    }
}