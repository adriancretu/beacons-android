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
public class AdvertisersManager {
    public interface BLEListener {
        void onBLEAdvertiseNotSupported();
        void onBLEAdvertiseStarted(Advertiser advertiser);
        void onBLEAdvertiseFailed(Advertiser advertiser, int errorCode);
    }

    private static final String TAG = "AdvertisersManager";

    private BluetoothLeAdvertiser mBleAdvertiser = null;
    private List<Advertiser> mAdvertisers = new ArrayList<>();
    private final BluetoothAdapter mBluetoothAdapter;
    private BLEListener mListener;

    /** Received TX power at 0 meters, for each TX power level **/
    private static final byte[] _txPower = new byte[] {
        -59, -35, -26, -16
    };

    public AdvertisersManager(BluetoothManager bluetoothManager, BLEListener listener) {
        mListener = listener;
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // fixme - change the measured powers depending on device
        Util.log("AdvertisersManager > product: " + Build.MODEL);
    }

    public boolean startAdvertiser(Advertiser advertiser) {
        if (null == mBleAdvertiser) {
            if (!mBluetoothAdapter.isEnabled()) {
                return false;
            }

            if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
                return false;
            }

            mBleAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
            if (null == mBleAdvertiser) {
                return false;
            }
        }

        //btAdapter.setName("AndroidBLE");   // beware - changes the name at OS level!
        advertiser.startAdvertising(mBleAdvertiser);
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

    public List<Advertiser> getAdvertisedItems() {
        return mAdvertisers;
    }

    public void onAdvertiserStarted(Advertiser advertiser) {
        mAdvertisers.add(advertiser);
        mListener.onBLEAdvertiseStarted(advertiser);
    }

    public void onAdvertiserFailed(Advertiser advertiser, int errorCode) {
        mAdvertisers.remove(advertiser);
        mListener.onBLEAdvertiseFailed(advertiser, errorCode);
    }

    public void enableAdvertiser(Advertiser advertiser, boolean enable) {
        if (enable) {
            startAdvertiser(advertiser);
        } else {
            if (null != mBleAdvertiser) {
                mBleAdvertiser.stopAdvertising(advertiser);
            }
            mAdvertisers.remove(advertiser);
            advertiser.setStoppedState();
        }
    }

    public boolean canAdvertise() {
        if (null != mBleAdvertiser) return true;

        if (!mBluetoothAdapter.isEnabled()) {
            return false;
        }

        if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
            mListener.onBLEAdvertiseNotSupported();
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
    public static byte getZeroDistanceTxPower(int powerLevel) {
        return _txPower[powerLevel];
    }

    public static byte[] getSupportedTxPowers() {
        return _txPower;
    }
}