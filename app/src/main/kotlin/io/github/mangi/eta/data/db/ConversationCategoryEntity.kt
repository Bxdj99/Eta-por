package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/** 用户自建的会话分类；删除分类只解散归属，不删除会话。 */
@Serializable
@Entity(tableName = "conversation_categories")
internal data class ConversationCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * 会话 ↔ 分类的映射。
 *
 * 移植说明：fork 是把 `category_id` 直接加在 conversations 表上，那要动 3.4.1 的
 * ConversationEntity 和它的所有投影（风险大、牵连广）；这里改成独立映射表，
 * 语义一致、迁移只需建表，不碰既有列。
 */
@Entity(tableName = "conversation_category_links")
internal data class ConversationCategoryLinkEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
internal interface ConversationCategoryDao {
    @Query("SELECT * FROM conversation_categories ORDER BY sort_order ASC, created_at ASC")
    suspend fun categories(): List<ConversationCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: ConversationCategoryEntity)

    @Query("DELETE FROM conversation_categories WHERE id = :categoryId")
    suspend fun delete(categoryId: String)

    @Query("DELETE FROM conversation_categories")
    suspend fun clear()

    @Query("SELECT * FROM conversation_category_links")
    suspend fun links(): List<ConversationCategoryLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(entity: ConversationCategoryLinkEntity)

    @Query("DELETE FROM conversation_category_links WHERE conversation_id = :conversationId")
    suspend fun unlink(conversationId: String)

    @Query("DELETE FROM conversation_category_links WHERE category_id = :categoryId")
    suspend fun unlinkCategory(categoryId: String)

    @Query("DELETE FROM conversation_category_links")
    suspend fun clearLinks()
}
