import { useCallback, useEffect, useRef, type RefObject } from 'react'

// The belly is one damped spring. A click pushes it in, it pops back up past rest, and it
// wobbles out. Clicks add to whatever it is already doing, so fast clicking builds up.
const STIFFNESS = 170
const DAMPING = 5.5
const PUSH = 7
const LEAN_PUSH = 34
const HEAD_FOLLOW = 90
const HEAD_DAMPING = 9

function clamp(value: number, limit: number): number {
  return Math.max(-limit, Math.min(limit, value))
}

function setPart(root: HTMLElement, part: string, transform: string) {
  root.querySelectorAll<SVGElement>(`[data-part="${part}"]`).forEach((el) => {
    el.style.transform = transform
  })
}

export function useBounce(rootRef: RefObject<HTMLElement | null>, bellyRef: RefObject<HTMLElement | null>) {
  const spring = useRef({ x: 0, v: 0, lean: 0, leanV: 0, head: 0, headV: 0 })
  const frame = useRef<number | null>(null)
  const last = useRef(0)

  const render = useCallback(() => {
    const root = rootRef.current
    const belly = bellyRef.current
    if (!root || !belly) return
    const { x, lean, head } = spring.current
    const unit = belly.offsetWidth / 1180
    const lift = Math.max(0, x) * 52 * unit
    const scaleY = 1 + 0.15 * x
    const scaleX = 1 - 0.11 * x
    belly.style.transform = `translateY(${-lift}px) rotate(${lean * 0.9}deg) scale(${scaleX}, ${scaleY})`
    setPart(root, 'head', `translateY(${-head * 26 * unit}px) rotate(${lean * 2}deg)`)
    setPart(root, 'torso', `translateY(${-Math.max(0, x) * 22 * unit}px)`)
    // the arms ride the belly's sides as it spreads and narrows
    setPart(root, 'arm-left', `rotate(${-x * 3.2 - lean * 0.9}deg)`)
    setPart(root, 'arm-right', `rotate(${x * 3.2 - lean * 0.9}deg)`)
  }, [rootRef, bellyRef])

  // side is where the click landed across the belly, from -1 (left) to 1 (right)
  const kick = useCallback(
    (side: number) => {
      rootRef.current?.classList.add('is-bouncing')
      if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        window.setTimeout(() => rootRef.current?.classList.remove('is-bouncing'), 500)
        return
      }
      const s = spring.current
      s.v = clamp(s.v - PUSH, 14)
      s.leanV = clamp(s.leanV + side * LEAN_PUSH, 60)
      if (frame.current !== null) return

      const step = (now: number) => {
        const s = spring.current
        const dt = Math.min(0.032, (now - last.current) / 1000)
        last.current = now
        s.v += (-STIFFNESS * s.x - DAMPING * s.v) * dt
        s.x = clamp(s.x + s.v * dt, 1)
        s.leanV += (-STIFFNESS * s.lean - DAMPING * s.leanV) * dt
        s.lean += s.leanV * dt
        s.headV += (HEAD_FOLLOW * (s.x - s.head) - HEAD_DAMPING * s.headV) * dt
        s.head += s.headV * dt
        render()

        const settled = [s.x, s.v, s.lean, s.leanV, s.head, s.headV].every((n) => Math.abs(n) < 0.002)
        if (settled) {
          spring.current = { x: 0, v: 0, lean: 0, leanV: 0, head: 0, headV: 0 }
          render()
          rootRef.current?.classList.remove('is-bouncing')
          frame.current = null
          return
        }
        frame.current = requestAnimationFrame(step)
      }
      last.current = performance.now()
      frame.current = requestAnimationFrame(step)
    },
    [render, rootRef],
  )

  useEffect(
    () => () => {
      if (frame.current !== null) cancelAnimationFrame(frame.current)
    },
    [],
  )

  return kick
}
