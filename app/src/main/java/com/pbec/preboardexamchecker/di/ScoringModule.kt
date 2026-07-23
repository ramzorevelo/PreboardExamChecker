package com.pbec.preboardexamchecker.di

import com.pbec.preboardexamchecker.ui.scanner.scoring.PercentageThresholdStrategy
import com.pbec.preboardexamchecker.ui.scanner.scoring.ScoringStrategy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScoringModule {
    @Provides
    @Singleton
    fun provideScoringStrategy(): ScoringStrategy = PercentageThresholdStrategy()
}
