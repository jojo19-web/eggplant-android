package com.example.egglance.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "scan_sessions")
data class ScanSession (
    @PrimaryKey
    val sessionID: String = UUID.randomUUID().toString(), //Gives a unique ID for each session
    val startingTimestamp: Long = System.currentTimeMillis(),
    var endingTimestamp: Long = 0L,
    var totalLeaves: Int = 0,
)
