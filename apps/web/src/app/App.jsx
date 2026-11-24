import FinancePage from '../portals/dispatcher/pages/FinancePage.jsx'
import PricingPage from '../portals/dispatcher/pages/PricingPage.jsx'
import { AuthProvider, RequireAuth } from '../shared/auth/AuthProvider.jsx'
import StaffLogin from '../shared/auth/StaffLogin.jsx'
import { IS_DRIVER, IS_CUSTOMER } from '../shared/config/portal.js'
import { Routes, Route, Navigate } from 'react-router-dom'
import Layout from '../portals/dispatcher/layout/DispatcherLayout.jsx'
import DriverApp from '../portals/driver/DriverApp.jsx'
import Dashboard from '../portals/dispatcher/pages/DashboardPage.jsx'
import Orders from '../portals/dispatcher/pages/OrdersPage.jsx'
import OrderDetailPage from '../portals/dispatcher/pages/OrderDetailPage.jsx'
import Warehouses from '../portals/dispatcher/pages/WarehousesPage.jsx'
import Vehicles from '../portals/dispatcher/pages/VehiclesPage.jsx'
import MaintenancePage from '../portals/dispatcher/pages/MaintenancePage.jsx'
import Drivers from '../portals/dispatcher/pages/DriversPage.jsx'
import Settings from '../portals/dispatcher/pages/SettingsPage.jsx'
import NotificationsPage from '../portals/dispatcher/pages/NotificationsPage.jsx'
import Live from '../portals/dispatcher/pages/FleetTrackingPage.jsx'
import Optimize from '../portals/dispatcher/pages/RoutingPage.jsx'
import Dynamic from '../portals/dispatcher/pages/CreateOrderPage.jsx'
import Distribution from '../portals/dispatcher/pages/DistributionPage.jsx'
import CustomerRoutes from '../portals/customer/CustomerRoutes.jsx'

function NotFound() {
  return <div className="auth-page"><div className="card"><h1>Không tìm thấy trang</h1><p>Địa chỉ này không thuộc cổng hiện tại.</p><a href="/">Về trang chủ</a></div></div>
}

export default function App() {
  if (IS_CUSTOMER) return <AuthProvider><CustomerRoutes /></AuthProvider>
  return (
    <AuthProvider><Routes>
      <Route path="/login" element={<StaffLogin />} />
      {IS_DRIVER ? (
        <Route element={<RequireAuth role="driver" />}>
          <Route index element={<DriverApp />} />
        </Route>
      ) : (
        <Route element={<RequireAuth role="dispatcher" />}>
          <Route element={<Layout />}>
            <Route index element={<Dashboard />} />
            <Route path="orders" element={<Orders />} /><Route path="orders/:id" element={<OrderDetailPage />} />
            <Route path="warehouses" element={<Warehouses />} />
            <Route path="vehicles" element={<Vehicles />} /><Route path="maintenance" element={<MaintenancePage />} /><Route path="pricing" element={<PricingPage />} />
            <Route path="drivers" element={<Drivers />} />
            <Route path="live" element={<Live />} />
            <Route path="routing" element={<Optimize />} />
            <Route path="distribution" element={<Distribution />} />
            <Route path="orders/new" element={<Dynamic />} />
            <Route path="optimize" element={<Navigate to="/routing" replace />} />
            <Route path="dynamic" element={<Navigate to="/orders/new" replace />} />
            <Route path="reports" element={<FinancePage />} /><Route path="finance" element={<Navigate to="/reports" replace />} />
            <Route path="settings" element={<Settings />} />
            <Route path="notifications" element={<NotificationsPage />} />
          </Route>
        </Route>
      )}
      <Route path="*" element={<NotFound />} />
    </Routes></AuthProvider>
  )
}
