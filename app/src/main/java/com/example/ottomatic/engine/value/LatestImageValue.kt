package com.example.ottomatic.engine.value

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.ImageItem
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.trigger.toItem
import com.example.ottomatic.engine.valueNode

/**
 * `value.latest_image` — the most recently added picture.
 *
 * **The value half of `trigger.image_saved`**, added in the same change because the rule
 * says so and because the pair is genuinely useful apart: the trigger answers "tell me
 * when a picture arrives", this answers "what is the newest one right now". Without it,
 * "when I get home, post the last photo I took" would need a second macro armed all day
 * just to remember what the first one saw.
 *
 * **Legal on the pull side, and the argument is `value.calendar_busy`'s.** A read here is
 * one indexed cursor query into a provider that is always installed — a binder call into a
 * local database, with no socket, no credential, no DNS and no timeout. That is *cheap and
 * cannot fail*, which is what the pull side actually requires; the bar was never "must not
 * touch storage". It is emphatically not `Files`' side of the line, where a granted folder
 * may be served by a cloud provider and the cheapest possible read is a round trip.
 *
 * **No config, deliberately**, and that is what the node is for. A `val:` read is performed
 * with no config at all, so a folder filter here would put it in `CONFIGURED_VALUE_TYPE_IDS`
 * beside `value.variable` and it could never be named in `action.if`'s source dropdown —
 * which is most of the point. "When I plug in, **if** the newest picture is a screenshot,
 * back it up" is one comparison on the canvas because of this decision. Narrowing to a
 * folder is `action.image_list`, on the exec wire.
 *
 * **Null when there is no picture and null when the grant is missing**, which is the one
 * place this node is deliberately less informative than its action counterpart: a value
 * must fail closed to a single null, and keeping "there are none", "no permission" and
 * "the provider failed" apart is `action.image_info`'s job, where the exec wire makes the
 * latency visible. It declares the grant anyway — `value.wifi_network` settled that a
 * value may — because the Problems panel and the node's own card both walk declarations,
 * and a silent one would read null for ever with nothing anywhere saying why.
 */
class LatestImageValue : ValueNode<NoConfig, ImageItem> {

    override val definition = valueNode<NoConfig, ImageItem>(
        typeId = "value.latest_image",
        displayName = "Latest picture",
        description = "The most recent photo, screenshot or picture saved on this phone",
        category = NodeCategory.VALUE_IMAGES,
        icon = NodeIcon.IMAGE,
        output = dataOut("image", label = "Picture"),
        permissions = listOf(READ_MEDIA_IMAGES),
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): ImageItem? =
        context.images.latest()?.toItem()

    private companion object {
        val READ_MEDIA_IMAGES = PermissionRequirement(
            manifestPermission = Permissions.READ_MEDIA_IMAGES.manifest,
            type = PrerequisiteType.RUNTIME,
            rationaleKey = "media.read",
        )
    }
}
