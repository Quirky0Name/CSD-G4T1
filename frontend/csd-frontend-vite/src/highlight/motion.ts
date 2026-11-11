// Scrolling to a passage jumps instead of gliding for a reader who asked for less motion
export function scrollMotion(): ScrollBehavior {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
}
