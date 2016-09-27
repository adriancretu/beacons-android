package com.uriio.beacons.model;

import com.uriio.beacons.BleService;
import com.uriio.beacons.BuildConfig;
import com.uriio.beacons.Callback;
import com.uriio.beacons.Storage;
import com.uriio.beacons.Util;

import static com.uriio.beacons.BleService.EVENT_SHORTURL_FAILED;

/**
 * Data model for an UriIO item.
 */
public class EphemeralURL extends EddystoneURL {
    private static final String TAG = "EphemeralURL";

    /** Long URL **/
    private String mLongUrl;

    /** Url ID **/
    private long mUrlId;

    /** Url Token **/
    private String mUrlToken;

    private int mTimeToLive;

    private long mExpireTime = 0;

    /**
     * Ephemeral URL spec.
     * @param urlId         The URL registration ID.
     * @param urlToken      The URL registration token.
     * @param ttl           Optional Time to Live for the ephemeral beacon URLs, in seconds.
     * @param longUrl       The destination URL. May be null if registration was done already.
     */
    public EphemeralURL(long itemId, long urlId, String urlToken, int ttl, String longUrl,
                        long expireTimestamp, String shortUrl,
                        @AdvertiseMode int advertiseMode,
                        @AdvertiseTxPower int txPowerLevel,
                        String name) {
        super(itemId, shortUrl, null, advertiseMode, txPowerLevel, name);
        init(urlId, urlToken, ttl, longUrl);
        mExpireTime = expireTimestamp;
    }

    public EphemeralURL(long urlId, String urlToken, int ttl, String longUrl,
                        long expireTimestamp, String shortUrl,
                        @AdvertiseMode int advertiseMode,
                        @AdvertiseTxPower int txPowerLevel,
                        String name) {
        this(0, urlId, urlToken, ttl, longUrl, expireTimestamp, shortUrl, advertiseMode, txPowerLevel, name);
    }

    public EphemeralURL(long urlId, String urlToken, int ttl, String longUrl,
                        long expireTimestamp, String shortUrl,
                        @AdvertiseMode int advertiseMode,
                        @AdvertiseTxPower int txPowerLevel) {
        this(0, urlId, urlToken, ttl, longUrl, expireTimestamp, shortUrl, advertiseMode, txPowerLevel, null);
    }

    public EphemeralURL(long urlId, String urlToken, int ttl, String longUrl,
                        @AdvertiseMode int advertiseMode,
                        @AdvertiseTxPower int txPowerLevel, String name) {
        this(0, urlId, urlToken, ttl, longUrl, 0, null, advertiseMode, txPowerLevel, name);
    }

    public EphemeralURL(long urlId, String urlToken, int ttl, String longUrl,
                        @AdvertiseMode int advertiseMode,
                        @AdvertiseTxPower int txPowerLevel) {
        this(0, urlId, urlToken, ttl, longUrl, 0, null, advertiseMode, txPowerLevel, null);
    }

    public EphemeralURL(long urlId, String urlToken, int ttl) {
        super(null);
        init(urlId, urlToken, ttl, null);
    }

    @Override
    public int getType() {
        return EPHEMERAL_URL;
    }

    private void init(long urlId, String urlToken, int ttl, String longUrl) {
        mUrlId = urlId;
        mUrlToken = urlToken;
        mTimeToLive = ttl;
        mLongUrl = longUrl;
    }

    public String getUrlToken() {
        return mUrlToken;
    }

    public int getTimeToLive() {
        return mTimeToLive;
    }

    public String getLongUrl() {
        return mLongUrl;
    }

    public long getMillisecondsUntilExpires() {
        return 0 == mExpireTime ? Long.MAX_VALUE : mExpireTime - System.currentTimeMillis();
    }

    @Override
    public long getScheduledRefreshTime() {
        // schedule refresh 7 seconds before actual server timeout
        return mExpireTime - 7 * 1000;
    }

    public long getActualExpireTime() {
        return mExpireTime;
    }

    public long getUrlId() {
        return mUrlId;
    }

    @Override
    public int getKind() {
        return Storage.KIND_URIIO;
    }

    public interface ShortURLIssuer {
        void issueBeaconUrl(EphemeralURL beacon, Callback<Boolean> callback);
    }

    private static ShortURLIssuer _issuerImpl = null;

    public static void setIssuer(ShortURLIssuer issuer) {
        _issuerImpl = issuer;
    }

    @Override
    public void onAdvertiseEnabled(final BleService service) {
        if (null == getURL() || getMillisecondsUntilExpires() < 7 * 1000) {
            if (null == _issuerImpl) {
                service.broadcastError(this, EVENT_SHORTURL_FAILED, "No URL provider!");
            }
            else {
                if (BuildConfig.DEBUG) Util.log(TAG, "Updating beacon URL for beacon " + getUUID());
                _issuerImpl.issueBeaconUrl(this, new Callback<Boolean>() {
                    @Override
                    public void onResult(Boolean result, Throwable error) {
                        if (result) {   // true or false, never null
                            service.startBeaconAdvertiser(EphemeralURL.this);
                        }
                        else if (null != error) {
                            service.broadcastError(EphemeralURL.this, EVENT_SHORTURL_FAILED, error.getMessage());
                        }
                    }
                });
            }
        }
        else {
            service.startBeaconAdvertiser(this);
        }
    }

    @Override
    public UriioEditor edit() {
        return new UriioEditor();
    }

    public class UriioEditor extends EddystoneURLEditor {
        private boolean mShortUrlChanged = false;

        public Editor setShortUrl(String shortUrl, long expireTime) {
            setUrl(shortUrl);

            if (mExpireTime != expireTime) {
                mExpireTime = expireTime;
                mRestartBeacon = true;
            }

            mShortUrlChanged = true;

            return this;
        }

        public UriioEditor setTTL(int timeToLive) {
            if (timeToLive != mTimeToLive) {
                mTimeToLive = timeToLive;
                mRestartBeacon = true;

                // force a short URL issue since TTL changed
                setShortUrl(null, 0);
            }
            return this;
        }

        public UriioEditor setLongUrl(String url) {
            if (!url.equals(mLongUrl)) {
                mLongUrl = url;
            }
            return this;
        }

        @Override
        public void apply() {
            if (mShortUrlChanged) {
                Storage.getInstance().updateUriioItemShortUrl(EphemeralURL.this);
            }

            super.apply();
        }
    }
}