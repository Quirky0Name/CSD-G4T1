import { lazy, Suspense } from 'react'
import { BrowserRouter, Outlet, Route, Routes } from 'react-router-dom'
import Sidebar from './components/Sidebar'
import { ToastProvider } from './components/Toasts'
import { TrackingProvider } from './tracking'
import Alerts from './pages/Alerts'
import Project from './pages/Project'
import Projects from './pages/Projects'
import Register from './pages/Register'
import Reports from './pages/Reports'
import Signin from './pages/Signin'
import { AuthProvider, GuestOnly, RequireAdmin, RequireAuth, RequireResearcher, useAuth } from './auth'

// Loaded only by the admin, so researchers never download it or its fonts.
const DevMode = lazy(() => import('./pages/DevMode'))
// Carries pdf.js, so it loads only when a report is opened.
const ReportView = lazy(() => import('./pages/ReportView'))

// Only rendered under RequireAuth, so tracking never fetches without a session.
// Keyed by user so a different user gets a fresh provider with no leftover state.
function AppLayout() {
  const { session } = useAuth()
  return (
    <TrackingProvider key={session?.user.id}>
      <div className="flex min-h-screen flex-col sm:flex-row">
        <Sidebar />
        <section className="min-h-screen w-full min-w-0 flex-1 bg-gray-950">
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
              <Route element={<RequireResearcher />}>
                <Route element={<AppLayout />}>
                  <Route path="/" element={<Projects />} />
                  <Route path="/project" element={<Project />} />
                  <Route path="/project/:projectId" element={<Project />} />
                  <Route path="/alerts" element={<Alerts />} />
                  <Route path="/reports" element={<Reports />} />
                  <Route
                    path="/reports/:paperId/:reportId"
                    element={
                      <Suspense fallback={null}>
                        <ReportView />
                      </Suspense>
                    }
                  />
                </Route>
              </Route>
              <Route element={<RequireAdmin />}>
                <Route
                  path="/dev"
                  element={
                    <Suspense fallback={null}>
                      <DevMode />
                    </Suspense>
                  }
                />
              </Route>
            </Route>
          </Routes>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  )
}

export default App