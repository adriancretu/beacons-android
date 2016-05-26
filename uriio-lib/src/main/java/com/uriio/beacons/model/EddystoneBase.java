package com.uriio.beacons.model;

import com.uriio.beacons.Storage;

import java.security.SecureRandom;

/**
 * Eddystone Advertiser model.
 * Created on 7/21/2015.
 */
public abstract class EddystoneBase extends Beacon {
    private byte[] mLockKey;

    public EddystoneBase(long itemId, int type, byte[] lockKey,
                         @AdvertiseMode int advertiseMode,
                         @AdvertiseTxPower int txPowerLevel,
                         String name) {
        super(itemId, type, advertiseMode, txPowerLevel, type << 4, name);
        init(lockKey);
    }

    public EddystoneBase(int type) {
        super(type, type << 4);
        init(null);
    }

    private void init(byte[] lockKey) {
        if (null == lockKey) {
            lockKey = new byte[16];
            new SecureRandom().nextBytes(lockKey);
        }
        mLockKey = lockKey;
    }

    @Override
    public int getKind() {
        return Storage.KIND_EDDYSTONE;
    }

    /**
     * Beacon's Lock Key to be used in GATT configuration.
     * @return
     */
    public byte[] getLockKey() {
        return mLockKey;
    }

    @Override
    public EddystoneEditor edit() {
        return new EddystoneEditor();
    }

    public class EddystoneEditor extends Editor {
        public EddystoneEditor setLockKey(byte[] key) {
            mLockKey = key;
            return this;
        }
    }
}