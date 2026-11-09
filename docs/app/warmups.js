// The warm-up exercises, from WARMUPS in app/src/main/java/art/cappuccino/paint/Warmups.kt.
// Each topic matches one of the companion's note topics, so the one your notes keep raising
// comes first. Generated from the Kotlin list; keep the two in step.

export const WARMUPS = [
  { kind: "STRAIGHT_LINES", group: "Lines and precision", topic: "Next step", title: "Dot to dot",
    steps: "Join each pair of dots with one confident straight line. Move from your shoulder, not your wrist.",
    purpose: "It trains the long, confident strokes behind clean lines and perspective, and teaches your eye to aim before your hand moves." },
  { kind: "HATCHING", group: "Lines and precision", topic: "Next step", title: "Even hatching",
    steps: "Fill each box with parallel lines, all at the same angle and evenly spaced.",
    purpose: "It builds the steady control you need to shade with lines, so tones look even instead of scratchy." },
  { kind: "ELLIPSES", group: "Lines and precision", topic: "Next step", title: "Ellipses",
    steps: "Draw an ellipse in each box, in one stroke, touching all four sides.",
    purpose: "Ellipses hide in cups, wheels, eyes, and every round form seen at an angle. Fitting them to a box trains proportion." },
  { kind: "TRACING", group: "Lines and precision", topic: "Next step", title: "Steady tracing",
    steps: "Trace each gray path as closely as you can, slowly and in one stroke.",
    purpose: "Slow tracing builds precise hand control, which makes curves and contours easier when you draw freely." },
  { kind: "PERSPECTIVE", group: "Lines and precision", topic: "Perspective", title: "Two-point boxes",
    steps: "Each gray line is a box's front corner. Draw the boxes, sending every side edge to one of the two dots on the horizon.",
    purpose: "Two-point perspective makes rooms, streets, and furniture sit believably in space. Aiming each edge trains your eye for it." },
  { kind: "LETTERING", group: "Lines and precision", topic: "Next step", title: "Lettering",
    steps: "Letter each word on its lines. Small letters reach the dashed line, tall ones the top line, and every letter sits on the baseline.",
    purpose: "Even letters make titles and labels look designed rather than written. It trains rhythm and a steady baseline." },
  { kind: "MIRROR", group: "Shapes", topic: "Silhouette", title: "Mirror it",
    steps: "Draw the other half of the shape on the right, as its mirror image across the dashed line.",
    purpose: "Symmetry is hard to judge by eye. Mirroring a curve trains you to compare distances and spot what's off." },
  { kind: "SILHOUETTES", group: "Shapes", topic: "Silhouette", title: "Silhouettes",
    steps: "Turn each stick figure into a solid black silhouette. Keep the pose easy to read.",
    purpose: "A pose that reads as a silhouette reads at any size. It trains clear gesture and shape design." },
  { kind: "SPHERE_LIGHT", group: "Light and shadow", topic: "Light", title: "Spheres in the sun",
    steps: "Each sphere has its own sun. Paint the side facing it, a core shadow, and a cast shadow on the ground.",
    purpose: "The sphere is the simplest form that shows light turning. Once it works, heads, fruit, and clouds follow." },
  { kind: "CUBE_SHADOW", group: "Light and shadow", topic: "Light", title: "Forms in the sun",
    steps: "Each form has its own sun. Make the planes facing it lightest and the ones facing away darkest.",
    purpose: "Boxes and cylinders hide inside everything you draw. Lighting their planes teaches how form turns." },
  { kind: "FACE_EYE", group: "Faces", topic: "Light", title: "An eye",
    steps: "Draw the eye over the construction lines, then shade it with light from the sun.",
    purpose: "Eyes draw attention first. Knowing their construction keeps faces believable from any angle." },
  { kind: "FACE_NOSE", group: "Faces", topic: "Light", title: "A nose",
    steps: "Draw the nose over the construction, then shade the side away from the sun.",
    purpose: "The nose is mostly planes and shadow. Thinking in planes keeps it from looking flat or outlined." },
  { kind: "FACE_LIPS", group: "Faces", topic: "Light", title: "Lips",
    steps: "Draw the lips over the guide. Shade the upper lip darker, since it faces down.",
    purpose: "Lips turn from shadow above to light below. Getting that right makes a mouth feel soft and real." },
  { kind: "THREE_VALUES", group: "Values", topic: "Values", title: "Three values",
    steps: "Fill this little landscape with only three values: one dark, one middle, and one light.",
    purpose: "Strong images often work in three values. It trains you to group the scene before adding detail." },
  { kind: "GRADIENT", group: "Values", topic: "Values", title: "Smooth blend",
    steps: "Fill the box with one smooth blend, from dark at the black dot to light at the white one.",
    purpose: "Smooth blends are how round forms, skies, and glows are painted. It trains gentle control of pressure and opacity." },
  { kind: "VALUE_SCALE", group: "Values", topic: "Values", title: "Value scale",
    steps: "Fill the five boxes from lightest to darkest, in even steps.",
    purpose: "Even steps from light to dark are the base of every shaded drawing. It trains your eye to judge value." },
  { kind: "ONE_HUE", group: "Color", topic: "Palette", title: "One main hue",
    steps: "Color this little landscape so one hue leads, with two quiet ones supporting it.",
    purpose: "A leading hue makes a palette feel unified. It trains restraint with color." },
  { kind: "WARM_COOL", group: "Color", topic: "Palette", title: "Warm light, cool shadow",
    steps: "Paint the sphere with warm light from the sun and cool shadows.",
    purpose: "Warm light with cool shadows is one of the quickest ways to make color feel lit and alive." },
  { kind: "HUE_SHIFT", group: "Color", topic: "Palette", title: "Shift the hue",
    steps: "Shade the sphere without black or gray. Let the hue move as it gets darker.",
    purpose: "Shadows painted with a shifted hue glow instead of going muddy. It trains richer color." },
];

export const WARMUP_GROUPS = [...new Set(WARMUPS.map((warmup) => warmup.group))];

// Topics a warm-up can help with, in the order a note's topic is checked.
export const WARMUP_TOPICS = [...new Set(WARMUPS.map((warmup) => warmup.topic))];

// What the companion suggests first, from the topic your notes raised most recently.
export function warmupFor(topic, day) {
  const matching = WARMUPS.filter((warmup) => warmup.topic === topic);
  const list = matching.length ? matching : WARMUPS;
  return list[((day % list.length) + list.length) % list.length];
}
