import express from "express";
import { adminOnly } from "../middlewares/auth.js";
import {
  allOrders,
  deleteOrder,
  getOrderById,
  myOrders,
  newOrder,
  processOrder,
} from "../controllers/order.js";

const app = express();

//route-/api/v1/order/new
app.post("/new", newOrder);

app.get("/my", myOrders);

app.get("/all", adminOnly, allOrders);

app
  .route("/:id")
  .get(getOrderById)
  .put(adminOnly, processOrder)
  .delete(adminOnly, deleteOrder);

export default app;
