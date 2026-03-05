package com.servicehook;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.TelephonyManager;

import com.google.gson.Gson;
import com.servicehook.model.LocationSnapshot;

import java.util.List;

/**
 * Collects the current multi-dimensional environment snapshot (GPS, WiFi, cell,
 * sensors) and persists it via SharedPreferences and the StatsProvider.
 */
public class SnapshotManager {

    private static final String PREFS_NAME   = "sh_prefs";
    private static final String KEY_SNAPSHOT = "active_snapshot";

    private static final Gson GSON = new Gson();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Collect a best-effort snapshot from the device's current environment.
     * GPS is gathered from the last-known location (fast, no timeout).
     * Returns null if no location is available and the caller should retry.
     */
    public static LocationSnapshot collectSnapshot(Context ctx) {
        LocationSnapshot snap = new LocationSnapshot();
        snap.captureTime = System.currentTimeMillis();

        fillGps(ctx, snap);
        fillWifi(ctx, snap);
        fillCell(ctx, snap);
        fillSensors(ctx, snap);

        return snap;
    }

    /** Persist snapshot locally and push it to the StatsProvider. */
    public static void saveSnapshot(Context ctx, LocationSnapshot snap) {
        String json = GSON.toJson(snap);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_SNAPSHOT, json).apply();

        pushToProvider(ctx, json);
    }

    /** Load the last persisted snapshot from SharedPreferences (may be null). */
    public static LocationSnapshot loadSnapshot(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_SNAPSHOT, null);
        if (json == null) return null;
        try {
            return GSON.fromJson(json, LocationSnapshot.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Push current SharedPreferences snapshot to the live provider (call after app start). */
    public static void activateSavedSnapshot(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_SNAPSHOT, null);
        if (json != null) pushToProvider(ctx, json);
    }

    /** Tell the provider to stop simulating (no active snapshot). */
    public static void deactivate(Context ctx) {
        try {
            Bundle extras = new Bundle();
            ctx.getContentResolver().call(
                    Uri.parse("content://" + StatsProvider.AUTHORITY),
                    StatsProvider.CMD_CLEAR_SNAPSHOT, null, extras);
        } catch (Exception ignored) {
        }
    }

    /** Query the provider for live intercept stats. Returns null on failure. */
    public static Bundle queryStats(Context ctx) {
        try {
            return ctx.getContentResolver().call(
                    Uri.parse("content://" + StatsProvider.AUTHORITY),
                    StatsProvider.CMD_QUERY, null, null);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void pushToProvider(Context ctx, String json) {
        try {
            Bundle extras = new Bundle();
            extras.putString(StatsProvider.KEY_SNAPSHOT, json);
            ctx.getContentResolver().call(
                    Uri.parse("content://" + StatsProvider.AUTHORITY),
                    StatsProvider.CMD_SET_SNAPSHOT, null, extras);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("MissingPermission")
    private static void fillGps(Context ctx, LocationSnapshot snap) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            Location loc = null;
            // Prefer GPS provider, fall back to network
            for (String provider : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER}) {
                try {
                    loc = lm.getLastKnownLocation(provider);
                    if (loc != null) break;
                } catch (Exception ignored) {
                }
            }
            if (loc != null) {
                snap.latitude  = loc.getLatitude();
                snap.longitude = loc.getLongitude();
                snap.altitude  = loc.getAltitude();
                snap.accuracy  = loc.getAccuracy();
                snap.bearing   = loc.getBearing();
                snap.speed     = loc.getSpeed();
            }
        } catch (Exception ignored) {
        }
    }

    private static void fillWifi(Context ctx, LocationSnapshot snap) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;

            // Add currently connected network first
            @SuppressWarnings("deprecation")
            WifiInfo connected = wm.getConnectionInfo();
            if (connected != null && connected.getBSSID() != null
                    && !connected.getBSSID().equals("02:00:00:00:00:00")) {
                LocationSnapshot.WifiEntry entry = new LocationSnapshot.WifiEntry();
                entry.ssid     = sanitiseSsid(connected.getSSID());
                entry.bssid    = connected.getBSSID();
                entry.level    = connected.getRssi();
                entry.frequency = connected.getFrequency();
                entry.capabilities = "[WPA2-PSK-CCMP][ESS]";
                snap.wifiList.add(entry);
            }

            List<ScanResult> scanResults = wm.getScanResults();
            if (scanResults != null) {
                for (ScanResult sr : scanResults) {
                    if (snap.wifiList.size() >= 10) break; // cap at 10 APs
                    // Skip if we already added the connected network
                    if (!snap.wifiList.isEmpty()
                            && sr.BSSID.equalsIgnoreCase(snap.wifiList.get(0).bssid)) {
                        continue;
                    }
                    LocationSnapshot.WifiEntry entry = new LocationSnapshot.WifiEntry();
                    entry.ssid     = sr.SSID;
                    entry.bssid    = sr.BSSID;
                    entry.level    = sr.level;
                    entry.frequency = sr.frequency;
                    entry.capabilities = sr.capabilities;
                    snap.wifiList.add(entry);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("MissingPermission")
    private static void fillCell(Context ctx, LocationSnapshot snap) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;
            List<CellInfo> cells = tm.getAllCellInfo();
            if (cells == null) return;

            for (CellInfo ci : cells) {
                if (snap.cellList.size() >= 6) break;
                LocationSnapshot.CellEntry entry = parseCellInfo(ci);
                if (entry != null) snap.cellList.add(entry);
            }
        } catch (Exception ignored) {
        }
    }

    private static LocationSnapshot.CellEntry parseCellInfo(CellInfo ci) {
        LocationSnapshot.CellEntry e = new LocationSnapshot.CellEntry();
        try {
            if (ci instanceof CellInfoLte) {
                CellInfoLte lte = (CellInfoLte) ci;
                e.type  = "LTE";
                e.mcc   = parseMcc(lte.getCellIdentity().getMccString());
                e.mnc   = parseMnc(lte.getCellIdentity().getMncString());
                e.lac   = lte.getCellIdentity().getTac();
                e.cid   = lte.getCellIdentity().getCi();
                e.pci   = lte.getCellIdentity().getPci();
                e.earfcn = lte.getCellIdentity().getEarfcn();
                e.dbm   = lte.getCellSignalStrength().getDbm();
                return e;
            }
            if (ci instanceof CellInfoGsm) {
                CellInfoGsm gsm = (CellInfoGsm) ci;
                e.type  = "GSM";
                e.mcc   = parseMcc(gsm.getCellIdentity().getMccString());
                e.mnc   = parseMnc(gsm.getCellIdentity().getMncString());
                e.lac   = gsm.getCellIdentity().getLac();
                e.cid   = gsm.getCellIdentity().getCid();
                e.dbm   = gsm.getCellSignalStrength().getDbm();
                return e;
            }
            if (ci instanceof CellInfoWcdma) {
                CellInfoWcdma wcdma = (CellInfoWcdma) ci;
                e.type  = "UMTS";
                e.mcc   = parseMcc(wcdma.getCellIdentity().getMccString());
                e.mnc   = parseMnc(wcdma.getCellIdentity().getMncString());
                e.lac   = wcdma.getCellIdentity().getLac();
                e.cid   = wcdma.getCellIdentity().getCid();
                e.pci   = wcdma.getCellIdentity().getPsc();
                e.dbm   = wcdma.getCellSignalStrength().getDbm();
                return e;
            }
            // Android 11+ NR (5G)
            if (ci instanceof CellInfoNr) {
                CellInfoNr nr = (CellInfoNr) ci;
                e.type  = "NR";
                e.dbm   = nr.getCellSignalStrength().getDbm();
                return e;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void fillSensors(Context ctx, final LocationSnapshot snap) {
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;

        // Capture one reading from each sensor synchronously (best effort, 200 ms timeout)
        readSensorOnce(sm, Sensor.TYPE_ACCELEROMETER, 200, values -> {
            if (values.length >= 3) {
                snap.accelX = values[0];
                snap.accelY = values[1];
                snap.accelZ = values[2];
            }
        });
        readSensorOnce(sm, Sensor.TYPE_GYROSCOPE, 200, values -> {
            if (values.length >= 3) {
                snap.gyroX = values[0];
                snap.gyroY = values[1];
                snap.gyroZ = values[2];
            }
        });
        readSensorOnce(sm, Sensor.TYPE_MAGNETIC_FIELD, 200, values -> {
            if (values.length >= 3) {
                snap.magX = values[0];
                snap.magY = values[1];
                snap.magZ = values[2];
            }
        });
        readSensorOnce(sm, Sensor.TYPE_PRESSURE, 200, values -> {
            if (values.length >= 1) {
                snap.pressure = values[0];
            }
        });
    }

    private interface FloatConsumer {
        void accept(float[] values);
    }

    private static void readSensorOnce(SensorManager sm, int type, long timeoutMs,
                                       FloatConsumer consumer) {
        Sensor sensor = sm.getDefaultSensor(type);
        if (sensor == null) return;

        Object lock = new Object();
        boolean[] done = {false};

        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                consumer.accept(event.values.clone());
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {}
        };

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        try {
            synchronized (lock) {
                if (!done[0]) lock.wait(timeoutMs);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            sm.unregisterListener(listener);
        }
    }

    /** Strip surrounding quotes that WifiInfo.getSSID() adds on some versions. */
    private static String sanitiseSsid(String raw) {
        if (raw == null) return "";
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static int parseMcc(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static int parseMnc(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
