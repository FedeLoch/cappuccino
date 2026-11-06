// Holding the pen at the end of a stroke turns it into a clean line, ellipse, or box, as
// ShapeSnap.kt does on Android. Paths are flat arrays of canvas x and y pairs.

const MIN_LENGTH = 40;
const CLOSED_GAP = 0.2;
const LINE_WOBBLE = 0.08;
const SHAPE_WOBBLE = 0.1;
const GUIDE_DEGREES = 6;
const AXIS_DEGREES = 3;
const OUTLINE_POINTS = 64;
const LINE_POINTS = 32;
const SIDE_POINTS = 12;
const ELLIPSE_POINTS = 96;
const SEARCH_STEP_DEGREES = 2;

// True for an ellipse or box. A snapped ellipse runs one step past its start so the stroke joins
// without a gap, so its end lands about one step from its first point, while a line's end is many
// steps away.
function isClosed(shape) {
  const gap = Math.hypot(shape[shape.length - 2] - shape[0], shape[shape.length - 1] - shape[1]);
  if (gap < 1) return true;
  const step = Math.hypot(shape[2] - shape[0], shape[3] - shape[1]);
  return shape.length >= 8 && gap <= step * 1.5;
}

// Two handles for a snapped shape: a line's ends, or the corners of a closed shape's box.
export function shapeAnchors(shape) {
  if (!isClosed(shape)) return [shape[0], shape[1], shape[shape.length - 2], shape[shape.length - 1]];
  const xs = shape.filter((_, index) => index % 2 === 0);
  const ys = shape.filter((_, index) => index % 2 === 1);
  return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
}

// Moves a snapped shape so its handles land on target. Lines turn, closed shapes stretch.
export function reshapeShape(shape, anchors, target) {
  const spanX = anchors[2] - anchors[0];
  const spanY = anchors[3] - anchors[1];
  const result = shape.slice();
  if (isClosed(shape)) {
    const scaleX = Math.abs(spanX) < 1 ? 1 : (target[2] - target[0]) / spanX;
    const scaleY = Math.abs(spanY) < 1 ? 1 : (target[3] - target[1]) / spanY;
    for (let index = 0; index < result.length; index += 2) {
      result[index] = target[0] + (shape[index] - anchors[0]) * scaleX;
      result[index + 1] = target[1] + (shape[index + 1] - anchors[1]) * scaleY;
    }
    return result;
  }
  const toX = target[2] - target[0];
  const toY = target[3] - target[1];
  const square = Math.max(spanX * spanX + spanY * spanY, 1);
  for (let index = 0; index < result.length; index += 2) {
    const x = shape[index] - anchors[0];
    const y = shape[index + 1] - anchors[1];
    const along = (x * spanX + y * spanY) / square;
    const across = (y * spanX - x * spanY) / square;
    result[index] = target[0] + along * toX - across * toY;
    result[index + 1] = target[1] + along * toY + across * toX;
  }
  return result;
}

// A clean line, ellipse, or rectangle close to the path, or null. Lines lean toward vanishing
// points and level axes.
export function snapShape(path, vanishing = []) {
  const count = path.length / 2;
  if (count < 4) return null;
  let length = 0;
  for (let index = 1; index < count; index++) {
    length += Math.hypot(path[index * 2] - path[index * 2 - 2], path[index * 2 + 1] - path[index * 2 - 1]);
  }
  if (length < MIN_LENGTH) return null;
  const gap = Math.hypot(path[count * 2 - 2] - path[0], path[count * 2 - 1] - path[1]);
  return gap < length * CLOSED_GAP ? closedShape(path, length) : straightLine(path, vanishing);
}

function straightLine(path, vanishing) {
  const [startX, startY] = path;
  const dx = path[path.length - 2] - startX;
  const dy = path[path.length - 1] - startY;
  const chord = Math.hypot(dx, dy);
  for (let index = 0; index < path.length; index += 2) {
    const away = Math.abs((path[index] - startX) * dy - (path[index + 1] - startY) * dx) / chord;
    if (away > chord * LINE_WOBBLE) return null;
  }
  const heading = Math.atan2(dy, dx);
  const offBy = (direction) => {
    let turn = (heading - direction) % Math.PI;
    if (turn > Math.PI / 2) turn -= Math.PI;
    if (turn < -Math.PI / 2) turn += Math.PI;
    return Math.abs(turn) * 180 / Math.PI;
  };
  const guides = [];
  for (let index = 0; index + 1 < vanishing.length; index += 2) {
    guides.push(Math.atan2(vanishing[index + 1] - startY, vanishing[index] - startX));
  }
  const near = guides.filter((guide) => offBy(guide) < GUIDE_DEGREES).sort((a, b) => offBy(a) - offBy(b));
  const axis = [0, 1, 2, 3].map((n) => n * Math.PI / 4).find((direction) => offBy(direction) < AXIS_DEGREES);
  const aim = near.length ? near[0] : axis ?? heading;
  const reach = dx * Math.cos(aim) + dy * Math.sin(aim);
  const result = [];
  for (let step = 0; step <= LINE_POINTS; step++) {
    const share = step / LINE_POINTS;
    result.push(startX + reach * Math.cos(aim) * share, startY + reach * Math.sin(aim) * share);
  }
  return result;
}

// Evenly spaced points along the stroke, so slow parts of it don't count more.
function outline(path, length) {
  const step = length / OUTLINE_POINTS;
  const points = [];
  let walked = 0;
  for (let index = 1; index < path.length / 2; index++) {
    const fromX = path[index * 2 - 2];
    const fromY = path[index * 2 - 1];
    const piece = Math.hypot(path[index * 2] - fromX, path[index * 2 + 1] - fromY);
    while (points.length / 2 < OUTLINE_POINTS && piece > 0 && walked + piece >= (points.length / 2) * step) {
      const share = ((points.length / 2) * step - walked) / piece;
      points.push(fromX + (path[index * 2] - fromX) * share, fromY + (path[index * 2 + 1] - fromY) * share);
    }
    walked += piece;
  }
  return points;
}

function closedShape(path, length) {
  const points = outline(path, length);
  const count = points.length / 2;
  if (count < 8) return null;
  let bestArea = Infinity;
  let angle = 0;
  let box = [0, 0, 0, 0];
  for (let degrees = 0; degrees < 90; degrees += SEARCH_STEP_DEGREES) {
    const turn = degrees * Math.PI / 180;
    const cos = Math.cos(turn);
    const sin = Math.sin(turn);
    let minU = Infinity;
    let maxU = -Infinity;
    let minV = Infinity;
    let maxV = -Infinity;
    for (let i = 0; i < count; i++) {
      const u = points[i * 2] * cos + points[i * 2 + 1] * sin;
      const v = -points[i * 2] * sin + points[i * 2 + 1] * cos;
      minU = Math.min(minU, u);
      maxU = Math.max(maxU, u);
      minV = Math.min(minV, v);
      maxV = Math.max(maxV, v);
    }
    const area = (maxU - minU) * (maxV - minV);
    if (area < bestArea) {
      bestArea = area;
      angle = turn;
      box = [minU, minV, maxU, maxV];
    }
  }
  const halfWidth = (box[2] - box[0]) / 2;
  const halfHeight = (box[3] - box[1]) / 2;
  if (halfWidth < 1 || halfHeight < 1) return null;
  const centerU = (box[0] + box[2]) / 2;
  const centerV = (box[1] + box[3]) / 2;
  const cos = Math.cos(angle);
  const sin = Math.sin(angle);
  let ellipseMiss = 0;
  let boxMiss = 0;
  for (let index = 0; index < count; index++) {
    const x = points[index * 2];
    const y = points[index * 2 + 1];
    const u = x * cos + y * sin - centerU;
    const v = -x * sin + y * cos - centerV;
    const spread = Math.sqrt((u / halfWidth) ** 2 + (v / halfHeight) ** 2);
    ellipseMiss += Math.abs(spread - 1) * (halfWidth + halfHeight) / 2;
    boxMiss += Math.min(Math.abs(Math.abs(u) - halfWidth), Math.abs(Math.abs(v) - halfHeight));
  }
  if (Math.min(ellipseMiss, boxMiss) / count / (halfWidth + halfHeight) > SHAPE_WOBBLE) return null;
  const result = [];
  const place = (u, v) => {
    const fullU = u + centerU;
    const fullV = v + centerV;
    result.push(fullU * cos - fullV * sin, fullU * sin + fullV * cos);
  };
  if (ellipseMiss <= boxMiss) {
    for (let index = 0; index <= ELLIPSE_POINTS + 1; index++) {
      const turn = index * 2 * Math.PI / ELLIPSE_POINTS;
      place(halfWidth * Math.cos(turn), halfHeight * Math.sin(turn));
    }
    return result;
  }
  const corners = [[-1, -1], [1, -1], [1, 1], [-1, 1], [-1, -1]];
  for (let side = 0; side < 4; side++) {
    for (let step = 0; step < SIDE_POINTS; step++) {
      const share = step / SIDE_POINTS;
      place((corners[side][0] + (corners[side + 1][0] - corners[side][0]) * share) * halfWidth,
        (corners[side][1] + (corners[side + 1][1] - corners[side][1]) * share) * halfHeight);
    }
  }
  place(-halfWidth, -halfHeight);
  return result;
}
