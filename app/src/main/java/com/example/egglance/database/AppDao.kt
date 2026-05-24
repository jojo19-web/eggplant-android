package com.example.eggplantdetector.database

import androidx.room.*

@Dao
interface AppDao {
    // ScanSession Operations
    @Insert
    suspend fun insertSession(session: ScanSession)

    @Query(value = "SELECT * FROM scan_sessions ORDER BY startingTimestamp DESC")
    suspend fun getAllSessions(): List<ScanSession>

    @Update
    suspend fun updateSession(session: ScanSession)

    // Scan Operations
    @Insert
    suspend fun insertScan(scan: Scan)

    @Query(value = "SELECT * FROM scans WHERE parentSessionID = :sessionId")
    suspend fun getScansForSession(sessionId: String): List<Scan>

    // Disease Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiseases(diseases: List<Disease>)

    @Query(value = "SELECT * FROM diseases WHERE diseaseID = :id")
    suspend fun getDiseaseById(id: Int): Disease?

    @Query(value = "SELECT * FROM diseases")
    suspend fun getAllDiseases(): List<Disease>

    // Treatment Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTreatment(treatment: Treatment)

    // Junction Table for Disease Treatment entity
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiseaseTreatment(crossRef: DiseaseTreatment)
}