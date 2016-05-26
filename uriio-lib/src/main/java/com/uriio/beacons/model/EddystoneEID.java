package com.uriio.beacons.model;

import com.uriio.beacons.ble.AdvertisersManager;
import com.uriio.beacons.ble.EddystoneAdvertiser;
import com.uriio.beacons.eid.EIDUtils;

import java.security.GeneralSecurityException;

/**
 * Created on 5/21/2016.
 */
public class EddystoneEID extends EddystoneBase {
    private byte[] mIdentityKey;
    private byte mRotationExponent;
    private int mTimeOffset;

    private long mExpireTime = 0;

    /**
     * EID spec.
     * @param identityKey         16-byte Identity Key
     * @param rotationExponent    EID rotation exponent (0 to 15)
     * @param timeOffset          Time offset to actual current timestamp, in seconds
     * @param mode                BLE transmit mode
     * @param txPowerLevel        BLE power level
     * @param name                Optional beacon name
     */
    public EddystoneEID(long id, byte[] identityKey, byte rotationExponent, int timeOffset,
                        byte[] lockKey,
                        @Beacon.AdvertiseMode int mode,
                        @Beacon.AdvertiseTxPower int txPowerLevel, String name) {
        super(id, Beacon.EDDYSTONE_EID, lockKey, mode, txPowerLevel, name);
        init(identityKey, rotationExponent, timeOffset);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset, byte[] lockKey,
                        @Beacon.AdvertiseMode int mode,
                        @Beacon.AdvertiseTxPower int txPowerLevel, String name) {
        this(0, identityKey, rotationExponent, timeOffset, lockKey, mode, txPowerLevel, name);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset,
                        @Beacon.AdvertiseMode int mode,
                        @Beacon.AdvertiseTxPower int txPowerLevel, String name) {
        this(0, identityKey, rotationExponent, timeOffset, null, mode, txPowerLevel, name);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset, byte[] lockKey,
                        @Beacon.AdvertiseMode int mode,
                        @Beacon.AdvertiseTxPower int txPowerLevel) {
        this(0, identityKey, rotationExponent, timeOffset, lockKey, mode, txPowerLevel, null);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset,
                        @Beacon.AdvertiseMode int mode,
                        @Beacon.AdvertiseTxPower int txPowerLevel) {
        this(0, identityKey, rotationExponent, timeOffset, null, mode, txPowerLevel, null);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset) {
        super(Beacon.EDDYSTONE_EID);
        init(identityKey, rotationExponent, timeOffset);
    }

    @Override
    public EddystoneAdvertiser createBeacon(AdvertisersManager advertisersManager) {
        // add time offset to current time
        int timeCounter = getEidClock();
        byte[] data;

        try {
            data = EIDUtils.computeEID(mIdentityKey, timeCounter, mRotationExponent);
        } catch (GeneralSecurityException e) {
            // too risky to return null, so just use an empty EID buffer
            data = new byte[8];
        }

        mExpireTime = ((timeCounter >> mRotationExponent) + 1 << mRotationExponent) - mTimeOffset;
        mExpireTime *= 1000;

        EddystoneAdvertiser beacon = new EddystoneAdvertiser(data, 0, 8, advertisersManager,
                getAdvertiseMode(), getTxPowerLevel(), isConnectable(), getFlags());
        setBeacon(beacon);

        return beacon;
    }

    @Override
    public long getScheduledRefreshTime() {
        return mExpireTime;
    }

    private void init(byte[] identityKey, byte rotationExponent, int timeOffset) {
        mIdentityKey = identityKey;
        mRotationExponent = rotationExponent;
        mTimeOffset = timeOffset;
    }

    public byte[] getIdentityKey() {
        return mIdentityKey;
    }

    public byte getRotationExponent() {
        return mRotationExponent;
    }

    public int getEidTimeOffset() {
        return mTimeOffset;
    }

    public int getEidClock() {
        return (int) (System.currentTimeMillis() / 1000 + mTimeOffset);
    }
}