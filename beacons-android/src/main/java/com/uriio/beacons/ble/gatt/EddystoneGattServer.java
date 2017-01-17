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
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;

import com.uriio.beacons.Beacons;
import com.uriio.beacons.Loggable;
import com.uriio.beacons.Util;
import com.uriio.beacons.model.EddystoneBase;
import com.uriio.beacons.model.EddystoneURL;

import java.util.List;

/**
 * Manages an Eddystone-GATT config service.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
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

    public EddystoneGattServer(Listener listener) {
        mListener = listener;
    }

    public void setLogger(Loggable loggable) {
        mLogger = loggable;
    }

    /**
     * Atempts to add this GATT service to the device's GATT server.
     * @param beacon    The initial beacon that will become connectable and be presented as configured currently.
     * @return True if the GATT service was successfully added to the device's Bluetooth GATT server.
     * Only one GATT service can run on the same device at the same time.
     */
    public boolean start(@NonNull EddystoneBase beacon) {
        if (mStarted) return false;

        mBeacon = beacon;

        Context context = Beacons.getContext();

        mBluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
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
        if (!beacon.isConnectable()) {
            log("Setting beacon connectable");
            beacon.edit().setConnectable(true).apply();
        }

        // finally, make sure the provided beacon is started
        return beacon.start();
    }

    /**
     * Equivalent to start("http://cf.physical-web.org")
     */
    public boolean start() {
        return start("http://cf.physical-web.org");
    }

    /**
     * Starts the GATT config service using an Eddystone-URL as the initial configurable beacon.
     * @param url    An URL to use as the initial Eddystone-URL configurable / connectable beacon
     */
    public boolean start(String url) {
        return !mStarted && start(new EddystoneURL(url));
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

        // if BT was off when we tried to start, the configurator is null
        if (null != mBeacon && null != mEddystoneConfigurator) {
            EddystoneBase configuredBeacon = mEddystoneConfigurator.getConfiguredBeacon();

            if (mBeacon == configuredBeacon) {
                log("Setting beacon un-connectable");
                configuredBeacon.edit().setConnectable(false).apply();
            }
            else {
                // no beacon configured, or the configured beacon is not the initial one

                // stop temporary or provided beacon (and delete it if it was also saved)
                mBeacon.delete();

                if (null != configuredBeacon) {
                    // save, if the original beacon was saved
                    if (mBeacon.getSavedId() > 0) {
                        configuredBeacon.save(true);
                    }
                    else configuredBeacon.start();
                }
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

        mEddystoneGattService.readCharacteristic(mGattServer, device, requestId, offset, characteristic);
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattCharacteristic characteristic,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
        super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);

        int status = mEddystoneGattService.writeCharacteristic(device, characteristic, value);

        if (responseNeeded) {
            mGattServer.sendResponse(device, requestId, status, offset,
                    status == BluetoothGatt.GATT_SUCCESS ? characteristic.getValue() : null);
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
        super.onConnectionStateChange(device, status, newState);

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