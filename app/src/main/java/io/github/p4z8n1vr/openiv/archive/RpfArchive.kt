package io.github.p4z8n1vr.openiv.archive

import io.github.p4z8n1vr.openiv.nativeio.NativeDeflate
import io.github.p4z8n1vr.openiv.nativeio.NativeDeflateException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.SecretKeySpec

sealed class RpfEntry protected constructor(nameBytes: ByteArray) {
    internal var encodedName = nameBytes
        set(value) {
            field = value
            name = value.decodeToString()
        }
    internal var parent: RpfDirectory? = null

    var name: String = nameBytes.decodeToString()
        private set
}

class RpfDirectory internal constructor(name: ByteArray) : RpfEntry(name) {
    internal val mutableChildren = mutableListOf<RpfEntry>()
    val children: List<RpfEntry> = Collections.unmodifiableList(mutableChildren)
}

class RpfFile internal constructor(name: ByteArray, stored: Payload) : RpfEntry(name) {
    internal var payload = stored

    val size: Long get() = payload.logicalSize
    val isResource: Boolean get() = payload.isResource
    val isCompressed: Boolean get() = !isResource && payload.packedSize > 0
    val isEntryEncrypted: Boolean
        get() = if (isResource) name.isScriptResource() else payload.extra == 1L
}

internal data class Payload(
    val file: File,
    val offset: Long,
    val storedSize: Long,
    val logicalSize: Long,
    val packedSize: Int,
    val metadata: Long,
    val extra: Long,
    val isResource: Boolean,
    val updateResourceSizeHeader: Boolean = false,
)

enum class RpfEncryption(val displayName: String) {
    NONE("Unencrypted"),
    OPEN("OPEN"),
    AES("AES"),
}

/** Edits an RPF7 tree in memory. The caller owns its input files and destination streams. */
class RpfArchive private constructor(
    private val file: File,
    private val magic: Long,
    private val encryption: Long,
    private val aesKey: ByteArray?,
    val root: RpfDirectory,
    initialEntryCount: Int,
) {
    var entryCount: Int = initialEntryCount
        private set
    var hasChanges: Boolean = false
        private set

    val encryptionMode: RpfEncryption
        get() = when (encryption) {
            ENCRYPTION_AES -> RpfEncryption.AES
            ENCRYPTION_OPEN -> RpfEncryption.OPEN
            else -> RpfEncryption.NONE
        }

    val encryptionName: String get() = encryptionMode.displayName

    /** Extracts decoded binary data or an RSC7 resource with its stored payload. */
    fun extract(entry: RpfFile, output: OutputStream) {
        requireMember(entry)
        val payload = entry.payload
        RandomAccessFile(payload.file, "r").use { source ->
            source.seek(payload.offset)
            if (entry.isResource) {
                extractResource(payload, source, output)
                return
            }

            val input = if (entry.isEntryEncrypted) {
                if (encryption != ENCRYPTION_AES || aesKey == null) {
                    throw RpfException(
                        RpfErrorCode.ENTRY_ENCRYPTION_UNSUPPORTED,
                        "This binary entry uses unsupported encryption.",
                    )
                }
                val encryptedSize = payload.storedSize - payload.storedSize % AES_BLOCK_SIZE
                val encrypted = ArchiveSlice(source, encryptedSize)
                val tail = ArchiveSlice(source, payload.storedSize - encryptedSize)
                SequenceInputStream(CipherInputStream(encrypted, aesCipher(aesKey, Cipher.DECRYPT_MODE)), tail)
            } else {
                ArchiveSlice(source, payload.storedSize)
            }
            input.use {
                if (payload.packedSize == 0) {
                    copyExactly(it, output, payload.storedSize, ByteArray(COPY_BUFFER_SIZE))
                } else {
                    try {
                        NativeDeflate.inflate(it, output, payload.storedSize, payload.logicalSize, payload.logicalSize)
                    } catch (exception: NativeDeflateException) {
                        throw RpfException(RpfErrorCode.DEFLATE_FAILED, exception.message, exception)
                    }
                }
            }
        }
    }

    fun rename(entry: RpfEntry, name: String) {
        requireMember(entry)
        val parent = entry.parent
            ?: throw RpfException(RpfErrorCode.ROOT_RENAME_FORBIDDEN, "The archive root cannot be renamed.")
        if (entry.name == name) return
        val encoded = validateNewName(parent, name, entry)
        if (entry is RpfFile && entry.isResource && entry.isEntryEncrypted != name.isScriptResource()) {
            throw RpfException(
                RpfErrorCode.RESOURCE_ENCRYPTION_RENAME,
                "Renaming this resource would change its encryption classification.",
            )
        }
        entry.encodedName = encoded
        hasChanges = true
    }

    fun remove(entry: RpfEntry) {
        requireMember(entry)
        val parent = entry.parent
            ?: throw RpfException(RpfErrorCode.ROOT_DELETE_FORBIDDEN, "The archive root cannot be deleted.")
        val descendants = ArrayDeque<RpfEntry>()
        descendants.addLast(entry)
        while (descendants.isNotEmpty()) {
            val current = descendants.removeLast()
            if (current is RpfDirectory) descendants.addAll(current.children)
            entryCount--
        }
        parent.mutableChildren.remove(entry)
        entry.parent = null
        hasChanges = true
    }

    fun add(directory: RpfDirectory, name: String, source: File): RpfFile {
        requireMember(directory)
        val encoded = validateNewName(directory, name)
        ensureEntryCapacity()
        val entry = RpfFile(encoded, importedPayload(name, source))
        return addChild(directory, entry)
    }

    fun createDirectory(directory: RpfDirectory, name: String): RpfDirectory {
        requireMember(directory)
        val encoded = validateNewName(directory, name)
        ensureEntryCapacity()
        return addChild(directory, RpfDirectory(encoded))
    }

    fun replace(entry: RpfFile, source: File) {
        requireMember(entry)
        if (entry.isResource && entry.isEntryEncrypted) {
            throw RpfException(
                RpfErrorCode.ENCRYPTED_RESOURCE_MUTATION,
                "Encrypted script resources cannot be replaced.",
            )
        }
        entry.payload = importedPayload(entry.name, source)
        hasChanges = true
    }

    /** Writes an independent archive. The destination must not be open on any source file. */
    fun write(output: OutputStream) {
        if (!hasChanges) {
            file.inputStream().buffered().use { it.copyTo(output) }
            return
        }

        val entries = mutableListOf<RpfEntry>(root)
        val directories = ArrayDeque<RpfDirectory>()
        val directoryStart = mutableMapOf<RpfDirectory, Int>()
        directories.add(root)
        while (directories.isNotEmpty()) {
            val directory = directories.removeFirst()
            directoryStart[directory] = entries.size
            val children = directory.children.sortedBy { it.name }
            entries.addAll(children)
            children.filterIsInstance<RpfDirectory>().forEach(directories::addLast)
        }

        val names = ByteArrayOutputStream()
        val nameOffsets = mutableMapOf<ByteBuffer, Int>()
        fun assignNameOffset(entry: RpfEntry): Int {
            val offset = nameOffsets.getOrPut(ByteBuffer.wrap(entry.encodedName)) {
                names.size().also {
                    val resultingSize = it + entry.encodedName.size + 1
                    if (resultingSize + paddingFor(resultingSize.toLong(), AES_BLOCK_SIZE) > MAX_NAME_TABLE_SIZE) {
                        throw RpfException(
                            RpfErrorCode.NAME_TABLE_TOO_LARGE,
                            "The archive's filename table is too large.",
                        )
                    }
                    names.write(entry.encodedName)
                    names.write(0)
                }
            }
            if (entry is RpfFile && offset > MAX_FILE_NAME_OFFSET) {
                throw RpfException(
                    RpfErrorCode.NAME_TABLE_TOO_LARGE,
                    "The file name table exceeds the RPF7 limit for file entries.",
                )
            }
            return offset
        }
        // File records have 16-bit name offsets. Directory offsets can follow the file names.
        assignNameOffset(root)
        entries.filterIsInstance<RpfFile>().forEach(::assignNameOffset)
        val offsets = entries.map(::assignNameOffset)
        val namePadding = paddingFor(names.size().toLong(), AES_BLOCK_SIZE)
        names.write(ByteArray(namePadding))
        val nameTable = names.toByteArray()

        val records = ByteBuffer.allocate(entries.size * RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = HEADER_SIZE + records.capacity().toLong() + nameTable.size
        val headerPadding = paddingFor(headerSize, SECTOR_SIZE)
        var currentSector = (headerSize + headerPadding) / SECTOR_SIZE
        for ((index, entry) in entries.withIndex()) {
            val nameOffset = offsets[index]
            when (entry) {
                is RpfDirectory -> {
                    records.putInt(nameOffset)
                    records.putInt(DIRECTORY_TAG.toInt())
                    records.putInt(directoryStart.getValue(entry))
                    records.putInt(entry.children.size)
                }
                is RpfFile -> {
                    val payload = entry.payload
                    if (currentSector >= MAX_SECTOR) {
                        throw RpfException(RpfErrorCode.SECTOR_LIMIT, "The archive exceeds the RPF7 sector limit.")
                    }
                    val resourceBit = if (entry.isResource) RESOURCE_SECTOR_FLAG else 0
                    records.putShort(nameOffset.toShort())
                    records.putU24(payload.packedSize)
                    records.putU24(currentSector.toInt() or resourceBit)
                    records.putInt(payload.metadata.toInt())
                    records.putInt(payload.extra.toInt())
                    currentSector += (payload.storedSize + paddingFor(payload.storedSize, SECTOR_SIZE)) / SECTOR_SIZE
                }
            }
        }

        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(magic.toInt()).putInt(entries.size).putInt(nameTable.size).putInt(encryption.toInt())
        output.write(header.array())
        output.write(transformTable(records.array(), aesKey, Cipher.ENCRYPT_MODE))
        output.write(transformTable(nameTable, aesKey, Cipher.ENCRYPT_MODE))
        output.write(ByteArray(headerPadding))

        val buffer = ByteArray(COPY_BUFFER_SIZE)
        RandomAccessFile(file, "r").use { original ->
            for (entry in entries.filterIsInstance<RpfFile>()) {
                val payload = entry.payload
                if (payload.file == file) {
                    writeResourceOrRaw(payload, original, output, buffer)
                } else {
                    RandomAccessFile(payload.file, "r").use { staged ->
                        writeResourceOrRaw(payload, staged, output, buffer)
                    }
                }
                output.write(ByteArray(paddingFor(payload.storedSize, SECTOR_SIZE)))
            }
        }
    }

    private fun requireMember(entry: RpfEntry) {
        var top = entry
        while (top.parent != null) top = checkNotNull(top.parent)
        if (top !== root) {
            throw RpfException(RpfErrorCode.ENTRY_NOT_IN_ARCHIVE, "The entry does not belong to this archive.")
        }
    }

    private fun ensureEntryCapacity() {
        if (entryCount == MAX_ENTRY_COUNT) {
            throw RpfException(RpfErrorCode.ENTRY_COUNT_LIMIT, "The archive has too many entries.")
        }
    }

    private fun <T : RpfEntry> addChild(parent: RpfDirectory, entry: T): T {
        parent.mutableChildren.add(entry)
        entry.parent = parent
        entryCount++
        hasChanges = true
        return entry
    }

    companion object {
        fun open(file: File, aesKey: ByteArray? = null): RpfArchive {
            RandomAccessFile(file, "r").use { source ->
                val fileSize = source.length()
                if (fileSize < HEADER_SIZE) {
                    throw RpfException(RpfErrorCode.INVALID_ARCHIVE, "The file is not an RPF7 archive.")
                }
                val header = source.readBuffer(HEADER_SIZE)
                val magic = header.u32
                if (magic != RPF7_MAGIC && magic != RPF7_ALTERNATE_MAGIC) {
                    throw RpfException(RpfErrorCode.INVALID_ARCHIVE, "The file is not an RPF7 archive.")
                }
                val entryCount = header.u32
                val namesSize = header.u32
                val encryption = header.u32
                val tableEnd = HEADER_SIZE + entryCount * RECORD_SIZE + namesSize
                if (entryCount !in 1L..MAX_ENTRY_COUNT.toLong() ||
                    namesSize !in 1L..MAX_NAME_TABLE_SIZE.toLong() || tableEnd > fileSize
                ) {
                    malformedFileTable("The RPF7 header has an invalid file table or filename table size.")
                }
                val key = when (encryption) {
                    ENCRYPTION_NONE, ENCRYPTION_OPEN -> null
                    ENCRYPTION_AES -> aesKey?.takeIf { it.size == 32 }?.copyOf()
                        ?: throw RpfException(RpfErrorCode.AES_KEY_REQUIRED, "This AES archive requires a 32-byte key.")
                    else -> throw RpfException(
                        RpfErrorCode.TABLE_ENCRYPTION_UNSUPPORTED,
                        "This archive uses unsupported table encryption.",
                    )
                }
                val recordTable = source.readBuffer(entryCount.toInt() * RECORD_SIZE).array()
                val nameTable = source.readBuffer(namesSize.toInt()).array()
                val recordBytes = transformTable(recordTable, key, Cipher.DECRYPT_MODE)
                val names = transformTable(nameTable, key, Cipher.DECRYPT_MODE)
                val entries = readEntries(file, source, recordBytes, names, tableEnd)
                val root = entries.first().first as? RpfDirectory
                    ?: malformedFileTable("The RPF7 file table has no root directory.")
                attachEntries(root, entries)
                return RpfArchive(file, magic, encryption, key, root, entries.size)
            }
        }
    }
}

private const val HEADER_SIZE = 16
private const val RECORD_SIZE = 16
private const val SECTOR_SIZE = 512
private const val AES_BLOCK_SIZE = 16
private const val COPY_BUFFER_SIZE = 64 * 1024
private const val MAX_ENTRY_COUNT = 500_000
private const val MAX_NAME_TABLE_SIZE = 16 * 1024 * 1024
private const val MAX_FILE_NAME_OFFSET = 0xffff
private const val MAX_SECTOR = 0x7fffff
private const val MAX_PACKED_SIZE = 0xffffff
private const val RESOURCE_SECTOR_FLAG = 0x800000
private const val RPF7_MAGIC = 0x52504637L
private const val RPF7_ALTERNATE_MAGIC = 0x37465052L
private const val DIRECTORY_TAG = 0x7fffff00L
private const val RESOURCE_MAGIC = 0x37435352L
private const val RESOURCE_HEADER_SIZE = 16
private const val ENCRYPTION_NONE = 0L
private const val ENCRYPTION_OPEN = 0x4e45504fL
private const val ENCRYPTION_AES = 0x0ffffff9L

private val ByteBuffer.u32: Long get() = int.toLong() and 0xffffffffL
private fun ByteBuffer.u24(): Int =
    (get().toInt() and 0xff) or ((get().toInt() and 0xff) shl 8) or ((get().toInt() and 0xff) shl 16)

private fun ByteBuffer.putU24(value: Int) {
    put(value.toByte())
    put((value ushr 8).toByte())
    put((value ushr 16).toByte())
}

private fun RandomAccessFile.readBuffer(size: Int): ByteBuffer {
    val bytes = ByteArray(size)
    readFully(bytes)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}

private data class DirectoryRange(val first: Long, val count: Long)

private fun malformedFileTable(detail: String): Nothing =
    throw RpfException(RpfErrorCode.MALFORMED_FILE_TABLE, detail)

private fun readEntries(
    file: File,
    source: RandomAccessFile,
    records: ByteArray,
    names: ByteArray,
    tableEnd: Long,
): List<Pair<RpfEntry, DirectoryRange?>> {
    val record = ByteBuffer.wrap(records).order(ByteOrder.LITTLE_ENDIAN)
    val nameCache = mutableMapOf<Int, ByteArray>()
    val fileSize = source.length()
    return List(records.size / RECORD_SIZE) { index ->
        val position = index * RECORD_SIZE
        record.position(position)
        val isDirectory = (record.getInt(position + 4).toLong() and 0xffffffffL) == DIRECTORY_TAG
        val nameOffset = if (isDirectory) record.u32 else (record.short.toInt() and 0xffff).toLong()
        if (nameOffset >= names.size) malformedFileTable("An RPF7 filename points outside its table.")
        val name = nameCache.getOrPut(nameOffset.toInt()) { readName(names, nameOffset.toInt()) }
        if (isDirectory) {
            record.int // Directory marker.
            RpfDirectory(name) to DirectoryRange(record.u32, record.u32)
        } else {
            val packedSize = record.u24()
            val sector = record.u24()
            val metadata = record.u32
            val extra = record.u32
            val isResource = (sector and RESOURCE_SECTOR_FLAG) != 0
            val offset = (sector and MAX_SECTOR).toLong() * SECTOR_SIZE
            val storedSize = if (isResource) {
                resourceSize(source, fileSize, offset, packedSize, metadata, extra)
            } else {
                if (extra !in 0L..1L) malformedFileTable("An RPF7 binary entry has an invalid encryption flag.")
                if (packedSize == 0) metadata else packedSize.toLong()
            }
            if (storedSize > 0 && (offset < tableEnd || offset > fileSize || storedSize > fileSize - offset)) {
                malformedFileTable("An RPF7 entry points outside the archive.")
            }
            val logicalSize = if (isResource) storedSize else metadata
            val payload = Payload(file, offset, storedSize, logicalSize, packedSize, metadata, extra, isResource)
            RpfFile(name, payload) to null
        }
    }
}

private fun readName(names: ByteArray, offset: Int): ByteArray {
    var end = offset
    while (end < names.size && names[end] != 0.toByte()) {
        if (end - offset >= 1024) malformedFileTable("An RPF7 filename is too long.")
        end++
    }
    if (end == names.size) malformedFileTable("An RPF7 filename is missing its terminator.")
    return names.copyOfRange(offset, end)
}

private fun attachEntries(root: RpfDirectory, entries: List<Pair<RpfEntry, DirectoryRange?>>) {
    val pending = ArrayDeque<Int>()
    pending.addLast(0)
    var attached = 1
    while (pending.isNotEmpty()) {
        val (entry, range) = entries[pending.removeLast()]
        val directory = entry as RpfDirectory
        val children = checkNotNull(range)
        if (children.count == 0L) continue
        if (children.first < 1 || children.first + children.count > entries.size) {
            malformedFileTable("An RPF7 directory points outside the file table.")
        }
        val end = (children.first + children.count).toInt()
        for (index in children.first.toInt() until end) {
            val child = entries[index].first
            if (child === root || child.parent != null) {
                malformedFileTable("The RPF7 directory tree contains a repeated entry.")
            }
            child.parent = directory
            directory.mutableChildren.add(child)
            attached++
            if (child is RpfDirectory) pending.addLast(index)
        }
    }
    if (attached != entries.size) malformedFileTable("The RPF7 file table contains disconnected entries.")
}

private fun validateNewName(directory: RpfDirectory, name: String, except: RpfEntry? = null): ByteArray {
    val encoded = name.encodeToByteArray()
    if (name.isBlank() || name == "." || name == ".." || encoded.size > 255 ||
        name.any { it == '/' || it == '\\' || it.isISOControl() }
    ) {
        throw RpfException(
            RpfErrorCode.INVALID_NAME,
            "Enter a filename of 1–255 bytes with no slashes or control characters.",
        )
    }
    if (directory.children.any { it !== except && it.name.equals(name, ignoreCase = true) }) {
        throw RpfException(RpfErrorCode.DUPLICATE_NAME, "A file or folder with this name already exists.")
    }
    return encoded
}

private fun importedPayload(name: String, source: File): Payload {
    RandomAccessFile(source, "r").use { file ->
        val size = file.length()
        if (size > 0xffffffffL) {
            throw RpfException(RpfErrorCode.FILE_SIZE_LIMIT, "The selected file exceeds the RPF7 file size limit.")
        }
        if (size >= RESOURCE_HEADER_SIZE) {
            val header = file.readBuffer(RESOURCE_HEADER_SIZE)
            if (header.u32 == RESOURCE_MAGIC) {
                if (name.isScriptResource()) {
                    throw RpfException(
                        RpfErrorCode.ENCRYPTED_RESOURCE_MUTATION,
                        "Encrypted script resources cannot be added or replaced.",
                    )
                }
                header.u32 // Resource version.
                val systemFlags = header.u32
                val graphicsFlags = header.u32
                val packedSize = minOf(size, MAX_PACKED_SIZE.toLong()).toInt()
                return Payload(source, 0, size, size, packedSize, systemFlags, graphicsFlags, true,
                    updateResourceSizeHeader = size >= MAX_PACKED_SIZE)
            }
        }
        return Payload(source, 0, size, size, 0, size, 0, false)
    }
}

private fun resourceSize(
    source: RandomAccessFile,
    archiveSize: Long,
    offset: Long,
    packedSize: Int,
    systemFlags: Long,
    graphicsFlags: Long,
): Long = when (packedSize) {
    0 -> resourcePageSize(systemFlags) + resourcePageSize(graphicsFlags)
    MAX_PACKED_SIZE -> {
        if (offset > archiveSize - RESOURCE_HEADER_SIZE) {
            throw RpfException(RpfErrorCode.MALFORMED_RESOURCE, "The archive contains a truncated resource header.")
        }
        source.seek(offset)
        val header = source.readBuffer(RESOURCE_HEADER_SIZE).array()
        val size = RESOURCE_SIZE_BYTE_OFFSETS.withIndex().fold(0L) { value, (index, position) ->
            value or ((header[position].toLong() and 0xff) shl (8 * index))
        }
        if (size < MAX_PACKED_SIZE) {
            throw RpfException(RpfErrorCode.MALFORMED_RESOURCE, "An RPF7 resource has an invalid extended size.")
        }
        size
    }
    else -> packedSize.toLong()
}

private fun resourcePageSize(flags: Long): Long {
    val shift = (flags and 0xf).toInt()
    val smallPages = (0..3).sumOf { bit -> ((flags ushr (27 - bit)) and 1L) shl bit }
    val pages = smallPages + (((flags ushr 17) and 0x7f) shl 4) +
        (((flags ushr 11) and 0x3f) shl 5) + (((flags ushr 7) and 0xf) shl 6) +
        (((flags ushr 5) and 3) shl 7) + (((flags ushr 4) and 1) shl 8)
    return (512L shl shift) * pages
}

private val RESOURCE_SIZE_BYTE_OFFSETS = intArrayOf(7, 14, 5, 2)

private fun extractResource(payload: Payload, source: RandomAccessFile, output: OutputStream) {
    var length = payload.storedSize
    if (payload.packedSize != 0) {
        if (length < RESOURCE_HEADER_SIZE) {
            throw RpfException(RpfErrorCode.MALFORMED_RESOURCE, "The RPF7 resource header is truncated.")
        }
        val version = ((payload.metadata ushr 28) shl 4) or (payload.extra ushr 28)
        val header = ByteBuffer.allocate(RESOURCE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(RESOURCE_MAGIC.toInt()).putInt(version.toInt())
            .putInt(payload.metadata.toInt()).putInt(payload.extra.toInt())
        source.seek(payload.offset + RESOURCE_HEADER_SIZE)
        output.write(header.array())
        length -= RESOURCE_HEADER_SIZE
    }
    copyExactly(ArchiveSlice(source, length), output, length, ByteArray(COPY_BUFFER_SIZE))
}

private fun writeResourceOrRaw(payload: Payload, source: RandomAccessFile, output: OutputStream, buffer: ByteArray) {
    source.seek(payload.offset)
    var length = payload.storedSize
    if (payload.updateResourceSizeHeader) {
        val header = source.readBuffer(RESOURCE_HEADER_SIZE).array()
        for ((index, position) in RESOURCE_SIZE_BYTE_OFFSETS.withIndex()) {
            header[position] = (length ushr (8 * index)).toByte()
        }
        output.write(header)
        length -= header.size
    }
    copyExactly(ArchiveSlice(source, length), output, length, buffer)
}

private fun aesCipher(key: ByteArray, mode: Int): Cipher = try {
    Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(key, "AES")) }
} catch (exception: GeneralSecurityException) {
    throw RpfException(RpfErrorCode.AES_FAILED, "The archive's AES operation could not be initialized.", exception)
}

private fun transformTable(bytes: ByteArray, key: ByteArray?, mode: Int): ByteArray {
    if (key == null) return bytes
    val alignedSize = bytes.size - bytes.size % AES_BLOCK_SIZE
    if (alignedSize == 0) return bytes
    try {
        val transformed = aesCipher(key, mode).doFinal(bytes, 0, alignedSize)
        transformed.copyInto(bytes)
    } catch (exception: GeneralSecurityException) {
        throw RpfException(RpfErrorCode.AES_FAILED, "The archive's AES table could not be processed.", exception)
    }
    return bytes
}

private fun String.isScriptResource(): Boolean = endsWith(".ysc", ignoreCase = true)

private fun paddingFor(length: Long, alignment: Int): Int =
    ((alignment - (length % alignment)) % alignment).toInt()

private fun copyExactly(input: InputStream, output: OutputStream, length: Long, buffer: ByteArray) {
    var remaining = length
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count <= 0) throw RpfException(RpfErrorCode.ENTRY_TRUNCATED, "The archive entry is truncated.")
        output.write(buffer, 0, count)
        remaining -= count
    }
}

private class ArchiveSlice(private val file: RandomAccessFile, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        val byte = file.read()
        if (byte == -1) throw RpfException(RpfErrorCode.ENTRY_TRUNCATED, "The archive entry is truncated.")
        remaining--
        return byte
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = file.read(bytes, offset, minOf(remaining, length.toLong()).toInt())
        if (count == -1) throw RpfException(RpfErrorCode.ENTRY_TRUNCATED, "The archive entry is truncated.")
        remaining -= count
        return count
    }
}
