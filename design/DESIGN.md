# Datebook design: Frutiger Aero

Datebook's look, decided in a design workshop in November 2026 (an interactive mockup of every screen, refined over
ten rounds). This page records every decision, so the app can be built to match it.

## The idea

A bright, optimistic Frutiger Aero look, the glossy late-2000s style of sky, water, glass and bubbles, made for a
phone today. Real-looking photo scenes sit behind frosted glass cards. They move gently, and their light follows the
real time of day.

- **Strength:** "modern revival": frosted glass and soft gloss rather than full 2007 gel, so text stays easy to read.
- **Colours:** sky blue, leaf green and aqua. Light mode by day; the night look comes from the scenes themselves.
- **Font:** a Segoe UI look. The app bundles **Selawik**, Microsoft's free (SIL Open Font License) look-alike.
- **In-app icons:** glass tiles (rounded glass squares with a split highlight and a coloured symbol).

## Scenes

Six photo-realistic scenes, each generated once at midday (the "master") and then relit, so that only the light
changes between times of day.

| Scene | What it shows | Moves |
|---|---|---|
| Lakeside city | A glass city across a mirror-still lake, big clouds, a grassy bank | Lake ripples and reflections, grass sway, sun glints |
| Aquarium | Half sky, half clear water with sun rays over sand | Water wobble, caustic light, 4 swimming fish, lens bubbles |
| Light ribbons | Glowing ribbons, glass orbs, sparkles | Ribbons flow, sparkles twinkle, a few lens bubbles |
| Dewdrop close-up | A big leaf covered in water drops, soft bokeh | Drops glint |
| Bubble field | Bright aqua light, glass orbs, ribbons and leaves | Orbs bob, lens bubbles rise |
| Tropical beach | Turquoise sea, white sand, a palm leaning in | Sea ripples and glints, palm sway |

Which screen shows which scene:

| Screens | Scene |
|---|---|
| Home | Lakeside city |
| Suggestion card | Dewdrop close-up |
| Share sheet | Tropical beach |
| Setup | Bubble field |
| Settings menu | Light ribbons |
| Settings pages (Apps, Calendars, Claude, ...) | Aquarium (these pages are sparse, so the fish stay visible) |

## Time of day

Every screen shows the same light at the same moment.

- **Six lights per scene:** night, blue hour, morning, midday, golden hour, sunset.
- **Sun times** are worked out on the phone from the date and a rough place (standard NOAA sunrise and sunset
  formulas), with no internet needed. The place is the phone's last known location (remembered for when there's
  none). Without one, it's the home address from Places & travel, located once with Android's geocoder and then
  remembered. Without either, it's a guess from the time zone. It never asks for a new permission, and the place
  stays on the phone.
- **Keyframes** are minutes relative to today's sunrise and sunset. Between two keyframes with different lights, the two
  pictures cross-fade with an eased (smoothstep) curve, updated continuously, so nothing ever jumps.

| Minute | Light |
|---|---|
| sunrise − 80 | night |
| sunrise − 25 | blue hour |
| sunrise + 25 to sunrise + 110 | morning |
| sunrise + 230 to sunset − 220 | midday |
| sunset − 95 | golden hour |
| sunset − 30 to sunset + 10 | sunset |
| sunset + 45 | blue hour |
| sunset + 95 | night |

- **Before the first keyframe and after the last,** it's night.
- **Late night:** from 11 PM (eased in over 30 minutes) to 5 AM (eased out over 30 minutes), the scene dims by up to
  30%, so a glance in bed isn't bright.
- **Always dark UI:** the app's cards, bars and text always use the dark look, whatever the phone's dark mode is set
  to, so nothing glares over the photos. The scenes still follow the sun. (Decided after the first phone test; it
  replaces the earlier rule that the phone's dark mode showed the night pictures all day.)

## Motion

All movement bends the photo's own pixels, or moves photo-real cutouts, so it looks as real as the pictures.

- **Engine:** an AGSL `RuntimeShader` (Android 13 and later) over the two current light pictures. On older phones, the
  cross-faded pictures stay still.
- **Masks:** one small picture per scene says where each effect may act. Red is water (ripples, caustics), green is
  sway or flow (grass, palm, ribbons), blue is glints and bobbing (water, drops, orbs).
- **Strengths** (fractions of the screen):

| Scene | Water | Sway | Flow | Bob | Caustics | Glints | Lens bubbles |
|---|---|---|---|---|---|---|---|
| Lakeside city | 0.0035 | 0.006 | | | | 0.9 | |
| Aquarium | 0.005 | | | | 0.45 | 0.5 | 8 (below the surface) |
| Light ribbons | | | 0.006 | | | 0.9 | 3 |
| Dewdrop close-up | | | | | | 1.2 | |
| Bubble field | | | | 0.006 | | 0.9 | 9 |
| Tropical beach | 0.0035 | 0.004 | | | | 0.9 | |

- **Wind:** sway comes in gusts that roll across the grass (and the palm) every few seconds; the blades bend, flutter
  and catch a little more light as each gust passes.
- **Lens bubbles:** each one bends the picture behind it like a lens, with a rainbow rim and a shine. They rise
  from below the screen and leave off the top; in the Aquarium they pop at the water's surface.
- **Fish:** goldfish, clownfish, blue tang and yellow tang cutouts, drawn in thin strips so the tail swings as they
  swim. They're tinted bluer and darker as night falls.
- **Surprises:** about every 3 minutes, a giant soap bubble drifts across the screen for 14 seconds. In the Aquarium a
  big goldfish swims by up close instead.
- **Battery:** motion runs only while Datebook is on screen. It stops in battery saver and when Android's "remove
  animations" setting is on (both checked every 5 seconds), and it's capped at 30 frames a second.
- **Moving backgrounds switch:** Settings → Look, on to start. Switched off, the scenes stand still but still change
  with the time of day.
- **Share sheet:** its beach moves too. It's framed on the open water, with stronger waves (0.008) and 6 lens bubbles
  rising behind the glass, so the motion shows through the wash.

## Glass

The app is always dark, so all the glass is dark glass with light text.

- **Cards:** medium frost: glass at 66% opacity over an 18dp blur of the moving scene behind, made with the Haze
  library (2.0.1). Android 12 and later blurs; older phones get the tint as a solid scrim. Cards have 22dp corners and
  a faint light edge.
- **Tint:** the glass takes on the light, blending with the scene as it fades (Compose blends the colours in the
  perceptual Oklab space).

| Light | Glass colour (RGB) |
|---|---|
| Night | 12, 24, 54 (navy) |
| Blue hour | 26, 30, 70 (indigo) |
| Morning | 46, 40, 58 (dusky lilac) |
| Midday | 16, 44, 72 (deep sky blue) |
| Golden hour | 62, 44, 28 (amber) |
| Sunset | 64, 30, 50 (plum) |

- **Next up card:** a glossy blue gradient (sky blue to aqua) with white text, instead of glass.
- **Section labels:** small uppercase text on a tinted pill (no blur, since labels can sit on cards).
- **Text:** #EEF5FF; secondary text #B4C6E0 with a soft shadow so it reads straight on the scene; links #9FD8FF;
  warnings #FFBE6A; label text #D6E8FF.
- **Buttons:** glossy gel pills with a soft highlight across the top half and a light edge, 38dp tall (32dp in lists),
  with bold 14 labels in normal case ("Add", "Ignore"). Sky-blue gel for main actions, leaf-green gel for adding a
  plan, glass pills with light-blue text for secondary actions, and plain light-blue text for small ones.
- **Switches:** glossy gel: a leaf-green track when on, a dark glass track when off, a shiny white knob.
- **Text fields:** glass tinted by the light, 12dp corners, a faint light edge that turns light blue with focus.
- **Setup progress:** a Vista-style bar of green gel in a dark glass track.
- **Loose text:** supporting text that sits straight on the scene gets its own small frosted backing (inside cards it
  doesn't need one).
- **Type:** Selawik with no added letter spacing; card text 15, supporting text 13.5, card titles 18 on Next up.
- **Share sheet:** a 55% wash of the light's glass colour over the beach, with the plans on a glass card.
- **Top bar:** clear. The scene runs to the top of the screen and the title floats over it with a soft shadow, with
  glass tile buttons for Back and Settings.
- **Scene veil:** a faint 12% dark wash over the scene, so the brightest midday spots never glare behind loose text.

## App icon

- **Picture:** a small glossy calendar inside a soap bubble, over a softly blurred lakeside.
- **Adaptive icon:**
  - **Background layer:** the lakeside.
  - **Foreground layer:** the bubble, sized to 56% of the 108dp layer so every launcher shape shows it whole. The two
    layers move apart a little when the launcher animates them.
- **Themed icon (Android 13+):** a one-colour vector of a bubble outline with a shine and a small calendar inside.
- **Play Store:** a 512 × 512 picture ([icon/play-store.png](icon/play-store.png)); Google Play shows it as a rounded
  square.
- **Splash screen (Android 12+):** Android shows the adaptive icon on the dark start-up background by itself.

## Notifications

- **Small icon:** the themed icon's silhouette (bubble and calendar).
- **Accent colour:** Aero aqua, #0E86C4, for the app name and the action buttons.
- **Picture:** none.

## How the pictures were made

[make_backgrounds.py](make_backgrounds.py) makes every picture with Google's Nano Banana (`gemini-nano-banana-2.1`)
and stops at a spending cap. It reads the API key from the environment and never stores it. Its generated files go in
`design/backgrounds/candidates/`, which is gitignored.

| Command | What it makes |
|---|---|
| `python design/make_backgrounds.py test` | One image, to check the key and the model |
| `python design/make_backgrounds.py all` | Every scene at morning, midday and sunset, 2 versions each, plus the fish on white |
| `python design/make_backgrounds.py relight` | Each picked midday master redrawn in the other five lights, plus the effect masks |
| `python design/make_backgrounds.py icons` | 4 icon concepts on 3 backgrounds |
| `python design/make_backgrounds.py iconlayers` | The picked icon without its bubble, as the background layer |
| `python design/make_backgrounds.py models` | The image models the key can use |

**Picked masters:** lake midday version 2, and version 1 of every other scene. **Picked fish:** version 1 of each.
Making every picture cost about $14 at the script's estimate of $0.15 per image.

[make_app_scenes.py](make_app_scenes.py) turns the picked masters and their relights into the app's pictures in
`android/app/src/main/res/drawable-nodpi`: the 36 scene pictures (1080-wide WebP), the six packed effect masks
(`mask_*.png`, 384 × 688) and the four fish cutouts (`fish_*.png`, 420 wide, white cut away), 6.6 MB in total.

[make_icon.py](make_icon.py) splits the picked icon into its two 432-pixel layers (`ic_launcher_background.webp` and
`ic_launcher_bubble.webp` in the same drawables folder) and makes the Play Store icon. The themed icon and the
notification icon are hand-drawn vectors (`ic_launcher_monochrome.xml`, `ic_stat_datebook.xml`).

## Building it

Planned in steps, each tested on a real phone:

1. The pictures and the time-of-day light (still pictures with eased fades; works on every Android version). Done.
2. The glass: Haze cards, tints, the clear top bar, Selawik, and glass tile icons. Done.
3. The motion: the AGSL shader with masks, the fish, lens bubbles and surprises, and the battery rules. Done.
4. Dark mode and late-night dimming. Done as part of steps 1 to 3 (the app is always dark; the late dimming is in
   both the still and the moving scenes).
5. The app icon and notifications. Done.
