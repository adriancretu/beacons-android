package com.uriio.beacons.model;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseSettings;
import android.os.Build;
import android.support.annotation.IntDef;

import com.uriio.beacons.Beacons;
import com.uriio.beacons.BleService;
import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.ble.AdvertisersManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base container for an item.
 */
public abstract class Beacon {
    public static final int STATUS_STOPPED          = 0;
    public static final int STATUS_UPDATING         = 1;
    public static final int STATUS_UPDATE_FAILED    = 2;
    public static final int STATUS_ADVERTISING      = 3;
    public static final int STATUS_ADVERTISE_PAUSED = 4;
    public static final int STATUS_ADVERTISE_FAILED = 5;
    public static final int STATUS_NO_BLUETOOTH     = 6;

    // Eddystone types match to stored frame types - do not modify
    public static final int EDDYSTONE_URL = 0;
    public static final int EDDYSTONE_UID = 1;
    public static final int EDDYSTONE_EID = 2;

    public static final int IBEACON       = 3;
    public static final int EPHEMERAL_URL = 4;

    private static final String[] _statusName = {
        "Stopped", "Updating", "Update error", "Running", "Paused", "Failed", "Bluetooth OFF"
    };

    /** Associated BLE object **/
    private Advertiser mAdvertiser;

    /** local id for DB persistence **/
    private long mId = 0;
    private String mName = null;

    private int mFlags;

    private int mAdvertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED;
    private int mTxPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;

    /** Current status **/
    private int mStatus = STATUS_STOPPED;

    // fixme - merge with state
    private int mStorageState;

    private boolean mConnectable = false;

    private final int mType;

    /*
    * @param mode                 BLE mode
    * @param txPowerLevel         BLE TX power level
    * @param mName                 Optional mName
    */
    public Beacon(long itemId, int type,
                  @AdvertiseMode int advertiseMode,
                  @AdvertiseTxPower int txPowerLevel, int flags, String name) {
        mId = itemId;
        mType = type;
        mFlags = flags;
        mAdvertiseMode = advertiseMode;
        mTxPowerLevel = txPowerLevel;
        mName = name;
    }

    public Beacon(int type,
                  @AdvertiseMode int advertiseMode,
                  @AdvertiseTxPower int txPowerLevel, int flags, String name) {
        this(0, type, advertiseMode, txPowerLevel, flags, name);
    }

    public Beacon(int type,
                  @AdvertiseMode int advertiseMode,
                  @AdvertiseTxPower int txPowerLevel, int flags) {
        this(0, type, advertiseMode, txPowerLevel, flags, null);
    }

    public Beacon(int type, int flags, String name) {
        mType = type;
        mFlags = flags;
        mName = name;
    }

    public Beacon(int type, int flags) {
        this(type, flags, null);
    }

    public void setStorageState(int state) {
        mStorageState = state;
    }

    public int getStorageState() {
        return mStorageState;
    }

    public long getId() {
        return mId;
    }

    public Advertiser getAdvertiser() {
        return mAdvertiser;
    }

    protected Advertiser setBeacon(Advertiser advertiser) {
        mAdvertiser = advertiser;
        return advertiser;
    }

    @AdvertiseMode
    public int getAdvertiseMode() {
        return mAdvertiseMode;
    }

    @AdvertiseTxPower
    public int getTxPowerLevel() {
        return mTxPowerLevel;
    }

    public int getFlags() {
        return mFlags;
    }

    public int getStatus() {
        return mStatus;
    }

    public void setStatus(int status) {
        mStatus = status;
    }

    public String getStatusDescription() {
        return _statusName[mStatus];
    }

    public abstract Advertiser createBeacon(AdvertisersManager advertisersManager);

    public abstract int getKind();

    public int getType() {
        return mType;
    }

    public String getName() {
        return mName;
    }

    public void clearBeacon() {
        mAdvertiser = null;
    }

    public long getScheduledRefreshTime() {
        return 0;
    }

    /**
     * Called when the item should start advertising a new BLE beacon.
     * Default implementation starts a new beacon; subclasses may override with other behaviour.
     * @param service    BLE Service
     */
    public void onAdvertiseEnabled(BleService service) {
        // (re)create the beacon
        service.startItemAdvertising(this);
    }

    public boolean isConnectable() {
        return mConnectable;
    }

    public Editor edit() {
        return new Editor();
    }

    /**
     * Sets the persistent item ID. Has no effect if the item already has an ID.
     * @param id    The item ID
     */
    public void setId(long id) {
        if (0 == mId) {
            mId = id;
        }
    }

    // Some ugly decorator definitions...
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
            AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    })
    public @interface AdvertiseMode {}

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW,
            AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
            AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
            AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
    })
    public @interface AdvertiseTxPower {}

    public class Editor<T> {
        protected boolean mRestartBeacon = false;

        public Editor<T> setAdvertiseMode(@AdvertiseMode int mode) {
            if (mode != mAdvertiseMode) {
                mAdvertiseMode = mode;
                mRestartBeacon = true;
            }
            return this;
        }

        public Editor<T> setAdvertiseTxPower(@AdvertiseTxPower int txPowerLevel) {
            if (txPowerLevel != mTxPowerLevel) {
                mTxPowerLevel = txPowerLevel;
                mRestartBeacon = true;
            }
            return this;
        }

        public Editor<T> setConnectable(boolean connectable) {
            if (connectable != mConnectable) {
                mConnectable = connectable;
                mRestartBeacon = true;
            }
            return this;
        }

        public Editor<T> setName(String name) {
            if (null == name || null == mName || !name.equals(mName)) {
                mName = name;
            }
            return this;
        }

        // internal use - sets custom beacon modifier flags to alter advertised data or behaviour
        protected Editor<T> setFlags(int flags) {
            if (mFlags != flags) {
                mFlags = flags;
                mRestartBeacon = true;  // ?...
            }
            return this;
        }

        public void apply() {
            Beacons.saveItem(Beacon.this, mRestartBeacon);
        }
    }
}