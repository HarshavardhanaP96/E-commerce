import { useEffect, useState } from "react";
import toast from "react-hot-toast";
import { LuTrash2, LuUser } from "react-icons/lu";
import { useNavigate, useParams } from "react-router-dom";
import { useSelector } from "react-redux";
import DetailLayout from "../../../components/admin/DetailLayout";
import Loader from "../../../components/admin/Loader";
import StatusBadge from "../../../components/admin/StatusBadge";
import { errorMessage } from "../../../redux/api/baseQuery";
import { useAllOrdersQuery } from "../../../redux/api/orderAPI";
import { useDeleteUserMutation, useUpdateUserRoleMutation, useUserDetailsQuery } from "../../../redux/api/userAPI";
import { RootState } from "../../../redux/store";
import { Role } from "../../../types/types";
import { formatDate, formatINR, transformImage } from "../../../utils/features";

const CustomerManagement = () => {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const me = useSelector((s: RootState) => s.userReducer.user);
  const { data, isLoading, isError } = useUserDetailsQuery(id);
  const { data: orders } = useAllOrdersQuery({});
  const [updateRole, { isLoading: saving }] = useUpdateUserRoleMutation();
  const [deleteUser, { isLoading: deleting }] = useDeleteUserMutation();
  const [role, setRole] = useState<Role>("user");

  const user = data?.user;
  useEffect(() => {
    if (user) setRole(user.role);
  }, [user]);

  useEffect(() => {
    if (isError) {
      toast.error("User not found");
      navigate("/admin/customer", { replace: true });
    }
  }, [isError, navigate]);

  if (isLoading || !user) return <Loader />;

  const isSelf = me?._id === user._id;
  const userOrders = (orders?.orders ?? []).filter((o) => o.user?._id === user._id);
  const spent = userOrders.reduce((sum, o) => sum + o.total, 0);

  const save = async () => {
    const res = await updateRole({ userId: id, role });
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
  };

  const remove = async () => {
    if (!confirm(`Delete ${user.name}'s account? This cannot be undone.`)) return;
    const res = await deleteUser(id);
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    navigate("/admin/customer");
  };

  return (
    <DetailLayout
      title={user.name}
      subtitle={`ID ${user._id}`}
      actions={
        <>
          <button className="btn btn--primary" onClick={save} disabled={saving || role === user.role}>
            {saving ? "Saving…" : "Save"}
          </button>
          <button className="btn btn--danger" onClick={remove} disabled={deleting || isSelf}
                  title={isSelf ? "You cannot delete your own account" : undefined}>
            <LuTrash2 /> Delete
          </button>
        </>
      }
    >
      <div className="detail-grid">
        <section className="card card--center">
          {user.photo ? (
            <img className="detail-photo detail-photo--round" src={transformImage(user.photo)} alt={user.name} />
          ) : (
            <div className="detail-photo detail-photo--round placeholder"><LuUser /></div>
          )}
          <h3>{user.name}</h3>
          <span>{user.email}</span>
          <span className={`badge badge--${user.role}`}>{user.role}</span>
        </section>

        <section className="card">
          <dl className="facts">
            <dt>Gender</dt><dd>{user.gender}</dd>
            <dt>Date of birth</dt><dd>{formatDate(user.dob)} ({user.age} yrs)</dd>
            <dt>Joined</dt><dd>{formatDate(user.createdAt)}</dd>
            <dt>Orders</dt><dd>{userOrders.length} · {formatINR(spent)} spent</dd>
            <dt>Role</dt>
            <dd>
              <select value={role} onChange={(e) => setRole(e.target.value as Role)} disabled={isSelf}>
                <option value="user">User</option>
                <option value="admin">Admin</option>
              </select>
            </dd>
          </dl>
          {userOrders.length > 0 && (
            <ul className="mini-list">
              {userOrders.slice(0, 5).map((o) => (
                <li key={o._id} onClick={() => navigate(`/admin/transaction/${o._id}`)}>
                  <span>{formatDate(o.createdAt)}</span>
                  <span>{formatINR(o.total)}</span>
                  <StatusBadge status={o.status} />
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </DetailLayout>
  );
};

export default CustomerManagement;
