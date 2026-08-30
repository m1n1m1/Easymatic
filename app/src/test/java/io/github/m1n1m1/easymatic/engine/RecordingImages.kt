package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.service.ImageEdit
import io.github.m1n1m1.easymatic.core.service.ImageEncoded
import io.github.m1n1m1.easymatic.core.service.ImageFacts
import io.github.m1n1m1.easymatic.core.service.ImageListing
import io.github.m1n1m1.easymatic.core.service.ImageQuery
import io.github.m1n1m1.easymatic.core.service.ImageRecord
import io.github.m1n1m1.easymatic.core.service.ImageWrite
import io.github.m1n1m1.easymatic.core.service.Images
import io.github.m1n1m1.easymatic.core.service.MetadataDetail
import io.github.m1n1m1.easymatic.core.service.PhotoRequest
import io.github.m1n1m1.easymatic.core.service.WhenExists

/**
 * A recording [Images], on `RecordingFiles`' shape.
 *
 * Every answer is a `var` so a test names the outcome it is about, and every call is
 * recorded so a test can assert the facade was reached — or, more often, that it was
 * **not**: a node handed a blank field must report without calling anything, and "did
 * nothing" is only checkable from this side.
 */
@Suppress("LongParameterList") // One answer per facade member; a test names the outcome
// it is about and leaves the rest at their defaults.
class RecordingImages(
    var listing: ImageListing = ImageListing(ok = true),
    var facts: ImageFacts = ImageFacts(exists = true),
    var write: ImageWrite = ImageWrite(changed = true),
    var newest: ImageRecord? = null,
    var newestScreenshot: ImageRecord? = null,
    var encoded: ImageEncoded = ImageEncoded(base64 = "AAAA", mediaType = "image/jpeg"),
    override var hasRecoverableBin: Boolean = true,
    override var hasCameraFlash: Boolean = true,
) : Images {

    val queries = mutableListOf<ImageQuery>()
    val detailed = mutableListOf<String>()
    val edits = mutableListOf<Pair<String, ImageEdit>>()
    val metadata = mutableListOf<Triple<String, MetadataDetail, String>>()
    val transfers = mutableListOf<Transfer>()
    val deletes = mutableListOf<Pair<String, Boolean>>()
    val encodedFor = mutableListOf<String>()
    val captures = mutableListOf<Capture>()
    val photos = mutableListOf<PhotoRequest>()

    data class Transfer(val ref: String, val toFolder: String, val move: Boolean, val whenExists: WhenExists)

    data class Capture(val toFolder: String, val name: String, val whenExists: WhenExists)

    /** Every call that reached the facade, in order, for "it did nothing" assertions. */
    val calls: Int
        get() = queries.size + detailed.size + edits.size + metadata.size + transfers.size +
            deletes.size + encodedFor.size + captures.size + photos.size

    override suspend fun query(spec: ImageQuery): ImageListing {
        queries += spec
        return listing
    }

    override suspend fun latest(): ImageRecord? = newest

    override suspend fun details(ref: String): ImageFacts {
        detailed += ref
        return facts
    }

    override suspend fun edit(ref: String, edit: ImageEdit): ImageWrite {
        edits += ref to edit
        return write
    }

    override suspend fun setMetadata(ref: String, detail: MetadataDetail, value: String): ImageWrite {
        metadata += Triple(ref, detail, value)
        return write
    }

    override suspend fun transfer(
        ref: String,
        toFolder: String,
        move: Boolean,
        whenExists: WhenExists,
    ): ImageWrite {
        transfers += Transfer(ref, toFolder, move, whenExists)
        return write
    }

    override suspend fun delete(ref: String, toTrash: Boolean): ImageWrite {
        deletes += ref to toTrash
        return write
    }

    override suspend fun encodeForModel(ref: String): ImageEncoded {
        encodedFor += ref
        return encoded
    }

    override suspend fun capture(toFolder: String, name: String, whenExists: WhenExists): ImageWrite {
        captures += Capture(toFolder, name, whenExists)
        return write
    }

    override suspend fun takePhoto(request: PhotoRequest): ImageWrite {
        photos += request
        return write
    }

    override suspend fun latestScreenshot(): ImageRecord? = newestScreenshot
}
