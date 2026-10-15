import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";

// In dev, proxy API and uploaded images to the Spring Boot backend so the app stays same-origin
// (matching production, where nginx does the same).
const backend = process.env.VITE_BACKEND_URL ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": backend,
      "/uploads": backend,
    },
  },
});
