package com.trollman.linernotes.data

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.trollman.linernotes.cloud.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Where group sets and writing settings are kept. */
interface SetStore {
    suspend fun sets(): List<GroupSet>
    suspend fun save(set: GroupSet)
    suspend fun delete(id: String)
    suspend fun writing(): Writing
    suspend fun saveWriting(writing: Writing)
}

internal val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** On the phone only: for builds without Firebase, and before signing in. */
class LocalSetStore(context: Context) : SetStore {
    private val dir = File(context.filesDir, "sets").apply { mkdirs() }
    private val writingFile = File(context.filesDir, "writing.json")

    override suspend fun sets(): List<GroupSet> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { storeJson.decodeFromString<GroupSet>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun save(set: GroupSet) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "${set.id}.tmp")
        tmp.writeText(storeJson.encodeToString(set))
        tmp.renameTo(File(dir, "${set.id}.json"))
        Unit
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        Unit
    }

    override suspend fun writing(): Writing = withContext(Dispatchers.IO) {
        if (!writingFile.exists()) Writing()
        else runCatching { storeJson.decodeFromString<Writing>(writingFile.readText()) }.getOrDefault(Writing())
    }

    override suspend fun saveWriting(writing: Writing) = withContext(Dispatchers.IO) {
        writingFile.writeText(storeJson.encodeToString(writing))
    }
}

/**
 * In Firestore, so sets follow the account across phones: users/{uid}/sets/{id} and
 * users/{uid}/settings/writing, each holding the JSON in a `json` field.
 */
class CloudSetStore(private val uid: String) : SetStore {
    private val db = FirebaseFirestore.getInstance()
    private val user get() = db.collection("users").document(uid)

    override suspend fun sets(): List<GroupSet> =
        user.collection("sets").get().await().documents
            .mapNotNull { d -> d.getString("json")?.let { runCatching { storeJson.decodeFromString<GroupSet>(it) }.getOrNull() } }
            .sortedByDescending { it.updatedAt }

    override suspend fun save(set: GroupSet) {
        user.collection("sets").document(set.id)
            .set(mapOf("json" to storeJson.encodeToString(set), "updatedAt" to set.updatedAt, "title" to set.title))
            .await()
    }

    override suspend fun delete(id: String) {
        user.collection("sets").document(id).delete().await()
    }

    override suspend fun writing(): Writing {
        val json = user.collection("settings").document("writing").get().await().getString("json") ?: return Writing()
        return runCatching { storeJson.decodeFromString<Writing>(json) }.getOrDefault(Writing())
    }

    override suspend fun saveWriting(writing: Writing) {
        user.collection("settings").document("writing")
            .set(mapOf("json" to storeJson.encodeToString(writing)), SetOptions.merge())
            .await()
    }
}
