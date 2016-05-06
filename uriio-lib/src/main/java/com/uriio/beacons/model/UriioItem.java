package com.uriio.beacons.model;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.uriio.beacons.Storage;
import com.uriio.beacons.UriioReceiver;
import com.uriio.beacons.UriioService;
import com.uriio.beacons.Util;

/**
 * Container for an URI object.
 */
public class UriioItem extends EddystoneItem {
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

    public byte[] getPrivateKey() {
        return mPrivateKey;
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

    public PendingIntent getRefreshPendingIntent(Context context) {
        Intent intent = new Intent(context, UriioReceiver.class);
        intent.putExtra(UriioService.EXTRA_ITEM_ID, getId());

        // use the item id as the private request code, or else the Intent is "identical" for all items and is reused!
        return PendingIntent.getBroadcast(context, (int) getId(), intent, 0);
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

    @Override
    public void onAdvertiseStarted(UriioService service) {
        super.onAdvertiseStarted(service);

        long scheduledRefreshTime = getScheduledRefreshTime();

        if (scheduledRefreshTime > 0) {
            PendingIntent pendingIntent = getRefreshPendingIntent(service);
            // first time - create repeating alarm
            Util.log("UriioService item " + getId() + " now: " + System.currentTimeMillis() + " alarm time: " + getScheduledRefreshTime());
            service.scheduleRTCAlarm(scheduledRefreshTime, pendingIntent);
        }
    }
}