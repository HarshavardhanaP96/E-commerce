import { NextFunction, Request, Response } from "express";
import { User } from "../models/user.js";
import { TryCatch } from "../middlewares/error.js";
import ErrorHandler from "../utils/utility-class.js";
import { NewUserRequestBody } from "../types/types.js";
import bcrypt from "bcrypt";
import jwt from "jsonwebtoken";

const createToken = (id: string) => {
  return jwt.sign({ id }, process.env.JWT_SECRET || "secret", {
    expiresIn: "30d",
  });
};

/**
 * @swagger
 * /api/v1/user/register:
 *   post:
 *     summary: Register a new user
 *     tags: [Users]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required:
 *               - name
 *               - email
 *               - dob
 *               - photo
 *               - gender
 *               - password
 *             properties:
 *               name:
 *                 type: string
 *               email:
 *                 type: string
 *               dob:
 *                 type: string
 *                 format: date
 *               photo:
 *                 type: string
 *               gender:
 *                 type: string
 *                 enum: [male, female, other]
 *               password:
 *                 type: string
 *     responses:
 *       201:
 *         description: User registered successfully
 *         content:
 *           application/json:
 *             schema:
 *               type: object
 *               properties:
 *                 success:
 *                   type: boolean
 *                 message:
 *                   type: string
 *                 token:
 *                   type: string
 *                 user:
 *                   $ref: '#/components/schemas/User'
 *       400:
 *         description: Bad request
 */
export const register = TryCatch(
  async (
    req: Request<{}, {}, NewUserRequestBody>,
    res: Response,
    next: NextFunction
  ) => {
    const { name, email, dob, photo, gender, password } = req.body;

    let user = await User.findOne({ email });
    if (user) return next(new ErrorHandler("User already exists", 400));

    if (!name || !email || !dob || !photo || !gender || !password)
      return next(new ErrorHandler("Please Enter all the fields", 400));

    user = await User.create({
      name,
      email,
      dob: new Date(dob),
      photo,
      gender,
      password,
    });

    const token = createToken(user._id.toString());

    return res.status(201).json({
      success: true,
      message: `Welcome, ${user.name}`,
      token,
      user,
    });
  }
);

/**
 * @swagger
 * /api/v1/user/login:
 *   post:
 *     summary: Login user
 *     tags: [Users]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required:
 *               - email
 *               - password
 *             properties:
 *               email:
 *                 type: string
 *               password:
 *                 type: string
 *     responses:
 *       200:
 *         description: Login successful
 *         content:
 *           application/json:
 *             schema:
 *               type: object
 *               properties:
 *                 success:
 *                   type: boolean
 *                 message:
 *                   type: string
 *                 token:
 *                   type: string
 *                 user:
 *                   $ref: '#/components/schemas/User'
 *       401:
 *         description: Invalid credentials
 */
export const login = TryCatch(async (req, res, next) => {
  const { email, password } = req.body;

  if (!email || !password)
    return next(new ErrorHandler("Please provide email and password", 400));

  console.log("Login attempt:", { email });

  const user = await User.findOne({ email }).select("+password");

  if (!user) {
    console.log("User not found");
    return next(new ErrorHandler("Invalid Email or Password", 401));
  }

  const isMatch = await user.comparePassword(password);
  console.log("Password match:", isMatch);

  if (!isMatch)
    return next(new ErrorHandler("Invalid Email or Password", 401));

  const token = createToken(user._id.toString());

  return res.status(200).json({
    success: true,
    message: `Welcome back, ${user.name}`,
    token,
    user,
  });
});

/**
 * @swagger
 * /api/v1/user/me:
 *   get:
 *     summary: Get my profile
 *     tags: [Users]
 *     security:
 *       - bearerAuth: []
 *     responses:
 *       200:
 *         description: Profile retrieved successfully
 *         content:
 *           application/json:
 *             schema:
 *               type: object
 *               properties:
 *                 success:
 *                   type: boolean
 *                 user:
 *                   $ref: '#/components/schemas/User'
 *       401:
 *         description: Unauthorized
 */
export const getMyProfile = TryCatch(async (req, res, next) => {
  const user = (req as any).user;

  res.status(200).json({
    success: true,
    user,
  });
});

/**
 * @swagger
 * /api/v1/user/reset-password:
 *   post:
 *     summary: Reset user password
 *     tags: [Users]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required:
 *               - email
 *               - password
 *             properties:
 *               email:
 *                 type: string
 *               password:
 *                 type: string
 *     responses:
 *       200:
 *         description: Password updated successfully
 *       404:
 *         description: User not found
 */
export const resetPassword = TryCatch(async (req, res, next) => {
  const { email, password } = req.body;

  if (!email || !password)
    return next(new ErrorHandler("Please provide email and new password", 400));

  const user = await User.findOne({ email }).select("+password");

  if (!user) return next(new ErrorHandler("User not found", 404));

  user.password = password;
  await user.save();

  res.status(200).json({
    success: true,
    message: "Password Updated Successfully",
  });
});
