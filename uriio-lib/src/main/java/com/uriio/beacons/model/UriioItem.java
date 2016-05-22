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
public class UriioItem extends EddystoneItem {
    private static final String TAG = "UriioItem";

    /** Long URL **/
    private String mLongUrl;

    /** Url ID **/
    private long mUrlId;

    /** Url Token **/
    private String mUrlToken;

    private int mTimeToLive;

    private long mExpireTime = 0;
    private byte[] mPrivateKey;

    public UriioItem(long itemId, int flags, long urlId, String urlToken, int ttl,
                     long expireTimestamp, String shortUrl, String longUrl, byte[] privateKey) {
        super(itemId, flags, shortUrl, null);

        mLongUrl = longUrl;
        mUrlId = urlId;
        mUrlToken = urlToken;
        mTimeToLive = ttl;
        mExpireTime = expireTimestamp;
        mPrivateKey = privateKey;
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

    public void updateShortUrl(String shortUrl, long expireTime) {
        setPayload(shortUrl);
        mExpireTime = expireTime;
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

    public boolean updateTTL(int timeToLive) {
        if (timeToLive != mTimeToLive) {
            mTimeToLive = timeToLive;
            return true;
        }
        return false;
    }

    public boolean updateLongUrl(String url) {
        if (!url.equals(mLongUrl)) {
            mLongUrl = url;
            return true;
        }
        return false;
    }

    @Override
    public void onAdvertiseEnabled(final BleService service) {
        if (null == getPayload() || getMillisecondsUntilExpires() < 7 * 1000) {
            Util.log(TAG, "Updating short url for item " + getId());
            Beacons.uriio().issueShortUrls(mUrlId, mUrlToken, mTimeToLive, 1, new Beacons.OnResultListener<ShortUrls>() {
                @Override
                public void onResult(ShortUrls result, Throwable error) {
                    if (null != result) {
                        ShortUrl shortUrl = result.getItems()[0];
                        Date expireDate = shortUrl.getExpire();
                        long expireTime = null == expireDate ? 0 : expireDate.getTime();

                        Beacons.updateEphemeralURLBeacon(UriioItem.this, shortUrl.getUrl(), expireTime);
                        service.startItemAdvertising(UriioItem.this);
                    } else {
                        setStatus(BaseItem.STATUS_UPDATE_FAILED);
                        service.broadcastError(EVENT_SHORTURL_FAILED, error);
                    }
                }
            });
        }
        else {
            service.startItemAdvertising(this);
        }
    }
}