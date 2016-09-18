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
import android.support.annotation.NonNull;

import com.uriio.beacons.Beacons;
import com.uriio.beacons.Loggable;
import com.uriio.beacons.Util;
import com.uriio.beacons.model.EddystoneBase;
import com.uriio.beacons.model.EddystoneUID;
import com.uriio.beacons.model.EddystoneURL;

import java.util.List;

import static android.content.Context.BLUETOOTH_SERVICE;

/**
 * Manages an Eddystone-GATT config service.
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
    private EddystoneBase mBeacon = null;
    private Loggable mLogger;
    private BluetoothManager mBluetoothManager;
    private boolean mStarted = false;
    private boolean mIsBeaconNew = false;

    public EddystoneGattServer(Listener listener) {
        mListener = listener;
    }

    public void setLogger(Loggable loggable) {
        mLogger = loggable;
    }

    /**
     * Atempts to add this GATT service to the device's GATT server.
     * @param context   A valid context, used to retrieve the Bluetooth service.
     * @param beacon    The initial beacon that will become connectable and be presented as configured currently.
     * @return True if the GATT service was successfully added to the device's Bluetooth GATT server.
     * Only one GATT service can run on the same device at the same time.
     */
    public boolean start(@NonNull Context context, @NonNull EddystoneBase beacon) {
        if (mStarted) return false;

        mBeacon = beacon;

        mBluetoothManager = (BluetoothManager) context.getSystemService(BLUETOOTH_SERVICE);
        mGattServer = mBluetoothManager.openGattServer(context, this);
        if (null == mGattServer) {
            log("Failed to open GATT server");
            return false;
        }

        List<BluetoothGattService> gattServices = mGattServer.getServices();
        for (BluetoothGattService service : gattServices) {
            if (service.getUuid() == EddystoneGattService.UUID_EDDYSTONE_GATT_SERVICE) {
                log("Another Eddystone-GATT service is already being served by this device");
                close();
                return false;
            }
        }

        mEddystoneConfigurator = new EddystoneGattConfigurator(beacon);

        mEddystoneGattService = new EddystoneGattService(this, mEddystoneConfigurator);
        if (!mGattServer.addService(mEddystoneGattService.getService())) {
            log("Eddystone-GATT service registration failed");
            close();
            return false;
        }

        // advertise beacon as connectable
        log("Setting beacon connectable");
        beacon.edit().setConnectable(true).apply();

        return true;
    }

    /**
     * Starts the GATT service with a blank Eddystone-UID as the initial configured beacon
     */
    public boolean start(Context context) {
        if (mStarted) return false;

        mIsBeaconNew = true;
        return start(context, Beacons.add(new EddystoneUID()));
    }

    /**
     * @param url    An URL to use as the initial Eddystone-URL configurable / connectable beacon
     */
    public boolean start(Context context, String url) {
        if (mStarted) return false;

        mIsBeaconNew = true;
        return start(context, Beacons.add(new EddystoneURL(url)));
    }

    /**
     * @return The currently configured beacon. May be null if an authenticated user reset it.
     */
    public EddystoneBase getBeacon() {
        return mBeacon;
    }

    public void close() {
        if (null != mGattServer) {
            mGattServer.close();
            mGattServer = null;
        }

        if (null != mBeacon) {
            EddystoneBase configuredBeacon = mEddystoneConfigurator.getConfiguredBeacon();
            if (null != configuredBeacon) {
                if (configuredBeacon != mBeacon) {
                    // configured beacon is not the original beacon, add it and remove original
                    Beacons.add(configuredBeacon);
                    Beacons.delete(mBeacon);
                }
            }

            if (mIsBeaconNew) {
                if (null == configuredBeacon) {
                    // remove temporary beacon used for GATT connectable advertising
                    Beacons.delete(mBeacon);
                }
            } else {
                log("Setting beacon un-connectable");
                mBeacon.edit().setConnectable(false).apply();
            }


            if (null != mListener) {
                mListener.onGattFinished(null == mEddystoneConfigurator ? null : configuredBeacon);
            }

            mBeacon = null;
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

    void disconnectAll(BluetoothDevice allowedDevice) {
        for (BluetoothDevice device : mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT)) {
            if (!device.equals(allowedDevice)) {
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