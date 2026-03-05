package com.servicehook;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.servicehook.model.SnapshotProfile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Manages multiple named snapshot profiles stored as plaintext JSON in
 * SharedPreferences. Encryption was removed to improve reliability and
 * eliminate data-corruption issues that caused exported data to contain
 * all zeros.
 *
 * On first load, any legacy encrypted profiles (from the old
 * {@code encrypted_profiles} key) are migrated automatically.
 */
public class ProfileManager {

    private static final String PREFS_NAME          = "sh_profiles";
    /** Current plaintext storage key. */
    private static final String KEY_PROFILES_PLAIN  = "profiles_plain";
    /** Legacy encrypted storage key (for one-time migration). */
    private static final String KEY_PROFILES_LEGACY = "encrypted_profiles";
    private static final String KEY_SECRET          = "profile_key";

    private static final Gson GSON = new Gson();

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns all saved profiles (never null). */
    public static List<SnapshotProfile> loadAll(Context ctx) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            // Try plaintext key first
            String json = prefs.getString(KEY_PROFILES_PLAIN, null);

            // One-time migration: if no plaintext data exists, try decrypting
            // legacy encrypted data and migrate it to plaintext.
            if (json == null) {
                String legacy = prefs.getString(KEY_PROFILES_LEGACY, null);
                if (legacy != null) {
                    json = decryptLegacy(ctx, legacy);
                    if (json != null) {
                        // Migrate: store plaintext and remove legacy keys
                        prefs.edit()
                                .putString(KEY_PROFILES_PLAIN, json)
                                .remove(KEY_PROFILES_LEGACY)
                                .remove(KEY_SECRET)
                                .apply();
                    }
                }
            }

            if (json == null) return new ArrayList<>();

            Type type = new TypeToken<List<SnapshotProfile>>() {}.getType();
            List<SnapshotProfile> profiles = GSON.fromJson(json, type);
            if (profiles == null) return new ArrayList<>();
            for (SnapshotProfile p : profiles) {
                if (p != null) p.ensureValid();
            }
            return profiles;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** Save a new profile. */
    public static void addProfile(Context ctx, SnapshotProfile profile) {
        List<SnapshotProfile> all = loadAll(ctx);
        all.add(profile);
        saveAll(ctx, all);
    }

    /** Delete a profile by id. */
    public static void deleteProfile(Context ctx, String profileId) {
        List<SnapshotProfile> all = loadAll(ctx);
        Iterator<SnapshotProfile> it = all.iterator();
        while (it.hasNext()) {
            SnapshotProfile p = it.next();
            if (p != null && profileId.equals(p.id)) {
                it.remove();
                break;
            }
        }
        saveAll(ctx, all);
    }

    /** Update an existing profile's name. */
    public static void renameProfile(Context ctx, String profileId, String newName) {
        List<SnapshotProfile> all = loadAll(ctx);
        for (SnapshotProfile p : all) {
            if (p != null && profileId.equals(p.id)) {
                p.name = newName;
                break;
            }
        }
        saveAll(ctx, all);
    }

    /** Find a profile by id (or null). */
    public static SnapshotProfile findById(Context ctx, String profileId) {
        for (SnapshotProfile p : loadAll(ctx)) {
            if (p != null && profileId.equals(p.id)) return p;
        }
        return null;
    }

    /**
     * Export all profiles to the given OutputStream as plaintext JSON.
     * The caller is responsible for closing the stream.
     */
    public static void exportProfiles(Context ctx, OutputStream out) throws Exception {
        List<SnapshotProfile> all = loadAll(ctx);
        String json = GSON.toJson(all);
        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(json);
        writer.flush();
    }

    /**
     * Import profiles from the given InputStream (expects plaintext JSON).
     * Imported profiles are merged with existing ones (duplicates by id are skipped).
     */
    public static int importProfiles(Context ctx, InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        String json = sb.toString().trim();

        if (json.isEmpty()) return 0;

        Type type = new TypeToken<List<SnapshotProfile>>() {}.getType();
        List<SnapshotProfile> imported = GSON.fromJson(json, type);
        if (imported == null || imported.isEmpty()) return 0;

        List<SnapshotProfile> existing = loadAll(ctx);
        java.util.Set<String> existingIds = new java.util.HashSet<>();
        for (SnapshotProfile p : existing) {
            if (p != null && p.id != null) existingIds.add(p.id);
        }

        int added = 0;
        for (SnapshotProfile p : imported) {
            if (p == null) continue;
            p.ensureValid();
            if (p.id != null && !existingIds.contains(p.id)) {
                existing.add(p);
                existingIds.add(p.id);
                added++;
            }
        }
        if (added > 0) saveAll(ctx, existing);
        return added;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void saveAll(Context ctx, List<SnapshotProfile> profiles) {
        try {
            String json = GSON.toJson(profiles);
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_PROFILES_PLAIN, json).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Attempt to decrypt legacy AES-GCM-encrypted profile data for one-time
     * migration. Returns the plaintext JSON string, or null on failure.
     */
    private static String decryptLegacy(Context ctx, String encryptedB64) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String keyB64 = prefs.getString(KEY_SECRET, null);
            if (keyB64 == null) return null;

            byte[] keyRaw = Base64.decode(keyB64, Base64.NO_WRAP);
            javax.crypto.spec.SecretKeySpec key =
                    new javax.crypto.spec.SecretKeySpec(keyRaw, "AES");

            byte[] combined = Base64.decode(encryptedB64, Base64.NO_WRAP);
            if (combined.length < 12) return null;

            byte[] iv = new byte[12];
            System.arraycopy(combined, 0, iv, 0, 12);
            byte[] ct = new byte[combined.length - 12];
            System.arraycopy(combined, 12, ct, 0, ct.length);

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key,
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
