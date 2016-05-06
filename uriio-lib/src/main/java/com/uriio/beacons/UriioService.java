package com.uriio.beacons;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Base64;
import android.widget.Toast;

import com.uriio.beacons.api.ShortUrl;
import com.uriio.beacons.api.ShortUrls;
import com.uriio.beacons.ble.BLEAdvertiseManager;
import com.uriio.beacons.ble.Beacon;
import com.uriio.beacons.ble.EddystoneBeacon;
import com.uriio.beacons.model.BaseItem;
import com.uriio.beacons.model.EddystoneItem;
import com.uriio.beacons.model.UriioItem;
import com.uriio.beacons.model.iBeaconItem;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Advertiser service, that persists and restarts in case of a crash by restoring its previous state.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class UriioService extends Service implements BLEAdvertiseManager.BLEListener {
    private static final String TAG = "UriioService";

    /** Broadcast action for when an event occurred in our service */
    public static final String ACTION_URIIO_EVENT       = "com.uriio.ACTION_EVENT";

    public static final int EVENT_ADVERTISER_ADDED      = 1;
    public static final int EVENT_ADVERTISER_STARTED    = 2;
    public static final int EVENT_ADVERTISER_STOPPED    = 3;
    public static final int EVENT_ADVERTISER_FAILED     = 4;
    public static final int EVENT_ADVERTISE_UNSUPPORTED = 5;

    // TODO - separate this from the bunch...
    public static final int EVENT_SHORTURL_FAILED       = 6;

    /** Service intent extras */
    public static final String EXTRA_COMMAND = "command";
    /** Key for the item id that needs to be updated or started */
    public static final String EXTRA_ITEM_ID = "itemId";

    public static final int COMMAND_REFRESH = 1;
    public static final int COMMAND_START   = 2;
    public static final int COMMAND_STOP    = 3;

    public class LocalBinder extends Binder {
        public UriioService getUriioService() {
            return UriioService.this;
        }
    }

    private BLEAdvertiseManager mBleAdvertiseManager = null;
    private Storage mStorage = Storage.getInstance();

    /** List of active items **/
    private List<BaseItem> mActiveItems = new ArrayList<>();

    private AlarmManager mAlarmManager = null;
    private boolean mStarted = false;

    /**
     * Listen to Bluetooth events.
     */
    private BroadcastReceiver mBluetoothBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
//            log("Bluetooth event state=" + state);
            if (BluetoothAdapter.STATE_TURNING_OFF == state) {
                for (BaseItem activeItem : mActiveItems) {
                    if (activeItem instanceof UriioItem) {
                        mAlarmManager.cancel(((UriioItem) activeItem).getRefreshPendingIntent(UriioService.this));
                    }
                    activeItem.setStatus(BaseItem.STATUS_NO_BLUETOOTH);
                    Beacon beacon = activeItem.getBeacon();
                    if (null != beacon) {
                        beacon.setStoppedState();
                    }
                }

                mBleAdvertiseManager.onBluetoothOff();
                broadcastAction(EVENT_ADVERTISER_STOPPED);
            }
            else if (BluetoothAdapter.STATE_ON == state) {
                for (BaseItem activeItem : mActiveItems) {
                    if (activeItem.getStorageState() == Storage.STATE_ENABLED) {
                        restoreActiveItem(activeItem);
                    }
                    else {
                        activeItem.setStatus(BaseItem.STATUS_ADVERTISE_PAUSED);
                    }
                }
                broadcastAction(EVENT_ADVERTISER_STARTED);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!mStarted) {
            initializeService();
            mStarted = true;
        }

        // intent is null if service restarted
        if (null != intent) {
            handleStartIntent(intent);
            UriioReceiver.completeWakefulIntent(intent);
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private void handleStartIntent(Intent intent) {
        long itemId = intent.getLongExtra(EXTRA_ITEM_ID, 0);
        if (0 != itemId) {
            Util.log(TAG, "Received intent for item " + itemId);
            switch (intent.getIntExtra(EXTRA_COMMAND, 0)) {
                case COMMAND_REFRESH:
                    for (BaseItem activeItem : mActiveItems) {
                        if (activeItem.getId() == itemId) {
                            if (activeItem instanceof UriioItem) {
                                Util.log(TAG, "Updating short url for item " + itemId);
                                requestShortUrl((UriioItem) activeItem, 1);
                            }
                            break;
                        }
                    }
                    break;
                case COMMAND_START:
                    startSavedItem(itemId);
                    break;
            }
        }
    }

    private void initializeService() {
        Uriio.reinitialize(this);

        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBluetoothBroadcastReceiver, intentFilter);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (null != bluetoothManager) {
            mBleAdvertiseManager = new BLEAdvertiseManager(bluetoothManager, this);

            restoreSavedState();
        }
    }

    private void restoreSavedState() {
        // restore advertisers
        Cursor cursor = mStorage.getActiveItems();
        while (cursor.moveToNext()) {
            BaseItem item = Storage.itemFromCursor(cursor);
            restoreActiveItem(item);
            mActiveItems.add(item);
        }
        cursor.close();
    }

    @Override
    public void onDestroy() {
        if (null != mBluetoothBroadcastReceiver) {
            unregisterReceiver(mBluetoothBroadcastReceiver);
        }

        if (null != mBleAdvertiseManager) {
            mBleAdvertiseManager.close();
            mBleAdvertiseManager = null;
        }

        if (null != mStorage) {
            mStorage.close();
            mStorage = null;
        }

        for (BaseItem activeItem : mActiveItems) {
            if (activeItem instanceof UriioItem) {
                mAlarmManager.cancel(((UriioItem) activeItem).getRefreshPendingIntent(this));
            }
        }
        mActiveItems.clear();

        super.onDestroy();
    }

    /**
     * Starts advertising a BLE beacon.
     * @param item    The item to start.
     * @return True if the beacon was started, false otherwise.
     */
    public boolean startAdvertisingBeacon(BaseItem item) {
        if (!isAppendAdvertiserAllowed()) {
            return false;
        }

        if (null != mBleAdvertiseManager) {
            Beacon existingBeacon = item.getBeacon();

            if (null != existingBeacon) {
                mBleAdvertiseManager.enableAdvertiser(existingBeacon, false);
            }

            Beacon beacon = null;
            try {
                beacon = item.createBeacon(mBleAdvertiseManager);
            } catch (GeneralSecurityException e) {
                handleException(e);
            }

            if (null == beacon) {
                return false;
            }

            mBleAdvertiseManager.startAdvertiser(beacon);

            // refresh any listening UI
            broadcastAction(EVENT_ADVERTISER_ADDED);
        }
        return true;
    }

    private void handleException(GeneralSecurityException e) {
        // TODO: 4/16/2016 - make sure we are in a looping thread (or the UI thread)
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }

    public boolean isAppendAdvertiserAllowed() {
        return true;
    }

    private void broadcastAction(int type) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_URIIO_EVENT)
                .putExtra("type", type));
    }

    private void broadcastBeaconAction(int type, int kind) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_URIIO_EVENT)
                .putExtra("type", type)
                .putExtra("kind", kind));
    }

    private void broadcastAction(Intent intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public List<BaseItem> getActiveItems() {
        return mActiveItems;
    }

    public BaseItem createEddystoneItem(int mode, int txPowerLevel, int flags, String payload, String name, String domain) {
        long itemId = mStorage.insertEddystoneItem(mode, txPowerLevel, payload, flags, name, domain);

        EddystoneItem item = new EddystoneItem(itemId, flags, payload, domain);
        item.setAdvertiseMode(mode);
        item.setTxPowerLevel(txPowerLevel);
        item.setName(name);
        mActiveItems.add(0, item);

        startAdvertisingBeacon(item);

        return item;
    }

    public BaseItem createIBeaconItem(int mode, int txPowerLevel, int flags, byte[] rawUuid, int major, int minor, String name) {
        long itemId = mStorage.insertAppleBeaconItem(mode, txPowerLevel,
                Base64.encodeToString(rawUuid, Base64.NO_PADDING), major, minor, flags, name);
        iBeaconItem item = new iBeaconItem(itemId, flags, rawUuid, major, minor);
        item.setAdvertiseMode(mode);
        item.setTxPowerLevel(txPowerLevel);
        item.setName(name);
        mActiveItems.add(0, item);

        startAdvertisingBeacon(item);

        return item;
    }

    private void requestShortUrl(final UriioItem uriioItem, int numToIssue) {
        Uriio.getInstance().issueShortUrls(uriioItem.getUrlId(), uriioItem.getUrlToken(),
                uriioItem.getTimeToLive(), numToIssue, new Uriio.OnResultListener<ShortUrls>() {
            @Override
            public void onResult(ShortUrls result, Throwable error) {
                if (null != result) {
                    ShortUrl shortUrl = result.getItems()[0];
                    Date expireDate = shortUrl.getExpire();
                    long expireTime = null == expireDate ? 0 : expireDate.getTime();

                    uriioItem.updateShortUrl(shortUrl.getUrl(), expireTime);
                    mStorage.updateUriioItemShortUrl(uriioItem.getId(), shortUrl.getUrl(), expireTime);

                    startAdvertisingBeacon(uriioItem);
                } else {
                    uriioItem.setStatus(BaseItem.STATUS_UPDATE_FAILED);

                    broadcastAction(new Intent(ACTION_URIIO_EVENT)
                            .putExtra("type", EVENT_SHORTURL_FAILED)
                            .putExtra("error", error.getMessage()));
                }
            }
        });
    }

    private BaseItem findActiveItem(Beacon advertiser) {
        for (BaseItem item : mActiveItems) {
            if (item.getBeacon() == advertiser) return item;
        }
        return null;
    }

    public BaseItem findActiveItem(long id) {
        for (BaseItem item : mActiveItems) {
            if (item.getId() == id) return item;
        }
        return null;
    }

    @Override
    public void onBLEAdvertiseStarted(Beacon advertiser) {
        BaseItem item = findActiveItem(advertiser);
        if (null != item) {
            item.onAdvertiseStarted(this);
            broadcastBeaconAction(EVENT_ADVERTISER_STARTED, item.getKind());
        }
    }

    @Override
    public void onBLEAdvertiseFailed(Beacon advertiser, int errorCode) {
        BaseItem item = findActiveItem(advertiser);
        if (null != item) item.setStatus(BaseItem.STATUS_ADVERTISE_FAILED);

        broadcastAction(new Intent(ACTION_URIIO_EVENT)
                .putExtra("type", EVENT_ADVERTISER_FAILED)
                .putExtra("code", errorCode));
    }

    @Override
    public void onBLEAdvertiseNotSupported() {
        broadcastAction(EVENT_ADVERTISE_UNSUPPORTED);
    }

    public Cursor getStoppedItems() {
        return mStorage.getInactiveItems();
    }

    public boolean setItemEnabled(BaseItem item, boolean enabled) {
        Beacon beacon = item.getBeacon();

        if (!mBleAdvertiseManager.canAdvertise()) {
            return false;
        }

        if (null != beacon) {
            mBleAdvertiseManager.enableAdvertiser(beacon, enabled);
        }
        else {
            // beacon is not running
            if (enabled) {
                if (item instanceof UriioItem) {
                    UriioItem uriioActiveItem = (UriioItem) item;
                    requestShortUrl(uriioActiveItem, 1);
                }
                else {
                    // ???
                    // fixme!!!
                    Util.log(TAG, "setItemEnabled requested, but no beacon exists!");
                }
            }
        }

        item.setStatus(enabled ? BaseItem.STATUS_ADVERTISING : BaseItem.STATUS_ADVERTISE_PAUSED);
        item.setStorageState(enabled ? Storage.STATE_ENABLED : Storage.STATE_PAUSED);

        mStorage.updateItemState(item.getId(), enabled ? Storage.STATE_ENABLED : Storage.STATE_PAUSED);

        return true;
    }

    public void stopItem(BaseItem item) {
        Beacon beacon = item.getBeacon();
        if (beacon != null && beacon.getStatus() == Beacon.STATUS_RUNNING) {
            mBleAdvertiseManager.enableAdvertiser(beacon, false);
        }
        if (item instanceof UriioItem) {
            mAlarmManager.cancel(((UriioItem) item).getRefreshPendingIntent(this));
        }
        mActiveItems.remove(item);

        mStorage.updateItemState(item.getId(), Storage.STATE_STOPPED);
        broadcastAction(EVENT_ADVERTISER_STOPPED);
    }

    public void removeSavedItem(long itemId) {
        mStorage.deleteItem(itemId);
    }

    public void startSavedItem(long itemId) {
        mStorage.updateItemState(itemId, Storage.STATE_ENABLED);
        BaseItem item = getItem(itemId);
        restoreActiveItem(item);
        mActiveItems.add(item);
    }

    public BaseItem getItem(long itemId) {
        BaseItem item = null;

        Cursor cursor = mStorage.getItem(itemId);
        if (cursor.moveToNext()) {
            item = Storage.itemFromCursor(cursor);
        }
        cursor.close();

        return item;
    }

    private void restoreActiveItem(BaseItem item) {
        int state = item.getStorageState();
        if (state == Storage.STATE_ENABLED) {
            if (item.getKind() == Storage.KIND_URIIO) {
                UriioItem uriioActiveItem = (UriioItem) item;
                if (null == uriioActiveItem.getPayload() || uriioActiveItem.getMillisecondsUntilExpires() < 7 * 1000) {
                    requestShortUrl(uriioActiveItem, 1);
                } else {
                    startAdvertisingBeacon(item);
                }
            }
            else {
                startAdvertisingBeacon(item);
            }
        }
    }

    public Storage getStorage() {
        return mStorage;
    }

    public void scheduleRTCAlarm(long triggerAtMillis, PendingIntent operation) {
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
    }

    public void updateUriioItemConfig(UriioItem uriioItem, int mode, int txPowerLevel,
                                      int timeToLive, String name) {
        boolean modeOrPowerChanged = uriioItem.updateBroadcastingOptions(mode, txPowerLevel);
        boolean nameChanged = uriioItem.setName(name);
        boolean ttlChanged = uriioItem.updateTTL(timeToLive);

        // watch out - don't inline the checks - condition may short-circuit!!!
        if (modeOrPowerChanged || nameChanged || ttlChanged) {
            mStorage.updateUriioItem(uriioItem.getId(), mode, txPowerLevel, timeToLive,
                    EddystoneBeacon.FLAG_EDDYSTONE, name);
        }

        if (uriioItem.getStatus() == BaseItem.STATUS_ADVERTISING) {
            if (ttlChanged) {
                // need to cancel the refresh alarm, the time to live has changed
                mAlarmManager.cancel(uriioItem.getRefreshPendingIntent(this));
                requestShortUrl(uriioItem, 1);
            }
            else if (modeOrPowerChanged) {
                // recreate the beacon, keep the same short URL until it expires (alarm triggers)
                startAdvertisingBeacon(uriioItem);
            }
        }
    }
}