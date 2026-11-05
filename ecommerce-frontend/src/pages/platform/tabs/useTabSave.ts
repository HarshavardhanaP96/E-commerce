import { useState } from "react";
import toast from "react-hot-toast";
import { errorMessage } from "../../../redux/api/baseQuery";
import { useUpdateTenantMutation } from "../../../redux/api/platformAPI";
import { fieldOf } from "../onboarding";

/** One PUT with only this tab's group. A `field: reason` error is kept for the form; anything else is a toast. */
export const useTabSave = (id: string) => {
  const [update, { isLoading }] = useUpdateTenantMutation();
  const [error, setError] = useState<string | null>(null);

  const save = async (body: Record<string, unknown>) => {
    setError(null);
    const res = await update({ id, body });
    if ("error" in res) {
      const message = errorMessage(res.error);
      if (fieldOf(message)) setError(message);
      else toast.error(message);
      return false;
    }
    toast.success("Saved");
    return true;
  };

  return { save, saving: isLoading, error };
};
