// Empty by default: the SPA calls the API on the same origin (nginx proxies /api and /uploads; Vite proxies
// them in dev). Set VITE_SERVER only to point a local build at a remote API.
export const server: string = import.meta.env.VITE_SERVER ?? "";
