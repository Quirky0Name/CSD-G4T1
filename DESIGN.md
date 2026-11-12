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
    rounded: "{rounded.sm}"
    padding: "6px 10px"
  input-field:
    textColor: "{colors.text-primary}"
    rounded: "{rounded.sm}"
    padding: "6px 12px"
  answer-tile:
    textColor: "{colors.text-body}"
    rounded: "{rounded.lg}"
    padding: "12px 16px"
  card-signup:
    backgroundColor: "{colors.slate-card}"
    rounded: "{rounded.card}"
  card-list:
    backgroundColor: "rgb(31 41 55 / 0.5)"
    rounded: "{rounded.sm}"
    padding: "16px 24px"
  dialog:
    backgroundColor: "{colors.slate-card}"
    textColor: "{colors.text-body}"
    rounded: "{rounded.md}"
    padding: "32px"
  toast:
    backgroundColor: "{colors.slate-panel}"
    textColor: "{colors.text-body}"
    rounded: "{rounded.md}"
    padding: "16px"
  tab-track:
    backgroundColor: "rgb(255 255 255 / 0.05)"
    rounded: "{rounded.md}"
    padding: "4px"
  paper-page:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.paper-ink}"
    rounded: "{rounded.sm}"
    padding: "16px 20px"
  nav-rail:
    backgroundColor: "{colors.slate-card}"
    textColor: "{colors.text-muted}"
    width: "72px"
---

# Design System: Research desk

## Overview

**Creative North Star: "The Night Desk"**

The researcher app is a quiet desk lit for late work: an always-dark ground, slate surfaces stacked one tone apart, white hairlines for every edge, and a single indigo signal that marks where to act, how far along you are, and what you chose. Nothing competes with that signal; status colours (red, amber, emerald) appear only as small tinted badges that report a paper's or alert's state.

The palette is fixed. The sign-up flow restyled components inside it rather than changing it: a two-panel card, fully rounded pill buttons, one question per screen with a counter, outlined answer tiles that fill indigo when chosen, and a flat authored illustration whose badge changes per step. Inside the signed-in app, controls stay compact and gently rounded; the pill and tile language belongs to onboarding screens.

The papers themselves are the one light thing on the desk. A PDF page or a source's text is laid down as white paper with a soft drop shadow, and the passage a claim rests on is marked with a yellow highlighter stroke the ink shows through.

Dev mode is a deliberate exception, bound by a PRODUCT.md brand commitment (the belly-as-UI screen in `DevMode.tsx` / `DevMode.css`). It is its own world and nothing here governs it, nor does it feed this system.

**Key Characteristics:**
- Always dark, whatever the OS asks for (`dark:` styles always apply).
- Tonal layering (ground, card, panel) with inset white hairlines instead of borders or heavy shadow.
- One accent, indigo, for action, progress, selection and focus.
- Status hues only as tinted, inset-ringed badges.
- Documents read as white paper on the dark desk, with quotes highlighted in yellow.
- Motion on entrances only, short and decelerating, with a gentler fade under reduced motion.

## Colors

A near-black cool ground, slate surfaces a single step apart, and one indigo signal.

### Primary
- **Signal Indigo** (`signal-indigo`): the primary pill and in-app primary action, the illustration's figure and badge, selected tiles' tint (at 12%), the selection highlight (at 40%).
- **Signal Indigo Light** (`signal-indigo-light`): hover for the primary button, focus rings on the sign-up card, the chosen tile's outline, the current progress dot, the counter's number, info icons, caret colour.
- **Signal Indigo Pale** (`signal-indigo-pale`): secondary indigo text, such as the counter's "/ 5" (at 70%) and selected tags' text.

### Tertiary
- **Status Red** (`status-red`): error text, high-severity badges, and error panels (tinted at 10% with a 20-25% outline).
- **Status Amber** (`status-amber`): medium-severity and pending badges, always tinted at 10% with a 20% inset ring.
- **Status Emerald** (`status-emerald`): acknowledged and resolved states, same tinted-badge treatment.
- **Highlighter Yellow** (`highlighter`): the mark over a quoted passage, and nothing else. On source text it sits at 70% under dark ink; on a PDF page it is yellow-400 at 45% with a multiply blend so the printed text shows through. It is not a status colour, and status amber never stands in for it.

### Neutral
- **Ink Ground** (`ink-ground`): the page behind everything.
- **Slate Card** (`slate-card`): the sign-up card, the nav rail, raised containers.
- **Slate Panel** (`slate-panel`): menus, listboxes, toasts, the illustration panel (at 40%), and list cards (at 50%).
- **Paper** (`paper`): a PDF page, a source's text and the draft card's thumbnail, the documents themselves.
- **Paper Ink** (`paper-ink`): text set on paper.
- **Hairline** (`hairline`): every edge: card outlines, inputs, review lists, dividers, the nav rail's border. Steps up to white at 15-30% for tiles and their hover.
- **Text Primary** (`text-primary`): headings, button labels, input text.
- **Text Body** (`text-body`): the root text colour and answer-tile labels.
- **Text Muted** (`text-muted`): supporting copy, labels, placeholders, inactive nav.

### Named Rules
**The One Signal Rule.** Indigo is the only accent. It marks action, progress, selection and focus, and nothing else. Status hues never stand in for it.

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

The signed-in app is a 72px icon-and-label nav rail on the left from 640px up, collapsing to a bar across the top on a phone, with the page beside it. Page content opens with a headline and one muted line below it. Lists are stacks of list cards; running text is held to about 75 characters (`max-w-3xl` or `75ch`).

The report view puts the claim list (18-22rem) beside a column of two evidence panes, your draft above the cited source, from 1024px. Below that the list comes first and a two-button toggle picks which pane shows, at 70% of the viewport's height.

The sign-up card is centred on the ground, up to 64rem wide and at least 46rem tall from 768px, split 9:11 into the illustration panel and the form pane. Below 768px the panel becomes a short band above the form. The pane pins its actions to the bottom (`mt-auto`, 40px above), so Back sits left and the forward action right on every step. Answer tiles run in one column, two from 640px, with an 8px gap.

Spacing rhythm: 6px inside fields, 8px between tiles and tags, 16-24px between field groups, 24-40px pane padding and section breaks.

## Elevation & Depth

Depth is tonal: ground, card and panel sit one slate step apart, edged with inset white hairlines. Shadows are rare and soft, and are reserved for things that float.

### Shadow Vocabulary
- **Card lift** (`box-shadow: 0 30px 80px -30px rgba(0,0,0,0.8)`): the sign-up card only, lifting it off the ground.
- **Indigo glow** (`box-shadow: 0 8px 20px -8px rgba(99,102,241,0.8)`): under the primary pill and, a little wider, under the illustration's badge.
- **Float** (Tailwind `shadow-lg`): menus, listboxes, toasts and dialogs that overlay content. A dialog also dims the page behind it with black at 70%.
- **Paper drop** (Tailwind `shadow-md` in black at 40%): under a PDF page or a source's text, so the paper sits on the desk.

### Named Rules
**The Hairline Rule.** Edges are inset white outlines at 10%, not borders in a contrasting colour and not shadows. Hover and selection change an outline's opacity or hue; they never add a stroke.

## Shapes

Gently rounded in the app, fully round on onboarding. Fields, menus, list cards, evidence panes and in-app action buttons are 6px; error panels, dialogs, toasts and the tab track 8px; answer tiles, the review list, the draft card and empty states 12px; the sign-up card 24px. Empty states and the PDF drop zone are the only dashed edges: a 10% white dashed border that turns indigo while a file is dragged over. Pills, progress dots, the account avatar and the illustration badge are fully round. The illustration is flat vector: rounded rectangles and circles, no outlines, no gradients.

## Components

### Buttons
Calm and decisive, one filled action per screen.
- **Shape:** fully round pills on sign-up (`rounded.pill`); gently rounded (`rounded.sm`) for in-app actions.
- **Primary pill:** indigo fill, white 600-weight label, 10px 24px, with the indigo glow; full width on a phone for the account screen's Continue.
- **Hover / Focus:** hover lightens to indigo-light; press scales to 0.97 on sign-up. Focus is a 2px outline offset 2px, indigo-light on the sign-up card and signal indigo in the app. Disabled drops to 60% opacity on sign-up and 50% in the app.
- **Ghost pill:** no fill, a white 15% hairline, light grey label; hover adds a 5% white wash and turns the label white. Used for Back and "Sign in".
- **Text link:** muted grey, underlines and turns white on hover. Used for Skip, Edit and Clear.
- **In-app action buttons:** primary (indigo), secondary (white 10% fill with a 10% inset ring), quiet (text only) and danger (red), all 6px radius, 600-weight 0.875rem labels.

### Chips
- **Tags:** indigo at 10% with pale indigo text, 6px radius, with a small remove button.
- **Status badges:** status hue at 10% fill, its own text colour and a 20% inset ring, 0.75rem medium, 6px radius (fully round when floated on a card).

### Cards / Containers
- **Corner Style:** 24px for the sign-up card; 12px for in-pane lists and the draft card; 6px for list cards.
- **Background:** slate card for the sign-up card, with the illustration panel adding slate panel at 40%. List cards (projects, reports, alerts) are slate panel at 50%; the rows nested inside an expanded one are slate card at 60%.
- **Shadow Strategy:** card lift only on the sign-up card (see Elevation & Depth); list cards have none.
- **Border:** a 10% white inset outline.
- **Internal Padding:** 24px, rising to 40px from 640px, on the sign-up card; 16px, rising to 24px across from 640px, on list cards.

### Dialogs and Toasts
- **Dialog:** slate card, 8px radius, 10% white edge, float shadow, 24px padding rising to 32px, over a 70% black scrim. Title in 1.125rem semibold white with one muted line under it; Cancel (secondary) and the action (primary) sit bottom right.
- **Toast:** slate panel, 8px radius, 10% outline, float shadow, stacked top right. A 20px outline icon gives the tone: indigo for info, emerald for success, red for error. An optional action is an indigo text button. It slides in over 300ms, fades out over 100ms, and leaves on its own after 6 seconds.

### Inputs / Fields
- **Style:** white 5% fill, white 10% inset outline, 6px radius, 6px 12px padding, white text, grey placeholder.
- **Focus:** the outline thickens to 2px indigo.
- **Error:** the outline turns red-400 (`aria-invalid`), with red-400 text below.
- **Combobox list:** slate panel, 10% outline, float shadow; the active option fills indigo.

### Navigation
- **Rail:** slate card with a 10% hairline edge; each item is a 20px outline icon over an 11px medium label. Active is a white 10% wash with white text; inactive is muted grey that washes 5% on hover. The account avatar is a round white 10% button with the initial.
- **Tabs:** a segmented track of white at 5%, 8px radius, 4px inset. Each tab is a 600-weight 0.875rem label with its count beside it in tabular figures at 70%; the selected tab takes a white 10% fill and white text.

### Evidence Pane (signature)
How a report shows its working. The claim list sits on the left: each claim is a 6px row that washes 5% on hover; the open claim takes a white 10% fill with a 60% indigo-light outline, and a claim with nothing quoted carries a small "No quote found" line in status amber. Each pane is slate card with a 10% outline and a 36px header (the source's name, its page, and a "1 of 3" pager when there is more than one quote) ruled off by a hairline. The paper sits inside: PDF pages stacked with their page number in small grey tabular figures, or the source's text on a white sheet held to reading width. The quote is highlighted and scrolled into view. When the quote cannot be placed, a thin amber band says so across the top of the pane and the quote is shown as a muted blockquote instead.

### Answer Tile (signature)
An outlined choice that fills when chosen. 12px radius, white 2% fill, white 15% outline, 12px 16px padding, a 16px radio dot or check box at the left. Hover lifts the outline to 30%; checked fills indigo at 12%, turns the outline indigo-light and the label white. The real input stays in the tab order (visually hidden), so arrow keys and screen readers work as on any radio or checkbox.

### Step Change (signature)
On sign-up, each screen enters from the direction of travel (28px slide, 6px blur settling to sharp, 420ms), the illustration's badge pops in (scale and a slight turn, 520ms), and the counter's number rises (360ms), all on `cubic-bezier(0.16, 1, 0.3, 1)`. Exits are never animated. Under reduced motion every one becomes a 160ms fade. Progress dots under the illustration stretch to a 20px indigo bar for the current step.

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
