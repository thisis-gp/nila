package com.nila.launcher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Someone NILA knows about: the user ("me") or a person they mention ("wife"). [key] is the about key. */
@Entity(tableName = "people", indices = [Index(value = ["about_key"], unique = true)])
internal data class PersonEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "about_key") val key: String,
    val name: String,
    val contactLookupKey: String?,
    val contactUri: String?,
    /** True once NILA has asked which contact this is; it asks at most once, even after "Not now". */
    val linkAsked: Boolean,
    val createdAt: Long,
    /** The relation a particular person was filed under ("cousin" for Kavya); null for the user and single relations. */
    val relation: String? = null,
)

@Entity(
    tableName = "facts",
    foreignKeys = [ForeignKey(PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("personId")],
)
internal data class FactEntity(
    @PrimaryKey val id: String,
    val personId: String,
    val text: String,
    val kind: String,
    val savedAt: Long,
)

/** A fact joined with its person's about key, name and relation. */
internal data class FactRow(
    val id: String,
    val text: String,
    val kind: String,
    val savedAt: Long,
    val about: String,
    val personName: String? = null,
    val relation: String? = null,
)

private const val FACT_ROWS =
    "SELECT f.id AS id, f.text AS text, f.kind AS kind, f.savedAt AS savedAt, p.about_key AS about, p.name AS personName, " +
        "p.relation AS relation FROM facts f JOIN people p ON p.id = f.personId ORDER BY f.savedAt, f.rowid"

@Dao
internal interface MemoryDao {
    @Query("SELECT * FROM people ORDER BY createdAt, rowid")
    fun observePeople(): Flow<List<PersonEntity>>

    @Query(FACT_ROWS)
    fun observeFacts(): Flow<List<FactRow>>

    @Query(FACT_ROWS)
    suspend fun allFacts(): List<FactRow>

    @Query("SELECT * FROM people WHERE relation = :relation ORDER BY createdAt, rowid")
    suspend fun peopleWithRelation(relation: String): List<PersonEntity>

    @Query("SELECT * FROM facts WHERE personId = :personId")
    suspend fun factsOf(personId: String): List<FactEntity>

    @Query("UPDATE facts SET personId = :personId WHERE id = :id")
    suspend fun moveFact(id: String, personId: String)

    @Query("UPDATE people SET name = :name, relation = :relation WHERE about_key = :key")
    suspend fun file(key: String, name: String, relation: String?)

    @Query("SELECT * FROM people WHERE about_key = :key LIMIT 1")
    suspend fun person(key: String): PersonEntity?

    @Query("SELECT text FROM facts WHERE personId = :personId")
    suspend fun textsOf(personId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPeople(people: List<PersonEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFacts(facts: List<FactEntity>)

    @Query("UPDATE people SET contactLookupKey = :lookupKey, contactUri = :uri, name = :name, linkAsked = 1 WHERE about_key = :key")
    suspend fun link(key: String, lookupKey: String, uri: String?, name: String)

    @Query("UPDATE people SET linkAsked = 1 WHERE about_key = :key")
    suspend fun markAsked(key: String)

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun deleteFact(id: String)

    @Query("DELETE FROM facts WHERE personId = (SELECT id FROM people WHERE about_key = :about) AND lower(text) = lower(:text)")
    suspend fun forgetFact(text: String, about: String)

    @Query("DELETE FROM people")
    suspend fun deleteAllPeople()
}

/** Version 2 adds the people.relation column. Existing rows keep everything and get a null relation. */
internal const val MIGRATION_1_2_SQL = "ALTER TABLE people ADD COLUMN relation TEXT"

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL(MIGRATION_1_2_SQL)
}

@Database(entities = [PersonEntity::class, FactEntity::class], version = 2, exportSchema = false)
internal abstract class MemoryDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        const val FILE = "nila_memory.db"

        fun open(context: Context, passphrase: ByteArray): MemoryDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, MemoryDatabase::class.java, FILE)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2)
                .build()
        }

        /** Removes the database and its journal files, for when the key can no longer open it. */
        fun wipe(context: Context) {
            context.getDatabasePath(FILE).parentFile?.listFiles { f: File -> f.name.startsWith(FILE) }?.forEach { it.delete() }
        }
    }
}

/**
 * The database passphrase: 32 random bytes, wrapped with an Android Keystore AES-GCM key and kept in
 * the `nila_memory_key` preferences. The Keystore key never leaves the secure hardware, so the stored
 * blob is useless off this phone.
 */
internal class MemoryKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The passphrase, made on first use. Null when a stored one can no longer be unwrapped. */
    fun passphrase(): ByteArray? {
        val stored = prefs.getString(WRAPPED, null) ?: return create()
        return runCatching { unwrap(Base64.decode(stored, Base64.NO_WRAP)) }.getOrNull()
    }

    /** Forgets the stored passphrase so [passphrase] makes a fresh one. */
    fun reset() {
        prefs.edit().remove(WRAPPED).commit()
    }

    private fun create(): ByteArray {
        val fresh = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)
        prefs.edit().putString(WRAPPED, Base64.encodeToString(wrap(fresh), Base64.NO_WRAP)).commit()
        return fresh
    }

    private fun wrapKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES)) }
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val PREFS = "nila_memory_key"
        const val WRAPPED = "wrapped"
        const val ALIAS = "nila_memory_wrap"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val PASSPHRASE_BYTES = 32
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
