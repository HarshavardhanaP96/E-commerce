import mongoose from "mongoose";
import { User } from "./models/user.js";
import dotenv from "dotenv";

dotenv.config();

const mongoURI = process.env.MONGO_URI || "";

const seedDB = async () => {
  try {
    if (!mongoURI) {
        console.error("MONGO_URI is missing in env");
        process.exit(1);
    }
    await mongoose.connect(mongoURI, {
        dbName: "Ecommerce_2024",
    });
    console.log("Connected to DB");

    // Delete all users
    await User.deleteMany({});
    console.log("Deleted existing users");

    // Create Admin
    const admin = await User.create({
      name: "Harsha Admin",
      email: "admin@gmail.com",
      password: "adminpassword",
      role: "admin",
      gender: "male",
      dob: new Date("1996-01-01"),
      photo: "https://cdn-icons-png.flaticon.com/512/147/147142.png",
    });
    console.log("Admin Created -> Email: admin@gmail.com | Password: adminpassword");

    // Create User
    const user = await User.create({
      name: "Normal User",
      email: "user@gmail.com",
      password: "userpassword",
      role: "user",
      gender: "female",
      dob: new Date("2000-01-01"),
      photo: "https://cdn-icons-png.flaticon.com/512/147/147142.png",
    });
    console.log("User Created -> Email: user@gmail.com | Password: userpassword");

    console.log("Seeding Completed Successfully");
    process.exit(0);
  } catch (error) {
    console.error("Seeding Failed:", error);
    process.exit(1);
  }
};

seedDB();
