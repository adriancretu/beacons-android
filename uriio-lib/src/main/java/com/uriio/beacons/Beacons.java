package com.uriio.beacons;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Base64;
import android.util.Log;

import com.uriio.beacons.api.ApiClient;
import com.uriio.beacons.ble.AppleBeacon;
import com.uriio.beacons.ble.EddystoneBeacon;
import com.uriio.beacons.model.BaseItem;
import com.uriio.beacons.model.EddystoneItem;
import com.uriio.beacons.model.UriioItem;
import com.uriio.beacons.model.iBeaconItem;

import java.nio.ByteBuffer;
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
    private List<BaseItem> mActiveItems = new ArrayList<>();

    private Beacons(Context context, String apiKey) {
        mAppContext = context.getApplicationContext();
        mUriioClient = new ApiClient(apiKey);
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
        if (apiKey != null) {
            initialize(context, apiKey, sharedPrefs.getString(PREF_DB_NAME, DEFAULT_DATABASE_NAME));
        }
    }

    /**
     * Initialize the API with the default persistence namespace.
     * @param context   The calling context from which to get the application context.
     * @param apiKey    Your API Key
     */
    @SuppressWarnings("unused")
    public static void initialize(Context context, String apiKey) {
        initialize(context, apiKey, DEFAULT_DATABASE_NAME);
    }

    /**
     * Initialize the API and use a custom persistence namespace.
     * @param context   The calling context from which to get the application context.
     * @param apiKey    Your API Key
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
                BaseItem item = Storage.itemFromCursor(cursor);
                getActive().add(item);
            }
            cursor.close();

            // (re)start the BLE service
            context.startService(new Intent(context, BleService.class));
        }
    }

    public static BaseItem add(BeaconSpec beaconSpec) {
        switch (beaconSpec.getType()) {
            case BeaconSpec.EDDYSTONE_UID:
                return addEddystoneUIDBeacon((EddystoneUIDSpec) beaconSpec);
            case BeaconSpec.EDDYSTONE_URL:
                return addEddystoneURLBeacon((EddystoneURLSpec) beaconSpec);
            case BeaconSpec.EDDYSTONE_EID:
                return addEddystoneEIDBeacon((EddystoneEIDSpec) beaconSpec);
            case BeaconSpec.EPHEMERAL_URL:
                return addEphemeralURLBeacon((EphemeralURLSpec) beaconSpec);
            case BeaconSpec.IBEACON:
                return addiBeacon((iBeaconSpec) beaconSpec);
        }
        return null;
    }

    @SuppressWarnings("unused")
    public static void delete(long itemId) {
        Storage.getInstance().deleteItem(itemId);

        if (findActive(itemId) != null) {
            sendStateBroadcast(itemId);
        }
    }

    public static void setState(long itemId, int state) {
        if (state >= 0 && state <= 2) {
            BaseItem item = findActive(itemId);
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

    public static BaseItem findActive(long itemId) {
        for (BaseItem item : getActive()) {
            if (item.getId() == itemId) {
                return item;
            }
        }
        return null;
    }

    public static BaseItem loadItem(long itemId) {
        BaseItem item = null;

        Cursor cursor = Storage.getInstance().getItem(itemId);
        if (cursor.moveToNext()) {
            item = Storage.itemFromCursor(cursor);
        }
        cursor.close();

        return item;
    }

    public static void editEddystoneURLBeacon(EddystoneItem item, String url, @BeaconSpec.AdvertiseMode int mode,
                                              @BeaconSpec.AdvertiseTxPower int txPowerLevel, String name) {
        int flags = EddystoneBeacon.FLAG_EDDYSTONE;
        Storage.getInstance().updateEddystoneItem(item.getId(), mode, txPowerLevel, url, flags, name, null);
        item.update(mode, txPowerLevel, flags, name, url, null);

        restartBeacon(item);
    }

    /** Updates an UID beacon. */
    public static void editEddystoneUIDBeacon(EddystoneItem item, byte[] namespaceInstance,
                                              @BeaconSpec.AdvertiseMode int mode,
                                              @BeaconSpec.AdvertiseTxPower int txPowerLevel, String name,
                                              String domainHint) {
        String payload = Base64.encodeToString(namespaceInstance, Base64.NO_PADDING);

        Storage.getInstance().updateEddystoneItem(item.getId(), mode, txPowerLevel, payload, item.getFlags(), name, domainHint);
        item.update(mode, txPowerLevel, item.getFlags(), name, payload, domainHint);

        restartBeacon(item);
    }

    public static void editEddystoneEIDBeacon(EddystoneItem item, @BeaconSpec.AdvertiseMode int mode,
                                              @BeaconSpec.AdvertiseTxPower int txPowerLevel, String name) {
        Storage.getInstance().updateEddystoneItem(item.getId(), mode, txPowerLevel, item.getPayload(), item.getFlags(), name, null);
        item.update(mode, txPowerLevel, item.getFlags(), name, item.getPayload(), null);

        restartBeacon(item);
    }

    public static void editEphemeralURLBeacon(UriioItem item, String url,
                                              @BeaconSpec.AdvertiseMode int mode,
                                              @BeaconSpec.AdvertiseTxPower int txPowerLevel,
                                              int timeToLive, String name) {
        boolean modeOrPowerChanged = item.updateBroadcastingOptions(mode, txPowerLevel);
        boolean nameChanged = item.setName(name);
        boolean ttlChanged = item.updateTTL(timeToLive);
        boolean urlChanged = item.updateLongUrl(url);

        // watch out - don't inline the checks - condition may short-circuit!!!
        if (modeOrPowerChanged || nameChanged || ttlChanged || urlChanged) {
            Storage.getInstance().updateUriioItem(item.getId(), mode, txPowerLevel,
                    EddystoneBeacon.FLAG_EDDYSTONE, name, url, timeToLive);
        }

        if (item.getStatus() == BaseItem.STATUS_ADVERTISING) {
            if (ttlChanged) {
                // force a short URL issue since TTL changed
                item.updateShortUrl(null, 0);
                Storage.getInstance().updateUriioItemShortUrl(item.getId(), null, 0);
                restartBeacon(item);
            }
            else if (modeOrPowerChanged) {
                // recreate the beacon, keep the same short URL until it expires (alarm triggers)
                restartBeacon(item);
            }
        }
    }

    public static void updateEphemeralURLBeacon(UriioItem item, String shortUrl, long expireTime) {
        // update memory model
        item.updateShortUrl(shortUrl, expireTime);

        // update database
        Storage.getInstance().updateUriioItemShortUrl(item.getId(), shortUrl, expireTime);
    }

    public static void editiBeacon(iBeaconItem item, byte[] uuid, int major, int minor,
                                   @BeaconSpec.AdvertiseMode int mode,
                                   @BeaconSpec.AdvertiseTxPower int txPowerLevel, String name) {
        Storage.getInstance().updateIBeaconItem(item.getId(), mode, txPowerLevel, AppleBeacon.FLAG_APPLE, name, uuid, major, minor);
        item.update(mode, txPowerLevel, uuid, major, minor, name);

        if (item.getStatus() == BaseItem.STATUS_ADVERTISING) {
            // todo - only do this if frequency / power / payload changed
            restartBeacon(item);
        }
    }

    /**
     * @return The collection of all active items. Not all items might actually be broadcasting.
     * To check if an item is broadcasting call getBeacon() on it.
     */
    public static List<BaseItem> getActive() {
        return getInstance().mActiveItems;
    }

    public static Cursor getStoppedItems() {
        return Storage.getInstance().getAllItems(true);
    }

    /**
     * @return Interface to an UriIO API client, used to register, update, and issue ephemeral URLs.
     */
    public static ApiClient uriio() {
        return getInstance().mUriioClient;
    }

    private static EddystoneItem addEddystoneURLBeacon(EddystoneURLSpec spec)
    {
        int flags = EddystoneBeacon.FLAG_EDDYSTONE;
        //noinspection PointlessBitwiseExpression
        flags |= EddystoneBeacon.FLAG_FRAME_URL << 4;

        return storeEddystone(spec.getAdvertiseMode(), spec.getAdvertiseTxPowerLevel(), flags,
                spec.getName(), spec.getURL(), null);
    }

    private static EddystoneItem addEddystoneUIDBeacon(EddystoneUIDSpec spec) {
        if (spec.getNamespaceInstance().length != 16) return null;

        int flags = EddystoneBeacon.FLAG_EDDYSTONE;
        flags |= EddystoneBeacon.FLAG_FRAME_UID << 4;

        String payload = Base64.encodeToString(spec.getNamespaceInstance(), Base64.NO_PADDING);

        return storeEddystone(spec.getAdvertiseMode(), spec.getAdvertiseTxPowerLevel(), flags,
                spec.getName(), payload, spec.getDomainHint());
    }

    private static EddystoneItem addEddystoneEIDBeacon(EddystoneEIDSpec spec) {
        int flags = EddystoneBeacon.FLAG_EDDYSTONE;
        flags |= EddystoneBeacon.FLAG_FRAME_EID << 4;

        // serialize beacon into storage blob
        byte[] data = new byte[21];
        System.arraycopy(spec.getIdentityKey(), 0, data, 0, 16);
        ByteBuffer.wrap(data, 16, 4).putInt(spec.getTimeOffset());
        data[20] = spec.getRotationExponent();

        String payload = Base64.encodeToString(data, Base64.NO_PADDING);

        return storeEddystone(spec.getAdvertiseMode(), spec.getAdvertiseTxPowerLevel(), flags,
                spec.getName(), payload,  null);
    }

    private static UriioItem addEphemeralURLBeacon(EphemeralURLSpec spec) {
        return storeUriio(spec.getUrlId(), spec.getUrlToken(), spec.getLongUrl(),
                null, spec.getAdvertiseMode(), spec.getAdvertiseTxPowerLevel(), spec.getTtl(),
                spec.getName());
    }

    private static iBeaconItem addiBeacon(iBeaconSpec spec) {
        int flags = AppleBeacon.FLAG_APPLE;
        long itemId = Storage.getInstance().insertAppleBeaconItem(spec.getAdvertiseMode(),
                spec.getAdvertiseTxPowerLevel(),
                Base64.encodeToString(spec.getUuid(), Base64.NO_PADDING), spec.getMajor(),
                spec.getMinor(), flags, spec.getName());

        iBeaconItem item = new iBeaconItem(itemId, flags, spec.getUuid(), spec.getMajor(), spec.getMinor());
        item.setAdvertiseMode(spec.getAdvertiseMode());
        item.setTxPowerLevel(spec.getAdvertiseTxPowerLevel());
        item.setName(spec.getName());

        getInstance().mActiveItems.add(item);
        setState(item.getId(), Storage.STATE_ENABLED);

        return item;
    }

    private static void sendStateBroadcast(long itemId) {
        LocalBroadcastManager.getInstance(getInstance().mAppContext).sendBroadcast(
            new Intent(BleService.ACTION_ITEM_STATE).putExtra(BleService.EXTRA_ITEM_ID, itemId));
    }

    private static void restartBeacon(BaseItem item) {
        if (item.getStatus() == BaseItem.STATUS_ADVERTISING) {
            // todo - only do this if frequency / power / payload changed
            setState(item.getId(), Storage.STATE_ENABLED);
        }
    }

    private static EddystoneItem storeEddystone(int mode, int txPowerLevel, int flags,
                                                String name, String payload, String domain) {
        long itemId = Storage.getInstance().insertEddystoneItem(mode, txPowerLevel, payload, flags,
                name, domain);

        EddystoneItem item = new EddystoneItem(itemId, flags, payload, domain);
        item.setAdvertiseMode(mode);
        item.setTxPowerLevel(txPowerLevel);
        item.setName(name);

        getInstance().mActiveItems.add(item);
        setState(item.getId(), Storage.STATE_ENABLED);

        return item;
    }

    @NonNull
    private static UriioItem storeUriio(long urlId, String urlToken, String url, byte[] privateKey,
                                        int mode, int txPowerLevel, int ttl, String name) {
        // store
        long itemId = Storage.getInstance().insertUriioItem(urlId, urlToken, url, privateKey,
                mode, txPowerLevel, ttl, EddystoneBeacon.FLAG_FRAME_URL, name);

        // instantiate item
        UriioItem item = new UriioItem(itemId, EddystoneBeacon.FLAG_FRAME_URL, urlId, urlToken, ttl,
                0, null, url, privateKey);
        item.setAdvertiseMode(mode);
        item.setTxPowerLevel(txPowerLevel);
        item.setName(name);

        getInstance().mActiveItems.add(item);
        setState(item.getId(), Storage.STATE_ENABLED);

        return item;
    }
}