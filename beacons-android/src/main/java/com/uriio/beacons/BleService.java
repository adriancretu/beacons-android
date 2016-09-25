package com.uriio.beacons;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.ble.AdvertisersManager;
import com.uriio.beacons.model.Beacon;
import com.uriio.beacons.model.EddystoneURL;
import com.uriio.beacons.model.EphemeralURL;

/**
 * Advertiser service, that persists and restarts in case of a crash by restoring its previous state.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class BleService extends Service implements AdvertisersManager.BLEListener {
    private static final String TAG = "BleService";

    public static final int NOTIFICATION_ID = 0xB33C0000;

    public static final String ACTION_BEACONS = BuildConfig.APPLICATION_ID + ".ACTION_BEACONS";

    /** Notification content tapped */
    public static final String ACTION_NOTIFICATION_CONTENT = BuildConfig.APPLICATION_ID + ".ACTION_NOTIF_CONTENT";

    /** Item state changed*/
    static final String ACTION_ITEM_STATE = BuildConfig.APPLICATION_ID + ".ACTION_ITEM_STATE";

    static final String ACTION_ALARM = BuildConfig.APPLICATION_ID + ".ACTION_ALARM";
    static final String ACTION_PAUSE_ADVERTISER = BuildConfig.APPLICATION_ID + ".ACTION_PAUSE_ADVERTISER";
    static final String ACTION_STOP_ADVERTISER = BuildConfig.APPLICATION_ID + ".ACTION_STOP_ADVERTISER";

    public static final int EVENT_ADVERTISER_ADDED      = 1;
    public static final int EVENT_ADVERTISER_STARTED    = 2;
    public static final int EVENT_ADVERTISER_STOPPED    = 3;
    public static final int EVENT_ADVERTISER_FAILED     = 4;
    public static final int EVENT_ADVERTISE_UNSUPPORTED = 5;

    // TODO - separate this from the bunch...
    public static final int EVENT_SHORTURL_FAILED       = 6;

    /** Intent extras */
    public static final String EXTRA_ITEM_ID      = "id";
    public static final String EXTRA_BEACON_EVENT = "type";
    public static final String EXTRA_ERROR        = "error";
    public static final String EXTRA_ERROR_CODE   = "code";

    public class LocalBinder extends Binder {
        public BleService getUriioService() {
            return BleService.this;
        }
    }

    private AdvertisersManager mAdvertisersManager = null;
    private AlarmManager mAlarmManager = null;

    /** app BroadcastReceiver, specified as the value of the "com.uriio.receiver" meta-data */
    private ComponentName mAppReceiver = null;

    /** Keeps track whether the service was started. */
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

    //region Service
    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mAppReceiver = new ComponentName(this, getAppReceiver());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!mStarted) {
            initializeService();
            mStarted = true;
        }

        // intent is null if service restarted
        if (null != intent) {
            Receiver.completeWakefulIntent(intent);

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

    @Override
    public void onDestroy() {
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

            for (Beacon activeItem : Beacons.getActive()) {
                mAlarmManager.cancel(getItemRestartPendingIntent(activeItem));
            }
            Beacons.getActive().clear();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);

            mStarted = false;
        }

        super.onDestroy();
    }
    //endregion

    private void handleBluetoothStateChanged(Intent intent) {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
//            log("Bluetooth event state=" + state);
        if (BluetoothAdapter.STATE_TURNING_OFF == state) {
            for (Beacon activeItem : Beacons.getActive()) {
                // cancel beacon updater alarm
                mAlarmManager.cancel(getItemRestartPendingIntent(activeItem));

                activeItem.setStatus(Beacon.STATUS_NO_BLUETOOTH);
                Advertiser advertiser = activeItem.getAdvertiser();
                if (null != advertiser) {
                    advertiser.setStoppedState();
                }
            }

            // when an advertiser will start, the service will start in foreground again
            stopForeground(true);

            mAdvertisersManager.onBluetoothOff();
            broadcastBeaconEvent(EVENT_ADVERTISER_STOPPED, null);
        } else if (BluetoothAdapter.STATE_ON == state) {
            for (Beacon activeItem : Beacons.getActive()) {
                if (activeItem.getStorageState() == Storage.STATE_ENABLED) {
                    activeItem.onAdvertiseEnabled(this);
                } else {
                    // beacon was not enabled, but in the active list, aka PAUSED
                    activeItem.setStatus(Beacon.STATUS_ADVERTISE_PAUSED);
                }
            }
        }
    }

    private void handleItemState(Intent intent) {
        long itemId = intent.getLongExtra(EXTRA_ITEM_ID, 0);
        if (0 != itemId) {
//            Util.log(TAG, "Received intent for item " + itemId);
            Beacon item = Beacons.findActive(itemId);

            if (null != item) {
                switch (item.getStorageState()) {
                    case Storage.STATE_ENABLED:
                        item.onAdvertiseEnabled(this);
                        broadcastBeaconEvent(EVENT_ADVERTISER_ADDED, item);
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

    private void initializeService() {
        Beacons.reinitialize(this);

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
        for (Beacon item : Beacons.getActive()) {
            if (item.getStorageState() == Storage.STATE_ENABLED) {
                item.onAdvertiseEnabled(this);
            }
        }
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

    private void broadcastLocalIntent(Intent intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastBeaconEvent(int event, Beacon beacon) {
        broadcastLocalIntent(makeBeaconEventIntent(event, beacon));
    }

    public void broadcastError(Beacon beacon, int event, String error) {
        broadcastLocalIntent(makeBeaconEventIntent(event, beacon).putExtra(EXTRA_ERROR, error));
    }

    private void broadcastError(Beacon beacon, int event, int errorCode) {
        broadcastLocalIntent(makeBeaconEventIntent(event, beacon).putExtra(EXTRA_ERROR_CODE, errorCode));
    }

    private Intent makeBeaconEventIntent(int event, Beacon beacon) {
        Intent intent = new Intent(ACTION_BEACONS).putExtra(EXTRA_BEACON_EVENT, event);
        if (null != beacon) {
            intent.putExtra(EXTRA_ITEM_ID, beacon.getId());
            intent.putExtra("kind", beacon.getKind());
        }
        return intent;
    }

    private Beacon findActiveItem(Advertiser advertiser) {
        for (Beacon item : Beacons.getActive()) {
            if (item.getAdvertiser() == advertiser) return item;
        }
        return null;
    }

    //region AdvertisersManager.BLEListener
    @Override
    public void onBLEAdvertiseStarted(Advertiser advertiser) {
        Beacon item = findActiveItem(advertiser);
        if (null != item) {
            item.setStatus(Beacon.STATUS_ADVERTISING);

            long scheduledRefreshTime = item.getScheduledRefreshTime();

            if (scheduledRefreshTime > 0) {
                PendingIntent pendingIntent = getItemRestartPendingIntent(item);

                // schedule alarm for next onAdvertiseEnabled
                if(BuildConfig.DEBUG) Log.d(TAG, "Scheduling alarm for " + item.getId() + " in " + scheduledRefreshTime);
                scheduleRTCAlarm(scheduledRefreshTime, pendingIntent);
            }

            broadcastBeaconEvent(EVENT_ADVERTISER_STARTED, item);

            updateForegroundNotification(true);
        }
    }

    private void updateForegroundNotification(boolean newAdvertiserStarted) {
        NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
        int totalRunning = fillInboxStyleNotification(inboxStyle);

        if (totalRunning > 0) {
            String contentText = totalRunning + " beacons are broadcasting";
            inboxStyle.setSummaryText(totalRunning + " beacons running");

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                    .setContentIntent(PendingIntent.getBroadcast(this, 0,
                            new Intent(ACTION_NOTIFICATION_CONTENT).setComponent(mAppReceiver), 0))
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("Beacon transmission active")
                    .setContentText(contentText)
                    .setStyle(inboxStyle)
                    .setOngoing(true)
                    .setColor(0xff800000)
                    .setNumber(totalRunning);

            builder.addAction(0, "Pause all", PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_PAUSE_ADVERTISER, null, this, Receiver.class), PendingIntent.FLAG_ONE_SHOT));
            builder.addAction(0, "Stop all", PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_STOP_ADVERTISER, null, this, Receiver.class), PendingIntent.FLAG_ONE_SHOT));

            if (newAdvertiserStarted && totalRunning == 1) {
                startForeground(NOTIFICATION_ID, builder.build());
            }
            else {
                NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                notificationManager.notify(NOTIFICATION_ID, builder.build());
            }
        }
        else {
            stopForeground(true);
        }
    }

    private CharSequence getNotificationContentText(Beacon item) {
        if (null != item.getName() && item.getName().length() > 0) return item.getName();
        return "[no name]";
    }

    @Override
    public void onBLEAdvertiseFailed(Advertiser advertiser, int errorCode) {
        Beacon item = findActiveItem(advertiser);
        if (null != item) {
            item.setStatus(Beacon.STATUS_ADVERTISE_FAILED);
            broadcastError(item, EVENT_ADVERTISER_FAILED, errorCode);
        }
    }

    @Override
    public void onBLEAdvertiseNotSupported() {
        broadcastBeaconEvent(EVENT_ADVERTISE_UNSUPPORTED, null);
    }
    //endregion

    private static final String[] _txPowers = {"Ultra-low", "Low", "Medium", "High"};
    private static final String[] _advModes = {"Low-power", "Balanced", "Low latency"};

    private int fillInboxStyleNotification(NotificationCompat.InboxStyle inboxStyle) {
        int totalRunning = 0;
        for (Beacon item : Beacons.getActive()) {
            if (item.getStatus() != Beacon.STATUS_ADVERTISING) continue;

            ++totalRunning;

            StringBuilder builder = new StringBuilder();

            if (builder.length() > 0) builder.append("\n");
            builder.append(getNotificationContentText(item)).append("\n");

            switch (item.getType()) {
                case Beacon.EDDYSTONE_URL:
                    builder.append(((EddystoneURL) item).getURL());
                    break;
                case Beacon.EDDYSTONE_UID:
                    builder.append("Eddystone-UID");
                    break;
                case Beacon.EDDYSTONE_EID:
                    builder.append("Eddystone-EID");
                    break;
                case Beacon.IBEACON:
                    builder.append("iBeacon");
                    break;
                case Beacon.EPHEMERAL_URL:
                    builder.append(((EphemeralURL) item).getLongUrl());
                    break;
            }
            builder.append(" ").append(_txPowers[item.getTxPowerLevel()])
                    .append(",  ").append(_advModes[item.getAdvertiseMode()]);

            inboxStyle.addLine(builder);
        }

        return totalRunning;
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
            if (0 == Beacons.getActive().size()) {
//                Toast.makeText(this, "BLE service stopped, no beacons are enabled.", Toast.LENGTH_LONG).show();
                stopSelf();
            }
        }
        broadcastBeaconEvent(EVENT_ADVERTISER_STOPPED, item);

        updateForegroundNotification(false);
    }

    public void scheduleRTCAlarm(long triggerAtMillis, PendingIntent operation) {
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
    }

    private PendingIntent getItemRestartPendingIntent(Beacon item) {
        Intent intent = new Intent(ACTION_ALARM, null, this, Receiver.class);
        intent.putExtra(EXTRA_ITEM_ID, item.getId());

        // use the item id as the private request code, or else the Intent is "identical" for all items and is reused!
        return PendingIntent.getBroadcast(this, (int) item.getId(), intent, 0);
    }

    private String getAppReceiver() {
        ApplicationInfo appInfo;
        try {
            appInfo = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("App package not found");
        }

        String receiverClassName = null;
        // metadata is null when no entries exist
        if (null != appInfo && null != appInfo.metaData) {
            receiverClassName = appInfo.metaData.getString("com.uriio.receiver");
        }

        return null != receiverClassName ? receiverClassName : Receiver.class.getName();
    }
}