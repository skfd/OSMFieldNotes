package com.surveyme.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.surveyme.data.model.NoteActionRequest
import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi

@Database(
    entities = [Poi::class, TrackEntity::class, LocationPointEntity::class, OsmNote::class, NoteActionRequest::class],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class SurveyMeDatabase : RoomDatabase() {

    abstract fun poiDao(): PoiDao
    abstract fun trackDao(): TrackDao
    abstract fun noteDao(): NoteDao
    abstract fun noteActionDao(): NoteActionDao

    companion object {
        @Volatile
        private var INSTANCE: SurveyMeDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracks` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `totalDistance` REAL NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, `duration` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `altitude` REAL NOT NULL, `time` INTEGER NOT NULL, `distanceFromStart` REAL NOT NULL, FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_points_trackId` ON `location_points` (`trackId`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pois` ADD COLUMN `isMuted` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4: structured notes live in their own table next to the POI row. Existing note POIs
         * keep working with no note row until the next refetch fills one in. Never destructive:
         * the maintainer has live data.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `osm_notes` (`noteId` INTEGER NOT NULL, `poiId` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `closedAt` INTEGER, `comments` TEXT NOT NULL, `labels` TEXT NOT NULL, `surveyAfter` INTEGER, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`noteId`))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_osm_notes_poiId` ON `osm_notes` (`poiId`)")
            }
        }

        /**
         * v5: the offline queue of comment / close / reopen requests (item 6). Rows are posted
         * in id order and deleted once osm.org answered. Create-only; nothing existing changes.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, `action` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT)"
                )
            }
        }

        fun getDatabase(context: Context): SurveyMeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SurveyMeDatabase::class.java,
                    "surveyme_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}