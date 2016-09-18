package com.uriio.beacons;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.uriio.beacons.api.ApiClient;
import com.uriio.beacons.model.Beacon;
import com.uriio.beacons.model.EddystoneBase;
import com.uriio.beacons.model.EddystoneURL;
import com.uriio.beacons.model.EphemeralURL;
import com.uriio.beacons.model.iBeacon;

import java.util.ArrayList;
import java.util.List;

/**
 * BLE Beacons API
 * Created on 4/28/2016.
 */
public class Beacons {
    /**
     * Generic listener for the result of an operation.
     * @param <T>    Specialized result type
     */
    public interface OnResultListener<T> {
        /**
         * Result callback.
         * @param result    The call's result, or null if there was an error.
         * @param error     Encountered error, or null if none.
         */
        void onResult(T result, Throwable error);
    }

    private static final String TAG = "Beacons";

    private static final String PREF_API_KEY = "apiKey";
    private static final String PREF_DB_NAME = "db";
    private static final String PREFS_FILENAME = "com.uriio.beacons";

    private static final String DEFAULT_DATABASE_NAME = "com.uriio.beacons";

    /** Singleton */
    private static Beacons _instance = null;

    private final Context mAppContext;
    private final ApiClient mUriioClient;

    /** List of active items **/
    private List<Beacon> mActiveItems = new ArrayList<>();

    private Beacons(Context context, String apiKey) {
        mAppContext = context.getApplicationContext();
        mUriioClient = null == apiKey ? null : new ApiClient(apiKey);
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
        String apiKey = sharedPrefs.getString(PREF_API_KEY, null);
        initialize(context, apiKey, sharedPrefs.getString(PREF_DB_NAME, DEFAULT_DATABASE_NAME));
    }

    /**
     * Initialize the API and use a custom persistence namespace.
     * @param context   The calling context from which to get the application context.
     * @param apiKey    Your API Key. May be null if you don't intend to use Ephemeral URLs.
     * @param dbName    Database name to use for opening and storing beacons.
     */
    public static void initialize(Context context, String apiKey, String dbName) {
        if (null != _instance) Log.w(TAG, "Already initialized");
        else {
            _instance = new Beacons(context, apiKey);
            Storage.init(context, dbName);

            // save this last config so we can restore ourselves if needed
            context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE).edit()
                    .putString(PREF_API_KEY, apiKey)
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
     * @param context   The calling context from which to get the application context.
     * @param apiKey    Your API Key. May be null if you don't intend to use Ephemeral URLs.
     */
    @SuppressWarnings("unused")
    public static void initialize(Context context, String apiKey) {
        initialize(context, apiKey, DEFAULT_DATABASE_NAME);
    }

    public static void initialize(Context context) {
        initialize(context, null);
    }

    public static<T extends Beacon> T add(T spec/*, boolean persistent*/) {
        // don't add an already persisted beacon
        if (spec.getId() > 0) return spec;

        if (true/*persistent*/) {
            switch (spec.getType()) {
                case Beacon.EDDYSTONE_URL:
                case Beacon.EDDYSTONE_UID:
                case Beacon.EDDYSTONE_EID:
                    Storage.getInstance().insertEddystoneItem((EddystoneBase) spec);
                    break;
                case Beacon.EPHEMERAL_URL:
                    Storage.getInstance().insertUriioItem((EphemeralURL) spec);
                    break;
                case Beacon.IBEACON:
                    Storage.getInstance().insertAppleBeaconItem((iBeacon) spec);
                    break;
            }
        }

        getInstance().mActiveItems.add(spec);
        setState(spec.getId(), Storage.STATE_ENABLED);

        return spec;
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

    public static void setState(long itemId, int state) {
        if (state >= 0 && state <= 2) {
            Beacon item = findActive(itemId);
            if (null != item) {
                if (state != item.getStorageState()) {
                    item.setStorageState(state);
                    Storage.getInstance().updateItemState(itemId, state);
                }
            }
            else if (state == Storage.STATE_ENABLED) {
                Storage.getInstance().updateItemState(itemId, state);

                item = loadItem(itemId);
                if (null != item) {
                    getActive().add(item);
                }
            }

            if (null != item) {
                sendStateBroadcast(itemId);
            }
        }
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

    /**
     * Interface to an UriIO API client, used to register, update, and issue ephemeral URLs.
     * @return API client, or null if there was no API Key specified at initialize time.
     */
    public static ApiClient uriio() {
        return getInstance().mUriioClient;
    }

    private static void sendStateBroadcast(long itemId) {
        LocalBroadcastManager.getInstance(getInstance().mAppContext).sendBroadcast(
            new Intent(BleService.ACTION_ITEM_STATE).putExtra(BleService.EXTRA_ITEM_ID, itemId));
    }

    public static void restartBeacon(Beacon item) {
        if (item.getStatus() == Beacon.STATUS_ADVERTISING) {
            setState(item.getId(), Storage.STATE_ENABLED);
        }
    }
}