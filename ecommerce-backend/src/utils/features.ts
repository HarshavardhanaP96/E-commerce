import mongoose, { Document } from "mongoose";
import { CacheInvalidatorProps, orderItemType } from "../types/types.js";
import { myCache } from "../app.js";
import { Product } from "../models/product.js";
import { Order } from "../models/order.js";

export const connectDB = (uri: string) => {
  mongoose
    .connect(uri, { dbName: "Ecommerce_2024" })
    .then((c) => console.log(`DB connected to ${c.connection.host}`))
    .catch((e) => console.log("DB Connection Error:", e.message));
};

export const cacheInvalidator = ({
  product,
  order,
  admin,
  userId,
  orderId,
  productId,
}: CacheInvalidatorProps) => {
  if (product) {
    const productKeys: string[] = [
      "latest-products",
      "categories",
      "all-products",
    ];

    if (typeof productId === "string") {
      productKeys.push(`product-${productId}`);
    }

    if (typeof productId === "object") {
      productId.forEach((i) => productKeys.push(`product-${i}`));
    }

    myCache.del(productKeys);
  }

  if (order) {
    const ordersKeys: string[] = [
      "all-orders",
      `my-orders-${userId}`,
      `order-${orderId}`,
    ];

    myCache.del(ordersKeys);
  }

  if (admin) {
    myCache.del([
      "admin-stats",
      "admin-pie-charts",
      "admin-bar-charts",
      "admin-line-charts",
    ]);
  }
};

export const reduceStock = (orderItems: orderItemType[]) => {
  orderItems.forEach(async (orderItem) => {
    const product = await Product.findById(orderItem.productId);
    if (!product) {
      throw new Error("Product Not Found");
    }

    product.stock -= orderItem.quantity;

    await product.save();
  });
};

export { calculatePercentage } from './calculations.js';

export const getInventories = async ({
  categories,
  productsCount,
}: {
  categories: string[];
  productsCount: number;
}) => {
  const categoriesCountPromise = categories.map((category) => {
    return Product.countDocuments({ category });
  });

  const categoriesCount = await Promise.all(categoriesCountPromise);
  const categoryCount: Record<string, number>[] = [];

  categories.forEach((category, i) => {
    categoryCount.push({
      [category]: Math.round((categoriesCount[i] / productsCount) * 100),
    });
  });

  return categoryCount;
};

interface MyDocument extends Document {
  createdAt: Date;
  discount?: number;
  total?: number;
}

type func1Props = {
  length: number;
  docArr: MyDocument[];
  today: Date;
  property?: "discount" | "total";
};

export const getChartData = ({
  length,
  docArr,
  today,
  property,
}: func1Props) => {
  const data: number[] = new Array(length).fill(0);

  docArr.forEach((i) => {
    const creationDate = i.createdAt;
    const monthDiff = (today.getMonth() - creationDate.getMonth() + 12) % 12; // to avoid negative

    if (monthDiff < length) {
      if (property) {
        data[length - 1 - monthDiff] += i[property]!;
      } else {
        data[length - 1 - monthDiff] += 1;
      }
    }
  });

  return data;
};
