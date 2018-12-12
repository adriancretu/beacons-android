package com.uriio.beacons.model;

import android.os.SystemClock;

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
    // maps the device's uptime zero moment to its UNIX timestamp. Since this may be wrong if the
    // device's time is off, it's enough to adjust it exactly once with a "corrected" time.
    private static long BOOT_TIME = System.currentTimeMillis() - SystemClock.elapsedRealtime();

    private byte[] mIdentityKey;
    private byte mRotationExponent;
    private int mClockOffset;

    private long mScheduledRefreshTime = 0;

    /**
     * Sets the absolute known boot time of the device. This should be called after retrieving
     * the EID server's actual current time, if possible.
     * @param bootTime    UNIX timestamp in milliseconds.
     */
    public static void setBootTime(long bootTime) {
        BOOT_TIME = bootTime;
    }

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
//        epoch = -mClockOffset * 1000t;
//        bootTime = currentTime - elapsedRealtime;
//        clockMs = bootTime + elapsedRealtime - epoch;

        int clock = getEidClock();
        byte[] data;

        try {
            data = EIDUtils.computeEID(mIdentityKey, clock, mRotationExponent);
        } catch (GeneralSecurityException e) {
            service.broadcastError(this, BleService.EVENT_START_FAILED, "EID compute failed: " + e.getMessage());

            return null;
        }

//        int mExpireTime = ((clock >> mRotationExponent) + 1 << mRotationExponent) - mClockOffset;
//        mExpireTime *= 1000;

        // todo - randomize exact time when EID is updated
        int refreshClock = (clock >> mRotationExponent) + 1 << mRotationExponent;

        // epoch - bootTime + refreshClock; 1000L needed for Long result!
        mScheduledRefreshTime = 1000L * (refreshClock - mClockOffset) - BOOT_TIME;

        return new EddystoneAdvertiser(this, EddystoneAdvertiser.FRAME_EID, data, 0, 8);
    }

    @Override
    public long getScheduledRefreshElapsedTime() {
        return mScheduledRefreshTime;
    }

    @Override
    public CharSequence getNotificationSubject() {
        return "EID beacon";
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

    /**
     * @return Beacon clock, in seconds
     */
    public int getEidClock() {
        return (int) ((BOOT_TIME + SystemClock.elapsedRealtime()) / 1000) + mClockOffset;
//        return (int) (System.currentTimeMillis() / 1000 + mClockOffset);
    }
}
