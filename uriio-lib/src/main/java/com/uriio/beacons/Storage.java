package com.uriio.beacons;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Base64;

import com.uriio.beacons.model.BaseItem;
import com.uriio.beacons.model.EddystoneItem;
import com.uriio.beacons.model.UriioItem;
import com.uriio.beacons.model.iBeaconItem;

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

    private static final int DATABASE_SCHEMA_VERSION = 5;

    private static Storage _instance;

    /** lazy SQLite statements **/
    private SQLiteStatement mInsertItemStmt = null;
    private SQLiteStatement mInsertUriioItemStmt = null;
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
        db.execSQL("CREATE TABLE " + EDDYSTONE_TABLE + " (url TEXT, domain TEXT)");

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
     * @param flags - 0 to use Eddystone format, 1 to use  URIBeacon format
     * @param name
     */
    public long insertUriioItem(long urlId, String urlToken, String longUrl, byte[] privateKey,
                                int mode, int txPowerLevel, int ttl, int flags, String name) {
        SQLiteDatabase db = getWritableDatabase();

        if (null == mInsertUriioItemStmt) {
            mInsertUriioItemStmt = db.compileStatement("INSERT INTO " + URIIO_TABLE +
                    " (rowid, key, longUrl, urlId, ttl, privateKey) VALUES (?, ?, ?, ?, ?, ?)");
        }

        mInsertUriioItemStmt.bindString(2, urlToken);
        mInsertUriioItemStmt.bindString(3, longUrl);
        mInsertUriioItemStmt.bindLong(4, urlId);
        mInsertUriioItemStmt.bindLong(5, ttl);

        if (null == privateKey) {
            mInsertUriioItemStmt.bindNull(6);
        }
        else {
            mInsertUriioItemStmt.bindString(6, Base64.encodeToString(privateKey,
                    Base64.NO_PADDING | Base64.URL_SAFE | Base64.NO_WRAP));
        }

        return insertByKind(db, KIND_URIIO, mode, txPowerLevel, flags, name, mInsertUriioItemStmt);
    }

    /**
     * @param payload URL or base64 UUID
     * @param flags 0xPQ where p is frame type (0 for URL, 1 for UID), and Q is beacon type (0 for Eddystone, 1 for URIBeacon)
     * @param name
     */
    public long insertEddystoneItem(int mode, int txPowerLevel, String payload, int flags, String name, String domain) {
        SQLiteDatabase db = getWritableDatabase();

        SQLiteStatement stmtInsertEddystone = db.compileStatement("INSERT INTO " + EDDYSTONE_TABLE + " (rowid, url, domain) VALUES (?, ?, ?)");
        stmtInsertEddystone.bindString(2, payload);
        if (null == domain) {
            stmtInsertEddystone.bindNull(3);
        }
        else {
            stmtInsertEddystone.bindString(3, domain);
        }

        long id = insertByKind(db, KIND_EDDYSTONE, mode, txPowerLevel, flags, name, stmtInsertEddystone);
        stmtInsertEddystone.close();

        return id;
    }

    /**
     * @param flags Company code flag. 0 for Apple iBeacon, 1 for AltBeacon (0xBEAC)
     * @param name
     */
    public long insertAppleBeaconItem(int mode, int txPowerLevel, String base64Uuid, int major, int minor, int flags, String name) {
        SQLiteDatabase db = getWritableDatabase();

        SQLiteStatement stmt = db.compileStatement("INSERT INTO " + IBEACONS_TABLE + " (rowid, uuid, maj, min) VALUES (?, ?, ?, ?)");
        stmt.bindString(2, base64Uuid);
        stmt.bindLong(3, major);
        stmt.bindLong(4, minor);

        long id = insertByKind(db, KIND_IBEACON, mode, txPowerLevel, flags, name, stmt);
        stmt.close();

        return id;
    }

    private long insertByKind(SQLiteDatabase db, long kind, int mode, int txPowerLevel, int flags, String name, SQLiteStatement stmt) {
        long rowid = 0;

        db.beginTransaction();
        try {
            rowid = insertItem(db, kind, mode, txPowerLevel, flags, name);
            if (rowid > 0) {
                stmt.bindLong(1, rowid);
                stmt.executeInsert();
                db.setTransactionSuccessful();
            }
        }
        finally {
            db.endTransaction();
        }

        return rowid;
    }

    public void updateUriioItemShortUrl(long itemId, String shortUrl, long expireTimestamp) {
        SQLiteDatabase db = getWritableDatabase();

        if (null == mUpdateShortUrlStmt) {
            mUpdateShortUrlStmt = db.compileStatement("UPDATE " + URIIO_TABLE + " SET shortUrl=?, expires=? WHERE rowid=?");
        }
        mUpdateShortUrlStmt.bindString(1, shortUrl);
        mUpdateShortUrlStmt.bindLong(2, expireTimestamp);
        mUpdateShortUrlStmt.bindLong(3, itemId);

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

    public Cursor getActiveItems() {
        return getReadableDatabase().rawQuery("SELECT i.rowid, key, longUrl, created, state, shortUrl, expires, advMode, txLevel, urlId, ttl, flags, kind, url, uuid, maj, min, name, domain, privateKey" +
                " FROM " + ITEMS_TABLE + " i" +
                " LEFT OUTER JOIN " + URIIO_TABLE + " u ON i.rowid=u.rowid" +
                " LEFT OUTER JOIN " + EDDYSTONE_TABLE + " e ON i.rowid=e.rowid" +
                " LEFT OUTER JOIN " + IBEACONS_TABLE + " a ON i.rowid=a.rowid" +
                " WHERE state<2 ORDER BY i.rowid DESC", null);
    }

    public Cursor getUriioItems() {
        return getReadableDatabase().rawQuery("SELECT i.rowid, flags, advMode, txLevel, state, name, urlId, key, longUrl, ttl, shortUrl, expires, privateKey" +
                " FROM " + ITEMS_TABLE + " i" +
                " LEFT OUTER JOIN " + URIIO_TABLE + " u ON i.rowid=u.rowid" +
                " WHERE kind=1 ORDER BY i.rowid DESC", null);
    }

    public static UriioItem uriioItemFromCursor(Cursor cursor) {
        byte[] privateKey = null;
        String pk = cursor.getString(12);
        if (pk != null && pk.length() > 0) {
            privateKey = Base64.decode(pk, Base64.URL_SAFE);
        }
        UriioItem item = new UriioItem(cursor.getLong(0), cursor.getInt(1), cursor.getLong(6),
                cursor.getString(7), cursor.getInt(9), cursor.getLong(11), cursor.getString(10),
                cursor.getString(8), privateKey);

        item.setAdvertiseMode(cursor.getInt(2));
        item.setTxPowerLevel(cursor.getInt(3));
        item.setStorageState(cursor.getInt(4));
        item.setName(cursor.getString(5));

        return item;
    }

    public Cursor getInactiveItems() {
        // if we use this in a CursorAdapter, the rowid column should also be aliased to '_id'
        return getReadableDatabase().rawQuery("SELECT i.rowid, key, longUrl, created, state, shortUrl, expires, advMode, txLevel, urlId, ttl, flags, kind, url, uuid, maj, min, name, domain, privateKey" +
                " FROM " + ITEMS_TABLE + " i" +
                " LEFT OUTER JOIN " + URIIO_TABLE + " u ON i.rowid=u.rowid" +
                " LEFT OUTER JOIN " + EDDYSTONE_TABLE + " e ON i.rowid=e.rowid" +
                " LEFT OUTER JOIN " + IBEACONS_TABLE + " a ON i.rowid=a.rowid" +
                " WHERE state=2 ORDER BY i.rowid DESC", null);
    }

    public Cursor getItem(long itemId) {
        return getReadableDatabase().rawQuery("SELECT i.rowid, key, longUrl, created, state, shortUrl, expires, advMode, txLevel, urlId, ttl, flags, kind, url, uuid, maj, min, name, domain, privateKey" +
                    " FROM " + ITEMS_TABLE + " i" +
                    " LEFT OUTER JOIN " + URIIO_TABLE + " u ON i.rowid=u.rowid" +
                    " LEFT OUTER JOIN " + EDDYSTONE_TABLE + " e ON i.rowid=e.rowid" +
                    " LEFT OUTER JOIN " + IBEACONS_TABLE + " a ON i.rowid=a.rowid" +
                    " WHERE i.rowid=?",
                new String[] { String.valueOf(itemId)});
    }

    public static BaseItem itemFromCursor(Cursor cursor) {
        BaseItem item = null;

        long itemId = cursor.getLong(0);
        int flags = cursor.getInt(11);
        int kind = cursor.getInt(12);
        String name = cursor.getString(17);

        if (kind == KIND_URIIO) {
            String apiKey = cursor.getString(1);
            String longUrl = cursor.getString(2);
            long expires = cursor.getLong(6);
            long urlId = cursor.getLong(9);
            int ttl = cursor.getInt(10);
            String shortUrl = cursor.getString(5);

            byte[] privateKey = null;
            String pk = cursor.getString(19);
            if (pk != null && pk.length() > 0) {
                privateKey = Base64.decode(pk, Base64.URL_SAFE);
            }

            item = new UriioItem(itemId, flags, urlId, apiKey, ttl, expires, shortUrl, longUrl, privateKey);
        } else if (kind == KIND_EDDYSTONE) {
            item = new EddystoneItem(itemId, flags, cursor.getString(13), cursor.getString(18));
        } else if (kind == KIND_IBEACON) {
            byte[] rawUuid = Base64.decode(cursor.getString(14), Base64.DEFAULT);
            item = new iBeaconItem(itemId, flags, rawUuid, cursor.getInt(15), cursor.getInt(16));
        }

        if (null != item) {
            item.setAdvertiseMode(cursor.getInt(7));
            item.setTxPowerLevel(cursor.getInt(8));
            item.setName(name);
            item.setStorageState(cursor.getInt(4));
        }

        return item;
    }

    public void updateIBeaconItem(long id, int mode, int txPowerLevel, byte[] rawUuid, int major, int minor, int flags, String name) {
        SQLiteDatabase db = getWritableDatabase();

        // todo - transact
        updateItem(db, id, mode, txPowerLevel, flags, name);

        SQLiteStatement stmt = db.compileStatement("UPDATE " + IBEACONS_TABLE + " SET uuid=?, maj=?, min=? WHERE rowid=?");
        stmt.bindString(1, Base64.encodeToString(rawUuid, Base64.NO_PADDING | Base64.NO_WRAP));
        stmt.bindLong(2, major);
        stmt.bindLong(3, minor);
        stmt.bindLong(4, id);

        stmt.executeUpdateDelete();
    }

    public void updateEddystoneItem(long id, int mode, int txPowerLevel, String url, int flags, String name, String domain) {
        SQLiteDatabase db = getWritableDatabase();

        // todo - transact
        updateItem(db, id, mode, txPowerLevel, flags, name);

        SQLiteStatement stmtUpdateEddystone = db.compileStatement("UPDATE " + EDDYSTONE_TABLE + " SET url=?, domain=? WHERE rowid=?");
        stmtUpdateEddystone.bindString(1, url);
        if (null == domain) {
            stmtUpdateEddystone.bindNull(2);
        } else {
            stmtUpdateEddystone.bindString(2, domain);
        }
        stmtUpdateEddystone.bindLong(3, id);

        stmtUpdateEddystone.executeUpdateDelete();
    }

    public void updateUriioItem(long id, int mode, int txPowerLevel, int timeToLive, int flags, String name) {
        SQLiteDatabase db = getWritableDatabase();

        // todo - transact
        updateItem(db, id, mode, txPowerLevel, flags, name);

        SQLiteStatement stmt = db.compileStatement("UPDATE " + URIIO_TABLE + " SET ttl=? WHERE rowid=?");
        stmt.bindLong(1, timeToLive);
        stmt.bindLong(2, id);

        stmt.executeUpdateDelete();
    }

    private void updateItem(SQLiteDatabase db, long id, int mode, int txPowerLevel, int flags, String name) {
        SQLiteStatement stmt = db.compileStatement("UPDATE " + ITEMS_TABLE + " SET advMode=?, txLevel=?, flags=?, name=? WHERE rowid=?");
        stmt.bindLong(1, mode);
        stmt.bindLong(2, txPowerLevel);
        stmt.bindLong(3, flags);
        stmt.bindString(4, name);
        stmt.bindLong(5, id);

        stmt.executeUpdateDelete();
    }
}