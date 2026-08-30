package io.github.m1n1m1.easymatic.engine.value

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.ImageItem
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.ValueNode
import io.github.m1n1m1.easymatic.engine.trigger.toItem
import io.github.m1n1m1.easymatic.engine.valueNode

/**
 * `value.latest_screenshot` — the most recent screenshot on this phone.
 *
 * **The value half of `trigger.screenshot`**, added in the same change because the rule says
 * a trigger over a readable state brings one, and `value.latest_image` behind
 * `trigger.image_saved` is that rule applied one shelf over. The pair is useful apart in the
 * usual way: the trigger says "tell me when I take one", this says "which one is the newest
 * right now" — so "when I get to my desk, **if** the last screenshot is from the last five
 * minutes, mail it to me" is one comparison rather than a second macro armed all day.
 *
 * **Not the same question as `value.latest_image`**, which is why both exist. That one
 * answers with whatever arrived last — a camera photo, a download, a picture saved out of a
 * messenger — so a macro about screenshots built on it fires on all of those and then has to
 * test the folder itself, which puts the `Pictures/Screenshots`-versus-`DCIM/Screenshots`
 * problem back in the user's hands. `Screenshots.isScreenshotFolder` answers it here, shared
 * with the trigger and the action.
 *
 * **Legal on the pull side** on `value.latest_image`'s argument, which carries across
 * unchanged: one indexed cursor query into a provider that is always installed, bounded to a
 * few dozen rows, with no socket and nothing to time out. Cheap and unfailable, which is
 * what the pull side requires.
 *
 * **`NoConfig`**, so it stays out of `CONFIGURED_VALUE_TYPE_IDS` and can be named directly
 * in `action.if`'s source dropdown — the property that makes the comparison above one drag
 * rather than three. There is nothing to configure in any case: which folder counts is the
 * app's business, and that is the node's whole point.
 *
 * **Null when there is none and null when the grant is missing**, failing closed to a single
 * answer as a value must. The grant is declared anyway, on `value.wifi_network`'s rule: the
 * Problems panel and the node's card both walk declarations, and a silent node would read
 * null for ever with nothing anywhere saying why.
 */
class LatestScreenshotValue : ValueNode<NoConfig, ImageItem> {

    override val definition = valueNode<NoConfig, ImageItem>(
        typeId = "value.latest_screenshot",
        displayName = "Latest screenshot",
        description = "The most recent screenshot saved on this phone",
        category = NodeCategory.VALUE_IMAGES,
        icon = NodeIcon.SCREENSHOT,
        output = dataOut("image", label = "Picture"),
        permissions = listOf(READ_MEDIA_IMAGES),
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): ImageItem? =
        context.images.latestScreenshot()?.toItem()

    private companion object {
        val READ_MEDIA_IMAGES = PermissionRequirement(
            manifestPermission = Permissions.READ_MEDIA_IMAGES.manifest,
            type = PrerequisiteType.RUNTIME,
            rationaleKey = "media.read",
        )
    }
}
