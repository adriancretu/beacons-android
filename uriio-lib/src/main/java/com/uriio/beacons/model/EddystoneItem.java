package com.uriio.beacons.model;

import android.util.Base64;

import com.uriio.beacons.Storage;
import com.uriio.beacons.ble.BLEAdvertiseManager;
import com.uriio.beacons.ble.Beacon;
import com.uriio.beacons.ble.EddystoneBeacon;
import com.uriio.beacons.eid.EIDUtils;

import org.uribeacon.beacon.UriBeacon;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

/**
 * Eddystone Beacon model.
 * Created on 7/21/2015.
 */
public class EddystoneItem extends BaseItem {
    private String mPayload;
    private String mDomain;
    private long mExpireTime = 0;

    public EddystoneItem(long itemId, int flags, String payload, String domain) {
        super(itemId, flags);
        mPayload = payload;
        mDomain = domain;
    }

    @Override
    public Beacon createBeacon(BLEAdvertiseManager bleAdvertiseManager) throws GeneralSecurityException {
        byte[] data = null;
        int flags = getFlags();
        int frameType = flags >>> 4;

        int len;
        if (EddystoneBeacon.FLAG_FRAME_URL == frameType) {
            data = UriBeacon.encodeUri(mPayload);
            if (null == data) return null;
            len = data.length;
        } else {
            try {
                data = Base64.decode(mPayload, Base64.DEFAULT);
            } catch (IllegalArgumentException ignored) {

            }

            if (null == data) return null;

            if (EddystoneBeacon.FLAG_FRAME_EID == frameType) {
                int timeOffset = ByteBuffer.wrap(data, 16, 4).getInt();

                // sanitize time offset to match range; see UriioService.createEddystoneEIDItem
                timeOffset = Math.min(255, Math.max(-65280, timeOffset));

                // sanitize rotation exponent to [0, 15] range
                byte rotationExponent = (byte) (data[20] & 0x0f);
                // add time offset to current time
                int timeCounter = (int) (System.currentTimeMillis() / 1000 + timeOffset);
                data = EIDUtils.computeEID(data, timeCounter, rotationExponent);

                // only the first 8 bytes are used
                len = 8;

                mExpireTime = ((timeCounter >> rotationExponent) + 1 << rotationExponent) - timeOffset;
                mExpireTime *= 1000;
            }
            else len = data.length;
        }

        // create the actual BLE beacon instance
        mBeacon = new EddystoneBeacon(data, 0, len, bleAdvertiseManager, getAdvertiseMode(), getTxPowerLevel(), flags);
        return mBeacon;
    }

    @Override
    public int getKind() {
        return Storage.KIND_EDDYSTONE;
    }

    @Override
    public long getScheduledRefreshTime() {
        return mExpireTime;
    }

    public String getPayload() {
        return mPayload;
    }

    public String getDomain() {
        return mDomain;
    }

    public void update(int advertiseMode, int txPowerLevel, int flags, String displayName, String payload, String domain) {
        mFlags = flags;
        setName(displayName);
        setAdvertiseMode(advertiseMode);
        setTxPowerLevel(txPowerLevel);

        mPayload = payload;
        mDomain = domain;
    }

    protected void setPayload(String payload) {
        mPayload = payload;
    }
}
