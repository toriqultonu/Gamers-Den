/**
 * DataTable — docs/design.md §2 primitives row, with the BookingTable
 * behaviour: clickable rows, accent outline on the selected row, empty state.
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DataTable, type Column } from '@/components/ui';

type Booking = { id: string; who: string; amount: number };

const ROWS: Booking[] = [
  { id: 'b1', who: 'Rakib Hossain', amount: 480 },
  { id: 'b2', who: 'Tanvir Alam', amount: 240 },
];

const COLUMNS: Column<Booking>[] = [
  { key: 'who', header: 'Customer' },
  { key: 'amount', header: 'Paid', align: 'right', render: (row) => `৳${row.amount}` },
];

describe('DataTable', () => {
  it('renders headers and rows', () => {
    render(<DataTable columns={COLUMNS} rows={ROWS} rowKey={(row) => row.id} />);
    expect(screen.getByRole('columnheader', { name: 'Customer' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Paid' })).toBeInTheDocument();
    expect(screen.getByText('Rakib Hossain')).toBeInTheDocument();
    expect(screen.getByText('৳480')).toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(3); // header + 2
  });

  it('renders numbers tabular', () => {
    render(<DataTable columns={COLUMNS} rows={ROWS} rowKey={(row) => row.id} />);
    expect(screen.getByRole('table')).toHaveClass('tabular');
  });

  it('draws the accent outline on the selected row', () => {
    render(
      <DataTable
        columns={COLUMNS}
        rows={ROWS}
        rowKey={(row) => row.id}
        selectedKey="b2"
        onSelect={() => {}}
      />,
    );
    const [, first, second] = screen.getAllByRole('row');
    expect(second).toHaveAttribute('data-state', 'selected');
    expect(second).toHaveClass('outline-2', 'outline-accent');
    expect(first).not.toHaveAttribute('data-state');
  });

  it('picks a row by click and by keyboard', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(
      <DataTable columns={COLUMNS} rows={ROWS} rowKey={(row) => row.id} onSelect={onSelect} />,
    );
    await user.click(screen.getByText('Rakib Hossain'));
    expect(onSelect).toHaveBeenCalledWith(ROWS[0]);

    // the click left focus on the first row; Tab walks to the next one
    await user.tab();
    const [, , second] = screen.getAllByRole('row');
    expect(second).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(onSelect).toHaveBeenLastCalledWith(ROWS[1]);
  });

  it('renders the empty state instead of an empty grid', () => {
    render(
      <DataTable
        columns={COLUMNS}
        rows={[]}
        rowKey={(row) => row.id}
        empty="No past bookings yet."
      />,
    );
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getByTestId('data-table-empty')).toHaveTextContent('No past bookings yet.');
  });
});
