# API Documentation with Swagger

## Overview
This project uses **Swagger/OpenAPI 3.0** for comprehensive API documentation. The interactive documentation is automatically generated and provides a user-friendly interface to explore and test all API endpoints.

## Accessing the Documentation

### Local Development
Once the server is running, access the Swagger UI at:
```
http://localhost:3000/api-docs
```

### Production
```
https://your-domain.com/api-docs
```

## Features

### Interactive API Explorer
- ✅ **Try it out**: Test endpoints directly from the browser
- ✅ **Request/Response Examples**: See sample data for all endpoints
- ✅ **Schema Validation**: View request and response schemas
- ✅ **Authentication**: Test protected endpoints with JWT tokens
- ✅ **Error Responses**: See all possible error codes and messages

## API Structure

### Base URL
```
http://localhost:3000/api/v1
```

### Available Tags

1. **Users** - User authentication and management
2. **Products** - Product catalog and management
3. **Orders** - Order processing and tracking
4. **Payment** - Razorpay integration and coupon management
5. **Dashboard** - Admin analytics and statistics

## Authentication

### JWT Bearer Token

Most endpoints require authentication. To test protected endpoints:

1. **Login/Register** to get a JWT token
2. Click the **"Authorize"** button in Swagger UI
3. Enter: `Bearer YOUR_JWT_TOKEN`
4. Click **"Authorize"**
5. Now you can test protected endpoints

### Example:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

## API Endpoints Overview

### Users (`/api/v1/user`)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| POST | `/register` | Register new user | No |
| POST | `/login` | Login user | No |
| GET | `/me` | Get current user | Yes |
| GET | `/all` | Get all users (Admin) | Yes |
| GET | `/:id` | Get user by ID | Yes |
| DELETE | `/:id` | Delete user (Admin) | Yes |

### Products (`/api/v1/product`)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/latest` | Get latest 5 products | No |
| GET | `/all` | Search products with filters | No |
| GET | `/categories` | Get all categories | No |
| GET | `/:id` | Get product by ID | No |
| POST | `/new` | Create product (Admin) | Yes |
| PUT | `/:id` | Update product (Admin) | Yes |
| DELETE | `/:id` | Delete product (Admin) | Yes |

### Orders (`/api/v1/order`)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| POST | `/new` | Create new order | Yes |
| GET | `/my` | Get user's orders | Yes |
| GET | `/all` | Get all orders (Admin) | Yes |
| GET | `/:id` | Get order by ID | Yes |
| PUT | `/:id` | Process order (Admin) | Yes |
| DELETE | `/:id` | Delete order (Admin) | Yes |

### Payment (`/api/v1/payment`)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| POST | `/create-order` | Create Razorpay order | No |
| POST | `/verify` | Verify payment signature | No |
| GET | `/discount` | Apply discount coupon | No |
| GET | `/coupon/all` | Get all coupons (Admin) | Yes |
| POST | `/coupon/new` | Create coupon (Admin) | Yes |
| DELETE | `/coupon/:id` | Delete coupon (Admin) | Yes |

### Dashboard (`/api/v1/dashboard`)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/stats` | Get dashboard stats (Admin) | Yes |
| GET | `/pie` | Get pie chart data (Admin) | Yes |
| GET | `/bar` | Get bar chart data (Admin) | Yes |
| GET | `/line` | Get line chart data (Admin) | Yes |

## Request/Response Examples

### Create Razorpay Order

**Request:**
```http
POST /api/v1/payment/create-order
Content-Type: application/json

{
  "amount": 1000
}
```

**Response:**
```json
{
  "success": true,
  "order": {
    "id": "order_xxxxxxxxxxxxx",
    "amount": 100000,
    "currency": "INR",
    "receipt": "receipt_1234567890"
  },
  "key": "rzp_test_xxxxxxxxxxxxx"
}
```

### Search Products

**Request:**
```http
GET /api/v1/product/all?search=laptop&category=electronics&price=50000&page=1
```

**Response:**
```json
{
  "success": true,
  "products": [
    {
      "_id": "507f1f77bcf86cd799439011",
      "name": "Gaming Laptop",
      "price": 45000,
      "stock": 10,
      "category": "electronics",
      "photo": "uploads/2024/01/15/laptop.jpg"
    }
  ],
  "totalPage": 5
}
```

### Create Order

**Request:**
```http
POST /api/v1/order/new
Content-Type: application/json
Authorization: Bearer YOUR_JWT_TOKEN

{
  "shippingInfo": {
    "address": "123 Main St",
    "city": "Mumbai",
    "state": "Maharashtra",
    "country": "India",
    "pinCode": 400001
  },
  "orderItems": [
    {
      "name": "Laptop",
      "photo": "uploads/laptop.jpg",
      "price": 45000,
      "quantity": 1,
      "productId": "507f1f77bcf86cd799439011"
    }
  ],
  "user": "507f1f77bcf86cd799439012",
  "subtotal": 45000,
  "tax": 8100,
  "shippingCharges": 100,
  "discount": 500,
  "total": 52700
}
```

## Error Responses

All errors follow this format:

```json
{
  "success": false,
  "message": "Error description"
}
```

### Common HTTP Status Codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 201 | Created |
| 400 | Bad Request |
| 401 | Unauthorized |
| 403 | Forbidden |
| 404 | Not Found |
| 500 | Internal Server Error |

## Schemas

### User Schema
```json
{
  "_id": "string",
  "name": "string",
  "email": "string",
  "photo": "string",
  "role": "admin | user",
  "gender": "male | female | other",
  "dob": "date"
}
```

### Product Schema
```json
{
  "_id": "string",
  "name": "string",
  "photo": "string",
  "price": "number",
  "stock": "number",
  "category": "string"
}
```

### Order Schema
```json
{
  "_id": "string",
  "shippingInfo": {
    "address": "string",
    "city": "string",
    "state": "string",
    "country": "string",
    "pinCode": "number"
  },
  "user": "string",
  "subtotal": "number",
  "tax": "number",
  "shippingCharges": "number",
  "discount": "number",
  "total": "number",
  "status": "Processing | Shipped | Delivered",
  "orderItems": [
    {
      "name": "string",
      "photo": "string",
      "price": "number",
      "quantity": "number",
      "productId": "string"
    }
  ]
}
```

## Testing with Swagger UI

### Step-by-Step Guide:

1. **Start the server**
   ```bash
   cd ecommerce-backend
   npm run dev
   ```

2. **Open Swagger UI**
   - Navigate to `http://localhost:3000/api-docs`

3. **Test Public Endpoints**
   - Try `/api/v1/product/latest`
   - Click "Try it out"
   - Click "Execute"
   - View response

4. **Test Protected Endpoints**
   - First, register/login to get JWT token
   - Click "Authorize" button (top right)
   - Enter: `Bearer YOUR_TOKEN`
   - Now test protected endpoints

5. **Test Payment Flow**
   - Create order: `/api/v1/payment/create-order`
   - Use test amount: `1000`
   - Copy the order ID
   - Use in frontend Razorpay integration

## Customization

### Adding New Endpoints

Add Swagger documentation using JSDoc comments:

```typescript
/**
 * @swagger
 * /api/v1/your-endpoint:
 *   post:
 *     summary: Brief description
 *     tags: [TagName]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             properties:
 *               field1:
 *                 type: string
 *     responses:
 *       200:
 *         description: Success response
 */
export const yourController = TryCatch(async (req, res, next) => {
  // Your code
});
```

### Updating Swagger Config

Edit `src/config/swagger.ts` to:
- Add new schemas
- Update server URLs
- Add more tags
- Customize UI theme

## Export Options

### Download API Specification

1. Visit `/api-docs`
2. The OpenAPI spec is available at the top
3. Can be exported as JSON/YAML
4. Use with Postman, Insomnia, or other tools

### Generate Client SDKs

Use the OpenAPI spec to generate client libraries:

```bash
# Install OpenAPI Generator
npm install @openapitools/openapi-generator-cli -g

# Generate TypeScript client
openapi-generator-cli generate \
  -i http://localhost:3000/api-docs-json \
  -g typescript-axios \
  -o ./generated-client
```

## Best Practices

1. **Keep Documentation Updated**: Update Swagger comments when changing APIs
2. **Use Examples**: Provide realistic example data
3. **Document Errors**: List all possible error responses
4. **Security**: Never expose sensitive data in examples
5. **Versioning**: Update version number in swagger config

## Troubleshooting

### Swagger UI Not Loading
- Check if server is running
- Verify `/api-docs` route is accessible
- Check browser console for errors

### Endpoints Not Showing
- Ensure JSDoc comments are correct
- Check `apis` path in `swagger.ts`
- Restart server after changes

### Authentication Not Working
- Verify JWT token format
- Check token expiry
- Ensure "Bearer " prefix is included

## Resources

- **Swagger Official Docs**: https://swagger.io/docs/
- **OpenAPI Specification**: https://spec.openapis.org/oas/v3.0.0
- **Swagger UI**: https://github.com/swagger-api/swagger-ui
- **JSDoc to Swagger**: https://github.com/Surnet/swagger-jsdoc

---

**Happy API Testing! 🚀**
