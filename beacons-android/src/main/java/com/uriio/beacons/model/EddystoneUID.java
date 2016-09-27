package com.uriio.beacons.model;

import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.ble.AdvertisersManager;
import com.uriio.beacons.ble.EddystoneAdvertiser;

import java.util.Arrays;

/**
 * Created on 5/21/2016.
 */
public class EddystoneUID extends EddystoneBase {
    private byte[] mNamespaceInstance;
    private String mDomainHint;

    /**
     * Eddystone UID spec.
     * @param namespaceInstance    16-byte buffer of namespace (10 bytes) and instance (6 bytes)
     * @param domainHint           Optional domain name hinted as the source for namespace.
     *                             Setting this does not modify the namespace/instance.
     */
    public EddystoneUID(long storageId, byte[] namespaceInstance, String domainHint, byte[] lockKey,
                        @Beacon.AdvertiseMode int mode,
                        @Beacon.AdvertiseTxPower int txPowerLevel, String name) {
        super(storageId, EDDYSTONE_UID, lockKey, mode, txPowerLevel, name);

        mNamespaceInstance = null != namespaceInstance ? namespaceInstance : new byte[16];
        mDomainHint = domainHint;
    }

    public EddystoneUID(byte[] namespaceInstance, String domainHint,
                        @Beacon.AdvertiseMode int mode,
                        @Beacon.AdvertiseTxPower int txPowerLevel, String name) {
        this(0, namespaceInstance, domainHint, null, mode, txPowerLevel, name);
    }

    public EddystoneUID(byte[] namespaceInstance, String domainHint,
                        @Beacon.AdvertiseMode int mode,
                        @Beacon.AdvertiseTxPower int txPowerLevel) {
        this(0, namespaceInstance, domainHint, null, mode, txPowerLevel, null);
    }

    public EddystoneUID(byte[] namespaceInstance,
                        @Beacon.AdvertiseMode int mode,
                        @Beacon.AdvertiseTxPower int txPowerLevel) {
        this(0, namespaceInstance, null, null, mode, txPowerLevel, null);
    }

    public EddystoneUID(byte[] namespaceInstance, String domainHint, byte[] lockKey, String name) {
        super(EDDYSTONE_UID, lockKey, name);

        mNamespaceInstance = null != namespaceInstance ? namespaceInstance : new byte[16];
        mDomainHint = domainHint;
    }

    public EddystoneUID(byte[] namespaceInstance, byte[] lockKey) {
        this(namespaceInstance, null, lockKey, null);
    }

    public EddystoneUID(byte[] namespaceInstance, String name) {
        this(namespaceInstance, null, null, name);
    }

    public EddystoneUID(byte[] namespaceInstance) {
        this(namespaceInstance, null, null, null);
    }

    /**
     * Creates a new blank Eddystone-UID beacon (namespace and instance zero-ed out)
     */
    public EddystoneUID() {
        this(null);
    }

    @Override
    public EddystoneBase cloneBeacon() {
        return new EddystoneUID(0, getNamespaceInstance(), getDomainHint(), getLockKey(),
                getAdvertiseMode(), getTxPowerLevel(), getName());
    }

    @Override
    public int getType() {
        return EDDYSTONE_UID;
    }

    @Override
    public Advertiser createAdvertiser(AdvertisersManager advertisersManager) {
        return setAdvertiser(new EddystoneAdvertiser(mNamespaceInstance, 0, 16, advertisersManager,
                getAdvertiseMode(), getTxPowerLevel(), isConnectable(), getFlags()));
    }

    public String getDomainHint() {
        return mDomainHint;
    }

    public byte[] getNamespaceInstance() {
        return mNamespaceInstance;
    }

    @Override
    public EddystoneUIDEditor edit() {
        return new EddystoneUIDEditor();
    }

    public class EddystoneUIDEditor extends EddystoneEditor {
        public EddystoneUIDEditor setNamespaceInstance(byte[] namespaceInstance) {
            if (!Arrays.equals(mNamespaceInstance, namespaceInstance)) {
                mNamespaceInstance = namespaceInstance;
                mRestartBeacon = true;
            }
            return this;
        }

        public EddystoneUIDEditor setDomainHint(String domainHint) {
            mDomainHint = domainHint;
            return this;
        }
    }
}
