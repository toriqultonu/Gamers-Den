/**
 * `components/ui` — the hand-rolled primitives from docs/design.md §2.
 *
 * Every variant/state in that table lives in exactly one file here; domain
 * components in `components/domain` compose these and never restyle them.
 */

export { Button, BUTTON_SIZES, BUTTON_VARIANTS } from './button';
export type { ButtonProps, ButtonSize, ButtonVariant } from './button';

export { Tag, TAG_VARIANTS } from './tag';
export type { TagProps, TagVariant } from './tag';

export { Dialog } from './dialog';
export type { DialogProps } from './dialog';

export { FieldInput } from './field-input';
export type { FieldInputProps } from './field-input';

export { SegmentedChoice } from './segmented-choice';
export type { SegmentedChoiceProps, SegmentedOption } from './segmented-choice';

export { ChipSelect } from './chip-select';
export type { ChipOption, ChipSelectProps } from './chip-select';

export { DataTable } from './data-table';
export type { Column, DataTableProps } from './data-table';

export { StatTile, STAT_TILE_VARIANTS } from './stat-tile';
export type { StatTileProps, StatTileVariant } from './stat-tile';

export { BarChart } from './bar-chart';
export type { BarChartProps, BarDatum } from './bar-chart';

export { ProgressBar, PROGRESS_VARIANTS } from './progress-bar';
export type { ProgressBarProps, ProgressVariant } from './progress-bar';

export { AvatarSwatch, AVATAR_COLORS, AVATAR_SWATCH_SIZES } from './avatar-swatch';
export type { AvatarColor, AvatarSwatchProps, AvatarSwatchSize } from './avatar-swatch';

export { ImagePicker } from './image-picker';
export type { ImagePickerProps } from './image-picker';

export { TimeStepper, BLOCK_MINUTES, formatBlocks } from './time-stepper';
export type { TimeStepperProps } from './time-stepper';

export { TokenBadge, TOKEN_BADGE_VARIANTS, formatToken } from './token-badge';
export type { TokenBadgeProps, TokenBadgeVariant } from './token-badge';

export { cn } from './cn';
