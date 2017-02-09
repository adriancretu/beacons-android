package com.uriio.beacons.ble;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
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
    public interface Listener {
        void onAdvertiserStarted(Advertiser advertiser);
        void onAdvertiserFailed(Advertiser advertiser, int errorCode);
    }

    private static final String TAG = "AdvertisersManager";

    private BluetoothLeAdvertiser mBleAdvertiser = null;
    private List<Advertiser> mAdvertisers = new ArrayList<>();
    private final BluetoothAdapter mBluetoothAdapter;
    private Listener mListener;

    /** Received TX power at 0 meters, for each TX power level **/
    private static final byte[] _advertisedTxPowers = new byte[] {
            -59, -35, -26, -16
    };
    private static final byte[] _radioTxPowers = new byte[] {
            // Nexus 6 / Android 6.0.1 actual TX Power characteristic values
            -21, -15, -7, 1
    };

    public AdvertisersManager(BluetoothManager bluetoothManager, Listener listener) {
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

        // temporarily change local device name if it will be used in BLE payload
        String oldAdapterName = null;
        String tempLocalName = advertiser.getAdvertisedLocalName();
        if (null != tempLocalName) {
            oldAdapterName = mBluetoothAdapter.getName();
            if (tempLocalName.equals(oldAdapterName)) {
                // same name already, don't change it
                tempLocalName = null;
            }
            if (null != tempLocalName) {
                // changes the name at OS level!
                mBluetoothAdapter.setName(tempLocalName);
            }
        }

        boolean success = advertiser.start(mBleAdvertiser);

        // change adapter name back
        if (null != tempLocalName) {
            mBluetoothAdapter.setName(oldAdapterName);
        }

        return success;
    }

    public void onBluetoothOff() {
        // trying to actually STOP active advertisers at this point crashes with 'BT adapter not turned on'
        // the BLE advertiser is now invalid; clear it so we don't try to use it again
        mBleAdvertiser = null;
        clearAdvertisers();
    }

    private void clearAdvertisers() {
        for (Advertiser advertiser : mAdvertisers) {
            advertiser.stop(mBleAdvertiser);
        }
        mAdvertisers.clear();
    }

    public void close() {
        clearAdvertisers();
    }

    public List<Advertiser> getAdvertisedItems() {
        return mAdvertisers;
    }

    void onAdvertiserStarted(Advertiser advertiser) {
        mAdvertisers.add(advertiser);
        mListener.onAdvertiserStarted(advertiser);
    }

    void onAdvertiserFailed(Advertiser advertiser, int errorCode) {
        mAdvertisers.remove(advertiser);
        mListener.onAdvertiserFailed(advertiser, errorCode);
    }

    public void stopAdvertiser(Advertiser advertiser) {
        advertiser.stop(mBleAdvertiser);
        mAdvertisers.remove(advertiser);
    }

    public boolean isBluetoothEnabled() {
        return mBluetoothAdapter.isEnabled();
    }

    /**
     * Checks whether BLE advertising is supported. This method must only be called after checking
     * that Bluetooth is enabled.
     * @return True if a BLE advertiser instance already exists or BLE advertisement is supported.
     */
    public boolean canAdvertise() {
        if (null != mBleAdvertiser) return true;

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
    public static byte getZeroDistanceTxPower(int powerLevel) {
        return _advertisedTxPowers[powerLevel];
    }

    public static byte[] getSupportedRadioTxPowers() {
        return _radioTxPowers;
    }
}