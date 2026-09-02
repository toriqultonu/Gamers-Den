'use client';

/**
 * DataTable — docs/design.md §2 (primitives row).
 *
 * 2px header rule, hairline row rules, hover tint, and an accent outline on the
 * selected row (the BookingTable behaviour in design.md §2). Numbers are
 * tabular everywhere per design.md §3.
 */

import type { ReactNode } from 'react';
import { cn } from './cn';

export type Column<Row> = {
  key: string;
  header: ReactNode;
  /** Cell renderer; defaults to `String(row[key])` when the key is a plain field. */
  render?: (row: Row) => ReactNode;
  align?: 'left' | 'right';
  width?: string;
};

export type DataTableProps<Row> = {
  columns: readonly Column<Row>[];
  rows: readonly Row[];
  rowKey: (row: Row) => string;
  /** Row currently selected — drawn with the accent outline. */
  selectedKey?: string | null;
  onSelect?: (row: Row) => void;
  /** Shown in place of the body when there are no rows. */
  empty?: ReactNode;
  caption?: string;
  className?: string;
};

export function DataTable<Row>({
  columns,
  rows,
  rowKey,
  selectedKey = null,
  onSelect,
  empty = 'Nothing here yet.',
  caption,
  className,
}: DataTableProps<Row>) {
  if (rows.length === 0) {
    return (
      <div
        data-testid="data-table-empty"
        className={cn('border-2 border-divider p-4 text-[13px] opacity-60', className)}
      >
        {empty}
      </div>
    );
  }

  return (
    <table className={cn('w-full border-collapse text-body tabular', className)}>
      {caption ? <caption className="sr-only">{caption}</caption> : null}
      <thead>
        <tr>
          {columns.map((column) => (
            <th
              key={column.key}
              scope="col"
              style={column.width ? { width: column.width } : undefined}
              className={cn(
                'type-label border-b-2 border-divider px-2 py-2 opacity-60',
                column.align === 'right' ? 'text-right' : 'text-left',
              )}
            >
              {column.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const key = rowKey(row);
          const selected = key === selectedKey;
          return (
            <tr
              key={key}
              data-state={selected ? 'selected' : undefined}
              aria-selected={onSelect ? selected : undefined}
              tabIndex={onSelect ? 0 : undefined}
              onClick={onSelect ? () => onSelect(row) : undefined}
              onKeyDown={
                onSelect
                  ? (event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        onSelect(row);
                      }
                    }
                  : undefined
              }
              className={cn(
                onSelect && 'cursor-pointer hover:bg-neutral-200',
                'focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent',
                selected && 'outline-2 -outline-offset-2 outline-accent',
              )}
            >
              {columns.map((column) => (
                <td
                  key={column.key}
                  className={cn(
                    'border-b border-divider px-2 py-2',
                    column.align === 'right' ? 'text-right' : 'text-left',
                  )}
                >
                  {column.render
                    ? column.render(row)
                    : String((row as Record<string, unknown>)[column.key] ?? '')}
                </td>
              ))}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
