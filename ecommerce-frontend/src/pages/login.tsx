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
        <h1 className="heading">{isRegister ? "Create account" : "Welcome back"}</h1>
        <p className="login__sub">{isRegister ? "Join 6Pack in under a minute." : "Sign in to continue to 6Pack."}</p>

        <form onSubmit={isRegister ? handleRegister : handleLogin}>
          {isRegister && (
            <>
              <div>
                <label htmlFor="reg-name">Name</label>
                <input id="reg-name"
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
              </div>
              <div>
                <label htmlFor="reg-gender">Gender</label>
                <select id="reg-gender" value={gender} onChange={(e) => setGender(e.target.value)} required>
                  <option value="">Select gender</option>
                  <option value="male">Male</option>
                  <option value="female">Female</option>
                </select>
              </div>
              <div>
                <label htmlFor="reg-dob">Date of birth</label>
                <input id="reg-dob"
                  type="date"
                  max={new Date().toISOString().slice(0, 10)}
                  value={date}
                  onChange={(e) => setDate(e.target.value)}
                  required
                />
              </div>
              <div>
                <label htmlFor="reg-photo">Photo (optional)</label>
                <input id="reg-photo"
                  type="file"
                  accept="image/jpeg,image/png,image/webp,image/gif"
                  onChange={changeImageHandler}
                />
              </div>
              {photoPrev && <img src={photoPrev} alt="Profile preview" />}
            </>
          )}

          <div>
            <label htmlFor="login-email">Email address</label>
            <input id="login-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              placeholder="Enter your email"
            />
          </div>

          <div>
            <label htmlFor="login-password">Password</label>
            <input id="login-password"
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
             <button
              type="button"
              className="text-link text-link--end"
              onClick={() => navigate("/forgot-password")}
             >
               Forgot password?
             </button>
          )}

          <button type="submit">
            {isRegister ? "Create account" : "Sign in"}
          </button>
        </form>

        <p>
          {isRegister ? "Already have an account?" : "Don't have an account?"}{" "}
          <button type="button" className="text-link" onClick={() => setIsRegister(!isRegister)}>
            {isRegister ? "Sign in" : "Create one"}
          </button>
        </p>
      </main>
    </div>
  );
};

export default Login;
