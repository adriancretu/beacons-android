package com.uriio.beacons.model;

import com.uriio.beacons.BleService;
import com.uriio.beacons.Storage;
import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.ble.EddystoneAdvertiser;

import java.io.ByteArrayOutputStream;

/**
 * Created on 5/21/2016.
 */
public class EddystoneURL extends EddystoneBase {
    private static final String[] SCHEMES = {"http://www.", "https://www.", "http://", "https://"};
    private static final String[] EXPANSIONS = {
            ".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/",
            ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov",
    };

    private String mURL;

    public static String decode(byte[] data, int offset) {
        if (null == data || offset >= data.length) {
            return "";
        }

        byte schemeByte = data[offset++];
        if (schemeByte < 0 || schemeByte > 3) {
            return null;
        }

        StringBuilder builder = new StringBuilder(SCHEMES[schemeByte]);
        while (offset < data.length) {
            byte val = data[offset++];
            if (val < 0 || 0x7f == val) return null;
            if (val > 32) {
                builder.append(val);
            } else {
                if (val >= EXPANSIONS.length) return null;
                builder.append(EXPANSIONS[val]);
            }
        }

        return builder.toString();
    }

    public static byte[] encode(String url) {
        if (null == url) {
            return null;
        }

        if (url.length() == 0) {
            return new byte[0];
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(18);

        int pos = 0;
        for (int schemeCode = 0; schemeCode < SCHEMES.length; ++schemeCode) {
            String scheme = SCHEMES[schemeCode];
            if (url.startsWith(scheme)) {
                baos.write(schemeCode);
                pos = scheme.length();
                break;
            }
        }

        if (pos <= 0) return null;

        while (pos < url.length()) {
            // longer expansions start from index 0, don't use a reversed loop
            boolean foundExpansion = false;
            for (int expansionCode = 0; expansionCode < EXPANSIONS.length; ++expansionCode) {
                String expansion = EXPANSIONS[expansionCode];
                if (url.startsWith(expansion, pos)) {
                    baos.write(expansionCode);
                    pos += expansion.length();
                    foundExpansion = true;
                    // need to break instead of looking for NEXT expansion, so we start from first
                    break;
                }
            }
            if (!foundExpansion) {
                char charAt = url.charAt(pos++);
                if (charAt <= 32 || charAt >= 127) {
                    return null;
                }
                baos.write(charAt);
            }
        }

        return baos.toByteArray();
    }

    public EddystoneURL(String url, byte[] lockKey, @Advertiser.Mode int mode,
                        @Advertiser.Power int txPowerLevel, String name) {
        super(lockKey, mode, txPowerLevel, name);
        mURL = url;
    }

    public EddystoneURL(String url, @Advertiser.Mode int mode,
                        @Advertiser.Power int txPowerLevel, String name) {
        this(url, null, mode, txPowerLevel, name);
    }

    public EddystoneURL(String url, @Advertiser.Mode int mode,
                        @Advertiser.Power int txPowerLevel) {
        this(url, null, mode, txPowerLevel, null);
    }

    public EddystoneURL(String url, byte[] lockKey, String name) {
        super(lockKey, name);
        mURL = url;
    }

    public EddystoneURL(String url, String name) {
        this(url, null, name);
    }

    public EddystoneURL(String url, byte[] lockKey) {
        this(url, lockKey, null);
    }

    public EddystoneURL(String url) {
        this(url, null, null);
    }

    @Override
    public int getKind() {
        return Storage.KIND_EDDYSTONE_URL;
    }

    @Override
    public EddystoneBase cloneBeacon() {
        return new EddystoneURL(getURL(), getLockKey(), getAdvertiseMode(), getTxPowerLevel(), getName());
    }

    @Override
    public Advertiser createAdvertiser(BleService service) {
        // a null URL or a empty URL is allowed
        byte[] data = null == mURL ? new byte[0] : encode(mURL);

        if (null == data || data.length > 18) {
            // payload can't be advertised (too large, invalid scheme or other fatal error)
            return null;
        }

        return new EddystoneAdvertiser(this, EddystoneAdvertiser.FRAME_URL, data, 0, data.length);
    }

    @Override
    public CharSequence getNotificationSubject() {
        if (null == mURL) return "<no URL>";
        else if (mURL.length() == 0) return "<empty URL>";

        return mURL;
    }

    @Override
    public EddystoneURLEditor edit() {
        return new EddystoneURLEditor();
    }

    public String getURL() {
        return mURL;
    }

    public class EddystoneURLEditor extends EddystoneEditor {
        public EddystoneURLEditor setUrl(String url) {
            if (null == url || null == mURL || !url.equals(mURL)) {
                mURL = url;
                mRestartBeacon = true;
            }
            return this;
        }
    }
}
