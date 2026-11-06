import { useId, type ReactNode } from 'react'

// The man behind and on the belly, in stage units (1600 by 1000). The belly itself is an HTML
// element so it can hold the UI. Each moving part is its own <svg>, so the browser paints its
// filters once and the bounce only moves a finished layer. All of it is decoration, and hidden
// from screen readers. Forms are built from shading, with no outlines.
const SHADE = '#74422a'

function rng(seed: number) {
  let state = seed
  return () => {
    state = (state * 16807) % 2147483647
    return state / 2147483647
  }
}

function hairs(count: number, seed: number, place: (a: number, b: number) => [number, number], length = 13) {
  const next = rng(seed)
  return Array.from({ length: count }, () => {
    const [x, y] = place(next(), next())
    const dx = (next() - 0.5) * length
    const dy = length * (0.5 + next() * 0.6)
    return `M${x.toFixed(0)} ${y.toFixed(0)} q ${dx.toFixed(1)} ${(dy / 2).toFixed(1)} ${(dx * 0.3).toFixed(1)} ${dy.toFixed(1)}`
  }).join(' ')
}

// A finger: a tube that tapers from r1 to r2, with round ends
// Body hair: fine strands that lie toward the navel, denser low on the belly and near its middle
function bodyHair(count: number, seed: number): string {
  const next = rng(seed)
  let out = ''
  for (let i = 0; i < count; i++) {
    const side = next() < 0.5 ? -1 : 1
    const spread = next() * next()
    const x = 590 + side * (40 + spread * 500)
    const y = 760 - next() * next() * 520
    const towardX = (590 - x) / 590
    const length = 6 + next() * 8
    const dx = towardX * length + (next() - 0.5) * 3
    const dy = length * (0.75 + next() * 0.3)
    out += `M${x.toFixed(0)} ${y.toFixed(0)} q ${(dx * 0.2).toFixed(1)} ${(dy * 0.6).toFixed(1)} ${dx.toFixed(1)} ${dy.toFixed(1)} `
  }
  return out
}

function capsule(x1: number, y1: number, x2: number, y2: number, r1: number, r2: number): string {
  const len = Math.hypot(x2 - x1, y2 - y1)
  const nx = -(y2 - y1) / len
  const ny = (x2 - x1) / len
  const f = (n: number) => n.toFixed(1)
  return (
    `M${f(x1 + nx * r1)} ${f(y1 + ny * r1)} L${f(x2 + nx * r2)} ${f(y2 + ny * r2)} ` +
    `A${r2} ${r2} 0 0 0 ${f(x2 - nx * r2)} ${f(y2 - ny * r2)} L${f(x1 - nx * r1)} ${f(y1 - ny * r1)} ` +
    `A${r1} ${r1} 0 0 0 ${f(x1 + nx * r1)} ${f(y1 + ny * r1)} Z`
  )
}

// A filled shape that darkens toward its own edge, which is what makes it read as round
function Flesh({
  d,
  fill,
  blur,
  shade = SHADE,
  depth = 16,
  strength = 0.5,
  children,
}: {
  d: string
  fill: string
  blur: string
  shade?: string
  depth?: number
  strength?: number
  children?: ReactNode
}) {
  const clip = `flesh-${useId().replace(/\W/g, '')}`
  return (
    <>
      <clipPath id={clip}>
        <path d={d} />
      </clipPath>
      <path d={d} fill={fill} />
      <g clipPath={`url(#${clip})`}>
        {children}
        <path d={d} fill="none" stroke={shade} strokeWidth={depth} opacity={strength} filter={`url(#${blur})`} />
      </g>
    </>
  )
}

const HAIRLINE =
  'M724 98 C712 40 752 4 800 4 C848 4 888 40 876 98 C874 80 868 66 856 58 C846 44 824 39 800 44 C776 39 754 44 744 58 C732 66 726 80 724 98 Z'
const FACE =
  'M800 10 C845 10 874 40 878 90 C880 110 892 130 890 154 C886 180 850 200 800 202 C750 200 714 180 710 154 C708 130 720 110 722 90 C726 40 755 10 800 10 Z'

const HAIR_STRANDS = (() => {
  const next = rng(31)
  let out = ''
  for (let i = 0; i < 220; i++) {
    const u = next()
    const edge = Math.abs(u - 0.5) * 2
    const x = 724 + 152 * u
    const y = 46 + edge * edge * 48 + next() * 8
    const lean = (u - 0.5) * 34 + (next() - 0.5) * 8
    out += `M${x.toFixed(0)} ${y.toFixed(0)} q ${(lean / 2).toFixed(1)} ${(-10 - next() * 8).toFixed(1)} ${lean.toFixed(1)} ${(-18 - next() * 22).toFixed(1)} `
  }
  return out
})()

function brow(x1: number, x2: number, seed: number): string {
  const next = rng(seed)
  let out = ''
  for (let i = 0; i < 30; i++) {
    const t = i / 29
    const x = x1 + (x2 - x1) * t
    const y = 91 - Math.sin(t * Math.PI) * 7 + next() * 3
    const dir = x2 > x1 ? 1 : -1
    out += `M${x.toFixed(1)} ${(y + 3).toFixed(1)} l ${(dir * (7 + next() * 4)).toFixed(1)} ${(-1.5 - next() * 2).toFixed(1)} `
  }
  return out
}
const BROWS = brow(744, 778, 11) + brow(856, 822, 23)

function HeadArt() {
  const raw = useId().replace(/\W/g, '')
  const ids = {
    skin: `h-skin-${raw}`,
    hair: `h-hair-${raw}`,
    iris: `h-iris-${raw}`,
    b1: `h-b1-${raw}`,
    b3: `h-b3-${raw}`,
    b7: `h-b7-${raw}`,
    stubble: `h-stubble-${raw}`,
    face: `h-face-${raw}`,
    capped: `h-capped-${raw}`,
    eyes: `h-eyes-${raw}`,
  }
  const blur = (id: string) => `url(#${id})`
  const eyeLeft = 'M750 111 C758 101 778 99 788 112 C778 118 760 119 750 111 Z'
  const eyeRight = 'M812 112 C822 99 842 101 850 111 C840 119 822 118 812 112 Z'

  return (
    <g>
      <defs>
        <radialGradient id={ids.skin} cx="0.46" cy="0.42" r="0.68">
          <stop offset="0" stopColor="#f1c7a6" />
          <stop offset="0.6" stopColor="#e2aa86" />
          <stop offset="1" stopColor="#b47a56" />
        </radialGradient>
        <linearGradient id={ids.hair} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#3b2d24" />
          <stop offset="1" stopColor="#57463a" />
        </linearGradient>
        <radialGradient id={ids.iris}>
          <stop offset="0" stopColor="#8b6a45" />
          <stop offset="0.7" stopColor="#5a4029" />
          <stop offset="1" stopColor="#2c1e13" />
        </radialGradient>
        <filter id={ids.b1} x="-30%" y="-30%" width="160%" height="160%">
          <feGaussianBlur stdDeviation="1.2" />
        </filter>
        <filter id={ids.b3} x="-40%" y="-40%" width="180%" height="180%">
          <feGaussianBlur stdDeviation="3" />
        </filter>
        <filter id={ids.b7} x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="7" />
        </filter>
        <filter id={ids.stubble} x="0" y="0" width="100%" height="100%">
          <feTurbulence type="fractalNoise" baseFrequency="1.5" numOctaves="1" seed="9" />
          <feColorMatrix values="0 0 0 0 0.18  0 0 0 0 0.13  0 0 0 0 0.1  0 0 0 3.4 -1.75" />
          <feComposite in2="SourceAlpha" operator="in" />
        </filter>
        <clipPath id={ids.face}>
          <path d={FACE} />
        </clipPath>
        <clipPath id={ids.capped}>
          <path d={HAIRLINE} />
        </clipPath>
        <clipPath id={ids.eyes}>
          <path d={eyeLeft + eyeRight} />
        </clipPath>
      </defs>

      {/* neck and second chin */}
      <ellipse cx="800" cy="216" rx="72" ry="10" fill="#3a1e10" opacity="0.4" filter={blur(ids.b7)} />
      <Flesh d="M734 150 C728 188 752 214 800 214 C848 214 872 188 866 150 Z" fill="#cf9570" blur={ids.b7} depth={18} strength={0.55} />
      <path d="M744 178 C766 202 834 202 856 178" fill="none" stroke="#6e3f2a" strokeWidth="5" opacity="0.4" filter={blur(ids.b3)} />

      {/* ears */}
      {[
        'M724 96 C712 88 704 96 706 114 C708 132 716 146 726 142 Z',
        'M876 96 C888 88 896 96 894 114 C892 132 884 146 874 142 Z',
      ].map((d) => (
        <Flesh key={d} d={d} fill="#d99f7b" blur={ids.b3} depth={8} strength={0.6} />
      ))}
      <path d="M714 104 C710 116 714 128 720 134 M886 104 C890 116 886 128 880 134" fill="none" stroke="#7d4a30" strokeWidth="4" opacity="0.5" filter={blur(ids.b1)} />

      {/* the face, its planes shaded from the upper left */}
      <path d={FACE} fill={`url(#${ids.skin})`} />
      <g clipPath={`url(#${ids.face})`}>
        <path d={FACE} fill="none" stroke={SHADE} strokeWidth="24" opacity="0.5" filter={blur(ids.b7)} />
        <ellipse cx="786" cy="64" rx="42" ry="18" fill="#fbe5d1" opacity="0.65" filter={blur(ids.b7)} />
        <ellipse cx="768" cy="108" rx="30" ry="16" fill="#8d5839" opacity="0.42" filter={blur(ids.b7)} />
        <ellipse cx="832" cy="108" rx="30" ry="16" fill="#8d5839" opacity="0.42" filter={blur(ids.b7)} />
        <ellipse cx="752" cy="138" rx="22" ry="11" fill="#f9d8bf" opacity="0.6" filter={blur(ids.b7)} />
        <ellipse cx="848" cy="138" rx="20" ry="11" fill="#f9d8bf" opacity="0.45" filter={blur(ids.b7)} />
        <ellipse cx="744" cy="152" rx="20" ry="13" fill="#cf6f58" opacity="0.32" filter={blur(ids.b7)} />
        <ellipse cx="856" cy="152" rx="20" ry="13" fill="#cf6f58" opacity="0.32" filter={blur(ids.b7)} />
        {/* stubble over the lip and jaw */}
        <path d="M722 150 C744 170 764 166 780 164 C794 162 806 162 820 164 C836 166 856 170 878 150 L878 204 L722 204 Z" fill="#000" filter={`url(#${ids.stubble})`} opacity="0.62" />
        {/* folds from nose to mouth */}
        <path d="M772 150 C762 160 758 172 762 184" fill="none" stroke="#74422a" strokeWidth="4" opacity="0.42" filter={blur(ids.b1)} />
        <path d="M828 150 C838 160 842 172 838 184" fill="none" stroke="#74422a" strokeWidth="4" opacity="0.36" filter={blur(ids.b1)} />
      </g>

      {/* hair, soft at the hairline, then strand by strand */}
      <path d={HAIRLINE} fill={`url(#${ids.hair})`} filter={blur(ids.b1)} />
      <g clipPath={`url(#${ids.capped})`}>
        <path d={HAIR_STRANDS} fill="none" stroke="#7c685a" strokeWidth="1.1" opacity="0.65" />
        <path d={HAIR_STRANDS} fill="none" stroke="#221810" strokeWidth="0.9" opacity="0.7" transform="translate(2 1)" />
      </g>

      {/* brows */}
      <path d="M746 94 C758 84 776 84 790 92 C776 90 760 91 748 98 Z M854 94 C842 84 824 84 810 92 C824 90 840 91 852 98 Z" fill="#3a2a20" opacity="0.75" filter={blur(ids.b1)} />
      <path d={BROWS} fill="none" stroke="#2c2018" strokeWidth="1.4" strokeLinecap="round" opacity="0.85" />

      {/* open eyes: lid fold, shaded white, iris, wet highlight, lash line, bag */}
      <g className="dev-eyes-sleepy">
        <path d="M748 104 C760 92 778 92 790 106 M810 106 C822 92 840 92 852 104" fill="none" stroke="#74422a" strokeWidth="2.5" opacity="0.6" filter={blur(ids.b1)} />
        <path d={eyeLeft} fill="#ece2d7" />
        <path d={eyeRight} fill="#ece2d7" />
        <g clipPath={`url(#${ids.eyes})`}>
          <circle cx="770" cy="109" r="9.5" fill={`url(#${ids.iris})`} />
          <circle cx="831" cy="109" r="9.5" fill={`url(#${ids.iris})`} />
          <circle cx="770" cy="109" r="4" fill="#0f0a07" />
          <circle cx="831" cy="109" r="4" fill="#0f0a07" />
          <path d="M748 96 H792 V106 H748 Z M808 96 H852 V106 H808 Z" fill="#3a2115" opacity="0.5" filter={blur(ids.b1)} />
          <ellipse cx="752" cy="112" rx="6" ry="7" fill="#b47a60" opacity="0.5" filter={blur(ids.b1)} />
          <ellipse cx="848" cy="112" rx="6" ry="7" fill="#b47a60" opacity="0.5" filter={blur(ids.b1)} />
        </g>
        <circle cx="773" cy="105.5" r="1.8" fill="#fff" />
        <circle cx="834" cy="105.5" r="1.8" fill="#fff" />
        <path d="M749 111 C758 101 778 99 789 112 C778 103 760 104 749 111 Z M811 112 C822 99 842 101 851 111 C840 104 822 103 811 112 Z" fill="#20140d" />
        <path d="M752 116 C762 122 778 122 788 116 M812 116 C822 122 838 122 848 116" fill="none" stroke="#b5735c" strokeWidth="1.3" opacity="0.8" />
        <path d="M750 124 C764 134 780 132 790 124 M810 124 C820 132 836 134 850 124" fill="none" stroke="#74422a" strokeWidth="3" opacity="0.38" filter={blur(ids.b1)} />
      </g>
      {/* screwed shut when the belly goes */}
      <g className="dev-eyes-happy">
        <path d="M748 112 C760 102 778 102 790 113 C778 108 760 108 748 112 Z M810 113 C822 102 840 102 852 112 C840 108 822 108 810 113 Z" fill="#20140d" />
        <path d="M744 116 l -9 4 M746 122 l -8 7 M856 116 l 9 4 M854 122 l 8 7" fill="none" stroke="#74422a" strokeWidth="1.6" opacity="0.7" strokeLinecap="round" />
      </g>

      {/* nose: bridge, side planes, ball, wings, the shadow under it */}
      <path d="M788 106 C784 122 780 136 776 146 C784 142 786 124 792 108 Z M812 106 C816 122 820 136 824 146 C816 142 814 124 808 108 Z" fill="#7c4a30" opacity="0.6" filter={blur(ids.b3)} />
      <ellipse cx="800" cy="166" rx="30" ry="5.5" fill="#5e3320" opacity="0.5" filter={blur(ids.b3)} />
      <ellipse cx="777" cy="152" rx="12" ry="10" fill="#d59a75" filter={blur(ids.b1)} />
      <ellipse cx="823" cy="152" rx="12" ry="10" fill="#cf9470" filter={blur(ids.b1)} />
      <ellipse cx="800" cy="149" rx="18" ry="15" fill="#e3ad88" filter={blur(ids.b1)} />
      <path d="M800 104 L800 136" stroke="#fbe2cc" strokeWidth="7" strokeLinecap="round" opacity="0.4" filter={blur(ids.b3)} />
      <ellipse cx="797" cy="145" rx="7" ry="4.5" fill="#fff4e8" opacity="0.38" filter={blur(ids.b1)} />
      <ellipse cx="788" cy="159" rx="5.5" ry="3" fill="#3f1d12" filter={blur(ids.b1)} />
      <ellipse cx="812" cy="159" rx="5.5" ry="3" fill="#3f1d12" filter={blur(ids.b1)} />

      {/* closed smile: upper lip in shade, lower lip catching light */}
      <g className="dev-smile">
        <path d="M766 176 C782 168 794 167 800 171 C806 167 818 168 834 176 C820 181 780 181 766 176 Z" fill="#94503f" filter={blur(ids.b1)} />
        <path d="M772 179 C784 195 816 195 828 179 C812 183 788 183 772 179 Z" fill="#b5705c" filter={blur(ids.b1)} />
        <ellipse cx="798" cy="186" rx="11" ry="2.5" fill="#e3ab98" opacity="0.65" filter={blur(ids.b1)} />
        <path d="M764 176 C786 181 814 181 836 176" fill="none" stroke="#3f1d12" strokeWidth="1.6" strokeLinecap="round" />
        <ellipse cx="800" cy="198" rx="20" ry="3" fill="#5e3320" opacity="0.4" filter={blur(ids.b3)} />
      </g>
      <g className="dev-grin">
        <path d="M764 166 C784 172 816 172 836 166 C832 190 816 197 800 197 C784 197 768 190 764 166 Z" fill="#3a1410" />
        <path d="M770 169 C786 174 814 174 830 169 L826 179 C812 182 788 182 774 179 Z" fill="#d9cdbb" />
        <ellipse cx="800" cy="192" rx="16" ry="5" fill="#a64a44" filter={blur(ids.b1)} />
      </g>
    </g>
  )
}

export function HeadSmall() {
  return (
    <svg className="dev-head-small" data-part="head" viewBox="680 -2 240 226" aria-hidden="true">
      <HeadArt />
    </svg>
  )
}

function Layer({ part, children }: { part?: string; children: ReactNode }) {
  return (
    <svg className="dev-guy" data-part={part} viewBox="0 0 1600 1000" aria-hidden="true">
      {children}
    </svg>
  )
}

export function GuyBehind() {
  const shoe = (x: number) => (
    <g key={x} transform={`translate(${x} -16)`}>
      {/* sole, then the toe box coming toward us, then laces */}
      <path d="M-92 980 C-96 992 -86 998 -74 998 L74 998 C86 998 96 992 92 980 Z" fill="url(#guy-sole)" />
      <Flesh d="M-90 984 C-98 958 -60 938 -38 934 L38 934 C60 938 98 958 90 984 Z" fill="#4b4038" shade="#15100c" blur="guy-b6" depth={14} strength={0.65} />
      <path d="M-70 982 C-72 964 -44 958 0 958 C44 958 72 964 70 982 Z" fill="#cfc8bb" />
      <ellipse cx="-18" cy="968" rx="30" ry="6" fill="#fff" opacity="0.45" filter="url(#guy-b3)" />
      <path d="M-24 938 L24 948 M24 938 L-24 948 M-26 948 L26 956 M26 948 L-26 956" stroke="#b9b1a4" strokeWidth="2.5" strokeLinecap="round" />
    </g>
  )
  return (
    <>
      <svg className="dev-guy dev-guy-defs" viewBox="0 0 1600 1000" aria-hidden="true">
        <defs>
          <filter id="guy-b3" x="-40%" y="-40%" width="180%" height="180%">
            <feGaussianBlur stdDeviation="3" />
          </filter>
          <filter id="guy-b6" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="6" />
          </filter>
          <filter id="guy-b14" x="-60%" y="-60%" width="220%" height="220%">
            <feGaussianBlur stdDeviation="14" />
          </filter>
          <filter id="guy-weave" x="0" y="0" width="100%" height="100%">
            <feTurbulence type="fractalNoise" baseFrequency="0.5 1.4" numOctaves="2" seed="3" />
            <feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 1.2 -0.5" />
            <feComposite in2="SourceAlpha" operator="in" />
          </filter>
          <linearGradient id="guy-denim-left" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stopColor="#2b4560" />
            <stop offset="0.55" stopColor="#6387ad" />
            <stop offset="1" stopColor="#2e4a67" />
          </linearGradient>
          <linearGradient id="guy-floor" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#2d241c" />
            <stop offset="1" stopColor="#120e0a" />
          </linearGradient>
          <linearGradient id="guy-sole" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#f2ede4" />
            <stop offset="1" stopColor="#a39b8f" />
          </linearGradient>
          <linearGradient id="guy-tee" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#d6dce1" />
            <stop offset="1" stopColor="#a2adb6" />
          </linearGradient>
          <linearGradient id="guy-glass" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stopColor="#8a4f06" />
            <stop offset="0.3" stopColor="#d98f15" />
            <stop offset="0.65" stopColor="#efab2b" />
            <stop offset="1" stopColor="#94560a" />
          </linearGradient>
        </defs>
      </svg>

      <Layer>
        <rect x="-2000" y="936" width="5600" height="600" fill="url(#guy-floor)" />
        <rect x="-2000" y="930" width="5600" height="8" fill="#08201a" />
        <ellipse cx="800" cy="976" rx="250" ry="11" fill="#000" opacity="0.75" filter="url(#guy-b6)" />
        {/* jeans: the left leg carries his weight, the right is set out a little */}
        <path d="M592 820 L796 820 L780 950 L640 950 Z" fill="url(#guy-denim-left)" />
        <path d="M804 820 L1008 820 L992 946 L838 946 Z" fill="url(#guy-denim-left)" />
        <path d="M592 820 L796 820 L780 950 L640 950 Z M804 820 L1008 820 L992 946 L838 946 Z" fill="#000" filter="url(#guy-weave)" opacity="0.1" />
        <path d="M652 944 C690 934 730 950 772 938 M850 940 C890 950 940 932 984 942" fill="none" stroke="#101a26" strokeWidth="6" opacity="0.55" filter="url(#guy-b3)" />
        <path d="M660 928 C700 922 736 932 768 926" fill="none" stroke="#6f8db0" strokeWidth="3" opacity="0.4" filter="url(#guy-b3)" />
        <path d="M800 820 L800 930" stroke="#0a1420" strokeWidth="14" opacity="0.7" filter="url(#guy-b3)" />
        {/* belt, just showing under the overhang, and the belly's shadow falling on it */}
        <path d="M616 890 L990 890 C996 898 996 910 988 916 L618 916 C610 910 610 898 616 890 Z" fill="#3b2a1e" />
        <path d="M618 893 L988 893" stroke="#6a4e39" strokeWidth="2" opacity="0.7" />
        <ellipse cx="800" cy="892" rx="245" ry="18" fill="#050a10" opacity="0.6" filter="url(#guy-b14)" />
        {shoe(708)}
        {shoe(916)}
      </Layer>

      <Layer part="torso">
        <Flesh
          d="M704 116 C620 130 512 142 442 182 C384 222 330 290 300 344 L1300 344 C1270 290 1218 222 1158 182 C1088 142 980 130 896 116 C860 146 740 146 704 116 Z"
          fill="url(#guy-tee)"
          shade="#4f5a64"
          blur="guy-b14"
          depth={40}
          strength={0.5}
        >
          {/* tension folds pulling down toward the hem */}
          <path d="M540 176 C600 196 650 196 690 176 M1064 170 C1010 196 950 198 908 178 M470 214 C540 236 600 228 650 204 M1130 214 C1070 240 1010 232 950 206" fill="none" stroke="#5c6771" strokeWidth="12" opacity="0.5" filter="url(#guy-b6)" />
          <path d="M530 190 C590 208 640 208 680 190 M1054 186 C1006 208 950 208 916 192" fill="none" stroke="#f0f4f6" strokeWidth="8" opacity="0.6" filter="url(#guy-b6)" />
        </Flesh>
        <path d="M712 126 C746 156 854 156 888 126" fill="none" stroke="#8d98a2" strokeWidth="10" />
      </Layer>
    </>
  )
}

function Nail({ cx, cy, angle }: { cx: number; cy: number; angle: number }) {
  return (
    <g transform={`rotate(${angle} ${cx} ${cy})`}>
      <rect x={cx - 7} y={cy - 6.5} width="14" height="13" rx="5.5" fill="#ecc6ad" />
      <rect x={cx + 2} y={cy - 5.5} width="3.5" height="11" rx="1.7" fill="#f8e6d8" opacity="0.6" />
    </g>
  )
}

export function GuyInFront() {
  const fingers: [number, number, number, number, number, number][] = [
    [312, 590, 346, 577, 12.5, 10],
    [318, 612, 362, 604, 13, 10.5],
    [316, 636, 356, 634, 12.5, 10],
    [306, 658, 334, 665, 10.5, 8.5],
  ]
  const grip: [number, number, number, number, number, number][] = [
    [1384, 538, 1306, 530, 14, 12],
    [1388, 564, 1290, 560, 14.5, 12.5],
    [1388, 590, 1298, 590, 14, 12],
    [1382, 614, 1322, 618, 12, 10.5],
  ]
  return (
    <>
      <Layer part="arm-left">
        {/* his left arm, in one piece, sleeve over it, hand flat on the belly */}
        <ellipse cx="322" cy="640" rx="66" ry="48" fill="#4b2210" opacity="0.42" filter="url(#guy-b14)" />
        <Flesh d="M440 184 C372 214 262 322 178 448 C156 490 168 528 200 558 L262 626 C276 640 310 618 320 590 L272 496 C284 440 322 350 420 262 Z" fill="#dda581" blur="guy-b14" depth={30} strength={0.5} />
        <path d={hairs(38, 13, (a, b) => [196 + a * 80 + b * 40, 400 + b * 200], 9)} fill="none" stroke="#4d3526" strokeWidth="0.8" opacity="0.5" />
        <Flesh d="M440 180 C396 200 342 248 296 304 C312 332 336 350 358 356 C374 320 398 288 430 260 C452 240 462 198 440 180 Z" fill="#c4ccd3" shade="#4f5a64" blur="guy-b6" depth={20} strength={0.5} />
        {/* thumb, back of the hand, then fingers */}
        <Flesh d={capsule(290, 590, 332, 548, 14, 11)} fill="#dfa883" blur="guy-b3" depth={8} strength={0.6} />
        <Nail cx={329} cy={551} angle={-45} />
        <Flesh d="M254 612 C258 584 296 568 324 582 C336 604 336 650 316 672 C292 682 262 664 254 640 Z" fill="#e2ad8a" blur="guy-b6" depth={18} strength={0.5}>
          <path d="M300 596 l 24 -4 M304 618 l 24 -3 M302 640 l 22 0" stroke="#f6d3ba" strokeWidth="7" strokeLinecap="round" opacity="0.6" filter="url(#guy-b3)" />
        </Flesh>
        <path d={hairs(9, 77, (a, b) => [268 + a * 34, 588 + b * 60], 8)} fill="none" stroke="#4d3526" strokeWidth="0.9" opacity="0.5" />
        {fingers.map(([x1, y1, x2, y2, r1, r2]) => (
          <g key={y1}>
            <Flesh d={capsule(x1, y1, x2, y2, r1, r2)} fill="#e6b391" blur="guy-b3" depth={7} strength={0.65} />
            <ellipse cx={x1 + 4} cy={y1} rx="10" ry={r1 - 2} fill="#f3caac" opacity="0.55" filter="url(#guy-b3)" />
            <path d={`M${(x1 + x2) / 2 - 1} ${(y1 + y2) / 2 - 7} q 2 7 0 14`} fill="none" stroke="#8d5839" strokeWidth="0.8" opacity="0.4" />
            <Nail cx={x2 + 2} cy={y2} angle={(Math.atan2(y2 - y1, x2 - x1) * 180) / Math.PI} />
          </g>
        ))}
      </Layer>

      <Layer part="arm-right">
        {/* his right arm, in one piece, coming round to the glass */}
        <Flesh d="M1160 184 C1228 214 1340 316 1424 436 C1456 474 1454 510 1444 556 C1440 590 1430 612 1408 618 C1392 620 1382 610 1376 596 L1336 494 C1316 440 1280 350 1180 262 Z" fill="#d9a07c" blur="guy-b14" depth={30} strength={0.5} />
        <path d={hairs(38, 59, (a, b) => [1372 + a * 66 - (1 - b) * 40, 400 + b * 200], 9)} fill="none" stroke="#4d3526" strokeWidth="0.8" opacity="0.5" />
        <Flesh d="M1160 180 C1204 200 1258 248 1304 304 C1288 332 1264 350 1242 356 C1226 320 1202 288 1170 260 C1148 240 1138 198 1160 180 Z" fill="#c4ccd3" shade="#4f5a64" blur="guy-b6" depth={20} strength={0.5} />

        {/* a pint resting on the belly */}
        <ellipse cx="1322" cy="680" rx="62" ry="16" fill="#4b2210" opacity="0.5" filter="url(#guy-b14)" />
        <path d="M1270 470 L1374 470 L1362 668 C1350 680 1294 680 1282 668 Z" fill="url(#guy-glass)" />
        <path d="M1283 664 C1294 676 1350 676 1361 664 L1362 672 C1350 684 1294 684 1282 672 Z" fill="#fbe6b7" opacity="0.75" />
        {[0, 1, 2, 3, 4, 5, 6, 7, 8, 9].map((i) => (
          <circle key={i} cx={1290 + ((i * 37) % 62)} cy={650 - i * 16} r={1 + (i % 3) * 0.6} fill="#ffe9b4" opacity="0.8" />
        ))}
        {/* foam */}
        <path d="M1268 500 C1258 478 1276 456 1296 462 C1306 446 1336 446 1346 460 C1366 454 1386 478 1376 500 C1370 512 1352 506 1346 512 C1336 520 1322 508 1312 514 C1298 522 1290 506 1282 510 C1276 512 1272 508 1268 500 Z" fill="#fbf4e4" />
        <ellipse cx="1320" cy="488" rx="44" ry="11" fill="#c8b389" opacity="0.32" filter="url(#guy-b6)" />
        {/* reflections on the glass */}
        <path d="M1286 476 L1294 656" stroke="#fff" strokeWidth="6" opacity="0.5" strokeLinecap="round" filter="url(#guy-b3)" />
        <path d="M1362 480 L1354 650" stroke="#fff" strokeWidth="2.5" opacity="0.4" strokeLinecap="round" />

        {/* his right hand around it */}
        <Flesh d="M1432 600 C1436 566 1414 528 1380 518 C1362 530 1356 610 1370 634 C1390 642 1420 630 1432 600 Z" fill="#dca581" blur="guy-b6" depth={20} strength={0.55} />
        <Flesh d={capsule(1388, 522, 1330, 500, 14, 11)} fill="#e0a985" blur="guy-b3" depth={8} strength={0.6} />
        <Nail cx={1333} cy={501} angle={200} />
        {grip.map(([x1, y1, x2, y2, r1, r2]) => (
          <g key={y1}>
            <Flesh d={capsule(x1, y1, x2, y2, r1, r2)} fill="#e6b391" blur="guy-b3" depth={8} strength={0.65} />
            <ellipse cx={x1 - 4} cy={y1} rx="11" ry={r1 - 2} fill="#f3caac" opacity="0.55" filter="url(#guy-b3)" />
            <path d={`M${(x1 + x2) / 2 + 8} ${(y1 + y2) / 2 - 8} q -2 8 0 16`} fill="none" stroke="#8d5839" strokeWidth="0.8" opacity="0.4" />
            <Nail cx={x2 - 1} cy={y2} angle={(Math.atan2(y2 - y1, x2 - x1) * 180) / Math.PI} />
          </g>
        ))}
      </Layer>

      <svg className="dev-guy" data-part="head" viewBox="0 0 1600 1000" aria-hidden="true">
        <HeadArt />
      </svg>
    </>
  )
}

const TRAIL_HAIR = hairs(18, 7, (a, b) => [590 + (a - 0.5) * (8 + b * 18), 734 + b * 12], 7)
const BODY_HAIR = bodyHair(420, 19)

// Painted on the belly, under the UI, and moves with it
export function BellyDetail() {
  return (
    <svg className="dev-belly-detail" viewBox="0 0 1180 760" preserveAspectRatio="xMidYMax slice" aria-hidden="true">
      <defs>
        <filter id="belly-b4" x="-40%" y="-40%" width="180%" height="180%">
          <feGaussianBlur stdDeviation="4" />
        </filter>
        <filter id="belly-b9" x="-20%" y="-40%" width="140%" height="180%">
          <feGaussianBlur stdDeviation="9" />
        </filter>
        {/* skin relief: fine noise lit from the upper left */}
        <filter id="belly-pores" x="0" y="0" width="100%" height="100%">
          <feTurbulence type="fractalNoise" baseFrequency="0.55" numOctaves="3" seed="4" result="noise" />
          <feDiffuseLighting in="noise" surfaceScale="1.3" lightingColor="#fff" diffuseConstant="1">
            <feDistantLight azimuth="235" elevation="62" />
          </feDiffuseLighting>
        </filter>
        {/* blotchy colour under the skin */}
        <filter id="belly-mottle" x="0" y="0" width="100%" height="100%">
          <feTurbulence type="fractalNoise" baseFrequency="0.012 0.018" numOctaves="4" seed="12" />
          <feColorMatrix values="0 0 0 0 0.72  0 0 0 0 0.26  0 0 0 0 0.18  0 0 0 2.6 -1.05" />
        </filter>
        <linearGradient id="belly-tee" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#c9d1d7" />
          <stop offset="1" stopColor="#b9c3ca" />
        </linearGradient>
      </defs>

      <rect width="1180" height="760" filter="url(#belly-mottle)" opacity="0.16" />
      <rect width="1180" height="760" filter="url(#belly-pores)" opacity="0.5" style={{ mixBlendMode: 'soft-light' }} />

      <g fill="none" stroke="#3f2a1e" strokeLinecap="round">
        <path d={BODY_HAIR} strokeWidth="0.7" opacity="0.34" />
        <path d={TRAIL_HAIR} strokeWidth="0.9" opacity="0.5" />
      </g>

      {/* navel: a dimple with a hooded top edge */}
      <g className="dev-belly-navel" transform="translate(0 8)">
        <ellipse cx="590" cy="708" rx="34" ry="16" fill="#7c4a30" opacity="0.4" filter="url(#belly-b9)" />
        <path d="M578 704 C582 697 598 697 602 704 C605 714 598 722 590 723 C582 722 575 714 578 704 Z" fill="#5a2e1c" filter="url(#belly-b4)" />
        <path d="M581 708 C585 703 595 703 599 708 C598 716 594 719 590 720 C586 719 582 716 581 708 Z" fill="#3a1b0f" />
        <path d="M574 703 C582 692 598 692 606 703 C598 698 582 698 574 703 Z" fill="#f4d0b5" opacity="0.8" filter="url(#belly-b4)" />
        <path d="M574 720 C582 732 598 732 606 720" fill="none" stroke="#f4d0b5" strokeWidth="3" opacity="0.5" filter="url(#belly-b4)" />
      </g>

      {/* the tee, pulled up by the belly: the shadow it throws, its body, folds, rolled hem */}
      <g className="dev-belly-tee">
        <path d="M-20 166 C230 31 950 31 1200 166" fill="none" stroke="#5a2c17" strokeWidth="22" opacity="0.3" filter="url(#belly-b9)" />
        <path d="M-20 -20 H1200 V150 C950 15 230 15 -20 150 Z" fill="url(#belly-tee)" />
        <path d="M150 88 C190 70 214 46 226 14 M320 48 C340 34 356 18 366 -6 M1030 88 C990 70 966 46 954 14 M860 48 C840 34 824 18 814 -6" fill="none" stroke="#5c6771" strokeWidth="9" opacity="0.45" filter="url(#belly-b4)" />
        <path d="M-20 150 C230 15 950 15 1200 150" fill="none" stroke="#eef2f4" strokeWidth="7" opacity="0.7" filter="url(#belly-b4)" />
        <path d="M-20 154 C230 19 950 19 1200 154" fill="none" stroke="#66717b" strokeWidth="4" opacity="0.8" filter="url(#belly-b4)" />
      </g>
    </svg>
  )
}
