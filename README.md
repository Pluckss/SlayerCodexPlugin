# Slayer Codex

A RuneLite plugin that shows wiki-sourced gear setups for your current Slayer task — without leaving the client.

## Features

- **Instant task detection** — the panel updates the moment a Slayer master assigns or updates your task, directly from the dialogue box; the chat gem and enchanted Slayer helmet also work as fallbacks
- **Boss variant awareness** — automatically suggests the boss version (e.g. Hellhounds → Cerberus) when enabled
- **Gear matrix** per combat style and slot tier (BIS → budget), pulled from bundled OSRS Wiki strategy data
- **Your Best column** — compares each slot against your equipped items and inventory to show the best gear you actually own
- **Bank-aware recommendations** — open your bank once per session to unlock full ownership-based picks across all slots
- **Slot notes** — slots that carry wiki footnotes are marked with `*`; select any marked slot to read the full note text inline
- **Compact sidebar UI** — designed specifically for the RuneLite narrow sidebar; all tabs fit, no horizontal scroll

## How to use

1. Get assigned a Slayer task by any master — the plugin reads the assignment dialogue and updates instantly. No need to check your gem or helmet first.
2. A red **Open task setup** button appears in the header. Click it to jump straight to that monster's strategy.
   - If the task is auto-matched, the button disappears — you are already there.
3. Choose a **combat style tab** (Melee / Ranged / Magic / etc.) at the top of the detail panel.
4. The gear table shows **Wiki** (recommended) and **Yours** (your best owned item per slot) side by side.
5. Click any slot row marked with `*` to read the relevant strategy notes at the bottom.
6. Use the blue **Wiki** button to open the full wiki strategy page in your browser.

### Task pill — what the colours mean

The chip at the top of the panel reflects the live state of your task:

| Pill | Meaning |
|---|---|
| **Grey · NO TASK** | No task detected. Visit a Slayer master to get one. |
| **Green · ON TASK · N left** | Task assigned **and** the bundled dataset has a strategy for it — gear matrix loads automatically. |
| **Orange · ON TASK · N left · NO DATA** | Task assigned but the bundled dataset doesn't ship a strategy for that monster. The task name and a working **Wiki** button are still shown so you can read up manually. |

> **Tip:** Open your bank at least once per session. The orange bank banner at the top disappears once your bank contents are known, and the **Yours** column becomes fully accurate across all slots. (This is separate from the orange pill state above.)

## Configuration

| Setting | Default | Description |
|---|---|---|
| Auto-detect Slayer task | On | Detect task from chat and auto-open the matching strategy |
| Prefer boss variant | On | Auto-select the boss version when a task has one |
| Show Slayer XP per kill | On | Display expected Slayer XP in the monster header |
| Show task remaining count | On | Show kills remaining next to task name |
| Compact gear table rows | Off | Smaller row height to show more slots without scrolling |
| Show boss variant badge | On | Badge in header when a boss variant exists for the task |
| Preferred combat style | Auto | Which style tab to open first (Auto / Melee / Ranged / Magic) |
| Highlight owned items | On | Green tint on items in Your Best column that you own |

## Data

Strategy data is bundled with the plugin and sourced from the [OSRS Wiki](https://oldschool.runescape.wiki) under the [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/) licence. No network requests are made at runtime.

## License

This plugin is released under the BSD 2-Clause licence.
Wiki data: CC BY-NC-SA 3.0 — Old School RuneScape Wiki contributors.
