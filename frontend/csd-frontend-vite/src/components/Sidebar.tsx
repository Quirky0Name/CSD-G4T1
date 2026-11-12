import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'
import { BellAlertIcon, DocumentTextIcon, FolderIcon } from '@heroicons/react/24/outline'
import { Link, NavLink, useLocation } from 'react-router-dom'
import { useSyncExternalStore } from 'react'
import { useAuth } from '../auth'
import { useTracking } from '../tracking'
import { MascotHead } from './AuthShell'

const navigationItems = [
  { label: 'Projects', to: '/', icon: FolderIcon },
  { label: 'Alerts', to: '/alerts', icon: BellAlertIcon },
  { label: 'Reports', to: '/reports', icon: DocumentTextIcon },
]

const BAR_QUERY = '(min-width: 40rem)'
const subscribe = (onChange: () => void) => {
  const query = window.matchMedia(BAR_QUERY)
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}
const useTopBar = () => useSyncExternalStore(subscribe, () => window.matchMedia(BAR_QUERY).matches)

// A bar across the top from sm up (a phone on its side included): the desk's mark on the left, the
// sections and the account on the right. On an upright phone it becomes a tab bar along the bottom,
// where the thumb already is.
function Sidebar() {
  const { session, logout } = useAuth()
  const { state } = useTracking()
  const topBar = useTopBar()
  const email = session?.user.email
  const { pathname } = useLocation()
  // /projects is User Management's, so a project's page lives under /project
  const inProject = pathname === '/project' || pathname.startsWith('/project/') || pathname.startsWith('/papers/')
  const newAlerts = state.status === 'ready' ? state.alerts.filter((alert) => alert.status === 'new').length : 0

  return (
    <nav
      aria-label="Main navigation"
      className="fixed inset-x-0 bottom-0 z-40 flex shrink-0 items-center gap-1 border-t border-white/10 bg-gray-900/95 px-2 pt-1.5 pb-[calc(0.375rem+env(safe-area-inset-bottom))] backdrop-blur-md sm:sticky sm:top-0 sm:bottom-auto sm:h-16 sm:gap-3 sm:border-t-0 sm:border-b sm:bg-gray-950/80 sm:px-6 sm:py-0 lg:px-10 short:sm:h-12"
    >
      <Link
        to="/"
        className="group mr-auto hidden items-center gap-2.5 rounded-full py-1 pr-2 focus-visible:outline-2 focus-visible:outline-indigo-400 sm:flex"
      >
        <MascotHead className="size-9 transition-transform group-hover:-rotate-8 short:size-7" />
        <span className="leading-none">
          <span className="block text-base font-semibold tracking-tight text-white">Research desk</span>
          <span className="mt-0.5 hidden text-[10px] font-medium tracking-[0.18em] text-indigo-300/80 md:block short:hidden">
            citation watch
          </span>
        </span>
      </Link>

      <ul role="list" className="flex flex-1 gap-1 sm:flex-none">
        {navigationItems.map(({ label, to, icon: Icon }) => (
          <li key={to} className="flex-1 sm:flex-none">
            <NavLink
              to={to}
              end={to === '/'}
              className={({ isActive }) =>
                `group relative flex flex-col items-center gap-0.5 rounded-2xl py-1.5 text-[11px]/4 font-medium transition-colors focus-visible:outline-2 focus-visible:outline-indigo-400 sm:flex-row sm:gap-2 sm:rounded-full sm:px-3.5 sm:py-2 sm:text-sm ${
                  isActive || (to === '/' && inProject) ? 'text-white sm:bg-indigo-500/15 sm:text-indigo-200' : 'text-gray-400 hover:text-white sm:hover:bg-white/5'
                }`
              }
            >
              {({ isActive }) => {
                const active = isActive || (to === '/' && inProject)
                return (
                  <>
                    <span
                      className={`flex h-7 w-12 items-center justify-center rounded-full transition-colors sm:h-auto sm:w-auto sm:bg-transparent ${
                        active ? 'bg-indigo-500/15 text-indigo-300' : 'group-hover:bg-white/5 sm:group-hover:bg-transparent'
                      }`}
                    >
                      <Icon aria-hidden="true" className="size-5" />
                    </span>
                    {label}
                    {to === '/alerts' && newAlerts > 0 && (
                      <span className="absolute top-0.5 left-1/2 ml-2 min-w-4 rounded-full bg-indigo-500 px-1 text-center text-[10px]/4 font-semibold text-white tabular-nums ring-2 ring-gray-900 sm:static sm:ml-0 sm:ring-0">
                        {newAlerts > 99 ? '99+' : newAlerts}
                        <span className="sr-only"> new</span>
                      </span>
                    )}
                  </>
                )
              }}
            </NavLink>
          </li>
        ))}
      </ul>

      <Menu>
        <MenuButton
          title={email}
          className="mx-1 flex size-10 shrink-0 items-center justify-center rounded-full bg-white/10 text-gray-200 outline-1 -outline-offset-1 outline-white/10 transition-colors hover:bg-white/20 hover:text-white focus-visible:outline-2 focus-visible:outline-indigo-400 sm:mx-0 sm:ml-2 sm:size-9 short:sm:size-8"
        >
          <span className="sr-only">Account</span>
          <span aria-hidden="true" className="text-sm/5 font-semibold">
            {(email?.[0] ?? 'U').toUpperCase()}
          </span>
        </MenuButton>
        <MenuItems
          transition
          anchor={topBar ? { to: 'bottom end', gap: 10, padding: 8 } : { to: 'top end', gap: 10, padding: 8 }}
          className="z-50 w-64 origin-top-right rounded-2xl bg-gray-800 p-1.5 shadow-lg outline-1 -outline-offset-1 outline-white/10 transition duration-150 ease-out focus:outline-none data-closed:scale-95 data-closed:opacity-0"
        >
          <p className="truncate px-3 pt-2 pb-2.5 text-xs/5 text-gray-400">{email ?? 'Your account'}</p>
          <div className="h-px bg-white/10" />
          <MenuItem>
            <Link to="/profile" className="mt-1 block w-full rounded-xl px-3 py-2 text-left text-white data-focus:bg-white/5">
              <span className="text-sm/5">Your profile</span>
            </Link>
          </MenuItem>
          <MenuItem>
            <button
              type="button"
              onClick={logout}
              className="block w-full rounded-xl px-3 py-2 text-left text-white data-focus:bg-white/5"
            >
              <span className="text-sm/5">Sign out</span>
            </button>
          </MenuItem>
        </MenuItems>
      </Menu>
    </nav>
  )
}

export default Sidebar
