package com.codex.meetingassistant.data.local

import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.codex.meetingassistant.data.model.MeetingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "meetings")
data class MeetingEntity(
    @PrimaryKey val id: String,
    val titleCipher: String,
    val agendaCipher: String,
    val status: String,
    val createdAtEpochMs: Long,
    val startedAtEpochMs: Long?,
    val endedAtEpochMs: Long?,
    val summariesPaused: Boolean,
)

@Entity(tableName = "audio_assets")
data class AudioAssetEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val encryptedFilePathCipher: String,
    val format: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val durationMs: Long,
)

@Entity(tableName = "transcript_segments")
data class TranscriptSegmentEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val startMs: Long,
    val endMs: Long,
    val textCipher: String,
    val speakerLabelCipher: String,
    val speakerConfidence: Float,
    val asrConfidence: Float,
    val isFinal: Boolean,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "speaker_profiles")
data class SpeakerProfileEntity(
    @PrimaryKey val id: String,
    val displayNameCipher: String,
    val embeddingCipher: String,
    val keepsAudioSamples: Boolean,
    val enrollmentCount: Int,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "speaker_assignments")
data class SpeakerAssignmentEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val speakerLabelCipher: String,
    val profileId: String?,
    val confidence: Float,
    val lockedByUser: Boolean,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "chunk_summaries")
data class ChunkSummaryEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val startMs: Long,
    val endMs: Long,
    val headlineCipher: String,
    val bulletsCipher: String,
    val modelPackId: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "meeting_summaries")
data class MeetingSummaryEntity(
    @PrimaryKey val meetingId: String,
    val overviewCipher: String,
    val decisionsCipher: String,
    val actionItemsCipher: String,
    val risksCipher: String,
    val openQuestionsCipher: String,
    val speakerNotesCipher: String,
    val modelPackId: String,
    val generatedAtEpochMs: Long,
)

@Entity(tableName = "model_packs")
data class ModelPackEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val description: String,
    val stage: String,
    val quantization: String,
    val estimatedSizeMb: Int,
    val assetPath: String,
    val status: String,
    val minRamGb: Int,
    val minScore: Int,
    val supportedLanguages: List<String>,
)

class AppTypeConverters {
    @TypeConverter
    fun encodeStringList(value: List<String>): String = Json.encodeToString<List<String>>(value)

    @TypeConverter
    fun decodeStringList(value: String): List<String> = Json.decodeFromString<List<String>>(value)
}

@Dao
interface MeetingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeeting(entity: MeetingEntity)

    @Query("SELECT * FROM meetings ORDER BY createdAtEpochMs DESC")
    fun observeMeetings(): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE id = :meetingId LIMIT 1")
    fun observeMeeting(meetingId: String): Flow<MeetingEntity?>

    @Query("SELECT * FROM meetings WHERE status IN (:statuses) ORDER BY createdAtEpochMs DESC LIMIT 1")
    fun observeLatestMeetingByStatuses(statuses: List<String>): Flow<MeetingEntity?>
}

@Dao
interface AudioAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAsset(entity: AudioAssetEntity)

    @Query("SELECT * FROM audio_assets WHERE meetingId = :meetingId LIMIT 1")
    fun observeAsset(meetingId: String): Flow<AudioAssetEntity?>
}

@Dao
interface TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegment(entity: TranscriptSegmentEntity)

    @Query("SELECT * FROM transcript_segments WHERE meetingId = :meetingId ORDER BY startMs ASC, createdAtEpochMs ASC")
    fun observeSegments(meetingId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("UPDATE transcript_segments SET speakerLabelCipher = :speakerLabelCipher WHERE meetingId = :meetingId AND speakerLabelCipher = :oldCipher")
    suspend fun renameSpeakerLabel(meetingId: String, oldCipher: String, speakerLabelCipher: String)
}

@Dao
interface SpeakerProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(entity: SpeakerProfileEntity)

    @Query("SELECT * FROM speaker_profiles ORDER BY createdAtEpochMs ASC")
    fun observeProfiles(): Flow<List<SpeakerProfileEntity>>
}

@Dao
interface SpeakerAssignmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssignment(entity: SpeakerAssignmentEntity)

    @Query("SELECT * FROM speaker_assignments WHERE meetingId = :meetingId ORDER BY updatedAtEpochMs DESC")
    fun observeAssignments(meetingId: String): Flow<List<SpeakerAssignmentEntity>>

    @Query("UPDATE speaker_assignments SET speakerLabelCipher = :speakerLabelCipher, updatedAtEpochMs = :updatedAtEpochMs WHERE meetingId = :meetingId AND speakerLabelCipher = :oldCipher")
    suspend fun renameSpeakerLabel(
        meetingId: String,
        oldCipher: String,
        speakerLabelCipher: String,
        updatedAtEpochMs: Long,
    )
}

@Dao
interface ChunkSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunkSummary(entity: ChunkSummaryEntity)

    @Query("SELECT * FROM chunk_summaries WHERE meetingId = :meetingId ORDER BY startMs ASC")
    fun observeChunkSummaries(meetingId: String): Flow<List<ChunkSummaryEntity>>
}

@Dao
interface MeetingSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeetingSummary(entity: MeetingSummaryEntity)

    @Query("SELECT * FROM meeting_summaries WHERE meetingId = :meetingId LIMIT 1")
    fun observeMeetingSummary(meetingId: String): Flow<MeetingSummaryEntity?>
}

@Dao
interface ModelPackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPacks(entities: List<ModelPackEntity>)

    @Query("SELECT * FROM model_packs ORDER BY stage ASC, estimatedSizeMb ASC")
    fun observeModelPacks(): Flow<List<ModelPackEntity>>

    @Query("SELECT COUNT(*) FROM model_packs")
    suspend fun count(): Int
}

@Database(
    entities = [
        MeetingEntity::class,
        AudioAssetEntity::class,
        TranscriptSegmentEntity::class,
        SpeakerProfileEntity::class,
        SpeakerAssignmentEntity::class,
        ChunkSummaryEntity::class,
        MeetingSummaryEntity::class,
        ModelPackEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(AppTypeConverters::class)
abstract class MeetingAssistantDatabase : RoomDatabase() {
    abstract fun meetingDao(): MeetingDao
    abstract fun audioAssetDao(): AudioAssetDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun speakerProfileDao(): SpeakerProfileDao
    abstract fun speakerAssignmentDao(): SpeakerAssignmentDao
    abstract fun chunkSummaryDao(): ChunkSummaryDao
    abstract fun meetingSummaryDao(): MeetingSummaryDao
    abstract fun modelPackDao(): ModelPackDao
}
