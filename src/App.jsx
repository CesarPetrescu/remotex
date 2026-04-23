import { useEffect, useMemo, useRef, useState } from 'react';

const BOARD_SIZES = [32, 48, 64, 80];
const SPEEDS = [
  { label: 'Slow', value: 420 },
  { label: 'Cruise', value: 180 },
  { label: 'Fast', value: 72 },
];

const PATTERNS = {
  glider: {
    name: 'Glider',
    cells: [
      [1, 0],
      [2, 1],
      [0, 2],
      [1, 2],
      [2, 2],
    ],
  },
  pulsar: {
    name: 'Pulsar',
    cells: [
      [2, 0], [3, 0], [4, 0], [8, 0], [9, 0], [10, 0],
      [0, 2], [5, 2], [7, 2], [12, 2],
      [0, 3], [5, 3], [7, 3], [12, 3],
      [0, 4], [5, 4], [7, 4], [12, 4],
      [2, 5], [3, 5], [4, 5], [8, 5], [9, 5], [10, 5],
      [2, 7], [3, 7], [4, 7], [8, 7], [9, 7], [10, 7],
      [0, 8], [5, 8], [7, 8], [12, 8],
      [0, 9], [5, 9], [7, 9], [12, 9],
      [0, 10], [5, 10], [7, 10], [12, 10],
      [2, 12], [3, 12], [4, 12], [8, 12], [9, 12], [10, 12],
    ],
  },
  spaceship: {
    name: 'Spaceship',
    cells: [
      [1, 0], [4, 0],
      [0, 1],
      [0, 2], [4, 2],
      [0, 3], [1, 3], [2, 3], [3, 3],
    ],
  },
  beacon: {
    name: 'Beacon Field',
    cells: [
      [0, 0], [1, 0], [0, 1],
      [3, 2], [2, 3], [3, 3],
      [8, 0], [9, 0], [8, 1],
      [11, 2], [10, 3], [11, 3],
      [4, 8], [5, 8], [4, 9],
      [7, 10], [6, 11], [7, 11],
    ],
  },
};

function makeGrid(size, fill = 0) {
  return new Uint8Array(size * size).fill(fill);
}

function randomGrid(size) {
  const next = makeGrid(size);
  for (let i = 0; i < next.length; i += 1) {
    next[i] = Math.random() > 0.72 ? 1 : 0;
  }
  return next;
}

function placePattern(size, patternKey) {
  const pattern = PATTERNS[patternKey];
  const next = makeGrid(size);
  const maxX = Math.max(...pattern.cells.map(([x]) => x));
  const maxY = Math.max(...pattern.cells.map(([, y]) => y));
  const offsetX = Math.floor((size - maxX) / 2);
  const offsetY = Math.floor((size - maxY) / 2);

  pattern.cells.forEach(([x, y]) => {
    const px = x + offsetX;
    const py = y + offsetY;
    if (px >= 0 && px < size && py >= 0 && py < size) {
      next[py * size + px] = 1;
    }
  });

  return next;
}

function countNeighbors(grid, size, x, y, wrap) {
  let count = 0;

  for (let dy = -1; dy <= 1; dy += 1) {
    for (let dx = -1; dx <= 1; dx += 1) {
      if (dx === 0 && dy === 0) continue;

      let nx = x + dx;
      let ny = y + dy;

      if (wrap) {
        nx = (nx + size) % size;
        ny = (ny + size) % size;
      } else if (nx < 0 || nx >= size || ny < 0 || ny >= size) {
        continue;
      }

      count += grid[ny * size + nx];
    }
  }

  return count;
}

function stepGrid(grid, size, wrap) {
  const next = makeGrid(size);
  let births = 0;
  let deaths = 0;
  let changed = false;

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const index = y * size + x;
      const alive = grid[index] === 1;
      const neighbors = countNeighbors(grid, size, x, y, wrap);
      const survives = alive ? neighbors === 2 || neighbors === 3 : neighbors === 3;
      next[index] = survives ? 1 : 0;

      if (!alive && survives) births += 1;
      if (alive && !survives) deaths += 1;
      if (grid[index] !== next[index]) changed = true;
    }
  }

  return { grid: next, births, deaths, changed };
}

function cellFromPointer(event, canvas, size) {
  const rect = canvas.getBoundingClientRect();
  const x = Math.floor(((event.clientX - rect.left) / rect.width) * size);
  const y = Math.floor(((event.clientY - rect.top) / rect.height) * size);

  if (x < 0 || x >= size || y < 0 || y >= size) return null;
  return y * size + x;
}

export default function App() {
  const canvasRef = useRef(null);
  const dragValueRef = useRef(null);
  const [size, setSize] = useState(48);
  const [grid, setGrid] = useState(() => placePattern(48, 'glider'));
  const [running, setRunning] = useState(false);
  const [speed, setSpeed] = useState(180);
  const [wrap, setWrap] = useState(true);
  const [drawMode, setDrawMode] = useState('live');
  const [generation, setGeneration] = useState(0);
  const [activity, setActivity] = useState({ births: 0, deaths: 0, stable: false });

  const stats = useMemo(() => {
    let live = 0;
    for (let i = 0; i < grid.length; i += 1) live += grid[i];
    return {
      live,
      density: Math.round((live / grid.length) * 100),
    };
  }, [grid]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    const cell = canvas.width / size;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#f6f1e6';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    ctx.strokeStyle = size > 64 ? 'rgba(44, 55, 58, 0.09)' : 'rgba(44, 55, 58, 0.16)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= size; i += 1) {
      const pos = Math.round(i * cell) + 0.5;
      ctx.beginPath();
      ctx.moveTo(pos, 0);
      ctx.lineTo(pos, canvas.height);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(0, pos);
      ctx.lineTo(canvas.width, pos);
      ctx.stroke();
    }

    ctx.fillStyle = '#183d3f';
    for (let i = 0; i < grid.length; i += 1) {
      if (!grid[i]) continue;
      const x = i % size;
      const y = Math.floor(i / size);
      ctx.fillRect(x * cell + 1, y * cell + 1, Math.max(1, cell - 2), Math.max(1, cell - 2));
    }
  }, [grid, size]);

  useEffect(() => {
    if (!running) return undefined;
    const timer = window.setInterval(() => {
      setGrid((current) => {
        const result = stepGrid(current, size, wrap);
        setActivity({ births: result.births, deaths: result.deaths, stable: !result.changed });
        setGeneration((value) => value + 1);
        if (!result.changed) setRunning(false);
        return result.grid;
      });
    }, speed);

    return () => window.clearInterval(timer);
  }, [running, size, speed, wrap]);

  const replaceGrid = (nextGrid) => {
    setGrid(nextGrid);
    setGeneration(0);
    setActivity({ births: 0, deaths: 0, stable: false });
  };

  const updateCell = (index, forceValue) => {
    setGrid((current) => {
      const next = new Uint8Array(current);
      const value = forceValue ?? (drawMode === 'toggle' ? (next[index] ? 0 : 1) : drawMode === 'live' ? 1 : 0);
      if (next[index] === value) return current;
      next[index] = value;
      return next;
    });
  };

  const handlePointerDown = (event) => {
    const cell = cellFromPointer(event, canvasRef.current, size);
    if (cell === null) return;
    canvasRef.current.setPointerCapture(event.pointerId);
    const nextValue = drawMode === 'toggle' ? (grid[cell] ? 0 : 1) : drawMode === 'live' ? 1 : 0;
    dragValueRef.current = nextValue;
    updateCell(cell, nextValue);
  };

  const handlePointerMove = (event) => {
    if (dragValueRef.current === null) return;
    const cell = cellFromPointer(event, canvasRef.current, size);
    if (cell !== null) updateCell(cell, dragValueRef.current);
  };

  const handlePointerUp = () => {
    dragValueRef.current = null;
  };

  const stepOnce = () => {
    setGrid((current) => {
      const result = stepGrid(current, size, wrap);
      setActivity({ births: result.births, deaths: result.deaths, stable: !result.changed });
      setGeneration((value) => value + 1);
      return result.grid;
    });
  };

  const resizeBoard = (nextSize) => {
    const numericSize = Number(nextSize);
    setSize(numericSize);
    setRunning(false);
    replaceGrid(placePattern(numericSize, 'glider'));
  };

  return (
    <main className="life-app">
      <section className="hero">
        <div>
          <p className="eyebrow">Cellular automata</p>
          <h1>Game of Life</h1>
        </div>
        <div className="score-strip" aria-label="Simulation statistics">
          <div>
            <span>{generation}</span>
            <small>Generation</small>
          </div>
          <div>
            <span>{stats.live}</span>
            <small>Alive</small>
          </div>
          <div>
            <span>{stats.density}%</span>
            <small>Density</small>
          </div>
        </div>
      </section>

      <section className="game-shell" aria-label="Game of Life board">
        <aside className="control-panel" aria-label="Simulation controls">
          <div className="control-row primary-actions">
            <button className="action-button run" type="button" onClick={() => setRunning((value) => !value)}>
              <span aria-hidden="true">{running ? 'II' : '>'}</span>
              {running ? 'Pause' : 'Run'}
            </button>
            <button className="icon-button" type="button" aria-label="Step one generation" onClick={stepOnce}>
              <span aria-hidden="true">+1</span>
            </button>
            <button className="icon-button" type="button" aria-label="Clear board" onClick={() => replaceGrid(makeGrid(size))}>
              <span aria-hidden="true">X</span>
            </button>
          </div>

          <label className="field">
            Pattern
            <select onChange={(event) => replaceGrid(placePattern(size, event.target.value))} defaultValue="glider">
              {Object.entries(PATTERNS).map(([key, pattern]) => (
                <option key={key} value={key}>{pattern.name}</option>
              ))}
            </select>
          </label>

          <button className="wide-button" type="button" onClick={() => replaceGrid(randomGrid(size))}>
            Random seed
          </button>

          <fieldset className="segmented">
            <legend>Draw</legend>
            {[
              ['live', 'Live'],
              ['dead', 'Erase'],
              ['toggle', 'Flip'],
            ].map(([value, label]) => (
              <button
                key={value}
                type="button"
                aria-pressed={drawMode === value}
                onClick={() => setDrawMode(value)}
              >
                {label}
              </button>
            ))}
          </fieldset>

          <label className="field">
            Speed
            <select value={speed} onChange={(event) => setSpeed(Number(event.target.value))}>
              {SPEEDS.map((item) => (
                <option key={item.value} value={item.value}>{item.label}</option>
              ))}
            </select>
          </label>

          <label className="field">
            Grid
            <select value={size} onChange={(event) => resizeBoard(event.target.value)}>
              {BOARD_SIZES.map((item) => (
                <option key={item} value={item}>{item} x {item}</option>
              ))}
            </select>
          </label>

          <label className="switch">
            <input type="checkbox" checked={wrap} onChange={(event) => setWrap(event.target.checked)} />
            <span>Wrap edges</span>
          </label>

          <div className="activity">
            <div>
              <strong>{activity.births}</strong>
              <span>Births</span>
            </div>
            <div>
              <strong>{activity.deaths}</strong>
              <span>Deaths</span>
            </div>
            <div className={activity.stable ? 'steady' : ''}>
              <strong>{activity.stable ? 'Still' : 'Live'}</strong>
              <span>Status</span>
            </div>
          </div>
        </aside>

        <div className="board-wrap">
          <canvas
            ref={canvasRef}
            className="life-board"
            width="880"
            height="880"
            aria-label={`${size} by ${size} Game of Life board`}
            onPointerDown={handlePointerDown}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerUp}
            onPointerCancel={handlePointerUp}
            onPointerLeave={handlePointerUp}
          />
        </div>
      </section>
    </main>
  );
}
