// The figure guide, as Figure.kt: points you drag onto a drawn body or face, plain notes on how
// its proportions read, and a lift into 3D for the mannequin. Points are canvas pixels, x then y.

export const HEAD_TOP = 0;
export const CHIN = 1;
export const NECK = 2;
export const PELVIS = 9;
export const BODY_JOINTS = 16;
export const FACE_POINTS = 6;

// Each joint's parent, so moving a joint carries everything that hangs from it.
export const BODY_PARENTS = [1, 2, 9, 2, 3, 4, 2, 6, 7, -1, 9, 10, 11, 9, 13, 14];

// The standing pose in head units, x to the picture's right, y up, z toward you, feet at zero.
export const STANDARD_POSE = [
  0, 7.5, 0, 0, 6.5, 0.05, 0, 6.2, 0,
  0.9, 6, 0, 1, 4.6, -0.05, 1.05, 3.45, 0.05,
  -0.9, 6, 0, -1, 4.6, -0.05, -1.05, 3.45, 0.05,
  0, 3.75, 0,
  0.5, 3.6, 0, 0.52, 1.8, 0.05, 0.5, 0.25, -0.05,
  -0.5, 3.6, 0, -0.52, 1.8, 0.05, -0.5, 0.25, -0.05,
];

export const BODY_BONES = BODY_PARENTS.map((parent, joint) => [parent, joint]).filter(([parent]) => parent >= 0);
export const BODY_LANDMARKS = [[1, "chin"], [2, "chest"], [3, "elbows"], [4, "wrists"], [5.75, "knees"], [7.5, "feet"]];
export const FACE_LEVELS = [[0.5, "eyes"], [0.69, "nose"], [0.79, "mouth"]];

const note = (text, values = [], joints = [], warning = true) => ({ text, values, joints, warning });

function standardLength(from, to) {
  return Math.hypot(
    STANDARD_POSE[to * 3] - STANDARD_POSE[from * 3],
    STANDARD_POSE[to * 3 + 1] - STANDARD_POSE[from * 3 + 1],
    STANDARD_POSE[to * 3 + 2] - STANDARD_POSE[from * 3 + 2],
  );
}

const heads = (value) => {
  const tenths = Math.round(value * 10);
  return `${Math.floor(tenths / 10)}.${tenths % 10}`;
};

// A standing figure filling most of the canvas height, centered.
export function defaultBody(width, height) {
  const unit = height * 0.85 / 7.6;
  const bottom = height * 0.925;
  return Array.from({ length: BODY_JOINTS * 2 }, (_, index) => {
    const joint = Math.floor(index / 2);
    return index % 2 === 0 ? width / 2 + STANDARD_POSE[joint * 3] * unit : bottom - STANDARD_POSE[joint * 3 + 1] * unit;
  });
}

// Moves a joint and everything that hangs from it.
export function moveJoint(points, joint, dx, dy) {
  for (let index = 0; index < BODY_JOINTS; index++) {
    let walk = index;
    while (walk >= 0 && walk !== joint) walk = BODY_PARENTS[walk];
    if (walk === joint) {
      points[index * 2] += dx;
      points[index * 2 + 1] += dy;
    }
  }
}

// Limbs as named in notes. Sides are named as they appear in the picture.
function limbs(rightArm, leftArm, rightLeg, leftLeg) {
  return [
    ["upper arm on the right", rightArm, rightArm + 1], ["forearm on the right", rightArm + 1, rightArm + 2],
    ["upper arm on the left", leftArm, leftArm + 1], ["forearm on the left", leftArm + 1, leftArm + 2],
    ["thigh on the right", rightLeg, rightLeg + 1], ["shin on the right", rightLeg + 1, rightLeg + 2],
    ["thigh on the left", leftLeg, leftLeg + 1], ["shin on the left", leftLeg + 1, leftLeg + 2],
  ].map(([name, from, to]) => ({ name, from, to }));
}

// How the figure's proportions read, warnings first.
export function analyzeBody(points) {
  const x = (joint) => points[joint * 2];
  const y = (joint) => points[joint * 2 + 1];
  const span = (a, b) => Math.hypot(x(b) - x(a), y(b) - y(a));
  const head = span(HEAD_TOP, CHIN);
  if (head < 1) return [];
  const notes = [];
  const rightArm = x(3) >= x(6) ? 3 : 6;
  const rightLeg = x(10) >= x(13) ? 10 : 13;
  const parts = limbs(rightArm, 9 - rightArm, rightLeg, 23 - rightLeg);
  const lowest = Math.max(y(12), y(15));
  const standing = lowest - y(PELVIS) > head * 2.5 && y(PELVIS) > y(NECK);

  if (standing) {
    const tall = (lowest - y(HEAD_TOP)) / head + 0.15;
    const reading = tall < 5.5 ? "That reads young or stylized."
      : tall < 7 ? "That's a sturdy, natural build."
        : tall <= 8.2 ? "That's close to the classic seven and a half."
          : "That's long and heroic, as in fashion or comics.";
    notes.push(note("The figure is %1 heads tall.", [heads(tall)], [], false));
    notes.push(note(reading, [], [], false));
  }
  const torso = span(NECK, PELVIS) / head;
  if (torso < 1.9) notes.push(note("The head reads big for the body, like a child or a cartoon.", [], [HEAD_TOP, CHIN]));
  if (torso > 3.2) notes.push(note("The head reads small for the body.", [], [HEAD_TOP, CHIN]));
  const shoulders = span(3, 6) / head;
  if (shoulders < 1.3) {
    notes.push(note("The shoulders are %1 heads across. About two is typical, unless the body turns.", [heads(shoulders)], [3, 6]));
  } else if (shoulders > 2.6) {
    notes.push(note("The shoulders are %1 heads across, which reads very broad. About two is typical.", [heads(shoulders)], [3, 6]));
  }
  const ratios = new Map(parts.map((limb) => [limb, span(limb.from, limb.to) / head / standardLength(limb.from, limb.to)]));
  const flagged = new Set();
  for (const limb of parts) {
    const ratio = ratios.get(limb);
    const typical = standardLength(limb.from, limb.to);
    if (ratio > 1.2) {
      flagged.add(limb);
      notes.push(note("The %1 is %2 heads long; it's usually about %3, so it reads long.",
        [limb.name, heads(ratio * typical), heads(typical)], [limb.from, limb.to]));
    } else if (ratio < 0.7) {
      flagged.add(limb);
      notes.push(note("The %1 reads short. That's right if it points toward or away from us.", [limb.name], [limb.from, limb.to], false));
    }
  }
  for (const [a, b] of [[parts[0], parts[2]], [parts[1], parts[3]], [parts[4], parts[6]], [parts[5], parts[7]]]) {
    if (flagged.has(a) || flagged.has(b)) continue;
    const ra = ratios.get(a);
    const rb = ratios.get(b);
    if (Math.max(ra, rb) / Math.max(Math.min(ra, rb), 0.01) > 1.25) {
      notes.push(note("The %1 and the %2 differ in length. Unless one turns toward us, match them.",
        [a.name, b.name], [a.from, a.to, b.from, b.to]));
    }
  }
  if (standing) {
    for (const [shoulder, side] of [[rightArm, "right"], [9 - rightArm, "left"]]) {
      const elbow = shoulder + 1;
      const wrist = shoulder + 2;
      const hanging = y(wrist) > y(elbow) && y(elbow) > y(shoulder) &&
        Math.abs(x(elbow) - x(shoulder)) < (y(elbow) - y(shoulder)) * 0.5 &&
        Math.abs(x(wrist) - x(elbow)) < (y(wrist) - y(elbow)) * 0.6;
      if (!hanging) continue;
      const waist = y(PELVIS) - head * 0.85;
      const crotch = y(PELVIS) + head * 0.25;
      if (Math.abs(y(elbow) - waist) > head * 0.5) {
        notes.push(note(y(elbow) < waist ? "The elbow on the %1 sits high. A hanging arm bends at the waist."
          : "The elbow on the %1 sits low. A hanging arm bends at the waist.", [side], [elbow]));
      } else if (Math.abs(y(wrist) - crotch) > head * 0.55) {
        notes.push(note(y(wrist) < crotch ? "The wrist on the %1 sits high. A hanging arm reaches the crotch at the wrist."
          : "The wrist on the %1 sits low. A hanging arm reaches the crotch at the wrist.", [side], [wrist]));
      }
    }
    const feetLeft = Math.min(x(12), x(15)) - head * 0.35;
    const feetRight = Math.max(x(12), x(15)) + head * 0.35;
    if (x(NECK) < feetLeft || x(NECK) > feetRight) {
      notes.push(note("The neck isn't above the feet, so the figure looks like it's tipping. Move the feet under it, or give it something to lean on.",
        [], [NECK, 12, 15]));
    }
  }
  if (!notes.some((it) => it.warning)) notes.push(note("The proportions read well.", [], [], false));
  return [...notes.filter((it) => it.warning), ...notes.filter((it) => !it.warning)];
}

// The face: crown, chin, eye on the left, eye on the right, nose base, mouth.
export function defaultFace(width, height) {
  const size = Math.min(width, height) * 0.5;
  const cx = width / 2;
  const top = height / 2 - size / 2;
  return [cx, top, cx, top + size, cx - size * 0.15, top + size * 0.5, cx + size * 0.15, top + size * 0.5,
    cx, top + size * 0.69, cx, top + size * 0.79];
}

// Where a face point sits: how far down from crown to chin, and how far across, in face lengths.
export function facePlace(points, index) {
  const ax = points[2] - points[0];
  const ay = points[3] - points[1];
  const length2 = ax * ax + ay * ay;
  if (length2 < 1) return [0, 0];
  const px = points[index * 2] - points[0];
  const py = points[index * 2 + 1] - points[1];
  return [(px * ax + py * ay) / length2, (px * -ay + py * ax) / length2];
}

export function analyzeFace(points) {
  const length = Math.hypot(points[2] - points[0], points[3] - points[1]);
  if (length < 1) return [];
  const notes = [];
  const [leftDown, leftAcross] = facePlace(points, 2);
  const [rightDown, rightAcross] = facePlace(points, 3);
  const [noseDown, noseAcross] = facePlace(points, 4);
  const [mouthDown, mouthAcross] = facePlace(points, 5);
  const eyes = (leftDown + rightDown) / 2;
  if (Math.abs(eyes - 0.5) > 0.06) {
    notes.push(note(eyes < 0.5 ? "The eyes sit high. They're usually halfway between the top of the head and the chin."
      : "The eyes sit low. They're usually halfway between the top of the head and the chin.", [], [2, 3]));
  }
  if (Math.abs(leftDown - rightDown) > 0.04) notes.push(note("The eyes aren't level with each other. Tilt the whole head, or level them.", [], [2, 3]));
  const apart = Math.hypot(points[4] - points[6], points[5] - points[7]) / length;
  if (apart < 0.22) notes.push(note("The eyes are close together. About one eye's width between them is typical.", [], [2, 3]));
  if (apart > 0.38) notes.push(note("The eyes are far apart. About one eye's width between them is typical.", [], [2, 3]));
  if (Math.abs(noseDown - 0.69) > 0.07) {
    notes.push(note(noseDown < 0.69 ? "The nose ends high. Its base usually sits a little above two thirds of the way down."
      : "The nose ends low. Its base usually sits a little above two thirds of the way down.", [], [4]));
  }
  if (Math.abs(mouthDown - 0.79) > 0.06) {
    notes.push(note(mouthDown < 0.79 ? "The mouth sits high. It's usually a third of the way from the nose to the chin."
      : "The mouth sits low. It's usually a third of the way from the nose to the chin.", [], [5]));
  }
  const middle = (leftAcross + rightAcross) / 2;
  if (Math.abs(noseAcross - middle) > 0.08 || Math.abs(mouthAcross - middle) > 0.08) {
    notes.push(note("The nose and mouth aren't between the eyes. If the head turns, move the eyes the same way.", [], [4, 5]));
  }
  if (!notes.length) notes.push(note("The face reads in proportion.", [], [], false));
  return notes;
}

// Which way the face guide turns, as yaw and pitch in radians.
export function faceTurn(points) {
  const [leftDown, leftAcross] = facePlace(points, 2);
  const [rightDown, rightAcross] = facePlace(points, 3);
  const [, noseAcross] = facePlace(points, 4);
  // Across runs to the face's own right, which is the picture's left when it faces you.
  const sideways = (leftAcross + rightAcross) / 2 - noseAcross;
  const down = (leftDown + rightDown) / 2 - 0.5;
  const clamp = (value) => Math.min(1, Math.max(-1, value));
  return [Math.asin(clamp(sideways / 0.3)), Math.asin(clamp(down / 0.25))];
}

// Lifts the drawn figure into 3D: each bone keeps its usual length, so a bone drawn shorter must
// point toward or away from you. Arms and thighs come forward, shins go back.
export function liftBody(points) {
  const head = Math.max(Math.hypot(points[2] - points[0], points[3] - points[1]), 1);
  const px = points[PELVIS * 2];
  const py = points[PELVIS * 2 + 1];
  const result = new Array(BODY_JOINTS * 3).fill(0);
  for (let joint = 0; joint < BODY_JOINTS; joint++) {
    result[joint * 3] = (points[joint * 2] - px) / head;
    result[joint * 3 + 1] = -(points[joint * 2 + 1] - py) / head;
  }
  const forward = new Set([4, 5, 7, 8, 11, 14]);
  const backward = new Set([12, 15]);
  for (const joint of [PELVIS, NECK, CHIN, HEAD_TOP, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15]) {
    const parent = BODY_PARENTS[joint];
    if (parent < 0) continue;
    const drawn = Math.hypot(result[joint * 3] - result[parent * 3], result[joint * 3 + 1] - result[parent * 3 + 1]);
    const usual = standardLength(parent, joint);
    const depth = Math.sqrt(Math.max(0, usual * usual - drawn * drawn));
    const sign = forward.has(joint) ? 1 : backward.has(joint) ? -1 : 0;
    result[joint * 3 + 2] = result[parent * 3 + 2] + depth * sign;
  }
  return result;
}

function posed(changes) {
  const pose = [...STANDARD_POSE];
  for (const [joint, [x, y, z]] of changes) pose.splice(joint * 3, 3, x, y, z);
  return pose;
}

// Ready poses for the 3D mannequin, as MANNEQUIN_POSES in Figure.kt.
export const MANNEQUIN_POSES = [
  ["Stand", STANDARD_POSE],
  ["Walk", posed([[4, [1, 4.65, -0.4]], [5, [1.05, 3.55, -0.65]], [7, [-1, 4.6, 0.35]], [8, [-1.05, 3.5, 0.6]],
    [11, [0.52, 1.85, 0.45]], [12, [0.5, 0.3, 0.6]], [14, [-0.52, 1.85, -0.35]], [15, [-0.5, 0.5, -0.85]]])],
  ["Sit", posed([[4, [1, 4.75, 0.6]], [5, [0.85, 4, 1.5]], [7, [-1, 4.75, 0.6]], [8, [-0.85, 4, 1.5]],
    [11, [0.55, 3.5, 1.8]], [12, [0.55, 1.95, 1.9]], [14, [-0.55, 3.5, 1.8]], [15, [-0.55, 1.95, 1.9]]])],
  ["Reach", posed([[4, [1.1, 7.35, 0.1]], [5, [1.2, 8.5, 0.2]]])],
];

// Draws the guide on the overlay. toScreen maps canvas pixels to overlay pixels; unit is one
// density-independent pixel there.
export function drawFigureGuide(c, { body, face, showFace, flagged, landmarks, editing }, toScreen, unit, translate) {
  const accent = "#D85A30";
  const strokes = (path) => {
    c.lineCap = "round";
    c.lineWidth = 4.5 * unit;
    c.strokeStyle = "rgba(30, 22, 18, 0.43)";
    c.stroke(path);
    c.lineWidth = 2.5 * unit;
    c.strokeStyle = "#FFFFFF";
    c.stroke(path);
  };
  const dashed = (path) => {
    c.setLineDash([8 * unit, 6 * unit]);
    c.lineWidth = 1.5 * unit;
    c.strokeStyle = accent;
    c.stroke(path);
    c.setLineDash([]);
  };
  const caption = (text, x, y) => {
    c.font = `${11 * unit}px Roboto, "Segoe UI", system-ui, sans-serif`;
    c.fillStyle = accent;
    c.fillText(translate(text), x, y + 4 * unit);
  };
  const point = ([x, y], warn) => {
    c.beginPath();
    c.arc(x, y, (editing ? 6.5 : 4.5) * unit, 0, Math.PI * 2);
    c.fillStyle = warn ? accent : "#FFFFFF";
    c.fill();
    c.lineWidth = 1.5 * unit;
    c.strokeStyle = "rgba(30, 22, 18, 0.63)";
    c.stroke();
  };
  const oval = (top, bottom, share) => {
    const length = Math.hypot(bottom[0] - top[0], bottom[1] - top[1]);
    const angle = Math.atan2(bottom[1] - top[1], bottom[0] - top[0]) - Math.PI / 2;
    const path = new Path2D();
    path.ellipse((top[0] + bottom[0]) / 2, (top[1] + bottom[1]) / 2, length * share, length / 2, angle, 0, Math.PI * 2);
    return { path, length, angle };
  };
  const drawBody = () => {
    const spots = Array.from({ length: BODY_JOINTS }, (_, i) => toScreen(body[i * 2], body[i * 2 + 1]));
    const head = Math.hypot(spots[CHIN][0] - spots[HEAD_TOP][0], spots[CHIN][1] - spots[HEAD_TOP][1]);
    if (landmarks && head > 2) {
      const right = Math.max(...spots.map(([x]) => x)) + head * 0.6;
      for (const [count, name] of BODY_LANDMARKS) {
        const y = spots[HEAD_TOP][1] + count * head;
        const tick = new Path2D();
        tick.moveTo(right - head * 0.25, y);
        tick.lineTo(right + head * 0.25, y);
        dashed(tick);
        caption(name, right + head * 0.35, y);
      }
    }
    const bones = new Path2D();
    for (const [from, to] of BODY_BONES) {
      if (from === CHIN && to === HEAD_TOP) continue;
      bones.moveTo(...spots[from]);
      bones.lineTo(...spots[to]);
    }
    strokes(bones);
    strokes(oval(spots[HEAD_TOP], spots[CHIN], 0.38).path);
    spots.forEach((spot, index) => point(spot, flagged.has(index)));
  };
  const drawFace = () => {
    const spots = Array.from({ length: FACE_POINTS }, (_, i) => toScreen(face[i * 2], face[i * 2 + 1]));
    const { path, length, angle } = oval(spots[0], spots[1], 0.37);
    if (length < 2) return;
    strokes(path);
    const cx = (spots[0][0] + spots[1][0]) / 2;
    const cy = (spots[0][1] + spots[1][1]) / 2;
    c.save();
    c.translate(cx, cy);
    c.rotate(angle);
    const half = length * 0.37;
    const center = new Path2D();
    center.moveTo(0, -length / 2);
    center.lineTo(0, length / 2);
    dashed(center);
    for (const [level, name] of FACE_LEVELS) {
      const y = -length / 2 + level * length;
      const line = new Path2D();
      line.moveTo(-half * 0.9, y);
      line.lineTo(half * 0.9, y);
      dashed(line);
      caption(name, half + 6 * unit, y);
    }
    c.restore();
    spots.forEach((spot, index) => point(spot, flagged.has(index)));
  };
  if (showFace) {
    if (face) drawFace();
  } else if (body) {
    drawBody();
  }
  if (!editing) {
    // Both guides show while you draw; only the one being edited shows while editing.
    if (showFace && body) drawBody();
    if (!showFace && face) drawFace();
  }
}
