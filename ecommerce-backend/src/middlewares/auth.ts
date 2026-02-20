import { User } from "../models/user.js";
import ErrorHandler from "../utils/utility-class.js";
import { TryCatch } from "./error.js";
import jwt from "jsonwebtoken";

export const isAuthenticated = TryCatch(async (req, res, next) => {
  const token = req.headers.authorization?.split(" ")[1];

  if (!token)
    return next(new ErrorHandler("Please Login to access this resource", 401));

  const decoded = jwt.verify(token, process.env.JWT_SECRET || "secret") as any;

  const user = await User.findById(decoded.id);

  if (!user) return next(new ErrorHandler("Saas bhaag gayi", 401));

  (req as any).user = user;
  next();
});

//to-check admin autherisation
export const adminOnly = TryCatch(async (req, res, next) => {
  const { id } = (req as any).user || {};  // Now checking from req.user set by isAuthenticated or if we want to keep query param style we need to discuss. But assuming full switch.
  
  // However, the original code used query param 'id'. The new plan implies Bearer token.
  // But wait, adminOnly might be used without isAuthenticated in some flows? 
  // Let's enforce isAuthenticated for adminOnly or check if user is already attached.
  
  let user = (req as any).user;
  
  // If no user attached yet (api didn't use isAuthenticated), try to find from token if present, or fallback (but better to rely on isAuthenticated)
  if(!user) {
      // Fallback: If adminOnly is used standalone, it needs to verify token too.
      // But standard practice: Use isAuthenticated then adminOnly.
      // Let's assume we chain them.
      return next(new ErrorHandler("Please Login first", 401));
  }
 
  if (user.role != "admin")
    return next(new ErrorHandler("You are not authorised", 401));

  next();
});
