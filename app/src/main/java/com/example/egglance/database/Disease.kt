package com.example.egglance.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diseases")
data class Disease (
    @PrimaryKey(autoGenerate = true)
    val diseaseID: Int = 0,
    val diseaseName: String,
    val diseaseDescription: String,
    val dateAdded: Long = System.currentTimeMillis(),
    var dateUpdated: Long = System.currentTimeMillis(),
)