import { server } from "../redux/config";

export const transformImage = (url: string) => {
  if (!url) return "";
  if (url.startsWith("http://") || url.startsWith("https://")) return url;
  return `${server}/${url}`;
};
