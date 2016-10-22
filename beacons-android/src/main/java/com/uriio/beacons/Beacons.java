package com.uriio.beacons;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.support.annotation.NonNull;
import android.util.Log;

import com.uriio.beacons.model.Beacon;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Beacons API wrapper.
 */
public class Beacons {
    private static final String TAG = "Beacons";

    private static final String PREF_API_KEY = "apiKey";     // obsolete
    private static final String PREF_DB_NAME = "db";         // obsolete
    private static final String PREFS_FILENAME = "com.uriio.beacons";

    private static final String DEFAULT_DATABASE_NAME = "com.uriio.beacons";

    /** Singleton */
    private static Beacons _instance = null;

    // don't leak the context; also makes Lint happy
    private WeakReference<Context> mAppContext;

    /** List of active items **/
    private List<Beacon> mActiveItems = null;
    private boolean mInitialized = false;

    private Beacons(Context context) {
        setContext(context);
    }

    private void setContext(Context context) {
        mAppContext = new WeakReference<>(context.getApplicationContext());
    }

    private static Beacons getInstance() {
        if (null == _instance) {
            throw new RuntimeException("Beacons.initialize() not called");
        }
        return _instance;
    }

    /**
     * Initializes the SDK.
     * @param context   The calling context from which to get the application context.
     */
    public static void initialize(@NonNull Context context) {
        if (null != _instance) {
            // singleton exists, so just set the app context
            _instance.setContext(context);
            if (BuildConfig.DEBUG) Log.d(TAG, "re-initialized");
        }
        else {
            if (BuildConfig.DEBUG) Log.d(TAG, "initialize");

            _instance = new Beacons(context);

            // clear obsolete preferences
            context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE).edit().clear().apply();

            Storage.init(context, DEFAULT_DATABASE_NAME);
        }

        if (!_instance.mInitialized) {
            _instance.mInitialized = true;

            // restore active items
            Cursor cursor = Storage.getInstance().getAllItems(false);
            while (cursor.moveToNext()) {
                Beacon item = Storage.itemFromCursor(cursor);
                getActive().add(item);
            }

            if (cursor.getCount() > 0) {
                // start the BLE service
                context.startService(new Intent(context, BleService.class));
            }
            cursor.close();
        }
    }

    /**
     * Stops the background BLE service all-together.
     * Warning: Beacons.initialize() will need to be called if you want to use the API again.
     */
    public static void shutdown() {
        Context context = getContext();
        if (null != context) {
            context.stopService(new Intent(context, BleService.class));

            // detach Context weak ref
            _instance.mAppContext.clear();
        }
    }

    /**
     * Retrieves a previously saved beacon by its persistent ID.
     * @param storageId    The beacon storage ID
     * @return  A beacon instance, either active or loaded from storage.
     */
    public static Beacon getSaved(long storageId) {
        Beacon beacon = findActive(storageId);
        return null != beacon ? beacon : loadItem(storageId);
    }

    /**
     * Finds an active beacon by it's unique ID.
     * @param uuid    The beacon unique ID. Note: this is not persistent between app restarts.
     * @return Active (enabled or paused) beacon, or null if uuid is null, beacon is stopped or doesn't exist.
     */
    public static Beacon findActive(UUID uuid) {
        if (null == uuid) return null;

        for (Beacon beacon : getActive()) {
            if (beacon.getUUID().equals(uuid)) {
                return beacon;
            }
        }
        return null;
    }

    /**
     * Finds an active beacon by it's storage ID.
     * @param storageId    The beacon's storage ID, persistent between app restarts.
     * @return Active (enabled or paused) beacon, or null if ID is invalid, beacon is stopped or doesn't exist.
     */
    public static Beacon findActive(long storageId) {
        if (storageId <= 0) return null;

        for (Beacon beacon : getActive()) {
            if (beacon.getSavedId() == storageId) {
                return beacon;
            }
        }

        return null;
    }

    private static Beacon loadItem(long storageId) {
        Beacon beacon = null;

        Cursor cursor = Storage.getInstance().getItem(storageId);
        if (cursor.moveToNext()) {
            beacon = Storage.itemFromCursor(cursor);
        }
        cursor.close();

        return beacon;
    }

    /**
     * @return The collection of all active items.
     * To check if a beacon is enabled or paused call getActiveState()
     * To check if a beacon is broadcasting call getAdvertiseState()
     */
    public static List<Beacon> getActive() {
        if (null == getInstance().mActiveItems) return _instance.mActiveItems = new ArrayList<>();
        return _instance.mActiveItems;
    }

    public static Cursor getStopped() {
        return Storage.getInstance().getAllItems(true);
    }

    public static Context getContext() {
        return getInstance().mAppContext.get();
    }

    static void onBleServiceDestroyed() {
        _instance.mInitialized = false;
    }
}