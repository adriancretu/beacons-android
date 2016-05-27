package com.uriio.beacons.ble.gatt;

import android.bluetooth.le.AdvertiseSettings;
import android.util.Base64;

import com.uriio.beacons.Util;
import com.uriio.beacons.ble.AdvertisersManager;
import com.uriio.beacons.ble.EddystoneAdvertiser;
import com.uriio.beacons.model.Beacon;
import com.uriio.beacons.model.EddystoneBase;
import com.uriio.beacons.model.EddystoneEID;
import com.uriio.beacons.model.EddystoneUID;
import com.uriio.beacons.model.EddystoneURL;

import java.nio.ByteBuffer;

/**
 * Manages a GATT-configurable Eddystone beacon.
 * Created on 5/25/2016.
 */
class EddystoneGattConfigurator implements EddystoneGattConfigCallback {
    private static final String TAG = "EddystoneGattConfig";

    /**
     * The configured beacon. This will never broadcast during configuration, because starting
     * or stopping any LE advertiser kills the GATT connection and Beacon Tools crashes during EID registration.
     */
    private EddystoneBase mBeacon;
    private boolean mIsAdvertisingSet = false;

    public EddystoneGattConfigurator(EddystoneBase initialBeacon) {
        mBeacon = initialBeacon;
    }

    /**
     * @return Current beacon configuration, or null if advertising data is not yet set.
     */
    public EddystoneBase getConfiguredBeacon() {
        return mIsAdvertisingSet ? mBeacon : null;
    }

    @Override
    public byte[] getAdvertisedData() {
        if (null == mBeacon) return new byte[0];

        if (mBeacon.getType() == Beacon.EDDYSTONE_EID) {
            EddystoneEID eddystoneEID = (EddystoneEID) this.mBeacon;

            ByteBuffer buffer = ByteBuffer.allocate(14);

            buffer.put((byte) 0x30);
            buffer.put(eddystoneEID.getRotationExponent());
            buffer.putInt(eddystoneEID.getEidClock());
            buffer.put(eddystoneEID.createBeacon(null).getServiceData(), 2, 8);

            return buffer.array();
        }
        return ((EddystoneAdvertiser) mBeacon.createBeacon(null)).getServiceData();
    }

    @Override
    public void advertiseURL(String url) {
        if (mBeacon.getType() != Beacon.EDDYSTONE_URL) {
            mBeacon = new EddystoneURL(0, url, mBeacon.getLockKey(), mBeacon.getAdvertiseMode(),
                    mBeacon.getTxPowerLevel(), mBeacon.getName());
        }
        else {
            ((EddystoneURL) mBeacon).edit().setUrl(url).apply();
        }

        mIsAdvertisingSet = true;
    }

    @Override
    public void advertiseUID(byte[] namespaceInstance) {
        if (mBeacon.getType() != Beacon.EDDYSTONE_UID) {
            mBeacon = new EddystoneUID(0, namespaceInstance, null, mBeacon.getLockKey(),
                    mBeacon.getAdvertiseMode(), mBeacon.getTxPowerLevel(), mBeacon.getName());
        }
        else {
            ((EddystoneUID) mBeacon).edit().setNamespaceInstance(namespaceInstance).apply();
        }

        mIsAdvertisingSet = true;
    }

    @Override
    public void advertiseEID(byte[] identityKey, byte rotationExponent) {
        // NOTE - this call assumes that the beacon has just been EID registered, or that registration
        // is in progress. The time counter is initialized to 65280, and to account for this,
        // the actual offset to real time is saved as a persistent property for future beacon restarts
        // Latency to HTTP calls for registration, or a wrong device time setting, may impact EID accuracy.

        int now = (int) (System.currentTimeMillis() / 1000);
        // https://github.com/google/eddystone/blob/master/eddystone-eid/eid-computation.md#implementation-guidelines
        int timeCounter = now & ~0xffff | 65280;

        // save the offset between current time and time counter so we can restore correctly
        int timeOffset = now - timeCounter;

        mBeacon = new EddystoneEID(identityKey, rotationExponent, timeOffset, mBeacon.getLockKey(),
                mBeacon.getAdvertiseMode(), mBeacon.getTxPowerLevel(), mBeacon.getName());

        mIsAdvertisingSet = true;

        Util.log(TAG, "advertiseEID timeOffset = " + timeOffset + " identityKey = "
                + Base64.encodeToString(identityKey, Base64.URL_SAFE) + " rotationExponent = " + rotationExponent);
    }

    @Override
    public void stopAdvertise() {
        // we never actually advertise during config
        mIsAdvertisingSet = false;
    }

    @Override
    public byte[] getSupportedRadioTxPowers() {
        return AdvertisersManager.getSupportedRadioTxPowers();
    }

    @Override
    public int getRadioTxPower() {
        return AdvertisersManager.getSupportedRadioTxPowers()[mBeacon.getTxPowerLevel()];
    }

    @Override
    public int getAdvertisedTxPower() {
        return AdvertisersManager.getZeroDistanceTxPower(mBeacon.getTxPowerLevel());
    }

    @Override
    public byte[] getEidIdentityKey() {
        if (mBeacon.getType() ==  Beacon.EDDYSTONE_EID) {
            return ((EddystoneEID) mBeacon).getIdentityKey();
        }
        return null;
    }

    @Override
    public int setRadioTxPower(byte txPower) {
        byte[] txPowers = getSupportedRadioTxPowers();
        int txPowerLevel = 0;

        for (int i = 0; i < txPowers.length; i++) {
            if (txPower >= txPowers[i]) txPowerLevel = i;
        }

        //noinspection WrongConstant
        mBeacon.edit().setAdvertiseTxPower(txPowerLevel).apply();

        return txPowers[txPowerLevel];
    }

    @Override
    public int setAdvertiseInterval(int advertiseIntervalMs) {
        Util.log(TAG, "setAdvertiseInterval() called with: advertiseIntervalMs = [" + advertiseIntervalMs + "]");

        @Beacon.AdvertiseMode int mode;

        if (advertiseIntervalMs <= 100 + (250 - 100) / 2) {
            // 100 ms
            mode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY;
        } else if (advertiseIntervalMs >= 1000 - (1000 - 250) / 2) {
            // 1000 ms
            mode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
        } else {
            // 250 ms actually
            mode = AdvertiseSettings.ADVERTISE_MODE_BALANCED;
        }

        mBeacon.edit().setAdvertiseMode(mode).apply();

        return getAdvertiseInterval();
    }

    @Override
    public int getAdvertiseInterval() {
        switch (mBeacon.getAdvertiseMode()) {
            case AdvertiseSettings.ADVERTISE_MODE_LOW_POWER:
            default:
                return 1000;
            case AdvertiseSettings.ADVERTISE_MODE_BALANCED:
                return 250;
            case AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY:
                return 100;
        }
    }

    @Override
    public byte[] getLockKey() {
        return mBeacon.getLockKey();
    }

    @Override
    public void setLockKey(byte[] lockKey) {
        mBeacon.edit().setLockKey(lockKey).apply();
    }
}