package com.servicehook.model;

import java.util.UUID;

/**
 * A named location snapshot profile — pairs a user-given label with
 * a {@link LocationSnapshot} so saved snapshots can be identified and
 * recalled later.  Serialised to/from JSON via Gson.
 */
public class SnapshotProfile {

    /** Unique identifier (UUID). */
    public String id;

    /** User-given name / label for this profile. */
    public String name;

    /** Timestamp when created (millis since epoch). */
    public long createdAt;

    /** The actual snapshot data. */
    public LocationSnapshot snapshot;

    /**
     * Creates a new profile, auto-generating {@link #id} and {@link #createdAt}.
     *
     * @param name     user-visible label
     * @param snapshot captured location snapshot
     */
    public SnapshotProfile(String name, LocationSnapshot snapshot) {
        this.id        = UUID.randomUUID().toString();
        this.name      = name;
        this.createdAt = System.currentTimeMillis();
        this.snapshot  = snapshot;
    }

    /**
     * Ensures the profile's snapshot is usable after Gson deserialization:
     * verifies the snapshot reference is non-null and delegates to
     * {@link LocationSnapshot#ensureNonNullLists()}.
     */
    public void ensureValid() {
        if (snapshot == null) {
            snapshot = new LocationSnapshot();
        }
        snapshot.ensureNonNullLists();
    }
}
