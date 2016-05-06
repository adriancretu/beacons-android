package com.uriio.beacons.model;

import com.uriio.beacons.Storage;
import com.uriio.beacons.Util;
import com.uriio.beacons.ble.AppleBeacon;
import com.uriio.beacons.ble.BLEAdvertiseManager;
import com.uriio.beacons.ble.Beacon;

/**
 * Created on 7/21/2015.
 */
public class iBeaconItem extends BaseItem {
    private byte[] mUuid;
    private int mMajor;
    private int mMinor;

    public iBeaconItem(long itemId, int flags, byte[] uuid, int major, int minor) {
        super(itemId, flags);

        mUuid = uuid;
        mMajor = major;
        mMinor = minor;
    }

    @Override
    public Beacon createBeacon(BLEAdvertiseManager bleAdvertiseManager) {
        mBeacon = new AppleBeacon(bleAdvertiseManager, getAdvertiseMode(), (byte) getTxPowerLevel(),
                mUuid, mMajor, mMinor, getFlags());
        return mBeacon;
    }

    @Override
    public int getKind() {
        return Storage.KIND_IBEACON;
    }

    public String getUuid() {
        return Util.binToUUID(mUuid).toString();
    }

    public int getMajor() {
        return mMajor;
    }

    public int getMinor() {
        return mMinor;
    }

    public void update(int mode, int txPowerLevel, byte[] uuid, int major, int minor, String name) {
        setAdvertiseMode(mode);
        setTxPowerLevel(txPowerLevel);
        setName(name);
        mUuid = uuid;
        mMajor = major;
        mMinor = minor;
    }
}