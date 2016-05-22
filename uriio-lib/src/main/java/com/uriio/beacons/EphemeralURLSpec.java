package com.uriio.beacons;

/**
 * Created on 5/21/2016.
 */
public class EphemeralURLSpec extends BeaconSpec {
    private long urlId;
    private String urlToken;
    private String longUrl;
    private int ttl;

    /**
     * Ephemeral URL spec.
     * @param urlId         The URL resource ID, or 0 if not yet registered
     * @param urlToken      The URL token, or null if not yet registered
     * @param longUrl       The destination URL. May be null if registration was done already.
     * @param ttl           Optional Time to Live for the ephemeral beacon URLs, in seconds.
     */
    public EphemeralURLSpec(long urlId, String urlToken, String longUrl, int ttl,
                            @AdvertiseMode int mode,
                            @AdvertiseTxPower int txPowerLevel, String name) {
        super(EPHEMERAL_URL, mode, txPowerLevel, name);
        this.urlId = urlId;
        this.urlToken = urlToken;
        this.longUrl = longUrl;
        this.ttl = ttl;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public int getTtl() {
        return ttl;
    }

    public long getUrlId() {
        return urlId;
    }

    public String getUrlToken() {
        return urlToken;
    }
}
