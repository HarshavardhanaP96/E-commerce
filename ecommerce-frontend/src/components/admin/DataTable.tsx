/* eslint-disable @typescript-eslint/no-explicit-any -- react-table v7 plugin hooks (sort/filter/pagination) are not reflected in its base typings */
import { ReactNode, useEffect, useState } from "react";
import { LuArrowDown, LuArrowUp, LuChevronLeft, LuChevronRight, LuSearch } from "react-icons/lu";
import { Column, useGlobalFilter, usePagination, useSortBy, useTable } from "react-table";

type DataTableProps<T extends object> = {
  columns: Column<T>[];
  data: T[];
  searchPlaceholder: string;
  /** Rendered inline, right after the search box. */
  filters?: ReactNode;
  /** Rendered at the right end of the toolbar (e.g. "Add product"). */
  actions?: ReactNode;
  isLoading?: boolean;
  emptyText?: string;
  /** Shown under the empty text, e.g. the page's "Add product" button. */
  emptyAction?: ReactNode;
  onRowClick?: (row: T) => void;
  /** When this value changes the table returns to its first page (e.g. server-side filters/paging feeding new data). */
  resetKey?: string;
  /** Server-side paging: `data` is already one page, and the footer drives the server's page and page size. */
  serverPaging?: {
    page: number;
    pageSize: number;
    total: number;
    onPageChange: (page: number) => void;
    onPageSizeChange: (size: number) => void;
  };
};

const PAGE_SIZES = [10, 25, 50];

/**
 * Admin table: toolbar (search + inline filters + actions), fixed column widths with ellipsis, and
 * pagination with page size at the bottom. The table body scrolls; the page does not.
 */
function DataTable<T extends object>({
  columns,
  data,
  searchPlaceholder,
  filters,
  actions,
  isLoading,
  emptyText = "No records found",
  emptyAction,
  onRowClick,
  resetKey,
  serverPaging,
}: DataTableProps<T>) {
  const [search, setSearch] = useState("");

  const {
    getTableProps,
    getTableBodyProps,
    headerGroups,
    page,
    prepareRow,
    nextPage,
    previousPage,
    canNextPage,
    canPreviousPage,
    pageCount,
    setPageSize,
    gotoPage,
    setGlobalFilter,
    rows,
    state: { pageIndex, pageSize },
  } = useTable<T>(
    { columns, data, autoResetPage: false, manualPagination: !!serverPaging, initialState: { pageSize: PAGE_SIZES[0] } } as never,
    useGlobalFilter,
    useSortBy,
    usePagination
  ) as any;

  useEffect(() => setGlobalFilter(search || undefined), [search, setGlobalFilter]);

  // gotoPage is a stable useCallback over the reducer dispatch; on page 0 it is a no-op (reducer returns the same state).
  useEffect(() => {
    gotoPage(0);
  }, [resetKey, gotoPage]);

  // With server paging `page` is every loaded row, and the footer counts against the server's total.
  const pager = serverPaging
    ? {
        index: serverPaging.page - 1,
        size: serverPaging.pageSize,
        count: Math.ceil(serverPaging.total / serverPaging.pageSize),
        total: serverPaging.total,
        shown: rows.length,
        canPrevious: serverPaging.page > 1,
        canNext: serverPaging.page * serverPaging.pageSize < serverPaging.total,
        previous: () => serverPaging.onPageChange(serverPaging.page - 1),
        next: () => serverPaging.onPageChange(serverPaging.page + 1),
        setSize: serverPaging.onPageSizeChange,
      }
    : {
        index: pageIndex,
        size: pageSize,
        count: pageCount,
        total: rows.length,
        shown: page.length,
        canPrevious: canPreviousPage,
        canNext: canNextPage,
        previous: previousPage,
        next: nextPage,
        setSize: setPageSize,
      };
  const from = pager.shown === 0 ? 0 : pager.index * pager.size + 1;
  const to = pager.index * pager.size + pager.shown;

  return (
    <section className="data-table">
      <div className="data-table__toolbar">
        <label className="data-table__search">
          <LuSearch aria-hidden />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={searchPlaceholder}
            aria-label={searchPlaceholder}
          />
        </label>
        {filters && <div className="data-table__filters">{filters}</div>}
        {actions && <div className="data-table__actions">{actions}</div>}
      </div>

      <div className="data-table__scroll">
        <table {...getTableProps()}>
          <thead>
            {headerGroups.map((group: any, gi: number) => (
              <tr {...group.getHeaderGroupProps()} key={gi}>
                {group.headers.map((column: any) => (
                  <th
                    {...column.getHeaderProps(column.getSortByToggleProps())}
                    key={column.id}
                    className={column.align === "right" ? "num" : undefined}
                    style={column.width === 150 ? { minWidth: 200 } : { minWidth: column.width, width: column.width <= 96 ? 1 : undefined }}
                  >
                    <span>
                      {column.render("Header")}
                      {column.isSorted &&
                        (column.isSortedDesc ? <LuArrowDown aria-label="sorted descending" /> : <LuArrowUp aria-label="sorted ascending" />)}
                    </span>
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody {...getTableBodyProps()}>
            {isLoading && (
              <tr>
                <td colSpan={columns.length} className="data-table__empty">Loading…</td>
              </tr>
            )}
            {!isLoading && page.length === 0 && (
              <tr>
                <td colSpan={columns.length} className="data-table__empty">
                  <p>{emptyText}</p>
                  {emptyAction}
                </td>
              </tr>
            )}
            {page.map((row: any) => {
              prepareRow(row);
              return (
                <tr
                  {...row.getRowProps()}
                  key={row.id}
                  className={onRowClick ? "clickable" : undefined}
                  onClick={onRowClick ? () => onRowClick(row.original) : undefined}
                  onKeyDown={onRowClick ? (e) => e.key === "Enter" && onRowClick(row.original) : undefined}
                  tabIndex={onRowClick ? 0 : undefined}
                >
                  {row.cells.map((cell: any) => (
                    <td
                      {...cell.getCellProps()}
                      key={cell.column.id}
                      className={cell.column.align === "right" ? "num" : undefined}
                      title={typeof cell.value === "string" || typeof cell.value === "number" ? String(cell.value) : undefined}
                    >
                      {cell.render("Cell")}
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <footer className="data-table__footer">
        <span>
          {from}–{to} of {pager.total}
        </span>
        <label>
          Rows per page
          <select value={pager.size} onChange={(e) => pager.setSize(Number(e.target.value))}>
            {PAGE_SIZES.map((size) => (
              <option key={size} value={size}>{size}</option>
            ))}
          </select>
        </label>
        <div className="data-table__pager">
          <button onClick={() => pager.previous()} disabled={!pager.canPrevious} aria-label="Previous page"><LuChevronLeft /></button>
          <span aria-live="polite">
            {pager.count === 0 ? 0 : pager.index + 1} / {pager.count}
          </span>
          <button onClick={() => pager.next()} disabled={!pager.canNext} aria-label="Next page"><LuChevronRight /></button>
        </div>
      </footer>
    </section>
  );
}

export default DataTable;
