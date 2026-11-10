import { describe, expect, it, vi } from 'vitest'
import { createRefresher, REFRESH_LOCK, type Lock } from './refresh'

// a refresh that finishes only when the test says so
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

// runs the task at once, as navigator.locks does when nothing else holds the lock
const freeLock: Lock = (_name, task) => task()

describe('createRefresher', () => {
  it('shares one request and one result between calls made during a refresh', async () => {
    const pending = deferred<string>()
    const refresh = vi.fn(() => pending.promise)
    const refreshSession = createRefresher(refresh, freeLock)

    const first = refreshSession()
    const second = refreshSession()
    pending.resolve('new-token')

    await expect(Promise.all([first, second])).resolves.toEqual(['new-token', 'new-token'])
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('rejects every waiting caller when the refresh fails', async () => {
    const pending = deferred<string>()
    const refreshSession = createRefresher(() => pending.promise, freeLock)

    const first = refreshSession()
    const second = refreshSession()
    pending.reject(new Error('Please sign in again'))

    await expect(first).rejects.toThrow('Please sign in again')
    await expect(second).rejects.toThrow('Please sign in again')
  })

  it('starts a new refresh for a call after the last one finished, whether it worked or not', async () => {
    const refresh = vi
      .fn<() => Promise<string>>()
      .mockResolvedValueOnce('first')
      .mockRejectedValueOnce(new Error('failed'))
      .mockResolvedValueOnce('third')
    const refreshSession = createRefresher(refresh, freeLock)

    await expect(refreshSession()).resolves.toBe('first')
    await expect(refreshSession()).rejects.toThrow('failed')
    await expect(refreshSession()).resolves.toBe('third')
    expect(refresh).toHaveBeenCalledTimes(3)
  })

  it('takes the shared lock for every refresh, so tabs never refresh at once', async () => {
    const names: string[] = []
    const lock: Lock = (name, task) => {
      names.push(name)
      return task()
    }
    const refreshSession = createRefresher(() => Promise.resolve('token'), lock)

    await refreshSession()
    await refreshSession()

    expect(names).toEqual(['refresh-session', 'refresh-session'])
    expect(REFRESH_LOCK).toBe('refresh-session')
  })

  it('waits for the lock before refreshing', async () => {
    const held = deferred<void>()
    const refresh = vi.fn(() => Promise.resolve('token'))
    // another tab holds the lock until `held` resolves
    const busyLock: Lock = (_name, task) => held.promise.then(task)
    const refreshSession = createRefresher(refresh, busyLock)

    const result = refreshSession()
    await Promise.resolve()
    expect(refresh).not.toHaveBeenCalled()
    held.resolve()

    await expect(result).resolves.toBe('token')
    expect(refresh).toHaveBeenCalledTimes(1)
  })
})
