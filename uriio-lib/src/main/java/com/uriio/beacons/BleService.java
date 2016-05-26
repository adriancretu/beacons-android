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
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;

import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.ble.AdvertisersManager;
import com.uriio.beacons.model.Beacon;

/**
 * Advertiser service, that persists and restarts in case of a crash by restoring its previous state.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class BleService extends Service implements AdvertisersManager.BLEListener {
    private static final String TAG = "BleService";

    /** Outgoing action for when an event occurred in our service */
    public static final String ACTION_BEACONS = "com.uriio.ACTION_BEACONS";

    /** Item state changed*/
    public static final String ACTION_ITEM_STATE = "com.uriio.ACTION_ITEM_STATE";

    public static final int EVENT_ADVERTISER_ADDED      = 1;
    public static final int EVENT_ADVERTISER_STARTED    = 2;
    public static final int EVENT_ADVERTISER_STOPPED    = 3;
    public static final int EVENT_ADVERTISER_FAILED     = 4;
    public static final int EVENT_ADVERTISE_UNSUPPORTED = 5;

    // TODO - separate this from the bunch...
    public static final int EVENT_SHORTURL_FAILED       = 6;

    /** Service intent extras */
    public static final String EXTRA_ITEM_ID    = "id";

    public class LocalBinder extends Binder {
        public BleService getUriioService() {
            return BleService.this;
        }
    }

    private AdvertisersManager mAdvertisersManager = null;

    private AlarmManager mAlarmManager = null;
    private boolean mStarted = false;

    /**
     * Receiver for Bluetooth events and beacon actions
     */
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    handleBluetoothStateChanged(intent);
                    break;
                case ACTION_ITEM_STATE:
                    handleItemState(intent);
                    break;
            }
        }
    };

    private void handleBluetoothStateChanged(Intent intent) {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
//            log("Bluetooth event state=" + state);
        if (BluetoothAdapter.STATE_TURNING_OFF == state) {
            for (Beacon activeItem : Beacons.getActive()) {
                // cancel onAdvertiseEnabled alarm
                mAlarmManager.cancel(getItemRestartPendingIntent(activeItem));

                activeItem.setStatus(Beacon.STATUS_NO_BLUETOOTH);
                Advertiser advertiser = activeItem.getAdvertiser();
                if (null != advertiser) {
                    advertiser.setStoppedState();
                }
            }

            mAdvertisersManager.onBluetoothOff();
            broadcastAction(EVENT_ADVERTISER_STOPPED);
        } else if (BluetoothAdapter.STATE_ON == state) {
            for (Beacon activeItem : Beacons.getActive()) {
                if (activeItem.getStorageState() == Storage.STATE_ENABLED) {
                    activeItem.onAdvertiseEnabled(this);
                } else {
                    // NO_BLUETOOTH -> PAUSED
                    activeItem.setStatus(Beacon.STATUS_ADVERTISE_PAUSED);
                }
            }
        }
    }

    private void handleItemState(Intent intent) {
        long itemId = intent.getLongExtra(EXTRA_ITEM_ID, 0);
        if (0 != itemId) {
            Util.log(TAG, "Received intent for item " + itemId);
            Beacon item = Beacons.findActive(itemId);

            if (null != item) {
                switch (item.getStorageState()) {
                    case Storage.STATE_ENABLED:
                        item.onAdvertiseEnabled(this);
                        broadcastAction(EVENT_ADVERTISER_ADDED);
                        break;
                    case Storage.STATE_PAUSED:
                        stopItem(item, false);
                        break;
                    case Storage.STATE_STOPPED:
                        stopItem(item, true);
                        break;
                }
            }
        }
    }

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
            AlarmReceiver.completeWakefulIntent(intent);

            long itemId = intent.getLongExtra(EXTRA_ITEM_ID, 0);
            if (0 != itemId) {
                Beacon item = Beacons.findActive(itemId);
                if (null != item) {
                    item.onAdvertiseEnabled(this);
                }
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private void initializeService() {
        Beacons.reinitialize(this);

        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        IntentFilter intentFilter = new IntentFilter(ACTION_ITEM_STATE);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);

        // Bluetooth events are not received when using LocalBroadcastManager
        registerReceiver(mBroadcastReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (null != bluetoothManager) {
            mAdvertisersManager = new AdvertisersManager(bluetoothManager, this);

            restoreSavedState();
        }
    }

    private void restoreSavedState() {
        // restore advertisers
        for (Beacon item : Beacons.getActive()) {
            if (item.getStorageState() == Storage.STATE_ENABLED) {
                item.onAdvertiseEnabled(this);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (null != mBroadcastReceiver) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);

            // we registered also in normal way
            unregisterReceiver(mBroadcastReceiver);
        }

        if (null != mAdvertisersManager) {
            mAdvertisersManager.close();
            mAdvertisersManager = null;
        }

        for (Beacon activeItem : Beacons.getActive()) {
            mAlarmManager.cancel(getItemRestartPendingIntent(activeItem));
        }
        Beacons.getActive().clear();

        super.onDestroy();
    }

    /**
     * Start or restart the advertising of an item's BLE beacon.
     * @param item    The item to (re)start.
     * @return True if the beacon was started, false otherwise.
     */
    public boolean startItemAdvertising(Beacon item) {
        if (null == mAdvertisersManager) {
            return false;
        }

        if (!isAdvertisingSupported()) {
            return false;
        }

        // stop any currently existing advertiser associated to the item
        Advertiser existingAdvertiser = item.getAdvertiser();
        if (null != existingAdvertiser) {
            mAdvertisersManager.enableAdvertiser(existingAdvertiser, false);
        }

        Advertiser advertiser = item.createBeacon(mAdvertisersManager);

        return null != advertiser && mAdvertisersManager.startAdvertiser(advertiser);
    }

    public boolean isAdvertisingSupported() {
        return mAdvertisersManager.canAdvertise();
    }

    private void broadcastAction(int type) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_BEACONS)
                .putExtra("type", type));
    }

    private void broadcastBeaconAction(int type, int kind) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_BEACONS)
                .putExtra("type", type)
                .putExtra("kind", kind));
    }

    private void broadcastAction(Intent intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public void broadcastError(int eventType, Throwable error) {
        broadcastAction(new Intent(ACTION_BEACONS).putExtra("type", eventType)
                .putExtra("error", error.getMessage()));
    }

    private Beacon findActiveItem(Advertiser advertiser) {
        for (Beacon item : Beacons.getActive()) {
            if (item.getAdvertiser() == advertiser) return item;
        }
        return null;
    }

    @Override
    public void onBLEAdvertiseStarted(Advertiser advertiser) {
        Beacon item = findActiveItem(advertiser);
        if (null != item) {
            item.setStatus(Beacon.STATUS_ADVERTISING);

            long scheduledRefreshTime = item.getScheduledRefreshTime();

            if (scheduledRefreshTime > 0) {
                PendingIntent pendingIntent = getItemRestartPendingIntent(item);

                // schedule alarm for next onAdvertiseEnabled
                Util.log("BleService item " + item.getId() + " now: " + System.currentTimeMillis() + " alarm time: " + scheduledRefreshTime);
                scheduleRTCAlarm(scheduledRefreshTime, pendingIntent);
            }

            broadcastBeaconAction(EVENT_ADVERTISER_STARTED, item.getKind());
        }
    }

    @Override
    public void onBLEAdvertiseFailed(Advertiser advertiser, int errorCode) {
        Beacon item = findActiveItem(advertiser);
        if (null != item) {
            item.setStatus(Beacon.STATUS_ADVERTISE_FAILED);
            broadcastAction(new Intent(ACTION_BEACONS)
                    .putExtra("type", EVENT_ADVERTISER_FAILED)
                    .putExtra("code", errorCode));
        }
    }

    @Override
    public void onBLEAdvertiseNotSupported() {
        broadcastAction(EVENT_ADVERTISE_UNSUPPORTED);
    }

    private void stopItem(Beacon item, boolean remove) {
        Advertiser advertiser = item.getAdvertiser();

        if (null != advertiser && advertiser.getStatus() == Advertiser.STATUS_RUNNING) {
            mAdvertisersManager.enableAdvertiser(advertiser, false);
        }
        mAlarmManager.cancel(getItemRestartPendingIntent(item));

        item.setStatus(remove ? Beacon.STATUS_STOPPED : Beacon.STATUS_ADVERTISE_PAUSED);
        if (remove) {
            Beacons.getActive().remove(item);
        }
        broadcastAction(EVENT_ADVERTISER_STOPPED);
    }

    public void scheduleRTCAlarm(long triggerAtMillis, PendingIntent operation) {
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
    }

    private PendingIntent getItemRestartPendingIntent(Beacon item) {
        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.putExtra(BleService.EXTRA_ITEM_ID, item.getId());

        // use the item id as the private request code, or else the Intent is "identical" for all items and is reused!
        return PendingIntent.getBroadcast(this, (int) item.getId(), intent, 0);
    }
}