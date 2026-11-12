package com.example.zplanner.di

import android.content.Context
import androidx.room.Room
import com.example.zplanner.data.TaskDao
import com.example.zplanner.data.ZPlannerDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ZPlannerDatabase =
        Room.databaseBuilder(context, ZPlannerDatabase::class.java, "zplanner.db").build()

    @Provides
    fun provideTaskDao(database: ZPlannerDatabase): TaskDao = database.taskDao()
}
