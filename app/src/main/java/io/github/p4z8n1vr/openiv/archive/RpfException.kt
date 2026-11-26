package io.github.p4z8n1vr.openiv.archive

import java.io.IOException

enum class RpfErrorCode {
    ENTRY_ENCRYPTION_UNSUPPORTED,
    ROOT_RENAME_FORBIDDEN,
    ROOT_DELETE_FORBIDDEN,
    RESOURCE_ENCRYPTION_RENAME,
    ENCRYPTED_RESOURCE_MUTATION,
    NAME_TABLE_TOO_LARGE,
    SECTOR_LIMIT,
    ENTRY_NOT_IN_ARCHIVE,
    ENTRY_COUNT_LIMIT,
    INVALID_ARCHIVE,
    MALFORMED_FILE_TABLE,
    AES_KEY_REQUIRED,
    TABLE_ENCRYPTION_UNSUPPORTED,
    MALFORMED_RESOURCE,
    INVALID_NAME,
    DUPLICATE_NAME,
    FILE_SIZE_LIMIT,
    AES_FAILED,
    ENTRY_TRUNCATED,
    DEFLATE_FAILED,
}

/** An archive failure with a stable code and an English diagnostic message. */
class RpfException internal constructor(
    val code: RpfErrorCode,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
