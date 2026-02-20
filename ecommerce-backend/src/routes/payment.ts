import express from "express";

import { adminOnly } from "../middlewares/auth.js";
import {
  applyDiscount,
  createRazorpayOrder,
  verifyPayment,
  deleteCoupon,
  getAllCoupon,
  newCoupon,
} from "../controllers/payment.js";

const app = express.Router();

// Razorpay payment routes
app.post("/create-order", createRazorpayOrder);
app.post("/verify", verifyPayment);

// Coupon routes
app.get("/discount", applyDiscount);
app.get("/coupon/all", adminOnly, getAllCoupon);
app.post("/coupon/new", adminOnly, newCoupon);
app.delete("/coupon/:id", adminOnly, deleteCoupon);

export default app;
