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
  onRowClick?: (row: T) => void;
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
  onRowClick,
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
    setGlobalFilter,
    rows,
    state: { pageIndex, pageSize },
  } = useTable<T>(
    { columns, data, autoResetPage: false, initialState: { pageSize: PAGE_SIZES[0] } } as never,
    useGlobalFilter,
    useSortBy,
    usePagination
  ) as any;

  useEffect(() => setGlobalFilter(search || undefined), [search, setGlobalFilter]);

  const from = rows.length === 0 ? 0 : pageIndex * pageSize + 1;
  const to = Math.min((pageIndex + 1) * pageSize, rows.length);

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
                <td colSpan={columns.length} className="data-table__empty">{emptyText}</td>
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
          {from}–{to} of {rows.length}
        </span>
        <label>
          Rows per page
          <select value={pageSize} onChange={(e) => setPageSize(Number(e.target.value))}>
            {PAGE_SIZES.map((size) => (
              <option key={size} value={size}>{size}</option>
            ))}
          </select>
        </label>
        <div className="data-table__pager">
          <button onClick={() => previousPage()} disabled={!canPreviousPage} aria-label="Previous page"><LuChevronLeft /></button>
          <span>
            {pageCount === 0 ? 0 : pageIndex + 1} / {pageCount}
          </span>
          <button onClick={() => nextPage()} disabled={!canNextPage} aria-label="Next page"><LuChevronRight /></button>
        </div>
      </footer>
    </section>
  );
}

export default DataTable;
