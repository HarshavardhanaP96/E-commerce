import swaggerJsdoc from 'swagger-jsdoc';

const options: swaggerJsdoc.Options = {
  definition: {
    openapi: '3.0.0',
    info: {
      title: 'E-Commerce API Documentation',
      version: '1.0.0',
      description: 'Comprehensive API documentation for the E-Commerce platform with Razorpay payment integration',
      contact: {
        name: 'API Support',
        email: 'support@ecommerce.com',
      },
      license: {
        name: 'MIT',
        url: 'https://opensource.org/licenses/MIT',
      },
    },
    servers: [
      {
        url: 'http://localhost:3000',
        description: 'Development server',
      },
      {
        url: 'https://api.ecommerce.com',
        description: 'Production server',
      },
    ],
    components: {
      securitySchemes: {
        bearerAuth: {
          type: 'http',
          scheme: 'bearer',
          bearerFormat: 'JWT',
          description: 'Enter JWT token obtained from login',
        },
      },
      schemas: {
        Error: {
          type: 'object',
          properties: {
            success: {
              type: 'boolean',
              example: false,
            },
            message: {
              type: 'string',
              example: 'Error message',
            },
          },
        },
        User: {
          type: 'object',
          properties: {
            _id: {
              type: 'string',
              example: '507f1f77bcf86cd799439011',
            },
            name: {
              type: 'string',
              example: 'John Doe',
            },
            email: {
              type: 'string',
              example: 'john@example.com',
            },
            photo: {
              type: 'string',
              example: 'https://example.com/photo.jpg',
            },
            role: {
              type: 'string',
              enum: ['admin', 'user'],
              example: 'user',
            },
            gender: {
              type: 'string',
              enum: ['male', 'female', 'other'],
              example: 'male',
            },
            dob: {
              type: 'string',
              format: 'date',
              example: '1990-01-01',
            },
          },
        },
        Product: {
          type: 'object',
          properties: {
            _id: {
              type: 'string',
              example: '507f1f77bcf86cd799439011',
            },
            name: {
              type: 'string',
              example: 'Laptop',
            },
            photo: {
              type: 'string',
              example: 'uploads/2024/01/15/laptop.jpg',
            },
            price: {
              type: 'number',
              example: 999.99,
            },
            stock: {
              type: 'number',
              example: 50,
            },
            category: {
              type: 'string',
              example: 'Electronics',
            },
          },
        },
        Order: {
          type: 'object',
          properties: {
            _id: {
              type: 'string',
              example: '507f1f77bcf86cd799439011',
            },
            shippingInfo: {
              type: 'object',
              properties: {
                address: { type: 'string' },
                city: { type: 'string' },
                state: { type: 'string' },
                country: { type: 'string' },
                pinCode: { type: 'number' },
              },
            },
            user: {
              type: 'string',
              example: '507f1f77bcf86cd799439011',
            },
            subtotal: {
              type: 'number',
              example: 1000,
            },
            tax: {
              type: 'number',
              example: 180,
            },
            shippingCharges: {
              type: 'number',
              example: 50,
            },
            discount: {
              type: 'number',
              example: 100,
            },
            total: {
              type: 'number',
              example: 1130,
            },
            status: {
              type: 'string',
              enum: ['Processing', 'Shipped', 'Delivered'],
              example: 'Processing',
            },
            orderItems: {
              type: 'array',
              items: {
                type: 'object',
                properties: {
                  name: { type: 'string' },
                  photo: { type: 'string' },
                  price: { type: 'number' },
                  quantity: { type: 'number' },
                  productId: { type: 'string' },
                },
              },
            },
          },
        },
        Coupon: {
          type: 'object',
          properties: {
            _id: {
              type: 'string',
              example: '507f1f77bcf86cd799439011',
            },
            coupon: {
              type: 'string',
              example: 'SAVE20',
            },
            amount: {
              type: 'number',
              example: 20,
            },
          },
        },
      },
    },
    tags: [
      {
        name: 'Users',
        description: 'User authentication and management',
      },
      {
        name: 'Products',
        description: 'Product management and browsing',
      },
      {
        name: 'Orders',
        description: 'Order processing and management',
      },
      {
        name: 'Payment',
        description: 'Payment processing with Razorpay and coupon management',
      },
      {
        name: 'Dashboard',
        description: 'Admin analytics and statistics',
      },
    ],
  },
  apis: ['./src/routes/*.ts', './src/controllers/*.ts'],
};

export const swaggerSpec = swaggerJsdoc(options);
