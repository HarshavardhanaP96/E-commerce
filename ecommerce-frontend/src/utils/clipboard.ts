/**
 * Copies `text` to the clipboard. Falls back to a hidden text box and the old copy command where the Clipboard API
 * is missing (plain http, older browsers) or refused. Returns whether it worked.
 */
export const copyText = async (text: string): Promise<boolean> => {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const box = document.createElement("textarea");
    box.value = text;
    box.setAttribute("readonly", "");
    box.style.position = "fixed";
    box.style.opacity = "0";
    document.body.append(box);
    box.select();
    try {
      return document.execCommand("copy");
    } catch {
      return false;
    } finally {
      box.remove();
    }
  }
};
