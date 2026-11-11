import { useId, type CSSProperties } from 'react'

// The desk's penguin: black and white, drawn flat with a heavy ink line, with a pale sticker edge
// (an SVG dilate, not a second drawing) so the black head holds on the dark ground. Its blush and
// its ? and ! are the app's own pale indigo, so the penguin sits inside the palette, not beside it.
//
// It is never still: it breathes and sways on its feet, tilts its head, blinks, flaps now and then,
// types when it has a laptop, and hops when a pointer comes over it. Each penguin starts its loops
// at a different moment, so two on one screen never move in step.
//
// stand   idle, the default
// wave    hello: sign-in, the first sign-up screen, a fresh profile
// laptop  at work: tracking, empty lists waiting for papers
// read    in spectacles over a paper with one line highlighted: alerts and reports
// think   a question: sign-up questions, pages that don't exist
// alert   arms folded, eyes wide: a destructive confirm, a refused sign-in
// happy   eyes closed, content: all clear
// cheer   leaning back, flippers up, beak open: a finished sign-up
// sit     3/4 view, sitting with a book open on its lap, a page turning now and then
// point   3/4 view, standing, a flipper out towards whatever sits beside it

export type MascotPose = 'stand' | 'wave' | 'laptop' | 'read' | 'think' | 'alert' | 'happy' | 'cheer' | 'sit' | 'point'
export type MascotFace = 'normal' | 'closed' | 'surprised' | 'open'
export type MascotMark = '!' | '?' | null

const INK = '#000000'
const PAPER = '#ffffff'
const EDGE = '#e5e7eb'
const BLUSH = '#a5b4fc' // indigo-300
const SIGNAL = '#818cf8' // indigo-400

const POSE_FACE: Record<MascotPose, MascotFace> = {
  stand: 'normal',
  wave: 'normal',
  laptop: 'normal',
  read: 'normal',
  think: 'normal',
  alert: 'surprised',
  happy: 'closed',
  cheer: 'open',
  sit: 'normal',
  point: 'normal',
}

const POSE_MARK: Partial<Record<MascotPose, MascotMark>> = { think: '?', alert: '!' }

type MascotProps = {
  pose?: MascotPose
  // overrides the pose's own face and mark, e.g. a reading penguin looking up with a !
  face?: MascotFace
  mark?: MascotMark
  glasses?: boolean
  className?: string
  // a still penguin: no loops (marks too small for motion to read)
  still?: boolean
  // drawn without the ground shadow
  noGround?: boolean
}

// a stable offset from the instance's id, so each penguin's loops start somewhere different
function seedDelay(id: string): CSSProperties {
  let hash = 0
  for (const char of id) hash = (hash * 31 + char.charCodeAt(0)) % 9973
  // spread by a prime step, so neighbouring ids land seconds apart, not milliseconds
  return { '--mascot-delay': `${-((hash * 1777) % 6000)}ms` } as CSSProperties
}

export default function Mascot({
  pose = 'stand',
  face,
  mark,
  glasses = pose === 'read',
  className = '',
  still = false,
  noGround = false,
}: MascotProps) {
  const id = useId().replace(/:/g, '')
  const sitting = pose === 'laptop'
  const shownFace = face ?? POSE_FACE[pose]
  const shownMark = mark === undefined ? (POSE_MARK[pose] ?? null) : mark

  return (
    <svg
      viewBox="0 0 160 170"
      aria-hidden="true"
      className={`mascot overflow-visible ${className}`}
      data-still={still || undefined}
      data-pose={pose}
      style={seedDelay(id)}
    >
      <defs>
        <filter id={`edge-${id}`} x="-20%" y="-20%" width="140%" height="140%">
          <feMorphology in="SourceAlpha" operator="dilate" radius="3" result="grown" />
          <feFlood floodColor={EDGE} />
          <feComposite in2="grown" operator="in" result="edge" />
          <feMerge>
            <feMergeNode in="edge" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>

      {!noGround && (
        <ellipse className="mascot-ground" cx="80" cy="160" rx={sitting || pose === 'sit' ? 58 : 44} ry="7" fill={SIGNAL} fillOpacity="0.2" />
      )}

      <g className="mascot-hover">
        <g className="mascot-hop">
          <g className="mascot-body">
            <g filter={`url(#edge-${id})`} strokeLinejoin="round" strokeLinecap="round">
              {pose === 'sit' ? (
                <SitThreeQuarter face={shownFace} glasses={glasses} />
              ) : pose === 'point' ? (
                <PointThreeQuarter face={shownFace} glasses={glasses} />
              ) : sitting ? (
                <Sitting face={shownFace} glasses={glasses} />
              ) : (
                <Standing pose={pose} face={shownFace} glasses={glasses} />
              )}
            </g>
          </g>
          {shownMark === '?' && <Question />}
          {shownMark === '!' && <Exclaim />}
        </g>
      </g>
    </svg>
  )
}

const flipper = { fill: INK, stroke: INK, strokeWidth: 3 }

function Standing({ pose, face, glasses }: { pose: MascotPose; face: MascotFace; glasses: boolean }) {
  const crossed = pose === 'think' || pose === 'alert'
  const reading = pose === 'read'
  const cheering = pose === 'cheer'
  const hanging = !crossed && !reading

  return (
    // cheering leans back on its heels
    <g transform={cheering ? 'rotate(-9 80 152)' : undefined}>
      {/* flippers behind the body */}
      {hanging &&
        (cheering ? (
          <g className="mascot-flap-l">
            <path d="M50 80 C38 68 26 52 16 34 C12 44 16 58 26 72 C32 82 40 92 48 98 Z" {...flipper} />
          </g>
        ) : (
          <g className="mascot-flip-l">
            <path d="M46 74 C30 92 22 116 20 134 C30 130 40 120 48 106 Z" {...flipper} />
          </g>
        ))}
      {hanging &&
        (pose === 'wave' ? (
          <g className="mascot-wave">
            <path d="M110 80 C122 68 134 52 144 34 C148 44 144 58 134 72 C128 82 120 92 112 98 Z" {...flipper} />
          </g>
        ) : cheering ? (
          <g className="mascot-flap-r">
            <path d="M110 80 C122 68 134 52 144 34 C148 44 144 58 134 72 C128 82 120 92 112 98 Z" {...flipper} />
          </g>
        ) : (
          <g className="mascot-flip-r">
            <path d="M114 74 C130 92 138 116 140 134 C130 130 120 120 112 106 Z" {...flipper} />
          </g>
        ))}

      {/* feet, then the body over them */}
      <ellipse cx="64" cy="152" rx="13" ry="5.5" fill={PAPER} stroke={INK} strokeWidth="3" />
      <ellipse cx="96" cy="152" rx="13" ry="5.5" fill={PAPER} stroke={INK} strokeWidth="3" />
      <path
        d="M80 12 C104 12 116 30 116 48 C116 58 113 64 112 70 C124 86 128 104 126 122 C124 142 106 152 80 152 C54 152 36 142 34 122 C32 104 36 86 48 70 C47 64 44 58 44 48 C44 30 56 12 80 12 Z"
        fill={INK}
      />
      <path
        d="M80 70 C88 70 96 64 102 64 C112 74 118 92 118 112 C118 134 102 146 80 146 C58 146 42 134 42 112 C42 92 48 74 58 64 C64 64 72 70 80 70 Z"
        fill={PAPER}
      />

      <Face face={face} glasses={glasses} />

      {crossed && (
        <g {...flipper}>
          {/* arms folded across the belly, one flipper lifted to the chin */}
          <path d="M38 86 C42 104 60 112 80 106 C100 112 118 104 122 86 C114 98 100 102 88 98 C84 97 82 98 80 100 C78 98 76 97 72 98 C60 102 46 98 38 86 Z" />
          <path d="M72 100 C68 88 72 76 80 68 C86 76 86 90 82 100 Z" />
        </g>
      )}

      {reading && (
        <>
          {/* a cited paper held up to read, one line highlighted */}
          <g className="mascot-paper">
            <g transform="rotate(-5 80 114)">
              <rect x="52" y="92" width="56" height="46" rx="3" fill={PAPER} stroke={INK} strokeWidth="3" />
              <path d="M98 92 v8 h8" fill="none" stroke={INK} strokeWidth="2" />
              <path d="M60 102 h26" stroke={INK} strokeWidth="2.6" />
              <rect x="58" y="110" width="40" height="7" rx="2" fill={BLUSH} />
              <path d="M60 113.5 h34 M60 123 h38 M60 130 h22" stroke={INK} strokeWidth="1.8" />
            </g>
          </g>
          <path d="M46 76 C36 92 38 110 58 118 C62 110 58 100 52 90 Z" {...flipper} />
          <path d="M114 76 C124 92 122 110 102 118 C98 110 102 100 108 90 Z" {...flipper} />
        </>
      )}
    </g>
  )
}

function Sitting({ face, glasses }: { face: MascotFace; glasses: boolean }) {
  return (
    <>
      {/* tail and feet out front, the body low behind a laptop */}
      <path d="M118 140 C132 144 142 150 146 156 C134 158 120 156 110 152 Z" fill={INK} stroke={INK} strokeWidth="3" />
      <path
        d="M80 30 C102 30 112 46 112 62 C112 70 110 76 109 80 C120 94 124 110 122 126 C120 146 104 154 80 154 C56 154 40 146 38 126 C36 110 40 94 51 80 C50 76 48 70 48 62 C48 46 58 30 80 30 Z"
        fill={INK}
      />
      <path d="M80 86 C87 86 94 81 99 81 C108 92 114 106 114 122 C114 140 100 148 80 148 C60 148 46 140 46 122 C46 106 52 92 61 81 C66 81 73 86 80 86 Z" fill={PAPER} />
      <g transform="translate(0 18)">
        <Face face={face} glasses={glasses} />
      </g>

      {/* the laptop, lid towards us, the desk's paper mark on it */}
      <path d="M40 152 L122 152" stroke={INK} strokeWidth="5" />
      <g transform="rotate(-6 76 127)">
        <rect x="44" y="104" width="64" height="46" rx="5" fill={PAPER} stroke={INK} strokeWidth="3" />
        <path d="M70 118 h9 l5 5 v12 h-14 Z" fill="none" stroke={INK} strokeWidth="2.2" />
        <path d="M73 127 h8 M73 131 h6" stroke={INK} strokeWidth="2" />
      </g>

      {/* flippers on the keys, tapping */}
      <g className="mascot-type-l">
        <path d="M50 96 C40 108 36 124 42 134 C48 130 52 120 56 110 Z" fill={INK} stroke={INK} strokeWidth="3" />
      </g>
      <g className="mascot-type-r">
        <path d="M110 96 C122 106 126 122 120 132 C114 128 110 118 106 110 Z" fill={INK} stroke={INK} strokeWidth="3" />
      </g>

      <ellipse cx="54" cy="156" rx="12" ry="5" fill={PAPER} stroke={INK} strokeWidth="3" />
      <ellipse cx="98" cy="157" rx="12" ry="5" fill={PAPER} stroke={INK} strokeWidth="3" />
    </>
  )
}

function Face({ face, glasses }: { face: MascotFace; glasses: boolean }) {
  const closed = face === 'closed' || face === 'open'
  return (
    <g className="mascot-face">
      {closed ? (
        <>
          <path d="M60 44 Q66 37 72 44" fill="none" stroke={PAPER} strokeWidth="3.4" />
          <path d="M88 44 Q94 37 100 44" fill="none" stroke={PAPER} strokeWidth="3.4" />
        </>
      ) : face === 'surprised' ? (
        <g className="mascot-eyes">
          <circle cx="66" cy="41" r="8.4" fill={PAPER} />
          <circle cx="94" cy="41" r="8.4" fill={PAPER} />
          <circle cx="66" cy="41" r="2.6" fill={INK} />
          <circle cx="94" cy="41" r="2.6" fill={INK} />
        </g>
      ) : (
        <g className="mascot-eyes">
          <circle cx="66" cy="42" r="7" fill={PAPER} />
          <circle cx="94" cy="42" r="7" fill={PAPER} />
          <circle cx="67.5" cy="42.5" r="3.6" fill={INK} />
          <circle cx="92.5" cy="42.5" r="3.6" fill={INK} />
        </g>
      )}

      {glasses && (
        <g fill="none" stroke={EDGE} strokeWidth="2">
          <circle cx="66" cy="42" r="11" />
          <circle cx="94" cy="42" r="11" />
          <path d="M77 41 Q80 38.5 83 41 M55 40 L47 37 M105 40 L113 37" />
        </g>
      )}

      {/* a small soft blush, low at the edge of the face */}
      <ellipse cx="53" cy="57" rx="5.2" ry="4.2" fill={BLUSH} fillOpacity="0.85" />
      <ellipse cx="107" cy="57" rx="5.2" ry="4.2" fill={BLUSH} fillOpacity="0.85" />

      {face === 'surprised' ? (
        <>
          <path d="M73 51 Q80 48 87 51 L80 55 Z" fill={PAPER} stroke={INK} strokeWidth="1.8" />
          <ellipse cx="80" cy="59.5" rx="4.4" ry="3.8" fill={PAPER} stroke={INK} strokeWidth="1.8" />
          <ellipse cx="80" cy="59.8" rx="2" ry="1.7" fill={INK} />
        </>
      ) : face === 'open' ? (
        <>
          <path d="M71 51 Q80 47 89 51 L80 55 Z" fill={PAPER} stroke={INK} strokeWidth="1.8" />
          <path d="M72 56 Q80 54 88 56 Q86 66 80 66 Q74 66 72 56 Z" fill={PAPER} stroke={INK} strokeWidth="1.8" />
        </>
      ) : (
        <>
          {/* the beak, a small smile */}
          <path d="M71 52 Q80 48 89 52 Q86 61 80 61 Q74 61 71 52 Z" fill={PAPER} stroke={INK} strokeWidth="2" />
          <path d="M74 55.5 Q80 58 86 55.5" fill="none" stroke={INK} strokeWidth="1.8" />
        </>
      )}
    </g>
  )
}

// The 3/4 views: the body turned to the viewer's right, the belly showing on the near side, the far
// eye and cheek foreshortened, the beak pointing past us. The face sits at the head of the sitting
// body; the standing one lifts it by 26.

function FaceThreeQuarter({ face, glasses }: { face: MascotFace; glasses: boolean }) {
  const closed = face === 'closed' || face === 'open'
  return (
    <g className="mascot-face" style={{ transformOrigin: '86px 70px' }}>
      {closed ? (
        <>
          <path d="M70 64 Q74 58 78 64" fill="none" stroke={PAPER} strokeWidth="3" />
          <path d="M92 64 Q98 57 104 64" fill="none" stroke={PAPER} strokeWidth="3.4" />
        </>
      ) : face === 'surprised' ? (
        <g className="mascot-eyes">
          <ellipse cx="74" cy="62" rx="5.4" ry="7.4" fill={PAPER} />
          <circle cx="98" cy="62" r="8.2" fill={PAPER} />
          <circle cx="75" cy="62" r="2" fill={INK} />
          <circle cx="99" cy="62" r="2.6" fill={INK} />
        </g>
      ) : (
        <g className="mascot-eyes">
          <ellipse cx="74" cy="63" rx="4.6" ry="6.2" fill={PAPER} />
          <circle cx="98" cy="63" r="6.8" fill={PAPER} />
          <ellipse cx="75.5" cy="63.6" rx="2.4" ry="3.2" fill={INK} />
          <circle cx="100" cy="63.6" r="3.5" fill={INK} />
        </g>
      )}

      {glasses && (
        <g fill="none" stroke={EDGE} strokeWidth="2">
          <ellipse cx="74" cy="63" rx="7.5" ry="10" />
          <circle cx="98" cy="63" r="10.5" />
          <path d="M81.5 62 Q84.5 59.5 87.5 62" />
        </g>
      )}

      <ellipse cx="66" cy="77" rx="3.4" ry="3.8" fill={BLUSH} fillOpacity="0.85" />
      <ellipse cx="108" cy="78" rx="5" ry="4.2" fill={BLUSH} fillOpacity="0.85" />

      {face === 'surprised' ? (
        <>
          <path d="M82 72 Q90 68 99 71 L90 76 Z" fill={PAPER} stroke={INK} strokeWidth="1.8" />
          <ellipse cx="90" cy="80" rx="3.8" ry="3.4" fill={PAPER} stroke={INK} strokeWidth="1.8" />
          <ellipse cx="90" cy="80.3" rx="1.7" ry="1.5" fill={INK} />
        </>
      ) : (
        <>
          {/* the beak, turned with the head, a small smile */}
          <path d="M80 72 Q90 67 101 71 Q98 80 90 80 Q83 80 80 72 Z" fill={PAPER} stroke={INK} strokeWidth="2" />
          <path d="M84 75.5 Q90 78 97 75" fill="none" stroke={INK} strokeWidth="1.8" />
        </>
      )}
    </g>
  )
}

function SitThreeQuarter({ face, glasses }: { face: MascotFace; glasses: boolean }) {
  return (
    <>
      {/* tail on the ground behind, then the body settled low */}
      <path d="M46 140 C32 146 22 152 14 158 C28 161 44 157 54 150 Z" {...flipper} />
      <path
        d="M76 40 C100 38 116 58 116 80 C116 88 114 94 113 98 C124 112 127 130 121 143 C113 156 93 160 72 158 C50 156 36 142 36 122 C36 104 44 92 52 84 C48 76 46 68 48 60 C52 46 62 40 76 40 Z"
        fill={INK}
      />
      <path d="M80 100 C88 93 99 88 108 90 C119 105 121 124 115 139 C107 153 90 157 76 154 C66 150 63 138 65 124 C67 112 72 104 80 100 Z" fill={PAPER} />

      <FaceThreeQuarter face={face} glasses={glasses} />

      {/* feet out front, angled with the body */}
      <ellipse cx="84" cy="158" rx="12.5" ry="5" fill={PAPER} stroke={INK} strokeWidth="3" transform="rotate(-8 84 158)" />
      <ellipse cx="110" cy="154" rx="11" ry="4.6" fill={PAPER} stroke={INK} strokeWidth="3" transform="rotate(-14 110 154)" />

      {/* a book open on the lap, one page turning over now and then */}
      <path d="M62 128 L91 122 L93 147 L64 151 Z" fill={PAPER} stroke={INK} strokeWidth="3" />
      <path d="M91 122 L120 125 L120 149 L93 147 Z" fill={PAPER} stroke={INK} strokeWidth="3" />
      <path d="M68 133 L86 129.5 M69 139 L87 135.5 M70 144 L82 142" stroke={INK} strokeWidth="1.6" />
      <path d="M97 129 L114 130.5 M97 135 L115 136.5" stroke={INK} strokeWidth="1.6" />
      <path d="M97 140.5 L112 141.8" stroke={BLUSH} strokeWidth="4" />
      <path className="mascot-page" d="M91 122 L118 125 L118 148 L93 147 Z" fill={PAPER} stroke={INK} strokeWidth="2" style={{ transformOrigin: '92px 135px' }} />

      {/* flippers holding the book's edges */}
      <path d="M54 102 C44 114 46 130 60 138 C63 128 61 116 58 108 Z" {...flipper} />
      <path d="M113 100 C126 110 130 126 123 140 C117 134 112 122 109 112 Z" {...flipper} />
    </>
  )
}

function PointThreeQuarter({ face, glasses }: { face: MascotFace; glasses: boolean }) {
  return (
    <>
      {/* the far flipper and the tail behind */}
      <path d="M50 80 C38 96 34 116 36 132 C44 126 50 114 52 100 Z" {...flipper} />
      <path d="M44 138 C34 144 28 150 22 156 C34 158 44 154 50 148 Z" {...flipper} />
      {/* the near flipper, out towards the page, its root behind the body */}
      <g className="mascot-point" style={{ transformOrigin: '112px 86px' }}>
        <path d="M100 80 C118 78 138 70 152 60 C151 71 140 84 125 91 C114 95 104 94 100 92 Z" {...flipper} />
      </g>
      <ellipse cx="70" cy="154" rx="12.5" ry="5.2" fill={PAPER} stroke={INK} strokeWidth="3" transform="rotate(-6 70 154)" />
      <ellipse cx="100" cy="153" rx="12" ry="5" fill={PAPER} stroke={INK} strokeWidth="3" transform="rotate(-12 100 153)" />
      <path
        d="M76 14 C100 12 116 32 116 54 C116 62 114 68 113 72 C124 88 127 108 123 126 C119 144 101 154 79 154 C57 154 40 144 38 124 C36 104 42 88 50 76 C46 68 44 60 46 50 C50 26 62 14 76 14 Z"
        fill={INK}
      />
      <path d="M80 74 C88 67 99 62 108 64 C120 81 122 104 117 122 C111 140 95 148 79 146 C67 142 64 128 64 112 C65 94 70 80 80 74 Z" fill={PAPER} />
      <g transform="translate(0 -26)">
        <FaceThreeQuarter face={face} glasses={glasses} />
      </g>
    </>
  )
}

// the marks float to the left of the head, clear of a badge at the right shoulder
function Question() {
  return (
    <g className="mascot-mark" fill="none" stroke={SIGNAL} strokeWidth="6" strokeLinecap="round">
      <path d="M12 22 C12 10 32 10 32 22 C32 30 22 30 22 40" />
      <circle cx="22" cy="51" r="3.4" fill={SIGNAL} stroke="none" />
    </g>
  )
}

function Exclaim() {
  return (
    <g className="mascot-mark" fill={SIGNAL}>
      <path d="M14 8 L26 6 L24 34 L18 35 Z" />
      <circle cx="21" cy="44" r="4" />
    </g>
  )
}
