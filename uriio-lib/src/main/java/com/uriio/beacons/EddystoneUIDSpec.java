package com.uriio.beacons;

/**
 * Created on 5/21/2016.
 */
public class EddystoneUIDSpec extends BeaconSpec {
    private byte[] namespaceInstance;
    private String domainHint;

    /**
     * Eddystone UID spec.
     * @param namespaceInstance    16-byte buffer of namespace (10 bytes) and instance (6 bytes)
     * @param domainHint           Optional domain name hinted as the source for namespace.
     */
    public EddystoneUIDSpec(byte[] namespaceInstance, String domainHint, @AdvertiseMode int mode, @AdvertiseTxPower int txPowerLevel, String name) {
        super(EDDYSTONE_UID, mode, txPowerLevel, name);
        this.namespaceInstance = namespaceInstance;
        this.domainHint = domainHint;
    }

    public byte[] getNamespaceInstance() {
        return namespaceInstance;
    }

    public String getDomainHint() {
        return domainHint;
    }
}
