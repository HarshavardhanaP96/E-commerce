import { ChangeEvent, FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { LuArrowLeft, LuMapPin, LuPencil, LuTrash2 } from "react-icons/lu";
import { Link } from "react-router-dom";
import { errorMessage } from "../../redux/api/baseQuery";
import {
  useAddAddressMutation,
  useDeleteAddressMutation,
  useGetMyAddressesQuery,
  useUpdateAddressMutation,
} from "../../redux/api/userAPI";
import { SavedAddress, SavedAddressInput } from "../../types/types";

const empty: SavedAddressInput = {
  label: "",
  name: "",
  phone: "",
  line1: "",
  line2: "",
  city: "",
  state: "",
  country: "India",
  pinCode: "",
  isDefault: false,
};

const MAX_ADDRESSES = 10;

/** Saved delivery addresses: list, add, edit, delete, and choose the default. */
const Addresses = () => {
  const { data, isLoading } = useGetMyAddressesQuery();
  const [addAddress, { isLoading: adding }] = useAddAddressMutation();
  const [updateAddress, { isLoading: updating }] = useUpdateAddressMutation();
  const [deleteAddress] = useDeleteAddressMutation();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<SavedAddressInput>(empty);
  const addresses = data?.addresses ?? [];

  const change = (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const { name, value, type, checked } = e.target as HTMLInputElement;
    setForm((prev) => ({ ...prev, [name]: type === "checkbox" ? checked : value }));
  };

  const edit = (a: SavedAddress) => {
    const { id, ...rest } = a;
    setEditingId(id);
    setForm({ ...empty, ...rest, label: rest.label ?? "", line2: rest.line2 ?? "" });
  };

  const reset = () => {
    setEditingId(null);
    setForm(empty);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const res = editingId ? await updateAddress({ id: editingId, body: form }) : await addAddress(form);
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    reset();
  };

  const remove = async (id: string) => {
    if (!confirm("Delete this address?")) return;
    const res = await deleteAddress(id);
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    if (editingId === id) reset();
  };

  return (
    <div className="shipping address-book">
      <Link className="back-btn" to="/">
        <LuArrowLeft /> Back to shop
      </Link>
      <h1>Saved addresses</h1>
      <p>
        {addresses.length} of {MAX_ADDRESSES} saved. Your default address is pre-selected at checkout.
      </p>

      {isLoading ? null : addresses.length === 0 ? (
        <p className="address-book__empty">You have no saved addresses yet.</p>
      ) : (
        <ul className="address-book__list">
          {addresses.map((a) => (
            <li key={a.id}>
              <LuMapPin />
              <div>
                <strong>
                  {a.label || a.name} {a.isDefault && <span className="badge badge--delivered">Default</span>}
                </strong>
                <span>
                  {a.line1}
                  {a.line2 ? `, ${a.line2}` : ""}, {a.city}, {a.state} {a.pinCode}, {a.country}
                </span>
                <small>
                  {a.name} · {a.phone}
                </small>
              </div>
              <button type="button" aria-label="Edit address" onClick={() => edit(a)}>
                <LuPencil />
              </button>
              <button type="button" aria-label="Delete address" onClick={() => remove(a.id)}>
                <LuTrash2 />
              </button>
            </li>
          ))}
        </ul>
      )}

      {(editingId || addresses.length < MAX_ADDRESSES) && (
        <form onSubmit={submit}>
          <h2>{editingId ? "Edit address" : "Add an address"}</h2>
          <label>
            Label (optional)
            <input name="label" value={form.label} onChange={change} maxLength={30} placeholder="Home, Work…" />
          </label>
          <label>
            Full name
            <input name="name" value={form.name} onChange={change} maxLength={80} required autoComplete="name" />
          </label>
          <label>
            Phone
            <input name="phone" value={form.phone} onChange={change} required autoComplete="tel"
                   pattern="\+?[0-9]{10,13}" title="10-13 digits, optionally starting with +" />
          </label>
          <label className="span-2">
            Address
            <input name="line1" value={form.line1} onChange={change} maxLength={200} required
                   placeholder="House no., street, area" autoComplete="address-line1" />
          </label>
          <label className="span-2">
            Landmark / apartment (optional)
            <input name="line2" value={form.line2} onChange={change} maxLength={200} autoComplete="address-line2" />
          </label>
          <label>
            City
            <input name="city" value={form.city} onChange={change} maxLength={80} required />
          </label>
          <label>
            State
            <input name="state" value={form.state} onChange={change} maxLength={80} required />
          </label>
          <label>
            Country
            <select name="country" value={form.country} onChange={change} required>
              <option value="India">India</option>
            </select>
          </label>
          <label>
            Pin code
            <input name="pinCode" value={form.pinCode} onChange={change} required inputMode="numeric"
                   pattern="\d{4,10}" title="4-10 digits" />
          </label>
          <label className="span-2 address-book__default">
            <input type="checkbox" name="isDefault" checked={form.isDefault} onChange={change} /> Make this my
            default address
          </label>
          <button type="submit" className="span-2" disabled={adding || updating}>
            {editingId ? "Save changes" : "Save address"}
          </button>
          {editingId && (
            <button type="button" className="span-2 address-book__cancel" onClick={reset}>
              Cancel
            </button>
          )}
        </form>
      )}
    </div>
  );
};

export default Addresses;
