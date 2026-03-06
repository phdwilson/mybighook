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

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Manages multiple named snapshot profiles with plaintext JSON storage.
 * Profiles are persisted in SharedPreferences as an unencrypted JSON array.
 *
 * Legacy encrypted data (from earlier versions) is automatically migrated
 * to plaintext on first load and the encrypted key is removed.
 */
public class ProfileManager {

    private static final String PREFS_NAME           = "sh_profiles";
    private static final String KEY_PROFILES_PLAIN   = "profiles_plain";

    // Legacy keys (encryption removed — kept only for one-time migration)
    private static final String KEY_LEGACY_ENCRYPTED = "encrypted_profiles";
    private static final String KEY_LEGACY_SECRET    = "profile_key";
    private static final String LEGACY_AES_ALGO      = "AES/GCM/NoPadding";
    private static final int    LEGACY_GCM_IV_LEN    = 12;
    private static final int    LEGACY_GCM_TAG_BITS  = 128;

    private static final Gson GSON = new Gson();

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns all saved profiles (never null). */
    public static List<SnapshotProfile> loadAll(Context ctx) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            // Prefer plaintext storage
            String json = prefs.getString(KEY_PROFILES_PLAIN, null);
            if (json != null) {
                return parseProfiles(json);
            }

            // One-time migration: try to decrypt legacy encrypted data
            String encrypted = prefs.getString(KEY_LEGACY_ENCRYPTED, null);
            if (encrypted != null) {
                List<SnapshotProfile> migrated = migrateLegacy(prefs, encrypted);
                if (migrated != null) return migrated;
            }

            return new ArrayList<>();
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
        String raw = sb.toString().trim();

        // Try parsing as plaintext JSON first
        List<SnapshotProfile> imported = tryParseProfiles(raw);

        // Fallback: try to decrypt legacy encrypted format
        if (imported == null) {
            String decrypted = decryptLegacy(ctx, raw);
            if (decrypted != null) {
                imported = tryParseProfiles(decrypted);
            }
        }

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

    private static List<SnapshotProfile> parseProfiles(String json) {
        try {
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

    /** Try to parse JSON; returns null on failure (not empty list). */
    private static List<SnapshotProfile> tryParseProfiles(String json) {
        try {
            Type type = new TypeToken<List<SnapshotProfile>>() {}.getType();
            List<SnapshotProfile> profiles = GSON.fromJson(json, type);
            if (profiles == null || profiles.isEmpty()) return null;
            for (SnapshotProfile p : profiles) {
                if (p != null) p.ensureValid();
            }
            return profiles;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Legacy migration (one-time, then deleted) ─────────────────────────────

    /**
     * Attempt to decrypt legacy encrypted profiles, save them as plaintext,
     * and remove the old encrypted keys.
     */
    private static List<SnapshotProfile> migrateLegacy(SharedPreferences prefs,
                                                        String encryptedB64) {
        try {
            String keyB64 = prefs.getString(KEY_LEGACY_SECRET, null);
            if (keyB64 == null) return null;
            byte[] keyRaw = Base64.decode(keyB64, Base64.NO_WRAP);
            SecretKey key = new SecretKeySpec(keyRaw, "AES");

            byte[] combined = Base64.decode(encryptedB64, Base64.NO_WRAP);
            if (combined.length < LEGACY_GCM_IV_LEN) return null;
            byte[] iv = new byte[LEGACY_GCM_IV_LEN];
            System.arraycopy(combined, 0, iv, 0, LEGACY_GCM_IV_LEN);
            byte[] ct = new byte[combined.length - LEGACY_GCM_IV_LEN];
            System.arraycopy(combined, LEGACY_GCM_IV_LEN, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(LEGACY_AES_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(LEGACY_GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            String json = new String(pt, StandardCharsets.UTF_8);

            List<SnapshotProfile> profiles = parseProfiles(json);

            // Save as plaintext and remove legacy keys
            prefs.edit()
                    .putString(KEY_PROFILES_PLAIN, GSON.toJson(profiles))
                    .remove(KEY_LEGACY_ENCRYPTED)
                    .remove(KEY_LEGACY_SECRET)
                    .apply();

            return profiles;
        } catch (Throwable t) {
            // Migration failed — remove corrupt legacy data
            prefs.edit()
                    .remove(KEY_LEGACY_ENCRYPTED)
                    .remove(KEY_LEGACY_SECRET)
                    .apply();
            return null;
        }
    }

    /**
     * Try to decrypt a legacy encrypted string (for import compatibility).
     * Returns null on failure.
     */
    private static String decryptLegacy(Context ctx, String encryptedB64) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String keyB64 = prefs.getString(KEY_LEGACY_SECRET, null);
            if (keyB64 == null) return null;
            byte[] keyRaw = Base64.decode(keyB64, Base64.NO_WRAP);
            SecretKey key = new SecretKeySpec(keyRaw, "AES");

            byte[] combined = Base64.decode(encryptedB64, Base64.NO_WRAP);
            if (combined.length < LEGACY_GCM_IV_LEN) return null;
            byte[] iv = new byte[LEGACY_GCM_IV_LEN];
            System.arraycopy(combined, 0, iv, 0, LEGACY_GCM_IV_LEN);
            byte[] ct = new byte[combined.length - LEGACY_GCM_IV_LEN];
            System.arraycopy(combined, LEGACY_GCM_IV_LEN, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(LEGACY_AES_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(LEGACY_GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
