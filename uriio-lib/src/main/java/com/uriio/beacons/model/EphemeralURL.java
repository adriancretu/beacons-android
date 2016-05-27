package com.uriio.beacons.model;

import com.uriio.beacons.Beacons;
import com.uriio.beacons.BleService;
import com.uriio.beacons.Storage;
import com.uriio.beacons.Util;
import com.uriio.beacons.api.ShortUrl;
import com.uriio.beacons.api.ShortUrls;

import java.util.Date;

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

    @Override
    public void onAdvertiseEnabled(final BleService service) {
        if (null == getURL() || getMillisecondsUntilExpires() < 7 * 1000) {
            Util.log(TAG, "Updating short url for item " + getId());
            Beacons.uriio().issueShortUrls(mUrlId, mUrlToken, mTimeToLive, 1, new Beacons.OnResultListener<ShortUrls>() {
                @Override
                public void onResult(ShortUrls result, Throwable error) {
                    if (null != result) {
                        ShortUrl shortUrl = result.getItems()[0];
                        Date expireDate = shortUrl.getExpire();
                        long expireTime = null == expireDate ? 0 : expireDate.getTime();

                        edit()
                                .setShortUrl(shortUrl.getUrl(), expireTime)
                                .apply();
                        service.startItemAdvertising(EphemeralURL.this);
                    } else {
                        setStatus(Beacon.STATUS_UPDATE_FAILED);
                        service.broadcastError(EVENT_SHORTURL_FAILED, error);
                    }
                }
            });
        }
        else {
            service.startItemAdvertising(this);
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
                Beacons.getStorage().updateUriioItemShortUrl(EphemeralURL.this);
            }

            super.apply();
        }
    }
}