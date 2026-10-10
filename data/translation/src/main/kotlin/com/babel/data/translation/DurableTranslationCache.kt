package com.babel.data.translation

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.domain.translation.TranslationCache
import com.babel.domain.translation.TranslationCacheKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [InMemoryTranslationCache] for everything, plus a small file of the entries
 * offered through [putDurable] — a game's names and interface words — so a
 * name keeps its translation across restarts and reinstalls of the build.
 *
 * Only those reach the file, and only short labels are offered
 * (`DefaultTranslationCoordinator`); dialogue stays in memory as before
 * (`docs/systems/privacy.md`). The file sits in `noBackupFilesDir`, so it
 * never leaves the device with a backup, and [clear] deletes it.
 *
 * Read once, on first use; written whole after each new durable entry, which
 * at a few hundred short labels is a few kilobytes.
 */
class DurableTranslationCache(
    private val file: File,
    private val memory: InMemoryTranslationCache = InMemoryTranslationCache(),
    private val maxDurable: Int = DEFAULT_MAX_DURABLE,
) : TranslationCache {

    private val mutex = Mutex()
    private var durable: LinkedHashMap<TranslationCacheKey, String>? = null

    override suspend fun get(key: TranslationCacheKey): String? =
        memory.get(key) ?: mutex.withLock { loaded()[key] }?.also { memory.put(key, it) }

    override suspend fun put(key: TranslationCacheKey, translatedText: String) = memory.put(key, translatedText)

    override suspend fun putDurable(key: TranslationCacheKey, translatedText: String) {
        memory.put(key, translatedText)
        mutex.withLock {
            val entries = loaded()
            if (entries[key] == translatedText) return@withLock
            entries.remove(key)
            entries[key] = translatedText
            while (entries.size > maxDurable) entries.remove(entries.keys.first())
            save(entries)
        }
    }

    override suspend fun clear() {
        memory.clear()
        mutex.withLock {
            durable = LinkedHashMap()
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    private suspend fun loaded(): LinkedHashMap<TranslationCacheKey, String> {
        durable?.let { return it }
        val read = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) emptyList() else json.decodeFromString(ListSerializer(Entry.serializer()), file.readText())
            }.getOrDefault(emptyList())
        }
        return LinkedHashMap<TranslationCacheKey, String>().also { map ->
            read.forEach { map[it.key()] = it.translation }
            durable = map
        }
    }

    private suspend fun save(entries: Map<TranslationCacheKey, String>) {
        val list = entries.map { (k, v) -> Entry(k.normalizedText, k.sourceLanguage?.value, k.targetLanguage.value, k.provider.value, k.schemaVersion, v) }
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val temp = File(file.path + ".tmp")
                temp.writeText(json.encodeToString(ListSerializer(Entry.serializer()), list))
                java.nio.file.Files.move(
                    temp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            }
        }
    }

    @Serializable
    private data class Entry(
        val text: String,
        val source: String?,
        val target: String,
        val provider: String,
        val schema: Int,
        val translation: String,
    ) {
        fun key() = TranslationCacheKey(text, source?.let(::LanguageTag), LanguageTag(target), ProviderId(provider), schema)
    }

    companion object {
        const val DEFAULT_MAX_DURABLE = 2000
        private val json = Json { ignoreUnknownKeys = true }
    }
}
