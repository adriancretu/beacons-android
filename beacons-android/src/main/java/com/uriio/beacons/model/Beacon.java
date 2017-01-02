package com.uriio.beacons.model;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.bluetooth.le.AdvertiseSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.IntDef;
import android.support.v4.content.LocalBroadcastManager;

import com.uriio.beacons.Beacons;
import com.uriio.beacons.BleService;
import com.uriio.beacons.Receiver;
import com.uriio.beacons.Storage;
import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.ble.AdvertisersManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.UUID;

/**
 * Base container for an item.
 */
public abstract class Beacon {
    /**
     * Beacon is active and enabled.
     */
    public static final int ACTIVE_STATE_ENABLED = 0;
    /**
     * Beacon is active but paused.
     */
    public static final int ACTIVE_STATE_PAUSED  = 1;
    /**
     * Beacon is stopped. This is the default initial state.
     */
    public static final int ACTIVE_STATE_STOPPED = 2;

    // Advertise state constants that reflect current BLE status
    public static final int ADVERTISE_STOPPED       = 0;
    public static final int ADVERTISE_RUNNING       = 1;
    public static final int ADVERTISE_NO_BLUETOOTH  = 2;

    // Eddystone types match to stored frame types - do not modify
    public static final int EDDYSTONE_URL = 0;
    public static final int EDDYSTONE_UID = 1;
    public static final int EDDYSTONE_EID = 2;

    public static final int IBEACON       = 3;
    public static final int EPHEMERAL_URL = 4;

    private static final int DEFAULT_ADVERTISE_MODE = AdvertiseSettings.ADVERTISE_MODE_BALANCED;
    private static final int DEFAULT_ADVERTISE_TX_POWER = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;

    /** Associated BLE object, if any. **/
    private Advertiser mAdvertiser = null;

    private String mName = null;

    /**
     * Unique beacon ID, needed for finding existing UNSAVED beacons. Cannot use a simple counter
     * for this because it will be different between restarts and we will find the wrong beacon when
     * an Alarm is triggered and we search for the beacon with that exact ID, while a random UUID will not
     * find any beacon at all (but since the beacon was unsaved that's to be expected).
     */
    private final UUID mUUID;

    /** Persistent ID for database purpose **/
    private long mStorageId = 0;

    private static int _lastStableId = 0;
    private final int mStableId = ++_lastStableId;

    private int mFlags;

    private int mAdvertiseMode;
    private int mTxPowerLevel;

    /** Current status **/
    private int mAdvertiseState = ADVERTISE_STOPPED;

    private int mActiveState = ACTIVE_STATE_STOPPED;

    private boolean mConnectable = false;
    private String mError;

    /*
    * @param mode                 BLE mode
    * @param txPowerLevel         BLE TX power level
    * @param mName                 Optional mName
    */
    public Beacon(long storageId,
                  @AdvertiseMode int advertiseMode,
                  @AdvertiseTxPower int txPowerLevel, int flags, String name) {
        mStorageId = storageId;
        mFlags = flags;
        mAdvertiseMode = advertiseMode;
        mTxPowerLevel = txPowerLevel;
        mName = name;
        mUUID = UUID.randomUUID();
    }

    public Beacon(@AdvertiseMode int advertiseMode,
                  @AdvertiseTxPower int txPowerLevel, int flags, String name) {
        this(0, advertiseMode, txPowerLevel, flags, name);
    }

    public Beacon(@AdvertiseMode int advertiseMode,
                  @AdvertiseTxPower int txPowerLevel, int flags) {
        this(0, advertiseMode, txPowerLevel, flags, null);
    }

    public Beacon(int flags, String name) {
        this(0, DEFAULT_ADVERTISE_MODE, DEFAULT_ADVERTISE_TX_POWER, flags, name);
    }

    public Beacon(int flags) {
        this(flags, null);
    }

    /**
     * Saves this beacon to persistent storage and optionally starts advertising.
     * @param startAdvertising    Enables the beacon to advertise, if not started already.
     * @return Same instance.
     */
    public Beacon save(boolean startAdvertising) {
        // don't save an already persisted beacon
        if (getSavedId() > 0) return this;

        switch (getType()) {
            case EDDYSTONE_URL:
            case EDDYSTONE_UID:
            case EDDYSTONE_EID:
                Storage.getInstance().insertEddystoneItem((EddystoneBase) this);
                break;
            case EPHEMERAL_URL:
                Storage.getInstance().insertUriioItem((EphemeralURL) this);
                break;
            case IBEACON:
                Storage.getInstance().insertAppleBeaconItem((iBeacon) this);
                break;
        }

        if (startAdvertising) {
            start();
        }

        return this;
    }

    /**
     * Saves the beacon and enables BLE advertising.
     * @return Same instance.
     */
    public Beacon save() {
        return save(true);
    }

    private void onEditDone(boolean needRestart) {
        if (getSavedId() > 0) {
            Storage.getInstance().saveExisting(this);
        }

        if (needRestart) {
            restartBeacon();
        }
    }

    private void restartBeacon() {
        if (ADVERTISE_RUNNING == getAdvertiseState()) {
            setActiveState(ACTIVE_STATE_PAUSED);
            setState(ACTIVE_STATE_ENABLED, false);  // already persisted as enabled
        }
    }

    /**
     * Enables BLE advertising for this beacon.
     * @return True on success. Note that the actual advertising may fail later, this call only transitions the beacon into enabled state.
     */
    public boolean start() {
        if (Beacons.getActive().size() == 0) {
            Context context = Beacons.getContext();
            if (null == context) return false;

            context.startService(new Intent(context, BleService.class));
        }
        return setState(Beacon.ACTIVE_STATE_ENABLED, true);
    }

    /**
     * Stops the beacon and deletes it from storage.
     */
    public void delete() {
        if (getActiveState() != ACTIVE_STATE_STOPPED) {
            setState(ACTIVE_STATE_STOPPED, false);
        }

        if (getSavedId() > 0) Storage.getInstance().deleteItem(getSavedId());
    }

    public void pause() {
        setState(ACTIVE_STATE_PAUSED, true);
    }

    public void stop() {
        setState(ACTIVE_STATE_STOPPED, true);
    }

    private boolean setState(int state, boolean persist) {
        if (state < 0 || state > 2) {
            return false;
        }

        Beacon targetBeacon = getSavedId() > 0 ? Beacons.findActive(getSavedId()) : Beacons.findActive(getUUID());

        if (null == targetBeacon) {
            targetBeacon = this;

            if (state != Beacon.ACTIVE_STATE_STOPPED) {
                // beacon is not active, and will not be stopped
                if (Beacons.isInitialized()) {
                    // prevent adding the beacon as active a second time on init, if thee service
                    // is not started at this point (example: stopping last beacon -> starting a new one)
                    Beacons.getActive().add(this);
                }
            }
        }

        if (state != targetBeacon.getActiveState()) {
            // item changed state
            targetBeacon.setActiveState(state);
            if (persist) {
                Storage.getInstance().updateBeaconState(targetBeacon, state);
            }

            sendStateBroadcast(targetBeacon);
        }

        return true;
    }

    private static void sendStateBroadcast(Beacon beacon) {
        Context context = Beacons.getContext();
        if (null != context) {
            Intent intent = new Intent(BleService.ACTION_ITEM_STATE);

            if (beacon.getSavedId() > 0) {
                intent.putExtra(BleService.EXTRA_ITEM_STORAGE_ID, beacon.getSavedId());
            }
            else {
                intent.putExtra(BleService.EXTRA_ITEM_ID, beacon.getUUID());
            }

            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        }
    }

    public void setActiveState(int state) {
        mActiveState = state;
    }

    public int getActiveState() {
        return mActiveState;
    }

    /**
     * @return Persistent ID of this beacon. If 0, then the beacon is not yet saved.
     */
    public long getSavedId() {
        return mStorageId;
    }

    /**
     * @return A non-persistent unique ID different than any other beacon.
     * The ID is not stable between service restarts and can easily collide.
     */
    public long getStableId() {
        return mStableId;
    }

    /**
     * @return A non-persistent UUID different than any other beacon.
     * The UUID is not stable between service restarts, but it will not collide.
     */
    public UUID getUUID() {
        return mUUID;
    }

    public Advertiser getAdvertiser() {
        return mAdvertiser;
    }

    Advertiser setAdvertiser(Advertiser advertiser) {
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

    public int getAdvertiseState() {
        return mAdvertiseState;
    }

    public void setAdvertiseState(int status) {
        mAdvertiseState = status;
    }

    public abstract Advertiser createAdvertiser(AdvertisersManager advertisersManager);

    public abstract int getKind();

    abstract public int getType();

    /**
     * Descriptive name. Not used for BLE advertising purposes.
     */
    public String getName() {
        return mName;
    }

    // FIXME: 9/26/2016 is this used?
    void clearAdvertiser() {
        mAdvertiser = null;
    }

    public long getScheduledRefreshTime() {
        return 0;
    }

    /**
     * Called when the item should start advertising a new BLE beacon.
     * Default implementation starts a new beacon advertiser; subclasses may override with other behaviour.
     * @param service    BLE Service
     */
    public void onAdvertiseEnabled(BleService service) {
        // (re)create the beacon
        service.startBeaconAdvertiser(this);
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
    public void setStorageId(long id) {
        if (0 == mStorageId) {
            mStorageId = id;
        }
    }

    public PendingIntent getAlarmPendingIntent(Context context) {
        Intent intent = new Intent(BleService.ACTION_ALARM, null, context, Receiver.class);

        if (getSavedId() > 0) intent.putExtra(BleService.EXTRA_ITEM_STORAGE_ID, getSavedId());
        else intent.putExtra(BleService.EXTRA_ITEM_ID, getUUID());

        // use a unique private request code, or else the returned PendingIntent is "identical" for all beacons, being reused
        return PendingIntent.getBroadcast(context, mStableId, intent, 0);
    }

    public void setError(String error) {
        mError = error;
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
            onEditDone(mRestartBeacon);
        }
    }
}