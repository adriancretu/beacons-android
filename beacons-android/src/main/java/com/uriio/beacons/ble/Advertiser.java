package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Build;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.util.Log;

import com.uriio.beacons.BuildConfig;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.UUID;

/** Base class for BLE advertisers. */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public abstract class Advertiser extends AdvertiseCallback {
    // Some ugly decorator definitions...
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
            AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    })
    public @interface Mode {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW,
            AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
            AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
            AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
    })
    public @interface Power {}

    public interface SettingsProvider {
        @Mode int getAdvertiseMode();
        @Power int getTxPowerLevel();
        int getTimeout();
        boolean isConnectable();
    }

    public static final int STATUS_WAITING  = 0;
    public static final int STATUS_RUNNING  = 1;
    public static final int STATUS_FAILED   = 2;
    public static final int STATUS_STOPPED  = 3;

    private static final String TAG = "Advertiser";

    /** Milliseconds between two advertisements, for each Mode */
    // todo - based on Nexus 6. Other devices may behave differently - how do we get these values?
    private static final int[] PDU_INTERVALS = { 1000, 250, 100 };

    private final AdvertiseSettings mAdvertiseSettings;
    private AdvertisersManager mAdvertisersManager = null;

    private AdvertiseSettings mSettingsInEffect = null;
    private int mStatus = STATUS_WAITING;

    private long mUnclearedPDUCount = 0;
    private long mLastPDUUpdateTime = 0;

    /**
     * Creates a ParcelUUID for a 16-bit or 32-bit short UUID
     * @param serviceId    Short UUID, either 16 or 32-bit
     * @return             The corresponding 128-bit parcelled UUID
     */
    @NonNull
    public static ParcelUuid parcelUuidFromShortUUID(long serviceId) {
        return new ParcelUuid(new UUID(0x1000 | (serviceId << 32), 0x800000805F9B34FBL));
    }

    public static int[] getPduIntervals() {
        return PDU_INTERVALS;
    }

    public Advertiser(SettingsProvider provider) {
        mAdvertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(provider.getAdvertiseMode())
                .setTxPowerLevel(provider.getTxPowerLevel())
                .setConnectable(provider.isConnectable())
                // oups! https://code.google.com/p/android/issues/detail?id=232219
//                .setTimeout(provider.getTimeout())
                .build();
    }

    @Override
    public void onStartSuccess(AdvertiseSettings settingsInEffect) {
        mStatus = STATUS_RUNNING;
        mSettingsInEffect = settingsInEffect;

        // on start or restart, rebase the clock time used for PDU count estimation
        mLastPDUUpdateTime = SystemClock.elapsedRealtime();

        if (null != mAdvertisersManager) {
            mAdvertisersManager.onAdvertiserStarted(this);
        }
    }

    @Override
    public void onStartFailure(int errorCode) {
        if(BuildConfig.DEBUG) {
            Log.d(TAG, "Start/stop failed " + errorCode + " - " + getErrorName(errorCode));
        }

        mStatus = STATUS_FAILED;

        if (null != mAdvertisersManager) {
            mAdvertisersManager.onAdvertiserFailed(this, errorCode);
        }
    }

    public void setManager(AdvertisersManager advertiseManager) {
        mAdvertisersManager = advertiseManager;
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

    /**
     * Attempt to start BLE advertising.
     * @param bleAdvertiser   BLE advertiser
     * @return  True if no exception occurred while trying to start advertising.
     */
    boolean start(BluetoothLeAdvertiser bleAdvertiser) {
        try {
            bleAdvertiser.startAdvertising(getAdvertiseSettings(), getAdvertiseData(),
                    getAdvertiseScanResponse(), this);
        } catch (IllegalStateException e) {
            // tried to start advertising after Bluetooth was turned off
            // let upper level notice that BT is off instead of reporting an error
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "start", e);
            }
            return false;
        }

        return true;
    }

    /**
     * Mark the advertiser as stopped and attempt to actually stop BLE advertisements.
     * @param bleAdvertiser    BLE advertiser, or null
     * @return  True if there was no error while trying to stop the Bluetooth advertiser.
     */
    boolean stop(BluetoothLeAdvertiser bleAdvertiser) {
        updateEstimatedPDUCount();
        mStatus = STATUS_STOPPED;

        if (null != bleAdvertiser) {
            bleAdvertiser.stopAdvertising(this);
        }

        return true;
    }

    /**
     * Updates the estimated transmitted packet data units and clears the internal counter.
     * @return  Estimated advertised PDUs since the last update (or since the advertiser started).
     */
    public long clearPDUCount() {
        updateEstimatedPDUCount();

        long pduCount = mUnclearedPDUCount;
        mUnclearedPDUCount = 0;

        return pduCount;
    }

    private void updateEstimatedPDUCount() {
        if (STATUS_RUNNING == mStatus) {
            long now = SystemClock.elapsedRealtime();
            int mode = mSettingsInEffect.getMode();
            mUnclearedPDUCount += (now - mLastPDUUpdateTime) / PDU_INTERVALS[mode];
            mLastPDUUpdateTime = now;
        }
    }
}