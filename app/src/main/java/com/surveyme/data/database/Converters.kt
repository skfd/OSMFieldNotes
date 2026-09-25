package com.surveyme.data.database

import androidx.room.TypeConverter
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.surveyme.data.model.NoteComment
import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.PoiCategory
import java.util.Date

class Converters {
    // Dates inside JSON columns go as epoch millis; Gson's default is a locale-formatted string
    // that does not round-trip when the device locale changes.
    private val gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, EpochMillisDateAdapter())
        .create()

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromPoiCategory(category: PoiCategory): String {
        return category.name
    }

    @TypeConverter
    fun toPoiCategory(categoryString: String): PoiCategory {
        return PoiCategory.fromString(categoryString)
    }

    @TypeConverter
    fun fromStringMap(value: String): Map<String, String> {
        val type = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(value, type)
    }

    @TypeConverter
    fun fromMapToString(map: Map<String, String>): String {
        return gson.toJson(map)
    }

    @TypeConverter
    fun fromNoteStatus(status: NoteStatus): String = status.apiValue

    @TypeConverter
    fun toNoteStatus(value: String): NoteStatus = NoteStatus.fromApi(value)

    @TypeConverter
    fun fromStringSet(value: String): Set<String> {
        val type = object : TypeToken<Set<String>>() {}.type
        return gson.fromJson(value, type) ?: emptySet()
    }

    @TypeConverter
    fun fromSetToString(set: Set<String>): String = gson.toJson(set)

    @TypeConverter
    fun fromCommentList(value: String): List<NoteComment> {
        val type = object : TypeToken<List<NoteComment>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

    @TypeConverter
    fun fromCommentListToString(comments: List<NoteComment>): String = gson.toJson(comments)

    private class EpochMillisDateAdapter : TypeAdapter<Date>() {
        override fun write(out: JsonWriter, value: Date?) {
            if (value == null) out.nullValue() else out.value(value.time)
        }

        override fun read(reader: JsonReader): Date? {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return null
            }
            return Date(reader.nextLong())
        }
    }
}
