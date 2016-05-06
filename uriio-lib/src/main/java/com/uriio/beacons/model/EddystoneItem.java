package com.uriio.beacons.model;

import android.util.Base64;

import com.uriio.beacons.Storage;
import com.uriio.beacons.ble.BLEAdvertiseManager;
import com.uriio.beacons.ble.Beacon;
import com.uriio.beacons.ble.EddystoneBeacon;
import com.uriio.beacons.eid.LocalEIDResolver;
import com.uriio.beacons.eid.EIDUtils;
import com.uriio.beacons.eid.RegisterParams;

import org.uribeacon.beacon.UriBeacon;

import java.security.GeneralSecurityException;

/**
 * Eddystone Beacon model.
 * Created on 7/21/2015.
 */
public class EddystoneItem extends BaseItem {
    private String mPayload;
    private String mDomain;

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
                data = registerEIDBeacon(data);

                len = 8;
            }
            else len = data.length;
        }

        // create the actual BLE beacon instance
        mBeacon = new EddystoneBeacon(data, 0, len, bleAdvertiseManager, getAdvertiseMode(), getTxPowerLevel(), flags);
        return mBeacon;
    }

    private byte[] registerEIDBeacon(byte[] data) throws GeneralSecurityException {
        // FIXME: 4/15/2016 Read from item
        int timeCounter = 0;
        byte rotationExponent = 0;

        // FIXME: 4/16/2016 - single eid resolver
        LocalEIDResolver eidServer = new LocalEIDResolver();

        // Curve25519 lib doesn't support buffer offsets, so we need to split the buffer
        byte[] publicKey = new byte[32];
        byte[] privateKey = new byte[32];

        System.arraycopy(data, 0, publicKey, 0, publicKey.length);
        System.arraycopy(data, publicKey.length, privateKey, 0, privateKey.length);

        RegisterParams registerParams = eidServer.queryRegistrationParams();

        byte[] identityKey = EIDUtils.computeSharedKey(registerParams.publicKey, privateKey);

        byte[] eid = EIDUtils.computeEID(identityKey, timeCounter, rotationExponent);
        eidServer.registerBeacon(publicKey, rotationExponent, timeCounter, eid);

        return eid;
    }

    @Override
    public int getKind() {
        return Storage.KIND_EDDYSTONE;
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
