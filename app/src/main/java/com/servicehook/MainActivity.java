package com.servicehook;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.servicehook.model.LocationSnapshot;
import com.servicehook.model.SnapshotProfile;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main UI for ServiceHook.
 *
 * Screens
 * ───────
 * • Module-active badge  – green if LSPosed has loaded this module, red otherwise.
 * • Snapshot card        – shows the currently stored snapshot (lat/lon, WiFi, cell).
 * • Action buttons       – Collect (grab current environment) and Activate/Deactivate.
 * • Saved profiles       – list of named profiles with activate/delete controls.
 * • Export / Import      – backup and restore profiles to encrypted files.
 * • Intercept counters   – live counts from the ContentProvider (refreshed every 3 s).
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS  = 42;
    private static final int REQ_EXPORT_FILE  = 100;
    private static final int REQ_IMPORT_FILE  = 101;
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
    private TextView tvNoProfiles;
    private Button   btnCollect;
    private Button   btnActivate;
    private Button   btnExport;
    private Button   btnImport;
    private LinearLayout llProfilesContainer;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isActive = false;
    private String  activeProfileId = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsRefreshRunnable = this::refreshStats;
    /** Guard against concurrent snapshot collection from rapid button taps. */
    private final AtomicBoolean collecting = new AtomicBoolean(false);

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvModuleStatus      = findViewById(R.id.tv_module_status);
        tvSnapshotInfo      = findViewById(R.id.tv_snapshot_info);
        tvStats             = findViewById(R.id.tv_stats);
        tvNoProfiles        = findViewById(R.id.tv_no_profiles);
        btnCollect          = findViewById(R.id.btn_collect);
        btnActivate         = findViewById(R.id.btn_activate);
        btnExport           = findViewById(R.id.btn_export);
        btnImport           = findViewById(R.id.btn_import);
        llProfilesContainer = findViewById(R.id.ll_profiles_container);

        btnCollect.setOnClickListener(v -> collectSnapshot());
        btnActivate.setOnClickListener(v -> toggleActivation());
        btnExport.setOnClickListener(v -> startExport());
        btnImport.setOnClickListener(v -> startImport());

        checkModuleActive();
        loadAndDisplaySnapshot();
        refreshProfileList();
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
        if (!collecting.compareAndSet(false, true)) return; // debounce
        btnCollect.setEnabled(false);
        btnCollect.setText(R.string.btn_collecting);

        executor.execute(() -> {
            try {
                LocationSnapshot snap = SnapshotManager.collectSnapshot(getApplicationContext());
                uiHandler.post(() -> {
                    collecting.set(false);
                    if (snap != null) {
                        SnapshotManager.saveSnapshot(getApplicationContext(), snap);
                        displaySnapshot(snap);
                        showSaveProfileDialog(snap);
                    } else {
                        Toast.makeText(this, R.string.toast_collect_failed, Toast.LENGTH_SHORT).show();
                    }
                    btnCollect.setEnabled(true);
                    btnCollect.setText(R.string.btn_collect);
                });
            } catch (Throwable t) {
                uiHandler.post(() -> {
                    collecting.set(false);
                    Toast.makeText(this, R.string.toast_collect_failed, Toast.LENGTH_SHORT).show();
                    btnCollect.setEnabled(true);
                    btnCollect.setText(R.string.btn_collect);
                });
            }
        });
    }

    /**
     * After collecting a snapshot, prompt the user to name and save it as a profile.
     */
    private void showSaveProfileDialog(LocationSnapshot snap) {
        EditText input = new EditText(this);
        input.setHint(R.string.dialog_save_hint);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_save_title)
                .setView(input)
                .setPositiveButton(R.string.dialog_save_ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = SDF.format(new Date(snap.captureTime));
                    }
                    SnapshotProfile profile = new SnapshotProfile(name, snap);
                    ProfileManager.addProfile(getApplicationContext(), profile);
                    Toast.makeText(this, R.string.toast_profile_saved, Toast.LENGTH_SHORT).show();
                    refreshProfileList();
                })
                .setNegativeButton(R.string.dialog_save_cancel, null)
                .show();
    }

    // ── Activation toggle ─────────────────────────────────────────────────────

    private void toggleActivation() {
        if (isActive) {
            SnapshotManager.deactivate(getApplicationContext());
            isActive = false;
            activeProfileId = null;
            btnActivate.setText(R.string.btn_activate);
            refreshProfileList();
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

    private void activateProfile(SnapshotProfile profile) {
        SnapshotManager.saveSnapshot(getApplicationContext(), profile.snapshot);
        isActive = true;
        activeProfileId = profile.id;
        displaySnapshot(profile.snapshot);
        updateActivateButton();
        refreshProfileList();
        Toast.makeText(this, R.string.toast_profile_activated, Toast.LENGTH_SHORT).show();
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

    // ── Profile list ──────────────────────────────────────────────────────────

    private void refreshProfileList() {
        llProfilesContainer.removeAllViews();
        List<SnapshotProfile> profiles = ProfileManager.loadAll(getApplicationContext());

        if (profiles.isEmpty()) {
            tvNoProfiles.setVisibility(View.VISIBLE);
            return;
        }
        tvNoProfiles.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (SnapshotProfile profile : profiles) {
            if (profile == null) continue;
            View itemView = inflater.inflate(R.layout.item_profile, llProfilesContainer, false);
            bindProfileItem(itemView, profile);
            llProfilesContainer.addView(itemView);
        }
    }

    private void bindProfileItem(View itemView, SnapshotProfile profile) {
        TextView tvName    = itemView.findViewById(R.id.tv_profile_name);
        TextView tvDetails = itemView.findViewById(R.id.tv_profile_details);
        Button   btnAct    = itemView.findViewById(R.id.btn_profile_activate);
        Button   btnDel    = itemView.findViewById(R.id.btn_profile_delete);
        View     root      = itemView.findViewById(R.id.profile_item_root);

        tvName.setText(profile.name != null ? profile.name : getString(R.string.profile_unnamed));

        StringBuilder details = new StringBuilder();
        if (profile.snapshot != null) {
            details.append(String.format(Locale.US, "%.4f, %.4f",
                    profile.snapshot.latitude, profile.snapshot.longitude));
        }
        details.append("  •  ").append(SDF.format(new Date(profile.createdAt)));
        tvDetails.setText(details);

        boolean isThisActive = isActive && profile.id != null && profile.id.equals(activeProfileId);
        if (isThisActive) {
            root.setBackgroundResource(R.drawable.bg_profile_item_active);
            btnAct.setText("⏹");
            btnAct.setBackgroundResource(R.drawable.bg_btn_danger);
        } else {
            root.setBackgroundResource(R.drawable.bg_profile_item);
            btnAct.setText("▶");
            btnAct.setBackgroundResource(R.drawable.bg_btn_primary);
        }

        btnAct.setOnClickListener(v -> {
            if (isThisActive) {
                toggleActivation(); // deactivate
            } else {
                showProfileActionDialog(profile);
            }
        });

        btnDel.setOnClickListener(v -> showDeleteDialog(profile));
    }

    private void showDeleteDialog(SnapshotProfile profile) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(String.format(getString(R.string.dialog_delete_message),
                        profile.name != null ? profile.name : getString(R.string.profile_unnamed)))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    ProfileManager.deleteProfile(getApplicationContext(), profile.id);
                    if (profile.id != null && profile.id.equals(activeProfileId)) {
                        SnapshotManager.deactivate(getApplicationContext());
                        isActive = false;
                        activeProfileId = null;
                        updateActivateButton();
                    }
                    Toast.makeText(this, R.string.toast_profile_deleted, Toast.LENGTH_SHORT).show();
                    refreshProfileList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Shows a dialog when tapping a saved profile, letting the user choose
     * between activating the profile or copying its data to the clipboard.
     */
    private void showProfileActionDialog(SnapshotProfile profile) {
        String[] options = {
                getString(R.string.action_activate_profile),
                getString(R.string.action_copy_profile)
        };
        new AlertDialog.Builder(this)
                .setTitle(profile.name != null ? profile.name : getString(R.string.profile_unnamed))
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        activateProfile(profile);
                    } else if (which == 1) {
                        copyProfileToClipboard(profile);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copyProfileToClipboard(SnapshotProfile profile) {
        String json = GSON.toJson(profile.snapshot);
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("ServiceHook Profile", json);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.toast_profile_copied, Toast.LENGTH_SHORT).show();
        }
    }

    // ── Export / Import ────────────────────────────────────────────────────────

    private void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "servicehook_profiles.enc");
        startActivityForResult(intent, REQ_EXPORT_FILE);
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_IMPORT_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_FILE) {
            handleExport(uri);
        } else if (requestCode == REQ_IMPORT_FILE) {
            handleImport(uri);
        }
    }

    private void handleExport(Uri uri) {
        executor.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    ProfileManager.exportProfiles(getApplicationContext(), out);
                    uiHandler.post(() ->
                            Toast.makeText(this, R.string.toast_export_ok, Toast.LENGTH_SHORT).show());
                }
            } catch (Throwable t) {
                uiHandler.post(() ->
                        Toast.makeText(this, R.string.toast_export_fail, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void handleImport(Uri uri) {
        executor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    int count = ProfileManager.importProfiles(getApplicationContext(), in);
                    uiHandler.post(() -> {
                        Toast.makeText(this,
                                String.format(getString(R.string.toast_import_ok), count),
                                Toast.LENGTH_SHORT).show();
                        refreshProfileList();
                    });
                }
            } catch (Throwable t) {
                uiHandler.post(() ->
                        Toast.makeText(this, R.string.toast_import_fail, Toast.LENGTH_SHORT).show());
            }
        });
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

        if (s.wifiList != null && !s.wifiList.isEmpty()) {
            sb.append("\n📶 WiFi (").append(s.wifiList.size()).append(")\n");
            for (int i = 0; i < Math.min(3, s.wifiList.size()); i++) {
                LocationSnapshot.WifiEntry w = s.wifiList.get(i);
                if (w == null) continue;
                sb.append("  ").append(w.ssid != null ? w.ssid : "?")
                  .append(" [").append(w.bssid != null ? w.bssid : "?").append("]")
                  .append(" ").append(w.level).append(" dBm\n");
            }
            if (s.wifiList.size() > 3)
                sb.append("  … ").append(s.wifiList.size() - 3).append(" more\n");
        }

        if (s.cellList != null && !s.cellList.isEmpty()) {
            sb.append("\n📡 Cell (").append(s.cellList.size()).append(")\n");
            for (int i = 0; i < Math.min(2, s.cellList.size()); i++) {
                LocationSnapshot.CellEntry c = s.cellList.get(i);
                if (c == null) continue;
                sb.append("  ").append(c.type != null ? c.type : "?")
                  .append(" MCC=").append(c.mcc)
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
