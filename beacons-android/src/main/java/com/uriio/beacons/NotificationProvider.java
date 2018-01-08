package com.uriio.beacons;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.text.Html;
import android.text.SpannableStringBuilder;

import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.model.Beacon;

@SuppressWarnings("WeakerAccess")
public class NotificationProvider {
    private static final String TAG = "NotificationProvider";

    // use the library's package namespace as the active beacons Notification Channel ID
    private static final String NOTIFICATION_CHANNEL_ID = BuildConfig.APPLICATION_ID;

    private static final int NOTIFICATION_ID = 0xB33C0000;

    private static final String[] NOTIF_FORMAT_TX_POWER = {
            "<font color=\"#008000\">%s</font>",
            "<font color=\"#000080\">%s</font>",
            "<font color=\"#ff8040\">%s</font>",
            "<font color=\"#ff0000\"><b>%s</b></font>"
    };

    private static final String[] NOTIF_FORMAT_ADV_MODES = {
            "<font color=\"#008000\">%d Hz</font>",
            "<font color=\"#000080\">%d Hz</font>",
            "<font color=\"#ff0000\"><b>%d Hz</b></font>"
    };

    private final Context mContext;

    protected NotificationProvider(Context context) {
        mContext = context;
    }

    public Context getContext() {
        return mContext;
    }

    public int getNotificationId() {
        return NOTIFICATION_ID;
    }

    /**
     * Creates a default notification. Apps should override the notification to blend in better
     * with the desired appearance, content, priority, colors, etc.
     * @param numRunningAdvertisers Total actively running beacons (excluding paused)
     */
    protected Notification makeNotification(NotificationManager notificationManager, int numRunningAdvertisers) {
        NotificationCompat.Builder builder = newBuilder(notificationManager, numRunningAdvertisers);

        addActions(builder);
        setInboxStyle(builder, numRunningAdvertisers);

        return builder
                .setContentIntent(PendingIntent.getBroadcast(mContext, 0,
                        new Intent(BleService.ACTION_NOTIFICATION_CONTENT)
                                .setComponent(new ComponentName(mContext, Receiver.class)), 0))
                .build();
    }

    /**
     * Called when the last BLE advertiser stopped and the service will no longer run in foreground.
     * @return True to remove the existing notification, false otherwise.
     */
    protected boolean onStoppedForeground() {
        return true;
    }

    protected NotificationCompat.Builder newBuilder(NotificationManager notificationManager, int numRunning) {
        checkChannel(notificationManager);

        return new NotificationCompat.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(mContext.getString(R.string.com_uriio_notification_title))
                .setContentText(numRunning + " beacons broadcasted by app.")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setColor(0xff000080)
                .setNumber(numRunning);
    }

    private PendingIntent makePendingIntent(String action) {
        return PendingIntent.getBroadcast(mContext, 0,
                new Intent(action, null, mContext, Receiver.class), PendingIntent.FLAG_ONE_SHOT);
    }

    protected void addActions(NotificationCompat.Builder builder) {
        builder.addAction(0, "Pause", makePendingIntent(BleService.ACTION_PAUSE_ALL))
                .addAction(0, "Stop", makePendingIntent(BleService.ACTION_STOP_ALL));
    }

    protected void setInboxStyle(NotificationCompat.Builder builder, int numRunning) {
        NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();

        fillInboxStyleNotification(inboxStyle);

        inboxStyle.setSummaryText(numRunning + " beacons running")
                .setBigContentTitle("These beacons are running");

        builder.setStyle(inboxStyle)
                .setContentText(numRunning + " beacons broadcasted by app. Expand for details.");
    }

    private void checkChannel(NotificationManager notificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID);

            if (null == notificationChannel) {
                notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                        mContext.getString(R.string.com_uriio_notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                notificationChannel.setDescription(mContext.getString(R.string.com_uriio_notification_channel_description));
            }

            notificationManager.createNotificationChannel(notificationChannel);
        }
    }

    private void fillInboxStyleNotification(NotificationCompat.InboxStyle inboxStyle) {
        Resources resources = mContext.getResources();
        CharSequence[] txPowers = resources.getTextArray(R.array.com_uriio_txPowerNames);

        for (Beacon beacon : Beacons.getActive()) {
            if (beacon.getAdvertiseState() != Beacon.ADVERTISE_RUNNING) continue;

            SpannableStringBuilder builder = new SpannableStringBuilder();
            builder.append(beacon.getNotificationSubject());
            builder.append(" ")
                    .append(Html.fromHtml(String.format(NOTIF_FORMAT_TX_POWER[beacon.getTxPowerLevel()],
                            txPowers[beacon.getTxPowerLevel()])))
                    .append(" ")
                    .append(Html.fromHtml(String.format(NOTIF_FORMAT_ADV_MODES[beacon.getAdvertiseMode()],
                            1000 / Advertiser.getPduIntervals()[beacon.getAdvertiseMode()])));

            inboxStyle.addLine(builder);
        }
    }
}
