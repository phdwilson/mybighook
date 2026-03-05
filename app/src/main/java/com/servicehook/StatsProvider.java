package com.servicehook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ContentProvider used as the sole IPC channel between:
 *   - the UI process (MainActivity / SnapshotManager) – sets the active snapshot
 *   - hook code injected into target-app processes – reads the snapshot and reports intercepts
 *
 * No files or XSharedPreferences are used; all data lives in this provider process.
 *
 * UID policy
 * ──────────
 * Write operations (setActiveSnapshot, clearSnapshot) are restricted to:
 *   UID 0   – root shell
 *   UID 1000 – system_server
 *   own UID – our UI process
 *
 * Read operations (getActiveSnapshot, report, query) are deliberately open so
 * that hook code running inside arbitrary target-app processes can reach them
 * via Binder without needing any special permission.
 */
public class StatsProvider extends ContentProvider {

    public static final String AUTHORITY     = "com.servicehook.stats";

    // ── Command tokens ────────────────────────────────────────────────────────
    /** Set the active simulation snapshot (admin only). */
    public static final String CMD_SET_SNAPSHOT   = "setActiveSnapshot";
    /** Clear the active simulation snapshot (admin only). */
    public static final String CMD_CLEAR_SNAPSHOT = "clearSnapshot";
    /** Read the active snapshot JSON. */
    public static final String CMD_GET_SNAPSHOT   = "getActiveSnapshot";
    /** Increment an intercept counter (called by hook code). */
    public static final String CMD_REPORT         = "report";
    /** Read all counters + active status (admin only). */
    public static final String CMD_QUERY          = "query";

    // ── Bundle keys ───────────────────────────────────────────────────────────
    public static final String KEY_SNAPSHOT = "snapshot";
    public static final String KEY_COUNT    = "count";
    public static final String KEY_ACTIVE   = "active";
    public static final String KEY_COUNTS   = "counts";

    // ── State (lives in the UI process) ──────────────────────────────────────
    private volatile String activeSnapshotJson = null;

    /** Per-category intercept counters, e.g. "gps", "wifi", "cell", "sensor". */
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (method == null) return null;

        Bundle result = new Bundle();

        switch (method) {
            // ── Admin-only write operations ───────────────────────────────────
            case CMD_SET_SNAPSHOT: {
                requireAdminUid();
                if (extras != null && extras.containsKey(KEY_SNAPSHOT)) {
                    activeSnapshotJson = extras.getString(KEY_SNAPSHOT);
                    result.putBoolean("success", true);
                } else {
                    result.putBoolean("success", false);
                }
                break;
            }
            case CMD_CLEAR_SNAPSHOT: {
                requireAdminUid();
                activeSnapshotJson = null;
                // Reset counters when snapshot is cleared
                counters.clear();
                result.putBoolean("success", true);
                break;
            }

            // ── Open read / increment operations (used by hook code) ──────────
            case CMD_GET_SNAPSHOT: {
                String snap = activeSnapshotJson;
                if (snap != null) {
                    result.putString(KEY_SNAPSHOT, snap);
                    result.putBoolean(KEY_ACTIVE, true);
                } else {
                    result.putBoolean(KEY_ACTIVE, false);
                }
                break;
            }
            case CMD_REPORT: {
                String category = (arg != null && !arg.isEmpty()) ? arg : "general";
                AtomicLong counter = counters.computeIfAbsent(category, k -> new AtomicLong(0));
                result.putLong(KEY_COUNT, counter.incrementAndGet());
                break;
            }

            // ── Admin-only read of full stats ─────────────────────────────────
            case CMD_QUERY: {
                requireAdminUid();
                result.putBoolean(KEY_ACTIVE, activeSnapshotJson != null);
                Bundle counts = new Bundle();
                for (ConcurrentHashMap.Entry<String, AtomicLong> e : counters.entrySet()) {
                    counts.putLong(e.getKey(), e.getValue().get());
                }
                result.putBundle(KEY_COUNTS, counts);
                break;
            }

            default:
                break;
        }

        return result;
    }

    // ── Unused ContentProvider abstract methods ───────────────────────────────

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Throws {@link SecurityException} if the Binder caller is not root (UID 0),
     * system_server (UID 1000), or our own app process.
     */
    private void requireAdminUid() {
        int callerUid = Binder.getCallingUid();
        int ownUid    = Process.myUid();
        if (callerUid != 0 && callerUid != 1000 && callerUid != ownUid) {
            throw new SecurityException("StatsProvider: access denied for UID " + callerUid);
        }
    }
}
