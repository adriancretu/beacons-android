package com.uriio.beacons.model;

import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

import com.uriio.beacons.BleService;
import com.uriio.beacons.Storage;
import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.ble.EddystoneAdvertiser;

import java.nio.ByteBuffer;

/**
 * Wraps an Eddystone Telemetry beacon.
 */
public class EddystoneTLM extends EddystoneBase {
    private static final int MIN_REFRESH_INTERVAL = 5000;
    private static final int MAX_REFRESH_INTERVAL = 300000;

    private long mRefreshInterval;
    private long mScheduledRTC = 0;

    private short mBatteryVoltage = 0;
    private int mPowerOnTime = 0;
    private int mEstimatedPDUCount = 0;

    /** Battery temperature, in tenths of Celsius */
    private int mBatteryTemperature = 0;

    public EddystoneTLM(long refreshInterval, byte[] lockKey, @Advertiser.Mode int mode, @Advertiser.Power int txPowerLevel, String name) {
        super(lockKey, mode, txPowerLevel, name);
        init(refreshInterval);
    }

    public EddystoneTLM(long refreshInterval, @Advertiser.Mode int mode, @Advertiser.Power int txPowerLevel, String name) {
        this(refreshInterval, null, mode, txPowerLevel, name);
    }

    public EddystoneTLM(long refreshInterval, @Advertiser.Mode int mode, @Advertiser.Power int txPowerLevel) {
        this(refreshInterval, mode, txPowerLevel, null);
    }

    public EddystoneTLM(long refreshInterval, byte[] lockKey, String name) {
        super(lockKey, name);
        init(refreshInterval);
    }

    public EddystoneTLM(long refreshInterval, String name) {
        this(refreshInterval, null, name);
    }

    public EddystoneTLM(long refreshInterval, byte[] lockKey) {
        this(refreshInterval, lockKey, null);
    }

    public EddystoneTLM(long refreshInterval) {
        this(refreshInterval, null, null);
    }

    private void init(long refreshInterval) {
        mRefreshInterval = Math.max(MIN_REFRESH_INTERVAL, Math.min(MAX_REFRESH_INTERVAL, refreshInterval));
    }

    @Override
    public int getKind() {
        return Storage.KIND_EDDYSTONE_TLM;
    }

    @Override
    public long getScheduledRefreshElapsedTime() {
        return mScheduledRTC;
    }

    @Override
    public EddystoneBase cloneBeacon() {
        return new EddystoneTLM(mRefreshInterval, getLockKey(), getAdvertiseMode(), getTxPowerLevel(), getName());
    }

    @Override
    public void onAdvertiseEnabled(BleService service) {
        mScheduledRTC = SystemClock.elapsedRealtime() + mRefreshInterval;
        super.onAdvertiseEnabled(service);
    }

    @Override
    public Advertiser createAdvertiser(BleService service) {
        byte[] data = new byte[12];

        if (null != service) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent stickyIntent = service.registerReceiver(null, intentFilter);
            if (null != stickyIntent) {
                mBatteryVoltage = (short) stickyIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                buffer.putShort(mBatteryVoltage);

                mBatteryTemperature = stickyIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);

                // (int * 10) to fixed point 8.8
                buffer.putShort((short) (mBatteryTemperature / 10 << 8 | mBatteryTemperature % 10 * 256 / 10));

                mEstimatedPDUCount = (int) service.updateEstimatedPDUCount();
                buffer.putInt(mEstimatedPDUCount);

                mPowerOnTime = (int) service.getPowerOnTime();
                buffer.putInt(mPowerOnTime / 100);
            }
        }

        return new EddystoneAdvertiser(this, EddystoneAdvertiser.FRAME_TLM, data, 0, data.length);
    }

    public long getRefreshInterval() {
        return mRefreshInterval;
    }

    public short getBatteryVoltage() {
        return mBatteryVoltage;
    }

    public int getBatteryTemperature() {
        return mBatteryTemperature;
    }

    public int getEstimatedPDUCount() {
        return mEstimatedPDUCount;
    }

    public int getPowerOnTime() {
        return mPowerOnTime;
    }
}
