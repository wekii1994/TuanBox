package com.github.yumeyucca.yumebox.data.trafficv2

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId

@Entity(tableName = "service_traffic_hourly", primaryKeys = ["hourMillis", "targetKey", "appKey", "basis"])
data class ServiceBucket(
    val hourMillis: Long, val targetKey: String, val appKey: String, val basis: String,
    val targetLabel: String, val appLabel: String, val packageName: String?,
    val upload: Long, val download: Long, val lastTrafficAt: Long,
) {
    fun delta() = LedgerDelta(hourMillis, targetKey, targetLabel, appKey, appLabel, packageName,
        basis, Bytes(upload, download), lastTrafficAt)
}
@Entity(tableName = "service_batch_cursor")
data class BatchCursor(@PrimaryKey val writer: String, val sequence: Long, val updatedAt: Long)
@Entity(tableName = "service_coverage_gap", primaryKeys = ["writer", "sequence", "ordinal"])
data class GapEntity(
    val writer: String, val sequence: Long, val ordinal: Int,
    val fromMillis: Long, val toMillis: Long, val reason: String,
)

@Dao
abstract class ServiceTrafficDao : LedgerSink {
    @Query("SELECT * FROM service_traffic_hourly WHERE hourMillis >= :from AND hourMillis < :until ORDER BY hourMillis, targetKey, appKey, basis")
    abstract fun observe(from: Long, until: Long): Flow<List<ServiceBucket>>

    @Query("SELECT * FROM service_coverage_gap WHERE toMillis >= :from AND fromMillis < :until ORDER BY fromMillis")
    abstract fun observeGaps(from: Long, until: Long): Flow<List<GapEntity>>

    @Query("SELECT * FROM service_batch_cursor WHERE writer = :writer")
    protected abstract suspend fun cursor(writer: String): BatchCursor?

    @Query("SELECT * FROM service_traffic_hourly WHERE hourMillis = :hour AND targetKey = :target AND appKey = :app AND basis = :basis")
    protected abstract suspend fun bucket(hour: Long, target: String, app: String, basis: String): ServiceBucket?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putBucket(row: ServiceBucket)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putCursor(cursor: BatchCursor)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun putGap(gap: GapEntity)

    @Transaction
    override suspend fun persist(batch: LedgerBatch) {
        val current = cursor(batch.writer)?.sequence ?: 0L
        if (batch.sequence <= current) return // Lost response or replay: exactly one durable addition.
        check(batch.sequence == current + 1L) { "Out-of-order ledger batch" }
        for (delta in batch.rows) {
            val old = bucket(delta.hourMillis, delta.targetKey, delta.appKey, delta.basis)
            val bytes = (old?.let { Bytes(it.upload, it.download) } ?: Bytes()) + delta.bytes
            val newer = old == null || delta.lastTrafficAt >= old.lastTrafficAt
            putBucket(ServiceBucket(
                delta.hourMillis, delta.targetKey, delta.appKey, delta.basis,
                if (newer) delta.targetLabel else old!!.targetLabel,
                if (newer) delta.appLabel else old!!.appLabel,
                if (newer) delta.packageName else old!!.packageName,
                bytes.up, bytes.down, maxOf(old?.lastTrafficAt ?: 0L, delta.lastTrafficAt),
            ))
        }
        batch.gaps.forEachIndexed { index, gap ->
            putGap(GapEntity(batch.writer, batch.sequence, index, gap.fromMillis, gap.toMillis, gap.reason))
        }
        putCursor(BatchCursor(batch.writer, batch.sequence, System.currentTimeMillis()))
    }

    @Query("DELETE FROM service_traffic_hourly") protected abstract suspend fun deleteServices()
    @Query("DELETE FROM service_coverage_gap") protected abstract suspend fun deleteGaps()
    @Query("DELETE FROM traffic_hourly") protected abstract suspend fun deleteLegacyHours()
    @Query("DELETE FROM app_traffic_daily") protected abstract suspend fun deleteLegacyApps()
    @Query("DELETE FROM route_traffic_daily") protected abstract suspend fun deleteLegacyRoutes()

    @Transaction
    override suspend fun clearStatistics() {
        deleteServices(); deleteGaps()
        deleteLegacyHours(); deleteLegacyApps(); deleteLegacyRoutes()
        // Do not reset the active writer's cursor: its next sequence must remain valid.
    }

    @Query("DELETE FROM service_traffic_hourly WHERE hourMillis < :cutoff")
    protected abstract suspend fun pruneServices(cutoff: Long)
    @Query("DELETE FROM service_coverage_gap WHERE toMillis < :cutoff")
    protected abstract suspend fun pruneGaps(cutoff: Long)

    /** Call at most once per local date from the existing service loop, never from a new timer. */
    @Transaction
    open suspend fun prune(cutoff: Long) { pruneServices(cutoff); pruneGaps(cutoff) }
}

/** Existing repository is DB v2. Rebase version numbers if the target branch has moved. */
val SERVICE_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS service_traffic_hourly (
            hourMillis INTEGER NOT NULL, targetKey TEXT NOT NULL, appKey TEXT NOT NULL, basis TEXT NOT NULL,
            targetLabel TEXT NOT NULL, appLabel TEXT NOT NULL, packageName TEXT,
            upload INTEGER NOT NULL, download INTEGER NOT NULL, lastTrafficAt INTEGER NOT NULL,
            PRIMARY KEY(hourMillis, targetKey, appKey, basis))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS service_batch_cursor (
            writer TEXT NOT NULL PRIMARY KEY, sequence INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS service_coverage_gap (
            writer TEXT NOT NULL, sequence INTEGER NOT NULL, ordinal INTEGER NOT NULL,
            fromMillis INTEGER NOT NULL, toMillis INTEGER NOT NULL, reason TEXT NOT NULL,
            PRIMARY KEY(writer, sequence, ordinal))""")
        // No historical service identity exists. Preserve the hourly source as Other exactly once.
        db.execSQL("""INSERT INTO service_traffic_hourly
            SELECT hour_start_millis, 'other', 'unknown', 'legacy_hourly', '其他流量',
                '来源应用未知', NULL, total_upload, total_download, hour_start_millis
            FROM traffic_hourly WHERE total_upload > 0 OR total_download > 0""")

        // v1 -> v2 added hourly storage without reconstructing older hours. Preserve daily-only days.
        // If a day has any hourly records, hourly is the authoritative historical total for that day.
        val zone = ZoneId.systemDefault()
        db.query("SELECT date_millis, SUM(total_upload), SUM(total_download) FROM app_traffic_daily GROUP BY date_millis").use { days ->
            while (days.moveToNext()) {
                val start = days.getLong(0)
                val end = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().plusDays(1)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                val hasHours = db.query("SELECT 1 FROM traffic_hourly WHERE hour_start_millis >= ? AND hour_start_millis < ? LIMIT 1", arrayOf(start, end)).use { it.moveToFirst() }
                if (!hasHours) db.execSQL("""INSERT OR IGNORE INTO service_traffic_hourly VALUES
                    (?, 'other', 'unknown', 'legacy_daily', '其他流量', '来源应用未知', NULL, ?, ?, ?)""",
                    arrayOf(start, days.getLong(1), days.getLong(2), start))
            }
        }
    }
}
