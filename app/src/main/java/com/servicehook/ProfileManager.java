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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Manages multiple named snapshot profiles stored as plaintext JSON in
 * SharedPreferences.  Encryption has been removed to improve reliability;
 * existing encrypted data is transparently migrated on first load.
 */
public class ProfileManager {

    private static final String PREFS_NAME    = "sh_profiles";
    /** Key for plaintext profile storage. */
    private static final String KEY_PROFILES_PLAIN = "profiles_plain";
    /** Legacy key for encrypted profile storage (migration only). */
    private static final String KEY_PROFILES  = "encrypted_profiles";
    private static final String KEY_SECRET    = "profile_key";
    private static final String AES_ALGO      = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN    = 12;
    private static final int    GCM_TAG_BITS  = 128;
    private static final int    AES_KEY_LEN   = 16; // 128-bit

    private static final Gson GSON = new Gson();

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns all saved profiles (never null). */
    public static List<SnapshotProfile> loadAll(Context ctx) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            // Try plaintext storage first
            String json = prefs.getString(KEY_PROFILES_PLAIN, null);

            // Migration: fall back to legacy encrypted storage
            if (json == null) {
                String encrypted = prefs.getString(KEY_PROFILES, null);
                if (encrypted == null) return new ArrayList<>();
                json = decrypt(ctx, encrypted);
                if (json == null) return new ArrayList<>();
                // Migrate: save as plaintext and remove encrypted key
                prefs.edit()
                        .putString(KEY_PROFILES_PLAIN, json)
                        .remove(KEY_PROFILES)
                        .apply();
            }

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
     * Import profiles from the given InputStream (expects plaintext JSON;
     * also accepts legacy encrypted format for backward compatibility).
     * Imported profiles are merged with existing ones (duplicates by id are skipped).
     */
    public static int importProfiles(Context ctx, InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        String content = sb.toString().trim();

        // Try parsing as plaintext JSON first
        String json = null;
        try {
            Type type = new TypeToken<List<SnapshotProfile>>() {}.getType();
            List<SnapshotProfile> test = GSON.fromJson(content, type);
            if (test != null) json = content;
        } catch (Throwable ignored) {}

        // Fall back to legacy encrypted format
        if (json == null) {
            json = decrypt(ctx, content);
            if (json == null) throw new Exception("Failed to parse import file");
        }

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
            prefs.edit()
                    .putString(KEY_PROFILES_PLAIN, json)
                    .remove(KEY_PROFILES)   // clean up legacy encrypted key
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static SecretKey getOrCreateKey(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String keyB64 = prefs.getString(KEY_SECRET, null);
        if (keyB64 != null) {
            byte[] raw = Base64.decode(keyB64, Base64.NO_WRAP);
            return new SecretKeySpec(raw, "AES");
        }
        byte[] raw = new byte[AES_KEY_LEN];
        new SecureRandom().nextBytes(raw);
        prefs.edit().putString(KEY_SECRET, Base64.encodeToString(raw, Base64.NO_WRAP)).apply();
        return new SecretKeySpec(raw, "AES");
    }

    private static String encrypt(Context ctx, String plaintext) {
        try {
            SecretKey key = getOrCreateKey(ctx);
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // iv + ciphertext, Base64-encoded
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String decrypt(Context ctx, String encryptedB64) {
        try {
            SecretKey key = getOrCreateKey(ctx);
            byte[] combined = Base64.decode(encryptedB64, Base64.NO_WRAP);
            if (combined.length < GCM_IV_LEN) return null;
            byte[] iv = new byte[GCM_IV_LEN];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LEN);
            byte[] ct = new byte[combined.length - GCM_IV_LEN];
            System.arraycopy(combined, GCM_IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
