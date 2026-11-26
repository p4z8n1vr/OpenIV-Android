package io.github.p4z8n1vr.openiv

import io.github.p4z8n1vr.openiv.archive.RpfErrorCode

internal fun RpfErrorCode.messageResource(): Int = when (this) {
    RpfErrorCode.ENTRY_ENCRYPTION_UNSUPPORTED -> R.string.error_rpf_entry_encryption_unsupported
    RpfErrorCode.ROOT_RENAME_FORBIDDEN -> R.string.error_rpf_root_rename_forbidden
    RpfErrorCode.ROOT_DELETE_FORBIDDEN -> R.string.error_rpf_root_delete_forbidden
    RpfErrorCode.RESOURCE_ENCRYPTION_RENAME -> R.string.error_rpf_resource_encryption_rename
    RpfErrorCode.ENCRYPTED_RESOURCE_MUTATION -> R.string.error_rpf_encrypted_resource_mutation
    RpfErrorCode.NAME_TABLE_TOO_LARGE -> R.string.error_rpf_name_table_too_large
    RpfErrorCode.SECTOR_LIMIT -> R.string.error_rpf_sector_limit
    RpfErrorCode.ENTRY_NOT_IN_ARCHIVE -> R.string.error_rpf_entry_not_in_archive
    RpfErrorCode.ENTRY_COUNT_LIMIT -> R.string.error_rpf_entry_count_limit
    RpfErrorCode.INVALID_ARCHIVE -> R.string.error_rpf_invalid_archive
    RpfErrorCode.MALFORMED_FILE_TABLE -> R.string.error_rpf_malformed_file_table
    RpfErrorCode.AES_KEY_REQUIRED -> R.string.error_rpf_aes_key_required
    RpfErrorCode.TABLE_ENCRYPTION_UNSUPPORTED -> R.string.error_rpf_table_encryption_unsupported
    RpfErrorCode.MALFORMED_RESOURCE -> R.string.error_rpf_malformed_resource
    RpfErrorCode.INVALID_NAME -> R.string.error_rpf_invalid_name
    RpfErrorCode.DUPLICATE_NAME -> R.string.error_rpf_duplicate_name
    RpfErrorCode.FILE_SIZE_LIMIT -> R.string.error_rpf_file_size_limit
    RpfErrorCode.AES_FAILED -> R.string.error_rpf_aes_failed
    RpfErrorCode.ENTRY_TRUNCATED -> R.string.error_rpf_entry_truncated
    RpfErrorCode.DEFLATE_FAILED -> R.string.error_rpf_deflate_failed
}
