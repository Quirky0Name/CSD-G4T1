---
name: Research desk
description: An always-dark researcher's desk that flags when a cited paper is retracted, corrected or delisted.
colors:
  ink-ground: "#030712"
  slate-card: "#111827"
  slate-panel: "#1f2937"
  hairline: "rgb(255 255 255 / 0.1)"
  text-primary: "#ffffff"
  text-body: "#e5e7eb"
  text-muted: "#9ca3af"
  signal-indigo: "#6366f1"
  signal-indigo-light: "#818cf8"
  signal-indigo-pale: "#a5b4fc"
  status-red: "#f87171"
  status-amber: "#facc15"
  status-emerald: "#34d399"
  paper: "#ffffff"
  paper-ink: "#1f2937"
  highlighter: "#fde047"
  mascot-ink: "#000000"
typography:
  headline:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.875rem"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.025em"
  title:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: 1.333
    letterSpacing: "-0.025em"
  counter:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.875rem"
    fontWeight: 600
    lineHeight: 1.2
    fontFeature: "tnum"
  body:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.714
  label:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    lineHeight: 1.667
    letterSpacing: "0.025em"
rounded:
  sm: "6px"
  md: "8px"
  lg: "12px"
  card: "24px"
  pill: "9999px"
spacing:
  xs: "6px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "40px"
components:
  button-pill-primary:
    backgroundColor: "{colors.signal-indigo}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.pill}"
    padding: "10px 24px"
  button-pill-primary-hover:
    backgroundColor: "{colors.signal-indigo-light}"
  button-pill-ghost:
    textColor: "{colors.text-body}"
    rounded: "{rounded.pill}"
    padding: "10px 16px"
  button-action-primary:
    backgroundColor: "{colors.signal-indigo}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.pill}"
    padding: "8px 16px"
  input-field:
    textColor: "{colors.text-primary}"
    rounded: "{rounded.md}"
    padding: "8px 12px"
  answer-tile:
    textColor: "{colors.text-body}"
    rounded: "{rounded.lg}"
    padding: "12px 16px"
  card-signup:
    backgroundColor: "{colors.slate-card}"
    rounded: "{rounded.card}"
  card-list:
    backgroundColor: "{colors.slate-card}"
    rounded: "16px"
    padding: "20px 24px"
  card-section:
    backgroundColor: "{colors.slate-card}"
    rounded: "{rounded.card}"
    padding: "20px 32px"
  dialog:
    backgroundColor: "{colors.slate-card}"
    textColor: "{colors.text-body}"
    rounded: "{rounded.card}"
    padding: "32px"
  toast:
    backgroundColor: "{colors.slate-panel}"
    textColor: "{colors.text-body}"
    rounded: "16px"
    padding: "16px"
  tab-track:
    backgroundColor: "rgb(255 255 255 / 0.05)"
    rounded: "{rounded.pill}"
    padding: "4px"
  paper-page:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.paper-ink}"
    rounded: "{rounded.sm}"
    padding: "16px 20px"
  nav-bar:
    backgroundColor: "rgb(3 7 18 / 0.8)"
    textColor: "{colors.text-muted}"
    height: "64px"
---

# Design System: Research desk

## Overview

**Creative North Star: "The Night Desk"**

The researcher app is a quiet desk lit for late work: an always-dark ground, slate surfaces stacked one tone apart, white hairlines for every edge, and a single indigo signal that marks where to act, how far along you are, and what you chose. Nothing competes with that signal; status colours (red, amber, emerald) appear only as small tinted badges that report a paper's or alert's state.

The palette is fixed. The sign-up flow restyled components inside it rather than changing it: a two-panel card, fully rounded pill buttons, one question per screen with a counter, outlined answer tiles that fill indigo when chosen, and the penguin acting out each step beside a badge that changes per step. The signed-in app carries the same language at working size: every button is a pill, cards are slate with a hairline at 16-24px, and pages arrive with a short rise-and-settle borrowed from the step change. Shared classes live in `src/ui.ts`; the page frame in `src/components/Page.tsx`.

The papers themselves are the one light thing on the desk. A PDF page or a source's text is laid down as white paper with a soft drop shadow, and the passage a claim rests on is marked with a yellow highlighter stroke the ink shows through.

The desk has a mascot: a flat black-and-white penguin with a pale indigo blush (see Mascot below). It is kept to the places a page has nothing else to say, never as decoration on working screens.

Dev mode is a deliberate exception, bound by a PRODUCT.md brand commitment (the belly-as-UI screen in `DevMode.tsx` / `DevMode.css`). It is its own world and nothing here governs it, nor does it feed this system.

**Key Characteristics:**
- Always dark, whatever the OS asks for (`dark:` styles always apply).
- Tonal layering (ground, card, panel) with inset white hairlines instead of borders or heavy shadow.
- One accent, indigo, for action, progress, selection and focus.
- Status hues only as tinted, inset-ringed badges.
- Documents read as white paper on the dark desk, with quotes highlighted in yellow.
- Each page takes the layout its job calls for: plain academic headers on working pages, the profile layout on the profile alone.
- A penguin mascot on sign-in, sign-up, empty lists and missing pages, and nowhere else.
- Motion on entrances only, short and decelerating, with a gentler fade under reduced motion.

## Colors

A near-black cool ground, slate surfaces a single step apart, and one indigo signal.

### Primary
- **Signal Indigo** (`signal-indigo`): the primary pill and in-app primary action, the sign-up step badge, selected tiles' tint (at 12%), the selection highlight (at 40%).
- **Signal Indigo Light** (`signal-indigo-light`): hover for the primary button, focus rings on the sign-up card, the chosen tile's outline, the current progress dot, the counter's number, info icons, caret colour.
- **Signal Indigo Pale** (`signal-indigo-pale`): secondary indigo text, such as the counter's "/ 5" (at 70%) and selected tags' text; the penguin's blush (at 85%) and the highlighted line on the paper it reads.

### Tertiary
- **Status Red** (`status-red`): error text, high-severity badges, and error panels (tinted at 10% with a 20-25% outline).
- **Status Amber** (`status-amber`): medium-severity and pending badges, always tinted at 10% with a 20% inset ring.
- **Status Emerald** (`status-emerald`): acknowledged and resolved states, same tinted-badge treatment.
- **Highlighter Yellow** (`highlighter`): the mark over a quoted passage, and nothing else. On source text it sits at 70% under dark ink; on a PDF page it is yellow-400 at 45% with a multiply blend so the printed text shows through. It is not a status colour, and status amber never stands in for it.

### Neutral
- **Ink Ground** (`ink-ground`): the page behind everything.
- **Slate Card** (`slate-card`): the sign-up card, list and section cards, dialogs, raised containers.
- **Mascot Ink** (`mascot-ink`): the penguin's black, and its outline. It is drawn with a pale `text-body` sticker edge so it holds on the dark ground.
- **Slate Panel** (`slate-panel`): menus, listboxes, toasts, the auth card's penguin panel (at 40%), and list cards (at 50%).
- **Paper** (`paper`): a PDF page, a source's text and the draft card's thumbnail, the documents themselves.
- **Paper Ink** (`paper-ink`): text set on paper.
- **Hairline** (`hairline`): every edge: card outlines, inputs, review lists, dividers, the top bar's edge. Steps up to white at 15-30% for tiles and their hover.
- **Text Primary** (`text-primary`): headings, button labels, input text.
- **Text Body** (`text-body`): the root text colour and answer-tile labels.
- **Text Muted** (`text-muted`): supporting copy, labels, placeholders, inactive nav.

### Named Rules
**The One Signal Rule.** Indigo is the only accent. It marks action, progress, selection and focus, and nothing else. Status hues never stand in for it.

**The Penguin Stays In Palette.** The penguin is black, white and indigo: pale indigo for its blush, indigo-light for its `?` and `!`. It brings no colour of its own.

**The Always-Dark Rule.** There is no light theme. New components are written dark-first; a light branch is dead code. The only light surface is a document being read, which is paper, not chrome.

## Typography

**Body Font:** the system sans stack (`ui-sans-serif, system-ui, sans-serif`), used for every role.

**Character:** plain and legible, with the hierarchy carried by weight, tight tracking on headings, and the indigo counter rather than by a second family.

### Hierarchy
- **Headline** (700, 1.5rem rising to 1.875rem from 640px, tight tracking): page titles in the signed-in app.
- **Title** (600, 1.5rem rising to 1.875rem on the sign-up card, tight tracking, balanced wrap): sign-up headings and each question.
- **Counter** (600, 1.875rem, tabular figures, indigo): the "2 / 5" step counter on sign-up.
- **Body** (400, 0.875rem / 1.5rem): all running copy, in muted grey for supporting lines.
- **Label** (500, 0.75rem, 0.025em tracking, muted grey, sentence case): field labels on the sign-up card and review-list terms. In-app field labels use 0.875rem medium.

### Named Rules
**The Sentence Case Rule.** Labels are sentence case and quiet; nothing is set in uppercase tracking.

## Layout

The signed-in app has a 64px bar across the top from 640px up (a phone held sideways included, 48px when the viewport is short): the penguin's head and "Research desk" on the left, then Projects, Alerts (with a count of new alerts) and Reports as pills and the account avatar on the right. The active section is an indigo-tinted pill. On an upright phone it becomes a tab bar fixed along the bottom, safe-area padded, with the avatar as its fourth item, and the page keeps clear of it.

Each page takes the layout its job calls for, inside one 72rem column (`pageColumn` in `ui.ts`) so every edge lines up, the report view included: no page runs edge to edge.
- **Working pages** (Projects, a project, a paper, Alerts, Reports) open with `PageHeader`: an optional back link, the title over a short indigo rule, one metadata line set like a citation and separated by middots (*Science* · 2014 · doi:…, or "3 papers · 1 new alert"), an optional muted line, and the page's actions to the right (below them on a phone). Content follows as section cards (`Section`: icon, heading, short indigo rule) or as a grid of item cards under a bare `SectionHeading`. A paper pairs its most recent report (wide) with its record (20rem).
- **The profile**, which is about a person, alone uses `PageHero`: a library banner (book spines in the slates with a few indigo volumes under a night-indigo sky), a centred card overlapping it with the person's initial in a round indigo portrait breaking its top edge, a "Researcher" tag, the title over the rule, and a tinted strip of "Label: value" facts along its foot. Below, "About your work" (wide) sits beside "Report voice" (17rem), then the answers to edit.

Lists are grids of item cards; running text is held to about 75 characters (`max-w-3xl` or `75ch`). The `short` variant (max-height 32rem) tightens the bar, the banner, the auth card and dialogs for landscape phones.

The report view keeps the same column: the claim list (18-22rem) beside a column of two evidence panes, your draft above the cited source, from 1024px, filling the window's height below the top bar. Below that the list comes first and a two-button toggle picks which pane shows, at 70% of the viewport's height.

The sign-up card is centred on the ground, up to 64rem wide and at least 46rem tall from 768px, split 9:11 into the penguin panel and the form pane (sign-in shares the card, `AuthShell.tsx`). Below 768px the panel becomes a short band above the form. The pane pins its actions to the bottom (`mt-auto`, 40px above), so Back sits left and the forward action right on every step. Answer tiles run in one column, two from 640px, with an 8px gap.

Spacing rhythm: 6px inside fields, 8px between tiles and tags, 16-24px between field groups, 24-40px pane padding and section breaks.

## Elevation & Depth

Depth is tonal: ground, card and panel sit one slate step apart, edged with inset white hairlines. Shadows are rare and soft, and are reserved for things that float.

### Shadow Vocabulary
- **Card lift** (`box-shadow: 0 30px 80px -30px rgba(0,0,0,0.8)`): the sign-in and sign-up card, the profile's hero card and dialogs, lifting them off the ground.
- **Indigo glow** (`box-shadow: 0 8px 20px -8px rgba(99,102,241,0.8)`): under the primary pill and, a little wider, under the sign-up step badge.
- **Float** (Tailwind `shadow-lg`): menus, listboxes and toasts that overlay content. A dialog dims the page behind it with black at 70% and a small backdrop blur.
- **Paper drop** (Tailwind `shadow-md` in black at 40%): under a PDF page or a source's text, so the paper sits on the desk.

### Named Rules
**The Hairline Rule.** Edges are inset white outlines at 10%, not borders in a contrasting colour and not shadows. Hover and selection change an outline's opacity or hue; they never add a stroke.

## Shapes

Fully round controls, soft containers. Every button, badge, tab and nav item is a pill; fields are 8px; menu items, error panels, answer tiles, claim rows and the review list 12px; menus, list cards, evidence panes and toasts 16px; section cards, dialogs, empty states and the auth and profile cards 24px. Empty states and the PDF drop zone are the only dashed edges: a 10% white dashed border that turns indigo while a file is dragged over. Progress dots, avatars, the profile portrait and the step badge are round. Dialogs rise as a sheet from the bottom edge on a phone (24px top corners) and settle centred from 640px.

## Components

### Buttons
Calm and decisive, one filled action per screen.
- **Shape:** fully round pills everywhere (`rounded.pill`).
- **Primary pill:** indigo fill, white 600-weight label, 10px 24px, with the indigo glow; full width on a phone for the account screen's Continue.
- **Hover / Focus:** hover lightens to indigo-light; press scales to 0.97. Focus is a 2px indigo-light outline offset 2px. Disabled drops to 60% opacity on sign-up and 50% in the app.
- **Ghost pill:** no fill, a white 15% hairline, light grey label; hover adds a 5% white wash and turns the label white. Used for Back and "Sign in".
- **Text link:** muted grey, underlines and turns white on hover. Used for Skip, Edit and Clear.
- **In-app action buttons (`ActionButton`):** pills 40px tall on touch, 36px from 640px: primary (indigo with the glow), secondary (the ghost pill's 15% hairline), quiet (text only) and danger (red), 600-weight 0.875rem labels. Icon-only buttons on cards are round, 40px on touch and 32px from 640px.

### Chips
- **Tags:** indigo at 10% with pale indigo text, 6px radius, with a small remove button.
- **Status badges:** status hue at 10% fill, its own text colour and a 20% inset ring, 0.75rem medium, fully round.

### Cards / Containers
- **Corner Style:** 24px for the auth card, the profile card and section cards; 16px for list cards (projects, papers, alerts, reports).
- **Background:** slate card throughout, the auth card's penguin panel adding slate panel at 40%. Rows nested inside a card (an alert in a report, a document) are white at 3% with a 5% outline.
- **Interactive cards:** a whole card that opens something lifts its outline to 20%, washes slate panel at 70% and rises 2px on hover; focus inside it draws a 2px indigo outline.
- **Shadow Strategy:** card lift on the auth and profile cards (see Elevation & Depth); list and section cards have none.
- **Border:** a 10% white inset outline.
- **Internal Padding:** 24px rising to 40px on the auth card; 20px rising to 32px on section cards; 20px across, 24px from 640px, on list cards.

### Dialogs and Toasts
- **Dialog:** slate card, 24px radius, 10% white edge, card lift, 24px padding rising to 32px, over a 70% black scrim with a small blur. On a phone it is a sheet along the bottom edge with its buttons full width and the action on top; from 640px it is centred, Cancel (ghost) and the action (primary) bottom right.
- **Toast:** slate panel at 95% with a backdrop blur, 16px radius, 10% outline, float shadow, stacked top right. A 20px outline icon gives the tone: indigo for info, emerald for success, red for error. An optional action is an indigo text button. It slides in over 300ms, fades out over 100ms, and leaves on its own after 6 seconds.

### Inputs / Fields
- **Style:** white 5% fill, white 10% inset outline, 8px radius, 8px 12px padding, white text, grey placeholder.
- **Focus:** the outline thickens to 2px indigo.
- **Error:** the outline turns red-400 (`aria-invalid`), with red-400 text below.
- **Combobox list:** slate panel, 10% outline, float shadow; the active option fills indigo.

### Navigation
- **Top bar:** ink ground at 80% under a 10% hairline, sticky. Each item is a pill with a 20px outline icon beside a 0.875rem label; active is indigo at 15% with pale indigo text, inactive is muted grey that washes 5% on hover. New alerts show as a small indigo count. The account avatar is a round white 10% button with the initial; its menu opens below it.
- **Phone tab bar:** slate card at 95% with a blur along the bottom; each item is a 20px icon over an 11px label, the active icon on an indigo-tinted pill.
- **Tabs:** a pill track of white at 5% with a 10% outline, 4px inset. Each tab is a 600-weight 0.875rem label with its count beside it in tabular figures at 70%; the selected tab fills indigo with the indigo glow.

### Evidence Pane (signature)
How a report shows its working. The claim list sits on the left: each claim is a 12px row that washes 5% on hover; the open claim takes indigo at 12% with an indigo-light outline, and a claim with nothing quoted carries a small "No quote found" line in status amber. Each pane is a 16px slate card with a 10% outline and a 36px header (the source's name, its page, and a "1 of 3" pager when there is more than one quote) ruled off by a hairline. The paper sits inside, in one column shared by both panes: the pane's width up to 51rem (a letter page at actual size), centred, with the scrollbar's gutter always reserved so the paper never narrows when a pane starts to scroll. PDF pages are stacked as sheets of that one width with their page number in small grey tabular figures. Every page is drawn at one scale, a US Letter page filling the sheet, so a smaller page (A4, A5) sits centred on a full-width sheet with its text the same size as any other document's, and only a page wider than Letter is shrunk to fit; a source's text sits on a white sheet of the same width, its lines held to 75ch. The quote is highlighted and scrolled into view. When the quote cannot be placed, a thin amber band says so across the top of the pane and the quote is shown as a muted blockquote instead.

### Answer Tile (signature)
An outlined choice that fills when chosen. 12px radius, white 2% fill, white 15% outline, 12px 16px padding, a 16px radio dot or check box at the left. Hover lifts the outline to 30%; checked fills indigo at 12%, turns the outline indigo-light and the label white. The real input stays in the tab order (visually hidden), so arrow keys and screen readers work as on any radio or checkbox.

### Step Change (signature)
On sign-up, each screen enters from the direction of travel (28px slide, 6px blur settling to sharp, 420ms), the penguin hops into the step's pose and its badge pops in (scale and a slight turn, 520ms), and the counter's number rises (360ms), all on `cubic-bezier(0.16, 1, 0.3, 1)`. Exits are never animated. Under reduced motion every one becomes a 160ms fade. Progress dots under the penguin stretch to a 20px indigo bar for the current step.

### Mascot (signature)
A flat penguin (`Mascot.tsx`), drawn with a heavy black line and a pale sticker edge (an SVG dilate) so its black head holds on the dark ground. Black and white, with a small pale-indigo blush and indigo `?` and `!` marks.
- **Where:** only where a page has nothing else to say. The sign-in and sign-up card (it acts out each step; on sign-in it points at the form and turns to its `!` when the details are refused), empty lists (no papers yet: at a laptop; no alerts: happy; no reports: at a laptop) and the missing page (thinking, with a `?`). It never sits in a page header, a working card, a dialog or the profile portrait. Its head alone is the app's mark, in the top bar and the auth card's wordmark.
- **Poses:** stand, wave, laptop, read (spectacles, a paper with one line highlighted), think (arms folded, `?`), alert (arms folded, wide eyes, `!`), happy (eyes closed), cheer (leaning back, flippers up, beak open), and two 3/4 views: sit (turned, a book open on its lap) and point (turned, a flipper out towards what sits beside it). Faces (normal, closed, surprised, open), the mark and spectacles can be set apart from the pose.
- **Motion:** it is never quite still. It breathes and sways on its feet (5.6s), tilts its head (9s), blinks, flaps a flipper now and then, waves in bursts, taps at its laptop, turns a page of its book, bobs its `?` or `!`, keeps hopping when it cheers, and hops once when a pointer comes over it. A new pose arrives with a small hop. Each penguin offsets its loops (`--mascot-delay`) so two on one screen never move in step. All of it stops under reduced motion.

### Page Rise (signature)
In the signed-in app a page's header and then its items rise 14px from a 4px blur over 480ms on `cubic-bezier(0.16, 1, 0.3, 1)`, 45ms apart (`.animate-rise` with `--i`, capped at 8). Under reduced motion it is a 160ms fade.

## Do's and Don'ts

### Do:
- **Do** keep indigo as the only accent: primary action, progress, selection, focus.
- **Do** edge surfaces with inset white hairlines at 10%, stepping to 15-30% only for tiles and their hover.
- **Do** separate surfaces tonally (ground, card, panel) before reaching for a shadow.
- **Do** show status as a 10% tinted badge with a 20% inset ring in its own hue.
- **Do** animate entrances only, on `cubic-bezier(0.16, 1, 0.3, 1)`, and give every animation a reduced-motion fade.
- **Do** keep the real input in the tab order when a control is custom-drawn, as the answer tiles do.
- **Do** show a document as white paper with the paper drop, and mark its quote in highlighter yellow.

### Don't:
- **Don't** add a light theme or write light-mode classes; the app is always dark. Paper is the document, not a theme.
- **Don't** introduce a second accent hue, or use red, amber or emerald for anything but status. The highlighter is for quotes only.
- **Don't** animate a screen's exit; the next screen is never held back. A toast's 100ms fade is the one exit that moves.
- **Don't** carry dev mode's belly world into the researcher app, or this system into dev mode.
- **Don't** give a working page the profile layout; the banner and portrait belong to the profile.
- **Don't** use the penguin as decoration. It appears on sign-in and sign-up, empty lists and missing pages, and nowhere else.
