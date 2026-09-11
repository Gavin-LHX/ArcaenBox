package io.nekohasekai.sagernet.database

import androidx.room.*

@Entity(tableName = "node_test_results", primaryKeys = ["profileId", "kind"],
    foreignKeys = [ForeignKey(entity = ProxyEntity::class, parentColumns = ["id"],
        childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)])
data class NodeTestResult(
    val profileId: Long,
    val kind: String,
    val value: Long,
    val testedAt: Long,
    val error: String = "",
    val transferred: Long = 0,
) {
    @androidx.room.Dao interface Dao {
        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun put(result: NodeTestResult)
        @Query("SELECT * FROM node_test_results WHERE profileId IN (:ids)")
        fun forProfiles(ids: List<Long>): List<NodeTestResult>
        @Query("SELECT node_test_results.* FROM node_test_results INNER JOIN proxy_entities ON profileId = proxy_entities.id WHERE groupId = :groupId")
        fun forGroup(groupId: Long): List<NodeTestResult>
        @Query("DELETE FROM node_test_results WHERE profileId IN (SELECT id FROM proxy_entities WHERE groupId = :groupId)")
        fun clearGroup(groupId: Long)
        @Query("DELETE FROM node_test_results WHERE profileId IN (:ids)")
        fun clear(ids: List<Long>)
    }
}
