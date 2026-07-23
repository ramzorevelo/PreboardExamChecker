package com.pbec.preboardexamchecker.di

import com.pbec.preboardexamchecker.data.repository.ExamClusterRepository
import com.pbec.preboardexamchecker.data.repository.ExamRepository
import com.pbec.preboardexamchecker.data.repository.IExamClusterRepository
import com.pbec.preboardexamchecker.data.repository.IExamRepository
import com.pbec.preboardexamchecker.data.repository.IScanResultRepository
import com.pbec.preboardexamchecker.data.repository.IStudentRepository
import com.pbec.preboardexamchecker.data.repository.ScanResultRepository
import com.pbec.preboardexamchecker.data.repository.StudentRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun bindStudentRepository(impl: StudentRepository): IStudentRepository
    @Binds @Singleton abstract fun bindScanResultRepository(impl: ScanResultRepository): IScanResultRepository
    @Binds @Singleton abstract fun bindExamRepository(impl: ExamRepository): IExamRepository
    @Binds @Singleton abstract fun bindExamClusterRepository(impl: ExamClusterRepository): IExamClusterRepository
}
