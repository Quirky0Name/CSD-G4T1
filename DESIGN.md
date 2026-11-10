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

Dev mode is a deliberate exception, bound by a PRODUCT.md brand commitment (the belly-as-UI screen in `DevMode.tsx` / `DevMode.css`). It is its own world and nothing here governs it, nor does it feed this system.

**Key Characteristics:**
- Always dark, whatever the OS asks for (`dark:` styles always apply).
- Tonal layering (ground, card, panel) with inset white hairlines instead of borders or heavy shadow.
- One accent, indigo, for action, progress, selection and focus.
- Status hues only as tinted, inset-ringed badges.
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

### Neutral
- **Ink Ground** (`ink-ground`): the page behind everything.
- **Slate Card** (`slate-card`): the sign-up card, the nav rail, raised containers.
- **Slate Panel** (`slate-panel`): menus, listboxes, and the illustration panel (at 40%).
- **Hairline** (`hairline`): every edge: card outlines, inputs, review lists, dividers, the nav rail's border. Steps up to white at 15-30% for tiles and their hover.
- **Text Primary** (`text-primary`): headings, button labels, input text.
- **Text Body** (`text-body`): the root text colour and answer-tile labels.
- **Text Muted** (`text-muted`): supporting copy, labels, placeholders, inactive nav.

### Named Rules
**The One Signal Rule.** Indigo is the only accent. It marks action, progress, selection and focus, and nothing else. Status hues never stand in for it.

**The Always-Dark Rule.** There is no light theme. New components are written dark-first; a light branch is dead code.

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

The signed-in app is a 72px icon-and-label nav rail on the left from 640px up, collapsing to a bar across the top on a phone, with the page beside it. Page content opens with a headline and one muted line below it.

The sign-up card is centred on the ground, up to 64rem wide and at least 46rem tall from 768px, split 9:11 into the illustration panel and the form pane. Below 768px the panel becomes a short band above the form. The pane pins its actions to the bottom (`mt-auto`, 40px above), so Back sits left and the forward action right on every step. Answer tiles run in one column, two from 640px, with an 8px gap.

Spacing rhythm: 6px inside fields, 8px between tiles and tags, 16-24px between field groups, 24-40px pane padding and section breaks.

## Elevation & Depth

Depth is tonal: ground, card and panel sit one slate step apart, edged with inset white hairlines. Shadows are rare and soft, and are reserved for things that float.

### Shadow Vocabulary
- **Card lift** (`box-shadow: 0 30px 80px -30px rgba(0,0,0,0.8)`): the sign-up card only, lifting it off the ground.
- **Indigo glow** (`box-shadow: 0 8px 20px -8px rgba(99,102,241,0.8)`): under the primary pill and, a little wider, under the illustration's badge.
- **Float** (Tailwind `shadow-lg`): menus and listboxes that overlay content.

### Named Rules
**The Hairline Rule.** Edges are inset white outlines at 10%, not borders in a contrasting colour and not shadows. Hover and selection change an outline's opacity or hue; they never add a stroke.

## Shapes

Gently rounded in the app, fully round on onboarding. Fields, menus and in-app action buttons are 6px; error panels 8px; answer tiles and the review list 12px; the sign-up card 24px. Pills, progress dots, the account avatar and the illustration badge are fully round. The illustration is flat vector: rounded rectangles and circles, no outlines, no gradients.

## Components

### Buttons
Calm and decisive, one filled action per screen.
- **Shape:** fully round pills on sign-up (`rounded.pill`); gently rounded (`rounded.sm`) for in-app actions.
- **Primary pill:** indigo fill, white 600-weight label, 10px 24px, with the indigo glow; full width on a phone for the account screen's Continue.
- **Hover / Focus:** hover lightens to indigo-light; press scales to 0.97; focus is a 2px indigo-light outline offset 2px. Disabled drops to 60% opacity.
- **Ghost pill:** no fill, a white 15% hairline, light grey label; hover adds a 5% white wash and turns the label white. Used for Back and "Sign in".
- **Text link:** muted grey, underlines and turns white on hover. Used for Skip, Edit and Clear.
- **In-app action buttons:** primary (indigo), secondary (white 10% fill with a 10% inset ring), quiet (text only) and danger (red), all 6px radius, 600-weight 0.875rem labels.

### Chips
- **Tags:** indigo at 10% with pale indigo text, 6px radius, with a small remove button.
- **Status badges:** status hue at 10% fill, its own text colour and a 20% inset ring, 0.75rem medium, 6px radius (fully round when floated on a card).

### Cards / Containers
- **Corner Style:** 24px for the sign-up card; 12px for in-pane lists.
- **Background:** slate card; the illustration panel adds slate panel at 40%.
- **Shadow Strategy:** card lift only on the sign-up card (see Elevation & Depth).
- **Border:** a 10% white inset outline.
- **Internal Padding:** 24px, rising to 40px from 640px.

### Inputs / Fields
- **Style:** white 5% fill, white 10% inset outline, 6px radius, 6px 12px padding, white text, grey placeholder.
- **Focus:** the outline thickens to 2px indigo.
- **Error:** the outline turns red-400 (`aria-invalid`), with red-400 text below.
- **Combobox list:** slate panel, 10% outline, float shadow; the active option fills indigo.

### Navigation
- **Rail:** slate card with a 10% hairline edge; each item is a 20px outline icon over an 11px medium label. Active is a white 10% wash with white text; inactive is muted grey that washes 5% on hover. The account avatar is a round white 10% button with the initial.

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

### Don't:
- **Don't** add a light theme or write light-mode classes; the app is always dark.
- **Don't** introduce a second accent hue, or use red, amber or emerald for anything but status.
- **Don't** animate exits; the next screen is never held back.
- **Don't** carry dev mode's belly world into the researcher app, or this system into dev mode.
