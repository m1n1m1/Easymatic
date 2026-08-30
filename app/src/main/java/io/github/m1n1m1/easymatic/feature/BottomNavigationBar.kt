package io.github.m1n1m1.easymatic.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors

/**
 * The app's bottom navigation bar: a rule above it, Material's items inside it, and
 * the editor's palette on both.
 *
 * ### One component, both bars
 *
 * The app has two of these — the home shell's, whose items are the app's two
 * top-level places, and the editor's, whose items are the three surfaces under the
 * canvas. Everything except the items themselves was the same on both and was
 * written out twice, which is the arrangement a divider colour or a label size drifts
 * out of. It is the reason [SettingsTopBar] exists and the reason
 * [trimmedBottomInsets] is one function, applied once more.
 *
 * The height is part of what is shared, and it is the editor's. [ShortNavigationBar]
 * rather than `NavigationBar` gives the same stacked icon-over-label item at 64 dp
 * instead of 80, because nearly all the difference between the two is padding above
 * and below the content — 16 dp of chrome under every screen in the app, buying
 * nothing that the label and the selection pill were not already saying. The
 * component is still Material's, so the touch targets, the ripple, the selection pill
 * and the accessibility semantics are too.
 *
 * ### Why the colours are overridden
 *
 * Only the colours are, and from [EditorColors] rather than `MaterialTheme`. That is
 * the rule everywhere in this app: the palette is fixed dark and independent of the
 * system theme, so a bar taking `MaterialTheme.colorScheme` would render *light*
 * under a permanently dark app on any light-themed phone.
 *
 * The bar pads itself for the system's bottom inset, so nothing above it needs
 * `navigationBarsPadding()`. Its height never changes, which is what lets the region
 * above be measured once — give that region `weight(1f)` and never `fillMaxSize()`,
 * which would measure the bar to nothing.
 *
 * @param content the bar's items, typically [BottomNavigationBarItem]s.
 */
@Composable
internal fun BottomNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        HorizontalDivider(color = EditorColors.chromeBorder)
        ShortNavigationBar(
            containerColor = EditorColors.chrome,
            contentColor = EditorColors.textPrimary,
            windowInsets = trimmedBottomInsets(),
            content = content,
        )
    }
}

/**
 * One item of a [BottomNavigationBar].
 *
 * [icon] is a slot rather than an [ImageVector] because the editor's items wear
 * badges: a `BadgedBox` collecting its own flow around the icon, so a count ticking
 * during a run invalidates one item rather than the bar it sits in. Use
 * [BottomNavigationBarIcon] for the plain case, which is what fixes the icon's size
 * in one place for both bars.
 */
@Composable
internal fun BottomNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    icon: @Composable () -> Unit,
) {
    ShortNavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = { Text(label, fontSize = LABEL_SIZE) },
        colors = ShortNavigationBarItemDefaults.colors(
            selectedIconColor = EditorColors.textPrimary,
            selectedTextColor = EditorColors.textPrimary,
            selectedIndicatorColor = EditorColors.nodeBackground,
            unselectedIconColor = EditorColors.textSecondary,
            unselectedTextColor = EditorColors.textSecondary,
        ),
    )
}

/**
 * A bar item's icon at the size both bars draw it, tinted by the item around it.
 *
 * Slightly under Material's 24 dp, because a short bar's item has less room above its
 * label and the icon is what gives that room back.
 */
@Composable
internal fun BottomNavigationBarIcon(icon: ImageVector) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
}

/**
 * How much of the system's bottom inset a navigation bar keeps.
 *
 * The inset is padding *inside* the bar's surface, so it lands entirely below the
 * labels and makes the bar look bottom-heavy — the space above the icons is the
 * item's own padding, the space below it is that plus the inset. Under gesture
 * navigation the inset guards a thin handle drawn *over* the app, and the bar's own
 * padding already keeps the labels clear of it, so half of it is enough and the bar
 * sits that much lower.
 *
 * Three-button navigation is left alone: there the inset guards real buttons that
 * would swallow taps meant for the bar, and its size is what says which one is in
 * use — a handle reserves a strip, a button bar reserves a bar.
 */
@Composable
internal fun trimmedBottomInsets(): WindowInsets {
    val insets = WindowInsets.systemBars
    val bottom = with(LocalDensity.current) { insets.getBottom(this).toDp() }
    val kept = if (bottom <= GESTURE_HANDLE_MAX) bottom / 2 else bottom
    return insets.only(WindowInsetsSides.Horizontal).add(WindowInsets(bottom = kept))
}

/** Above this, a bottom inset is reserving buttons rather than a gesture handle. */
private val GESTURE_HANDLE_MAX: Dp = 32.dp

private val ICON_SIZE: Dp = 22.dp

private val LABEL_SIZE = 11.sp
