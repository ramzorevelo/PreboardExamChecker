package com.pbec.preboardexamchecker.data.models

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject

@ProvidedTypeConverter
class ListLongConverter @Inject constructor(private val gson: Gson) {
    @TypeConverter
    fun fromListLong(list: List<Long>?): String? {
        return gson.toJson(list)
    }

    @TypeConverter
    fun toListLong(json: String?): List<Long>? {
        if (json == null) {
            return null
        }
        val type = object : TypeToken<List<Long>>() {}.type
        return gson.fromJson(json, type)
    }
}
