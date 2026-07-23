// app/src/main/java/com/pbec/preboardexamchecker/di/AppModule.kt
package com.pbec.preboardexamchecker.di

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.pbec.preboardexamchecker.utils.PdfExportUtil
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class) 
object AppModule {

    @Provides
    @Singleton 
    fun providePdfExportUtil(@ApplicationContext context: Context): PdfExportUtil {
        return PdfExportUtil(context)
    }

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore {
        return FirebaseFirestore.getInstance()
    }
}
