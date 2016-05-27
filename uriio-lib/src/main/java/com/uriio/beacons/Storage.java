package com.uriio.beacons;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Base64;

import com.uriio.beacons.model.Beacon;
import com.uriio.beacons.model.EddystoneBase;
import com.uriio.beacons.model.EddystoneEID;
import com.uriio.beacons.model.EddystoneUID;
import com.uriio.beacons.model.EddystoneURL;
import com.uriio.beacons.model.EphemeralURL;
import com.uriio.beacons.model.iBeacon;

import java.nio.ByteBuffer;

/**
 * Database manager.
 */
public class Storage extends SQLiteOpenHelper {
    public static final int STATE_ENABLED  = 0;
    public static final int STATE_PAUSED   = 1;
    public static final int STATE_STOPPED  = 2;

    public static final int KIND_URIIO     = 1;
    public static final int KIND_EDDYSTONE = 2;
    public static final int KIND_IBEACON   = 3;

    private static final String ITEMS_TABLE     = "b";
    private static final String EDDYSTONE_TABLE = "url";
    private static final String IBEACONS_TABLE  = "ib";
    private static final String URIIO_TABLE     = "uriio";

    private static final int DATABASE_SCHEMA_VERSION = 6;

    private static Storage _instance;

    /** lazy SQLite statements **/
    private SQLiteStatement mInsertItemStmt = null;
    private SQLiteStatement mInsertUriioItemStmt = null;
    private SQLiteStatement mInsertEddystoneItemStmt = null;
    private SQLiteStatement mInsertAppleBeaconItemStmt = null;
    private SQLiteStatement mUpdateShortUrlStmt = null;
    private SQLiteStatement mUpdateStateStmt = null;

    private Storage(Context context, String dbName) {
        super(context, dbName, null, DATABASE_SCHEMA_VERSION);
    }

    public static void init(Context context, String dbName) {
        _instance = new Storage(context, dbName);
    }

    public static Storage getInstance() {
        return _instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.beginTransaction();

        // tables
        db.execSQL("CREATE TABLE " + ITEMS_TABLE + " (kind INTEGER, created INTEGER, advMode INTEGER, txLevel INTEGER, state INTEGER DEFAULT 1, flags INTEGER, name TEXT)");

        // URI I/O items
        // 'key' -> url token
        // 'privateKey' is in base64 url-safe
        db.execSQL("CREATE TABLE " + URIIO_TABLE + " (urlId TEXT, key TEXT, shortUrl TEXT, expires INTEGER, ttl INTEGER, longUrl TEXT, privateKey TEXT)");

        // url - either the full URL or the base64 raw UID payload [Namespace]Instance
        // domain - what domain to use to generate the Namespace; if null, Namespace is already in the 'url' field
        db.execSQL("CREATE TABLE " + EDDYSTONE_TABLE + " (url TEXT, domain TEXT, lockKey BLOB)");

        db.execSQL("CREATE TABLE " + IBEACONS_TABLE + " (uuid TEXT, maj INTEGER, min INTEGER)");

        // indexes
//        db.execSQL("CREATE UNIQUE INDEX ia ON " + URIIO_TABLE + "(urlId) ");
//        db.execSQL("CREATE INDEX ib ON " + IBEACONS_TABLE + "(maj, min) ");

        db.setTransactionSuccessful();
        db.endTransaction();
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // added the 'name' column to items
            db.execSQL("ALTER TABLE " + ITEMS_TABLE + " ADD COLUMN name TEXT");
        }

        if (oldVersion < 3) {
            // added the 'domain' column to Eddystone items
            db.execSQL("ALTER TABLE " + EDDYSTONE_TABLE + " ADD COLUMN domain TEXT");
        }

        if (oldVersion < 4) {
            // before this version, items were only deleted from the main items table
            db.execSQL("DELETE FROM " + EDDYSTONE_TABLE + " WHERE rowid NOT IN (SELECT rowid FROM " + ITEMS_TABLE + ")");
            db.execSQL("DELETE FROM " + IBEACONS_TABLE + " WHERE rowid NOT IN (SELECT rowid FROM " + ITEMS_TABLE + ")");
            // uriio items not public yet, so nothing to cleanup
        }

        if (oldVersion < 5) {
            // added 'privateKey' column to uriio table (no entries should exist though)
            db.execSQL("ALTER TABLE " + URIIO_TABLE + " ADD COLUMN privateKey TEXT");
        }

        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE " + EDDYSTONE_TABLE + " ADD COLUMN lockKey BLOB");
        }
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        super.onDowngrade(db, oldVersion, newVersion);
    }

    @Override
    public synchronized void close() {
        if (null != mInsertItemStmt) {
            mInsertItemStmt.close();
            mInsertItemStmt = null;
        }

        if (null != mInsertUriioItemStmt) {
            mInsertUriioItemStmt.close();
            mInsertUriioItemStmt = null;
        }

        if (null != mInsertAppleBeaconItemStmt) {
            mInsertAppleBeaconItemStmt.close();
            mInsertAppleBeaconItemStmt = null;
        }

        if (null != mInsertEddystoneItemStmt) {
            mInsertEddystoneItemStmt.close();
            mInsertEddystoneItemStmt = null;
        }

        if (null != mUpdateShortUrlStmt) {
            mUpdateShortUrlStmt.close();
            mUpdateShortUrlStmt = null;
        }

        if (null != mUpdateStateStmt) {
            mUpdateStateStmt.close();
            mUpdateStateStmt = null;
        }

        super.close();
    }

    private long insertItem(SQLiteDatabase db, long kind, int advertiseMode, int txPowerLevel, int flags, String name) {
        if (null == mInsertItemStmt) {
            mInsertItemStmt = db.compileStatement("INSERT INTO " + ITEMS_TABLE + " (created, advMode, txLevel, kind, flags, name) VALUES (?, ?, ?, ?, ?, ?)");
        }

        mInsertItemStmt.bindLong(1, System.currentTimeMillis());
        mInsertItemStmt.bindLong(2, advertiseMode);
        mInsertItemStmt.bindLong(3, txPowerLevel);
        mInsertItemStmt.bindLong(4, kind);
        mInsertItemStmt.bindLong(5, flags);
        if (null == name) mInsertItemStmt.bindNull(6);
        else mInsertItemStmt.bindString(6, name);

        return mInsertItemStmt.executeInsert();
    }

    /**
     * Inserts a new Ephemeral URL entry.
     * @param item
     * @return Inserted item ID.
     */
    public long insertUriioItem(EphemeralURL item) {
        SQLiteDatabase db = getWritableDatabase();

        if (null == mInsertUriioItemStmt) {
            mInsertUriioItemStmt = db.compileStatement("INSERT INTO " + URIIO_TABLE +
                    " (rowid, key, longUrl, urlId, ttl, privateKey) VALUES (?, ?, ?, ?, ?, ?)");
        }

        mInsertUriioItemStmt.bindString(2, item.getUrlToken());

        String longUrl = item.getLongUrl();
        if (null == longUrl) mInsertUriioItemStmt.bindNull(3);
        else mInsertUriioItemStmt.bindString(3, longUrl);

        mInsertUriioItemStmt.bindLong(4, item.getUrlId());
        mInsertUriioItemStmt.bindLong(5, item.getTimeToLive());

        // fixme - consider saving the AES key if possible
        byte[] privateKey = null;
        if (null == privateKey) {
            mInsertUriioItemStmt.bindNull(6);
        }
        else {
            mInsertUriioItemStmt.bindString(6, Base64.encodeToString(privateKey,
                    Base64.NO_PADDING | Base64.URL_SAFE | Base64.NO_WRAP));
        }

        return insertByKind(db, item, mInsertUriioItemStmt);
    }

    /**
     * Inserts a new Eddystone item into the database.
     * @param item    Eddystone item, either an URL, UID, or EID instance.
     * @return  The inserted item ID
     */
    public long insertEddystoneItem(EddystoneBase item) {
        SQLiteDatabase db = getWritableDatabase();

        if (null == mInsertEddystoneItemStmt) {
            mInsertEddystoneItemStmt = db.compileStatement("INSERT INTO " + EDDYSTONE_TABLE +
                    " (rowid, url, domain, lockKey) VALUES (?, ?, ?, ?)");
        }
        mInsertEddystoneItemStmt.bindString(2, getEddystonePayload(item));

        String domain = item.getType() == Beacon.EDDYSTONE_UID ? ((EddystoneUID) item).getDomainHint() : null;
        if (null == domain) {
            mInsertEddystoneItemStmt.bindNull(3);
        }
        else {
            mInsertEddystoneItemStmt.bindString(3, domain);
        }

        mInsertEddystoneItemStmt.bindBlob(4, item.getLockKey());

        return insertByKind(db, item, mInsertEddystoneItemStmt);
    }

    public long insertAppleBeaconItem(iBeacon item) {
        SQLiteDatabase db = getWritableDatabase();

        if (null == mInsertAppleBeaconItemStmt) {
            mInsertAppleBeaconItemStmt = db.compileStatement("INSERT INTO " + IBEACONS_TABLE +
                    " (rowid, uuid, maj, min) VALUES (?, ?, ?, ?)");
        }
        mInsertAppleBeaconItemStmt.bindString(2, Base64.encodeToString(item.getUuidRaw(), Base64.NO_PADDING));
        mInsertAppleBeaconItemStmt.bindLong(3, item.getMajor());
        mInsertAppleBeaconItemStmt.bindLong(4, item.getMinor());

        return insertByKind(db, item, mInsertAppleBeaconItemStmt);
    }

    private long insertByKind(SQLiteDatabase db, Beacon item, SQLiteStatement stmt) {
        long rowid = 0;

        db.beginTransaction();
        try {
            rowid = insertItem(db, item.getKind(), item.getAdvertiseMode(),
                    item.getTxPowerLevel(), item.getFlags(), item.getName());
            if (rowid > 0) {
                stmt.bindLong(1, rowid);
                stmt.executeInsert();
                db.setTransactionSuccessful();

                item.setId(rowid);
            }
        }
        finally {
            db.endTransaction();
        }

        return rowid;
    }

    public void updateUriioItemShortUrl(EphemeralURL item) {
        SQLiteDatabase db = getWritableDatabase();

        if (null == mUpdateShortUrlStmt) {
            mUpdateShortUrlStmt = db.compileStatement("UPDATE " + URIIO_TABLE + " SET shortUrl=?, expires=? WHERE rowid=?");
        }

        String shortUrl = item.getURL();
        if (null == shortUrl) {
            mUpdateShortUrlStmt.bindNull(1);
        }
        else {
            mUpdateShortUrlStmt.bindString(1, shortUrl);
        }

        mUpdateShortUrlStmt.bindLong(2, item.getActualExpireTime());
        mUpdateShortUrlStmt.bindLong(3, item.getId());

        mUpdateShortUrlStmt.executeUpdateDelete();
    }

    public void updateItemState(long itemId, int state) {
        SQLiteDatabase db = getWritableDatabase();

        if (null == mUpdateStateStmt) {
            // mask = ~ 0x03
            // flags = (flags & mask) | state
            mUpdateStateStmt = db.compileStatement("UPDATE " + ITEMS_TABLE + " SET state=? WHERE rowid=?");
        }

        mUpdateStateStmt.bindLong(1, state);
        mUpdateStateStmt.bindLong(2, itemId);

        mUpdateStateStmt.execute();
    }

    public void deleteItem(long itemId) {
        SQLiteDatabase db = getWritableDatabase();

        SQLiteStatement stmt = db.compileStatement("DELETE FROM " + ITEMS_TABLE + " WHERE rowid=?");
        stmt.bindLong(1, itemId);
        stmt.executeUpdateDelete();
        stmt.close();

        stmt = db.compileStatement("DELETE FROM " + EDDYSTONE_TABLE + " WHERE rowid=?");
        stmt.bindLong(1, itemId);
        stmt.executeUpdateDelete();
        stmt.close();

        stmt = db.compileStatement("DELETE FROM " + IBEACONS_TABLE + " WHERE rowid=?");
        stmt.bindLong(1, itemId);
        stmt.executeUpdateDelete();
        stmt.close();

        stmt = db.compileStatement("DELETE FROM " + URIIO_TABLE + " WHERE rowid=?");
        stmt.bindLong(1, itemId);
        stmt.executeUpdateDelete();
        stmt.close();
    }

    public Cursor getAllItems(boolean stopped) {
        // if we ever use this in a CursorAdapter, the rowid column should also be aliased to '_id'
        return getReadableDatabase().rawQuery(String.format("SELECT i.rowid, key, longUrl, created, state, shortUrl, expires, advMode, txLevel, urlId, ttl, flags, kind, url, uuid, maj, min, name, domain, lockKey" +
                " FROM " + ITEMS_TABLE + " i" +
                " LEFT OUTER JOIN " + URIIO_TABLE + " u ON i.rowid=u.rowid" +
                " LEFT OUTER JOIN " + EDDYSTONE_TABLE + " e ON i.rowid=e.rowid" +
                " LEFT OUTER JOIN " + IBEACONS_TABLE + " a ON i.rowid=a.rowid" +
                " WHERE state%s2 ORDER BY i.rowid DESC", stopped ? "=" : "<"), null);
    }

    public Cursor getItem(long itemId) {
        return getReadableDatabase().rawQuery("SELECT i.rowid, key, longUrl, created, state, shortUrl, expires, advMode, txLevel, urlId, ttl, flags, kind, url, uuid, maj, min, name, domain, lockKey" +
                    " FROM " + ITEMS_TABLE + " i" +
                    " LEFT OUTER JOIN " + URIIO_TABLE + " u ON i.rowid=u.rowid" +
                    " LEFT OUTER JOIN " + EDDYSTONE_TABLE + " e ON i.rowid=e.rowid" +
                    " LEFT OUTER JOIN " + IBEACONS_TABLE + " a ON i.rowid=a.rowid" +
                    " WHERE i.rowid=?",
                new String[] { String.valueOf(itemId)});
    }

    public static Beacon itemFromCursor(Cursor cursor) {
        Beacon item = null;

        long itemId = cursor.getLong(0);
        int flags = cursor.getInt(11);
        int kind = cursor.getInt(12);
        String name = cursor.getString(17);

        // todo - clamp these just to make sure
        @Beacon.AdvertiseMode int advertiseMode = cursor.getInt(7);
        @Beacon.AdvertiseTxPower int txPowerLevel = cursor.getInt(8);

        if (kind == KIND_URIIO) {
            String urlToken = cursor.getString(1);
            String longUrl = cursor.getString(2);
            long expires = cursor.getLong(6);
            long urlId = cursor.getLong(9);
            int ttl = cursor.getInt(10);
            String shortUrl = cursor.getString(5);

            item = new EphemeralURL(itemId, urlId, urlToken, ttl, longUrl, expires, shortUrl, advertiseMode, txPowerLevel, name);
        } else if (kind == KIND_EDDYSTONE) {
            byte[] lockKey = null;
            if (!cursor.isNull(19)) {
                lockKey = cursor.getBlob(19);
            }
            item = loadEddystone(itemId, advertiseMode, txPowerLevel, flags, name,
                    cursor.getString(13), cursor.getString(18), lockKey);
        } else if (kind == KIND_IBEACON) {
            byte[] uuid = Base64.decode(cursor.getString(14), Base64.DEFAULT);
            item = new iBeacon(itemId, uuid, cursor.getInt(15), cursor.getInt(16), advertiseMode, txPowerLevel, flags, name);
        }

        if (null != item) {
            item.setStorageState(cursor.getInt(4));
        }

        return item;
    }

    public void updateIBeaconItem(iBeacon item) {
        long id = item.getId();

        SQLiteDatabase db = getWritableDatabase();

        // todo - transact
        updateItem(db, item);

        SQLiteStatement stmt = db.compileStatement("UPDATE " + IBEACONS_TABLE + " SET uuid=?, maj=?, min=? WHERE rowid=?");
        stmt.bindString(1, Base64.encodeToString(item.getUuidRaw(), Base64.NO_PADDING | Base64.NO_WRAP));
        stmt.bindLong(2, item.getMajor());
        stmt.bindLong(3, item.getMinor());
        stmt.bindLong(4, id);

        stmt.executeUpdateDelete();
    }

    public void updateEddystoneItem(EddystoneBase item) {
        SQLiteDatabase db = getWritableDatabase();

        // todo - transact
        updateItem(db, item);

        SQLiteStatement stmtUpdateEddystone = db.compileStatement("UPDATE " + EDDYSTONE_TABLE + " SET url=?, domain=? WHERE rowid=?");
        stmtUpdateEddystone.bindString(1, getEddystonePayload(item));

        String domain = item.getType() == Beacon.EDDYSTONE_UID ? ((EddystoneUID) item).getDomainHint() : null;
        if (null == domain) {
            stmtUpdateEddystone.bindNull(2);
        } else {
            stmtUpdateEddystone.bindString(2, domain);
        }

        stmtUpdateEddystone.bindLong(3, item.getId());

        stmtUpdateEddystone.executeUpdateDelete();
    }

    public void updateUriioItem(EphemeralURL item) {
        long id = item.getId();

        SQLiteDatabase db = getWritableDatabase();

        // todo - transact
        updateItem(db, item);

        SQLiteStatement stmt = db.compileStatement("UPDATE " + URIIO_TABLE + " SET ttl=?, longUrl=? WHERE rowid=?");
        stmt.bindLong(1, item.getTimeToLive());

        String longUrl = item.getLongUrl();
        if (null == longUrl) stmt.bindNull(2);
        else stmt.bindString(2, longUrl);

        stmt.bindLong(3, id);

        stmt.executeUpdateDelete();
    }

    private void updateItem(SQLiteDatabase db, Beacon item) {
        SQLiteStatement stmt = db.compileStatement("UPDATE " + ITEMS_TABLE + " SET advMode=?, txLevel=?, flags=?, name=? WHERE rowid=?");
        stmt.bindLong(1, item.getAdvertiseMode());
        stmt.bindLong(2, item.getTxPowerLevel());
        stmt.bindLong(3, item.getFlags());

        String name = item.getName();
        if (null == name) stmt.bindNull(4);
        else stmt.bindString(4, name);

        stmt.bindLong(5, item.getId());

        stmt.executeUpdateDelete();
    }

    public void save(Beacon item) {
        switch (item.getKind()) {
            case KIND_EDDYSTONE:
                updateEddystoneItem((EddystoneBase) item);
                break;
            case KIND_IBEACON:
                updateIBeaconItem((iBeacon) item);
                break;
            case KIND_URIIO:
                updateUriioItem((EphemeralURL) item);
                break;
        }
    }

    private static EddystoneBase loadEddystone(long id, @Beacon.AdvertiseMode int advertiseMode,
                                               @Beacon.AdvertiseTxPower int txPowerLevel, int flags,
                                               String name, String payload, String domain, byte[] lockKey) {
        int type = flags >>> 4;

        switch (type) {
            case Beacon.EDDYSTONE_URL:
                return new EddystoneURL(id, payload, lockKey, advertiseMode, txPowerLevel, name);
            case Beacon.EDDYSTONE_UID:
                byte[] data;
                return new EddystoneUID(id, Base64.decode(payload, Base64.DEFAULT), domain, lockKey,
                        advertiseMode, txPowerLevel, name);
            case Beacon.EDDYSTONE_EID:
                data = Base64.decode(payload, Base64.DEFAULT);
                int eidTimeOffset = ByteBuffer.wrap(data, 16, 4).getInt();

                // sanitize time offset to match range; see EIDUtils.register()
                eidTimeOffset = Math.min(255, Math.max(-65280, eidTimeOffset));

                // sanitize rotation exponent to [0, 15] range
                byte rotationExponent = (byte) (data[20] & 0x0f);
                return new EddystoneEID(id, data, rotationExponent, eidTimeOffset, lockKey,
                        advertiseMode, txPowerLevel, name);
        }

        return null;
    }

    private String getEddystonePayload(EddystoneBase beacon) {
        switch (beacon.getType()) {
            case Beacon.EDDYSTONE_EID:
                EddystoneEID eddystoneEID = (EddystoneEID) beacon;
                // serialize beacon into storage blob
                byte[] data = new byte[21];
                System.arraycopy(eddystoneEID.getIdentityKey(), 0, data, 0, 16);
                ByteBuffer.wrap(data, 16, 4).putInt(eddystoneEID.getEidTimeOffset());
                data[20] = eddystoneEID.getRotationExponent();

                return Base64.encodeToString(data, Base64.NO_PADDING);
            case Beacon.EDDYSTONE_URL:
                return ((EddystoneURL) beacon).getURL();
            case Beacon.EDDYSTONE_UID:
                return Base64.encodeToString(((EddystoneUID) beacon).getNamespaceInstance(), Base64.NO_PADDING);
        }
        return null;
    }
}