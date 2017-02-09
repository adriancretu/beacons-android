package com.uriio.beacons;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Base64;
import android.util.SparseArray;

import com.uriio.beacons.ble.Advertiser;
import com.uriio.beacons.model.Beacon;
import com.uriio.beacons.model.EddystoneBase;
import com.uriio.beacons.model.EddystoneEID;
import com.uriio.beacons.model.EddystoneTLM;
import com.uriio.beacons.model.EddystoneUID;
import com.uriio.beacons.model.EddystoneURL;
import com.uriio.beacons.model.iBeacon;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Database manager.
 */
public class Storage extends SQLiteOpenHelper {
    /**
     * Serializer/storage interface for custom beacon kinds.
     */
    public interface Persistable {
        /**
         * @return The beacon kind. Values below 0x10000 (65536) are reserved. Since the beacon kind
         * is stored with every beacon, it must never be changed.
         */
        int getKind();

        /**
         * Prepares an insert. You can bind custom data to indexes 1 to 7 of the statement.
         * @param beacon       Beacon that will be inserted.
         * @param statement    Compiled statement. First 7 positions can be used for custom data.
         */
        void prepareInsert(Beacon beacon, SQLiteStatement statement);

        /**
         * @param beacon    Beacon to be saved.
         * @param db        SQLite database
         * @param flags     Custom flags sent by your beacon editor, or 0 to save default data.  @return          The statement to use. The statement is not closed after execution.
         */
        SQLiteStatement prepareUpdate(Beacon beacon, SQLiteDatabase db, int flags);

        /**
         * Called after a beacon was deleted from storage.
         * @param beacon    The beacon that was removed.
         */
        void onDeleted(Beacon beacon);

        /**
         * @param cursor    Database cursor.
         * @return          Deserialized beacon instance.
         */
        Beacon fromCursor(Cursor cursor);

        void close();
    }

    public static final int KIND_EDDYSTONE_URL = 1;
    public static final int KIND_EDDYSTONE_UID = 2;
    public static final int KIND_IBEACON       = 3;
    public static final int KIND_EDDYSTONE_EID = 4;
    public static final int KIND_EDDYSTONE_TLM = 5;

    private static final String ITEMS_TABLE     = "b";
    @Deprecated private static final String EDDYSTONE_TABLE = "url";
    @Deprecated private static final String IBEACONS_TABLE  = "ib";
    @Deprecated private static final String URIIO_TABLE     = "uriio";

    private static final int DATABASE_SCHEMA_VERSION = 7;

    private static Storage _instance;

    /** lazy SQLite statements **/
    private SQLiteStatement mInsertItemStmt = null;
    private SQLiteStatement mUpdateItemStmt = null;
    private SQLiteStatement mDeleteItemStmt = null;
    private SQLiteStatement mUpdateStateStmt = null;
    private SQLiteStatement mUpdateEddystoneStmt = null;
    private SQLiteStatement mUpdateIBeaconStmt = null;

    private SparseArray<Persistable> mBeaconPersisters = null;

    private Storage(Context context, String dbName) {
        super(context, dbName, null, DATABASE_SCHEMA_VERSION);
    }

    static void init(Context context, String dbName) {
        _instance = new Storage(context, dbName);

        ApplicationInfo appInfo;
        try {
            appInfo = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("App package not found");
        }

        // metadata is null when no entries exist
        if (null != appInfo && null != appInfo.metaData) {
            for (String key : appInfo.metaData.keySet()) {
                if (key.startsWith("com.uriio.ext.")) {
                    String className = appInfo.metaData.getString(key);
                    if (null != className) {
                        Persistable persistable;

                        try {
                            persistable = (Persistable) Class.forName(className).newInstance();
                        } catch (Exception ignored) {
                            continue;
                        }

                        int kind = persistable.getKind();
                        if (kind > 0xffff) {
                            if (null == _instance.mBeaconPersisters) {
                                _instance.mBeaconPersisters = new SparseArray<>();
                            }
                            _instance.mBeaconPersisters.put(kind, persistable);
                        }
                    }
                }
            }
        }
    }

    public static Storage getInstance() {
        return _instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
//        db.beginTransaction();

        // create main table
        db.execSQL("CREATE TABLE " + ITEMS_TABLE + " (kind INTEGER, created INTEGER, " +
                "advMode INTEGER, txLevel INTEGER, state INTEGER DEFAULT 1, flags INTEGER, " +
                "name TEXT, d0 TEXT, d1 TEXT, d2 TEXT, d3 TEXT, d4 TEXT, d5 TEXT, d6 TEXT)");

        // indexes
//        db.execSQL("CREATE INDEX ia ON " + ITEMS_TABLE + "(kind)");

//        db.setTransactionSuccessful();
//        db.endTransaction();
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

        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE " + EDDYSTONE_TABLE + " ADD COLUMN lockKey BLOB");
        }

        if (oldVersion < 7) {
            for (int idx = 0; idx < 7; idx++) {
                db.execSQL(String.format("ALTER TABLE " + ITEMS_TABLE + " ADD COLUMN d%d TEXT", idx));
            }

            migrateUriioItems(db);
            migrateEddystoneItems(db);
            migrateIBeaconItems(db);
        }
    }

    @Override
    public synchronized void close() {
        if (null != mInsertItemStmt) {
            mInsertItemStmt.close();
            mInsertItemStmt = null;
        }

        if (null != mUpdateItemStmt) {
            mUpdateItemStmt.close();
            mUpdateItemStmt = null;
        }

        if (null != mDeleteItemStmt) {
            mDeleteItemStmt.close();
            mDeleteItemStmt = null;
        }

        if (null != mUpdateStateStmt) {
            mUpdateStateStmt.close();
            mUpdateStateStmt = null;
        }

        if (null != mUpdateEddystoneStmt) {
            mUpdateEddystoneStmt.close();
            mUpdateEddystoneStmt = null;
        }

        if (null != mUpdateIBeaconStmt) {
            mUpdateIBeaconStmt.close();
            mUpdateIBeaconStmt = null;
        }

        if (null != mBeaconPersisters) {
            for (int idx = mBeaconPersisters.size() - 1; idx >= 0; --idx) {
                mBeaconPersisters.valueAt(idx).close();
            }
        }

        super.close();
    }

    public long insert(Beacon item) {
        SQLiteDatabase db = getWritableDatabase();

        if (null == mInsertItemStmt) {
            mInsertItemStmt = db.compileStatement("INSERT INTO " + ITEMS_TABLE +
                    " (d0, d1, d2, d3, d4, d5, d6, created, advMode, txLevel, kind, flags, name)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        }

        mInsertItemStmt.clearBindings();

        switch (item.getKind()) {
            case KIND_EDDYSTONE_URL:
            case KIND_EDDYSTONE_UID:
            case KIND_EDDYSTONE_EID:
            case KIND_EDDYSTONE_TLM:
                bindInsertEddystoneItem((EddystoneBase) item);
                break;
            case KIND_IBEACON:
                bindInsertIBeaconStatement((iBeacon) item);
                break;
            default:
                if (null != mBeaconPersisters) {
                    mBeaconPersisters.get(item.getKind()).prepareInsert(item, mInsertItemStmt);
                }
                break;
        }

        mInsertItemStmt.bindLong(8, System.currentTimeMillis());
        mInsertItemStmt.bindLong(9, item.getAdvertiseMode());
        mInsertItemStmt.bindLong(10, item.getTxPowerLevel());
        mInsertItemStmt.bindLong(11, item.getKind());
        mInsertItemStmt.bindLong(12, item.getFlags());
        bindStringOrNull(mInsertItemStmt, 13, item.getName());

        long rowid = mInsertItemStmt.executeInsert();
        if (rowid > 0) {
            item.setStorageId(rowid);
        }

        return rowid;
    }

    private void bindInsertEddystoneItem(EddystoneBase beacon) {
        mInsertItemStmt.bindBlob(1, beacon.getLockKey());

        switch (beacon.getKind()) {
            case KIND_EDDYSTONE_URL:
                bindStringOrNull(mInsertItemStmt, 2, ((EddystoneURL) beacon).getURL());
                break;
            case KIND_EDDYSTONE_UID:
                mInsertItemStmt.bindBlob(2, ((EddystoneUID) beacon).getNamespaceInstance());
                bindStringOrNull(mInsertItemStmt, 3, ((EddystoneUID) beacon).getDomainHint());
                break;
            case KIND_EDDYSTONE_EID:
                mInsertItemStmt.bindBlob(2, ((EddystoneEID) beacon).getIdentityKey());
                mInsertItemStmt.bindLong(3, ((EddystoneEID) beacon).getRotationExponent());
                mInsertItemStmt.bindLong(4, ((EddystoneEID) beacon).getClockOffset());
                break;
            case KIND_EDDYSTONE_TLM:
                mInsertItemStmt.bindLong(2, ((EddystoneTLM) beacon).getRefreshInterval());
                break;
        }
    }

    private void bindInsertIBeaconStatement(iBeacon beacon) {
        mInsertItemStmt.bindBlob(1, beacon.getUuidRaw());
        mInsertItemStmt.bindLong(2, beacon.getMajor());
        mInsertItemStmt.bindLong(3, beacon.getMinor());
    }

    public void delete(Beacon beacon) {
        long id = beacon.getSavedId();
        if (id > 0) {
            SQLiteDatabase db = getWritableDatabase();

            if (null == mDeleteItemStmt) {
                mDeleteItemStmt = db.compileStatement("DELETE FROM " + ITEMS_TABLE + " WHERE rowid=?");
            }

            mDeleteItemStmt.bindLong(1, id);
            executeSafeUpdateOrDelete(mDeleteItemStmt);

            Persistable persistable = null == mBeaconPersisters ? null : mBeaconPersisters.get(beacon.getKind());
            if (null != persistable) {
                persistable.onDeleted(beacon);
            }
        }
    }

    Cursor queryAll(boolean stopped) {
        // if we ever use this in a CursorAdapter, the rowid column should be aliased to '_id'
        return getReadableDatabase().rawQuery(String.format("SELECT d0, d1, d2, d3, d4, d5, d6," +
                        " rowid, state, advMode, txLevel, flags, kind, name, created" +
                        " FROM " + ITEMS_TABLE + " WHERE state%s2 ORDER BY rowid DESC",
                stopped ? "=" : "<"), null);
    }

    Cursor query(long itemId) {
        return getReadableDatabase().rawQuery("SELECT d0, d1, d2, d3, d4, d5, d6," +
                        " rowid, state, advMode, txLevel, flags, kind, name, created" +
                        " FROM " + ITEMS_TABLE + " WHERE rowid=?",
                new String[] { String.valueOf(itemId)});
    }

    public static long getId(Cursor cursor) {
        return cursor.getLong(7);
    }

    public static int getKind(Cursor cursor) {
        return cursor.getInt(12);
    }

    public static Beacon fromCursor(Cursor cursor) {
        Beacon beacon;
        int kind = cursor.getInt(12);

        switch (kind) {
            case KIND_EDDYSTONE_URL:
                beacon = new EddystoneURL(cursor.getString(1),
                        cursor.isNull(0) ? null : cursor.getBlob(0));
                break;
            case KIND_EDDYSTONE_UID:
                beacon = new EddystoneUID(cursor.getBlob(1), cursor.getString(2),
                        cursor.isNull(0) ? null : cursor.getBlob(0), null);
                break;
            case KIND_EDDYSTONE_EID:
                beacon = new EddystoneEID(cursor.getBlob(1), (byte) cursor.getInt(2), cursor.getInt(3),
                        cursor.isNull(0) ? null : cursor.getBlob(0));
                break;
            case KIND_EDDYSTONE_TLM:
                beacon = new EddystoneTLM(cursor.getInt(1), cursor.isNull(0) ? null : cursor.getBlob(0));
                break;
            case KIND_IBEACON:
                beacon = new iBeacon(cursor.getBlob(0), cursor.getInt(1), cursor.getInt(2));
                break;
            default:
                Persistable persistable = null == getInstance().mBeaconPersisters ? null : getInstance().mBeaconPersisters.get(kind);
                beacon = null == persistable ? null : persistable.fromCursor(cursor);
                break;
        }

        if (null != beacon) {
            long itemId = cursor.getLong(7);
            @Advertiser.Mode int advertiseMode = cursor.getInt(9);
            @Advertiser.Power int txPowerLevel = cursor.getInt(10);
            int flags = cursor.getInt(11);
            String name = cursor.getString(13);

            beacon.init(itemId, advertiseMode, txPowerLevel, flags, name);
            beacon.setActiveState(cursor.getInt(8));
        }

        return beacon;
    }

    private SQLiteStatement prepareUpdateStatement(iBeacon item, SQLiteDatabase db) {
        if (null == mUpdateIBeaconStmt) {
            mUpdateIBeaconStmt = createUpdater(db, "d0", "d1", "d2");
        }
        mUpdateIBeaconStmt.bindBlob(2, item.getUuidRaw());
        mUpdateIBeaconStmt.bindLong(3, item.getMajor());
        mUpdateIBeaconStmt.bindLong(4, item.getMinor());

        return mUpdateIBeaconStmt;
    }

    private SQLiteStatement prepareUpdateStatement(EddystoneBase beacon, SQLiteDatabase db) {
        if (null == mUpdateEddystoneStmt) {
            mUpdateEddystoneStmt = createUpdater(db, "d0", "d1", "d2", "d3");
        }

        mUpdateEddystoneStmt.bindBlob(2, beacon.getLockKey());

        switch (beacon.getKind()) {
            case KIND_EDDYSTONE_URL:
                bindStringOrNull(mUpdateEddystoneStmt, 3, ((EddystoneURL) beacon).getURL());
                break;
            case KIND_EDDYSTONE_UID:
                mUpdateEddystoneStmt.bindBlob(3, ((EddystoneUID) beacon).getNamespaceInstance());
                bindStringOrNull(mUpdateEddystoneStmt, 4, ((EddystoneUID) beacon).getDomainHint());
                break;
            case KIND_EDDYSTONE_EID:
                mUpdateEddystoneStmt.bindBlob(3, ((EddystoneEID) beacon).getIdentityKey());
                mUpdateEddystoneStmt.bindLong(4, ((EddystoneEID) beacon).getRotationExponent());
                mUpdateEddystoneStmt.bindLong(5, ((EddystoneEID) beacon).getClockOffset());
                break;
            case KIND_EDDYSTONE_TLM:
                mUpdateEddystoneStmt.bindLong(3, ((EddystoneTLM) beacon).getRefreshInterval());
                break;
        }

        return mUpdateEddystoneStmt;
    }

    /**
     * Saves an existing beacon's main details, and/or custom details.
     * <b>This method is for internal (and beacon extensions) use only.</b>
     * @param beacon    An existing beacon.
     * @param flags     If 0, the beacon's <b>advertiseMode</b>, <b>txPower</b>, <b>name</b> and <b>flags</b> will be saved.
     *                  Other basic details will also be saved depending on the beacon type.
     *                  If non-zero, then only custom details will be saved, on a per-beacon defined basis.
     */
    public void update(Beacon beacon, int flags) {
        SQLiteDatabase db = getWritableDatabase();

        if (0 == flags) {
            // we'll do two updates - use a transaction
            db.beginTransaction();

            if (null == mUpdateItemStmt) {
                mUpdateItemStmt = db.compileStatement("UPDATE " + ITEMS_TABLE + " SET advMode=?, txLevel=?, flags=?, name=? WHERE rowid=?");
            }

            mUpdateItemStmt.bindLong(1, beacon.getAdvertiseMode());
            mUpdateItemStmt.bindLong(2, beacon.getTxPowerLevel());
            mUpdateItemStmt.bindLong(3, beacon.getFlags());
            bindStringOrNull(mUpdateItemStmt, 4, beacon.getName());
            mUpdateItemStmt.bindLong(5, beacon.getSavedId());

            executeSafeUpdateOrDelete(mUpdateItemStmt);
        }

        SQLiteStatement updateStatement;
        switch (beacon.getKind()) {
            case KIND_EDDYSTONE_URL:
            case KIND_EDDYSTONE_UID:
            case KIND_EDDYSTONE_EID:
            case KIND_EDDYSTONE_TLM:
                updateStatement = prepareUpdateStatement((EddystoneBase) beacon, db);
                break;
            case KIND_IBEACON:
                updateStatement = prepareUpdateStatement((iBeacon) beacon, db);
                break;
            default:
                Persistable persister = null == mBeaconPersisters ? null : mBeaconPersisters.get(beacon.getKind());
                updateStatement = null == persister ? null : persister.prepareUpdate(beacon, db, flags);
                break;
        }

        if (null != updateStatement) {
            updateStatement.bindLong(1, beacon.getSavedId());
            executeSafeUpdateOrDelete(updateStatement);
        }

        if (0 == flags) {
            db.setTransactionSuccessful();
            db.endTransaction();
        }
    }

    /**
     * Saves an existing beacon's main details.
     * @param beacon    Target beacon.
     */
    public void update(Beacon beacon) {
        update(beacon, 0);
    }

    public void updateState(Beacon beacon, int state) {
        SQLiteDatabase db = getWritableDatabase();

        if (null == mUpdateStateStmt) {
            mUpdateStateStmt = db.compileStatement("UPDATE " + ITEMS_TABLE + " SET state=? WHERE rowid=?");
        }

        mUpdateStateStmt.bindLong(1, state);
        mUpdateStateStmt.bindLong(2, beacon.getSavedId());

        executeSafeUpdateOrDelete(mUpdateStateStmt);
    }

    /**
     * Binds either a string or NULL to a SQLite statement.
     * Reason: trying to bind a null string would normally crash the app.
     * @param statement    SQLite statement
     * @param value        A string, or null
     */
    public static void bindStringOrNull(@NonNull SQLiteStatement statement, int index, String value) {
        if (null == value) {
            statement.bindNull(index);
        }
        else {
            statement.bindString(index, value);
        }
    }

    private void executeSafeUpdateOrDelete(SQLiteStatement statement) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            statement.executeUpdateDelete();
        }
        else {
            statement.execute();
        }
    }

    private static @NonNull SQLiteStatement createUpdater(SQLiteDatabase db, String... columns) {
        StringBuilder sql = null;

        for (int i = 0; i < columns.length; ++i) {
            String column = columns[i];
            if (null == sql) {
                sql = new StringBuilder("UPDATE " + ITEMS_TABLE + " SET ");
            } else {
                sql.append(",");
            }
            sql.append(column).append("=?").append(2 + i);
        }

        if (null == sql) {
            throw new IllegalArgumentException("Invalid columns");
        }

        // need a db reference to work around IllegalStateException: getDatabase called recursively
        return db.compileStatement(sql.append(" WHERE rowid=?1").toString());
    }

    /**
     * Creates a beacon update statement for saving custom data.
     * In the returned statement, bind position 1 is reserved for the ID of the updated item.
     * @param db            SQLite database
     * @param columns       Custom columns to update, 0 to 6 inclusive.
     * @return              A compiled statement. To bind values, start from index 2.
     */
    public static @NonNull SQLiteStatement createUpdater(SQLiteDatabase db, int... columns) {
        String[] names = new String[columns.length];
        for (int i = 0; i < columns.length; ++i) {
            if (columns[i] >= 0 && columns[i] < 7) {
                names[i] = "d" + columns[i];
            }
        }
        return createUpdater(db, names);
    }

    private void migrateUriioItems(SQLiteDatabase db) {
        SQLiteStatement updateStatement = createUpdater(db, "d1", "d5", "kind");
        Cursor cursor = db.rawQuery("SELECT rowid, longUrl, shortUrl FROM " + URIIO_TABLE, null);
        while (cursor.moveToNext()) {
            updateStatement.bindLong(1, cursor.getLong(0));
            bindStringOrNull(updateStatement, 2, cursor.getString(1));
            bindStringOrNull(updateStatement, 3, cursor.getString(2));
            updateStatement.bindLong(4, 0x10000);

            executeSafeUpdateOrDelete(updateStatement);
        }
        cursor.close();
        updateStatement.close();

        db.execSQL("DROP TABLE " + URIIO_TABLE);
    }

    private void migrateIBeaconItems(SQLiteDatabase db) {
        SQLiteStatement updateStatement = createUpdater(db, "d0", "d1", "d2");
        Cursor cursor = db.rawQuery("SELECT rowid, uuid, maj, min FROM " + IBEACONS_TABLE, null);
        while (cursor.moveToNext()) {
            updateStatement.bindLong(1, cursor.getLong(0));
            updateStatement.bindBlob(2, Base64.decode(cursor.getString(1), Base64.DEFAULT));
            updateStatement.bindLong(3, cursor.getInt(2));
            updateStatement.bindLong(4, cursor.getInt(3));

            executeSafeUpdateOrDelete(updateStatement);
        }
        cursor.close();
        updateStatement.close();

        db.execSQL("DROP TABLE " + IBEACONS_TABLE);
    }

    private void migrateEddystoneItems(SQLiteDatabase db) {
        SQLiteStatement updateStatement = createUpdater(db, "d0", "d1", "d2", "d3", "kind", "flags");
        Cursor cursor = db.rawQuery("SELECT e.rowid, url, domain, lockKey, flags" +
                " FROM " + EDDYSTONE_TABLE + " e" +
                " LEFT OUTER JOIN " + ITEMS_TABLE + " i ON e.rowid=i.rowid", null);
        while (cursor.moveToNext()) {
            updateStatement.clearBindings();

            updateStatement.bindLong(1, cursor.getLong(0));

            int flags = cursor.getInt(4);
            int type = flags >>> 4;

            if (!cursor.isNull(3)) {
                updateStatement.bindBlob(2, cursor.getBlob(3));   // lock key
            }
            updateStatement.bindLong(7, 0);

            String payload = cursor.getString(1);
            String domain = cursor.getString(2);

            switch (type) {
                case 0:         // EDDYSTONE_URL
                    bindStringOrNull(updateStatement, 3, payload);
                    updateStatement.bindLong(6, KIND_EDDYSTONE_URL);
                    break;
                case 1:         // EDDYSTONE_UID
                    updateStatement.bindBlob(3, Base64.decode(payload, Base64.DEFAULT));
                    bindStringOrNull(updateStatement, 4, domain);
                    updateStatement.bindLong(6, KIND_EDDYSTONE_UID);
                    break;
                case 2:         // EDDYSTONE_EID
                    byte[] eidRaw = Base64.decode(payload, Base64.DEFAULT);
                    int eidTimeOffset = ByteBuffer.wrap(eidRaw, 16, 4).getInt();

                    // sanitize time offset to match range; see EIDUtils.register()
                    eidTimeOffset = Math.min(255, Math.max(-65280, eidTimeOffset));

                    // sanitize rotation exponent to [0, 15] range
                    byte rotationExponent = (byte) (eidRaw[20] & 0x0f);

                    updateStatement.bindBlob(3, Arrays.copyOfRange(eidRaw, 0, 16));
                    updateStatement.bindLong(4, rotationExponent);
                    updateStatement.bindLong(5, eidTimeOffset);
                    updateStatement.bindLong(6, KIND_EDDYSTONE_EID);
                    break;
            }

            executeSafeUpdateOrDelete(updateStatement);
        }
        cursor.close();
        updateStatement.close();

        db.execSQL("DROP TABLE " + EDDYSTONE_TABLE);
    }
}