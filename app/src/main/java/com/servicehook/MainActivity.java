package com.servicehook;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.servicehook.model.LocationSnapshot;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main UI for ServiceHook.
 *
 * Screens
 * ───────
 * • Module-active badge  – green if LSPosed has loaded this module, red otherwise.
 * • Snapshot card        – shows the currently stored snapshot (lat/lon, WiFi, cell).
 * • Action buttons       – Collect (grab current environment) and Activate/Deactivate.
 * • Intercept counters   – live counts from the ContentProvider (refreshed every 3 s).
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 42;
    private static final long STATS_REFRESH_INTERVAL_MS = 3_000L;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.READ_PHONE_STATE,
    };

    private static final Gson GSON = new Gson();
    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView tvModuleStatus;
    private TextView tvSnapshotInfo;
    private TextView tvStats;
    private Button   btnCollect;
    private Button   btnActivate;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isActive = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsRefreshRunnable = this::refreshStats;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvModuleStatus = findViewById(R.id.tv_module_status);
        tvSnapshotInfo = findViewById(R.id.tv_snapshot_info);
        tvStats        = findViewById(R.id.tv_stats);
        btnCollect     = findViewById(R.id.btn_collect);
        btnActivate    = findViewById(R.id.btn_activate);

        btnCollect.setOnClickListener(v -> collectSnapshot());
        btnActivate.setOnClickListener(v -> toggleActivation());

        checkModuleActive();
        loadAndDisplaySnapshot();
        requestPermissionsIfNeeded();

        // On startup, push any previously-saved snapshot to the live provider
        SnapshotManager.activateSavedSnapshot(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleStatsRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(statsRefreshRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ── Module-active detection ───────────────────────────────────────────────

    /**
     * Returns {@code false} normally.
     * LSPosed replaces the return value with {@code true} when the module is
     * active (see {@link HookMain#hookModuleActive}).
     */
    public static boolean isModuleActive() {
        return false;
    }

    private void checkModuleActive() {
        if (isModuleActive()) {
            tvModuleStatus.setText(R.string.status_active);
            tvModuleStatus.setBackgroundResource(R.drawable.bg_status_active);
        } else {
            tvModuleStatus.setText(R.string.status_inactive);
            tvModuleStatus.setBackgroundResource(R.drawable.bg_status_inactive);
        }
    }

    // ── Snapshot collect ──────────────────────────────────────────────────────

    private void collectSnapshot() {
        btnCollect.setEnabled(false);
        btnCollect.setText(R.string.btn_collecting);

        executor.execute(() -> {
            LocationSnapshot snap = SnapshotManager.collectSnapshot(getApplicationContext());
            uiHandler.post(() -> {
                if (snap != null) {
                    SnapshotManager.saveSnapshot(getApplicationContext(), snap);
                    displaySnapshot(snap);
                    Toast.makeText(this, R.string.toast_collected, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.toast_collect_failed, Toast.LENGTH_SHORT).show();
                }
                btnCollect.setEnabled(true);
                btnCollect.setText(R.string.btn_collect);
            });
        });
    }

    // ── Activation toggle ─────────────────────────────────────────────────────

    private void toggleActivation() {
        if (isActive) {
            SnapshotManager.deactivate(getApplicationContext());
            isActive = false;
            btnActivate.setText(R.string.btn_activate);
        } else {
            LocationSnapshot existing = SnapshotManager.loadSnapshot(getApplicationContext());
            if (existing == null) {
                Toast.makeText(this, R.string.toast_no_snapshot, Toast.LENGTH_SHORT).show();
                return;
            }
            SnapshotManager.saveSnapshot(getApplicationContext(), existing); // pushes to provider
            isActive = true;
            btnActivate.setText(R.string.btn_deactivate);
        }
        updateActivateButton();
    }

    private void updateActivateButton() {
        if (isActive) {
            btnActivate.setBackgroundResource(R.drawable.bg_btn_danger);
            btnActivate.setText(R.string.btn_deactivate);
        } else {
            btnActivate.setBackgroundResource(R.drawable.bg_btn_primary);
            btnActivate.setText(R.string.btn_activate);
        }
    }

    // ── Snapshot display ──────────────────────────────────────────────────────

    private void loadAndDisplaySnapshot() {
        LocationSnapshot snap = SnapshotManager.loadSnapshot(this);
        if (snap != null) displaySnapshot(snap);
        else tvSnapshotInfo.setText(R.string.no_snapshot);
    }

    private void displaySnapshot(LocationSnapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("📍 ").append(String.format(Locale.US, "%.6f, %.6f", s.latitude, s.longitude))
          .append("  ±").append(String.format(Locale.US, "%.1f m", s.accuracy)).append("\n");
        sb.append("🏔 ").append(String.format(Locale.US, "%.1f m", s.altitude)).append("\n");
        sb.append("🕐 ").append(SDF.format(new Date(s.captureTime))).append("\n");

        if (!s.wifiList.isEmpty()) {
            sb.append("\n📶 WiFi (").append(s.wifiList.size()).append(")\n");
            for (int i = 0; i < Math.min(3, s.wifiList.size()); i++) {
                LocationSnapshot.WifiEntry w = s.wifiList.get(i);
                sb.append("  ").append(w.ssid).append(" [").append(w.bssid).append("]")
                  .append(" ").append(w.level).append(" dBm\n");
            }
            if (s.wifiList.size() > 3)
                sb.append("  … ").append(s.wifiList.size() - 3).append(" more\n");
        }

        if (!s.cellList.isEmpty()) {
            sb.append("\n📡 Cell (").append(s.cellList.size()).append(")\n");
            for (int i = 0; i < Math.min(2, s.cellList.size()); i++) {
                LocationSnapshot.CellEntry c = s.cellList.get(i);
                sb.append("  ").append(c.type).append(" MCC=").append(c.mcc)
                  .append(" MNC=").append(c.mnc)
                  .append(" CID=").append(c.cid)
                  .append(" ").append(c.dbm).append(" dBm\n");
            }
        }

        tvSnapshotInfo.setText(sb.toString().trim());
    }

    // ── Stats refresh ─────────────────────────────────────────────────────────

    private void scheduleStatsRefresh() {
        uiHandler.removeCallbacks(statsRefreshRunnable);
        refreshStats();
    }

    private void refreshStats() {
        executor.execute(() -> {
            Bundle stats = SnapshotManager.queryStats(getApplicationContext());
            uiHandler.post(() -> {
                displayStats(stats);
                uiHandler.postDelayed(statsRefreshRunnable, STATS_REFRESH_INTERVAL_MS);
            });
        });
    }

    private void displayStats(Bundle stats) {
        if (stats == null) {
            tvStats.setText(R.string.stats_unavailable);
            return;
        }
        boolean active = stats.getBoolean(StatsProvider.KEY_ACTIVE, false);
        Bundle counts  = stats.getBundle(StatsProvider.KEY_COUNTS);

        StringBuilder sb = new StringBuilder();
        sb.append(getString(active ? R.string.hook_active : R.string.hook_inactive)).append("\n");

        if (counts != null) {
            for (String key : counts.keySet()) {
                sb.append("  ").append(key).append(": ").append(counts.getLong(key)).append("\n");
            }
        }
        tvStats.setText(sb.toString().trim());
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void requestPermissionsIfNeeded() {
        boolean allGranted = true;
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Re-check after user responds; no further action needed for core functionality
        checkModuleActive();
    }
}
