package com.uriio.beacons;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.WakefulBroadcastReceiver;

/**
 * Receiver used for scheduled Alarms.
 * Created on 7/19/2015.
 */
public class AlarmReceiver extends WakefulBroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Util.log(TAG, "onReceive: " + intent);

        long itemId = intent.getLongExtra(BleService.EXTRA_ITEM_ID, 0);
        if (0 != itemId) {
            // start the service, signaling we want to refresh a specific beacon
            Intent serviceIntent = new Intent(context, BleService.class)
                .putExtra(BleService.EXTRA_ITEM_ID, itemId)
                .putExtra(BleService.EXTRA_COMMAND, BleService.COMMAND_REFRESH);

            // Start the wakeful service, keeping the device awake while it is launching.
            startWakefulService(context, serviceIntent);
        }
    }
}
