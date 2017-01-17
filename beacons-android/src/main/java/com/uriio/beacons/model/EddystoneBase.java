package com.uriio.beacons.model;

import com.uriio.beacons.ble.Advertiser;

import java.security.SecureRandom;

/**
 * Eddystone beacons common model.
 * Created on 7/21/2015.
 */
public abstract class EddystoneBase extends Beacon {
    private byte[] mLockKey;

    public EddystoneBase(byte[] lockKey,
                         @Advertiser.Mode int advertiseMode,
                         @Advertiser.Power int txPowerLevel,
                         String name) {
        super(advertiseMode, txPowerLevel, 0, name);
        init(lockKey);
    }

    public EddystoneBase(byte[] lockKey, String name) {
        super(0, name);
        init(lockKey);
    }

    private void init(byte[] lockKey) {
        if (null == lockKey) {
            lockKey = new byte[16];
            new SecureRandom().nextBytes(lockKey);
        }
        mLockKey = lockKey;
    }

    /**
     * Beacon's Lock Key to be used in GATT configuration.
     * @return
     */
    public byte[] getLockKey() {
        return mLockKey;
    }

    public abstract EddystoneBase cloneBeacon();

    @Override
    public EddystoneEditor edit() {
        return new EddystoneEditor();
    }

    public class EddystoneEditor extends BaseEditor {
        /**
         * Changes the Beacon Lock Key, to be used when configuring the beacon via GATT.
         * @param lockKey    The new Lock Key, as a 16-byte array
         * @return The editor instance, for call chaining.
         */
        public EddystoneEditor setLockKey(byte[] lockKey) {
            mLockKey = lockKey;
            return this;
        }
    }
}