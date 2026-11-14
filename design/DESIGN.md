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
| Lakeside city | 0.0035 | 0.0022 | | | | 0.9 | |
| Aquarium | 0.005 | | | | 0.45 | 0.5 | 8 (below the surface) |
| Light ribbons | | | 0.006 | | | 0.9 | 3 |
| Dewdrop close-up | | | | | | 1.2 | |
| Bubble field | | | | 0.006 | | 0.9 | 9 |
| Tropical beach | 0.0035 | 0.004 | | | | 0.9 | |

- **Lens bubbles:** each one bends the picture behind it like a lens, with a rainbow rim and a shine.
- **Fish:** goldfish, clownfish, blue tang and yellow tang cutouts, drawn in thin strips so the tail swings as they
  swim. They're tinted bluer and darker as night falls.
- **Surprises:** about every 3 minutes, a giant soap bubble drifts across the screen for 14 seconds. In the Aquarium a
  big goldfish swims by up close instead.
- **Battery:** motion runs only while Datebook is on screen. It stops in battery saver and when Android's "remove
  animations" setting is on, and it's capped at 30 frames a second.

## Glass

**To redo in step 2:** with the UI always dark, the glass becomes dark glass with light text all day. The tints
below were designed for light glass by day and get re-decided then.

- **Cards:** medium frost, glass at 66% opacity with an 18px blur behind it. The blur is live over the moving scene, so
  the build uses the Haze library.
- **Tint:** the glass takes on the light, cross-fading with the scene.

| Light | Glass colour (RGB) |
|---|---|
| Midday | 255, 255, 255 |
| Morning | 255, 249, 238 |
| Golden hour | 255, 238, 208 |
| Sunset | 255, 226, 220 |
| Blue hour | 214, 224, 255 |
| Night | 16, 32, 70 |

- **Text** switches to light colours when the glass gets dark (brightness below 45%):

| | Day | Night |
|---|---|---|
| Text | #0E2A3D | #EEF5FF |
| Secondary text | #3F6178 | #B4C6E0 |
| Links and glass buttons | #0B6FB8 | #9FD8FF |
| Warnings | #B25F00 | #FFBE6A |

- **Top bar:** clear. The scene runs to the top of the screen and the title floats over it with a soft shadow.

## App icon

- **Picture:** a small glossy calendar inside a soap bubble, over a softly blurred lakeside.
- **Adaptive icon:**
  - **Background layer:** the lakeside.
  - **Foreground layer:** the bubble, sized to 56% of the 108dp layer so every launcher shape shows it whole. The two
    layers move apart a little when the launcher animates them.
- **Themed icon (Android 13+):** a one-colour vector of a bubble outline with a shine and a small calendar inside.
- **Play Store:** a 512 × 512 picture; Google Play shows it as a rounded square.

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

[make_app_scenes.py](make_app_scenes.py) turns the picked masters and their relights into the app's 36 scene
pictures: 1080-wide WebP files in `android/app/src/main/res/drawable-nodpi`, 5.8 MB in total.

The workshop also packed the masks, cut the white off the fish, and split the icon into its layers. Those helper
scripts move into this folder with the build steps that use them.

## Building it

Planned in steps, each tested on a real phone:

1. The pictures and the time-of-day light (still pictures with eased fades; works on every Android version).
2. The glass: Haze cards, tints, the clear top bar, Selawik, and glass tile icons.
3. The motion: the AGSL shader with masks, the fish, lens bubbles and surprises, and the battery rules.
4. Dark mode and late-night dimming.
5. The app icon and notifications.
