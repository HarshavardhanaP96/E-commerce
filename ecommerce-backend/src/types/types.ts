import { Request, Response, NextFunction } from "express";

//dob can't be defined in user model
export interface NewUserRequestBody {
  name: string;
  email: string;
  photo: string;
  gender: string;
  password?: string;
  dob: Date;
}

export interface NewProductRequestBody {
  name: string;
  category: string;
  price: number;
  stock: number;
}

export type controllerType = (
  req: Request, //any to get params in product id
  res: Response,
  next: NextFunction
) => Promise<void | Response<any, Record<string, any>>>;

export type SearchRequestQuery = {
  search?: string;
  price?: string;
  category?: string;
  page?: string;
  sort?: string;
};

export interface BaseQuery {
  name?: {
    $regex: string;
    $options: string;
  };
  price?: {
    $lte: number;
  };
  category?: string;
}

export type CacheInvalidatorProps = {
  product?: boolean;
  admin?: boolean;
  order?: boolean;
  userId?: string; //to handle the access
  orderId?: string; //to handle the order exitence
  productId?: string | string[]; //to handle the product exitence
};

export type orderItemType = {
  name: string;
  photo: string;
  price: number;
  quantity: number;
  productId: string;
};

export type shippingInfoType = {
  address: string;
  city: string;
  state: string;
  county: string;
  pinCode: number;
};

export interface NewOrderRequestBody {
  shippingInfo: shippingInfoType;
  user: string;
  subtotal: number;
  tax: number;
  shippingCharges: number;
  discount: number;
  total: number;
  orderItems: orderItemType[];
}
