import { useRef } from "react";
import { LuRuler, LuX } from "react-icons/lu";
import { SizeChart } from "../types/types";

/** "Size guide" link that opens the product's measurement table in a modal dialog. */
const SizeGuide = ({ chart, productName }: { chart: SizeChart; productName: string }) => {
  const dialog = useRef<HTMLDialogElement>(null);

  return (
    <>
      <button type="button" className="size-guide__open" onClick={() => dialog.current?.showModal()}>
        <LuRuler /> Size guide
      </button>
      <dialog
        ref={dialog}
        className="size-guide"
        aria-labelledby="size-guide-title"
        // Clicking the backdrop (the dialog element itself) closes it.
        onClick={(e) => e.target === dialog.current && dialog.current?.close()}
      >
        <header>
          <h2 id="size-guide-title">Size guide · {productName}</h2>
          <button type="button" onClick={() => dialog.current?.close()} aria-label="Close size guide">
            <LuX />
          </button>
        </header>
        <div className="size-guide__table">
          <table>
            <thead>
              <tr>
                {chart.columns.map((c) => (
                  <th key={c} scope="col">{c}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {chart.rows.map((row, i) => (
                <tr key={i}>
                  {row.map((cell, j) =>
                    j === 0 ? <th key={j} scope="row">{cell}</th> : <td key={j}>{cell}</td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {chart.note && <p className="size-guide__note">{chart.note}</p>}
      </dialog>
    </>
  );
};

export default SizeGuide;
