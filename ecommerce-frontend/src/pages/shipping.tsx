import { ChangeEvent, useState } from "react"
import { BiArrowBack } from "react-icons/bi";
import { useNavigate } from "react-router-dom";

const Shipping = () => {

  const navigate =useNavigate()

  const [shippingInfo, setShippingInfo]=useState({
      address:"",
      city:"",
      state:"",
      country:"",
      pincode:""
  })

  const changeHandler=(e:ChangeEvent<HTMLInputElement|HTMLSelectElement>)=>{
      setShippingInfo((prev)=>({...prev, [e.target.name]:e.target.value}))
  };

  return (
    <div className="shipping">
      
        <button className="back-btn" onClick={()=>navigate("/cart")}>
          <BiArrowBack/>
        </button>

        <form >
            <h1>Shipping Address</h1>

            <input type="text" 
            name="address" 
            placeholder="Address" 
            value={shippingInfo.address} 
            onChange={changeHandler}
            required />

            <input type="text" 
            name="city" 
            placeholder="City" 
            value={shippingInfo.city} 
            onChange={changeHandler}
            required />

            <input type="text" 
            name="state" 
            placeholder="State" 
            value={shippingInfo.state} 
            onChange={changeHandler}
            required />

            <select name="country"
            required
            value={shippingInfo.country}
            onChange={changeHandler}>

                {/* country picker can be used */}
                <option value="">Choose a Country</option>
                <option value="India">India</option>


            </select>


            <input type="number" 
            name="pincode" 
            placeholder="Pin Code" 
            value={shippingInfo.pincode} 
            onChange={changeHandler}
            required />

            <button type="submit">Order Now</button>

        </form>
      
    </div>
  )
}

export default Shipping
