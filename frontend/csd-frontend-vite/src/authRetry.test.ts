import axios, { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { withAuth } from './authRetry'

// a server that answers each request with the next status in `statuses` (the last one repeats),
// recording the Authorization header of every request
function server(statuses: number[]) {
  const seen: (string | undefined)[] = []
  const adapter: AxiosAdapter = (config: InternalAxiosRequestConfig) => {
    seen.push(config.headers.Authorization as string | undefined)
    const status = statuses[Math.min(seen.length - 1, statuses.length - 1)]
    const response = { status, statusText: '', data: { ok: status < 400 }, headers: {}, config }
    if (status < 400) return Promise.resolve(response)
    return Promise.reject(new AxiosError(`status ${status}`, 'ERR_BAD_REQUEST', config, null, response))
  }
  return { adapter, seen }
}

function setUp(statuses: number[], refresh: () => Promise<unknown>) {
  let token: string | null = 'old'
  const { adapter, seen } = server(statuses)
  const onUnauthorized = vi.fn()
  const client = axios.create({ adapter })
  const refreshed = vi.fn(async () => {
    await refresh()
    token = 'new'
  })
  withAuth(client, { getToken: () => token, refresh: refreshed, onUnauthorized })
  return { client, seen, onUnauthorized, refreshed }
}

describe('withAuth', () => {
  it('sends the token with every request', async () => {
    const { client, seen } = setUp([200], async () => {})

    await client.get('/papers')

    expect(seen).toEqual(['Bearer old'])
  })

  it('answers a 401 with one refresh and one retry carrying the new token', async () => {
    const { client, seen, onUnauthorized, refreshed } = setUp([401, 200], async () => {})

    const res = await client.get('/papers')

    expect(res.data).toEqual({ ok: true })
    expect(refreshed).toHaveBeenCalledTimes(1)
    expect(seen).toEqual(['Bearer old', 'Bearer new'])
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('signs out after one retry when the server still answers 401, instead of refreshing again', async () => {
    const { client, seen, onUnauthorized, refreshed } = setUp([401], async () => {})

    await expect(client.get('/papers')).rejects.toMatchObject({ response: { status: 401 } })

    expect(refreshed).toHaveBeenCalledTimes(1)
    expect(seen).toHaveLength(2)
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('signs out without a retry when the refresh fails', async () => {
    const { client, seen, onUnauthorized } = setUp([401], () => Promise.reject(new Error('Please sign in again')))

    await expect(client.get('/papers')).rejects.toMatchObject({ response: { status: 401 } })

    expect(seen).toHaveLength(1)
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('leaves other errors alone', async () => {
    const { client, onUnauthorized, refreshed } = setUp([403], async () => {})

    await expect(client.get('/papers')).rejects.toMatchObject({ response: { status: 403 } })

    expect(refreshed).not.toHaveBeenCalled()
    expect(onUnauthorized).not.toHaveBeenCalled()
  })
})
