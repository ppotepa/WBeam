package com.wbeam.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Reads and writes user settings from SharedPreferences.
 * All PREF_* keys and load/save operations are isolated here.
 * UI binding (spinners, seekbars) remains in MainActivity.
 */
@SuppressWarnings({"java:S1192", "java:S1104"})
public final class SettingsRepository {

    private static final String PREFS = "wbeam_settings";
    private static final String PROFILE_DEFAULT = "default";
    private static final String PROFILE_ADAPTIVE = "adaptive";
    private static final String ENCODER_H264 = "h264";
    private static final String ENCODER_H265 = "h265";
    private static final String ENCODER_RAW_PNG = "raw-png";
    private static final String ENCODER_RAWPNG = "rawpng";
    private static final String CURSOR_EMBEDDED = "embedded";

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

        String profile = prefs.getString(PREF_PROFILE, PROFILE_DEFAULT);
        String encoder = prefs.getString(PREF_ENCODER, ENCODER_H265);
        String cursor  = prefs.getString(PREF_CURSOR, CURSOR_EMBEDDED);

        if (!PROFILE_DEFAULT.equals(profile) && !PROFILE_ADAPTIVE.equals(profile)) {
            profile = PROFILE_DEFAULT;
            prefs.edit().putString(PREF_PROFILE, profile).apply();
        }

        // Migration v3: collapse legacy encoder names to h265/rawpng.
        // h264 is a valid first-class encoder — pass through without migration.
        if (!ENCODER_H264.equals(encoder) && !ENCODER_H265.equals(encoder)
                && !ENCODER_RAW_PNG.equals(encoder) && !ENCODER_RAWPNG.equals(encoder)) {
            encoder = ENCODER_H265;
            prefs.edit().putString(PREF_ENCODER, encoder).apply();
        }

        // Migration v2: rename "hidden" cursor to "embedded"
        if (!prefs.getBoolean(PREF_CURSOR_MIGRATED_V2, false) && "hidden".equals(cursor)) {
            cursor = CURSOR_EMBEDDED;
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

        return new SettingsSnapshot.Builder()
                .setProfile(profile)
                .setEncoder(encoder)
                .setCursor(cursor)
                .setResScale(resScale)
                .setFps(fps)
                .setBitrateMbps(bitrateMbps)
                .setLocalCursor(localCursor)
                .setIntraOnly(intraOnly)
                .build();
    }

    /** Persist the current settings snapshot. */
    public void save(SettingsSnapshot s) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_PROFILE,          s.getProfile())
                .putString(PREF_ENCODER,          s.getEncoder())
                .putString(PREF_CURSOR,           s.getCursor())
                .putInt(PREF_RES_SCALE,           s.getResScale())
                .putInt(PREF_FPS,                 s.getFps())
                .putInt(PREF_BITRATE,             s.getBitrateMbps())
                .putBoolean(PREF_LOCAL_CURSOR,    s.isLocalCursor())
                .putBoolean(PREF_INTRA_ONLY,      s.isIntraOnly())
                .apply();
    }

    /** Immutable snapshot of all user settings. */
    public static final class SettingsSnapshot {
        private final String  profile;
        private final String  encoder;
        private final String  cursor;
        private final int     resScale;
        private final int     fps;
        private final int     bitrateMbps;
        private final boolean localCursor;
        private final boolean intraOnly;

        public SettingsSnapshot(Builder builder) {
            this.profile     = builder.profile;
            this.encoder     = builder.encoder;
            this.cursor      = builder.cursor;
            this.resScale    = builder.resScale;
            this.fps         = builder.fps;
            this.bitrateMbps = builder.bitrateMbps;
            this.localCursor = builder.localCursor;
            this.intraOnly   = builder.intraOnly;
        }

        public String getProfile() {
            return profile;
        }

        public String getEncoder() {
            return encoder;
        }

        public String getCursor() {
            return cursor;
        }

        public int getResScale() {
            return resScale;
        }

        public int getFps() {
            return fps;
        }

        public int getBitrateMbps() {
            return bitrateMbps;
        }

        public boolean isLocalCursor() {
            return localCursor;
        }

        public boolean isIntraOnly() {
            return intraOnly;
        }

        public static final class Builder {
            private String  profile;
            private String  encoder;
            private String  cursor;
            private int     resScale;
            private int     fps;
            private int     bitrateMbps;
            private boolean localCursor;
            private boolean intraOnly;

            public Builder setProfile(String profile) {
                this.profile = profile;
                return this;
            }

            public Builder setEncoder(String encoder) {
                this.encoder = encoder;
                return this;
            }

            public Builder setCursor(String cursor) {
                this.cursor = cursor;
                return this;
            }

            public Builder setResScale(int resScale) {
                this.resScale = resScale;
                return this;
            }

            public Builder setFps(int fps) {
                this.fps = fps;
                return this;
            }

            public Builder setBitrateMbps(int bitrateMbps) {
                this.bitrateMbps = bitrateMbps;
                return this;
            }

            public Builder setLocalCursor(boolean localCursor) {
                this.localCursor = localCursor;
                return this;
            }

            public Builder setIntraOnly(boolean intraOnly) {
                this.intraOnly = intraOnly;
                return this;
            }

            public SettingsSnapshot build() {
                return new SettingsSnapshot(this);
            }
        }
    }

}
