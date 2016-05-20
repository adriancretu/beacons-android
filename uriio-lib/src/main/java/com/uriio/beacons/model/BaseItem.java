package com.uriio.beacons.model;

import android.app.PendingIntent;
import android.bluetooth.le.AdvertiseSettings;
import android.content.Context;
import android.content.Intent;

import com.uriio.beacons.UriioReceiver;
import com.uriio.beacons.UriioService;
import com.uriio.beacons.Util;
import com.uriio.beacons.ble.BLEAdvertiseManager;
import com.uriio.beacons.ble.Beacon;

import java.security.GeneralSecurityException;

/**
 * Base container for an item.
 */
public abstract class BaseItem {
    public static final int STATUS_STOPPED          = 0;
    public static final int STATUS_UPDATING         = 1;
    public static final int STATUS_UPDATE_FAILED    = 2;
    public static final int STATUS_ADVERTISING      = 3;
    public static final int STATUS_ADVERTISE_PAUSED = 4;
    public static final int STATUS_ADVERTISE_FAILED = 5;
    public static final int STATUS_NO_BLUETOOTH     = 6;

    private static final String[] _statusName = {
        "Stopped", "Updating", "Update error", "Running", "Paused", "Failed", "Bluetooth OFF"
    };

    /** Associated BLE object **/
    protected Beacon mBeacon;

    /** local id for DB persistence **/
    private final long mItemId;
    private String mName = null;

    protected int mFlags;

    private int mAdvertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED;
    private int mTxPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_LOW;

    /** Current status **/
    private int mStatus = STATUS_STOPPED;

    // fixme - merge with state
    private int mStorageState;

    public BaseItem(long itemId, int flags) {
        mItemId = itemId;
        mFlags = flags;
    }

    public void setStorageState(int state) {
        mStorageState = state;
    }

    public int getStorageState() {
        return mStorageState;
    }

    public long getId() {
        return mItemId;
    }

    public Beacon getBeacon() {
        return mBeacon;
    }

    public int getTxPowerLevel() {
        return mTxPowerLevel;
    }

    public int getAdvertiseMode() {
        return mAdvertiseMode;
    }

    public void setAdvertiseMode(int mode) {
        mAdvertiseMode = mode;
    }

    public void setTxPowerLevel(int txPowerLevel) {
        mTxPowerLevel = txPowerLevel;
    }

    public boolean updateBroadcastingOptions(int mode, int txPowerLevel) {
        if (mode != mAdvertiseMode || txPowerLevel != mTxPowerLevel) {
            setAdvertiseMode(mode);
            setTxPowerLevel(txPowerLevel);
            return true;
        }
        return false;
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

    public abstract Beacon createBeacon(BLEAdvertiseManager bleAdvertiseManager) throws GeneralSecurityException;

    public abstract int getKind();

    public boolean setName(String name) {
        if (null == name || !name.equals(mName)) {
            mName = name;
            return true;
        }
        return false;
    }

    public String getName() {
        return mName;
    }

    public void clearBeacon() {
        mBeacon = null;
    }

    protected long getScheduledRefreshTime() {
        return 0;
    }

    public void onAdvertiseStarted(UriioService service) {
        setStatus(BaseItem.STATUS_ADVERTISING);

        long scheduledRefreshTime = getScheduledRefreshTime();

        if (scheduledRefreshTime > 0) {
            PendingIntent pendingIntent = getRefreshPendingIntent(service);

            // schedule alarm for next refresh
            Util.log("UriioService item " + getId() + " now: " + System.currentTimeMillis() + " alarm time: " + getScheduledRefreshTime());
            service.scheduleRTCAlarm(scheduledRefreshTime, pendingIntent);
        }
    }

    public PendingIntent getRefreshPendingIntent(Context context) {
        Intent intent = new Intent(context, UriioReceiver.class);
        intent.putExtra(UriioService.EXTRA_ITEM_ID, getId());

        // use the item id as the private request code, or else the Intent is "identical" for all items and is reused!
        return PendingIntent.getBroadcast(context, (int) getId(), intent, 0);
    }
}