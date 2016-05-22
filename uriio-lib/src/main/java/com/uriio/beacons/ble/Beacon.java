package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Build;

import com.uriio.beacons.Util;

/** Base class for BLE advertisers. */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public abstract class Beacon extends AdvertiseCallback {
    private static final String TAG = "Beacon";

    public static final int STATUS_WAITING  = 0;
    public static final int STATUS_RUNNING  = 1;
    public static final int STATUS_FAILED   = 2;
    public static final int STATUS_STOPPED  = 3;

    private final AdvertiseSettings mAdvertiseSettings;
    private final BLEAdvertiseManager mAdvertisersManager;
    private AdvertiseSettings mSettingsInEffect = null;
    private int mStatus = STATUS_WAITING;

    public Beacon(BLEAdvertiseManager advertiseManager, int advertiseMode, int txPowerLevel) {
        mAdvertisersManager = advertiseManager;
        mAdvertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(advertiseMode)
                .setTxPowerLevel(txPowerLevel)
                .setConnectable(false)   // why is this true by default?
                .build();
    }

    @Override
    public void onStartSuccess(AdvertiseSettings settingsInEffect) {
        mStatus = STATUS_RUNNING;
        mSettingsInEffect = settingsInEffect;
        mAdvertisersManager.onAdvertiserStarted(this);
    }

    @Override
    public void onStartFailure(int errorCode) {
        mStatus = STATUS_FAILED;
        Util.log(TAG + " Advertise start/stop failed! Error code: " + errorCode + " - " + getErrorName(errorCode));
        mAdvertisersManager.onAdvertiserFailed(this, errorCode);
    }

    public AdvertiseSettings getAdvertiseSettings() {
        return mAdvertiseSettings;
    }

    public abstract AdvertiseData getAdvertiseData();

    public AdvertiseData getAdvertiseScanResponse() {
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
                bleAdvertiser.startAdvertising(getAdvertiseSettings(), advertiseData, getAdvertiseScanResponse(), this);
            } catch (IllegalStateException e) {
                // tried to start advertising after Bluetooth was turned off
                // let upper level notice that BT is off instead of reporting an error
            }
        }
    }
}