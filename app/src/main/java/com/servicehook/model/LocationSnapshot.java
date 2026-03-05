package com.servicehook.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable-friendly snapshot of the device's multi-dimensional positioning
 * environment: GPS, WiFi access-points, cell towers, and a basic sensor baseline.
 * Serialised to/from JSON via Gson for ContentProvider IPC and SharedPreferences storage.
 */
public class LocationSnapshot {

    // ── GPS ──────────────────────────────────────────────────────────────────
    public double latitude;
    public double longitude;
    public double altitude;      // metres
    public float  accuracy;      // metres (horizontal, 68th-percentile)
    public float  bearing;       // degrees [0, 360)
    public float  speed;         // m/s
    /** Wall-clock capture time (System.currentTimeMillis()). */
    public long   captureTime;

    // ── WiFi ─────────────────────────────────────────────────────────────────
    public List<WifiEntry> wifiList = new ArrayList<>();

    // ── Cell towers ──────────────────────────────────────────────────────────
    public List<CellEntry> cellList = new ArrayList<>();

    // ── Sensor baseline (captured at snapshot time) ───────────────────────
    /** Accelerometer x/y/z in m/s² (gravity included). */
    public float accelX, accelY, accelZ;
    /** Gyroscope x/y/z in rad/s. */
    public float gyroX, gyroY, gyroZ;
    /** Magnetometer x/y/z in µT. */
    public float magX, magY, magZ;
    /** Barometric pressure in hPa. */
    public float pressure;

    // ─────────────────────────────────────────────────────────────────────────

    public static class WifiEntry {
        public String ssid;
        public String bssid;
        public int    level;      // dBm, typically -30 … -90
        public int    frequency;  // MHz (2412, 5180, …)
        public String capabilities; // e.g. "[WPA2-PSK-CCMP][ESS]"
    }

    public static class CellEntry {
        public int    mcc;   // Mobile Country Code
        public int    mnc;   // Mobile Network Code
        public int    lac;   // Location Area Code (GSM/UMTS) / TAC (LTE)
        public int    cid;   // Cell Identity
        public int    pci;   // Physical Cell ID (LTE/NR)
        public String type;  // "LTE", "NR", "UMTS", "GSM"
        public int    dbm;   // Signal strength in dBm
        public int    earfcn; // Absolute RF channel number (LTE)
    }
}
