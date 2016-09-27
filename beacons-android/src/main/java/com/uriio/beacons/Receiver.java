package com.uriio.beacons;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.WakefulBroadcastReceiver;

import com.uriio.beacons.model.Beacon;

/**
 * Receiver used for scheduled Alarms and other explicit intents.
 */
public class Receiver extends WakefulBroadcastReceiver {
    private static final String TAG = "Receiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (BuildConfig.DEBUG) Util.log(TAG, "onReceive: " + intent);

        switch (intent.getAction()) {
            case BleService.ACTION_NOTIFICATION_CONTENT:
                // Toast.makeText(context, "Please implement a receiver in your app", Toast.LENGTH_SHORT).show();
                break;
            case BleService.ACTION_STOP_ADVERTISER:
                for (Beacon beacon : Beacons.getActive()) {
                    beacon.stop();
                }
                break;
            case BleService.ACTION_PAUSE_ADVERTISER:
                for (Beacon beacon : Beacons.getActive()) {
                    beacon.pause();
                }
                break;
            case BleService.ACTION_ALARM:
                // we need to restart a specific beacon
                // Start the wakeful service, keeping the device awake while it is launching.
                startWakefulService(context, new Intent(context, BleService.class).putExtras(intent));
                break;
        }
    }
}
