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
    version = 7,
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

        /**
         * v6: a `create` row carries where the note goes (item 7). Two nullable columns; the
         * existing comment / close / reopen rows keep them null.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `note_actions` ADD COLUMN `lat` REAL")
                db.execSQL("ALTER TABLE `note_actions` ADD COLUMN `lon` REAL")
            }
        }

        /**
         * v7: the OSM element behind a fixme as columns (item 10). Three nullable columns, and
         * the existing `overpass_{type}_{id}` ids are split into them in SQL so old rows hand
         * off without a refetch; the version stays null until Overpass reports one.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pois` ADD COLUMN `osmElementType` TEXT")
                db.execSQL("ALTER TABLE `pois` ADD COLUMN `osmElementId` INTEGER")
                db.execSQL("ALTER TABLE `pois` ADD COLUMN `osmElementVersion` INTEGER")
                // 'overpass_' is 9 characters; GLOB, not LIKE, because '_' is a LIKE wildcard.
                db.execSQL(
                    "UPDATE `pois` SET " +
                        "`osmElementType` = substr(`id`, 10, instr(substr(`id`, 10), '_') - 1), " +
                        "`osmElementId` = CAST(substr(`id`, 10 + instr(substr(`id`, 10), '_')) AS INTEGER) " +
                        "WHERE `id` GLOB 'overpass_node_[0-9]*' OR `id` GLOB 'overpass_way_[0-9]*' " +
                        "OR `id` GLOB 'overpass_relation_[0-9]*'"
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}