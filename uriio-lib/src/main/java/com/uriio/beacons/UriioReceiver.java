package com.uriio.beacons;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.util.Log;

/**
 * Receiver used for scheduled Alarms.
 * Created on 7/19/2015.
 */
public class UriioReceiver extends WakefulBroadcastReceiver {
    private static final String TAG = "UriioReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Util.log(TAG, "onReceive: " + intent);

        long itemId = intent.getLongExtra(UriioService.EXTRA_ITEM_ID, 0);
        if (0 != itemId) {
            // start the service, signaling we want to refresh a specific beacon
            Intent serviceIntent = new Intent(context, UriioService.class)
                .putExtra(UriioService.EXTRA_ITEM_ID, itemId)
                .putExtra(UriioService.EXTRA_COMMAND, UriioService.COMMAND_REFRESH);

            // Start the wakeful service, keeping the device awake while it is launching.
            startWakefulService(context, serviceIntent);
        }
    }
}
