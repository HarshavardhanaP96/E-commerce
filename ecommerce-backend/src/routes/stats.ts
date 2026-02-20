import express from "express";

import { adminOnly, isAuthenticated } from "../middlewares/auth.js";
import {
  getBarCharts,
  getDashboardStats,
  getLineCharts,
  getPieCharts,
} from "../controllers/stats.js";

const app = express();

//route-/api/v1/dashboard/stats
//route-/api/v1/dashboard/stats
app.get("/stats", isAuthenticated, adminOnly, getDashboardStats);

app.get("/bar", isAuthenticated, adminOnly, getBarCharts);

app.get("/pie", isAuthenticated, adminOnly, getPieCharts);

app.get("/line", isAuthenticated, adminOnly, getLineCharts);

export default app;
