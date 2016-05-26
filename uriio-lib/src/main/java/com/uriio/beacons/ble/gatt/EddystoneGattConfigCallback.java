package com.uriio.beacons.ble.gatt;

/**
 * Callbacks for a GATT-configurable beacon.
 */

public interface EddystoneGattConfigCallback {

    /**
     * @return Currently broadcasted Service data.
     */
    byte[] getAdvertisedData();

    byte[] getSupportedTxPowers();

    /**
     * Change Advertise TX power
     * @param advertisedTxPower    Requested TX power (may not be one in the supoorted list)
     * @return Final resolved TX power
     */
    int setAdvertiseTxPower(byte advertisedTxPower);


    /**
     * Change advertise interval
     * @param advertiseIntervalMs    Requested advertise interval, in milliseconds
     * @return Final resolved advertised interval
     */
    int setAdvertiseInterval(int advertiseIntervalMs);

    /**
     * Requests advertising an URL, in an already encoded form.
     * @param advertiseData
     */
    void advertiseURL(byte[] advertiseData);

    /**
     * @param namespaceInstance    16-bytes namespace + instance raw data.
     */
    void advertiseUID(byte[] namespaceInstance);

    /**
     * Sets the beacon to transmit EID frames.
     * @param identityKey         Computed (or received) Identity Key
     * @param rotationExponent    Rotation Exponent.
     */
    void advertiseEID(byte[] identityKey, byte rotationExponent);

    /**
     * Called when configured to an empty advertise data, or factory reset.
     */
    void stopAdvertise();

    int getRadioTxPower();

    int getAdvertisedTxPower();

    byte[] getEidIdentityKey();

    int getAdvertiseInterval();

    /**
     * @return This beacon's Lock key (16 bytes)
     */
    byte[] getLockKey();

    /**
     * Saves the new lock key for this beacon.
     * @param lockKey    New lock key.
     */
    void setLockKey(byte[] lockKey);
}
