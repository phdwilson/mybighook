package com.servicehook;

import android.hardware.Sensor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import com.google.gson.Gson;
import com.servicehook.model.LocationSnapshot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module entry point.
 *
 * Hooks injected into every target-app process replace multi-dimensional
 * location data (GPS, WiFi, cell, sensors) with the values stored in the
 * active {@link LocationSnapshot} so that no app — even one that
 * cross-correlates multiple signals — can determine the real position.
 *
 * Design principles
 * ─────────────────
 * • No mock/test flags left in returned objects.
 * • All timestamps remain real (prevents replay-detection).
 * • Signals carry physics-faithful fluctuations (sinusoidal drift +
 *   bounded Gaussian noise) so they never look frozen or artificially smooth.
 * • Snapshot is cached for {@link #SNAPSHOT_CACHE_MS} ms; ContentProvider
 *   calls are therefore rare and will not stall the target app.
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TAG = "SH";
    private static final long SNAPSHOT_CACHE_MS = 5_000L;
    private static final Gson GSON = new Gson();

    // ── Per-process state (each hooked process has its own instance) ──────────
    private android.content.Context appCtx = null;
    private volatile LocationSnapshot cachedSnap = null;
    private volatile long lastCacheRefresh = 0L;
    private final Random rng = new Random();

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;

        // Hook our own package just to signal that the module is active
        if ("com.servicehook".equals(lpparam.packageName)) {
            hookModuleActive(lpparam.classLoader);
            return;
        }

        // Skip system_server – hooks there risk system-wide instability
        if ("android".equals(lpparam.packageName)) return;

        // Capture context as early as possible so later hooks can reach the provider
        hookApplicationOnCreate(lpparam.classLoader);

        // Location hooks
        hookLocationMethods(lpparam.classLoader);
        hookLocationManager(lpparam.classLoader);

        // WiFi hooks
        hookWifiManager(lpparam.classLoader);
        hookWifiInfo(lpparam.classLoader);

        // Cell-tower hooks
        hookTelephonyManager(lpparam.classLoader);
        hookPhoneStateListener(lpparam.classLoader);
        hookTelephonyCallback(lpparam.classLoader);   // Android 12+

        // Sensor noise
        hookSensorDispatch(lpparam.classLoader);
    }

    // ── Module-active detection ───────────────────────────────────────────────

    private void hookModuleActive(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.servicehook.MainActivity", cl,
                    "isModuleActive",
                    XC_MethodReplacement.returnConstant(true));
        } catch (Throwable ignored) {
        }
    }

    // ── Context capture ───────────────────────────────────────────────────────

    private void hookApplicationOnCreate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Application", cl, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (appCtx == null) {
                                appCtx = (android.app.Application) p.thisObject;
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    // ── Snapshot retrieval with 5-second cache ────────────────────────────────

    /**
     * Returns the active snapshot, or null if none is configured.
     * Refreshes from the ContentProvider at most once per {@link #SNAPSHOT_CACHE_MS}.
     */
    private LocationSnapshot getSnapshot() {
        long now = SystemClock.elapsedRealtime();
        if (cachedSnap != null && (now - lastCacheRefresh) < SNAPSHOT_CACHE_MS) {
            return cachedSnap;
        }
        android.content.Context ctx = appCtx;
        if (ctx == null) {
            // Try the Android helper as a fallback
            try {
                ctx = (android.content.Context)
                        XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass(
                                        "android.app.ActivityThread", null),
                                "currentApplication");
            } catch (Throwable ignored) {
            }
        }
        if (ctx == null) return cachedSnap; // keep previous cache

        try {
            Bundle result = ctx.getContentResolver().call(
                    Uri.parse("content://" + StatsProvider.AUTHORITY),
                    StatsProvider.CMD_GET_SNAPSHOT, null, null);
            if (result != null && result.getBoolean(StatsProvider.KEY_ACTIVE, false)) {
                String json = result.getString(StatsProvider.KEY_SNAPSHOT);
                if (json != null) cachedSnap = GSON.fromJson(json, LocationSnapshot.class);
            } else {
                cachedSnap = null;
            }
            lastCacheRefresh = now;
        } catch (Throwable t) {
            // Provider unavailable; keep stale cache so we don't spam Binder
            lastCacheRefresh = now;
        }
        return cachedSnap;
    }

    /** Send an async increment to the provider (fire-and-forget, best effort). */
    private void report(String category) {
        android.content.Context ctx = appCtx;
        if (ctx == null) return;
        try {
            ctx.getContentResolver().call(
                    Uri.parse("content://" + StatsProvider.AUTHORITY),
                    StatsProvider.CMD_REPORT, category, null);
        } catch (Throwable ignored) {
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GPS / Location hooks
    // ─────────────────────────────────────────────────────────────────────────

    private void hookLocationMethods(ClassLoader cl) {
        // ── Coordinate getters ────────────────────────────────────────────────
        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl,
                    "getLatitude", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null) return;
                            p.setResult(s.latitude + gpsNoise(0));
                            report("gps");
                        }
                    });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl,
                    "getLongitude", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null) return;
                            p.setResult(s.longitude + gpsNoise(1));
                        }
                    });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl,
                    "getAltitude", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null) return;
                            p.setResult(s.altitude + altNoise());
                        }
                    });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl,
                    "getAccuracy", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null) return;
                            float base = (s.accuracy > 0) ? s.accuracy : 5.0f;
                            p.setResult(base + (float)(rng.nextGaussian() * 0.5));
                        }
                    });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl,
                    "getBearing", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null) return;
                            p.setResult(s.bearing);
                        }
                    });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl,
                    "getSpeed", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null) return;
                            p.setResult(s.speed);
                        }
                    });
        } catch (Throwable ignored) {}

        // ── Anti-mock-detection ───────────────────────────────────────────────
        // getTime/getElapsedRealtimeNanos intentionally NOT faked — keep real clock
        // to avoid replay-detection.

        // isFromMockProvider() — legacy (API 18–30)
        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl,
                    "isFromMockProvider",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE));
        } catch (Throwable ignored) {}

        // isMock() — API 31+
        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl,
                    "isMock",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE));
        } catch (Throwable ignored) {}

        // getExtras() — inject satellite count so it looks like a real fix
        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl,
                    "getExtras", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (getSnapshot() == null) return;
                            android.os.Bundle extras = (android.os.Bundle) p.getResult();
                            if (extras == null) extras = new android.os.Bundle();
                            else extras = new android.os.Bundle(extras);
                            // Typical GPS fix has 8–14 satellites
                            extras.putInt("satellites", 10 + rng.nextInt(5));
                            p.setResult(extras);
                        }
                    });
        } catch (Throwable ignored) {}

        // getProvider() — always report "gps", not "network" or "mock"
        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl,
                    "getProvider", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (getSnapshot() == null) return;
                            p.setResult("gps");
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private void hookLocationManager(ClassLoader cl) {
        // getLastKnownLocation
        try {
            XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", cl,
                    "getLastKnownLocation", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null) return;
                            android.location.Location loc = buildFakeLocation(s);
                            p.setResult(loc);
                        }
                    });
        } catch (Throwable ignored) {}

        // isProviderEnabled — always report GPS as enabled so apps don't pop a dialog
        try {
            XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", cl,
                    "isProviderEnabled", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (getSnapshot() == null) return;
                            String provider = (String) p.args[0];
                            if ("gps".equals(provider) || "fused".equals(provider)) {
                                p.setResult(true);
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }

    /** Build a fully populated Location object from the snapshot. */
    private android.location.Location buildFakeLocation(LocationSnapshot s) {
        android.location.Location loc = new android.location.Location("gps");
        loc.setLatitude(s.latitude + gpsNoise(0));
        loc.setLongitude(s.longitude + gpsNoise(1));
        loc.setAltitude(s.altitude + altNoise());
        float base = (s.accuracy > 0) ? s.accuracy : 5.0f;
        loc.setAccuracy(base + (float)(rng.nextGaussian() * 0.5));
        loc.setBearing(s.bearing);
        loc.setSpeed(s.speed);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        android.os.Bundle extras = new android.os.Bundle();
        extras.putInt("satellites", 10 + rng.nextInt(5));
        loc.setExtras(extras);
        // Clear mock flag via reflection
        clearMockFlag(loc);
        return loc;
    }

    private static void clearMockFlag(android.location.Location loc) {
        try {
            Field f = android.location.Location.class.getDeclaredField("mIsFromMockProvider");
            f.setAccessible(true);
            f.set(loc, false);
        } catch (Throwable ignored) {}
        try {
            // API 31+ field name
            Field f = android.location.Location.class.getDeclaredField("mMock");
            f.setAccessible(true);
            f.set(loc, false);
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WiFi hooks
    // ─────────────────────────────────────────────────────────────────────────

    private void hookWifiManager(ClassLoader cl) {
        // getScanResults() → return fake AP list
        try {
            XposedHelpers.findAndHookMethod(
                    "android.net.wifi.WifiManager", cl,
                    "getScanResults",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null || s.wifiList.isEmpty()) return;
                            List<android.net.wifi.ScanResult> results = buildFakeScanResults(s);
                            if (!results.isEmpty()) {
                                p.setResult(results);
                                report("wifi");
                            }
                        }
                    });
        } catch (Throwable ignored) {}

        // getConnectionInfo() → match connected AP to snapshot's first entry
        try {
            XposedHelpers.findAndHookMethod(
                    "android.net.wifi.WifiManager", cl,
                    "getConnectionInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null || s.wifiList.isEmpty()) return;
                            // Patch the returned WifiInfo in-place via reflection
                            Object info = p.getResult();
                            if (info == null) return;
                            LocationSnapshot.WifiEntry first = s.wifiList.get(0);
                            patchWifiInfo(info, first);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    /** Hook individual WifiInfo getters as a belt-and-suspenders approach. */
    private void hookWifiInfo(ClassLoader cl) {
        hookWifiGetter(cl, "getSSID", () -> {
            LocationSnapshot s = getSnapshot();
            if (s == null || s.wifiList.isEmpty()) return null;
            return "\"" + s.wifiList.get(0).ssid + "\"";
        });
        hookWifiGetter(cl, "getBSSID", () -> {
            LocationSnapshot s = getSnapshot();
            if (s == null || s.wifiList.isEmpty()) return null;
            return s.wifiList.get(0).bssid;
        });
        hookWifiGetter(cl, "getRssi", () -> {
            LocationSnapshot s = getSnapshot();
            if (s == null || s.wifiList.isEmpty()) return null;
            return s.wifiList.get(0).level + wifiNoise(s.wifiList.get(0).bssid);
        });
        hookWifiGetter(cl, "getFrequency", () -> {
            LocationSnapshot s = getSnapshot();
            if (s == null || s.wifiList.isEmpty()) return null;
            return s.wifiList.get(0).frequency;
        });
    }

    private void hookWifiGetter(ClassLoader cl, String methodName, java.util.concurrent.Callable<Object> supplier) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.net.wifi.WifiInfo", cl, methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            Object v = supplier.call();
                            if (v != null) p.setResult(v);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private List<android.net.wifi.ScanResult> buildFakeScanResults(LocationSnapshot s) {
        List<android.net.wifi.ScanResult> list = new ArrayList<>();
        long tsUs = SystemClock.elapsedRealtime() * 1000L;

        for (LocationSnapshot.WifiEntry entry : s.wifiList) {
            android.net.wifi.ScanResult sr = buildScanResult(
                    entry.ssid, entry.bssid,
                    entry.capabilities != null ? entry.capabilities : "[WPA2-PSK-CCMP][ESS]",
                    entry.level + wifiNoise(entry.bssid),
                    entry.frequency, tsUs);
            if (sr != null) list.add(sr);
        }
        return list;
    }

    private android.net.wifi.ScanResult buildScanResult(String ssid, String bssid,
                                                         String caps, int level,
                                                         int freq, long tsUs) {
        try {
            android.net.wifi.ScanResult sr = new android.net.wifi.ScanResult();
            setField(sr, "SSID", ssid);
            setField(sr, "BSSID", bssid);
            setField(sr, "capabilities", caps);
            setField(sr, "level", level);
            setField(sr, "frequency", freq);
            setField(sr, "timestamp", tsUs);
            return sr;
        } catch (Throwable ignored) {}

        // Fallback: reflective constructor (API varies)
        try {
            Class<?> cls = android.net.wifi.ScanResult.class;
            Constructor<?> ctor = cls.getDeclaredConstructor(
                    String.class, String.class, String.class, int.class, int.class, long.class);
            ctor.setAccessible(true);
            return (android.net.wifi.ScanResult) ctor.newInstance(ssid, bssid, caps, level, freq, tsUs);
        } catch (Throwable ignored) {}
        return null;
    }

    private void patchWifiInfo(Object info, LocationSnapshot.WifiEntry entry) {
        try { setField(info, "mSSID", entry.ssid); } catch (Throwable ignored) {}
        try { setField(info, "mBSSID", entry.bssid); } catch (Throwable ignored) {}
        try { setField(info, "mRssi", entry.level + wifiNoise(entry.bssid)); } catch (Throwable ignored) {}
        try { setField(info, "mFrequency", entry.frequency); } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cell-tower hooks
    // ─────────────────────────────────────────────────────────────────────────

    private void hookTelephonyManager(ClassLoader cl) {
        // getAllCellInfo()
        try {
            XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager", cl,
                    "getAllCellInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null || s.cellList.isEmpty()) return;
                            List<android.telephony.CellInfo> fake = buildFakeCellInfoList(s);
                            if (!fake.isEmpty()) {
                                p.setResult(fake);
                                report("cell");
                            }
                        }
                    });
        } catch (Throwable ignored) {}

        // getNetworkOperator() → "MCCMNC"
        try {
            XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager", cl,
                    "getNetworkOperator",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null || s.cellList.isEmpty()) return;
                            LocationSnapshot.CellEntry c = s.cellList.get(0);
                            p.setResult(String.format("%03d%02d", c.mcc, c.mnc));
                        }
                    });
        } catch (Throwable ignored) {}
    }

    /**
     * Hook the legacy PhoneStateListener so that asynchronous onCellInfoChanged
     * callbacks also return fake data.
     */
    private void hookPhoneStateListener(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.telephony.PhoneStateListener", cl,
                    "onCellInfoChanged", List.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null || s.cellList.isEmpty()) return;
                            List<android.telephony.CellInfo> fake = buildFakeCellInfoList(s);
                            if (!fake.isEmpty()) p.args[0] = fake;
                        }
                    });
        } catch (Throwable ignored) {}
    }

    /**
     * Hook TelephonyManager.registerTelephonyCallback (Android 12+ / API 31+).
     * We wrap the supplied TelephonyCallback to intercept CellInfo updates.
     */
    private void hookTelephonyCallback(ClassLoader cl) {
        try {
            Class<?> callbackCls = XposedHelpers.findClass(
                    "android.telephony.TelephonyCallback", cl);
            Class<?> cellInfoListenerCls = XposedHelpers.findClass(
                    "android.telephony.TelephonyCallback$CellInfoListener", cl);

            // Hook the interface method that delivers the update
            XposedHelpers.findAndHookMethod(cellInfoListenerCls, "onCellInfoChanged",
                    List.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            LocationSnapshot s = getSnapshot();
                            if (s == null || s.cellList.isEmpty()) return;
                            List<android.telephony.CellInfo> fake = buildFakeCellInfoList(s);
                            if (!fake.isEmpty()) p.args[0] = fake;
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private List<android.telephony.CellInfo> buildFakeCellInfoList(LocationSnapshot s) {
        List<android.telephony.CellInfo> list = new ArrayList<>();
        for (LocationSnapshot.CellEntry entry : s.cellList) {
            android.telephony.CellInfo ci = buildFakeCellInfo(entry);
            if (ci != null) list.add(ci);
        }
        return list;
    }

    /**
     * Build a {@link android.telephony.CellInfoLte} (or other type) via reflection
     * to remain compatible across Android versions whose constructors differ.
     */
    private android.telephony.CellInfo buildFakeCellInfo(LocationSnapshot.CellEntry entry) {
        try {
            if ("LTE".equals(entry.type) || entry.type == null) {
                return buildCellInfoLte(entry);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private android.telephony.CellInfoLte buildCellInfoLte(LocationSnapshot.CellEntry e) {
        try {
            Class<?> cidCls = Class.forName("android.telephony.CellIdentityLte");
            Class<?> ssCls  = Class.forName("android.telephony.CellSignalStrengthLte");
            Class<?> ciCls  = android.telephony.CellInfoLte.class;

            // ── CellIdentityLte — try constructors from most to least detailed ──
            Object identity = null;
            // Android 9+ (10-param)
            try {
                Constructor<?> ctor = cidCls.getDeclaredConstructor(
                        int.class, int.class, int.class, int.class, int.class,
                        int.class, int.class, String.class, String.class, String.class);
                ctor.setAccessible(true);
                identity = ctor.newInstance(
                        e.mcc, e.mnc, e.cid, e.pci, e.lac,
                        e.earfcn, -1, "", "", "");
            } catch (Throwable ignored) {}

            // Android 8 (5-param)
            if (identity == null) {
                try {
                    Constructor<?> ctor = cidCls.getDeclaredConstructor(
                            int.class, int.class, int.class, int.class, int.class);
                    ctor.setAccessible(true);
                    identity = ctor.newInstance(e.mcc, e.mnc, e.cid, e.pci, e.lac);
                } catch (Throwable ignored) {}
            }

            // Android 12+ (String mcc/mnc)
            if (identity == null) {
                try {
                    Constructor<?> ctor = cidCls.getDeclaredConstructor(
                            String.class, String.class, int.class, int.class, int.class,
                            int.class, int[].class, int.class, String.class, String.class,
                            java.util.List.class, Object.class);
                    ctor.setAccessible(true);
                    identity = ctor.newInstance(
                            String.format("%03d", e.mcc),
                            String.format("%02d", e.mnc),
                            e.cid, e.pci, e.lac,
                            e.earfcn, new int[]{}, -1, "", "",
                            new ArrayList<>(), null);
                } catch (Throwable ignored) {}
            }

            if (identity == null) return null;

            // ── CellSignalStrengthLte ─────────────────────────────────────────
            Object ss = null;
            // Try no-arg constructor then patch fields
            try {
                Constructor<?> ctor = ssCls.getDeclaredConstructor();
                ctor.setAccessible(true);
                ss = ctor.newInstance();
            } catch (Throwable ignored) {}

            if (ss == null) {
                // 7-param constructor (older Android)
                try {
                    Constructor<?> ctor = ssCls.getDeclaredConstructor(
                            int.class, int.class, int.class, int.class,
                            int.class, int.class, int.class);
                    ctor.setAccessible(true);
                    int dbm = e.dbm + cellNoise();
                    ss = ctor.newInstance(dbm, dbm + 10, 0, 0, 0, 0, 3);
                } catch (Throwable ignored) {}
            }

            if (ss != null) {
                int dbm = e.dbm + cellNoise();
                setFieldIfExists(ss, "mRsrp", dbm);
                setFieldIfExists(ss, "mRsrq", -10);
                setFieldIfExists(ss, "mSignalStrength", Math.min(31, (dbm + 140) / 2));
            }

            // ── CellInfoLte ───────────────────────────────────────────────────
            android.telephony.CellInfoLte ci;
            try {
                Constructor<?> ctor = ciCls.getDeclaredConstructor();
                ctor.setAccessible(true);
                ci = (android.telephony.CellInfoLte) ctor.newInstance();
            } catch (Throwable t) {
                return null;
            }

            // Set registered + timestamp
            setFieldIfExists(ci, "mRegistered", true);
            setFieldIfExists(ci, "mTimeStamp", SystemClock.elapsedRealtimeNanos());

            // Set identity
            Field fId = findField(ciCls, "mCellIdentityLte");
            if (fId != null) { fId.setAccessible(true); fId.set(ci, identity); }

            // Set signal strength
            if (ss != null) {
                Field fSs = findField(ciCls, "mCellSignalStrengthLte");
                if (fSs != null) { fSs.setAccessible(true); fSs.set(ci, ss); }
            }

            return ci;

        } catch (Throwable t) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sensor noise injection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hook {@code SystemSensorManager$SensorEventQueue.dispatchSensorEvent} so
     * that every sensor reading gets physics-based micro-noise added before it
     * reaches the app. This prevents bitwise-identical fingerprinting of sensor
     * data while maintaining physical plausibility.
     *
     * Noise parameters by sensor type:
     *  TYPE_ACCELEROMETER  (1) – ±0.04 m/s²  sinusoidal + Gaussian (breathing)
     *  TYPE_GYROSCOPE      (4) – ±0.002 rad/s sinusoidal (zero drift)
     *  TYPE_MAGNETIC_FIELD (2) – ±0.3 µT      sinusoidal (electrical interference)
     *  TYPE_PRESSURE       (6) – ±0.05 hPa    slow sinusoidal
     *  others                  – ±0.005        minimal
     */
    private void hookSensorDispatch(ClassLoader cl) {
        // Build a handle→type lookup map when sensors are registered
        // so we can apply type-appropriate noise in dispatchSensorEvent
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.SystemSensorManager", cl,
                    "registerListenerImpl",
                    android.hardware.SensorEventListener.class,
                    android.hardware.Sensor.class,
                    int.class,
                    android.os.Handler.class,
                    Object.class,
                    int.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            android.hardware.Sensor sensor = (android.hardware.Sensor) p.args[1];
                            if (sensor != null) {
                                int handle = getSensorHandle(sensor);
                                sensorHandleTypeMap.put(handle, sensor.getType());
                            }
                        }
                    });
        } catch (Throwable ignored) {}

        // Inject noise in the delivery method
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.SystemSensorManager$SensorEventQueue", cl,
                    "dispatchSensorEvent",
                    int.class, float[].class, int.class, long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (getSnapshot() == null) return; // only when active
                            float[] values = (float[]) p.args[1];
                            if (values == null || values.length == 0) return;
                            int handle = (int) p.args[0];
                            int type = sensorHandleTypeMap.getOrDefault(handle, -1);
                            addSensorNoise(values, type);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> sensorHandleTypeMap
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** Retrieve the internal handle of a Sensor; falls back to hashCode if @hide API is absent. */
    private static int getSensorHandle(android.hardware.Sensor sensor) {
        try {
            java.lang.reflect.Method m = android.hardware.Sensor.class.getDeclaredMethod("getHandle");
            m.setAccessible(true);
            return (int) m.invoke(sensor);
        } catch (Throwable ignored) {
            return sensor.hashCode();
        }
    }

    private void addSensorNoise(float[] v, int type) {
        double t = SystemClock.elapsedRealtime() / 1000.0; // seconds
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER: {
                // Simulate breathing-induced micro-movement (~0.03 Hz, 0.04 m/s²)
                double drift = 0.04 * Math.sin(t * 0.03 * 2 * Math.PI);
                for (int i = 0; i < Math.min(3, v.length); i++) {
                    v[i] += drift * Math.cos(i * 1.2) + (float)(rng.nextGaussian() * 0.005);
                }
                break;
            }
            case Sensor.TYPE_GYROSCOPE: {
                // Zero-rate offset drift typical of MEMS gyros (~0.001 rad/s)
                double drift = 0.001 * Math.sin(t * 0.01 * 2 * Math.PI);
                for (int i = 0; i < Math.min(3, v.length); i++) {
                    v[i] += drift + (float)(rng.nextGaussian() * 0.0003);
                }
                break;
            }
            case Sensor.TYPE_MAGNETIC_FIELD: {
                double drift = 0.3 * Math.sin(t * 0.05 * 2 * Math.PI);
                for (int i = 0; i < Math.min(3, v.length); i++) {
                    v[i] += drift * Math.sin(i + 1.0) + (float)(rng.nextGaussian() * 0.05);
                }
                break;
            }
            case Sensor.TYPE_PRESSURE: {
                // Barometric micro-fluctuation (~0.02 hPa, period ~10 s)
                v[0] += 0.02 * Math.sin(t * 0.1 * 2 * Math.PI)
                        + (float)(rng.nextGaussian() * 0.005);
                break;
            }
            default: {
                // Generic small noise to break fingerprinting
                for (int i = 0; i < v.length; i++) {
                    v[i] += (float)(rng.nextGaussian() * 0.002);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Noise generators
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GPS coordinate noise: slow sinusoidal drift (period ~90 s, ~1 m amplitude)
     * plus tiny Gaussian jitter, imitating atmospheric/multipath effects.
     */
    private double gpsNoise(int axis) {
        double t  = SystemClock.elapsedRealtime() / 1000.0;
        double period = (axis == 0) ? 87.3 : 113.7; // slightly different per-axis
        double sin  = 0.000009 * Math.sin(t * 2 * Math.PI / period);
        double gaus = rng.nextGaussian() * 0.000002;
        return sin + gaus;
    }

    /** Altitude noise: ±0.3 m sinusoidal + 0.05 m Gaussian. */
    private double altNoise() {
        double t = SystemClock.elapsedRealtime() / 1000.0;
        return 0.3 * Math.sin(t * 2 * Math.PI / 60.0) + rng.nextGaussian() * 0.05;
    }

    /**
     * WiFi RSSI noise: each AP gets a deterministic base phase derived from its
     * BSSID, preventing all APs from fluctuating in sync (which looks artificial).
     */
    private int wifiNoise(String bssid) {
        double t = SystemClock.elapsedRealtime() / 1000.0;
        double phase = (bssid != null ? (bssid.hashCode() & 0xFFFFF) : 0) * 0.0001;
        int sin  = (int)(2.0 * Math.sin(t * 2 * Math.PI / 18.0 + phase));
        int gaus = (int)(rng.nextGaussian() * 0.8);
        return sin + gaus;
    }

    /** Cell signal noise: ±3 dBm sinusoidal (period ~25 s) + 1 dBm Gaussian. */
    private int cellNoise() {
        double t = SystemClock.elapsedRealtime() / 1000.0;
        return (int)(3.0 * Math.sin(t * 2 * Math.PI / 25.0) + rng.nextGaussian());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reflection utilities
    // ─────────────────────────────────────────────────────────────────────────

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private static void setFieldIfExists(Object obj, String name, Object value) {
        Field f = findField(obj.getClass(), name);
        if (f == null) return;
        try {
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable ignored) {}
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }
}
