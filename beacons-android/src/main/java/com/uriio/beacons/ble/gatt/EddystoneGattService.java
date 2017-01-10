package com.uriio.beacons.ble.gatt;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.os.Build;
import android.support.annotation.RequiresApi;

import com.uriio.beacons.Util;
import com.uriio.beacons.eid.EIDUtils;

import org.uribeacon.beacon.UriBeacon;
import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Eddystone GATT Service
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class EddystoneGattService {
    public static final UUID UUID_EDDYSTONE_GATT_SERVICE = UUID.fromString("a3c87500-8ed3-4bdf-8a39-a01bebede295");

    private static final UUID UUID_CAPABILITIES_CHARACTERISTIC = UUID.fromString("a3c87501-8ed3-4bdf-8a39-a01bebede295");
    private static final UUID UUID_ACTIVE_SLOT_CHARACTERISTIC = UUID.fromString("a3c87502-8ed3-4bdf-8a39-a01bebede295");
    private static final UUID UUID_ADVERTISE_INTERVAL_CHARACTERISTIC = UUID.fromString("a3c87503-8ed3-4bdf-8a39-a01bebede295");
    private static final UUID UUID_RADIO_TX_POWER_CHARACTERISTIC = UUID.fromString("a3c87504-8ed3-4bdf-8a39-a01bebede295");
    private static final UUID UUID_ADVERTISED_TX_POWER_CHARACTERISTIC = UUID.fromString("a3c87505-8ed3-4bdf-8a39-a01bebede295");
    private static final UUID UUID_LOCK_STATE_CHARACTERISTIC = UUID.fromString("a3c87506-8ed3-4bdf-8a39-a01bebede295");
    private static final UUID UUID_UNLOCK_CHARACTERISTIC = UUID.fromString("a3c87507-8ed3-4bdf-8a39-a01bebede295");
    private static final UUID UUID_PUBLIC_ECDH_KEY_CHARACTERISTIC = UUID.fromString("a3c87508-8ed3-4bdf-8a39-a01bebede295");
    private static final UUID UUID_EID_IDENTITY_KEY_CHARACTERISTIC = UUID.fromString("a3c87509-8ed3-4bdf-8a39-a01bebede295");
    private static final UUID UUID_ADV_SLOT_DATA_CHARACTERISTIC = UUID.fromString("a3c8750A-8ed3-4bdf-8a39-a01bebede295");
    private static final UUID UUID_FACTORY_RESET_CHARACTERISTIC = UUID.fromString("a3c8750B-8ed3-4bdf-8a39-a01bebede295");
    private static final UUID UUID_REMAIN_CONNECTABLE_CHARACTERISTIC = UUID.fromString("a3c8750C-8ed3-4bdf-8a39-a01bebede295");

    private static final int VARIABLE_ADV_SUPPORTED = 0x01;
    private static final int VARIABLE_TX_POWER_SUPPORTED = 0x02;

    private static final byte LOCK_STATE_LOCKED = 0x00;
    private static final byte LOCK_STATE_UNLOCKED = 0x01;

    private final BluetoothGattService mService;
    private final BluetoothGattCharacteristic mCapabilitiesCharacteristic;

    private final BluetoothGattCharacteristic mActiveSlotCharacteristic;
    private final BluetoothGattCharacteristic mAdvertiseIntervalCharacteristic;
    private final BluetoothGattCharacteristic mRadioTxPowerCharacteristic;
    private final BluetoothGattCharacteristic mAdvertisedTxPowerCharacteristic;
    private final BluetoothGattCharacteristic mLockStateCharacteristic;
    private final BluetoothGattCharacteristic mUnlockCharacteristic;
    private final BluetoothGattCharacteristic mPublicEcdhKeyCharacteristic;
    private final BluetoothGattCharacteristic mEidIdentityKeyCharacteristic;
    private final BluetoothGattCharacteristic mAdvSlotDataCharacteristic;
    private final BluetoothGattCharacteristic mFactoryResetCharacteristic;
    private final BluetoothGattCharacteristic mRemainConnectableCharacteristic;

    private byte[] mLockKey;
    private EddystoneGattServer mGattServer;
    private EddystoneGattConfigCallback mConfigCallback;
    private Curve25519KeyPair mEidKeyPair;
    private BluetoothDevice mOwnerDevice = null;

    public EddystoneGattService(EddystoneGattServer eddystoneGattServer,
                                EddystoneGattConfigCallback configCallback) {
        mGattServer = eddystoneGattServer;
        mConfigCallback = configCallback;

        mService = new BluetoothGattService(UUID_EDDYSTONE_GATT_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        mLockKey = mConfigCallback.getLockKey();

        mCapabilitiesCharacteristic = new BluetoothGattCharacteristic(
                UUID_CAPABILITIES_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);

        byte version = 0x00;

        /**
         * We need two slots to support EID registration.
         * Beacon Tools tries to use slot 1 for EID registration, even if we specify we only have 1 slot.
         * The second slot is for the configurable beacon, which may be started, re-started, or stopped during config
         * Note that restarting the master BLE advertiser (or any other one) kills the GATT connection (BT address changes)
         * fortunately we will fake the second slot so we don;t require its BLE running during config.
        */
        byte maxSupportedTotalSlots = 2;
        byte maxSupportedEidSlots = 1;
        byte capabilities = VARIABLE_ADV_SUPPORTED | VARIABLE_TX_POWER_SUPPORTED;
        short supportedFrameTypes = 0x01 | 0x02 | 0x08;
        byte[] supportedTxPowerLevels = mConfigCallback.getSupportedRadioTxPowers();

        ByteBuffer byteBuffer = ByteBuffer.allocate(6 + supportedTxPowerLevels.length);
        byteBuffer.put(new byte[]{
                version,
                maxSupportedTotalSlots,
                maxSupportedEidSlots,
                capabilities,
                (byte) (supportedFrameTypes >>> 8),
                (byte) supportedFrameTypes
        });
        byteBuffer.put(supportedTxPowerLevels);

        mCapabilitiesCharacteristic.setValue(byteBuffer.array());

        mService.addCharacteristic(mCapabilitiesCharacteristic);

        mActiveSlotCharacteristic = new BluetoothGattCharacteristic(UUID_ACTIVE_SLOT_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        mActiveSlotCharacteristic.setValue(0, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
        mService.addCharacteristic(mActiveSlotCharacteristic);

        mAdvertiseIntervalCharacteristic = new BluetoothGattCharacteristic(UUID_ADVERTISE_INTERVAL_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        mAdvertiseIntervalCharacteristic.setValue(toBigEndian(mConfigCallback.getAdvertiseInterval()), BluetoothGattCharacteristic.FORMAT_UINT16, 0);
        mService.addCharacteristic(mAdvertiseIntervalCharacteristic);

        mRadioTxPowerCharacteristic = new BluetoothGattCharacteristic(UUID_RADIO_TX_POWER_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        mRadioTxPowerCharacteristic.setValue(mConfigCallback.getRadioTxPower(), BluetoothGattCharacteristic.FORMAT_SINT8, 0);
        mService.addCharacteristic(mRadioTxPowerCharacteristic);

        mAdvertisedTxPowerCharacteristic = new BluetoothGattCharacteristic(UUID_ADVERTISED_TX_POWER_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        mAdvertisedTxPowerCharacteristic.setValue(mConfigCallback.getAdvertisedTxPower(), BluetoothGattCharacteristic.FORMAT_SINT8, 0);
        mService.addCharacteristic(mAdvertisedTxPowerCharacteristic);

        mLockStateCharacteristic = new BluetoothGattCharacteristic(UUID_LOCK_STATE_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        setLockState(LOCK_STATE_LOCKED);
        mService.addCharacteristic(mLockStateCharacteristic);

        mUnlockCharacteristic = new BluetoothGattCharacteristic(UUID_UNLOCK_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        mService.addCharacteristic(mUnlockCharacteristic);

        mPublicEcdhKeyCharacteristic = new BluetoothGattCharacteristic(UUID_PUBLIC_ECDH_KEY_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        mService.addCharacteristic(mPublicEcdhKeyCharacteristic);

        mEidIdentityKeyCharacteristic = new BluetoothGattCharacteristic(UUID_EID_IDENTITY_KEY_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        mService.addCharacteristic(mEidIdentityKeyCharacteristic);

        mAdvSlotDataCharacteristic = new BluetoothGattCharacteristic(UUID_ADV_SLOT_DATA_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        mService.addCharacteristic(mAdvSlotDataCharacteristic);

        mFactoryResetCharacteristic = new BluetoothGattCharacteristic(UUID_FACTORY_RESET_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        mService.addCharacteristic(mFactoryResetCharacteristic);

        mRemainConnectableCharacteristic = new BluetoothGattCharacteristic(UUID_REMAIN_CONNECTABLE_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        mRemainConnectableCharacteristic.setValue(1, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
        mService.addCharacteristic(mRemainConnectableCharacteristic);
    }

    private int toBigEndian(int val) {
        return val >>> 8 | (val & 0xff) << 8;
    }

    private int unpackShort(byte[] val) {
        // prevent automatic byte to int expansion, for LSB (messes up the sign bit)
        return val[1] & 0xff | val[0] << 8;
    }

    public BluetoothGattService getService() {
        return mService;
    }

    public void readCharacteristic(BluetoothGattServer gattServer, BluetoothDevice device,
                                   int requestId, int offset,
                                   BluetoothGattCharacteristic characteristic) {
//        UUID uuid = characteristic.getUuid();
        int status =  BluetoothGatt.GATT_SUCCESS;

        if (isLocked()) {
            if (characteristic == mUnlockCharacteristic) {
                log("Generating secure unlock challenge");
                characteristic.setValue(new byte[16]);
                new SecureRandom().nextBytes(characteristic.getValue());
            } else {
                if (characteristic != mLockStateCharacteristic) {
                    status = BluetoothGatt.GATT_READ_NOT_PERMITTED;
                }
            }
        }
        else if (characteristic == mUnlockCharacteristic) {
            status = BluetoothGatt.GATT_READ_NOT_PERMITTED;
        } else if (characteristic == mPublicEcdhKeyCharacteristic) {
            log("ECDH Public Key was requested");
            if (0 == offset) {
                characteristic.setValue(null == mEidKeyPair ? new byte[0] : mEidKeyPair.getPublicKey());
            }
        } else if (characteristic == mAdvSlotDataCharacteristic) {
            log("Advertisement slot data requested");
            characteristic.setValue(mConfigCallback.getAdvertisedData());
        } else if (characteristic  == mEidIdentityKeyCharacteristic) {
            log("Identity Key was requested");
            byte[] identityKey = mConfigCallback.getEidIdentityKey();
            if (null == identityKey) {
                status = BluetoothGatt.GATT_FAILURE;
            }
            else {
                characteristic.setValue(aes_transform(true, identityKey, 0, 16));
            }
        }

        gattServer.sendResponse(device, requestId, status, offset,
                status == BluetoothGatt.GATT_SUCCESS ? Arrays.copyOfRange(characteristic.getValue(), offset, characteristic.getValue().length) : null);
    }

    public boolean isLocked() {
        return mLockStateCharacteristic.getValue()[0] == LOCK_STATE_LOCKED;
    }

    public BluetoothDevice getConnectedOwner() {
        return mOwnerDevice;
    }

    public int writeCharacteristic(BluetoothDevice device, BluetoothGattCharacteristic characteristic, byte[] value) {
//        UUID uuid = characteristic.getUuid();
        if (isLocked()) {
            if (characteristic == mUnlockCharacteristic) {
                if (value.length == 16) {
                    byte[] token = aes_transform(true, characteristic.getValue(), 0, 16);
                    if (Arrays.equals(token, value)) {
                        log(String.format("Unlocked by %s", device));

                        mOwnerDevice = device;
                        mGattServer.disconnectAll(device);
                        characteristic.setValue((byte[]) null);
                        mLockStateCharacteristic.setValue(new byte[] { LOCK_STATE_UNLOCKED});

                        return BluetoothGatt.GATT_SUCCESS;
                    }
                    else log("Unlock failed!");
                }
                else log(String.format("Unlock: expected 16 bytes, got %d", value.length));
            }

            log("Beacon locked - write request denied");
            return BluetoothGatt.GATT_WRITE_NOT_PERMITTED;
        }

        if (characteristic == mLockStateCharacteristic) {
            if (LOCK_STATE_LOCKED == value[0] && 17 == value.length) {
                mLockKey = aes_transform(false, value, 1, 16);
                mConfigCallback.setLockKey(mLockKey);
                log("Lock key changed");
            }
            characteristic.setValue(new byte[]{value[0]});
        } else if (characteristic == mActiveSlotCharacteristic) {
            log("Request to change active slot to " + value[0]);
            if (value[0] != 1) {
                // Beacon Tools tries to change the active slot to 1
                return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
            }
        } else if (characteristic == mRadioTxPowerCharacteristic) {
            if (value.length == 1) {
                int txPower = mConfigCallback.setRadioTxPower(value[0]);
                characteristic.setValue(txPower, BluetoothGattCharacteristic.FORMAT_SINT8, 0);

                // if Radio TX has changed, then Advertised TX has also changed
                mAdvertisedTxPowerCharacteristic.setValue(mConfigCallback.getAdvertisedTxPower(), BluetoothGattCharacteristic.FORMAT_SINT8, 0);
                log(String.format("Radio TX Power %d was requested. Actual value is now %d",
                        value[0], txPower));
            }
            else {
                log("Invalid Radio TX power value size: " + value.length);
            }
        } else if (characteristic == mAdvertiseIntervalCharacteristic) {
            if (value.length == 2) {
                int wantedAdvertiseInterval = unpackShort(value);
                int actualAdvertiseInterval = mConfigCallback.setAdvertiseInterval(wantedAdvertiseInterval);
                characteristic.setValue(toBigEndian(actualAdvertiseInterval), BluetoothGattCharacteristic.FORMAT_UINT16, 0);
                log(String.format("Advertise Interval %d was requested. Actual value is now %d",
                        wantedAdvertiseInterval, actualAdvertiseInterval));
            }
            else {
                log("Invalid Advertise Interval value size: " + value.length);
            }
        } else if (characteristic == mAdvSlotDataCharacteristic) {
            handleWriteAdvertiseSlotData(value);
        } else if (characteristic == mFactoryResetCharacteristic) {
            if (0x0B == value[0]) {
                factoryReset();
            }
        }

        return BluetoothGatt.GATT_SUCCESS;
    }

    private int handleWriteAdvertiseSlotData(byte[] value) {
        switch (value[0]) {     // the frame type
            case 0x00: // UID
                if (value.length == 1) {
                    // TODO: 5/25/2016 - check if array is empty, according to spec
                    log("Clearing beacon advertisement format");
                    mConfigCallback.stopAdvertise();
                }
                else {
                    log("Setting UID frame " + Util.binToHex(value, 1, 16, ' '));
                    mConfigCallback.advertiseUID(Arrays.copyOfRange(value, 1, 17));
                }
                break;
            case 0x10: // URL
                String url = UriBeacon.decodeUri(Arrays.copyOfRange(value, 1, value.length), 0);
                log("Setting URL frame: " + url);
                mConfigCallback.advertiseURL(url);
                break;
            case 0x20: // TLM
                log("TLM format is not supported");
                break;
            case 0x30: // EID
                if (value.length == 34) {
                    byte[] serverPublicKey = Arrays.copyOfRange(value, 1, 33);
                    byte rotationExponent = value[33];
                    log(String.format("Computing Identity Key with rotation exponent %d and server PublicKey %s",
                            rotationExponent, Util.binToHex(serverPublicKey)));

                    log("Generating ECDH Private Key");
                    mEidKeyPair = Curve25519.getInstance(Curve25519.BEST).generateKeyPair();

                    byte[] sharedSecret = EIDUtils.computeSharedSecret(serverPublicKey, mEidKeyPair.getPrivateKey());

                    byte[] identityKey;
                    try {
                        identityKey = EIDUtils.computeIdentityKey(sharedSecret, serverPublicKey, mEidKeyPair.getPublicKey());
                    } catch (InvalidKeyException e) {
                        return BluetoothGatt.GATT_FAILURE;
                    } catch (NoSuchAlgorithmException e) {
                        return BluetoothGatt.GATT_FAILURE;
                    }

//                        Util.log(TAG, "IK: " + Util.binToHex(identityKey));
                    mConfigCallback.advertiseEID(identityKey, rotationExponent);
                }
                else if (value.length == 18) {
                    log("WARNING!!! Received direct IdentityKey. Rotation exponent is " + value[17]);
                    byte[] identityKey = aes_transform(false, value, 1, 16);
                    mConfigCallback.advertiseEID(identityKey, value[17]);
                }
                break;
        }

        return BluetoothGatt.GATT_SUCCESS;
    }

    private void factoryReset() {
        mConfigCallback.stopAdvertise();
    }

    private byte[] aes_transform(boolean encrypt, byte[] src, int offset, int len) {
        String transformation = "AES/ECB/NoPadding";
        try {
            Cipher aes = Cipher.getInstance(transformation);
            aes.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(mLockKey, 0, 16, "AES"));
            return aes.doFinal(src, offset, len);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    public void onOwnerDisconnected() {
        mOwnerDevice = null;
        setLockState(LOCK_STATE_LOCKED);
    }

    private void setLockState(byte state) {
        mLockStateCharacteristic.setValue(new byte[] { state });
    }

    private void log(String message) {
        mGattServer.log(message);
    }
}