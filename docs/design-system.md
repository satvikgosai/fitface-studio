# The design system, and where it deliberately departs from the design

[`design-system.html`](design-system.html) is the visual reference: open it in a
browser and it renders the palette in both themes, the type scale, twenty components
in their real states, the glyph inventory, the screen inventory, and the top bar's
width budget. It is one self-contained file — no build step, no server, no network —
so it works from a fresh clone.

One thing to know while reading it: the panels are 360px wide to stand in for a 360dp
phone, but they draw with whatever fonts the browser resolves, so **colours, sizes and
structure are exact while text widths are not** — never read a truncation or a fit off
that page.

It is a visual reference, not a record. Anything that needs explaining, qualifying or
dating belongs here instead.

This file is the prose half. It records what the code implements and, the
load-bearing part, the places where it knowingly does something else, so those are
not repeatedly rediscovered as bugs and "fixed" into a regression.

**Both files describe [`:core:ui`](../core/ui/src/main/kotlin/dev/fitface/studio/core/ui/),
which is the authority.** If they disagree with the Kotlin, the Kotlin is right and
they are stale. Changing a token means changing all three together;
`SemanticColorContrastTest` and `FitTopBarLayoutTest` will catch some drift but they
cannot notice that a document went out of date.

## What is implemented faithfully

* **The palette, verbatim.** Every colour in the design is in
  [`FitFaceTheme.kt`](../core/ui/src/main/kotlin/dev/fitface/studio/core/ui/FitFaceTheme.kt)
  unchanged: teal `#7FE3D2`, deep teal `#00504A`, coral `#FFAF9F`, amber `#F2C879`,
  violet `#C8B6FF`, ink `#E6EDEA`, and the base → raised → card → sheet surface
  ladder `#0A0E0E → #101615 → #18201F → #222B29`. Elevation is expressed as lighter
  surfaces plus a 1dp `outlineVariant` border, never as a shadow, exactly as drawn.
  There are no colour literals anywhere outside that file.
* **The information architecture.** Five rail destinations (Widgets, Background,
  Styles, Validate, Install), selection as a peek bar over the canvas rather than a
  page, Inspector as a child of Widgets, Install as its own page, Validate as the
  only route to it.
* **Mono for quantities.** `labelMedium`, `labelSmall`, `FitFaceType.numeric`,
  `FitFaceType.micro` and `FitFaceType.readout` are monospace, and everything
  quantitative uses one of them. This is not decoration: a proportional digit changes
  width with its value, so a figure being nudged jitters while the button is held.
  `readout` exists because the Material ramp has no mono slot at 20sp, which is why
  the inspector's coordinates had fallen back to a proportional `headlineSmall`.
* **One shape for a top-bar action.** `FitIconButton` is 38dp square with a 1dp
  border, and the back button, the `⋯` overflow and the `≡` app menu are all it.
  The app's three global actions — report a problem, about, check for update — live
  *inside* that menu rather than beside it, which is what keeps the count at one.

## The menus

There are two: `AppMenuAction` in both top bars, and the overflow on a project row. They
share `FitDropdownMenu` and `FitMenuEntry` so that they are one thing with two anchors —
assembled separately they had already drifted, the row menu taking Material's default entry
type (`labelLarge`) against the bar menu's `bodyMedium`, so the same gesture opened two
different-looking menus in one app.

`AppMenuAction` was the **first** `DropdownMenu`, and it is worth saying why one was
allowed there at all after so much effort went into keeping the top bar's actions slot
narrow.

A `DropdownMenu` is a `Popup` — its own window, measured outside the composition that
anchors it — so the bar pays nothing for the entries. What the actions `Row` measures is
the anchoring `Box`, which wraps its content: one 38dp square, exactly what the single
report button it replaced measured. `FitTopBarLayoutTest.theOpenMenuCostsTheBarNoWidth`
holds that, and it exists specifically so that replacing the popup with an inline column
fails a test rather than quietly re-ellipsizing every editor subtitle.

Rules both menus follow:

* **Every entry closes the menu before it runs.** They open a dialog or navigate, and a
  popup left standing behind a dialog is the first thing that goes wrong here.
* **A destructive entry is `colorScheme.error` and goes last.** That is the same colour
  `FitButtonStyle.Danger` and `FitStatus.Fail` use, and last is furthest from where the
  menu opens — nothing above it can lose work.
* **The bar menu's glyph is `≡`, not `⋮`.** `⚙` and `ℹ` were the other candidates and were
  rejected because U+2699 and U+2139 fall through to the emoji font on many Android builds;
  they would be the only colour glyphs in the inventory.

**The two glyphs mean two scopes, not two behaviours.** `≡` is the *app's* menu and is the
same on every screen. `⋯` is "more about this one project", and it takes the form the
surface allows: a menu on a row in the list, and — on the editor's Canvas, where a popup
would be too small for what it holds — a jump to the Project page that carries rename,
duplicate, reset and delete. The rule the earlier note was protecting still holds: the two
never sit side by side.

**Every dialog's text slot scrolls, and About is sized to not need it.** An `AlertDialog`
caps its own height and gives the text slot the remainder, which on a landscape phone is a
few lines — and it clips rather than scrolling, silently. So the slot scrolls in all three
dialogs, and the scroll is on the slot rather than on something inside it, or an inner
scroller leaves the text above it clipped and fights the outer gesture. Scrolling is only
the floor, though: About is trimmed until it *fits* landscape outright, because content a
reader has to go looking for is content most of them never see. That is why its prose
carries a length bound and why the project link has no label line — the version needs that
line.

The About dialog behind it keeps its legal line to **one sentence** and puts a link to the
project below it. That is a deliberate trade rather than carelessness: `NOTICE.md` is the
long form, and a dialog that opens onto a paragraph of disclaimer is a dialog whose
disclaimer is not read. `AboutCopyTest` holds the claim ("independent", "not affiliated")
and the length, because those are what a well-meant rewording removes. It asserts copy
rather than layout because an `AlertDialog` never reaches idle in this harness —
`createComposeRule` throws `AppNotIdleException` before it can measure one, which is why
neither this dialog nor `DiagnosticsDialog` has a rendering test. The link is a
`LinkAnnotation.Url`, so the platform's URI handler opens it and `:core:ui` still needs no
`Context` and no `Intent`.

The menu is also where the previous-crash offer now lives. It keeps the rule it always
had — a crash changes the colour and the wording, never the size or the shape — but it
now has to move *two* things, because an amber `≡` cannot say what it is amber about: the
glyph is tinted and the first entry is relabelled "Report crash" and tinted with it.
`AppMenuActionTest` and `LibraryHeaderLayoutTest` both pin that the geometry does not
move.

## Departure 1 — the typefaces are the platform's

The design specifies IBM Plex Sans for prose and IBM Plex Mono for numerals. The app
uses `FontFamily.SansSerif` and `FontFamily.Monospace`, which on Android means Roboto
and Roboto Mono. No Plex files are bundled.

What is preserved is the *structure* of the choice — which text is mono and which is
proportional, and at what size and weight. What is not preserved is the faces
themselves.

This is deliberate. Bundling Plex would change the measured width of every string in
the app, and the top bar has already had one bug caused by exactly that class of
change: a text-labelled action ellipsized the subtitle on every editor page. A font
swap would need the whole visual pass re-verified on a device, and it would
re-baseline the geometry assertions in `FitTopBarLayoutTest`. It is a separate change
with its own regression window, not a detail to fold into an unrelated fix.

## Departure 2 — spacing is by literal, and the token was removed

The design defines a 4dp base scale (2/4/8/12/16/20/28/40). A `FitFaceSpacing` data
class encoding it used to exist, exposed as `MaterialTheme.fitSpacing`, and **nothing
in the repository ever read it** — not one call site. Against that, the UI modules
hold roughly 330 `.dp` literals, and about two thirds are off the scale: 3, 5, 6, 7,
9, 10, 11, 13, 14, 17, 18 are all in use, and have no token to map onto.

The token has been deleted rather than left in place. A design token with no consumers
is worse than no token: it reads as the rule the code follows, when the code does not
follow it, and the obvious repair — migrating 330 literals — is a very large diff, no
user-visible change, and one opportunity per literal to shift a layout. If that
migration is ever wanted, it should be its own change, with the scale first extended
to cover the values actually in use.

`FitFaceElevation` was removed for the same reason: zero consumers, and the codebase
expresses elevation with surface colour and a border instead, which is what the design
asks for.

## Departure 3 — 38dp actions, under the 48dp touch guideline

`FitIconButton` is 38dp, which is what the design specifies and what the back button
has always been. Material's minimum touch target is 48dp. `FitChip` is likewise 42dp.

Raising only the diagnostics action would have made it the widest item in the actions
slot and reopened the crowding this size exists to prevent. Raising all of them is a
coherent change and worth making, but it moves every bar, chip and row in the app and
belongs on its own branch.

## Semantic colours

`FitFaceSemanticColors` carries the two meanings a Material scheme has no slot for —
`warning` (the watch will accept this but render it differently from the preview) and
`experimental` (the direct-install path). They are per-theme, and `FitFaceTheme`
provides them through `LocalFitFaceSemanticColors`.

That provider is the whole point. Without it the composition local fell back to its
own default, so the light theme silently used the dark values: amber measured
**1.50:1** against the light background and violet **1.73:1**, against a 4.5:1 floor,
across nine call sites — the inspector's OPAQUE banner, the EDITED badge, the crash
report offer, the device badge, the transfer phase dot. The light values are
`LightColors`' own `secondary` and `tertiary`, so the light theme stays one palette
rather than gaining two invented colours, and they measure 6.07:1 and 6.27:1.

`SemanticColorContrastTest` holds both schemes to the floor and `FitFaceThemeTest`
holds the provider in place, because the values being correct and a composable
actually reading them are two different things and only the second was broken.

## Quiet text — there are two tiers and no alpha

`FitFaceTextColors` carries the two dim tiers, reached through `MaterialTheme.fitText`:
`secondary` for meta lines, subtitles and section headings, and `tertiary` for the
quietest line where three stack up — a project row's name, source and age. **Neither
takes an alpha at the call site, and nothing else may be used to dim text.**

That rule is the fix for a real defect. `onSurfaceVariant` is already the dim role, and
the code used to dim it *again* — nineteen call sites reaching for
`.copy(alpha = .68f)`, or `.72f`, or `.66f`, or `.48f`, each picked by hand. On the small
styles the second dimming took the text under the readable floor:

| Where | Was | Then | Now |
| --- | --- | --- | --- |
| `MicroLabel` — section headings, badges, the rail | × .68 | 4.08:1 dark / 3.38:1 light | 6.52 / 6.67 |
| `FitTopBar` subtitle | × .72 | 4.44:1 dark | 6.52 |
| `WidgetRow` record line | × .68 | 4.08:1 dark | 6.52 |
| `ProjectRow` age | × .48 | 2.58:1 dark | 5.56 / 5.27 |

None of those sizes reach the ~24sp where the 3:1 large-text allowance begins, so 4.5:1
was the floor and all of them were under it. A reader described the editor's small text
as "almost unreadable"; the numbers bore that out.

`tertiary` is `onSurfaceVariant × .90`, chosen for headroom rather than for the boundary:
`.82` is the lowest alpha that clears the floor in both schemes and it lands on 4.52:1 in
the light one, near enough that a small palette change would fail it silently.
`SmallTextContrastTest` holds both tiers to 4.5:1 on every surface, and separately asserts
the tertiary tier keeps margin above it. Disabled controls keep their own alphas and are
exempt — a control that cannot be operated should look inert.

**A different complaint that sounds the same.** The teal text buttons — "or pick from the
list", "reset placement", "restart setup" — measure **12.77:1**; contrast was never their
problem. They are 11sp monospace at Medium weight, so what makes them hard to read is size
and weight. That is still open, and it needs a type change rather than a colour one.
