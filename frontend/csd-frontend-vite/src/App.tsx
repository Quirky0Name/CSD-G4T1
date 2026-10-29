import { BrowserRouter, Outlet, Route, Routes } from 'react-router-dom'
import Sidebar from './components/Sidebar'
import { ToastProvider } from './components/Toasts'
import { TrackingProvider } from './tracking'
import Alerts from './pages/Alerts'
import Project from './pages/Project'
import Projects from './pages/Projects'
import Register from './pages/Register'
import Signin from './pages/Signin'
import { AuthProvider, GuestOnly, RequireAuth, useAuth } from './auth'

// Only rendered under RequireAuth, so tracking never fetches without a session.
// Keyed by user so a different user gets a fresh provider with no leftover state.
function AppLayout() {
  const { session } = useAuth()
  return (
    <TrackingProvider key={session?.user.id}>
      <div className="min-h-screen flex">
        <Sidebar />
        <section className="min-h-screen flex-1 bg-grey-950 mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <Outlet />
        </section>
      </div>
    </TrackingProvider>
  )
}

function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          <Routes>
            <Route element={<GuestOnly />}>
              <Route path="/signin" element={<Signin />} />
              <Route path="/register" element={<Register />} />
            </Route>
            <Route element={<RequireAuth />}>
              <Route element={<AppLayout />}>
                <Route path="/" element={<Projects />} />
                <Route path="/project" element={<Project />} />
                <Route path="/alerts" element={<Alerts />} />
              </Route>
            </Route>
          </Routes>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  )
}

export default App