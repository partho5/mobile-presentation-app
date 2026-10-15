package com.jovoc.facecampresentationrecorder.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Migration 5 to 6 (the direct-upload session table). There is no SQLite on the JVM, so this
 * checks the steps the migration runs; Room itself validates the result against the entity when
 * the database is opened.
 */
public class AppDatabaseMigrationTest {

    /** A database that only records the SQL it is given. */
    private static SupportSQLiteDatabase recording(List<String> sql) {
        return (SupportSQLiteDatabase) Proxy.newProxyInstance(
                SupportSQLiteDatabase.class.getClassLoader(),
                new Class<?>[]{SupportSQLiteDatabase.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("execSQL")) sql.add((String) args[0]);
                    return null;
                });
    }

    private static List<String> run(Migration m) {
        List<String> sql = new ArrayList<>();
        m.migrate(recording(sql));
        return sql;
    }

    @Test
    public void currentVersionIsSixAndEveryStepIsRegisteredInOrder() {
        Migration[] all = AppDatabase.MIGRATIONS;
        assertEquals(5, all.length);
        for (int i = 0; i < all.length; i++) {
            assertEquals(i + 1, all[i].startVersion);
            assertEquals(i + 2, all[i].endVersion);
        }
        assertEquals(6, all[all.length - 1].endVersion);
    }

    @Test
    public void migration5to6_createsTheSessionTableAndTouchesNothingElse() {
        List<String> sql = run(AppDatabase.MIGRATION_5_6);
        assertEquals(1, sql.size());
        String s = sql.get(0);
        assertTrue(s.startsWith("CREATE TABLE IF NOT EXISTS `upload_sessions`"));
        assertTrue(!s.contains("publish_jobs") && !s.contains("slides"));
    }

    @Test
    public void migration5to6_hasEveryEntityColumnAndTheJobIdPrimaryKey() {
        String s = run(AppDatabase.MIGRATION_5_6).get(0);
        String[] columns = {"`job_id` INTEGER NOT NULL", "`upload_id` TEXT", "`size` INTEGER NOT NULL",
                "`part_size` INTEGER NOT NULL", "`part_count` INTEGER NOT NULL", "`parts_json` TEXT",
                "`status` TEXT", "PRIMARY KEY(`job_id`)"};
        for (String c : columns) assertTrue("missing " + c, s.contains(c));
        // One column per entity field, none more.
        int fields = 0;
        for (java.lang.reflect.Field f : UploadSession.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) fields++;
        }
        assertEquals(fields, columns.length - 1);
    }
}
