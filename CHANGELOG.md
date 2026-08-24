# Changelog

User-visible changes per version. APKs are on the
[releases page](https://github.com/satvikgosai/fitface-studio/releases); the reasoning
behind a change lives in [`docs/`](docs/README.md).

## Unreleased

Added:

- A menu in the header of both screens, holding **Report a problem**, **About this app**
  and **Check for update**.
- **Check for update** finds a newer release, tells you the size, downloads it and
  installs it. It keeps going if you close the dialog, and stops if the new build was
  signed with a different key — installing that would mean uninstalling first, which
  deletes every saved project.
- **About this app** shows which version you are running, which nothing did before, and
  links to the project page for the source, the full notices and the releases.

Fixed:

- **Install over Bluetooth** said the watch companion app was not installed on phones
  where it is — it ships under a second name on some models, the Galaxy A10s and A11
  among them. Those phones could not get past step 1.
- A phone the app cannot recognise no longer blocks **Install over Bluetooth**. It says
  what it could not find and lets you try anyway — only the attempt can tell whether the
  watch answers.
- Every editor page cut off its own subtitle: "face 00112 · style0" showed as
  "face 00112 · styl…". The header carries one small menu button now, so the title has
  room.
- On the library screen that button no longer jumps sideways when you switch between
  Watch faces and Projects, and it lines up with the heading beside it.
- Switching between **Watch faces** and **Projects** no longer moves the two tabs up or
  down the screen, so the one you just tapped stays where you tapped it.
- Warning text — the "Opaque" note on a widget, the "Edited" badge, the install status —
  was almost unreadable in light mode, and is legible now.
- Holding the phone sideways shrank the watch face in the editor to a third of its size,
  and tapping a widget shrank it to a dot. Sideways, the face is now beside its controls
  at full size.
- Opening a project sideways showed a grey placeholder with the spinner and "decoding
  images" cut off below the bottom of the screen.
- Coordinates and sizes in the inspector no longer shift about as you nudge them.
- Rows in the widget list no longer wrap the word "frames" onto a line of its own.
- Small grey text — section headings, the record line under each widget, a project's age
  — was too faint, and worse in light mode. All of it is brighter.

Changed:

- The **Report a problem** note says what the report contains and stops there. It used to
  list what it leaves out, which read as though the app were holding that back.
- A crash in the previous run tints the header menu and its report entry instead of
  replacing the button; a screen reader still announces it as **Report crash**.
- The list of watch faces is kept for a week instead of half a day. **Refresh** on the
  Watch faces tab still fetches the latest whenever you want.

## 0.1.1 (code `17`) — 2026-08-18

Fixed:

- Empty catalogue on phones whose locale the store refuses — `es_419` (default Spanish in
  Latin America), `en_001`, `en_150`, Indonesian, Hebrew, Yiddish, Filipino, Tagalog,
  Quechua, Guaraní. Repaired where the region alone is at fault, retried in `en_US`
  otherwise.
- "Catalogue unavailable" no longer blames your connection; it shows the real reason.
- A failed refresh keeps the cached catalogue instead of emptying the list.

Added:

- **Report a problem** on both screens: a copyable report of the app version, this phone,
  and what the app did. Carries no device identifiers, watch addresses or image details.
- A crash in the previous run is offered as **Report crash** on the next launch.

## 0.1.0 (code `16`) — 2026-08-14

Initial release.
