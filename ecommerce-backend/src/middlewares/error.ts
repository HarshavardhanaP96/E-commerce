import { Request, Response, NextFunction } from "express";
import ErrorHandler from "../utils/utility-class.js";
import { controllerType } from "../types/types.js";

export const errorMiddleware = (
  err: ErrorHandler,
  req: Request,
  res: Response,
  next: NextFunction
) => {
  err.message ||= "Internal Server Error";
  err.statusCode ||= 500;

  if (err.name == "CastError") err.message = "Invalid ID"; //Handles invalid length of id necessary because this error will not reach the if block to handle in controllers

  return res.status(err.statusCode).json({
    success: false,
    message: err.message,
  });
};

// Wraper to avoid repetition
export const TryCatch =
  (func: controllerType) =>
  (req: Request, res: Response, next: NextFunction) => {
    return Promise.resolve(func(req, res, next).catch(next));
  };
