package com.uriio.beacons;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.ble.AdvertisersManager;
import com.uriio.beacons.model.Beacon;

import java.util.UUID;

/**
 * Advertiser service, that persists and restarts in case of a crash by restoring its previous state.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class BleService extends Service implements AdvertisersManager.Listener {
    private static final String TAG = "BleService";
    private static boolean D = BuildConfig.DEBUG;

    private static final String META_KEY_NOTIFICATION_PROVIDER = "com.uriio.provider";

    public static final String ACTION_BEACONS = BuildConfig.APPLICATION_ID + ".ACTION_BEACONS";

    /** Item state changed*/
    public static final String ACTION_ITEM_STATE = BuildConfig.APPLICATION_ID + ".ACTION_ITEM_STATE";

    /**
     * AlarmManager PendingIntent - a beacon is asking to be recreated with updated advertising data
     */
    public static final String ACTION_ALARM     = BuildConfig.APPLICATION_ID + ".ACTION_ALARM";

    /** Notification actions pending intents */
    static final String ACTION_PAUSE_ALL = BuildConfig.APPLICATION_ID + ".ACTION_PAUSE_ALL";
    static final String ACTION_STOP_ALL  = BuildConfig.APPLICATION_ID + ".ACTION_STOP_ALL";

    /** Notification content tapped */
    static final String ACTION_NOTIFICATION_CONTENT = BuildConfig.APPLICATION_ID + ".ACTION_NOTIF_CONTENT";

    public static final int EVENT_ADVERTISER_ADDED      = 1;
    public static final int EVENT_ADVERTISER_STARTED    = 2;
    public static final int EVENT_ADVERTISER_STOPPED    = 3;
    public static final int EVENT_ADVERTISER_FAILED     = 4;
    public static final int EVENT_ADVERTISE_UNSUPPORTED = 5;

    /**
     * Sent when the beacon was not started because of a missing precondition or error.
     * Example: retrieving the URL to be advertised from a back-end server failed.
     */
    public static final int EVENT_START_FAILED          = 6;

    /** Intent extra - beacon UUID */
    public static final String EXTRA_ITEM_ID         = "id";
    /** Intent extra - beacon storage ID */
    public static final String EXTRA_ITEM_STORAGE_ID = "dbid";

    public static final String EXTRA_BEACON_EVENT    = "type";
    public static final String EXTRA_ERROR           = "error";
    public static final String EXTRA_ERROR_CODE      = "code";

    public class LocalBinder extends Binder {
        public BleService getUriioService() {
            return BleService.this;
        }
    }

    private AdvertisersManager mAdvertisersManager = null;
    private AlarmManager mAlarmManager = null;
    private NotificationManager mNotificationManager = null;

    /** App-provided (or default) notification provider. */
    private NotificationProvider mNotificationProvider = null;

    /** System clock time in milliseconds, when service was created */
    private long mPowerOnStartTime = 0;

    /** Estimated total broadcasted advertisements since power-on time  */
    private long mEstimatedPDUCount = 0;

    /** Keeps track whether the service was started. */
    private boolean mStarted = false;

    /**
     * Receiver for Bluetooth events and beacon actions
     */
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (null == action) {
                return;
            }

            switch (action) {
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    handleBluetoothStateChanged(intent);
                    break;
                case ACTION_ITEM_STATE:
                    handleItemState(intent);
                    break;
            }
        }
    };

    //region Service
    @Override
    public IBinder onBind(Intent intent) {
        if(D) Log.d(TAG, "onBind() called with: intent = [" + intent + "]");
        return new LocalBinder();
    }

    @Override
    public void onCreate() {
        if(D) Log.d(TAG, "onCreate() called");

        super.onCreate();

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        mNotificationProvider = getNotificationProvider();
        mPowerOnStartTime = SystemClock.elapsedRealtime();
        mEstimatedPDUCount = 0;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(D) Log.d(TAG, "onStartCommand() called with: intent = [" + intent + "], flags = [" + flags + "], startId = [" + startId + "]");

        if (!mStarted) {
            initializeService();
            mStarted = true;
        }

        // intent is null if service restarted
        if (null != intent) {
            Receiver.completeWakefulIntent(intent);

            Beacon beacon = Beacons.findActive(intent.getLongExtra(EXTRA_ITEM_STORAGE_ID, 0));

            // unsaved beacon, try finding by UUID
            if (null == beacon) beacon = Beacons.findActive((UUID) intent.getSerializableExtra(EXTRA_ITEM_ID));

            if (null != beacon) {
                beacon.onAdvertiseEnabled(this);
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        if(D) Log.d(TAG, "onDestroy() called");

        // we can end up destroyed without actually ever being started, and since we didn't
        // register our receiver, the app would crash on unregister
        if (mStarted) {
            if (null != mBroadcastReceiver) {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);

                // we registered also in normal way
                unregisterReceiver(mBroadcastReceiver);
            }

            if (null != mAdvertisersManager) {
                mAdvertisersManager.close();
                mAdvertisersManager = null;
            }

            for (Beacon beacon : Beacons.getActive()) {
                mAlarmManager.cancel(beacon.getAlarmPendingIntent(this));
            }

            mNotificationManager.cancel(mNotificationProvider.getNotificationId());

            mStarted = false;
        }

        Beacons.onBleServiceDestroyed();

        super.onDestroy();

        mNotificationManager = null;
    }
    //endregion

    private void handleBluetoothStateChanged(Intent intent) {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
//            log("Bluetooth event state=" + state);
        if (BluetoothAdapter.STATE_TURNING_OFF == state) {
            mAdvertisersManager.onBluetoothOff();

            for (Beacon beacon : Beacons.getActive()) {
                // cancel beacon updater alarm
                mAlarmManager.cancel(beacon.getAlarmPendingIntent(this));

                mEstimatedPDUCount += beacon.onBluetoothOff();
            }

            // when an advertiser will start, the service will start in foreground again
            stopForeground(true);

            broadcastBeaconEvent(EVENT_ADVERTISER_STOPPED, null);
        } else if (BluetoothAdapter.STATE_ON == state) {
            for (Beacon beacon : Beacons.getActive()) {
                if (beacon.getActiveState() == Beacon.ACTIVE_STATE_ENABLED) {
                    beacon.onAdvertiseEnabled(this);
                } else {
                    // beacon was active but not enabled, aka PAUSED
                    beacon.setAdvertiseState(Beacon.ADVERTISE_STOPPED);
                }
            }
        }
    }

    private void handleItemState(Intent intent) {
        if(D) Log.d(TAG, "handleItemState() called with: intent = [" + intent + "]");

        Beacon beacon = Beacons.findActive(intent.getLongExtra(EXTRA_ITEM_STORAGE_ID, 0));

        // unsaved beacon, try finding by UUID
        if (null == beacon) beacon = Beacons.findActive((UUID) intent.getSerializableExtra(EXTRA_ITEM_ID));

        if (null != beacon) {
            if (D) Log.d(TAG, "Received itemState intent for " + beacon);

            switch (beacon.getActiveState()) {
                case Beacon.ACTIVE_STATE_ENABLED:
                    beacon.onAdvertiseEnabled(this);
                    break;
                case Beacon.ACTIVE_STATE_PAUSED:
                    stopBeacon(beacon, false);
                    break;
                case Beacon.ACTIVE_STATE_STOPPED:
                    stopBeacon(beacon, true);
                    break;
            }
        }
    }

    private void initializeService() {
        if(D) Log.d(TAG, "initializeService() called");

        Beacons.initialize(this);

        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        IntentFilter localIntentFilter = new IntentFilter(ACTION_ITEM_STATE);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, localIntentFilter);

        // Bluetooth events are not received when using LocalBroadcastManager
        IntentFilter systemIntentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBroadcastReceiver, systemIntentFilter);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (null != bluetoothManager) {
            mAdvertisersManager = new AdvertisersManager(bluetoothManager, this);

            restoreSavedState();
        }
    }

    private void restoreSavedState() {
        // restore advertisers
        for (Beacon beacon : Beacons.getActive()) {
            if (beacon.getActiveState() == Beacon.ACTIVE_STATE_ENABLED) {
                beacon.onAdvertiseEnabled(this);
            }
        }
    }

    /**
     * Start or restart the advertising of an item's BLE beacon.
     * @param beacon    The item to (re)start.
     * @return True if the beacon was started, false otherwise.
     */
    public boolean startBeaconAdvertiser(Beacon beacon) {
        if (null == mAdvertisersManager) {
            return false;
        }

        if (!mAdvertisersManager.isBluetoothEnabled()) {
            return false;
        }

        if (!mAdvertisersManager.canAdvertise()) {
            beacon.onAdvertiseFailed(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED);
            broadcastBeaconEvent(EVENT_ADVERTISE_UNSUPPORTED, beacon);
            return false;
        }

        // stop current advertiser for this beacon
        Advertiser existingAdvertiser = beacon.getAdvertiser();
        if (null != existingAdvertiser) {
            mAdvertisersManager.stopAdvertiser(existingAdvertiser);
            mEstimatedPDUCount += existingAdvertiser.clearPDUCount();
        }

        Advertiser advertiser = beacon.recreateAdvertiser(this);
        if (null != advertiser) {
            advertiser.setManager(mAdvertisersManager);
        }

        return null != advertiser && mAdvertisersManager.startAdvertiser(advertiser);
    }

    private void broadcastBeaconEvent(int event, Beacon beacon) {
        Beacons.broadcastLocalIntent(makeBeaconEventIntent(event, beacon));
    }

    public void broadcastError(Beacon beacon, int event, String error) {
        Beacons.broadcastLocalIntent(makeBeaconEventIntent(event, beacon).putExtra(EXTRA_ERROR, error));
    }

    private void broadcastError(Beacon beacon, int event, int errorCode) {
        Beacons.broadcastLocalIntent(makeBeaconEventIntent(event, beacon).putExtra(EXTRA_ERROR_CODE, errorCode));
    }

    public static Intent makeBeaconEventIntent(int event, Beacon beacon) {
        Intent intent = new Intent(ACTION_BEACONS).putExtra(EXTRA_BEACON_EVENT, event);
        if (null != beacon) {
            intent.putExtra(EXTRA_ITEM_ID, beacon.getUUID());
            intent.putExtra(EXTRA_ITEM_STORAGE_ID, beacon.getSavedId());
            intent.putExtra("kind", beacon.getKind());
        }
        return intent;
    }

    private Beacon findActiveBeacon(Advertiser advertiser) {
        for (Beacon item : Beacons.getActive()) {
            if (item.getAdvertiser() == advertiser) return item;
        }
        return null;
    }

    //region AdvertisersManager.Listener
    @Override
    public void onAdvertiserStarted(Advertiser advertiser) {
        Beacon beacon = findActiveBeacon(advertiser);
        if (null != beacon) {
            beacon.setAdvertiseState(Beacon.ADVERTISE_RUNNING);

            long scheduledRefresh = beacon.getScheduledRefreshElapsedTime();

            if (scheduledRefresh > 0) {
                // schedule alarm for next onAdvertiseEnabled
                if(BuildConfig.DEBUG) Log.d(TAG, "Scheduling alarm for " + beacon.getUUID() + " in " + scheduledRefresh);
                scheduleElapsedTimeAlarm(scheduledRefresh, beacon.getAlarmPendingIntent(this));
            }

            broadcastBeaconEvent(EVENT_ADVERTISER_STARTED, beacon);

            updateForegroundNotification(true);
        }
    }

    @Override
    public void onAdvertiserFailed(Advertiser advertiser, int errorCode) {
        Beacon beacon = findActiveBeacon(advertiser);
        if (null != beacon) {
            // mark beacon as paused so we can try to start it again
            beacon.onAdvertiseFailed(errorCode);
            broadcastError(beacon, EVENT_ADVERTISER_FAILED, errorCode);
        }
    }
    //endregion

    private void updateForegroundNotification(boolean newAdvertiserStarted) {
        int totalRunning = 0;
        for (Beacon beacon : Beacons.getActive()) {
            if (beacon.getAdvertiseState() == Beacon.ADVERTISE_RUNNING) {
                ++totalRunning;
            }
        }

        if (totalRunning > 0) {
            Notification notification = mNotificationProvider.makeNotification(mNotificationManager, totalRunning);
            if (null != notification) {
                if (newAdvertiserStarted && 1 == totalRunning) {
                    startForeground(mNotificationProvider.getNotificationId(), notification);
                } else {
                    mNotificationManager.notify(mNotificationProvider.getNotificationId(), notification);
                }
            }
        }
        else {
            stopForeground(mNotificationProvider.onStoppedForeground());
        }
    }

    private void stopBeacon(Beacon beacon, boolean remove) {
        Advertiser advertiser = beacon.getAdvertiser();

        // stop the advertising and cancel any pending alarm
        if (null != advertiser && advertiser.getStatus() == Advertiser.STATUS_RUNNING) {
            mAdvertisersManager.stopAdvertiser(advertiser);
            mEstimatedPDUCount += advertiser.clearPDUCount();
        }
        mAlarmManager.cancel(beacon.getAlarmPendingIntent(this));

        beacon.setAdvertiseState(Beacon.ADVERTISE_STOPPED);
        if (remove) {
            Beacons.getActive().remove(beacon);
            if (0 == Beacons.getActive().size()) {
//                Toast.makeText(this, "BLE service stopped, no beacons are enabled.", Toast.LENGTH_LONG).show();
                stopSelf();
            }
        }
        broadcastBeaconEvent(EVENT_ADVERTISER_STOPPED, beacon);

        updateForegroundNotification(false);
    }

    public void scheduleElapsedTimeAlarm(long triggerAtMillis, PendingIntent operation) {
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, operation);
    }

    public AdvertisersManager getAdvertisersManager() {
        return mAdvertisersManager;
    }

    /**
     * @return Power-on time since service is up, in milliseconds
     */
    public long getPowerOnTime() {
        return SystemClock.elapsedRealtime() - mPowerOnStartTime;
    }

    public long updateEstimatedPDUCount() {
        for (Beacon beacon : Beacons.getActive()) {
            if (null != beacon.getAdvertiser()) {
                mEstimatedPDUCount += beacon.getAdvertiser().clearPDUCount();
            }
        }

        return mEstimatedPDUCount;
    }

    private NotificationProvider getNotificationProvider() {
        ApplicationInfo appInfo;
        try {
            appInfo = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("App package not found");
        }

        String providerClassName = null;

        // metadata is null when no entries exist
        if (null != appInfo && null != appInfo.metaData) {
            providerClassName = appInfo.metaData.getString(META_KEY_NOTIFICATION_PROVIDER);
        }

        if (null != providerClassName) {
            try {
                return (NotificationProvider) Class.forName(providerClassName).getConstructor(Context.class).newInstance(this);
            } catch (ReflectiveOperationException ignored) {
                throw new RuntimeException("Invalid provider class name");
            }
        } else {
            return new NotificationProvider(this);
        }
    }
}