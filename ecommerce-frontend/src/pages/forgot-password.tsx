import { useState } from "react";
import toast from "react-hot-toast";
import { useResetPasswordMutation } from "../redux/api/userAPI";
import { MessageResponse } from "../types/api-types";
import { FetchBaseQueryError } from "@reduxjs/toolkit/query";
import { useNavigate } from "react-router-dom";

const ForgotPassword = () => {
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [resetPassword, { isLoading }] = useResetPasswordMutation();
    const navigate = useNavigate();

    const submitHandler = async (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        try {
            const res = await resetPassword({ email, password });
            if ("data" in res && res.data) {
                toast.success(res.data.message);
                navigate("/login");
            } else {
                const error = res.error as FetchBaseQueryError;
                const message = (error.data as MessageResponse).message;
                toast.error(message);
            }
        } catch (error) {
            toast.error("Something went wrong");
        }
    };

    return (
        <div className="login">
            <main>
                <h1 className="heading">Reset Password</h1>
                <form onSubmit={submitHandler}>
                    <div>
                        <label>Email Address</label>
                        <input
                            type="email"
                            placeholder="abc@gmail.com"
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                            required
                        />
                    </div>
                    <div>
                        <label>New Password</label>
                        <input
                            type="password"
                            placeholder="New Password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            required
                        />
                    </div>

                    <button type="submit" disabled={isLoading}>
                        {isLoading ? "Updating..." : "Update Password"}
                    </button>
                </form>
            </main>
        </div>
    );
};

export default ForgotPassword;
