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
import android.widget.Toast;

import com.uriio.beacons.ble.BLEAdvertiseManager;
import com.uriio.beacons.ble.Beacon;
import com.uriio.beacons.model.BaseItem;

import java.security.GeneralSecurityException;

/**
 * Advertiser service, that persists and restarts in case of a crash by restoring its previous state.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class BleService extends Service implements BLEAdvertiseManager.BLEListener {
    private static final String TAG = "BleService";

    /** Outgoing action for when an event occurred in our service */
    public static final String ACTION_BEACONS = "com.uriio.ACTION_BEACONS";

    /** Incoming beacon-related command */
    public static final String ACTION_COMMAND = "com.uriio.ACTION_COMMAND";

    public static final int EVENT_ADVERTISER_ADDED      = 1;
    public static final int EVENT_ADVERTISER_STARTED    = 2;
    public static final int EVENT_ADVERTISER_STOPPED    = 3;
    public static final int EVENT_ADVERTISER_FAILED     = 4;
    public static final int EVENT_ADVERTISE_UNSUPPORTED = 5;

    // TODO - separate this from the bunch...
    public static final int EVENT_SHORTURL_FAILED       = 6;

    /** Service intent extras */
    public static final String EXTRA_COMMAND    = "cmd";
    public static final String EXTRA_ITEM_ID    = "id";

    public static final int COMMAND_STATE   = 0;
    public static final int COMMAND_REFRESH = 1;

    public class LocalBinder extends Binder {
        public BleService getUriioService() {
            return BleService.this;
        }
    }

    private BLEAdvertiseManager mBleAdvertiseManager = null;

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
                case ACTION_COMMAND:
                    handleBeaconCommand(intent);
                    break;
            }
        }
    };

    private void handleBluetoothStateChanged(Intent intent) {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
//            log("Bluetooth event state=" + state);
        if (BluetoothAdapter.STATE_TURNING_OFF == state) {
            for (BaseItem activeItem : Beacons.getActive()) {
                // cancel onAdvertiseEnabled alarm
                mAlarmManager.cancel(getItemRestartPendingIntent(activeItem));

                activeItem.setStatus(BaseItem.STATUS_NO_BLUETOOTH);
                Beacon beacon = activeItem.getBeacon();
                if (null != beacon) {
                    beacon.setStoppedState();
                }
            }

            mBleAdvertiseManager.onBluetoothOff();
            broadcastAction(EVENT_ADVERTISER_STOPPED);
        } else if (BluetoothAdapter.STATE_ON == state) {
            for (BaseItem activeItem : Beacons.getActive()) {
                if (activeItem.getStorageState() == Storage.STATE_ENABLED) {
                    activeItem.onAdvertiseEnabled(this);
                } else {
                    // NO_BLUETOOTH -> PAUSED
                    activeItem.setStatus(BaseItem.STATUS_ADVERTISE_PAUSED);
                }
            }
            broadcastAction(EVENT_ADVERTISER_STARTED);
        }
    }

    private void handleBeaconCommand(Intent intent) {
        long itemId = intent.getLongExtra(EXTRA_ITEM_ID, 0);
        if (0 != itemId) {
            Util.log(TAG, "Received intent for item " + itemId);
            BaseItem item = Beacons.findActive(itemId);

            if (null != item && intent.hasExtra(EXTRA_COMMAND)) {
                switch (intent.getIntExtra(EXTRA_COMMAND, 0)) {
                    case COMMAND_REFRESH:
                        item.onAdvertiseEnabled(this);
                        break;
                    case COMMAND_STATE: {
                        switch (item.getStorageState()) {
                            case Storage.STATE_ENABLED:
                                item.onAdvertiseEnabled(this);
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
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private void initializeService() {
        Beacons.reinitialize(this);

        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        IntentFilter intentFilter = new IntentFilter(ACTION_COMMAND);
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);

        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (null != bluetoothManager) {
            mBleAdvertiseManager = new BLEAdvertiseManager(bluetoothManager, this);

            restoreSavedState();
        }
    }

    private void restoreSavedState() {
        // restore advertisers
        for (BaseItem item : Beacons.getActive()) {
            if (item.getStorageState() == Storage.STATE_ENABLED) {
                item.onAdvertiseEnabled(this);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (null != mBroadcastReceiver) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        }

        if (null != mBleAdvertiseManager) {
            mBleAdvertiseManager.close();
            mBleAdvertiseManager = null;
        }

        for (BaseItem activeItem : Beacons.getActive()) {
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
    public boolean startItemAdvertising(BaseItem item) {
        if (null == mBleAdvertiseManager) {
            return false;
        }

        if (!mBleAdvertiseManager.canAdvertise()) {
            return false;
        }

        // stop any currently existing beacon associated to the item
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

        // onAdvertiseEnabled any listening UI
        broadcastAction(EVENT_ADVERTISER_ADDED);
        return true;
    }

    private void handleException(GeneralSecurityException e) {
        // TODO: 4/16/2016 - make sure we are in a looping thread (or the UI thread)
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
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

    private BaseItem findActiveItem(Beacon advertiser) {
        for (BaseItem item : Beacons.getActive()) {
            if (item.getBeacon() == advertiser) return item;
        }
        return null;
    }

    @Override
    public void onBLEAdvertiseStarted(Beacon advertiser) {
        BaseItem item = findActiveItem(advertiser);
        if (null != item) {
            item.setStatus(BaseItem.STATUS_ADVERTISING);

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
    public void onBLEAdvertiseFailed(Beacon advertiser, int errorCode) {
        BaseItem item = findActiveItem(advertiser);
        if (null != item) {
            item.setStatus(BaseItem.STATUS_ADVERTISE_FAILED);
            broadcastAction(new Intent(ACTION_BEACONS)
                    .putExtra("type", EVENT_ADVERTISER_FAILED)
                    .putExtra("code", errorCode));
        }
    }

    @Override
    public void onBLEAdvertiseNotSupported() {
        broadcastAction(EVENT_ADVERTISE_UNSUPPORTED);
    }

    private void stopItem(BaseItem item, boolean remove) {
        Beacon beacon = item.getBeacon();

        if (null != beacon && beacon.getStatus() == Beacon.STATUS_RUNNING) {
            mBleAdvertiseManager.enableAdvertiser(beacon, false);
        }
        mAlarmManager.cancel(getItemRestartPendingIntent(item));

        item.setStatus(remove ? BaseItem.STATUS_STOPPED : BaseItem.STATUS_ADVERTISE_PAUSED);
        if (remove) {
            Beacons.getActive().remove(item);
            broadcastAction(EVENT_ADVERTISER_STOPPED);
        }
    }

    public void scheduleRTCAlarm(long triggerAtMillis, PendingIntent operation) {
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
    }

    private PendingIntent getItemRestartPendingIntent(BaseItem item) {
        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.putExtra(BleService.EXTRA_ITEM_ID, item.getId());

        // use the item id as the private request code, or else the Intent is "identical" for all items and is reused!
        return PendingIntent.getBroadcast(this, (int) item.getId(), intent, 0);
    }
}