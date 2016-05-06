package com.uriio.beacons;

import android.annotation.TargetApi;
import android.bluetooth.le.AdvertiseSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.util.Log;

import com.uriio.beacons.api.ApiClient;
import com.uriio.beacons.api.IssueUrls;
import com.uriio.beacons.api.ShortUrls;
import com.uriio.beacons.api.SimpleResultHandler;
import com.uriio.beacons.api.Url;
import com.uriio.beacons.ble.EddystoneBeacon;
import com.uriio.beacons.model.UriioItem;

import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * UriIO beacons API
 * Created on 4/28/2016.
 */
public class Uriio {
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

    private static final String TAG = "Uriio";

    private static final String PREF_API_KEY = "apiKey";
    private static final String PREF_DB_NAME = "db";
    private static final String PREFS_NAME = "com.uriio.beacons";

    private static final String DEFAULT_DATABASE_NAME = "com.uriio.beacons";

    private final Context mAppContext;
    private String mApiKey = null;
    private ApiClient.UriioService mApiService = null;

    // Some ugly decorator definitions...
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
            AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    })
    private @interface AdvertiseMode {}

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW,
            AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
            AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
            AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
    })
    private @interface AdvertiseTxPower {}

    private static Uriio _instance = null;

    private Uriio(Context context, String apiKey) {
        mAppContext = context.getApplicationContext();
        mApiKey = apiKey;
        mApiService = ApiClient.getRetrofit().create(ApiClient.UriioService.class);
    }

    static Uriio getInstance() {
        if (null == _instance) {
            throw new RuntimeException("Not initialized");
        }
        return _instance;
    }

    static void reinitialize(Context context) {
        // restore from saved config
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String apiKey = sharedPreferences.getString(PREF_API_KEY, null);
        if (apiKey != null) {
            initialize(context, apiKey, sharedPreferences.getString(PREF_DB_NAME, DEFAULT_DATABASE_NAME));
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
            _instance = new Uriio(context, apiKey);
            Storage.init(context, dbName);

            // save this last config so we can restore ourselves if needed
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(PREF_API_KEY, apiKey)
                    .putString(PREF_DB_NAME, dbName)
                    .apply();

            // (re)start the service
            startUriioService(0, 0);
        }
    }

    /**
     * Creates a new beacon after registering a new destination URL.
     * @param longUrl       The destination URL.
     * @param ttl           Time to Live for the ephemeral beacon URLs, in seconds.
     * @param advertiseMode Beacon broadcasting advertise mode.
     * @param txPowerLevel  Beacon broadcasting power level.
     * @param callback      Result callback.
     */
    @SuppressWarnings("unused")
    public static void addBeacon(String longUrl, final int ttl,
                                 @AdvertiseMode final int advertiseMode,
                                 @AdvertiseTxPower final int txPowerLevel,
                                 final OnResultListener<UriioItem> callback) {
        final Curve25519KeyPair keyPair = Curve25519.getInstance(Curve25519.BEST).generateKeyPair();

        getInstance().registerUrl(longUrl, keyPair.getPublicKey(), new OnResultListener<Url>() {
            @Override
            public void onResult(Url result, Throwable error) {
                UriioItem item = null;
                if (null != result) {
                    item = storeNewItem(result.getId(), result.getToken(), result.getUrl(),
                            keyPair.getPrivateKey(), advertiseMode, txPowerLevel, ttl);
                    enableBeacon(item.getId(), true);
                }
                if (null != callback) {
                    callback.onResult(item, error);
                }
            }
        });
    }

    /**
     * Creates a new beacon using details of an already registered URL.
     * @param urlId         The URL resource ID.
     * @param urlToken      The URL token.
     * @param longUrl       The long URL. This can be null, if not needed back when listing beacons.
     * @param ttl           Time to Live for the ephemeral beacon URLs, in seconds.
     * @param mode          Beacon broadcasting advertise mode.
     * @param txPowerLevel  Beacon broadcasting power level.
     * @param callback      Result callback.
     */
    @SuppressWarnings("unused")
    public static void addBeacon(long urlId, String urlToken, String longUrl, int ttl,
                                 @AdvertiseMode int mode,
                                 @AdvertiseTxPower int txPowerLevel,
                                 OnResultListener<UriioItem> callback) {
        UriioItem item = storeNewItem(urlId, urlToken, longUrl, null, mode, txPowerLevel, ttl);
        enableBeacon(item.getId(), true);
        if (null != callback) {
            callback.onResult(item, null);
        }
    }

    /**
     * @return A list of all created UriIO beacons, either active or inactive.
     */
    public static List<UriioItem> getBeacons() {
        Cursor cursor = Storage.getInstance().getUriioItems();
        List<UriioItem> items = new ArrayList<>(cursor.getCount());
        while (cursor.moveToNext()) {
            items.add(Storage.uriioItemFromCursor(cursor));
        }
        cursor.close();
        return items;
    }

    @SuppressWarnings("unused")
    public static void deleteBeacon(long itemId) {
        Storage.getInstance().deleteItem(itemId);
        startUriioService(itemId, UriioService.COMMAND_STOP);
    }

    public static void enableBeacon(long itemId, boolean enable) {
        startUriioService(itemId, enable ? UriioService.COMMAND_START : UriioService.COMMAND_STOP);
    }

    private static void startUriioService(long itemId, int command) {
        Context context = getInstance().mAppContext;
        context.startService(new Intent(context, UriioService.class)
                .putExtra(UriioService.EXTRA_ITEM_ID, itemId)
                .putExtra(UriioService.EXTRA_COMMAND, command));
    }

    @NonNull
    private static UriioItem storeNewItem(long urlId, String urlToken, String url, byte[] privateKey, int mode, int txPowerLevel, int ttl) {
        // store
        long itemId = Storage.getInstance().insertUriioItem(urlId, urlToken, url, privateKey,
                mode, txPowerLevel, ttl, EddystoneBeacon.FLAG_FRAME_URL, null);

        // instantiate item
        UriioItem item = new UriioItem(itemId, EddystoneBeacon.FLAG_FRAME_URL, urlId, urlToken, ttl, 0, null, url, privateKey);
        item.setAdvertiseMode(mode);
        item.setTxPowerLevel(txPowerLevel);

        return item;
    }

    /**
     * Registers a new long URL resource.
     * @param url           The long URL.
     * @param urlPublicKey  The public key of the new URL. Each URL should have its own key-pair.
     *                      Generate a key-pair using Curve25519.generateKeyPair() and save the private key
     *                      to a secure place, while giving only the public key to the API.
     * @param callback      Result callback.
     */
    private void registerUrl(String url, byte[] urlPublicKey, OnResultListener<Url> callback) {
        mApiService.registerUrl(new Url(url, urlPublicKey), mApiKey)
                .enqueue(new SimpleResultHandler<>(callback));
    }

    /**
     * Requests a new short URL for the specified resource.
     * @param urlId       The registered URL's ID.
     * @param urlToken    The URL token.
     * @param ttl         Time To Live for the returned short URL (or 0 to never expire).
     * @param numToIssue  How many short URLs to request.
     * @param callback    Result callback.
     */
    void issueShortUrls(long urlId, String urlToken, int ttl, int numToIssue,
                               OnResultListener<ShortUrls> callback) {
        mApiService.createShortUrl(urlId, mApiKey,
                new IssueUrls(urlToken, ttl, numToIssue))
                .enqueue(new SimpleResultHandler<>(callback));
    }
}