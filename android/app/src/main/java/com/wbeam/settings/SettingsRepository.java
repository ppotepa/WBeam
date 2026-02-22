package com.wbeam.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Reads and writes user settings from SharedPreferences.
 * All PREF_* keys and load/save operations are isolated here.
 * UI binding (spinners, seekbars) remains in MainActivity.
 */
public final class SettingsRepository {

    private static final String PREFS = "wbeam_settings";

    public static final String PREF_PROFILE          = "profile";
    public static final String PREF_ENCODER          = "encoder";
    public static final String PREF_CURSOR           = "cursor";
    public static final String PREF_CURSOR_MIGRATED_V2 = "cursor_migrated_v2";
    public static final String PREF_RES_SCALE        = "res_scale";
    public static final String PREF_FPS              = "fps";
    public static final String PREF_BITRATE          = "bitrate";
    public static final String PREF_LOCAL_CURSOR     = "local_cursor_overlay";
    public static final String PREF_INTRA_ONLY       = "intra_only";

    private final Context context;

    public SettingsRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Load all persisted settings. Applies migration if needed. Returns a snapshot. */
    public SettingsSnapshot load() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        String profile = prefs.getString(PREF_PROFILE, "lowlatency");
        String encoder = prefs.getString(PREF_ENCODER, "h265");
        String cursor  = prefs.getString(PREF_CURSOR, "embedded");

        // Migration v3: collapse legacy encoder names to h265/rawpng.
        // h264 is a valid first-class encoder — pass through without migration.
        if (!"h264".equals(encoder) && !"h265".equals(encoder)
                && !"raw-png".equals(encoder) && !"rawpng".equals(encoder)) {
            encoder = "h265";
            prefs.edit().putString(PREF_ENCODER, encoder).apply();
        }

        // Migration v2: rename "hidden" cursor to "embedded"
        if (!prefs.getBoolean(PREF_CURSOR_MIGRATED_V2, false) && "hidden".equals(cursor)) {
            cursor = "embedded";
            prefs.edit()
                    .putString(PREF_CURSOR, cursor)
                    .putBoolean(PREF_CURSOR_MIGRATED_V2, true)
                    .apply();
        }

        int resScale      = prefs.getInt(PREF_RES_SCALE, 100);
        int fps           = prefs.getInt(PREF_FPS, 60);
        int bitrateMbps   = prefs.getInt(PREF_BITRATE, 25);
        boolean localCursor = prefs.getBoolean(PREF_LOCAL_CURSOR, true);
        boolean intraOnly   = prefs.getBoolean(PREF_INTRA_ONLY, false);

        return new SettingsSnapshot(
                profile, encoder, cursor,
                resScale, fps, bitrateMbps,
                localCursor, intraOnly
        );
    }

    /** Persist the current settings snapshot. */
    public void save(SettingsSnapshot s) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_PROFILE,          s.profile)
                .putString(PREF_ENCODER,          s.encoder)
                .putString(PREF_CURSOR,           s.cursor)
                .putInt(PREF_RES_SCALE,           s.resScale)
                .putInt(PREF_FPS,                 s.fps)
                .putInt(PREF_BITRATE,             s.bitrateMbps)
                .putBoolean(PREF_LOCAL_CURSOR,    s.localCursor)
                .putBoolean(PREF_INTRA_ONLY,      s.intraOnly)
                .apply();
    }

    /** Immutable snapshot of all user settings. */
    public static final class SettingsSnapshot {
        public final String  profile;
        public final String  encoder;
        public final String  cursor;
        public final int     resScale;
        public final int     fps;
        public final int     bitrateMbps;
        public final boolean localCursor;
        public final boolean intraOnly;

        public SettingsSnapshot(
                String profile, String encoder, String cursor,
                int resScale, int fps, int bitrateMbps,
                boolean localCursor, boolean intraOnly
        ) {
            this.profile     = profile;
            this.encoder     = encoder;
            this.cursor      = cursor;
            this.resScale    = resScale;
            this.fps         = fps;
            this.bitrateMbps = bitrateMbps;
            this.localCursor = localCursor;
            this.intraOnly   = intraOnly;
        }
    }
}
