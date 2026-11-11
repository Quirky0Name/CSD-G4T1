// The token every User Management, Storage and Updating client sends, and what a 401 does: one
// refresh (refresh.ts), then the request once more with the new token. A second 401 for the same
// request, or a failed refresh, signs out, so a service that keeps refusing a fresh token can never
// make the tab refresh in a loop.
import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'

type AuthDeps = {
  getToken: () => string | null
  // renews the stored token; rejects when the refresh token no longer works
  refresh: () => Promise<unknown>
  onUnauthorized: () => void
}

// a flag on the config, not a set of config objects: axios merges the config into a new object
// when the request is sent again, and its own properties are copied across, so this one survives
type RetriableConfig = InternalAxiosRequestConfig & { retriedAfterRefresh?: boolean }

export function withAuth(client: AxiosInstance, { getToken, refresh, onUnauthorized }: AuthDeps): void {
  client.interceptors.request.use((config) => {
    const token = getToken()
    if (token) config.headers.Authorization = `Bearer ${token}`
    return config
  })
  client.interceptors.response.use(undefined, async (err: unknown) => {
    if (!axios.isAxiosError(err) || err.response?.status !== 401 || !err.config) throw err
    const config: RetriableConfig = err.config
    if (config.retriedAfterRefresh || getToken() === null) {
      onUnauthorized()
      throw err
    }
    try {
      await refresh()
    } catch {
      onUnauthorized()
      throw err
    }
    // the request interceptor puts the new token on it
    return client({ ...config, retriedAfterRefresh: true } as RetriableConfig)
  })
}
