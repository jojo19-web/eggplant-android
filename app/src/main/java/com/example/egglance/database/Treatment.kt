package com.example.egglance.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "treatments")
data class Treatment (
    @PrimaryKey(autoGenerate = true)
    val treatmentID: Int = 0,
    val treatmentTitle: String,
    val treatmentProcedures: String,
    val treatmentType: String,
    val dateAdded: Long = System.currentTimeMillis(),
    var dateUpdated: Long = System.currentTimeMillis()
)