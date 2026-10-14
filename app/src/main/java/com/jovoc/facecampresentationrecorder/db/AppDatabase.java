package com.jovoc.facecampresentationrecorder.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Slide.class, PublishJob.class}, version = 5, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase INSTANCE;

    public abstract SlideDao slideDao();

    public abstract PublishJobDao publishJobDao();

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

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "presentation_db"
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build();
                }
            }
        }
        return INSTANCE;
    }
}
