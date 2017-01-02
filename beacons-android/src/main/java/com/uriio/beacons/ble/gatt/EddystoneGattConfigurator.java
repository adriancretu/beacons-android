package com.uriio.beacons.ble.gatt;

import android.bluetooth.le.AdvertiseSettings;
import android.util.Base64;

import com.uriio.beacons.BuildConfig;
import com.uriio.beacons.Util;
import com.uriio.beacons.ble.AdvertisersManager;
import com.uriio.beacons.ble.EddystoneAdvertiser;
import com.uriio.beacons.model.Beacon;
import com.uriio.beacons.model.EddystoneBase;
import com.uriio.beacons.model.EddystoneEID;
import com.uriio.beacons.model.EddystoneUID;
import com.uriio.beacons.model.EddystoneURL;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Manages a GATT-configurable Eddystone beacon.
 * Created on 5/25/2016.
 */
class EddystoneGattConfigurator implements EddystoneGattConfigCallback {
    private static final String TAG = "EddystoneGattConfig";
    private static final boolean D = BuildConfig.DEBUG;

    /**
     * The configured beacon. This will never broadcast during configuration, because starting
     * or stopping any LE advertiser kills the GATT connection and Beacon Tools crashes during EID registration.
     */
    private EddystoneBase mConfiguredBeacon = null;
    private final EddystoneBase mOriginalBeacon;
    private boolean mIsAdvertisingSet = false;

    public EddystoneGattConfigurator(EddystoneBase beacon) {
        mOriginalBeacon = beacon;
    }

    /**
     * @return Current beacon configuration, or null if advertising data is not yet set.
     */
    public EddystoneBase getConfiguredBeacon() {
        return mIsAdvertisingSet ? getModifiedOrOriginalBeacon() : null;
    }

    @Override
    public byte[] getAdvertisedData() {
        // use either the currently altered beacon, or the original one
        EddystoneBase beacon = getModifiedOrOriginalBeacon();

        if (beacon.getType() == Beacon.EDDYSTONE_EID) {
            EddystoneEID eddystoneEID = (EddystoneEID) this.mConfiguredBeacon;
            EddystoneAdvertiser advertiser = eddystoneEID.createAdvertiser(null);
            if (null == advertiser) {
                return new byte[0];
            }

            ByteBuffer buffer = ByteBuffer.allocate(14);

            buffer.put((byte) 0x30);
            buffer.put(eddystoneEID.getRotationExponent());
            buffer.putInt(eddystoneEID.getEidClock());
            buffer.put(advertiser.getServiceData(), 2, 8);

            return buffer.array();
        }

        EddystoneAdvertiser advertiser = (EddystoneAdvertiser) beacon.createAdvertiser(null);
        return null == advertiser ? new byte[0] : advertiser.getServiceData();
    }

    @Override
    public void advertiseURL(String url) {
        EddystoneBase beacon = getModifiedOrOriginalBeacon();

        boolean sameType = beacon.getType() == Beacon.EDDYSTONE_URL;
        boolean changed = !sameType;
        if (sameType) {
            String currentUrl = ((EddystoneURL) beacon).getURL();
            changed = (null == url && null != currentUrl) || (null != url && !url.equals(currentUrl));
        }

        if (changed) {
            if (null == mConfiguredBeacon || !sameType) {
                mConfiguredBeacon = new EddystoneURL(0, url, beacon.getLockKey(), beacon.getAdvertiseMode(),
                        beacon.getTxPowerLevel(), beacon.getName());
            } else {
                ((EddystoneURL) mConfiguredBeacon).edit().setUrl(url).apply();
            }
        }

        mIsAdvertisingSet = true;
    }

    @Override
    public void advertiseUID(byte[] namespaceInstance) {
        EddystoneBase beacon = getModifiedOrOriginalBeacon();

        boolean sameType = beacon.getType() == Beacon.EDDYSTONE_UID;
        boolean changed = !sameType;
        if (sameType) {
            byte[] current = ((EddystoneUID) beacon).getNamespaceInstance();
            changed = !Arrays.equals(current, namespaceInstance);
        }

        if (changed) {
            if (null == mConfiguredBeacon || beacon.getType() != Beacon.EDDYSTONE_UID) {
                mConfiguredBeacon = new EddystoneUID(0, namespaceInstance, null, beacon.getLockKey(),
                        beacon.getAdvertiseMode(), beacon.getTxPowerLevel(), beacon.getName());
            } else {
                ((EddystoneUID) mConfiguredBeacon).edit().setNamespaceInstance(namespaceInstance).apply();
            }
        }

        mIsAdvertisingSet = true;
    }

    @Override
    public void advertiseEID(byte[] identityKey, byte rotationExponent) {
        EddystoneBase beacon = getModifiedOrOriginalBeacon();

        boolean sameType = beacon.getType() == Beacon.EDDYSTONE_EID;
        boolean changed = !sameType;
        if (sameType) {
            EddystoneEID eidBeacon = (EddystoneEID) beacon;
            changed = rotationExponent != eidBeacon.getRotationExponent() || !Arrays.equals(identityKey, eidBeacon.getIdentityKey());
        }

        if (changed) {
            // NOTE - because we don't have the EID clock offset from real-time, used at registration,
            // this call assumes that a new beacon has just been EID registered, or that registration
            // is in progress. The time counter is initialized to 65280, and to account for this,
            // the actual offset to real time is saved as a persistent property for future beacon restarts
            // Latency to HTTP calls for registration, or a wrong device time setting, may impact EID accuracy.

            int now = (int) (System.currentTimeMillis() / 1000);
            // https://github.com/google/eddystone/blob/master/eddystone-eid/eid-computation.md#implementation-guidelines
            int timeCounter = now & ~0xffff | 65280;

            // save the offset between current time and time counter so we can restore correctly
            int clockOffset = now - timeCounter;

            mConfiguredBeacon = new EddystoneEID(identityKey, rotationExponent, clockOffset, beacon.getLockKey(),
                    beacon.getAdvertiseMode(), beacon.getTxPowerLevel(), beacon.getName());

            if (D) Util.log(TAG, "advertiseEID clockOffset = " + clockOffset
                    + " identityKey = " + Base64.encodeToString(identityKey, Base64.URL_SAFE)
                    + " rotationExponent = " + rotationExponent);
        }

        mIsAdvertisingSet = true;
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
        return AdvertisersManager.getSupportedRadioTxPowers()[getModifiedOrOriginalBeacon().getTxPowerLevel()];
    }

    @Override
    public int getAdvertisedTxPower() {
        return AdvertisersManager.getZeroDistanceTxPower(getModifiedOrOriginalBeacon().getTxPowerLevel());
    }

    @Override
    public byte[] getEidIdentityKey() {
        EddystoneBase beacon = getModifiedOrOriginalBeacon();
        if (beacon.getType() ==  Beacon.EDDYSTONE_EID) {
            return ((EddystoneEID) beacon).getIdentityKey();
        }
        return null;
    }

    @Override
    public int setRadioTxPower(byte txPower) {
        byte[] txPowers = getSupportedRadioTxPowers();
        @Beacon.AdvertiseTxPower int txPowerLevel = 0;

        for (int i = 0; i < txPowers.length; i++) {
            if (txPower >= txPowers[i]) {
                //noinspection WrongConstant
                txPowerLevel = i;
            }
        }

        if (txPowerLevel != getModifiedOrOriginalBeacon().getTxPowerLevel()) {
            // restarting a beacon destroys the GATT connection, make sure we use a stopped clone
            getOrCloneConfiguredBeacon().edit().setAdvertiseTxPower(txPowerLevel).apply();
        }

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

        if (mode != getModifiedOrOriginalBeacon().getAdvertiseMode()) {
            // restarting a beacon destroys the GATT connection, make sure we use a stopped clone
            getOrCloneConfiguredBeacon().edit().setAdvertiseMode(mode).apply();
        }

        return getAdvertiseInterval();
    }

    @Override
    public int getAdvertiseInterval() {
        switch (getModifiedOrOriginalBeacon().getAdvertiseMode()) {
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
        return getModifiedOrOriginalBeacon().getLockKey();
    }

    @Override
    public void setLockKey(byte[] lockKey) {
        // in case beacon is not yet modified, lock key is saved to original
        // if beacon is modified, the new lock key will be copied anyway when it's created
        getModifiedOrOriginalBeacon().edit().setLockKey(lockKey).apply();
    }

    private EddystoneBase getModifiedOrOriginalBeacon() {
        return null != mConfiguredBeacon ? mConfiguredBeacon : mOriginalBeacon;
    }

    private EddystoneBase getOrCloneConfiguredBeacon() {
        if (null == mConfiguredBeacon) {
            mConfiguredBeacon = mOriginalBeacon.cloneBeacon();
        }

        return mConfiguredBeacon;
    }
}