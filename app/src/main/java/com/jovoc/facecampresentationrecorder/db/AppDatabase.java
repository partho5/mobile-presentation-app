package com.jovoc.facecampresentationrecorder.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Slide.class, PublishJob.class, UploadSession.class,
        AudioUploadSession.class, TrackedActivity.class},
        version = 12, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase INSTANCE;

    public abstract SlideDao slideDao();

    public abstract PublishJobDao publishJobDao();

    public abstract UploadSessionDao uploadSessionDao();

    public abstract AudioUploadSessionDao audioUploadSessionDao();

    public abstract TrackedActivityDao trackedActivityDao();

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE slides ADD COLUMN is_disabled INTEGER NOT NULL DEFAULT 0");
        }
    };

    // Adds the publish job table only; slides are untouched.
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `publish_jobs` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`file_path` TEXT, `file_name` TEXT, `caption` TEXT, `channels_json` TEXT, "
                    + "`mode` TEXT, `schedule_at` INTEGER NOT NULL, `idempotency_key` TEXT, "
                    + "`media_id` TEXT, `media_path` TEXT, `status` TEXT, `progress` INTEGER NOT NULL, "
                    + "`error_kind` TEXT, `error_detail` TEXT, `post_ids` TEXT, "
                    + "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_publish_jobs_file_path` "
                    + "ON `publish_jobs` (`file_path`)");
        }
    };

    // Adds the workspace a publish job belongs to; existing rows keep NULL (= default workspace).
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `publish_jobs` ADD COLUMN `workspace_id` TEXT");
        }
    };

    // Adds what the server reported about an accepted post (live / failed / deleted); all nullable.
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `publish_jobs` ADD COLUMN `post_integrations` TEXT");
            database.execSQL("ALTER TABLE `publish_jobs` ADD COLUMN `remote_state` TEXT");
            database.execSQL("ALTER TABLE `publish_jobs` ADD COLUMN `remote_channel` TEXT");
            database.execSQL("ALTER TABLE `publish_jobs` ADD COLUMN `remote_error` TEXT");
            database.execSQL("ALTER TABLE `publish_jobs` ADD COLUMN `remote_url` TEXT");
        }
    };

    /** The table of {@link UploadSession}; kept as text so a test can check what a migration runs. */
    static final String CREATE_UPLOAD_SESSIONS = "CREATE TABLE IF NOT EXISTS `upload_sessions` ("
            + "`job_id` INTEGER NOT NULL, `upload_id` TEXT, `size` INTEGER NOT NULL, "
            + "`part_size` INTEGER NOT NULL, `part_count` INTEGER NOT NULL, "
            + "`parts_json` TEXT, `status` TEXT, PRIMARY KEY(`job_id`))";

    // Adds the direct-upload session table (resume after a kill); other tables are untouched.
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(CREATE_UPLOAD_SESSIONS);
        }
    };

    /** The old activity-log table, dropped in 9→10; kept as text so a test can check what a migration ran. */
    static final String CREATE_ACTIVITY_EVENTS = "CREATE TABLE IF NOT EXISTS `activity_events` ("
            + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ts` INTEGER NOT NULL, "
            + "`tz` INTEGER NOT NULL, `session` TEXT, `action` TEXT, `meta_json` TEXT, "
            + "`batch_id` TEXT)";

    // Adds the activity log buffer table; every other table is untouched.
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(CREATE_ACTIVITY_EVENTS);
        }
    };

    /** The table of {@link AudioUploadSession}; same columns as {@code upload_sessions}. */
    static final String CREATE_AUDIO_UPLOAD_SESSIONS =
            "CREATE TABLE IF NOT EXISTS `audio_upload_sessions` ("
            + "`job_id` INTEGER NOT NULL, `upload_id` TEXT, `size` INTEGER NOT NULL, "
            + "`part_size` INTEGER NOT NULL, `part_count` INTEGER NOT NULL, "
            + "`parts_json` TEXT, `status` TEXT, PRIMARY KEY(`job_id`))";

    // AI captions: the speech-audio upload table and the caption state of a publish job (all
    // nullable); the video's upload_sessions and every other column are untouched.
    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(CREATE_AUDIO_UPLOAD_SESSIONS);
            database.execSQL("ALTER TABLE `publish_jobs` ADD COLUMN `aiCaptionId` TEXT");
            database.execSQL("ALTER TABLE `publish_jobs` ADD COLUMN `aiCaptionState` TEXT");
            database.execSQL("ALTER TABLE `publish_jobs` ADD COLUMN `aiCaptionKey` TEXT");
            database.execSQL("ALTER TABLE `publish_jobs` ADD COLUMN `publishDate` TEXT");
        }
    };

    /** The table of {@link TrackedActivity}; kept as text so a test can check what a migration runs. */
    static final String CREATE_TRACKED_ACTIVITIES = "CREATE TABLE IF NOT EXISTS `tracked_activities` ("
            + "`id` TEXT NOT NULL, `seq` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, "
            + "`json` TEXT NOT NULL, `batch_id` TEXT, PRIMARY KEY(`id`))";

    // Activity tracker buffer; every other table is untouched.
    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(CREATE_TRACKED_ACTIVITIES);
        }
    };

    // Drops the old activity-log table only; the activity tracker keeps its own table (tracked_activities).
    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE IF EXISTS `activity_events`");
        }
    };

    // Adds the nullable set id to slides: NULL = the user's own topics (every existing row), "plan:<dayId>" = a plan day's topics.
    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE slides ADD COLUMN set_id TEXT");
        }
    };

    // Adds the nullable speaker note to slides (what the user plans to say on a topic, used by Practice).
    static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE slides ADD COLUMN speaker_note TEXT");
        }
    };

    /** Every step from version 1 to the current one, in order. */
    static final Migration[] MIGRATIONS = {
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            MIGRATION_10_11, MIGRATION_11_12
    };

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "presentation_db"
                    ).addMigrations(MIGRATIONS).build();
                }
            }
        }
        return INSTANCE;
    }
}
