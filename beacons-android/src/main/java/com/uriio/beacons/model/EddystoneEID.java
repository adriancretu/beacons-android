package com.uriio.beacons.model;

import com.uriio.beacons.BleService;
import com.uriio.beacons.Storage;
import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.ble.EddystoneAdvertiser;
import com.uriio.beacons.eid.EIDUtils;

import java.security.GeneralSecurityException;

/**
 * Created on 5/21/2016.
 */
public class EddystoneEID extends EddystoneBase {
    private byte[] mIdentityKey;
    private byte mRotationExponent;
    private int mClockOffset;

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
    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset, byte[] lockKey,
                        @Advertiser.Mode int mode, @Advertiser.Power int txPowerLevel, String name) {
        super(lockKey, mode, txPowerLevel, name);
        init(identityKey, rotationExponent, timeOffset);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset,
                        @Advertiser.Mode int mode,
                        @Advertiser.Power int txPowerLevel, String name) {
        this(identityKey, rotationExponent, timeOffset, null, mode, txPowerLevel, name);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset, byte[] lockKey,
                        @Advertiser.Mode int mode,
                        @Advertiser.Power int txPowerLevel) {
        this(identityKey, rotationExponent, timeOffset, lockKey, mode, txPowerLevel, null);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset,
                        @Advertiser.Mode int mode,
                        @Advertiser.Power int txPowerLevel) {
        this(identityKey, rotationExponent, timeOffset, null, mode, txPowerLevel, null);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset, byte[] lockKey, String name) {
        super(lockKey, name);
        init(identityKey, rotationExponent, timeOffset);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset, byte[] lockKey) {
        this(identityKey, rotationExponent, timeOffset, lockKey, null);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset, String name) {
        this(identityKey, rotationExponent, timeOffset, null, name);
    }

    public EddystoneEID(byte[] identityKey, byte rotationExponent, int timeOffset) {
        this(identityKey, rotationExponent, timeOffset, null, null);
    }

    @Override
    public int getKind() {
        return Storage.KIND_EDDYSTONE_EID;
    }

    @Override
    public EddystoneBase cloneBeacon() {
        return new EddystoneEID(getIdentityKey(), getRotationExponent(), getClockOffset(),
                getLockKey(), getAdvertiseMode(), getTxPowerLevel(), getName());
    }

    @Override
    protected Advertiser createAdvertiser(BleService service) {
        // add time offset to current time
        int timeCounter = getEidClock();
        byte[] data;

        try {
            data = EIDUtils.computeEID(mIdentityKey, timeCounter, mRotationExponent);
        } catch (GeneralSecurityException e) {
            // too risky to return null, so just use an empty EID buffer
            data = new byte[8];
        }

        mExpireTime = ((timeCounter >> mRotationExponent) + 1 << mRotationExponent) - mClockOffset;
        mExpireTime *= 1000;

        return new EddystoneAdvertiser(this, EddystoneAdvertiser.FRAME_EID, data, 0, 8);
    }

    @Override
    public long getScheduledRefreshTime() {
        return mExpireTime;
    }

    private void init(byte[] identityKey, byte rotationExponent, int timeOffset) {
        mIdentityKey = identityKey;
        mRotationExponent = rotationExponent;
        mClockOffset = timeOffset;
    }

    public byte[] getIdentityKey() {
        return mIdentityKey;
    }

    public byte getRotationExponent() {
        return mRotationExponent;
    }

    public int getClockOffset() {
        return mClockOffset;
    }

    public int getEidClock() {
        return (int) (System.currentTimeMillis() / 1000 + mClockOffset);
    }
}