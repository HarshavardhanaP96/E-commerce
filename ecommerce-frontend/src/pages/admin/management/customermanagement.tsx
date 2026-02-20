import { FormEvent, useState } from "react";
import { FaTrash } from "react-icons/fa";
import AdminSidebar from "../../../components/admin/AdminSidebar";
// import { useSelector } from "react-redux";
// import { RootState } from "../../../redux/store";
import { useNavigate } from "react-router-dom";
// import { useDeleteUserMutation, useUserDetailsQuery, useUpdateUserMutation } from "../../../redux/api/userAPI";
// import { MessageResponse, UserResponse } from "../../../types/api-types";
import toast from "react-hot-toast";

const CustomerManagement = () => {
  // const { user } = useSelector((state: RootState) => state.userReducer);
  // const params = useParams();
  const navigate = useNavigate();

  // Mock Data & Handlers (Replace with actual API hooks)
  // const { data, isError, error } = useUserDetailsQuery(params.id!);
  // const [deleteUser] = useDeleteUserMutation();
  // const [updateUser] = useUpdateUserMutation();

  const [name, setName] = useState<string>("Emily Palmer");
  const [email] = useState<string>("emily.palmer@example.com");
  const [gender, setGender] = useState<string>("female");
  const [role, setRole] = useState<string>("user");
  const [avatar] = useState<string>("https://randomuser.me/api/portraits/women/54.jpg");

  // Load Data
  // useEffect(() => {
  //   if (data) {
  //     setName(data.user.name);
  //     setEmail(data.user.email);
  //     setGender(data.user.gender);
  //     setRole(data.user.role);
  //     setAvatar(data.user.photo);
  //   }
  // }, [data]);

  const deleteHandler = async () => {
    // const res = await deleteUser({ userId: user?._id!, adminUserId: params.id! });
    // response handling...
    toast.success("User Deleted");
    navigate("/admin/customer");
  };

  const submitHandler = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    // Update logic
    toast.success("User Updated");
    navigate("/admin/customer");
  };

  return (
    <div className="admin-container" style={{ display: 'grid', gridTemplateColumns: 'min-content 1fr', height: 'calc(100vh - 4rem)', backgroundColor: '#f3f4f6', overflow: 'hidden' }}>
      <AdminSidebar />
      <main className="product-management" style={{ padding: '1rem', display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
        <article style={{
            backgroundColor: 'white',
            padding: '2rem',
            borderRadius: '12px',
            boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
            width: '100%',
            maxWidth: '600px',
            position: 'relative',
            display: 'flex',
            flexDirection: 'column',
            gap: '1.5rem'
        }}>
            {/* Header Actions */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <h2 style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#1f2937', margin: 0 }}>Manage User</h2>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <button type="submit" form="update-user-form" style={{
                         padding: '0.4rem 1rem',
                         backgroundColor: '#3b82f6',
                         color: 'white',
                         border: 'none',
                         borderRadius: '6px',
                         fontWeight: '600',
                         cursor: 'pointer',
                         transition: 'background 0.2s',
                         fontSize: '0.9rem'
                    }}>
                        Update
                    </button>
                    <button className="product-delete-btn" onClick={deleteHandler} style={{
                        backgroundColor: '#fee2e2',
                        color: '#991b1b',
                        border: 'none',
                        borderRadius: '6px',
                        padding: '0.4rem 0.6rem',
                        fontSize: '1rem',
                        cursor: 'pointer',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center'
                    }}>
                        <FaTrash />
                    </button>
                </div>
            </div>
            
            <div style={{ display: 'flex', justifyContent: 'center', marginBottom: '1rem' }}>
                <img src={avatar} alt={name} style={{ width: '100px', height: '100px', borderRadius: '50%', objectFit: 'cover', border: '2px solid #e5e7eb' }} />
            </div>

            <form id="update-user-form" onSubmit={submitHandler} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                <div>
                    <label style={{ display: 'block', marginBottom: '0.25rem', fontSize: '0.85rem', fontWeight: '600', color: '#4b5563' }}>Name</label>
                    <input
                        type="text"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        style={{ width: '100%', padding: '0.5rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none' }}
                    />
                </div>
                <div>
                    <label style={{ display: 'block', marginBottom: '0.25rem', fontSize: '0.85rem', fontWeight: '600', color: '#4b5563' }}>Email</label>
                    <input
                        type="email"
                        value={email}
                        readOnly // Usually email isn't editable easily or requires specific flow
                        style={{ width: '100%', padding: '0.5rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none', backgroundColor: '#f9fafb', color: '#6b7280' }}
                    />
                </div>
                
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.25rem', fontSize: '0.85rem', fontWeight: '600', color: '#4b5563' }}>Gender</label>
                        <select
                            value={gender}
                            onChange={(e) => setGender(e.target.value)}
                            style={{ width: '100%', padding: '0.5rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none' }}
                        >
                            <option value="male">Male</option>
                            <option value="female">Female</option>
                        </select>
                    </div>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.25rem', fontSize: '0.85rem', fontWeight: '600', color: '#4b5563' }}>Role</label>
                        <select
                            value={role}
                            onChange={(e) => setRole(e.target.value)}
                            style={{ width: '100%', padding: '0.5rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none' }}
                        >
                            <option value="user">User</option>
                            <option value="admin">Admin</option>
                        </select>
                    </div>
                </div>
            </form>
        </article>
      </main>
    </div>
  );
};

export default CustomerManagement;
