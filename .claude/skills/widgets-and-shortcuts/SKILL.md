---
name: widgets-and-shortcuts
description: Read before touching Easymatic's home-screen widgets or launcher shortcuts — the two Glance widgets (Run tile and the Easymatic panel), PanelConfig, MacroSnapshots, MacroTile, RunFeedback, MacroShortcuts, RunTilePin, RunTriggerActivity, and the MacroIcon/MacroAccent appearance a macro carries. Covers why widgets follow the system theme while the app does not, why every manual run — tile, shortcut and the editor's own card button — goes through runFromTrigger, why "Add to home screen" places a widget rather than a pinned shortcut and how the trigger reaches it, why the update traffic runs feature-ward, why appearance was added without a schema bump, and why a widget's config must be read back before it is shown.
---

# Widgets and shortcuts

Everything under `feature/widget/` and `feature/shortcut/` exists to answer one question away from the app: **which macros can I run by hand, and can I run one now?** Before it, `trigger.manual` could only be fired from the editor's whole-graph Run FAB — four taps and a context switch for a macro whose whole point is being run on demand.

## Two widgets, and why not three

`RunTileWidget` is one manual trigger as a button. `PanelWidget` is everything else — engine status, problem count, last run, and a grid of triggers — with each section switched on or off per placed widget.

The panel replaces a separate "Trigger deck" and "Engine status". They looked like different widgets and were not: the status one already grew a deck when it had the height, so two of its three sizes *were* the other one. The split also stranded options on whichever provider happened to own them, and forced anyone who wanted status and buttons to place two cards and align them by hand. Turn everything off but the triggers and the panel is the old deck; turn the triggers off and it is the old status widget.

A panel with every section off is legal and says so, rather than being prevented — a config screen that refuses to save is worse than an honest empty state, and clearing it out before choosing what to put back is a real thing to do.

## A widget's config must read itself back

`PanelConfig` owns **both** directions — `from(prefs)` and `writeTo(prefs)` — and that shape is the fix for a bug, not tidiness. The previous config activity initialised its switches to hard-coded defaults and never read the stored state, so reopening it showed every option reset, and Save then wrote those resets over the real ones. It surfaced as two separate-looking complaints ("the header setting is not stored", "the trigger selection is not restored") that were one missing read.

`loadPanelConfig` is that read; it returns defaults when the widget is not yet bound, since `getAppWidgetState` throws for an id the launcher has not finished creating, and a fresh widget genuinely has no state. `PanelConfigTest` pins the round-trip, including that picked order survives — the order is the whole point of picking, and a `Set` would have silently reshuffled the grid on every reconfigure.

## A macro's appearance

`MacroIcon` and `MacroAccent` (`domain/model/`) are on `Workflow`, and the way they were added is the load-bearing part. `WorkflowRepository.load` **discards** anything below `Workflow.CURRENT_SCHEMA_VERSION` rather than migrating it, so bumping the version for a colour deletes every workflow on the device. Both fields therefore have defaults and the version did **not** move; `MacroAppearanceTest` pins a pre-appearance JSON decoding with the defaults filled in. They are also out of `runtimeSignature()`, so recolouring never re-arms — re-arming re-registers geofences and re-enqueues periodic work.

`feature/macro/MacroAppearance.kt` holds the **one** mapping, and it maps to a **drawable resource**, unlike `NodeIcon`'s mapping to a Compose `ImageVector` (`EditorColors.nodeIcon`). Neither Glance nor `ShortcutInfoCompat` can take an `ImageVector`, and Compose renders a drawable perfectly well through `painterResource`, so one table serves the list row, the widgets and the shortcuts instead of two that drift. It lives in `feature/macro/` rather than `feature/widget/` because the workflow list draws a chip too and should not import the widget package to do it.

The **accent tints the glyph and its chip, never a surface**. On a widget the surface is the system's, so a user-red card on a wallpaper-blue home screen would read as pasted on; a coloured mark on a neutral card is why nine accents can share one screen. `MacroAccent.SYSTEM` is the default and means "the wallpaper accent" — it has no colour resource, and `colorRes()` returns null so every caller resolves it itself. In-app, `inAppColor()` reads `EditorColors` **constants** rather than the colour resources, because the app never resolves a night qualifier and `colorResource(macro_accent_blue)` would hand back the *light* value on a light-themed phone.

## Widgets follow the system; the app does not

`MainActivity` forces `darkTheme = true, dynamicColor = false` because the editor canvas is a fixed dark surface. A widget is not on that canvas, and the precedent already in the repo is `Theme.Easymatic.Dialog` in `themes.xml`: surfaces that appear *outside* the app take `Theme.Material3.DayNight` with dynamic colour. `EasymaticWidgetTheme` is the same argument with the same answer — dynamic on API 31+, an explicit fallback built around `dialog_accent` below it. The config activities are the counter-example and are deliberately fixed-dark: they are the app, not the home screen.

Every widget surface is a **shape drawable tinted through a `ColorFilter`**, never `background(ColorProvider)`. Glance's `cornerRadius` is a no-op below API 31 and minSdk is 26, so the plain-colour route draws square cards for a third of the supported range. `drawable-v31/` restates each shape at the system widget radius.

## The chips are filled buttons, and that took two attempts

A macro's accent is a **fill plus the content on it** (`MacroAccent.widgetColors`), following the `primary` / `onPrimary` tones — tone 40 under white in light, tone 80 under tone 20 in dark. `MacroAccent.SYSTEM` resolves to `GlanceTheme.colors.primary` / `onPrimary`, which is the same treatment taken from the wallpaper.

Two earlier versions failed on a real home screen and are worth not repeating:

1. **A 15%-alpha wash tinted with the accent, glyph in the same colour.** That is not a container, it is a smudge: every accent looked alike at a glance and the widget read as a flat grey card.
2. **The `primaryContainer` / `onPrimaryContainer` tones.** Correct M3, and still wrong here — in dark mode a container tone sits about as bright as `widgetBackground` itself, so chips for the *default* accent (which is most of them) disappeared into the card they were on.

A **deck cell draws no card of its own**, because the widget's card is already `widgetBackground` underneath it and a second surface in the same colour is invisible. The chip is the only filled thing; the cell around it is transparent and clickable.

`CELL_HEIGHT` is **arithmetic, not a chosen number** — `CHIP_SIZE + CHIP_LABEL_GAP + LABEL_LINE_HEIGHT`. A picked 68dp was 10dp short of its own contents and clipped the bottom off every label in every deck. Deck labels are one line for the same reason: two lines needed 28dp of a cell that had 20dp left.

`NARROW_CONTENT_HEIGHT` is the same sum for the Run tile's **1×1** arrangement, which was the chip alone until 2026-08-06 and now carries the label under a smaller chip — a row of accent squares is a row of things you have to remember rather than read, and the icon identifies a macro only until the fourth one is placed beside it. The difference from a deck cell is that there is **nobody to ask for more room**: a 1×1 tile gets what the launcher gives it, so the sum has to fit inside `RunTileWidget`'s smallest responsive bucket (`SMALLEST_SIZE`, 57dp) with the chip already shrunk to 28dp. `MacroTileSizeTest` pins that it does. The stack is **centred rather than top-aligned**, because 57dp is a floor and most launchers hand over half as much again — centring puts the contents in the middle of whatever actually arrives. A height check inside the arrangement would not work: under `SizeMode.Responsive`, `LocalSize.current` is the *bucket*, not the measured size.

Two layout traps, both of which produced visible bugs. Glance's `Row` **wraps its content unless told to `fillMaxWidth()`**, so a `defaultWeight()` inside one has nothing to expand into — that is how the status header rendered as "Engine running5 · 1 armed". And in Compose UI, `LazyVerticalGrid` measures items at a **fixed cell width**, so `Modifier.size()` on the item cannot make it narrower; the Edit dialog's round colour swatches came out as ovals until each was centred inside a full-width cell.

Widgets are unstyled Material by default in one more place: the app's `EasymaticTheme` still carries the Android Studio template's purple scheme, so any *in-app* Material control that is not explicitly coloured renders purple. `editorTextButtonColors()`, `darkFieldColors()` and `editorSwitchColors()` exist for that, and dialog buttons need one of them.

## Running from outside the app

A widget tap goes through `MacroEngineService.ACTION_RUN_MANUAL`, which loads the graph and calls `runFromTrigger` (`engine/ManualRun.kt`) — also what `WorkflowRunner.collect` calls, so the per-event `catch` and the `"finished"` emit exist once. `trigger.manual` needs no activation at all, so this works armed or not, and needs no `armMutex`.

It used to be the *exception*: `ManualTrigger` kept a registry of shared flows keyed by node id that the editor's Run FAB fired into, and the widgets refused to use it — keyed by node id alone, so the editor's preview runner and the engine's armed runner clobbered each other's entry; `release` ran only when the editor stopped a preview, so a disarm left a dead flow that `fire` emitted into silently; and it returned nothing, while a tile has to report an outcome. **The editor now takes this road too** (its per-card Run button calls `runFromTrigger` on the live in-memory graph, since it has an editor to read from where a tile does not), the registry is gone, and the argument above is retired rather than merely avoided.

`runFromTrigger`'s `Boolean` is what `RunFeedback.finished` draws, and it is false for a run that never started as well as one that threw — `executeFrom` returns false when the *trigger node itself* is quarantined, which used to reach the tile as a green "ran" for a graph that did nothing.

**A tap runs a macro whose switch is off.** `enabled` is the intent to keep a macro listening for *background events*; a tap is not one. The tile says "Off" so it is visible rather than surprising.

`MacroEngineService.runManual` wraps `startForegroundService` and falls back to `appScope`: interacting with a widget is on Android 12's FGS-start exemption list, but the window is short and OEM builds are inconsistent, and a dropped tap is a button that does nothing. A **shortcut** has no such problem — `RunTriggerActivity` is a real foreground activity for the instant it lives, which is why shortcuts need a trampoline at all (a `ShortcutInfo` intent is started with `startActivity`, and the service is not exported).

## "Add to home screen" places a widget, not a shortcut

`MacroShortcuts` is **dynamic shortcuts only**. Pinned ones existed and the workflow list's "Add to home screen" created one; it places a Run tile instead as of 2026-08-06 (`RunTilePin`). The two occupy the same grid cell and run the same macro, but a pinned shortcut is a *bitmap the launcher owns* from the moment it is dropped — it cannot say "Running…", it cannot go red on a failure, it cannot show that the macro is switched off, and its label is the launcher's to truncate. The tile does all four and is already built. Dynamic shortcuts stay, because they are the one thing a widget cannot be: a list that maintains itself under a long-press of the app icon, with nothing to place.

The hard part is **not asking the same question twice**. `RunTileConfigActivity` exists so a tile dropped from the widget picker can be told which trigger it is for, and whether a launcher opens it after a pin is the launcher's decision — nothing in the API lets an app say "don't". So the trigger key travels **two routes**, and whichever arrives first wins:

- the **options bundle** of the pin request, which the launcher stores against the new widget id. `RunTileConfigActivity` looks for it before drawing anything and confirms itself, so the chooser never flashes up on its way to closing.
- the **success callback**, a broadcast carrying the id the widget was given, for the launchers that never open the config screen. `RunTilePinnedReceiver` writes from there **only into a tile that has no trigger yet**, so it can never overrule a choice made on the config screen in the meantime.

That PendingIntent must be **`FLAG_MUTABLE`** on API 31+: the system fills `EXTRA_APPWIDGET_ID` into it before sending, and an immutable one arrives with no id to write to. Its request code is the trigger's, so pinning a second macro before the first callback fires cannot have `FLAG_UPDATE_CURRENT` rewrite the first one's payload.

The requested key is **consumed, not read** — by both routes. Widget options outlive the placement, so a key left there is found again the next time that tile is *reconfigured*, and reconfiguring would silently re-apply the original trigger instead of offering the list. `updateAppWidgetOptions` merges and cannot remove a key, so consuming means writing a null string over it.

## RunFeedback, and why it is in `core/`

`core/service/RunFeedback.kt` is **written by `engine`** (the service) and **read by `feature`** (the widgets). Under `engine ← domain + core` and `feature ← domain + engine + core`, `core` is the only place both can see.

Decay is a **function of the clock, not stored state**: `displayState(entry, nowMs)` returns null once a finished run is past `SETTLE_MS`, so any redraw for any reason shows the truth. `RUNNING` never decays, because `action.delay` exists and a long macro's tile going quiet reads as a dropped tap. `RunTriggerAction` sets RUNNING *in the tap* rather than leaving it to the service, so the tile responds to the finger rather than to the engine several hundred milliseconds later.

## The update traffic runs feature-ward

The obvious design — repository redraws widgets on write, engine redraws them on run — is forbidden by the package rule. Neither component that knows something changed may know widgets exist. So `WorkflowRepository.changes` and `RunFeedback`/`MacroEngineService.engineRunning` are plain flows, `WidgetUpdater` collects them, and `EasymaticApplication` calls `attach` — the root package being the one place allowed to introduce them.

`WidgetUpdater` `drop(1)`s the StateFlows (a new collector replays the current value, which is not news) and debounces, because saves and runs both arrive in bursts. One extra delayed redraw fires after `SETTLE_MS` because nothing *emits* when a finished run decays.

## MacroSnapshots

Builds what all three widgets render — name, enabled, `GraphValidator` error count, manual triggers — by loading every workflow. That is the same pass `WorkflowListViewModel.refresh()` already made, and now shares, so the list and the widgets cannot disagree about a problem count. **Cached**, invalidated by `WorkflowRepository.changes`, because widgets update on every finished run. `refresh()` invalidates explicitly first, since it is called immediately after a mutation and would otherwise race the collector.

Deliberately **no on-disk index**: a second source of truth for a workload the app already does synchronously. If many macros ever make this slow, an index is the answer then.

The label fallback is `config.label` → node name → macro name, with the node name treated as unnamed when it still equals the registry's `displayName` — every fresh `trigger.manual` carries it, and a deck of eight cells reading "Manual Trigger" is the failure the chain exists to prevent.

## Shapes and sizes

`MacroTile` has three arrangements (wide, compact, narrow) and `TriggerDeck` lays cells out by hand rather than with `LazyVerticalGrid`: the grid is bounded by the widget's measured size, `defaultWeight()` keeps cells genuinely equal, and a lazy grid would bring a `RemoteViewsService` for a dozen cells that never scroll. Anything past the budget is reported by `DeckOverflow` rather than dropped silently.

The panel draws its grid only when a *whole* cell row fits below the header, and says "make this taller" instead of drawing a sliver — a half-row reads as a rendering bug rather than as a size the user chose. The grid is centred in whatever the header leaves, because a panel is resized to fit a gap and is routinely taller than its contents.

`updatePeriodMillis` is 0 in both providers: the platform's poll has a 30-minute floor and everything these widgets show changes on an event `WidgetUpdater` already collects.
