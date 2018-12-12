package com.uriio.beacons.model;

import com.uriio.beacons.BleService;
import com.uriio.beacons.Storage;
import com.uriio.beacons.ble.Advertiser;
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
    public EddystoneUID(byte[] namespaceInstance, String domainHint, byte[] lockKey,
                        @Advertiser.Mode int mode,
                        @Advertiser.Power int txPowerLevel, String name) {
        super(lockKey, mode, txPowerLevel, name);

        mNamespaceInstance = null != namespaceInstance ? namespaceInstance : new byte[16];
        mDomainHint = domainHint;
    }

    public EddystoneUID(byte[] namespaceInstance, String domainHint,
                        @Advertiser.Mode int mode,
                        @Advertiser.Power int txPowerLevel, String name) {
        this(namespaceInstance, domainHint, null, mode, txPowerLevel, name);
    }

    public EddystoneUID(byte[] namespaceInstance, String domainHint,
                        @Advertiser.Mode int mode,
                        @Advertiser.Power int txPowerLevel) {
        this(namespaceInstance, domainHint, null, mode, txPowerLevel, null);
    }

    public EddystoneUID(byte[] namespaceInstance,
                        @Advertiser.Mode int mode,
                        @Advertiser.Power int txPowerLevel) {
        this(namespaceInstance, null, null, mode, txPowerLevel, null);
    }

    public EddystoneUID(byte[] namespaceInstance, String domainHint, byte[] lockKey, String name) {
        super(lockKey, name);

        mNamespaceInstance = null != namespaceInstance ? namespaceInstance : new byte[16];
        mDomainHint = domainHint;
    }

    public EddystoneUID(byte[] namespaceInstance, String domainHint, byte[] lockKey) {
        this(namespaceInstance, domainHint, lockKey, null);
    }

    public EddystoneUID(byte[] namespaceInstance, byte[] lockKey) {
        this(namespaceInstance, null, lockKey);
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
    public int getKind() {
        return Storage.KIND_EDDYSTONE_UID;
    }

    @Override
    public EddystoneBase cloneBeacon() {
        return new EddystoneUID(getNamespaceInstance(), getDomainHint(), getLockKey(),
                getAdvertiseMode(), getTxPowerLevel(), getName());
    }

    @Override
    public Advertiser createAdvertiser(BleService service) {
        return new EddystoneAdvertiser(this, EddystoneAdvertiser.FRAME_UID, mNamespaceInstance, 0, 16);
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
                setNeedsRestart();
            }
            return this;
        }

        public EddystoneUIDEditor setDomainHint(String domainHint) {
            mDomainHint = domainHint;
            return this;
        }
    }
}
