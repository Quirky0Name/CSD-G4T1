import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Dialog, DialogBackdrop, DialogPanel, DialogTitle } from '@headlessui/react'
import { PencilIcon, PlusIcon, TrashIcon } from '@heroicons/react/20/solid'
import { FolderIcon } from '@heroicons/react/24/outline'
import { createProject, deleteProject, errorMessage, listProjects, renameProject, type Alert, type Project } from '../api'
import { ActionButton } from '../components/ActionButton'
import { useToast } from '../components/Toasts'
import { ALERT_SEVERITY_DOT_CLASS, highestNewAlertSeverity } from '../format'
import { useTracking } from '../tracking'
import { Page, PageHeader } from '../components/Page'
import { DIALOG_FRAME, DIALOG_PANEL, card, cardInteractive, errorPanel, iconButton, inputClasses, riseDelay, signalLink } from '../ui'


type Load = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; projects: Project[] }

function paperCount(count: number | null): string {
  if (count === null) return ''
  return `${count} paper${count === 1 ? '' : 's'}`
}

function Projects() {
  const { state: tracking, refresh } = useTracking()
  const showToast = useToast()
  const [load, setLoad] = useState<Load>({ status: 'loading' })
  const [creating, setCreating] = useState(false)
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<Project | null>(null)

  useEffect(() => {
    let active = true
    listProjects()
      .then((projects) => {
        if (active) setLoad({ status: 'ready', projects })
      })
      .catch((err: unknown) => {
        if (active) setLoad({ status: 'error', message: errorMessage(err) })
      })
    return () => {
      active = false
    }
  }, [])

  const countIn = (projectId: string | null) =>
    tracking.status === 'ready' ? tracking.papers.filter((paper) => paper.project_id === projectId).length : null

  const newAlertsIn = (projectId: string | null) => {
    if (tracking.status !== 'ready') return []
    const paperIds = new Set(tracking.papers.filter((paper) => paper.project_id === projectId).map((paper) => paper.id))
    return tracking.alerts.filter((alert) => paperIds.has(alert.paper_id) && alert.status === 'new')
  }

  const update = (change: (projects: Project[]) => Project[]) =>
    setLoad((current) => (current.status === 'ready' ? { status: 'ready', projects: change(current.projects) } : current))

  const create = async (name: string) => {
    const project = await createProject(name)
    // newest first, as GET /projects lists them
    update((projects) => [project, ...projects])
    setCreating(false)
  }

  const rename = async (id: string, name: string) => {
    const project = await renameProject(id, name)
    update((projects) => projects.map((p) => (p.id === id ? project : p)))
    setRenamingId(null)
  }

  const remove = async (project: Project) => {
    await deleteProject(project.id)
    update((projects) => projects.filter((p) => p.id !== project.id))
    setDeleting(null)
    showToast({ tone: 'success', message: `Deleted "${project.name}"` })
    // its papers went with it
    await refresh()
  }

  return (
    <Page>
      <PageHeader
        title="Projects"
        meta={[
          load.status === 'ready' && `${load.projects.length + 1} project${load.projects.length === 0 ? '' : 's'}`,
          tracking.status === 'ready' && `${tracking.papers.length} paper${tracking.papers.length === 1 ? '' : 's'} tracked`,
          tracking.status === 'ready' && (
            <NewAlertsNote count={tracking.alerts.filter((alert) => alert.status === 'new').length} />
          ),
        ]}
        description="Each project keeps its own tracked papers and draft."
        actions={
          <ActionButton tone="primary" disabled={load.status !== 'ready'} onClick={() => setCreating(true)}>
            <PlusIcon aria-hidden="true" className="size-4" />
            New project
          </ActionButton>
        }
      />

      {load.status === 'error' && (
        <p className={`animate-reveal mb-4 ${errorPanel}`}>Couldn't load your projects: {load.message}</p>
      )}

      <ul role="list" className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,20rem),1fr))] gap-4">
        {creating && (
          <li className="animate-rise">
            <NameForm label="New project name" submit="Create" onSubmit={create} onCancel={() => setCreating(false)} />
          </li>
        )}
        {load.status === 'ready' &&
          load.projects.map((project, i) => (
            <li key={project.id} className="animate-rise" style={riseDelay(i + 1)}>
              {renamingId === project.id ? (
                <NameForm
                  label={`Rename ${project.name}`}
                  initial={project.name}
                  submit="Save"
                  onSubmit={(name) => rename(project.id, name)}
                  onCancel={() => setRenamingId(null)}
                />
              ) : (
                <ProjectCard
                  to={`/project/${project.id}`}
                  name={project.name}
                  count={countIn(project.id)}
                  newAlerts={newAlertsIn(project.id)}
                >
                  <CardAction label={`Rename ${project.name}`} title="Rename" onClick={() => setRenamingId(project.id)}>
                    <PencilIcon aria-hidden="true" className="size-4" />
                  </CardAction>
                  <CardAction
                    label={`Delete ${project.name}`}
                    title="Delete"
                    danger
                    onClick={() => setDeleting(project)}
                  >
                    <TrashIcon aria-hidden="true" className="size-4" />
                  </CardAction>
                </ProjectCard>
              )}
            </li>
          ))}
        {/* the default project has no row: it can't be renamed or deleted (docs/CONTRACTS.md, "Projects") */}
        <li className="animate-rise" style={riseDelay(load.status === 'ready' ? load.projects.length + 1 : 1)}>
          <ProjectCard to="/project" name="Default project" count={countIn(null)} newAlerts={newAlertsIn(null)} />
        </li>
      </ul>

      <DeleteDialog
        project={deleting}
        count={deleting ? countIn(deleting.id) : null}
        onConfirm={remove}
        onClose={() => setDeleting(null)}
      />
    </Page>
  )
}

// the strip's right end: how many alerts are waiting, in the alert's own words
export function NewAlertsNote({ count }: { count: number }) {
  if (count === 0) return <span>No new alerts</span>
  return (
    <Link to="/alerts" className={`inline-flex items-center gap-1.5 ${signalLink}`}>
      <span className="size-1.5 rounded-full bg-red-400" aria-hidden="true" />
      {count} new alert{count === 1 ? '' : 's'}
    </Link>
  )
}

function ProjectCard({
  to,
  name,
  count,
  newAlerts,
  children,
}: {
  to: string
  name: string
  count: number | null
  newAlerts: Alert[]
  children?: ReactNode
}) {
  const severity = highestNewAlertSeverity(newAlerts)

  return (
    <div className={`${cardInteractive} group relative flex h-full min-h-44 flex-col p-5`}>
      <div className="flex min-h-8 items-start justify-between gap-3">
        <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-indigo-500/10 text-indigo-300 transition-colors group-hover:bg-indigo-500/20">
          <FolderIcon aria-hidden="true" className="size-6" />
        </span>
        {children && <div className="-mt-1 -mr-1 flex shrink-0 gap-1">{children}</div>}
      </div>
      <Link
        to={to}
        title={name}
        className="mt-4 line-clamp-2 text-base/6 font-semibold break-words text-white after:absolute after:inset-0 focus:outline-none"
      >
        {name}
      </Link>
      <div className="mt-auto flex min-h-5 flex-wrap items-center gap-x-4 pt-3 text-sm/5 text-gray-400">
        <p>{paperCount(count)}</p>
        {severity && (
          <p className="flex items-center gap-x-1.5 text-gray-200">
            <svg viewBox="0 0 6 6" aria-hidden="true" className={`size-1.5 ${ALERT_SEVERITY_DOT_CLASS[severity]}`}>
              <circle r={3} cx={3} cy={3} />
            </svg>
            {newAlerts.length} new alert{newAlerts.length === 1 ? '' : 's'}
          </p>
        )}
      </div>
    </div>
  )
}

// above the card's stretched link, so it doesn't open the project
function CardAction({
  label,
  title,
  danger = false,
  onClick,
  children,
}: {
  label: string
  title: string
  danger?: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={title}
      onClick={onClick}
      className={`${iconButton} ${danger ? 'hover:text-red-400' : ''}`}
    >
      {children}
    </button>
  )
}

function NameForm({
  label,
  initial = '',
  submit,
  onSubmit,
  onCancel,
}: {
  label: string
  initial?: string
  submit: string
  onSubmit: (name: string) => Promise<void>
  onCancel: () => void
}) {
  const [name, setName] = useState(initial)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await onSubmit(name.trim())
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <form
      onSubmit={(event) => void handleSubmit(event)}
      onKeyDown={(event) => {
        if (event.key === 'Escape') onCancel()
      }}
      className={`${card} flex h-full min-h-44 flex-col justify-center p-5 outline-indigo-400/60`}
    >
      <input
        autoFocus
        aria-label={label}
        aria-invalid={error !== null}
        value={name}
        maxLength={100}
        placeholder="Project name"
        onChange={(event) => setName(event.target.value)}
        onFocus={(event) => event.target.select()}
        className={inputClasses}
      />
      {error && <p className="mt-1.5 px-1 text-xs/5 text-red-400">{error}</p>}
      <div className="mt-3 flex justify-end gap-2">
        <ActionButton tone="quiet" onClick={onCancel}>
          Cancel
        </ActionButton>
        <ActionButton type="submit" tone="primary" disabled={busy || name.trim().length === 0}>
          {submit}
        </ActionButton>
      </div>
    </form>
  )
}

function DeleteDialog({
  project,
  count,
  onConfirm,
  onClose,
}: {
  project: Project | null
  count: number | null
  onConfirm: (project: Project) => Promise<void>
  onClose: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const close = () => {
    setError(null)
    onClose()
  }

  const confirm = async () => {
    if (!project) return
    setBusy(true)
    setError(null)
    try {
      await onConfirm(project)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const papers = count === null || count === 0 ? 'Its tracked papers' : `Its ${paperCount(count)}`

  return (
    <Dialog open={project !== null} onClose={close} className="relative z-50">
      <DialogBackdrop transition className="fixed inset-0 bg-black/70 backdrop-blur-sm transition-opacity duration-200 data-closed:opacity-0" />
      <div className={DIALOG_FRAME}>
        <DialogPanel className={`${DIALOG_PANEL} max-w-md`}>
          <DialogTitle className="text-lg/7 font-semibold break-words text-white">Delete "{project?.name}"?</DialogTitle>
          <p className="mt-1 text-sm/6 text-gray-300">
            {papers}, with their alerts and reports, and its draft are deleted too. This can't be undone.
          </p>
          {error && <p className={`mt-4 ${errorPanel}`}>{error}</p>}
          <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <ActionButton tone="quiet" onClick={close}>
              Cancel
            </ActionButton>
            <ActionButton tone="danger" disabled={busy} onClick={() => void confirm()}>
              Delete project
            </ActionButton>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  )
}

export default Projects
