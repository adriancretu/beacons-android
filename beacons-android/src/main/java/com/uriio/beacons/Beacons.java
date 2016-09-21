package com.uriio.beacons;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.uriio.beacons.model.Beacon;
import com.uriio.beacons.model.EddystoneBase;
import com.uriio.beacons.model.EddystoneURL;
import com.uriio.beacons.model.EphemeralURL;
import com.uriio.beacons.model.iBeacon;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Beacons API wrapper.
 */
public class Beacons {
    private static final String TAG = "Beacons";

    private static final String PREF_API_KEY = "apiKey";
    private static final String PREF_DB_NAME = "db";
    private static final String PREFS_FILENAME = "com.uriio.beacons";

    private static final String DEFAULT_DATABASE_NAME = "com.uriio.beacons";

    /** Singleton */
    private static Beacons _instance = null;

    // don't leak the context; also makes Lint happy
    private WeakReference<Context> mAppContext;

    /** List of active items **/
    private List<Beacon> mActiveItems = new ArrayList<>();

    private Beacons(Context context) {
        setContext(context);
    }

    private void setContext(Context context) {
        mAppContext = new WeakReference<>(context.getApplicationContext());
    }

    static Beacons getInstance() {
        if (null == _instance) {
            throw new RuntimeException("Not initialized");
        }
        return _instance;
    }

    /** Called on BleService (re)start; restores singleton and the latest known state */
    static void reinitialize(Context context) {
        // restore from saved config
        SharedPreferences sharedPrefs = context.getSharedPreferences(PREFS_FILENAME, 0);
        initialize(context, sharedPrefs.getString(PREF_DB_NAME, DEFAULT_DATABASE_NAME));
    }

    /**
     * Initialize the API and use a custom persistence namespace.
     * @param context   The calling context from which to get the application context.
     * @param dbName    Database name to use for opening and storing beacons.
     */
    public static void initialize(Context context, String dbName) {
        if (null != _instance) {
            // singleton exists, so just set the app context
            _instance.setContext(context);
            Log.d(TAG, "initialized");
        }
        else {
            _instance = new Beacons(context);

            if (null == dbName) dbName = DEFAULT_DATABASE_NAME;
            Storage.init(context, dbName);

            // save this last config so we can restore ourselves if needed
            context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE).edit()
                    .remove(PREF_API_KEY)             // obsolete
                    .putString(PREF_DB_NAME, dbName)
                    .apply();

            // restore active items
            Cursor cursor = Storage.getInstance().getAllItems(false);
            while (cursor.moveToNext()) {
                Beacon item = Storage.itemFromCursor(cursor);
                getActive().add(item);
            }
            cursor.close();

            // (re)start the BLE service
            context.startService(new Intent(context, BleService.class));
        }
    }

    /**
     * Initialize the API with the default persistence namespace.
     * @param context   The calling context.
     */
    @SuppressWarnings("unused")
    public static void initialize(Context context) {
        initialize(context, DEFAULT_DATABASE_NAME);
    }

    /**
     * Stops the background BLE service all-together.
     */
    public static void shutdown() {
        Context context = getContext();
        if (null != context) {
            context.stopService(new Intent(context, BleService.class));

            // detach Context weak ref
            _instance.mAppContext.clear();
        }
    }

    static void onBleServiceDestroyed() {
        // time to LET IT GO
        _instance = null;
    }

    public static<T extends Beacon> T add(T beacon/*, boolean persistent*/) {
        // don't add an already persisted beacon
        if (beacon.getId() > 0) return beacon;

        if (true/*persistent*/) {
            switch (beacon.getType()) {
                case Beacon.EDDYSTONE_URL:
                case Beacon.EDDYSTONE_UID:
                case Beacon.EDDYSTONE_EID:
                    Storage.getInstance().insertEddystoneItem((EddystoneBase) beacon);
                    break;
                case Beacon.EPHEMERAL_URL:
                    Storage.getInstance().insertUriioItem((EphemeralURL) beacon);
                    break;
                case Beacon.IBEACON:
                    Storage.getInstance().insertAppleBeaconItem((iBeacon) beacon);
                    break;
            }
        }

        getInstance().mActiveItems.add(beacon);
        enable(beacon);

        return beacon;
    }

    public static EddystoneURL add(String url) {
        return add(new EddystoneURL(url));
    }

    /**
     * Deletes a beacon. If beacon is active, it will be stopped.
     * @param id    The beacon ID
     */
    public static void delete(long id) {
        Storage.getInstance().deleteItem(id);

        Beacon item = findActive(id);
        if (item != null) {
            item.setStorageState(Storage.STATE_STOPPED);
            sendStateBroadcast(id);
        }
    }

    public static void delete(Beacon beacon) {
        if (null != beacon && beacon.getId() > 0) delete(beacon.getId());
    }

    private static void setState(Beacon beacon, int state) {
        long itemId = beacon.getId();

        if (itemId > 0 && state >= 0 && state <= 2) {
            Beacon item = findActive(itemId);
            if (null != item) {
                if (state != item.getStorageState()) {
                    // item changed state
                    item.setStorageState(state);
                    Storage.getInstance().updateItemState(itemId, state);
                }
            }
            else if (state != Storage.STATE_STOPPED) {
                // beacon was not in active list, save new state if not stopped
                beacon.setStorageState(state);
                Storage.getInstance().updateItemState(itemId, state);

                getActive().add(beacon);
            }

            sendStateBroadcast(itemId);
        }
    }

    public static void enable(Beacon beacon) {
        setState(beacon, Storage.STATE_ENABLED);
    }

    public static void pause(Beacon beacon) {
        setState(beacon, Storage.STATE_PAUSED);
    }

    public static void stop(Beacon beacon) {
        setState(beacon, Storage.STATE_STOPPED);
    }

    /**
     * @param id    The beacon ID
     * @return  A beacon instance, either currently active, or loaded from persistent storage.
     */
    public static Beacon get(long id) {
        Beacon beacon = findActive(id);
        return null == beacon ? loadItem(id) : beacon;
    }

    /**
     * @param id    The beacon ID
     * @return Active (or paused) beacon, or null if beacon is stopped (or doesn't exist).
     */
    public static Beacon findActive(long id) {
        for (Beacon item : getActive()) {
            if (item.getId() == id) {
                return item;
            }
        }
        return null;
    }

    private static Beacon loadItem(long itemId) {
        Beacon item = null;

        Cursor cursor = Storage.getInstance().getItem(itemId);
        if (cursor.moveToNext()) {
            item = Storage.itemFromCursor(cursor);
        }
        cursor.close();

        return item;
    }

    public static void saveItem(Beacon item, boolean restartBeacon) {
        if (item.getId() > 0) {
            Storage.getInstance().save(item);
        }

        if (restartBeacon) {
            restartBeacon(item);
        }
    }

    public static Storage getStorage() {
        return Storage.getInstance();
    }

    /**
     * @return The collection of all active items. Not all items might actually be broadcasting.
     * To check if an item is broadcasting call getAdvertiser() on it.
     */
    public static List<Beacon> getActive() {
        return getInstance().mActiveItems;
    }

    public static Cursor getStoppedItems() {
        return Storage.getInstance().getAllItems(true);
    }

    public static Context getContext() {
        return getInstance().mAppContext.get();
    }

    private static void sendStateBroadcast(long itemId) {
        Context context = getInstance().mAppContext.get();
        if (null != context) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                    new Intent(BleService.ACTION_ITEM_STATE).putExtra(BleService.EXTRA_ITEM_ID, itemId));
        }
    }

    public static void restartBeacon(Beacon item) {
        if (item.getStatus() == Beacon.STATUS_ADVERTISING) {
            setState(item, Storage.STATE_ENABLED);
        }
    }
}