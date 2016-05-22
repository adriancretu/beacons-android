package com.uriio.beacons;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseSettings;
import android.os.Build;
import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Created on 5/21/2016.
 */
public abstract class BeaconSpec {
    public static final int EDDYSTONE_EID = 0;
    public static final int EDDYSTONE_UID = 1;
    public static final int EDDYSTONE_URL = 2;
    public static final int EPHEMERAL_URL = 3;
    public static final int IBEACON       = 4;

    // Some ugly decorator definitions...
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
            AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    })
    protected @interface AdvertiseMode {}

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW,
            AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
            AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
            AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
    })
    protected @interface AdvertiseTxPower {}

    private final int mType;
    private int advertiseMode;
    private int advertiseTxPowerLevel;
    private String name;

    /*
    * @param mode                 BLE mode
    * @param txPowerLevel         BLE TX power level
    * @param name                 Optional name
    */
    public BeaconSpec(int type, @AdvertiseMode int mode, @AdvertiseTxPower int txPowerLevel,
                      String name) {
        this.mType = type;
        advertiseMode = mode;
        advertiseTxPowerLevel = txPowerLevel;
        this.name = name;
    }

    public int getType() {
        return mType;
    }

    public int getAdvertiseMode() {
        return advertiseMode;
    }

    public int getAdvertiseTxPowerLevel() {
        return advertiseTxPowerLevel;
    }

    public String getName() {
        return name;
    }
}
