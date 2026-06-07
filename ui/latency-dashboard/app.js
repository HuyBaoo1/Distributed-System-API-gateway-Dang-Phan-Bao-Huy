const state = {
  rows: [],
  fileName: "",
};

const STRATEGY_COLORS = {
  "in-memory": "#4c9be8",
  "redis-fixed-window": "#f4a261",
  "redis-sliding-window": "#2a9d8f",
  "redis-token-bucket": "#e76f51",
};

const els = {
  dataFile: document.getElementById("dataFile"),
  scenarioSelect: document.getElementById("scenarioSelect"),
  sortSelect: document.getElementById("sortSelect"),
  policySelect: document.getElementById("policySelect"),
  datasetStatus: document.getElementById("datasetStatus"),
  strategyChart: document.getElementById("strategyChart"),
  metricsBody: document.getElementById("metricsBody"),
  rowCount: document.getElementById("rowCount"),
  activeScenario: document.getElementById("activeScenario"),
  highlightList: document.getElementById("highlightList"),
  bestGateway: document.getElementById("bestGateway"),
  bestGatewayName: document.getElementById("bestGatewayName"),
  bestLimiter: document.getElementById("bestLimiter"),
  bestLimiterName: document.getElementById("bestLimiterName"),
  bestRejection: document.getElementById("bestRejection"),
  bestRejectionName: document.getElementById("bestRejectionName"),
  bestThroughput: document.getElementById("bestThroughput"),
  bestThroughputName: document.getElementById("bestThroughputName"),
};

els.dataFile.addEventListener("change", handleFile);
els.scenarioSelect.addEventListener("change", render);
els.sortSelect.addEventListener("change", render);
els.policySelect.addEventListener("change", render);

async function handleFile(event) {
  const file = event.target.files?.[0];
  if (!file) return;

  const text = await file.text();
  const lowerName = file.name.toLowerCase();
  const rows = lowerName.endsWith(".json")
    ? rowsFromJson(text)
    : rowsFromCsv(text);

  state.rows = normalizeRows(rows);
  state.fileName = file.name;
  rebuildControls();
  render();
}

function rowsFromJson(text) {
  const payload = JSON.parse(text);
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload.comparisons)) return payload.comparisons;
  if (Array.isArray(payload.rows)) return payload.rows;
  return [];
}

function rowsFromCsv(text) {
  const lines = text
    .replace(/^\uFEFF/, "")
    .split(/\r?\n/)
    .filter((line) => line.trim().length > 0);

  if (lines.length < 2) return [];

  const headers = splitCsvLine(lines[0]).map((item) => item.trim());
  return lines.slice(1).map((line) => {
    const values = splitCsvLine(line);
    return headers.reduce((row, header, index) => {
      row[header] = values[index] ?? "";
      return row;
    }, {});
  });
}

function splitCsvLine(line) {
  const values = [];
  let current = "";
  let quoted = false;

  for (let index = 0; index < line.length; index += 1) {
    const char = line[index];
    const next = line[index + 1];

    if (char === '"' && quoted && next === '"') {
      current += '"';
      index += 1;
      continue;
    }

    if (char === '"') {
      quoted = !quoted;
      continue;
    }

    if (char === "," && !quoted) {
      values.push(current);
      current = "";
      continue;
    }

    current += char;
  }

  values.push(current);
  return values;
}

function normalizeRows(rows) {
  return rows
    .map((row) => {
      const strategy = value(row.strategy) || inferStrategy(row.targetName) || "unknown";
      return {
        targetName: value(row.targetName) || strategy,
        strategy,
        faultPolicy: value(row.faultPolicy) || "fail-closed",
        scenario: value(row.scenario) || "unknown",
        trialCount: number(row.trialCount),
        throughputRequestsPerSecond: number(row.throughputRequestsPerSecond),
        rejectionRate: number(row.rejectionRate),
        clientP50Ms: number(row.clientP50Ms),
        clientP95Ms: number(row.clientP95Ms),
        clientP99Ms: number(row.clientP99Ms),
        gatewayP50Ms: number(row.gatewayP50Ms),
        gatewayP95Ms: number(row.gatewayP95Ms),
        gatewayP99Ms: number(row.gatewayP99Ms),
        backendP95Ms: number(row.backendP95Ms),
        backendP99Ms: number(row.backendP99Ms),
        rateLimiterP50Ms: number(row.rateLimiterP50Ms),
        rateLimiterP95Ms: number(row.rateLimiterP95Ms),
        rateLimiterP99Ms: number(row.rateLimiterP99Ms),
      };
    })
    .filter((row) => row.targetName && row.scenario);
}

function value(input) {
  return input === undefined || input === null ? "" : String(input).trim();
}

function number(input) {
  if (input === undefined || input === null || input === "") return null;
  const parsed = Number(input);
  return Number.isFinite(parsed) ? parsed : null;
}

function inferStrategy(targetName) {
  const target = value(targetName);
  if (!target) return "";
  return target.split("@")[0];
}

function rebuildControls() {
  const scenarios = unique(state.rows.map((row) => row.scenario));
  const policies = unique(state.rows.map((row) => row.faultPolicy));
  replaceOptions(els.scenarioSelect, scenarios);
  replaceOptions(els.policySelect, ["all", ...policies]);
  [els.scenarioSelect, els.sortSelect, els.policySelect].forEach((select) => {
    select.disabled = state.rows.length === 0;
  });
}

function replaceOptions(select, values) {
  select.innerHTML = "";
  values.forEach((item) => {
    const option = document.createElement("option");
    option.value = item;
    option.textContent = item;
    select.appendChild(option);
  });
}

function unique(values) {
  return [...new Set(values.filter(Boolean))];
}

function render() {
  const filtered = filteredRows();
  const sortMetric = els.sortSelect.value || "gatewayP95Ms";
  const sorted = [...filtered].sort((left, right) => compareRows(left, right, sortMetric));

  els.datasetStatus.textContent = state.rows.length
    ? `${state.fileName}: ${state.rows.length} measured rows`
    : "No dataset loaded";
  els.rowCount.textContent = `${sorted.length} rows`;
  els.activeScenario.textContent = els.scenarioSelect.value || "-";

  renderSummary(sorted);
  renderChart(sorted);
  renderHighlights(sorted);
  renderTable(sorted);
}

function filteredRows() {
  const scenario = els.scenarioSelect.value;
  const policy = els.policySelect.value;
  return state.rows.filter((row) => {
    const scenarioMatch = !scenario || row.scenario === scenario;
    const policyMatch = !policy || policy === "all" || row.faultPolicy === policy;
    return scenarioMatch && policyMatch;
  });
}

function compareRows(left, right, metric) {
  const leftValue = metricValue(left, metric);
  const rightValue = metricValue(right, metric);
  if (metric === "throughputRequestsPerSecond") {
    return rightValue - leftValue;
  }
  return leftValue - rightValue;
}

function metricValue(row, metric) {
  const value = row[metric];
  return value === null || value === undefined ? Number.POSITIVE_INFINITY : value;
}

function renderSummary(rows) {
  const bestGateway = bestBy(rows, "gatewayP95Ms", "min");
  const bestLimiter = bestBy(rows, "rateLimiterP95Ms", "min");
  const bestRejection = bestBy(rows, "rejectionRate", "min");
  const bestThroughput = bestBy(rows, "throughputRequestsPerSecond", "max");

  setMetric(els.bestGateway, els.bestGatewayName, bestGateway, "gatewayP95Ms", "ms");
  setMetric(els.bestLimiter, els.bestLimiterName, bestLimiter, "rateLimiterP95Ms", "ms");
  setMetric(els.bestRejection, els.bestRejectionName, bestRejection, "rejectionRate", "%");
  setMetric(els.bestThroughput, els.bestThroughputName, bestThroughput, "throughputRequestsPerSecond", "rps");
}

function bestBy(rows, metric, direction) {
  const valid = rows.filter((row) => row[metric] !== null && row[metric] !== undefined);
  if (!valid.length) return null;
  return valid.reduce((best, row) => {
    if (!best) return row;
    return direction === "max"
      ? row[metric] > best[metric] ? row : best
      : row[metric] < best[metric] ? row : best;
  }, null);
}

function setMetric(valueEl, nameEl, row, metric, unit) {
  if (!row) {
    valueEl.textContent = "-";
    nameEl.textContent = "-";
    return;
  }
  valueEl.textContent = formatMetric(row[metric], unit);
  nameEl.textContent = row.targetName;
}

function renderChart(rows) {
  if (!rows.length) {
    els.strategyChart.className = "strategy-chart empty";
    els.strategyChart.innerHTML = "<p>Load `latency_comparison.csv` or `manifest.json` from a real experiment run.</p>";
    return;
  }

  els.strategyChart.className = "strategy-chart";
  const maxGateway = maxOf(rows, "gatewayP95Ms");
  const maxLimiter = maxOf(rows, "rateLimiterP95Ms");
  const maxClient = maxOf(rows, "clientP95Ms");
  const bestGateway = bestBy(rows, "gatewayP95Ms", "min");

  els.strategyChart.innerHTML = rows.map((row) => {
    const color = STRATEGY_COLORS[row.strategy] || "#637083";
    const isWinner = bestGateway && row.targetName === bestGateway.targetName;
    return `
      <article class="strategy-row ${isWinner ? "winner" : ""}">
        <div class="strategy-name">
          <strong><span class="target-dot" style="background:${color}"></span> ${escapeHtml(row.targetName)}</strong>
          <span>${escapeHtml(row.strategy)} / ${escapeHtml(row.faultPolicy)}</span>
        </div>
        <div class="bar-stack">
          ${barLine("Gateway p95", row.gatewayP95Ms, maxGateway, "gateway")}
          ${barLine("Limiter p95", row.rateLimiterP95Ms, maxLimiter, "limiter")}
          ${barLine("Client p95", row.clientP95Ms, maxClient, "client")}
          ${barLine("429 rate", row.rejectionRate, 1, "rejection", "%")}
        </div>
      </article>
    `;
  }).join("");
}

function barLine(label, value, max, kind, unit = "ms") {
  const width = value === null || max <= 0 ? 0 : Math.max(1, Math.min(100, (value / max) * 100));
  return `
    <div class="bar-line">
      <span>${label}</span>
      <div class="bar-track"><div class="bar-fill ${kind}" style="width:${width}%"></div></div>
      <strong>${formatMetric(value, unit)}</strong>
    </div>
  `;
}

function maxOf(rows, metric) {
  return Math.max(0, ...rows.map((row) => row[metric]).filter((item) => item !== null && item !== undefined));
}

function renderHighlights(rows) {
  if (!rows.length) {
    els.highlightList.innerHTML = "<li>No measured data loaded.</li>";
    return;
  }

  const bestGateway = bestBy(rows, "gatewayP95Ms", "min");
  const bestLimiter = bestBy(rows, "rateLimiterP95Ms", "min");
  const worstRejection = bestBy(rows, "rejectionRate", "max");
  const maxGateway = bestBy(rows, "gatewayP95Ms", "max");
  const items = [];

  if (bestGateway) {
    items.push(`${bestGateway.targetName} has the lowest gateway p95 at ${formatMetric(bestGateway.gatewayP95Ms, "ms")}.`);
  }
  if (bestLimiter) {
    items.push(`${bestLimiter.targetName} has the lowest rate limiter overhead at ${formatMetric(bestLimiter.rateLimiterP95Ms, "ms")}.`);
  }
  if (maxGateway && bestGateway && maxGateway.targetName !== bestGateway.targetName) {
    const delta = maxGateway.gatewayP95Ms - bestGateway.gatewayP95Ms;
    items.push(`Gateway p95 spread is ${formatMetric(delta, "ms")} between fastest and slowest targets.`);
  }
  if (worstRejection && worstRejection.rejectionRate > 0) {
    items.push(`Highest 429 rate is ${formatMetric(worstRejection.rejectionRate, "%")} on ${worstRejection.targetName}.`);
  }

  els.highlightList.innerHTML = items.map((item) => `<li>${escapeHtml(item)}</li>`).join("");
}

function renderTable(rows) {
  if (!rows.length) {
    els.metricsBody.innerHTML = '<tr><td colspan="9" class="empty-cell">No measured data loaded.</td></tr>';
    return;
  }

  els.metricsBody.innerHTML = rows.map((row) => {
    const color = STRATEGY_COLORS[row.strategy] || "#637083";
    return `
      <tr>
        <td><span class="target-pill"><span class="target-dot" style="background:${color}"></span>${escapeHtml(row.targetName)}</span></td>
        <td>${escapeHtml(row.strategy)}</td>
        <td>${escapeHtml(row.faultPolicy)}</td>
        <td>${formatMetric(row.gatewayP95Ms, "ms")}</td>
        <td>${formatMetric(row.rateLimiterP95Ms, "ms")}</td>
        <td>${formatMetric(row.backendP95Ms, "ms")}</td>
        <td>${formatMetric(row.clientP95Ms, "ms")}</td>
        <td>${formatMetric(row.rejectionRate, "%")}</td>
        <td>${formatMetric(row.throughputRequestsPerSecond, "rps")}</td>
      </tr>
    `;
  }).join("");
}

function formatMetric(value, unit) {
  if (value === null || value === undefined || Number.isNaN(value)) return "-";
  if (unit === "%") return `${(value * 100).toFixed(1)}%`;
  if (unit === "rps") return `${value.toFixed(1)} rps`;
  return `${value.toFixed(2)} ms`;
}

function escapeHtml(input) {
  return String(input)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
