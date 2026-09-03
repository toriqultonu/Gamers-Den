'use client';

/**
 * S10 — Setup / Menu & stock (design.md §1, S10 row; docs/bookings.md §1).
 *
 * One route with two faces. The owner configures the venue — consoles, the
 * rate card, the staff roster and the pre-booking switches; the manager
 * configures the shelves. Which sections exist is decided once, in
 * `features/setup/schemas.ts`, so the main column, the rail and the tests all
 * read the same sentence rather than three hand-written role checks that can
 * drift apart. A cashier gets the access notice: the middleware already turned
 * them away, and this is what shows when the routing hint and the real role
 * disagree.
 *
 * Hiding is cosmetic and the screen behaves as if it were (§4.3): every write
 * here is guarded server-side, and the three refusals the operator can
 * actually provoke — a name already taken, a console someone is playing on, a
 * staff member with a drawer open — are rendered as notices beside the thing
 * that was refused, with nothing removed from the screen in the meantime.
 *
 * Nothing on this screen is optimistic. Configuration is cheap to wait for and
 * expensive to draw wrongly: a station that appeared and then vanished, or a
 * pre-booking switch that took the Bookings tab away and then handed it back,
 * is a terminal the counter stops trusting.
 */

import { useEffect, useMemo, useState } from 'react';
import { AccessNotice } from './access-notice';
import { PrintingCard } from './printing-card';
import { Button } from '@/components/ui/button';
import { ChipSelect } from '@/components/ui/chip-select';
import { DataTable, type Column } from '@/components/ui/data-table';
import { FieldInput } from '@/components/ui/field-input';
import { SegmentedChoice } from '@/components/ui/segmented-choice';
import { Tag } from '@/components/ui/tag';
import { errorNotice } from '@/lib/api';
import { formatBDT, parseAmount } from '@/lib/money';
import type { Role } from '@/lib/nav';
import { CONSOLE_TYPES, type ConsoleType } from '@/features/queue/schemas';
import { useBookingSettings } from '@/features/bookings/queries';
import { useUpdateBookingSettings } from '@/features/bookings/mutations';
import { updateBookingSettingsSchema } from '@/features/bookings/schemas';
import { useMenu, usePricing } from '@/features/pos/queries';
import { useStations } from '@/features/sessions/queries';
import { staffRows, useStaff } from '@/features/setup/queries';
import {
  useCreateItem,
  useCreateStaff,
  useCreateStation,
  useDeleteItem,
  useDeleteStaff,
  useDeleteStation,
  useUpdateItem,
  useUpdatePricing,
} from '@/features/setup/mutations';
import {
  ITEM_CATEGORIES,
  ITEM_CATEGORY_LABELS,
  STAFF_ROLES,
  canEditBookingSettings,
  canOpenSetup,
  canSetDefaultPrinter,
  changedPricing,
  createItemSchema,
  createStaffSchema,
  createStationSchema,
  fieldError,
  hasSetupSection,
  itemCategoryLabel,
  pricingDraft,
  roleNote,
  staffRoleLabel,
  stationRemovable,
  stockRows,
  type Item,
  type ItemCategory,
  type PricingFormInput,
  type Staff,
  type StaffRole,
  type Station,
} from '@/features/setup/schemas';

export type SetupScreenProps = {
  /** The role the middleware just read from the session cookie. */
  role: Role | null;
};

export function SetupScreen({ role }: SetupScreenProps) {
  if (!canOpenSetup(role)) {
    return (
      <AccessNotice
        screen="Setup"
        message="Stations, pricing, staff and stock are configured by the owner and the manager. Your till and the floor are unaffected."
      />
    );
  }

  const showStations = hasSetupSection(role, 'stations');
  const showPricing = hasSetupSection(role, 'pricing');
  const showPrebooking = canEditBookingSettings(role);
  const showStaff = hasSetupSection(role, 'staff');
  const showMenu = hasSetupSection(role, 'menu');
  const showPrinting = hasSetupSection(role, 'printing');

  return (
    <div data-testid="setup-screen" data-role={role ?? 'none'} className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col gap-5 overflow-auto p-5">
        {showStations ? <StationsSection /> : null}
        {showPricing ? <PricingSection /> : null}
        {showPrebooking ? <PreBookingSection /> : null}
        {showStaff ? <StaffSection /> : null}
        {showMenu ? <MenuSection /> : null}
        {showPrinting ? <PrintingCard canSetDefault={canSetDefaultPrinter(role)} /> : null}
      </div>

      <aside
        data-testid="setup-rail"
        className="flex w-[356px] flex-none flex-col gap-4 overflow-auto border-l-2 border-divider bg-surface p-5"
      >
        <p data-testid="setup-role-note" className="text-[12px] opacity-70">
          {roleNote(role)}
        </p>

        {showStations ? (
          <>
            <div className="h-0.5 bg-divider" />
            <AddStationForm />
          </>
        ) : null}

        {showStaff ? (
          <>
            <div className="h-0.5 bg-divider" />
            <AddStaffForm />
          </>
        ) : null}

        {showMenu ? (
          <>
            <div className="h-0.5 bg-divider" />
            <AddItemForm />
          </>
        ) : null}
      </aside>
    </div>
  );
}

/* ------------------------------------------------------------------ shared */

function SectionHeading({ children }: { children: React.ReactNode }) {
  return <h2 className="type-label opacity-55">{children}</h2>;
}

function Notice({ testId, children }: { testId: string; children: React.ReactNode }) {
  return (
    <p
      role="alert"
      data-testid={testId}
      className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
    >
      {children}
    </p>
  );
}

function Saved({ testId, children }: { testId: string; children: React.ReactNode }) {
  return (
    <p role="status" data-testid={testId} className="border-2 border-divider px-3 py-2 text-body">
      {children}
    </p>
  );
}

/* ---------------------------------------------------------------- stations */

function StationsSection() {
  const stations = useStations();
  const remove = useDeleteStation();
  const pricing = usePricing();
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const rateFor = (consoleType: string | undefined) => {
    const row = (pricing.data ?? []).find((rate) => rate.consoleType === consoleType);
    return row ? `${formatBDT(row.perHalfHour ?? 0)} / 30 min` : '—';
  };

  const drop = (station: Station) => {
    const id = station.id;
    if (typeof id !== 'number') return;
    setNotice(null);
    setBusyId(id);
    remove.mutate(
      { id },
      {
        onError: (error) =>
          setNotice(errorNotice(error, `${station.name ?? 'That console'} could not be removed.`)),
        onSettled: () => setBusyId(null),
      },
    );
  };

  const columns: Column<Station>[] = [
    {
      key: 'name',
      header: 'Name',
      render: (station) => <span className="font-heading font-extrabold">{station.name}</span>,
    },
    {
      key: 'consoleType',
      header: 'Console',
      width: '90px',
      render: (station) => <span className="opacity-70">{station.consoleType}</span>,
    },
    {
      key: 'rate',
      header: 'Rate',
      width: '140px',
      render: (station) => <span className="opacity-70">{rateFor(station.consoleType)}</span>,
    },
    {
      key: 'state',
      header: 'Status',
      width: '140px',
      render: (station) => (
        <span className="opacity-70">
          {station.status === 'MAINTENANCE' ? 'Maintenance' : (station.floorState ?? 'FREE')}
        </span>
      ),
    },
    {
      key: 'action',
      header: 'Action',
      align: 'right',
      width: '110px',
      render: (station) => (
        <Button
          variant="ghost"
          size="sm"
          disabled={!stationRemovable(station)}
          loading={busyId === station.id && remove.isPending}
          onClick={() => drop(station)}
        >
          {stationRemovable(station) ? 'Remove' : 'In use'}
        </Button>
      ),
    },
  ];

  return (
    <section data-testid="stations-section" className="flex flex-col gap-2">
      <SectionHeading>Stations</SectionHeading>
      {notice ? <Notice testId="stations-notice">{notice}</Notice> : null}
      {stations.isPending ? (
        <RowsSkeleton testId="stations-skeleton" />
      ) : stations.isError ? (
        <Notice testId="stations-error">
          {errorNotice(stations.error, 'The consoles could not be read.')}
        </Notice>
      ) : (
        <DataTable
          columns={columns}
          rows={stations.data ?? []}
          rowKey={(station) => String(station.id ?? station.name)}
          caption="Consoles on the floor"
          empty="No consoles yet — add the first one on the right."
        />
      )}
    </section>
  );
}

/* ----------------------------------------------------------------- pricing */

/**
 * The rate card, one editable card per console type.
 *
 * "New blocks only — running sessions keep the prices they purchased", so
 * saving here is safe mid-shift and the card says so. The morning window is an
 * OPEN FLAG (design.md §8.3): the fields show whatever the server holds and
 * save whatever is typed — nothing here invents 10:00–14:00.
 */
function PricingSection() {
  const pricing = usePricing();
  const save = useUpdatePricing();

  const [draft, setDraft] = useState(() => pricingDraft(pricing.data));
  const [notice, setNotice] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  // The server's card is the truth; the draft re-bases on it whenever it lands
  // or is invalidated, and only diverges while somebody is typing into it.
  useEffect(() => {
    if (pricing.data) setDraft(pricingDraft(pricing.data));
  }, [pricing.data]);

  const dirty = useMemo(() => changedPricing(pricing.data, draft), [pricing.data, draft]);

  const set = (consoleType: ConsoleType, patch: Partial<PricingFormInput>) => {
    setSaved(false);
    setDraft((current) => ({ ...current, [consoleType]: { ...current[consoleType], ...patch } }));
  };

  const submit = () => {
    setNotice(null);
    setSaved(false);
    if (dirty.length === 0) return;
    save.mutate(dirty, {
      onSuccess: () => setSaved(true),
      onError: (error) => setNotice(errorNotice(error, 'The rates could not be saved.')),
    });
  };

  return (
    <section data-testid="pricing-section" className="flex flex-col gap-2">
      <SectionHeading>Pricing · per console</SectionHeading>
      {notice ? <Notice testId="pricing-notice">{notice}</Notice> : null}
      {saved ? (
        <Saved testId="pricing-saved">
          Rates saved. New blocks only — sessions already running keep the prices they bought.
        </Saved>
      ) : null}

      {pricing.isPending ? (
        <RowsSkeleton testId="pricing-skeleton" rows={2} />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-4">
            {CONSOLE_TYPES.map((consoleType) => {
              const rate = draft[consoleType];
              return (
                <div
                  key={consoleType}
                  data-testid={`pricing-card-${consoleType}`}
                  className="flex flex-col gap-3 border-2 border-text p-4"
                >
                  <p className="font-heading text-[18px] font-extrabold">{consoleType}</p>
                  <div className="grid grid-cols-2 gap-2.5">
                    <FieldInput
                      label="Per hour (৳)"
                      inputMode="numeric"
                      value={String(rate.perHour)}
                      onChange={(event) =>
                        set(consoleType, { perHour: parseAmount(event.target.value) ?? 0 })
                      }
                    />
                    <FieldInput
                      label="Per 30 min (৳)"
                      inputMode="numeric"
                      value={String(rate.perHalfHour)}
                      onChange={(event) =>
                        set(consoleType, { perHalfHour: parseAmount(event.target.value) ?? 0 })
                      }
                    />
                  </div>
                  <div className="grid grid-cols-3 gap-2.5">
                    <FieldInput
                      label="Morning −%"
                      inputMode="numeric"
                      value={String(rate.morningDiscountPct)}
                      onChange={(event) =>
                        set(consoleType, {
                          morningDiscountPct: parseAmount(event.target.value) ?? 0,
                        })
                      }
                    />
                    <FieldInput
                      label="From"
                      placeholder="10:00"
                      value={rate.morningStart}
                      onChange={(event) => set(consoleType, { morningStart: event.target.value })}
                    />
                    <FieldInput
                      label="Until"
                      placeholder="14:00"
                      value={rate.morningEnd}
                      onChange={(event) => set(consoleType, { morningEnd: event.target.value })}
                    />
                  </div>
                </div>
              );
            })}
          </div>

          <div className="flex items-center gap-3">
            <Button
              variant="primary"
              disabled={dirty.length === 0}
              loading={save.isPending}
              onClick={submit}
            >
              Save rates
            </Button>
            <p className="text-[12px] opacity-60">
              The morning window is still unconfirmed with the owner — these fields show what the
              server holds.
            </p>
          </div>
        </>
      )}
    </section>
  );
}

/* ------------------------------------------------------------- pre-booking */

/**
 * The pre-booking switches — Admin only, and the one control here whose reach
 * is the whole venue: `enabled: false` hides the Bookings nav item on every
 * terminal and starts refusing new bookings with `PREBOOKING_DISABLED`.
 *
 * Fee and cutoff go in the same PUT as the switch because they are one
 * decision, and because the server applies all three to new bookings only —
 * every booking already sold keeps the terms it was sold under.
 */
function PreBookingSection() {
  const settings = useBookingSettings();
  const save = useUpdateBookingSettings();

  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [fee, setFee] = useState<string | null>(null);
  const [cutoff, setCutoff] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [saved, setSaved] = useState<boolean | null>(null);

  const stored = settings.data;
  const enabledValue = enabled ?? stored?.enabled ?? false;
  const feeValue = fee ?? String(stored?.packageFee ?? 0);
  const cutoffValue = cutoff ?? String(stored?.cancelCutoffHours ?? 0);

  const parsed = updateBookingSettingsSchema.safeParse({
    enabled: enabledValue,
    packageFee: parseAmount(feeValue) ?? undefined,
    cancelCutoffHours: parseAmount(cutoffValue) ?? undefined,
  });
  const errorFor = (field: string) =>
    touched && !parsed.success ? fieldError(parsed.error, field) : undefined;

  const submit = () => {
    setTouched(true);
    setNotice(null);
    setSaved(null);
    if (!parsed.success) return;
    save.mutate(parsed.data, {
      onSuccess: (next) => {
        setSaved(next.enabled);
        setEnabled(null);
        setFee(null);
        setCutoff(null);
      },
      onError: (error) =>
        setNotice(errorNotice(error, 'The pre-booking settings could not be saved.')),
    });
  };

  return (
    <section data-testid="prebooking-section" className="flex flex-col gap-2">
      <SectionHeading>Pre-booking</SectionHeading>
      {notice ? <Notice testId="prebooking-notice">{notice}</Notice> : null}
      {saved !== null ? (
        <Saved testId="prebooking-saved">
          {saved
            ? 'Pre-booking is on. New bookings take the fee and cutoff above; bookings already sold keep theirs.'
            : 'Pre-booking is off. The Bookings screen is hidden on every terminal and new bookings are refused; the ones already paid for still check in and cancel.'}
        </Saved>
      ) : null}

      {settings.isPending ? (
        <RowsSkeleton testId="prebooking-skeleton" rows={2} />
      ) : (
        <div className="flex max-w-[560px] flex-col gap-3 border-2 border-text p-4">
          <div className="flex items-center gap-3">
            <SegmentedChoice
              label="Pre-booking"
              value={enabledValue ? 'on' : 'off'}
              onChange={(value) => {
                setSaved(null);
                setEnabled(value === 'on');
              }}
              options={[
                { value: 'on', label: 'On' },
                { value: 'off', label: 'Off' },
              ]}
            />
            <p className="text-[12px] opacity-60">
              Customers pay play time plus the package fee up front to hold a console. Switching it
              off hides the Bookings screen for all staff.
            </p>
          </div>

          <div className="grid grid-cols-2 gap-2.5">
            <FieldInput
              label="Package fee (৳, paid up front)"
              inputMode="numeric"
              value={feeValue}
              error={errorFor('packageFee')}
              onChange={(event) => {
                setSaved(null);
                setFee(event.target.value);
              }}
            />
            <FieldInput
              label="Free cancellation until (hours before)"
              inputMode="numeric"
              value={cutoffValue}
              error={errorFor('cancelCutoffHours')}
              onChange={(event) => {
                setSaved(null);
                setCutoff(event.target.value);
              }}
            />
          </div>

          <Button variant="primary" loading={save.isPending} onClick={submit}>
            Save pre-booking settings
          </Button>
        </div>
      )}
    </section>
  );
}

/* ------------------------------------------------------------------- staff */

function StaffSection() {
  const staff = useStaff();
  const remove = useDeleteStaff();
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const drop = (person: Staff) => {
    const id = person.id;
    if (typeof id !== 'number') return;
    setNotice(null);
    setBusyId(id);
    remove.mutate(
      { id },
      {
        onError: (error) =>
          setNotice(errorNotice(error, `${person.name ?? 'That person'} could not be removed.`)),
        onSettled: () => setBusyId(null),
      },
    );
  };

  const columns: Column<Staff>[] = [
    {
      key: 'name',
      header: 'Name',
      render: (person) => <span className="font-heading font-extrabold">{person.name}</span>,
    },
    {
      key: 'role',
      header: 'Role',
      width: '110px',
      render: (person) => <span className="opacity-70">{staffRoleLabel(person.role)}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      width: '120px',
      render: (person) =>
        person.active === false ? (
          <Tag variant="neutral">Removed</Tag>
        ) : (
          <span className="opacity-70">Active</span>
        ),
    },
    {
      key: 'action',
      header: 'Action',
      align: 'right',
      width: '110px',
      render: (person) =>
        person.active === false || person.role === 'ADMIN' ? (
          <span className="text-[12px] opacity-45">—</span>
        ) : (
          <Button
            variant="ghost"
            size="sm"
            loading={busyId === person.id && remove.isPending}
            onClick={() => drop(person)}
          >
            Remove
          </Button>
        ),
    },
  ];

  return (
    <section data-testid="staff-section" className="flex flex-col gap-2">
      <SectionHeading>Staff</SectionHeading>
      {notice ? <Notice testId="staff-notice">{notice}</Notice> : null}
      {staff.isPending ? (
        <RowsSkeleton testId="staff-skeleton" rows={3} />
      ) : staff.isError ? (
        <Notice testId="staff-error">
          {errorNotice(staff.error, 'The staff roster could not be read.')}
        </Notice>
      ) : (
        <DataTable
          columns={columns}
          rows={staffRows(staff.data)}
          rowKey={(person) => String(person.id ?? person.name)}
          caption="Staff who can sign in on this terminal"
          empty="Only the owner account exists so far."
        />
      )}
      <p className="text-[12px] opacity-60">
        New staff sign in from the login screen with their role and PIN. Someone on shift cannot be
        removed — close their shift first. PINs are never shown; set a new one to replace a
        forgotten one.
      </p>
    </section>
  );
}

/* ------------------------------------------------------------ menu & stock */

/**
 * The menu editor: price, counted stock, reorder point, one row at a time.
 *
 * `stock` is the **absolute counted figure**, not a delta — the server audits
 * the difference as one signed `MANUAL_ADJUST` movement. The row therefore
 * saves what the shelf actually holds, which is the number the person doing
 * the counting has in their hand.
 */
function MenuSection() {
  const menu = useMenu();
  const rows = stockRows(menu.data);

  return (
    <section data-testid="menu-section" className="flex flex-col gap-2">
      <SectionHeading>Menu &amp; stock</SectionHeading>
      {menu.isPending ? (
        <RowsSkeleton testId="menu-skeleton" rows={6} />
      ) : menu.isError ? (
        <Notice testId="menu-error">
          {errorNotice(menu.error, 'The menu could not be read.')}
        </Notice>
      ) : rows.length === 0 ? (
        <div data-testid="menu-empty" className="border-2 border-divider p-4 text-[13px] opacity-60">
          The menu is empty — add the first item on the right.
        </div>
      ) : (
        <table className="w-full border-collapse text-body tabular">
          <caption className="sr-only">Menu rows and their stock</caption>
          <thead>
            <tr>
              {['Item', 'Category', 'Price ৳', 'Stock', 'Reorder at', 'Action'].map(
                (header, index) => (
                  <th
                    key={header}
                    scope="col"
                    className={`type-label border-b-2 border-divider px-2 py-2 opacity-60 ${
                      index === 5 ? 'text-right' : 'text-left'
                    }`}
                  >
                    {header}
                  </th>
                ),
              )}
            </tr>
          </thead>
          <tbody>
            {rows.map((item) => (
              <MenuRow key={String(item.id ?? item.name)} item={item} />
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function MenuRow({ item }: { item: Item }) {
  const update = useUpdateItem();
  const remove = useDeleteItem();

  const stored = {
    price: String(item.price ?? 0),
    stock: String(item.stock ?? 0),
    reorderAt: String(item.reorderAt ?? 0),
  };
  const [draft, setDraft] = useState(stored);
  const [notice, setNotice] = useState<string | null>(null);

  const dirty =
    draft.price !== stored.price ||
    draft.stock !== stored.stock ||
    draft.reorderAt !== stored.reorderAt;

  const numbers = {
    price: parseAmount(draft.price),
    stock: parseAmount(draft.stock),
    reorderAt: parseAmount(draft.reorderAt),
  };
  const valid = Object.values(numbers).every((value) => value !== null);

  const save = () => {
    const id = item.id;
    if (typeof id !== 'number' || !valid) return;
    setNotice(null);
    update.mutate(
      {
        id,
        price: numbers.price as number,
        stock: numbers.stock as number,
        reorderAt: numbers.reorderAt as number,
      },
      { onError: (error) => setNotice(errorNotice(error, 'That row could not be saved.')) },
    );
  };

  const drop = () => {
    const id = item.id;
    if (typeof id !== 'number') return;
    setNotice(null);
    remove.mutate(
      { id },
      { onError: (error) => setNotice(errorNotice(error, 'That item could not be removed.')) },
    );
  };

  const cell = (field: keyof typeof draft, label: string) => (
    <input
      aria-label={`${label} — ${item.name ?? 'item'}`}
      inputMode="numeric"
      value={draft[field]}
      onChange={(event) => setDraft((current) => ({ ...current, [field]: event.target.value }))}
      className="min-h-8 w-full rounded-none border border-divider bg-surface px-2 py-1 text-body text-text caret-accent focus-visible:border-accent focus-visible:outline-2 focus-visible:outline-accent"
    />
  );

  return (
    <>
      <tr data-testid="menu-row" data-item={item.name}>
        <td className="border-b border-divider px-2 py-2">
          <span className="font-heading font-extrabold">{item.name}</span>
          {item.active === false ? (
            <span className="ml-2 type-label opacity-55">Off menu</span>
          ) : null}
        </td>
        <td className="border-b border-divider px-2 py-2 opacity-70">
          {itemCategoryLabel(item.category)}
        </td>
        <td className="w-[110px] border-b border-divider px-2 py-2">{cell('price', 'Price')}</td>
        <td className="w-[100px] border-b border-divider px-2 py-2">{cell('stock', 'Stock')}</td>
        <td className="w-[110px] border-b border-divider px-2 py-2">
          {cell('reorderAt', 'Reorder at')}
        </td>
        <td className="w-[150px] border-b border-divider px-2 py-2 text-right">
          <Button
            variant="secondary"
            size="sm"
            disabled={!dirty || !valid}
            loading={update.isPending}
            onClick={save}
          >
            Save
          </Button>
          <Button variant="ghost" size="sm" loading={remove.isPending} onClick={drop}>
            Remove
          </Button>
        </td>
      </tr>
      {notice ? (
        <tr>
          <td colSpan={6} className="border-b border-divider px-2 pb-2">
            <span role="alert" data-testid="menu-row-notice" className="text-[12px] text-accent-strong">
              {notice}
            </span>
          </td>
        </tr>
      ) : null}
    </>
  );
}

/* -------------------------------------------------------------- rail forms */

function AddStationForm() {
  const create = useCreateStation();
  const [name, setName] = useState('');
  const [consoleType, setConsoleType] = useState<ConsoleType>('PS5');
  const [touched, setTouched] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [added, setAdded] = useState<string | null>(null);

  const parsed = createStationSchema.safeParse({ name, consoleType });

  const submit = () => {
    setTouched(true);
    setNotice(null);
    setAdded(null);
    if (!parsed.success) return;
    create.mutate(parsed.data, {
      onSuccess: (station) => {
        setAdded(station.name ?? parsed.data.name);
        setName('');
      },
      onError: (error) => setNotice(errorNotice(error, 'That console could not be added.')),
    });
  };

  return (
    <section data-testid="add-station-form" className="flex flex-col gap-2.5">
      <p className="type-label opacity-55">Add a station</p>
      {notice ? <Notice testId="add-station-notice">{notice}</Notice> : null}
      {added ? <Saved testId="add-station-saved">{added} is on the floor.</Saved> : null}
      <FieldInput
        label="Name"
        placeholder="e.g. Titan II"
        value={name}
        error={touched && !parsed.success ? fieldError(parsed.error, 'name') : undefined}
        onChange={(event) => {
          setAdded(null);
          setName(event.target.value);
        }}
      />
      <div className="flex flex-col gap-1">
        <span className="text-[12px] opacity-70">Console</span>
        <ChipSelect
          label="Console"
          value={consoleType}
          onChange={setConsoleType}
          options={CONSOLE_TYPES.map((type) => ({ value: type, label: type }))}
        />
      </div>
      <Button variant="primary" size="lg" loading={create.isPending} onClick={submit}>
        Add station
      </Button>
    </section>
  );
}

function AddStaffForm() {
  const create = useCreateStaff();
  const [name, setName] = useState('');
  const [staffRole, setStaffRole] = useState<StaffRole | null>(null);
  const [pin, setPin] = useState('');
  const [touched, setTouched] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [added, setAdded] = useState<string | null>(null);

  const parsed = createStaffSchema.safeParse({
    name,
    role: staffRole ?? undefined,
    pin,
  });
  const errorFor = (field: string) =>
    touched && !parsed.success ? fieldError(parsed.error, field) : undefined;

  const submit = () => {
    setTouched(true);
    setNotice(null);
    setAdded(null);
    if (!parsed.success) return;
    create.mutate(parsed.data, {
      onSuccess: (person) => {
        setAdded(person.name ?? parsed.data.name);
        setName('');
        setPin('');
        setStaffRole(null);
        setTouched(false);
      },
      onError: (error) => setNotice(errorNotice(error, 'That person could not be added.')),
    });
  };

  return (
    <section data-testid="add-staff-form" className="flex flex-col gap-2.5">
      <p className="type-label opacity-55">Add staff</p>
      {notice ? <Notice testId="add-staff-notice">{notice}</Notice> : null}
      {added ? <Saved testId="add-staff-saved">{added} can sign in now.</Saved> : null}
      <FieldInput
        label="Full name"
        placeholder="e.g. Rakib Hossain"
        value={name}
        error={errorFor('name')}
        onChange={(event) => {
          setAdded(null);
          setName(event.target.value);
        }}
      />
      <div className="flex flex-col gap-1">
        <span className="text-[12px] opacity-70">Role</span>
        <ChipSelect
          label="Role"
          value={staffRole}
          onChange={(value) => {
            setAdded(null);
            setStaffRole(value);
          }}
          options={STAFF_ROLES.map((value) => ({ value, label: staffRoleLabel(value) }))}
        />
        {errorFor('role') ? (
          <p role="alert" className="text-[12px] text-accent-strong">
            {errorFor('role')}
          </p>
        ) : null}
      </div>
      <FieldInput
        label="4-digit PIN"
        placeholder="0000"
        inputMode="numeric"
        maxLength={4}
        value={pin}
        error={errorFor('pin')}
        onChange={(event) => {
          setAdded(null);
          setPin(event.target.value);
        }}
      />
      <Button variant="primary" size="lg" loading={create.isPending} onClick={submit}>
        Add staff
      </Button>
    </section>
  );
}

function AddItemForm() {
  const create = useCreateItem();
  const [name, setName] = useState('');
  const [category, setCategory] = useState<ItemCategory | null>(null);
  const [price, setPrice] = useState('');
  const [stock, setStock] = useState('');
  const [reorderAt, setReorderAt] = useState('');
  const [touched, setTouched] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [added, setAdded] = useState<string | null>(null);

  const parsed = createItemSchema.safeParse({
    name,
    category: category ?? undefined,
    price: parseAmount(price) ?? undefined,
    stock: parseAmount(stock) ?? 0,
    reorderAt: parseAmount(reorderAt) ?? 0,
  });
  const errorFor = (field: string) =>
    touched && !parsed.success ? fieldError(parsed.error, field) : undefined;

  const submit = () => {
    setTouched(true);
    setNotice(null);
    setAdded(null);
    if (!parsed.success) return;
    create.mutate(parsed.data, {
      onSuccess: (item) => {
        setAdded(item.name ?? parsed.data.name);
        setName('');
        setPrice('');
        setStock('');
        setReorderAt('');
        setCategory(null);
        setTouched(false);
      },
      onError: (error) => setNotice(errorNotice(error, 'That item could not be added.')),
    });
  };

  return (
    <section data-testid="add-item-form" className="flex flex-col gap-2.5">
      <p className="type-label opacity-55">Add a menu item</p>
      {notice ? <Notice testId="add-item-notice">{notice}</Notice> : null}
      {added ? <Saved testId="add-item-saved">{added} is on the menu.</Saved> : null}
      <FieldInput
        label="Name"
        placeholder="e.g. Chicken Roll"
        value={name}
        error={errorFor('name')}
        onChange={(event) => {
          setAdded(null);
          setName(event.target.value);
        }}
      />
      <div className="flex flex-col gap-1">
        <span className="text-[12px] opacity-70">Category</span>
        <ChipSelect
          label="Category"
          value={category}
          onChange={(value) => {
            setAdded(null);
            setCategory(value);
          }}
          options={ITEM_CATEGORIES.map((value) => ({
            value,
            label: ITEM_CATEGORY_LABELS[value],
          }))}
        />
        {errorFor('category') ? (
          <p role="alert" className="text-[12px] text-accent-strong">
            Pick a category.
          </p>
        ) : null}
      </div>
      <div className="grid grid-cols-3 gap-2">
        <FieldInput
          label="Price ৳"
          placeholder="0"
          inputMode="numeric"
          value={price}
          error={errorFor('price')}
          onChange={(event) => {
            setAdded(null);
            setPrice(event.target.value);
          }}
        />
        <FieldInput
          label="Stock"
          placeholder="0"
          inputMode="numeric"
          value={stock}
          error={errorFor('stock')}
          onChange={(event) => {
            setAdded(null);
            setStock(event.target.value);
          }}
        />
        <FieldInput
          label="Reorder"
          placeholder="0"
          inputMode="numeric"
          value={reorderAt}
          error={errorFor('reorderAt')}
          onChange={(event) => {
            setAdded(null);
            setReorderAt(event.target.value);
          }}
        />
      </div>
      <Button variant="primary" size="lg" loading={create.isPending} onClick={submit}>
        Add item
      </Button>
    </section>
  );
}

/* --------------------------------------------------------------- skeleton */

/** The loading state, shaped like the rows it becomes (design.md §1). */
function RowsSkeleton({ testId, rows = 4 }: { testId: string; rows?: number }) {
  return (
    <div data-testid={testId} aria-busy="true" className="flex flex-col">
      <div className="h-6 border-b-2 border-divider" />
      {Array.from({ length: rows }, (_, row) => (
        <div key={row} className="flex items-center gap-4 border-b border-divider px-2 py-2.5">
          <div className="h-3 w-40 bg-track" />
          <div className="h-3 w-20 bg-track" />
          <div className="ml-auto h-3 w-16 bg-track" />
        </div>
      ))}
    </div>
  );
}
