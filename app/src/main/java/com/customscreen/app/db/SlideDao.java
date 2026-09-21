package com.customscreen.app.db;

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
