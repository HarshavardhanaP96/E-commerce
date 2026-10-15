import { ChangeEvent, useState } from "react";
import toast from "react-hot-toast";
import { useLoginMutation, useRegisterMutation } from "../redux/api/userAPI";
import { errorMessage } from "../redux/api/baseQuery";
import { useDispatch } from "react-redux";
import { userLoggedIn } from "../redux/reducer/userReducer";
import { useNavigate } from "react-router-dom";

const Login = () => {
  const [isRegister, setIsRegister] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [gender, setGender] = useState("");
  const [date, setDate] = useState("");
  const [photoPrev, setPhotoPrev] = useState("");
  const [photo, setPhoto] = useState<File>();

  const [login] = useLoginMutation();
  const [register] = useRegisterMutation();
  const dispatch = useDispatch();
  const navigate = useNavigate();

  const changeImageHandler = (e: ChangeEvent<HTMLInputElement>) => {
    const file: File | undefined = e.target.files?.[0];

    const reader: FileReader = new FileReader();

    if (file) {
      reader.readAsDataURL(file);
      reader.onloadend = () => {
        if (typeof reader.result === "string") {
          setPhotoPrev(reader.result);
          setPhoto(file);
        }
      };
    }
  };

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const res = await login({ email, password });

      if ("data" in res && res.data) {
        dispatch(userLoggedIn({ user: res.data.user, token: res.data.token }));
        toast.success(res.data.message);
        navigate("/");
      } else {
        toast.error(errorMessage(res.error));
      }
    } catch (error) {
      toast.error("Login Failed");
    }
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const formData = new FormData();
      formData.set("name", name);
      formData.set("email", email);
      formData.set("password", password);
      formData.set("gender", gender);
      formData.set("dob", date);
      if (photo) formData.set("photo", photo);

      const res = await register(formData);

      if ("data" in res && res.data) {
        dispatch(userLoggedIn({ user: res.data.user, token: res.data.token }));
        toast.success(res.data.message);
        navigate("/");
      } else {
        toast.error(errorMessage(res.error));
      }
    } catch (error) {
      toast.error("Registration Failed");
    }
  };

  return (
    <div className="login">
      <main>
        <h1 className="heading">{isRegister ? "Register" : "Login"}</h1>

        <form onSubmit={isRegister ? handleRegister : handleLogin}>
          {isRegister && (
            <>
              <div>
                <label>Name</label>
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
              </div>
              <div>
                <label>Gender</label>
                <select value={gender} onChange={(e) => setGender(e.target.value)} required>
                  <option value="">Select Gender</option>
                  <option value="male">Male</option>
                  <option value="female">Female</option>
                </select>
              </div>
              <div>
                <label>Date Of Birth</label>
                <input
                  type="date"
                  max={new Date().toISOString().slice(0, 10)}
                  value={date}
                  onChange={(e) => setDate(e.target.value)}
                  required
                />
              </div>
              <div>
                <label>Photo (optional)</label>
                <input
                  type="file"
                  accept="image/jpeg,image/png,image/webp,image/gif"
                  onChange={changeImageHandler}
                />
              </div>
              {photoPrev && <img src={photoPrev} alt="User" style={{width: 50, height: 50, borderRadius: "50%"}} />}
            </>
          )}

          <div>
            <label>Email Address</label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              placeholder="Enter your email"
            />
          </div>

          <div>
            <label>Password</label>
            <input
              type="password"
              minLength={isRegister ? 8 : undefined}
              maxLength={72}
              autoComplete={isRegister ? "new-password" : "current-password"}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              placeholder="Enter your password"
            />
          </div>

          {!isRegister && (
             <span 
              onClick={() => navigate("/forgot-password")}
              style={{ 
                cursor: "pointer", 
                color: "#6366f1", 
                fontSize: "0.85rem",
                alignSelf: "flex-end",
                marginTop: "-0.5rem",
                fontWeight: 500
              }}
             >
               Forgot Password?
             </span>
          )}

          <button type="submit">
            {isRegister ? "Sign Up" : "Sign In"}
          </button>
        </form>

        <p>
          {isRegister ? "Already have an account?" : "Don't have an account?"}{" "}
          <span
            onClick={() => setIsRegister(!isRegister)}
            style={{ cursor: "pointer", color: "blue", textDecoration: "underline" }}
          >
            {isRegister ? "Login" : "Register"}
          </span>
        </p>
      </main>
    </div>
  );
};

export default Login;
