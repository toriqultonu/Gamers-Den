'use client';

/**
 * S5 — Inventory (design.md §1, S5 row): "Read-only stock table, low-stock
 * rail, equipment register".
 *
 * Read-only is the whole design. Every role can open this screen, and none of
 * them edits from it — stock counts are corrected in Setup (Admin) or Menu &
 * stock (Manager), where the write is guarded and audited as a signed
 * `MANUAL_ADJUST` movement. So the table offers no controls at all and says
 * where the controls are instead, which is what the prototype's footnote does.
 *
 * It reads the same `['items']` the POS grid draws from, retired rows included:
 * a stock record is not a menu, and a line taken off sale still has units on
 * the shelf that a delivery has to reconcile against.
 */

import { PackageSearch } from 'lucide-react';
import { AccessNotice } from './access-notice';
import { DataTable, type Column } from '@/components/ui/data-table';
import { StatTile } from '@/components/ui/stat-tile';
import { Tag } from '@/components/ui/tag';
import { errorNotice, isApiError } from '@/lib/api';
import { formatAmount, formatBDT } from '@/lib/money';
import { useMenu } from '@/features/pos/queries';
import { useStations } from '@/features/sessions/queries';
import {
  STATION_STATUS_LABELS,
  STOCK_STATE_LABELS,
  STOCK_STATE_TAGS,
  itemCategoryLabel,
  lowStockItems,
  lowStockNote,
  stockRows,
  stockState,
  stockTotals,
  type Item,
} from '@/features/setup/schemas';

export function InventoryScreen() {
  const menu = useMenu();
  const stations = useStations();

  if (isApiError(menu.error) && menu.error.status === 403) {
    return <AccessNotice screen="Inventory" />;
  }

  const rows = stockRows(menu.data);
  const totals = stockTotals(menu.data);
  const low = lowStockItems(menu.data);

  const columns: Column<Item>[] = [
    {
      key: 'name',
      header: 'Item',
      render: (item) => (
        <span className="font-heading font-extrabold">
          {item.name ?? '—'}
          {item.active === false ? (
            <span className="ml-2 type-label opacity-55">Off menu</span>
          ) : null}
        </span>
      ),
    },
    {
      key: 'category',
      header: 'Category',
      width: '120px',
      render: (item) => <span className="opacity-70">{itemCategoryLabel(item.category)}</span>,
    },
    {
      key: 'price',
      header: 'Price',
      align: 'right',
      width: '100px',
      render: (item) => <span className="tabular">{formatBDT(item.price ?? 0)}</span>,
    },
    {
      key: 'stock',
      header: 'In stock',
      align: 'right',
      width: '90px',
      render: (item) => <span className="tabular">{formatAmount(item.stock ?? 0)}</span>,
    },
    {
      key: 'reorderAt',
      header: 'Reorder at',
      align: 'right',
      width: '100px',
      render: (item) => (
        <span className="tabular opacity-50">{formatAmount(item.reorderAt ?? 0)}</span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      width: '120px',
      render: (item) => {
        const state = stockState(item);
        return <Tag variant={STOCK_STATE_TAGS[state]}>{STOCK_STATE_LABELS[state]}</Tag>;
      },
    },
  ];

  return (
    <div data-testid="inventory-screen" className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col gap-4 overflow-auto p-5">
        <div className="grid grid-cols-4 border-2 border-divider divide-x-2 divide-divider">
          <StatTile label="Lines carried" value={formatAmount(totals.lines)} />
          <StatTile label="Units on the shelf" value={formatAmount(totals.units)} />
          <StatTile
            label="Needs ordering"
            value={formatAmount(totals.reorder)}
            variant={totals.reorder > 0 ? 'accent' : 'default'}
          />
          <StatTile
            label="Out of stock"
            value={formatAmount(totals.out)}
            variant={totals.out > 0 ? 'accent' : 'default'}
          />
        </div>

        {menu.isError ? (
          <p
            role="alert"
            data-testid="inventory-error"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {errorNotice(menu.error, 'The stock list could not be read.')}
          </p>
        ) : null}

        {menu.isPending ? (
          <TableSkeleton />
        ) : (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(item) => String(item.id ?? item.name)}
            caption="Stock on hand, by category"
            empty="Nothing on the menu yet — add the first item in Setup."
          />
        )}
      </div>

      <aside
        data-testid="inventory-rail"
        className="flex w-[356px] flex-none flex-col gap-3.5 overflow-auto border-l-2 border-divider bg-surface p-5"
      >
        <p className="type-label text-accent-strong">Low stock — needs ordering</p>

        {menu.isPending ? (
          <div data-testid="low-stock-skeleton" aria-busy="true" className="flex flex-col gap-2">
            {[0, 1, 2].map((card) => (
              <div key={card} className="border-2 border-divider p-3.5">
                <div className="h-4 w-32 bg-track" />
                <div className="mt-2 h-3 w-40 bg-track" />
              </div>
            ))}
          </div>
        ) : low.length === 0 ? (
          <p data-testid="low-stock-empty" className="text-body opacity-60">
            Nothing is under its reorder point. The shelves are stocked.
          </p>
        ) : (
          low.map((item) => (
            <div
              key={String(item.id ?? item.name)}
              data-testid="low-stock-card"
              className="flex flex-col gap-1 border-2 border-accent bg-bg p-3.5"
            >
              <p className="font-heading text-[16px] font-extrabold">{item.name}</p>
              <p className="text-[12px] opacity-70">{lowStockNote(item)}</p>
            </div>
          ))
        )}

        <p className="text-[12px] opacity-60">
          Stock counts are edited from Setup (Admin) or Menu &amp; stock (Manager).
        </p>

        <div className="h-0.5 bg-divider" />

        <EquipmentRegister
          stations={stations.data}
          loading={stations.isPending}
          failed={stations.isError}
        />
      </aside>
    </div>
  );
}

/* ------------------------------------------------------- equipment register */

/**
 * The equipment register — the consoles the venue owns and what each is doing.
 *
 * `GET /stations` is the only equipment the API keeps a register of, so that is
 * what this lists: name, console type, and the floor state that says whether it
 * is earning, free or on the bench for repair. The prototype's peripherals
 * (controllers, headsets, spare cables) have no table, no endpoint and no DDL
 * in any of the docs — inventing one would be inventing a contract — so the
 * card says plainly that they are tracked off-system rather than showing
 * numbers nobody maintains.
 */
function EquipmentRegister({
  stations,
  loading,
  failed,
}: {
  stations: ReturnType<typeof useStations>['data'];
  loading: boolean;
  failed: boolean;
}) {
  const rows = stations ?? [];

  return (
    <section data-testid="equipment-register" className="flex flex-col gap-2">
      <p className="type-label opacity-55">Equipment register</p>

      {loading ? (
        <div aria-busy="true" className="flex flex-col gap-2">
          {[0, 1, 2, 3].map((row) => (
            <div key={row} className="h-4 w-full bg-track" />
          ))}
        </div>
      ) : failed ? (
        <p className="text-[12px] opacity-60">The console register could not be read.</p>
      ) : rows.length === 0 ? (
        <p data-testid="equipment-empty" className="text-[12px] opacity-60">
          No consoles registered — add one in Setup.
        </p>
      ) : (
        rows.map((station) => (
          <div
            key={String(station.id ?? station.name)}
            className="flex justify-between gap-2 border-b border-divider pb-2 text-[13px]"
          >
            <span>{station.name}</span>
            <span className="opacity-60">
              {station.consoleType} ·{' '}
              {station.status === 'MAINTENANCE'
                ? STATION_STATUS_LABELS.MAINTENANCE
                : (station.floorState ?? 'FREE').toLowerCase()}
            </span>
          </div>
        ))
      )}

      <p className="flex items-start gap-2 text-[12px] opacity-55">
        <PackageSearch aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" strokeWidth={2} />
        Controllers, headsets and cables are counted off-system — the API keeps no register for
        them yet.
      </p>
    </section>
  );
}

/* --------------------------------------------------------------- skeleton */

/** The loading state, shaped like the table it becomes (design.md §1). */
function TableSkeleton() {
  return (
    <div data-testid="inventory-skeleton" aria-busy="true" className="flex flex-col">
      <div className="h-6 border-b-2 border-divider" />
      {[0, 1, 2, 3, 4, 5, 6, 7].map((row) => (
        <div key={row} className="flex items-center gap-4 border-b border-divider px-2 py-2.5">
          <div className="h-3 w-40 bg-track" />
          <div className="h-3 w-20 bg-track" />
          <div className="ml-auto h-3 w-16 bg-track" />
          <div className="h-3 w-12 bg-track" />
        </div>
      ))}
    </div>
  );
}
