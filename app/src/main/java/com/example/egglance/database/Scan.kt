package com.example.egglance.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "scans",
    foreignKeys = [
        ForeignKey(
            entity = ScanSession::class,
            parentColumns = ["sessionID"],
            childColumns = ["parentSessionID"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)

data class Scan(
    @PrimaryKey(autoGenerate = true)
    val scanID: Int = 0,

    val parentSessionID: String, // Links back to the UUID in ScanSession
    var imagePath: String, // The storage location of the eggplant leaf photo
    val dateTimeCaptured: Long = System.currentTimeMillis(),
    var confidenceScore: Float /*Made it var for now, since in live capture,
                                confidenceScore can change easily */
)