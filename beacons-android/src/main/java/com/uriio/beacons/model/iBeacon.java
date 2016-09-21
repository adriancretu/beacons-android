package com.uriio.beacons.model;

import com.uriio.beacons.Storage;
import com.uriio.beacons.Util;
import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.ble.AdvertisersManager;
import com.uriio.beacons.ble.iBeaconAdvertiser;

import java.util.Arrays;

/**
 * Created on 7/21/2015.
 */
public class iBeacon extends Beacon {
    private byte[] mUuid;
    private int mMajor;
    private int mMinor;

    public iBeacon(long itemId,
                   byte[] uuid, int major, int minor, @AdvertiseMode int advertiseMode,
                   @AdvertiseTxPower int txPowerLevel,
                   int flags, String name) {
        super(itemId, advertiseMode, txPowerLevel, flags, name);

        init(uuid, major, minor);
    }

    public iBeacon(byte[] uuid, int major, int minor, @AdvertiseMode int advertiseMode,
                   @AdvertiseTxPower int txPowerLevel,
                   int flags, String name) {
        this(0, uuid, major, minor, advertiseMode, txPowerLevel, flags, name);
    }

    public iBeacon(byte[] uuid, int major, int minor, @AdvertiseMode int advertiseMode,
                   @AdvertiseTxPower int txPowerLevel, String name) {
        this(0, uuid, major, minor, advertiseMode, txPowerLevel, iBeaconAdvertiser.FLAG_APPLE, name);
    }

    public iBeacon(byte[] uuid, int major, int minor, @AdvertiseMode int advertiseMode,
                   @AdvertiseTxPower int txPowerLevel) {
        this(0, uuid, major, minor, advertiseMode, txPowerLevel, iBeaconAdvertiser.FLAG_APPLE, null);
    }

    public iBeacon(byte[] uuid, int major, int minor, String name) {
        super(iBeaconAdvertiser.FLAG_APPLE, name);
        init(uuid, major, minor);
    }

    public iBeacon(byte[] uuid, int major, int minor) {
        this(uuid, major, minor, null);
    }

    @Override
    public int getType() {
        return IBEACON;
    }

    private void init(byte[] uuid, int major, int minor) {
        mUuid = uuid;
        mMajor = major;
        mMinor = minor;
    }

    @Override
    public Advertiser createBeacon(AdvertisersManager advertisersManager) {
        return setBeacon(new iBeaconAdvertiser(advertisersManager, getAdvertiseMode(), getTxPowerLevel(),
                mUuid, mMajor, mMinor, getFlags(), isConnectable()));
    }

    @Override
    public int getKind() {
        return Storage.KIND_IBEACON;
    }

    public String getUuid() {
        return Util.binToUUID(mUuid).toString();
    }

    public byte[] getUuidRaw() {
        return mUuid;
    }

    public int getMajor() {
        return mMajor;
    }

    public int getMinor() {
        return mMinor;
    }

    @Override
    public iBeaconEditor edit() {
        return new iBeaconEditor();
    }

    public class iBeaconEditor extends Editor {
        public Editor setIndicators(byte[] uuid, int major, int minor) {
            if (major != mMajor || minor != mMinor || !Arrays.equals(mUuid, uuid)) {
                init(uuid, major, minor);
                mRestartBeacon = true;
            }
            return this;
        }
    }
}