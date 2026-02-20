import {
  AiOutlineSortAscending,
  AiOutlineSortDescending,
  AiOutlineSearch,
} from "react-icons/ai";
import {
  Column,
  usePagination,
  useSortBy,
  useTable,
  useGlobalFilter,
} from "react-table";
import { useState, useEffect } from "react";

function TableHOC<T extends Object>(
  columns: Column<T>[],
  data: T[],
  containerClassname: string,
  heading: string,
  showPagination: boolean = false,
  extraToolbarContent?: React.ReactNode // Extra content like filters and buttons
) {
  return function HOC() {
    const options: any = {
      columns,
      data,
      initialState: {
        pageSize: 6,
      },
    };

    const [searchInput, setSearchInput] = useState("");

    const {
      getTableProps,
      getTableBodyProps,
      headerGroups,
      page,
      prepareRow,
      nextPage,
      pageCount,
      state: { pageIndex },
      previousPage,
      canNextPage,
      canPreviousPage,
      setGlobalFilter,
    } = useTable(options as any, useGlobalFilter, useSortBy, usePagination) as any;

    useEffect(() => {
        setGlobalFilter(searchInput);
    }, [searchInput, setGlobalFilter]);

    return (
      <div className={containerClassname} style={{
          backgroundColor: 'white',
          padding: '1rem',
          borderRadius: '8px',
          boxShadow: '0 2px 4px rgba(0,0,0,0.1)',
          height: '100%',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          position: 'relative'
      }}>
        {/* Toolbar: Search & Filters */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', marginBottom: '1rem' }}>
            <div style={{ position: 'relative', width: '300px' }}>
                <input 
                    value={searchInput}
                    onChange={(e) => setSearchInput(e.target.value)}
                    placeholder={`Search ${heading}...`}
                    style={{
                        padding: '0.5rem 1rem',
                        paddingLeft: '2.5rem',
                        borderRadius: '6px',
                        border: '1px solid #d1d5db',
                        width: '100%',
                        fontSize: '0.9rem',
                        outline: 'none'
                    }}
                />
                <AiOutlineSearch style={{ position: 'absolute', left: '0.8rem', top: '50%', transform: 'translateY(-50%)', color: '#9ca3af' }} />
            </div>
            
            {extraToolbarContent}
        </div>

        {/* Scrollable Table Container */}
        <div style={{ flex: 1, overflowY: 'auto', border: '1px solid #e5e7eb', borderRadius: '6px' }}>
            <table className="table" {...getTableProps()} style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
            <thead style={{ position: 'sticky', top: 0, backgroundColor: '#f9fafb', zIndex: 1, boxShadow: '0 1px 2px rgba(0,0,0,0.05)' }}>
                {headerGroups.map((headerGroup: any) => (
                <tr {...headerGroup.getHeaderGroupProps()}>
                    {headerGroup.headers.map((column: any) => (
                    <th {...column.getHeaderProps(column.getSortByToggleProps())} style={{
                        padding: '0.75rem 1rem',
                        textAlign: 'left',
                        fontSize: '0.75rem',
                        fontWeight: '700',
                        color: '#4b5563',
                        textTransform: 'uppercase',
                        letterSpacing: '0.05em',
                        borderBottom: '1px solid #e5e7eb',
                        whiteSpace: 'nowrap',
                        cursor: 'pointer',
                        userSelect: 'none',
                        width: column.id === 'photo' || column.id === 'avatar' ? '80px' : 'auto',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis'
                    }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                            {column.render("Header")}
                            {column.isSorted && (
                            <span>
                                {column.isSortedDesc ? <AiOutlineSortDescending /> : <AiOutlineSortAscending />}
                            </span>
                            )}
                        </div>
                    </th>
                    ))}
                </tr>
                ))}
            </thead>
            <tbody {...getTableBodyProps()}>
                {page.map((row: any) => {
                prepareRow(row);
                return (
                    <tr {...row.getRowProps()} style={{ borderBottom: '1px solid #f3f4f6', height: '48px' }}>
                    {row.cells.map((cell: any) => (
                        <td {...cell.getCellProps()} style={{
                            padding: '0.5rem 1rem',
                            fontSize: '0.875rem',
                            color: '#374151',
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            maxWidth: '0' // flex/grid trick for ellipsis, but table-layout: fixed does it with just overflowing
                        }} title={typeof cell.value === 'string' ? cell.value : ''}>
                            {cell.render("Cell")}
                        </td>
                    ))}
                    </tr>
                );
                })}
            </tbody>
            </table>
        </div>

        {/* Pagination & Count */}
        {showPagination && (
          <div className="table-pagination" style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginTop: '0.75rem',
              paddingTop: '0.5rem',
              borderTop: '1px solid #e5e7eb'
          }}>
            <div style={{ fontSize: '0.85rem', color: '#6b7280' }}>
               Showing {page.length} of {data.length} rows
            </div>
            
            <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                <button disabled={!canPreviousPage} onClick={previousPage} style={{
                    padding: '0.25rem 0.5rem',
                    border: '1px solid #d1d5db',
                    borderRadius: '4px',
                    backgroundColor: !canPreviousPage ? '#f3f4f6' : 'white',
                    color: !canPreviousPage ? '#9ca3af' : '#374151',
                    cursor: !canPreviousPage ? 'not-allowed' : 'pointer',
                    fontSize: '0.8rem'
                }}>
                Prev
                </button>
                <span style={{ fontSize: '0.8rem', color: '#4b5563' }}>Page <strong>{pageIndex + 1}</strong> of {pageCount}</span>
                <button disabled={!canNextPage} onClick={nextPage} style={{
                    padding: '0.25rem 0.5rem',
                    border: '1px solid #d1d5db',
                    borderRadius: '4px',
                    backgroundColor: !canNextPage ? '#f3f4f6' : 'white',
                    color: !canNextPage ? '#9ca3af' : '#374151',
                    cursor: !canNextPage ? 'not-allowed' : 'pointer',
                    fontSize: '0.8rem'
                }}>
                Next
                </button>
            </div>
          </div>
        )}
      </div>
    );
  };
}

export default TableHOC;
