package com.uriio.beacons.ble.gatt;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import com.uriio.beacons.Loggable;
import com.uriio.beacons.Util;
import com.uriio.beacons.model.EddystoneBase;

import java.util.List;

import static android.content.Context.BLUETOOTH_SERVICE;

/**
 * Bluetooth GATT Server hosting an Eddystone config service.
 */
public class EddystoneGattServer extends BluetoothGattServerCallback {
    public interface Listener {
        void onGattFinished(EddystoneBase configuredBeacon);
    }

    private EddystoneGattConfigurator mEddystoneConfigurator;
    private static final String TAG = "EddystoneGattServer";

    private EddystoneGattService mEddystoneGattService = null;
    private BluetoothGattServer mGattServer;
    private Listener mListener;
    private EddystoneBase mPivotBeacon;
    private Loggable mLogger;
    private BluetoothManager mBluetoothManager;

    public EddystoneGattServer(EddystoneBase pivotBeacon, Listener listener, Loggable loggable) {
        mListener = listener;
        mPivotBeacon = pivotBeacon;
        mLogger = loggable;
    }

    public boolean start(Context context, EddystoneBase currentBeacon) {
        mBluetoothManager = (BluetoothManager) context.getSystemService(BLUETOOTH_SERVICE);
        mGattServer = mBluetoothManager.openGattServer(context, this);
        if (null == mGattServer) {
            log("Failed to open GATT server");
            return false;
        }

        List<BluetoothGattService> gattServices = mGattServer.getServices();
        for (BluetoothGattService service : gattServices) {
            if (service.getUuid() == EddystoneGattService.UUID_EDDYSTONE_SERVICE) {
                log("An Eddystone GATT service is already running on this device");
                close();
                return false;
            }
        }

        mEddystoneConfigurator = new EddystoneGattConfigurator(currentBeacon);

        mEddystoneGattService = new EddystoneGattService(this, mEddystoneConfigurator, mLogger);
        if (!mGattServer.addService(mEddystoneGattService.getService())) {
            log("Failed to add Eddystone GATT service");
            close();
            return false;
        }

        if (null != mPivotBeacon) {
            // advertise beacon as connectable
            log("Making pivot beacon connectable");
            mPivotBeacon.edit().setConnectable(true).apply();
        }

        return true;
    }

    public void close() {
        if (null != mGattServer) {
            mGattServer.close();
            mGattServer = null;
        }

        if (null != mPivotBeacon) {
            mPivotBeacon.edit().setConnectable(false).apply();
            mPivotBeacon = null;
        }
    }

    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
        Util.log(TAG, "onCharacteristicReadRequest() called with: device = [" + device + "], requestId = [" + requestId + "], offset = [" + offset + "], characteristic = [" + characteristic.getUuid() + "]");

        mEddystoneGattService.readCharacteristic(mGattServer, device, requestId, offset, characteristic);
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattCharacteristic characteristic,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
        super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
        Util.log(TAG, "onCharacteristicWriteRequest() called with: device = [" + device + "], requestId = [" + requestId + "], characteristic = [" + characteristic.getUuid() + "], preparedWrite = [" + preparedWrite + "], responseNeeded = [" + responseNeeded + "], offset = [" + offset + "], value = [" + value + "]");

        int status = mEddystoneGattService.writeCharacteristic(device, characteristic, value);

        if (responseNeeded) {
            mGattServer.sendResponse(device, requestId, status, offset,
                    status == BluetoothGatt.GATT_SUCCESS ? characteristic.getValue() : null);
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
        super.onConnectionStateChange(device, status, newState);
        Util.log(TAG, "onConnectionStateChange() called with: device = [" + device + "], status = [" + status + "], newState = [" + newState + "]");

        if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            log(device + " has disconnected");
            if (device.equals(mEddystoneGattService.getConnectedOwner())) {
                log("Owner disconnected, stopping GATT server");
                mEddystoneGattService.onOwnerDisconnected();
                mListener.onGattFinished(mEddystoneConfigurator.getConfiguredBeacon());
                close();
            }
        }
        else if (newState == BluetoothGatt.STATE_CONNECTED) {
            log(device + " has connected");
            if (mEddystoneGattService.getConnectedOwner() != null) {
                // don't allow a second client to connect at the same time
                log(device + " tried to connect, but owner is active. Disconnecting.");
                mGattServer.cancelConnection(device);
            }
        }
    }

    @Override
    public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
        super.onExecuteWrite(device, requestId, execute);

        log(String.format("%s Request %d: executeWrite(%s) is not expected!",
                device, requestId, execute));
//        mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, new byte[0]);
    }

    public void keepSingleConnected(BluetoothDevice allowedDevice) {
        for (BluetoothDevice device : mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT)) {
            if (!allowedDevice.equals(device)) {
                log(String.format("Disconnecting %s", device));
                mGattServer.cancelConnection(device);
            }
        }
    }

    void log(String message) {
        if (null != mLogger) {
            mLogger.log(TAG, message);
        }
        else Util.log(TAG, message);
    }
}