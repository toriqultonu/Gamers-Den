# ui

Hand-rolled primitives, built to the `docs/design.md` §2 variant/state table
(TASK F02). No third-party visual library: radius 0, 2px structural rules, 45%
disabled opacity, 2px accent `:focus-visible` outline, `tabular-nums` on every
number.

| Primitive | Variants | States |
|---|---|---|
| `Button` | primary, secondary, ghost, icon, block | default, hover, active, focus-visible, disabled, loading |
| `Tag` | accent, neutral, outline | static |
| `Dialog` | — | open/closed, Escape + backdrop close, focus trap |
| `FieldInput` | — | default, error, disabled |
| `SegmentedChoice` | — | selected, unselected, disabled option |
| `ChipSelect` | single, multiple | selected, unselected, disabled option |
| `DataTable` | — | rows, selected row (accent outline), empty |
| `StatTile` | default, accent | — |
| `BarChart` | accent series, alt series | plotted, empty |
| `ProgressBar` | accent, alt | — |
| `AvatarSwatch` | sm, md, lg · chip / initials | selected, static |
| `ImagePicker` | — | empty, set, disabled |
| `TimeStepper` | — | −30 disabled at 30 min, +30 disabled at max |
| `TokenBadge` | inline, stub | today, previous day (shows issue date) |

`TimeStepper` and `TokenBadge` are listed under `components/domain` in
`frontend/ARCHITECTURE.md` §3; TASK F02 places them here with the other
primitives, since neither one touches a query or a domain type.

Variant/state props are typed, and the exported `*_VARIANTS` tuples are what
the rendering tests iterate — adding a variant without a test fails the suite.
