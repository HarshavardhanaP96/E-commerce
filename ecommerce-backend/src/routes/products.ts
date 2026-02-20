import express from "express";

import { adminOnly, isAuthenticated } from "../middlewares/auth.js";
import {
  deleteProduct,
  getAdminProducts,
  getAllCategories,
  getAllProducts,
  getlatestProducts,
  getProductById,
  newProduct,
  updateProduct,
} from "../controllers/product.js";
import { singleUpload } from "../middlewares/multer.js";

const app = express();

app.post("/new", isAuthenticated, adminOnly, singleUpload, newProduct);

app.get("/all", getAllProducts); //with fillter or search

app.get("/latest", getlatestProducts);

app.get("/categories", getAllCategories);

app.get("/get-Admin-products", isAuthenticated, adminOnly, getAdminProducts);

app
  .route("/:id")
  .get(getProductById)
  .put(isAuthenticated, adminOnly, singleUpload, updateProduct)
  .delete(isAuthenticated, adminOnly, deleteProduct);

export default app;
