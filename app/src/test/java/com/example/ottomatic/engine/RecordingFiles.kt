package com.example.ottomatic.engine

import com.example.ottomatic.core.service.FileBytes
import com.example.ottomatic.core.service.FileFacts
import com.example.ottomatic.core.service.FileListing
import com.example.ottomatic.core.service.FileRead
import com.example.ottomatic.core.service.FileResult
import com.example.ottomatic.core.service.Files
import com.example.ottomatic.core.service.ListFilter
import com.example.ottomatic.core.service.TextEncoding
import com.example.ottomatic.core.service.WhenExists

/** One call the nodes made, kept so a test can assert what did *not* reach the facade. */
data class FileCall(
    val member: String,
    val path: String,
    val text: String = "",
    val append: Boolean = false,
    val whenExists: WhenExists = WhenExists.REPLACE,
    val encoding: TextEncoding = TextEncoding.UTF_8,
    val pattern: String = "",
    val show: ListFilter = ListFilter.FILES,
    val move: Boolean = false,
    val to: String = "",
)

/**
 * A filesystem that records every call and answers from fields a test sets.
 *
 * [FakeMqtt]'s shape and it **records** for the same reason: half of what these nodes
 * promise is about what does not reach the facade at all — a blank path, and a path
 * `FilePath` refuses. A fake that only returned answers could not tell "the node
 * refused this" from "the store was asked and said no", which is exactly the
 * distinction the tests need.
 */
class RecordingFiles(
    var read: FileRead = FileRead(text = "", ok = true),
    var result: FileResult = FileResult(changed = true),
    var listing: FileListing = FileListing(ok = true),
    var facts: FileFacts = FileFacts(exists = true, path = ""),
    var bytes: FileBytes = FileBytes(base64 = "AAAA", mediaType = "image/jpeg"),
) : Files {

    val calls = mutableListOf<FileCall>()

    override suspend fun readText(path: String, encoding: TextEncoding): FileRead {
        calls += FileCall("readText", path, encoding = encoding)
        return read
    }

    override suspend fun readBytes(path: String): FileBytes {
        calls += FileCall("readBytes", path)
        return bytes
    }

    override suspend fun writeText(
        path: String,
        text: String,
        append: Boolean,
        whenExists: WhenExists,
        encoding: TextEncoding,
    ): FileResult {
        calls += FileCall("writeText", path, text = text, append = append, whenExists = whenExists, encoding = encoding)
        return result
    }

    override suspend fun list(path: String, pattern: String, show: ListFilter): FileListing {
        calls += FileCall("list", path, pattern = pattern, show = show)
        return listing
    }

    override suspend fun info(path: String): FileFacts {
        calls += FileCall("info", path)
        return facts
    }

    override suspend fun delete(path: String): FileResult {
        calls += FileCall("delete", path)
        return result
    }

    override suspend fun transfer(from: String, to: String, move: Boolean, whenExists: WhenExists): FileResult {
        calls += FileCall("transfer", from, move = move, to = to, whenExists = whenExists)
        return result
    }
}
