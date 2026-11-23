package com.example.zplanner.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Task::class], version = 1, exportSchema = false)
abstract class ZPlannerDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
}
