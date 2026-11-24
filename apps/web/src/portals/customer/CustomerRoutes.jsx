import { Route, Routes } from 'react-router-dom'
import { RequireAuth } from '../../shared/auth/AuthProvider.jsx'
import CustomerLayout from './layout/CustomerLayout.jsx'
import CustomerHome from './pages/HomePage.jsx'
import ServicePage from './pages/ServicePage.jsx'
import CustomerLogin from './pages/AuthPage.jsx'
import CustomerCreate from './pages/CreateOrderPage.jsx'
import CustomerOrders from './pages/OrdersPage.jsx'
import CustomerTracking from './pages/TrackingPage.jsx'
import CustomerNotifications from './pages/NotificationsPage.jsx'
import CustomerAccount from './pages/AccountPage.jsx'
import CustomerNotFound from './pages/NotFoundPage.jsx'
import './styles/customer.css'

export default function CustomerRoutes() {
  return <Routes><Route element={<CustomerLayout />}><Route index element={<CustomerHome />} /><Route path="service" element={<ServicePage />} /><Route path="login" element={<CustomerLogin />} /><Route element={<RequireAuth role="customer" />}><Route path="send" element={<CustomerCreate />} /><Route path="orders" element={<CustomerOrders />} /><Route path="orders/:id" element={<CustomerTracking />} /><Route path="notifications" element={<CustomerNotifications />} /><Route path="account" element={<CustomerAccount />} /></Route><Route path="*" element={<CustomerNotFound />} /></Route></Routes>
}
