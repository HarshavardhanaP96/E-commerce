import { TryCatch } from "../middlewares/error.js";
import { Coupon } from "../models/coupon.js";
import ErrorHandler from "../utils/utility-class.js";
import { razorpay } from "../app.js";
import crypto from "crypto";

/**
 * @swagger
 * /api/v1/payment/create-order:
 *   post:
 *     summary: Create Razorpay order
 *     tags: [Payment]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required:
 *               - amount
 *             properties:
 *               amount:
 *                 type: number
 *                 description: Amount in INR (will be converted to paise)
 *                 example: 1000
 *     responses:
 *       201:
 *         description: Order created successfully
 *         content:
 *           application/json:
 *             schema:
 *               type: object
 *               properties:
 *                 success:
 *                   type: boolean
 *                   example: true
 *                 order:
 *                   type: object
 *                   properties:
 *                     id:
 *                       type: string
 *                     amount:
 *                       type: number
 *                     currency:
 *                       type: string
 *                     receipt:
 *                       type: string
 *       400:
 *         description: Bad request
 */
export const createRazorpayOrder = TryCatch(async (req, res, next) => {
  const { amount } = req.body;

  if (!amount || amount <= 0) {
    return next(new ErrorHandler("Please provide a valid amount", 400));
  }

  // Razorpay expects amount in paise (smallest currency unit)
  const amountInPaise = Math.round(amount * 100);

  const options = {
    amount: amountInPaise,
    currency: "INR",
    receipt: `receipt_${Date.now()}`,
    payment_capture: 1, // Auto capture payment
  };

  const order = await razorpay.orders.create(options);

  return res.status(201).json({
    success: true,
    order: {
      id: order.id,
      amount: order.amount,
      currency: order.currency,
      receipt: order.receipt,
    },
    key: process.env.RAZORPAY_KEY_ID, // Send key to frontend
  });
});

/**
 * @swagger
 * /api/v1/payment/verify:
 *   post:
 *     summary: Verify Razorpay payment signature
 *     tags: [Payment]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required:
 *               - razorpay_order_id
 *               - razorpay_payment_id
 *               - razorpay_signature
 *             properties:
 *               razorpay_order_id:
 *                 type: string
 *               razorpay_payment_id:
 *                 type: string
 *               razorpay_signature:
 *                 type: string
 *     responses:
 *       200:
 *         description: Payment verified successfully
 *       400:
 *         description: Invalid signature
 */
export const verifyPayment = TryCatch(async (req, res, next) => {
  const { razorpay_order_id, razorpay_payment_id, razorpay_signature } =
    req.body;

  if (!razorpay_order_id || !razorpay_payment_id || !razorpay_signature) {
    return next(new ErrorHandler("Missing payment details", 400));
  }

  // Create signature for verification
  const body = razorpay_order_id + "|" + razorpay_payment_id;
  const expectedSignature = crypto
    .createHmac("sha256", process.env.RAZORPAY_KEY_SECRET || "")
    .update(body.toString())
    .digest("hex");

  const isAuthentic = expectedSignature === razorpay_signature;

  if (!isAuthentic) {
    return next(new ErrorHandler("Payment verification failed", 400));
  }

  return res.status(200).json({
    success: true,
    message: "Payment verified successfully",
  });
});

/**
 * @swagger
 * /api/v1/payment/coupon/new:
 *   post:
 *     summary: Create a new discount coupon (Admin only)
 *     tags: [Payment]
 *     security:
 *       - bearerAuth: []
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required:
 *               - coupon
 *               - amount
 *             properties:
 *               coupon:
 *                 type: string
 *                 example: SAVE20
 *               amount:
 *                 type: number
 *                 example: 20
 *     responses:
 *       201:
 *         description: Coupon created successfully
 *       400:
 *         description: Invalid input
 *       401:
 *         description: Unauthorized
 */
export const newCoupon = TryCatch(async (req, res, next) => {
  const { coupon, amount } = req.body;

  if (!coupon || !amount) {
    return next(new ErrorHandler("Please Enter both Amount and Coupon", 400));
  }

  await Coupon.create({ coupon, amount });

  return res.status(201).json({
    success: true,
    message: `Coupon ${coupon} Created Successfully`,
  });
});

/**
 * @swagger
 * /api/v1/payment/discount:
 *   get:
 *     summary: Apply discount coupon
 *     tags: [Payment]
 *     parameters:
 *       - in: query
 *         name: coupon
 *         required: true
 *         schema:
 *           type: string
 *         description: Coupon code to apply
 *         example: SAVE20
 *     responses:
 *       200:
 *         description: Discount applied successfully
 *         content:
 *           application/json:
 *             schema:
 *               type: object
 *               properties:
 *                 success:
 *                   type: boolean
 *                   example: true
 *                 discount:
 *                   type: number
 *                   example: 20
 *       400:
 *         description: Invalid coupon
 */
export const applyDiscount = TryCatch(async (req, res, next) => {
  const { coupon } = req.query;

  const discount = await Coupon.findOne({ coupon });

  if (!discount) {
    return next(new ErrorHandler("Invalid Coupon", 400));
  }

  return res.status(200).json({
    success: true,
    discount: discount.amount,
  });
});

/**
 * @swagger
 * /api/v1/payment/coupon/all:
 *   get:
 *     summary: Get all coupons (Admin only)
 *     tags: [Payment]
 *     security:
 *       - bearerAuth: []
 *     responses:
 *       200:
 *         description: List of all coupons
 *         content:
 *           application/json:
 *             schema:
 *               type: object
 *               properties:
 *                 success:
 *                   type: boolean
 *                   example: true
 *                 coupons:
 *                   type: array
 *                   items:
 *                     $ref: '#/components/schemas/Coupon'
 *       401:
 *         description: Unauthorized
 */
export const getAllCoupon = TryCatch(async (req, res, next) => {
  const coupons = await Coupon.find({});

  return res.status(200).json({
    success: true,
    coupons,
  });
});

/**
 * @swagger
 * /api/v1/payment/coupon/{id}:
 *   delete:
 *     summary: Delete a coupon (Admin only)
 *     tags: [Payment]
 *     security:
 *       - bearerAuth: []
 *     parameters:
 *       - in: path
 *         name: id
 *         required: true
 *         schema:
 *           type: string
 *         description: Coupon ID
 *     responses:
 *       200:
 *         description: Coupon deleted successfully
 *       400:
 *         description: Invalid coupon ID
 *       401:
 *         description: Unauthorized
 */
export const deleteCoupon = TryCatch(async (req, res, next) => {
  const { id } = req.params;

  const coupon = await Coupon.findByIdAndDelete(id);

  if (!coupon) return next(new ErrorHandler("Invalid Coupon", 400));

  return res.status(200).json({
    success: true,
    message: `Coupon Deleted Successfully`,
  });
});
