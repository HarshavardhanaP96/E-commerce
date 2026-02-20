import express from "express";
import {
  deleteUser,
  getAllUsers,
  getUser,
} from "../controllers/user.js";
import {
  register,
  login,
  getMyProfile,
  resetPassword,
} from "../controllers/auth.js";
import { adminOnly, isAuthenticated } from "../middlewares/auth.js";

const app = express();

//route-/api/v1/user/new -> /register
app.post("/register", register);
app.post("/login", login);
app.post("/reset-password", resetPassword);

//route-/api/v1/user/me
app.get("/me", isAuthenticated, getMyProfile);

//route-/api/v1/user/all
app.get("/all", isAuthenticated, adminOnly, getAllUsers);

//route-/api/v1/user/dynamicID
app.route("/:id").get(isAuthenticated, adminOnly, getUser).delete(isAuthenticated, adminOnly, deleteUser);

export default app;
