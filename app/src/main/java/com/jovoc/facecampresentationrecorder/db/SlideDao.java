package com.jovoc.facecampresentationrecorder.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface SlideDao {

    @Query("SELECT * FROM slides ORDER BY order_index ASC")
    List<Slide> getAllOrdered();

    @Query("SELECT * FROM slides WHERE set_id IS NULL ORDER BY order_index ASC")
    List<Slide> getOwnOrdered();

    @Query("SELECT * FROM slides WHERE set_id = :setId ORDER BY order_index ASC")
    List<Slide> getSetOrdered(String setId);

    @Query("DELETE FROM slides WHERE set_id IS NULL")
    void deleteOwn();

    @Query("DELETE FROM slides WHERE set_id = :setId")
    void deleteSet(String setId);

    @Query("SELECT id FROM slides WHERE set_id = :setId")
    List<Integer> idsOfSet(String setId);

    @Query("SELECT id FROM slides WHERE set_id IS NULL")
    List<Integer> idsOfOwn();

    @Query("SELECT DISTINCT set_id FROM slides WHERE set_id LIKE 'plan:%'")
    List<String> planSetIds();

    /** Writes only the note, so a stale copy of the slide elsewhere can never undo another field. */
    @Query("UPDATE slides SET speaker_note = :note WHERE id = :id")
    void updateNote(int id, String note);

    @Query("SELECT speaker_note FROM slides WHERE id = :id")
    String noteOf(int id);

    @Insert
    void insertAll(List<Slide> slides);

    @Insert
    long insert(Slide slide);

    @Update
    void update(Slide slide);

    @Delete
    void delete(Slide slide);

    @Query("DELETE FROM slides WHERE id = :id")
    void deleteById(int id);

    @Query("DELETE FROM slides")
    void deleteAll();
}
