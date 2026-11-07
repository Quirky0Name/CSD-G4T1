import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Dialog, DialogBackdrop, DialogPanel, DialogTitle, Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'
import { EllipsisVerticalIcon, PlusIcon } from '@heroicons/react/20/solid'
import { FolderIcon } from '@heroicons/react/24/outline'
import { createProject, deleteProject, errorMessage, listProjects, renameProject, type Project } from '../api'
import { ActionButton } from '../components/ActionButton'
import { useToast } from '../components/Toasts'
import { useTracking } from '../tracking'

const CARD = 'rounded-md bg-gray-800/50 outline -outline-offset-1 outline-white/10'

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
    <main className="px-4 py-10 sm:px-6 lg:px-8">
      <div className="mb-6 flex items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl/7 font-bold text-white sm:text-3xl sm:tracking-tight">Projects</h1>
          <p className="mt-2 text-sm/6 text-gray-400">Each project has its own tracked papers and draft.</p>
        </div>
        <ActionButton tone="primary" disabled={load.status !== 'ready'} onClick={() => setCreating(true)}>
          <PlusIcon aria-hidden="true" className="size-4" />
          New project
        </ActionButton>
      </div>

      {load.status === 'error' && (
        <p className="mb-4 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-400 outline outline-red-500/20">
          Couldn't load your projects: {load.message}
        </p>
      )}

      <ul role="list" className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,18rem),1fr))] gap-3">
        {creating && (
          <li>
            <NameForm label="New project name" submit="Create" onSubmit={create} onCancel={() => setCreating(false)} />
          </li>
        )}
        {load.status === 'ready' &&
          load.projects.map((project) => (
            <li key={project.id}>
              {renamingId === project.id ? (
                <NameForm
                  label={`Rename ${project.name}`}
                  initial={project.name}
                  submit="Save"
                  onSubmit={(name) => rename(project.id, name)}
                  onCancel={() => setRenamingId(null)}
                />
              ) : (
                <ProjectCard to={`/project/${project.id}`} name={project.name} count={countIn(project.id)}>
                  <Menu>
                    <MenuButton className="relative z-10 flex size-10 items-center justify-center rounded-md text-gray-400 hover:bg-white/10 hover:text-white focus-visible:outline-2 focus-visible:outline-indigo-500 sm:size-8">
                      <span className="sr-only">Options for {project.name}</span>
                      <EllipsisVerticalIcon aria-hidden="true" className="size-5" />
                    </MenuButton>
                    <MenuItems
                      anchor={{ to: 'bottom end', gap: 4, padding: 8 }}
                      className="z-50 w-36 rounded-md bg-gray-800 py-1 shadow-lg outline outline-white/10 focus:outline-none"
                    >
                      <MenuItem>
                        <button
                          type="button"
                          onClick={() => setRenamingId(project.id)}
                          className="block w-full px-3 py-2 text-left text-white data-focus:bg-white/5"
                        >
                          <span className="text-sm/5">Rename</span>
                        </button>
                      </MenuItem>
                      <MenuItem>
                        <button
                          type="button"
                          onClick={() => setDeleting(project)}
                          className="block w-full px-3 py-2 text-left text-red-400 data-focus:bg-white/5"
                        >
                          <span className="text-sm/5">Delete</span>
                        </button>
                      </MenuItem>
                    </MenuItems>
                  </Menu>
                </ProjectCard>
              )}
            </li>
          ))}
        {/* the default project has no row: it can't be renamed or deleted (docs/CONTRACTS.md, "Projects") */}
        <li>
          <ProjectCard to="/project" name="Default project" count={countIn(null)} />
        </li>
      </ul>

      <DeleteDialog
        project={deleting}
        count={deleting ? countIn(deleting.id) : null}
        onConfirm={remove}
        onClose={() => setDeleting(null)}
      />
    </main>
  )
}

function ProjectCard({
  to,
  name,
  count,
  children,
}: {
  to: string
  name: string
  count: number | null
  children?: ReactNode
}) {
  return (
    <div className={`${CARD} relative flex items-center gap-3 py-2 pr-2 pl-4 hover:bg-gray-800 focus-within:outline-indigo-500`}>
      <FolderIcon aria-hidden="true" className="size-6 shrink-0 text-gray-400" />
      <div className="min-w-0 flex-1 py-1">
        <Link to={to} title={name} className="block truncate text-sm/6 font-semibold text-white after:absolute after:inset-0 focus:outline-none">
          {name}
        </Link>
        <p className="h-5 text-xs/5 text-gray-400">{paperCount(count)}</p>
      </div>
      {children}
    </div>
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
      className={`${CARD} p-2`}
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
        className="block w-full rounded-md bg-white/5 px-2.5 py-1.5 text-sm/6 text-white outline -outline-offset-1 outline-white/10 placeholder:text-gray-500 focus:outline-2 focus:-outline-offset-2 focus:outline-indigo-500"
      />
      {error && <p className="mt-1.5 px-1 text-xs/5 text-red-400">{error}</p>}
      <div className="mt-2 flex justify-end gap-2">
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
      <DialogBackdrop className="fixed inset-0 bg-black/70" />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel className="w-full max-w-md rounded-lg border border-white/10 bg-gray-900 p-6 text-gray-100 shadow-lg">
          <DialogTitle className="text-base/7 font-semibold break-words text-white">Delete "{project?.name}"?</DialogTitle>
          <p className="mt-2 text-sm/6 text-gray-300">
            {papers}, with their alerts and reports, and its draft are deleted too. This can't be undone.
          </p>
          {error && <p className="mt-3 text-sm/6 text-red-400">{error}</p>}
          <div className="mt-5 flex justify-end gap-2">
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
