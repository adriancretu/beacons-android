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
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.util.Log;

import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.ble.AdvertisersManager;
import com.uriio.beacons.model.Beacon;
import com.uriio.beacons.model.EddystoneURL;

import java.util.UUID;

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
    public static final String ACTION_ITEM_STATE = BuildConfig.APPLICATION_ID + ".ACTION_ITEM_STATE";

    public static final String ACTION_ALARM     = BuildConfig.APPLICATION_ID + ".ACTION_ALARM";
    static final String ACTION_PAUSE_ADVERTISER = BuildConfig.APPLICATION_ID + ".ACTION_PAUSE_ADVERTISER";
    static final String ACTION_STOP_ADVERTISER  = BuildConfig.APPLICATION_ID + ".ACTION_STOP_ADVERTISER";

    public static final int EVENT_ADVERTISER_ADDED      = 1;
    public static final int EVENT_ADVERTISER_STARTED    = 2;
    public static final int EVENT_ADVERTISER_STOPPED    = 3;
    public static final int EVENT_ADVERTISER_FAILED     = 4;
    public static final int EVENT_ADVERTISE_UNSUPPORTED = 5;

    // TODO - separate this from the bunch...
    public static final int EVENT_SHORTURL_FAILED       = 6;

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
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);

            mStarted = false;
        }

        Beacons.onBleServiceDestroyed();

        super.onDestroy();
    }
    //endregion

    private void handleBluetoothStateChanged(Intent intent) {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
//            log("Bluetooth event state=" + state);
        if (BluetoothAdapter.STATE_TURNING_OFF == state) {
            for (Beacon beacon : Beacons.getActive()) {
                // cancel beacon updater alarm
                mAlarmManager.cancel(beacon.getAlarmPendingIntent(this));

                beacon.setAdvertiseState(Beacon.ADVERTISE_NO_BLUETOOTH);
                Advertiser advertiser = beacon.getAdvertiser();
                if (null != advertiser) {
                    advertiser.setStoppedState();
                }
            }

            // when an advertiser will start, the service will start in foreground again
            stopForeground(true);

            mAdvertisersManager.onBluetoothOff();
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
        Beacon beacon = Beacons.findActive(intent.getLongExtra(EXTRA_ITEM_STORAGE_ID, 0));

        // unsaved beacon, try finding by UUID
        if (null == beacon) beacon = Beacons.findActive((UUID) intent.getSerializableExtra(EXTRA_ITEM_ID));

        if (null != beacon) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Received itemState intent for " + beacon.getUUID());

            switch (beacon.getActiveState()) {
                case Beacon.ACTIVE_STATE_ENABLED:
                    beacon.onAdvertiseEnabled(this);
                    broadcastBeaconEvent(EVENT_ADVERTISER_ADDED, beacon);
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
        for (Beacon item : Beacons.getActive()) {
            if (item.getActiveState() == Beacon.ACTIVE_STATE_ENABLED) {
                item.onAdvertiseEnabled(this);
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

        if (!isAdvertisingSupported()) {
            return false;
        }

        // stop current advertiser for this beacon
        Advertiser existingAdvertiser = beacon.getAdvertiser();
        if (null != existingAdvertiser) {
            mAdvertisersManager.enableAdvertiser(existingAdvertiser, false);
        }

        Advertiser advertiser = beacon.createAdvertiser(mAdvertisersManager);

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

    //region AdvertisersManager.BLEListener
    @Override
    public void onBLEAdvertiseStarted(Advertiser advertiser) {
        Beacon beacon = findActiveBeacon(advertiser);
        if (null != beacon) {
            beacon.setAdvertiseState(Beacon.ADVERTISE_RUNNING);

            long scheduledRefreshTime = beacon.getScheduledRefreshTime();

            if (scheduledRefreshTime > 0) {
                // schedule alarm for next onAdvertiseEnabled
                if(BuildConfig.DEBUG) Log.d(TAG, "Scheduling alarm for " + beacon.getUUID() + " in " + scheduledRefreshTime);
                scheduleRTCAlarm(scheduledRefreshTime, beacon.getAlarmPendingIntent(this));
            }

            broadcastBeaconEvent(EVENT_ADVERTISER_STARTED, beacon);

            updateForegroundNotification(true);
        }
    }

    private void updateForegroundNotification(boolean newAdvertiserStarted) {
        NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
        int totalRunning = fillInboxStyleNotification(inboxStyle);

        if (totalRunning > 0) {
            String contentText = totalRunning + " beacons broadcasted by app. Expand for details.";
            inboxStyle.setSummaryText(totalRunning + " beacons running");

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                    .setContentIntent(PendingIntent.getBroadcast(this, 0,
                            new Intent(ACTION_NOTIFICATION_CONTENT).setComponent(mAppReceiver), 0))
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("Beacons active")
                    .setContentText(contentText)
                    .setStyle(inboxStyle)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setColor(0xff800000)
                    .setNumber(totalRunning);

            builder.addAction(0, "Pause all", PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_PAUSE_ADVERTISER, null, this, Receiver.class), PendingIntent.FLAG_ONE_SHOT));
            builder.addAction(0, "Stop all", PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_STOP_ADVERTISER, null, this, Receiver.class), PendingIntent.FLAG_ONE_SHOT));

            if (newAdvertiserStarted && 1 == totalRunning) {
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

    @Override
    public void onBLEAdvertiseFailed(Advertiser advertiser, int errorCode) {
        Beacon beacon = findActiveBeacon(advertiser);
        if (null != beacon) {
            beacon.setError("Failure " + errorCode);
            broadcastError(beacon, EVENT_ADVERTISER_FAILED, errorCode);
        }
    }

    @Override
    public void onBLEAdvertiseNotSupported() {
        broadcastBeaconEvent(EVENT_ADVERTISE_UNSUPPORTED, null);
    }
    //endregion

    private static final String[] _txPowers = {
            "<font color=\"#008000\">ULP</font>",
            "<font color=\"#000080\">Low</font>",
            "<font color=\"#ff8040\">Medium</font>",
            "<font color=\"#ff0000\"><b>High</b></font>"
    };
    private static final String[] _advModes = {
            "<font color=\"#008000\">1 Hz</font>",
            "<font color=\"#000080\">4 Hz</font>",
            "<font color=\"#ff0000\"><b>10 Hz</b></font>"
    };

    private int fillInboxStyleNotification(NotificationCompat.InboxStyle inboxStyle) {
        int totalRunning = 0;
        for (Beacon item : Beacons.getActive()) {
            if (item.getAdvertiseState() != Beacon.ADVERTISE_RUNNING) continue;

            ++totalRunning;

            SpannableStringBuilder builder = new SpannableStringBuilder();

            switch (item.getType()) {
                case Beacon.EDDYSTONE_URL:
                case Beacon.EPHEMERAL_URL:
                    String url = ((EddystoneURL) item).getURL();

                    if (null == url) url = "<no URL>";
                    else if (url.length() == 0) url = "<empty URL>";

                    builder.append(url);
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
            }
            builder.append(" ").append(Html.fromHtml(_txPowers[item.getTxPowerLevel()]))
                    .append(" ").append(Html.fromHtml(_advModes[item.getAdvertiseMode()]));

            inboxStyle.addLine(builder);
        }

        return totalRunning;
    }

    private void stopBeacon(Beacon beacon, boolean remove) {
        Advertiser advertiser = beacon.getAdvertiser();

        // stop the advertising and cancel any pending alarm
        if (null != advertiser && advertiser.getStatus() == Advertiser.STATUS_RUNNING) {
            mAdvertisersManager.enableAdvertiser(advertiser, false);
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

    public void scheduleRTCAlarm(long triggerAtMillis, PendingIntent operation) {
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
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