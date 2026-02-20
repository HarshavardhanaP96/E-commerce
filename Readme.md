# E-Commerce Platform

A full-stack e-commerce application built with **React**, **TypeScript**, **Redux Toolkit**, **Node.js**, **Express**, and **MongoDB**. This platform includes product management, shopping cart functionality, **JWT-based authentication**, order processing, and admin analytics.

---

## 📁 Project Structure

```
6Pack/
├── ecommerce-backend/     # Node.js/Express backend
├── ecommerce-frontend/    # React/TypeScript frontend
└── Readme.md             # This file
```

---

## 🚀 Features

### Frontend
- **Product Browsing**: View latest products on home page
- **Advanced Search**: Filter products by name, category, price with pagination
- **Shopping Cart**: Add/remove items, adjust quantities, apply discount coupons
- **User Authentication**: JWT-based authentication with email/password
- **Responsive Design**: Mobile-friendly UI with SCSS styling
- **State Management**: Redux Toolkit with RTK Query for API calls

### Backend
- **RESTful API**: Express.js with TypeScript
- **Database**: MongoDB with Mongoose ODM
- **Caching**: Node-cache for improved performance
- **File Uploads**: Multer for product images with date-based folder organization
- **Payment Integration**: Stripe for payment processing
- **Admin Analytics**: Dashboard statistics, charts (pie, bar, line)

---

## 🛠️ Tech Stack

### Frontend
- **React** 18.2.0
- **TypeScript** 5.2.2
- **Redux Toolkit** (RTK Query)
- **React Router** 6.23.1
- **JWT** (Authentication via jsonwebtoken)
- **Chart.js** (Analytics visualization)
- **Sass** (Styling)
- **Vite** (Build tool)

### Backend
- **Node.js** with **Express** 4.19.2
- **TypeScript** 5.5.2
- **MongoDB** with **Mongoose** 8.4.4
- **Stripe** 16.8.0
- **Multer** (File uploads with date-based organization)
- **Node-cache** 5.1.2
- **Morgan** (Logging)
- **jsonwebtoken** (JWT authentication)
- **bcrypt** (Password hashing)

---

## 📦 Installation & Setup

### Prerequisites
- **Node.js** (v16 or higher)
- **MongoDB** (local or Atlas)
- **Stripe** account (for payments)

### Backend Setup

1. **Navigate to backend directory**
   ```bash
   cd ecommerce-backend
   ```

2. **Install dependencies**
   ```bash
   npm install
   ```

3. **Create `.env` file** (copy from `.env.example`)
   ```bash
   cp .env.example .env
   ```

4. **Configure environment variables** in `.env`:
   ```env
   MONGO_URI=your_mongodb_connection_string
   PORT=4000
   PRODUCT_PER_PAGE=10
   STRIPE_KEY=your_stripe_secret_key
   JWT_SECRET=your_super_secret_jwt_key
   ```

5. **Build TypeScript**
   ```bash
   npm run build
   ```

6. **Start development server**
   ```bash
   npm run dev
   ```
   Backend will run on `http://localhost:4000`

### Frontend Setup

1. **Navigate to frontend directory**
   ```bash
   cd ecommerce-frontend
   ```

2. **Install dependencies**
   ```bash
   npm install
   ```

3. **Create `.env` file** (copy from `.env.example`)
   ```bash
   cp .env.example .env
   ```

4. **Configure environment variables** in `.env`:
   ```env
   VITE_SERVER=http://localhost:4000
   ```

5. **Start development server**
   ```bash
   npm run dev
   ```
   Frontend will run on `http://localhost:5173`

---

## 🗂️ API Endpoints

### Products
- `GET /api/v1/product/latest` - Get latest 5 products
- `GET /api/v1/product/all` - Search products with filters
- `GET /api/v1/product/categories` - Get all categories
- `GET /api/v1/product/:id` - Get product by ID
- `POST /api/v1/product/new` - Create new product (Admin)
- `PUT /api/v1/product/:id` - Update product (Admin)
- `DELETE /api/v1/product/:id` - Delete product (Admin)

### Users (Authentication)
- `POST /api/v1/user/register` - Register new user
- `POST /api/v1/user/login` - Login user (returns JWT token)
- `GET /api/v1/user/me` - Get current user profile (requires auth)
- `GET /api/v1/user/all` - Get all users (Admin)
- `DELETE /api/v1/user/:id` - Delete user (Admin)

### Orders
- `POST /api/v1/order/new` - Create new order
- `GET /api/v1/order/my` - Get user's orders
- `GET /api/v1/order/all` - Get all orders (Admin)
- `GET /api/v1/order/:id` - Get order by ID
- `PUT /api/v1/order/:id` - Process order (Admin)
- `DELETE /api/v1/order/:id` - Delete order (Admin)

### Payment
- `POST /api/v1/payment/create` - Create payment intent
- `POST /api/v1/payment/coupon/new` - Create coupon (Admin)
- `GET /api/v1/payment/discount` - Apply discount coupon

### Stats (Admin)
- `GET /api/v1/dashboard/stats` - Dashboard statistics
- `GET /api/v1/dashboard/pie` - Pie chart data
- `GET /api/v1/dashboard/bar` - Bar chart data
- `GET /api/v1/dashboard/line` - Line chart data

---

## 📂 Frontend Structure

```
src/
├── components/          # Reusable components (ProductCard, CartItems, etc.)
├── pages/              # Page components (Home, Search, Cart, Login, etc.)
│   ├── admin/          # Admin dashboard pages
│   ├── home.tsx
│   ├── search.tsx
│   ├── cart.tsx
│   └── login.tsx
├── redux/              # Redux store and slices
│   ├── api/            # RTK Query API slices
│   │   ├── userAPI.ts
│   │   └── productAPI.ts
│   ├── reducer/        # Redux reducers
│   │   ├── userReducer.ts
│   │   └── cartReducer.ts
│   └── store.ts        # Redux store configuration
├── types/              # TypeScript type definitions
│   ├── types.ts
│   └── api-types.ts
├── styles/             # SCSS stylesheets
└── App.tsx             # Main app component
```

## 📂 Backend Structure

```
src/
├── controllers/        # Request handlers
│   ├── product.ts
│   ├── user.ts
│   ├── order.ts
│   ├── payment.ts
│   └── stats.ts
├── models/            # Mongoose schemas
│   ├── product.ts
│   ├── user.ts
│   ├── order.ts
│   └── coupon.ts
├── routes/            # API routes
│   ├── products.ts
│   ├── user.ts
│   ├── order.ts
│   ├── payment.ts
│   └── stats.ts
├── middlewares/       # Custom middleware
│   ├── error.ts
│   ├── multer.ts
│   └── auth.ts
├── types/             # TypeScript types
│   └── types.ts
├── utils/             # Utility functions
│   ├── features.ts
│   └── utility-class.ts
└── app.ts             # Express app setup
```

---

## 🔑 Key Features Explained

### Redux State Management
- **User State**: Manages authentication state
- **Cart State**: Handles cart items, pricing calculations, shipping info
- **RTK Query**: Automatic caching, refetching, and optimistic updates

### Caching Strategy
Backend implements node-cache for:
- Latest products
- Product categories
- All products list
- Individual product details
- User orders
- Admin statistics

Cache is invalidated on:
- Product creation/update/delete
- Order creation/update
- User actions

### JWT Authentication
- Email/password login with bcrypt password hashing
- JWT tokens stored in localStorage
- Token automatically included in API requests
- Stores user profile (name, email, photo, gender, DOB)

### Image Storage
- Product images stored in date-based folders (`uploads/YYYY/MM/DD/`)
- Faster retrieval through organized folder structure
- Easy migration to cloud storage (S3, GCS) by updating base URL

---

## 🧪 Development Scripts

### Backend
```bash
npm run dev      # Start development server with nodemon
npm run build    # Compile TypeScript
npm run watch    # Watch mode for TypeScript compilation
npm start        # Start production server
```

### Frontend
```bash
npm run dev      # Start Vite dev server
npm run build    # Build for production
npm run preview  # Preview production build
npm run lint     # Run ESLint
```

---

## 🔐 Environment Variables

### Backend (`.env`)
| Variable | Description |
|----------|-------------|
| `MONGO_URI` | MongoDB connection string |
| `PORT` | Server port (default: 4000) |
| `PRODUCT_PER_PAGE` | Pagination limit |
| `STRIPE_KEY` | Stripe secret key |
| `JWT_SECRET` | Secret key for JWT token signing |

### Frontend (`.env`)
| Variable | Description |
|----------|-------------|
| `VITE_SERVER` | Backend API URL |

---

## 📝 Notes for Developers

### Adding New Products
1. Products require an image upload
2. Categories are automatically extracted from existing products
3. Stock management is handled automatically on order placement

### Cart Functionality
- Cart state persists in Redux (not localStorage by default)
- Automatic price calculation including tax (18%) and shipping
- Discount coupons are validated on backend

### Admin Features
- Admin users have access to dashboard analytics
- Can manage products, orders, and users
- View statistics with interactive charts

### Code Quality
- TypeScript for type safety
- ESLint for code linting
- Consistent error handling with custom ErrorHandler class
- Async error handling with TryCatch wrapper

---

## 🐛 Common Issues & Solutions

### Backend won't start
- Ensure MongoDB is running
- Check `.env` file configuration
- Run `npm run build` before `npm run dev`

### Frontend API calls failing
- Verify `VITE_SERVER` in `.env` matches backend URL
- Check CORS configuration in backend
- Ensure backend is running

### Authentication not working
- Ensure JWT_SECRET is set in backend `.env`
- Check that token is being stored in localStorage
- Verify token is being sent in Authorization header

---

## 📄 License

This project is for educational purposes.

---

## 👥 Contributing

For bug reports or feature requests, please create an issue in the repository.
