package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Build;

import com.uriio.beacons.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages Bluetooth Low Energy advertising.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class BLEAdvertiseManager {
    public interface BLEListener {
        void onBLEAdvertiseNotSupported();
        void onBLEAdvertiseStarted(Beacon beacon);
        void onBLEAdvertiseFailed(Beacon beacon, int errorCode);
    }

    private static final String TAG = "BLEAdvertiseManager";

    private BluetoothLeAdvertiser mBleAdvertiser = null;
    private List<Beacon> mAdvertisers = new ArrayList<>();
    private final BluetoothAdapter mBluetoothAdapter;
    private BLEListener mListener;

    /** Measured power from 1 meter away, for each TX power level **/
    private final byte[] mCalibratedMeasuredPower = new byte[] {
        -59, -35, -26, -16
    };

    public BLEAdvertiseManager(BluetoothManager bluetoothManager, BLEListener listener) {
        mListener = listener;
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // fixme - change the measured powers depending on device
        Util.log("BLEAdvertiseManager > product: " + Build.MODEL);
    }

    public boolean startAdvertiser(Beacon beacon) {
        if (null == mBleAdvertiser) {
            if (!mBluetoothAdapter.isEnabled()) {
                return false;
            }

            if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
                mListener.onBLEAdvertiseNotSupported();
                return false;
            }

            mBleAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
            if (null == mBleAdvertiser) {
                return false;
            }
        }

        //btAdapter.setName("AndroidBLE");   // beware - changes the name at OS level!
        beacon.startAdvertising(mBleAdvertiser);
        return true;
    }

    public void onBluetoothOff() {
        // trying to actually STOP active advertisers at this point crashes with 'BT adapter not turned on'
        mAdvertisers.clear();
        // the BLE advertiser is now invalid; clear it so we don't try to use it again
        mBleAdvertiser = null;
    }

    public void clearAdvertisers() {
        if (null != mBleAdvertiser) {
            for (AdvertiseCallback advertiser : mAdvertisers) {
                mBleAdvertiser.stopAdvertising(advertiser);
            }
        }
        mAdvertisers.clear();
    }

    public void close() {
        clearAdvertisers();
    }

    public List<Beacon> getAdvertisedItems() {
        return mAdvertisers;
    }

    public void onAdvertiserStarted(Beacon beacon) {
        mAdvertisers.add(beacon);
        mListener.onBLEAdvertiseStarted(beacon);
    }

    public void onAdvertiserFailed(Beacon beacon, int errorCode) {
        mAdvertisers.remove(beacon);
        mListener.onBLEAdvertiseFailed(beacon, errorCode);
    }

    public void enableAdvertiser(Beacon beacon, boolean enable) {
        if (enable) {
            startAdvertiser(beacon);
        } else {
            if (null != mBleAdvertiser) {
                mBleAdvertiser.stopAdvertising(beacon);
            }
            mAdvertisers.remove(beacon);
            beacon.setStoppedState();
        }
    }

    public boolean canAdvertise() {
        if (!mBluetoothAdapter.isEnabled()) {
            return false;
        }

        if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
            return false;
        }

        mBleAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        return null != mBleAdvertiser;
    }

    /**
     * Tx power is the received power at 0 meters, in dBm, and the value ranges from -100 dBm to +20 dBm to a resolution of 1 dBm.
     * The best way to determine the precise value to put into this field is to measure the actual output of your beacon from 1 meter away and then add 41dBm to that. 41dBm is the signal loss that occurs over 1 meter.
     * @return TX power
     */
    protected byte getZeroDistanceTxPower(int powerLevel) {
        return mCalibratedMeasuredPower[powerLevel];
    }
}