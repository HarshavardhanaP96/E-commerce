import { useState } from 'react';
import { BsSearch } from 'react-icons/bs';
import AdminSidebar from '../../components/admin/AdminSidebar';
import { FaRegBell } from 'react-icons/fa';
import { HiTrendingDown, HiTrendingUp } from 'react-icons/hi';
import { BarChart, DoughnutChart, LineChart } from '../../components/admin/Charts';
import { BiMaleFemale } from 'react-icons/bi';

const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const Analytics = () => {
  const [activeTab, setActiveTab] = useState('orders');

  return (
    <div className='admin-container' style={{ display: 'grid', gridTemplateColumns: 'min-content 1fr', height: 'calc(100vh - 4rem)', backgroundColor: '#f3f4f6' }}>
      <AdminSidebar />
      <main className='dashboard' style={{ overflowY: 'auto', padding: '0', backgroundColor: '#f3f4f6', fontFamily: '"Inter", sans-serif' }}>
        
        {/* Header Section */}
        <div style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '1.5rem 2rem',
            backgroundColor: 'white',
            boxShadow: '0 2px 4px rgba(0,0,0,0.05)',
            position: 'sticky',
            top: 0,
            zIndex: 10
        }}>
            {/* Tabs */}
            <div style={{ display: 'flex', gap: '0.5rem', backgroundColor: '#f3f4f6', padding: '0.3rem', borderRadius: '12px' }}>
                {['orders', 'users', 'products'].map((tab) => (
                    <button
                        key={tab}
                        onClick={() => setActiveTab(tab)}
                        style={{
                            padding: '0.6rem 1.5rem',
                            fontSize: '0.9rem',
                            textTransform: 'capitalize',
                            backgroundColor: activeTab === tab ? 'white' : 'transparent',
                            color: activeTab === tab ? '#1f2937' : '#6b7280',
                            border: 'none',
                            borderRadius: '8px',
                            cursor: 'pointer',
                            fontWeight: activeTab === tab ? '600' : '500',
                            transition: 'all 0.2s',
                            boxShadow: activeTab === tab ? '0 2px 5px rgba(0,0,0,0.05)' : 'none'
                        }}
                    >
                        {tab}
                    </button>
                ))}
            </div>

            {/* Profile & Notifications */}
             <div style={{ display: 'flex', gap: '1.5rem', alignItems: 'center' }}>
                <FaRegBell style={{ fontSize: '1.2rem', color: '#6b7280', cursor: 'pointer' }} />
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
                    <img src="https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSqFzl9DjutlpVLcc83GGh8beynflxmbpu3TT31bk5N1hI2hssL" alt='User' style={{ width: '36px', height: '36px', borderRadius: '50%', objectFit: 'cover' }} />
                    <span style={{ fontSize: '0.9rem', fontWeight: '500', color: '#374151' }}>Admin</span>
                </div>
             </div>
        </div>

        {/* Content Area */}
        <div style={{ padding: '2rem', maxWidth: '1400px', margin: '0 auto' }}>
            {activeTab === 'orders' && <OrdersAnalytics />}
            {activeTab === 'users' && <UsersAnalytics />}
            {activeTab === 'products' && <ProductsAnalytics />}
        </div>

      </main>
    </div>
  );
};

// --- Sub-Components ---

const SearchBar = ({ placeholder }: { placeholder: string }) => (
    <div style={{ 
        display: 'flex', 
        alignItems: 'center', 
        gap: '1rem', 
        backgroundColor: 'white', 
        padding: '0.8rem 1.5rem', 
        borderRadius: '10px', 
        marginBottom: '2rem',
        boxShadow: '0 2px 4px rgba(0,0,0,0.02)',
        border: '1px solid #e5e7eb'
    }}>
        <BsSearch style={{ color: '#9ca3af' }} />
        <input 
            type='text' 
            placeholder={placeholder} 
            style={{ 
                border: 'none', 
                outline: 'none', 
                width: '100%', 
                backgroundColor: 'transparent',
                fontSize: '1rem',
                color: '#374151'
            }} 
        />
    </div>
);

const SectionTitle = ({ title }: { title: string }) => (
    <h3 style={{ 
        fontSize: '1.1rem', 
        fontWeight: '600', 
        color: '#111827', 
        marginBottom: '1rem',
        marginTop: '2rem'
    }}>
        {title}
    </h3>
);

const WidgetContainer = ({ children }: { children: React.ReactNode }) => (
    <section style={{ 
        display: 'grid', 
        gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', 
        gap: '1.5rem',
        marginBottom: '2rem'
    }}>
        {children}
    </section>
);

const ChartContainer = ({ children, title }: { children: React.ReactNode, title: string }) => (
    <div style={{ 
        backgroundColor: 'white', 
        padding: '1.5rem', 
        borderRadius: '10px', 
        boxShadow: '0 2px 4px rgba(0,0,0,0.02)',
        border: '1px solid #e5e7eb',
        display: 'flex',
        flexDirection: 'column',
        gap: '1rem',
        minHeight: '350px'
    }}>
        <h2 style={{ fontSize: '1rem', fontWeight: '600', color: '#374151', textTransform: 'uppercase', letterSpacing: '0.5px' }}>{title}</h2>
        {children}
    </div>
);

const OrdersAnalytics = () => (
  <>
    <SearchBar placeholder='Search orders by ID, customer name...' />

    <SectionTitle title='Monetary Aggregates' />
    <WidgetContainer>
       <WidgetItem percent={12} amount={true} value={145.50} heading='Avg Order Value' color='#3b82f6' />
       <WidgetItem percent={5} amount={true} value={130.00} heading='Median Order Value' color='#f59e0b' />
       <WidgetItem percent={-2} amount={true} value={45.20} heading='Revenue StdDev' color='#8b5cf6' />
       <WidgetItem percent={25} amount={true} value={540000} heading='Gross Merchandise Value' color='#10b981' />
    </WidgetContainer>

    <SectionTitle title='Performance Metrics' />
    <WidgetContainer>
       <WidgetItem percent={2.4} amount={false} value={3.5} heading='Conversion Rate (%)' color='#ec4899' />
       <WidgetItem percent={-0.5} amount={false} value={1.2} heading='Return Rate (%)' color='#ef4444' />
       <WidgetItem percent={10} amount={true} value={12500} heading='Moving Avg Revenue' color='#f97316' />
       <WidgetItem percent={15} amount={false} value={450} heading='Velocity (Orders/Day)' color='#06b6d4' />
    </WidgetContainer>

    <SectionTitle title='Trends & Volume' />
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(400px, 1fr))', gap: '2rem' }}>
      <ChartContainer title='Revenue Trend'>
        <LineChart
            data={[24000, 14400, 24100, 34300, 90000, 20000, 25600, 44700, 99000, 144400, 100000, 120000]}
            backgroundColor='rgba(16, 185, 129, 0.2)'
            borderColor='#10b981'
            label='Revenue'
            labels={months}
        />
      </ChartContainer>
      
      <ChartContainer title='Order Volume'>
         <BarChart
            horizontal={false}
            data_2={[]}
            data_1={[200, 444, 343, 556, 778, 455, 990, 444, 122, 334, 890, 909]}
            title_1='Orders'
            title_2=''
            bgColor_1='#3b82f6'
            bgColor_2=''
            labels={months}
          />
      </ChartContainer>
    </div>
  </>
);

const UsersAnalytics = () => (
  <>
    <SearchBar placeholder='Search users by email, name...' />

    <SectionTitle title='Customer Value' />
    <WidgetContainer>
      <WidgetItem percent={8} amount={true} value={850} heading='Customer Lifetime Value' color='#3b82f6' />
      <WidgetItem percent={-5} amount={true} value={45} heading='Acquisition Cost (CAC)' color='#ef4444' />
      <WidgetItem percent={12} amount={false} value={4.2} heading='Avg Order Frequency' color='#10b981' />
    </WidgetContainer>

     <SectionTitle title='Engagement & Churn' />
    <WidgetContainer>
      <WidgetItem percent={-15} amount={false} value={12} heading='Recency (Days)' color='#f59e0b' />
      <WidgetItem percent={-2} amount={false} value={5.4} heading='Churn Rate (%)' color='#ef4444' />
       <WidgetItem percent={5} amount={false} value={420} heading='Avg RFM Score' color='#8b5cf6' />
    </WidgetContainer>

    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(400px, 1fr))', gap: '2rem', marginTop: '2rem' }}>
        <ChartContainer title='User Growth'>
            <LineChart
                data={[200, 444, 444, 556, 778, 455, 990, 1444, 256, 447, 1000, 1200]}
                label='Users'
                borderColor='#3b82f6'
                backgroundColor='rgba(59, 130, 246, 0.2)'
                labels={months}
            />
        </ChartContainer>
        
         <ChartContainer title='Demographics (Gender)'>
             <div style={{ height: '300px', display: 'flex', justifyContent: 'center' }}>
                <DoughnutChart
                    labels={['Female', 'Male']}
                    data={[12, 19]}
                    backgroundColor={['#ec4899', '#3b82f6']}
                    cutout={90}
                 />
             </div>
             <div style={{ textAlign: 'center', marginTop: '-40px' }}><BiMaleFemale style={{fontSize: '2rem', color: '#6b7280'}} /></div>
        </ChartContainer>
    </div>
  </>
);

const ProductsAnalytics = () => (
  <>
    <SearchBar placeholder='Search products by SKU, name...' />

    <SectionTitle title='Inventory Health & Profitability' />
     <WidgetContainer>
        <WidgetItem percent={10} amount={false} value={65} heading='Sell-Through Rate (%)' color='#8b5cf6' />
        <WidgetItem percent={-5} amount={false} value={32} heading='Days Inventory Outstanding' color='#f97316' />
        <WidgetItem percent={2} amount={false} value={45} heading='Avg Product Margin (%)' color='#10b981' />
         <WidgetItem percent={0} amount={false} value={0.75} heading='Concentration (Gini)' color='#ec4899' />
    </WidgetContainer>

    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(400px, 1fr))', gap: '2rem', marginTop: '2rem' }}>
        <ChartContainer title='Top Products Sales'>
             <BarChart
                data_1={[200, 444, 343, 556, 778, 455, 990]}
                data_2={[]}
                title_1='Sales'
                title_2=''
                bgColor_1='#8b5cf6'
                bgColor_2=''
            />
        </ChartContainer>
        <ChartContainer title='Stock Distribution'>
             <div style={{ height: '300px', display: 'flex', justifyContent: 'center' }}>
                <DoughnutChart
                  labels={['In Stock', 'Out of Stock', 'Low Stock']}
                  data={[40, 20, 5]}
                  backgroundColor={['#10b981', '#3b82f6', '#ef4444']}
                  legends={true}
                  offset={[0, 10, 20]}
                  cutout='70%'
                />
             </div>
        </ChartContainer>
    </div>
  </>
);

interface WidgetItemProps {
  heading: string;
  value: number;
  percent: number;
  color: string;
  amount?: boolean;
}

const WidgetItem = ({ heading, value, percent, color, amount }: WidgetItemProps) => (
  <article style={{
      backgroundColor: 'white',
      padding: '1.5rem',
      borderRadius: '10px',
      boxShadow: '0 2px 4px rgba(0,0,0,0.02)',
      border: '1px solid #e5e7eb',
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center'
  }}>
    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.3rem' }}>
      <p style={{ fontSize: '0.85rem', color: '#6b7280', fontWeight: '500' }}>{heading}</p>
      <h4 style={{ fontSize: '1.5rem', fontWeight: '700', color: '#111827' }}>{amount ? `$${value}` : value}</h4>
      
      {percent > 0 ? (
        <span style={{ color: '#10b981', display: 'flex', alignItems: 'center', gap: '0.2rem', fontSize: '0.85rem', fontWeight: '600' }}>
          <HiTrendingUp /> +{percent}%
        </span>
      ) : (
        <span style={{ color: '#ef4444', display: 'flex', alignItems: 'center', gap: '0.2rem', fontSize: '0.85rem', fontWeight: '600' }}>
          <HiTrendingDown /> {percent}%
        </span>
      )}
    </div>

    <div style={{
        position: 'relative',
        width: '4rem',
        height: '4rem',
        borderRadius: '50%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: `conic-gradient(${color} ${(Math.abs(percent) / 100) * 360}deg, #f3f4f6 0)`
    }}>
      <div style={{
          position: 'absolute',
          width: '3.2rem',
          height: '3.2rem',
          backgroundColor: 'white',
          borderRadius: '50%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center'
      }}>
         <span style={{ color: color, fontSize: '0.8rem', fontWeight: '700' }}>{percent}%</span>
      </div>
    </div>
  </article>
);

export default Analytics;
