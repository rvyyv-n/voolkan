# Settings UI overhaul + QoL features (task `settings-ui`): scope proposal, NOT started

First in the parked queue; waiting for the owner to kick off work. Nothing here is implemented yet.

## What exists after 4.1 to 4.4

Four pages (Video, Graphics, Optimizations, Other), a search field, per-option tooltips and performance-impact labels, Apply/Undo, a read-only Compatibility block, a Preset row, voolkan branding.

## Proposed scope (small, in this order; stop wherever the owner likes)

1. **Regroup options** by purpose instead of by upstream's historical split: move latency options (frame queue, low latency, swapchain images, VSync, framerate limit) into a "Latency" block on the Video page, keep "Other" for threads and device. Pure reordering in `Options.java`; no new widgets.
2. **Per-option "reset to default"** (small button or right-click) using the defaults in `Config`. New widget code in `dev.voolkan.gui`.
3. **Search improvements**: match against block titles and show the page name per result. Small change in `VOptionScreen.performSearch`.
4. **Scaling fixes** for large GUI scales (the options list gets tight below 600 px height; the bottom Compatibility block needs scrolling to reach).
5. **Additional QoL features** (to be described by the owner when we build this).

## Not proposed

A full redesign (new layout, themes, controller navigation): high churn against upstream's GUI code, which makes later upstream cherry-picks harder, for little gain on the target use (competitive PvP, settings set once).

## Questions for the owner

- Which of 1 to 4 do you want, if any?
- Should the latency options really move out of "Other"? (They are the main voolkan additions.)
