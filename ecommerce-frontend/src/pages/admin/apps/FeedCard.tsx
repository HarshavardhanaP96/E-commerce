import { useState } from "react";
import toast from "react-hot-toast";
import { LuAlertCircle } from "react-icons/lu";
import { Link } from "react-router-dom";
import StatusBadge from "../../../components/admin/StatusBadge";
import { errorMessage } from "../../../redux/api/baseQuery";
import { useRotateFeedMutation, useSetFeedMutation } from "../../../redux/api/feedAPI";
import { Feed } from "../../../redux/api/marketingAPI";
import { copyText } from "../../../utils/clipboard";

const HELP = "/admin/help/product-feed";
const ROTATE_CONFIRM =
  "Make a new feed address? The current one stops working straight away, so paste the new one into Google Merchant Center and Meta Commerce Manager.";

/** Admin → Marketing: one product feed address for Google Merchant Center and the Meta catalogue. */
const FeedCard = ({ feed }: { feed: Feed }) => {
  const [setFeed, { isLoading: switching }] = useSetFeedMutation();
  const [rotateFeed, { isLoading: rotating }] = useRotateFeedMutation();
  const [error, setError] = useState<string | null>(null);
  const busy = switching || rotating;

  const toggle = async (enabled: boolean) => {
    setError(null);
    const res = await setFeed(enabled);
    if ("error" in res) return setError(errorMessage(res.error));
    toast.success(enabled ? "Product feed is on" : "Product feed is off");
  };

  const rotate = async () => {
    if (!confirm(ROTATE_CONFIRM)) return;
    setError(null);
    const res = await rotateFeed();
    if ("error" in res) return setError(errorMessage(res.error));
    toast.success("New feed address ready");
  };

  const copy = async () => {
    if (feed.url && (await copyText(feed.url))) toast.success("Feed address copied");
    else toast.error("Couldn't copy. Select the address and copy it yourself.");
  };

  return (
    <section className="card form-grid marketing-card" aria-labelledby="marketing-feed">
      <div className="span-2 marketing-card__head">
        <h2 id="marketing-feed">Product feed</h2>
        <StatusBadge status={feed.enabled ? "ACTIVE" : "INACTIVE"} />
      </div>
      <p className="span-2 store-settings__hint">
        One address that Google Merchant Center and the Meta catalogue read your active products from, with their
        price, stock, picture and page. <Link to={HELP}>How do I set it up?</Link>
      </p>
      {error && (
        <p className="span-2 field-error" role="alert">
          <LuAlertCircle aria-hidden /> {error}
        </p>
      )}
      <label className="span-2 check">
        <input type="checkbox" checked={feed.enabled} disabled={busy} onChange={(e) => toggle(e.target.checked)} />
        Publish my products in a feed
      </label>
      {feed.enabled && feed.url && (
        <>
          <label className="span-2" htmlFor="feed-url">
            Feed address
            <input id="feed-url" value={feed.url} readOnly spellCheck={false} onFocus={(e) => e.target.select()} />
          </label>
          <div className="span-2 store-settings__actions">
            <button type="button" className="btn btn--primary" onClick={copy}>
              Copy
            </button>
            <button type="button" className="btn" disabled={busy} onClick={rotate}>
              {rotating ? "Making a new URL…" : "New URL"}
            </button>
          </div>
          <div className="span-2 store-settings__hint">
            <p>
              Google Merchant Center: open Products, add a product source from a file, choose Enter a link to your
              file, paste the address and set it to fetch daily.
            </p>
            <p>
              Meta Commerce Manager: open your catalogue, then Data sources, add items with a data feed, choose a
              scheduled feed and paste the address.
            </p>
          </div>
        </>
      )}
    </section>
  );
};

export default FeedCard;
