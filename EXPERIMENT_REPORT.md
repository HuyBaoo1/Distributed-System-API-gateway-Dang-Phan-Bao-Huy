# Báo Cáo Thí Nghiệm Latency – API Gateway Rate Limiting

> **Lưu ý:** Các bảng kết quả được đánh dấu `TBD` là chưa có số liệu thật.
> Chạy `python run_latency_experiments.py --strategy-matrix` rồi `python plot_latency_report.py`
> để điền số liệu thực tế.

---

## Mục Lục

1. [Câu hỏi nghiên cứu](#1-câu-hỏi-nghiên-cứu)
2. [Môi trường thí nghiệm](#2-môi-trường-thí-nghiệm)
3. [Chiến lược và chính sách được so sánh](#3-chiến-lược-và-chính-sách-được-so-sánh)
4. [Ma trận kịch bản](#4-ma-trận-kịch-bản)
5. [Protocol chạy thí nghiệm](#5-protocol-chạy-thí-nghiệm)
6. [Kết quả – so sánh chiến lược](#6-kết-quả--so-sánh-chiến-lược)
7. [Kết quả – fault tolerance policy](#7-kết-quả--fault-tolerance-policy)
8. [Kết quả – burst behavior](#8-kết-quả--burst-behavior)
9. [Phân tích và diễn giải](#9-phân-tích-và-diễn-giải)
10. [Threats to validity](#10-threats-to-validity)
11. [Kết luận](#11-kết-luận)

---

## 1. Câu Hỏi Nghiên Cứu

| # | Câu hỏi |
|---|---------|
| RQ1 | Thuật toán rate limiting nào tạo **overhead thấp nhất** trong gateway khi backend không có delay? |
| RQ2 | Khi backend latency tăng lên 100ms và 500ms, phần overhead của rate limiter còn **ảnh hưởng đáng kể** đến p95/p99 gateway không? |
| RQ3 | Dưới burst traffic, fixed-window, sliding-window và token-bucket **khác nhau như thế nào** về tỷ lệ `429`, `Retry-After`, và tail latency? |
| RQ4 | Khi Redis gặp lỗi, ba fault policy (`fail-closed`, `fail-open`, `local-fallback`) làm thay đổi **safety và availability** ra sao? |
| RQ5 | `local-fallback` có giữ được throughput tương đương `fail-open` trong khi kiểm soát rate limit không? |

---

## 2. Môi Trường Thí Nghiệm

| Thành phần | Chi tiết |
|------------|----------|
| API Gateway | Spring Boot 3.x, embedded Tomcat |
| Mock backend | Spring Boot, tham số `delayMs` |
| Redis | 7-alpine, single-node, local Docker |
| Benchmark client | Python 3.11, `requests` + `ThreadPoolExecutor` |
| Máy chạy | _(điền: CPU, RAM, OS)_ |
| Docker version | _(điền)_ |
| Rate limit config | 60 req/min, window 60s |

### Topology

```
[Benchmark script]
     │  HTTP (concurrent threads)
     ▼
[API Gateway :808x]
     │  X-Gateway-Latency-Ms header
     │  X-RateLimit-Latency-Ms header  ──►  [Redis :6379]
     │  X-Backend-Latency-Ms header
     ▼
[Mock Backend :8081]
```

---

## 3. Chiến Lược và Chính Sách Được So Sánh

### 3.1 Rate-Limiting Strategies

| Strategy | Mô tả | Docker port | Đặc điểm kỹ thuật |
|----------|-------|------------|-------------------|
| `in-memory` | Bộ đếm local per-instance, không cần Redis | 8084 | Không atomic cross-instance; latency thấp nhất |
| `redis-fixed-window` | Atomic Lua `INCR + EXPIRE`; window cố định | 8082 | Boundary burst có thể cho 2× quota trong 1 giây |
| `redis-sliding-window` | Redis Sorted Set theo timestamp request | 8080 | Kiểm soát chính xác nhất; chi phí Redis cao nhất (ZADD + ZRANGEBYSCORE) |
| `redis-token-bucket` | Redis Hash lưu tokens + last_refill; Lua script | 8083 | Cho phép burst trong giới hạn capacity; làm mượt traffic |

### 3.2 Fault-Tolerance Policies

| Policy | Hành vi khi Redis down | Safety | Availability | Port (fixed-window) |
|--------|------------------------|--------|--------------|---------------------|
| `fail-closed` | Deny tất cả (429) | ★★★ | ★ | 8082 |
| `fail-open` | Allow tất cả (bypass limit) | ★ | ★★★ | 8090 |
| `local-fallback` | Dùng in-memory counter per instance | ★★ | ★★★ | 8091 |

---

## 4. Ma Trận Kịch Bản

| Scenario | Backend delay | Requests | Concurrency | Mục đích |
|----------|--------------|----------|-------------|----------|
| `baseline` | 0 ms | 200 | 20 | Đo pure gateway overhead |
| `delay-100` | 100 ms | 200 | 20 | Backend latency trung bình |
| `delay-500` | 500 ms | 200 | 20 | Backend latency cao |
| `overload` | 100 ms | 500 | 50 | Tải cao vượt rate limit |
| `burst` | 0 ms | 120 | 120 | Tất cả req gửi cùng lúc |
| `high-concur` | 50 ms | 400 | 80 | Concurrency cao |

---

## 5. Protocol Chạy Thí Nghiệm

### 5.1 Chuẩn bị

```bash
# Cài dependency Python
pip install -r requirements.txt

# Copy env template
cp .env.example .env

# Build và khởi động tất cả container (4 strategies × 3 policies = 10 containers)
docker compose up --build -d

# Kiểm tra health
docker compose ps
curl http://localhost:8082/api/v1/hello  # fixed-window
curl http://localhost:8083/api/v1/hello  # token-bucket
```

### 5.2 Chạy ma trận strategy (4 strategies × 4 scenarios)

```bash
python run_latency_experiments.py --strategy-matrix \
  --scenarios baseline,delay-100,delay-500,overload \
  --output-dir reports/strategy-matrix
```

### 5.3 Chạy ma trận fault-tolerance policy

```bash
# Fixed-window × 3 policies
python run_latency_experiments.py \
  --fault-policy-matrix \
  --strategies redis-fixed-window \
  --scenarios baseline,overload \
  --output-dir reports/fault-policy

# Token-bucket × 3 policies
python run_latency_experiments.py \
  --fault-policy-matrix \
  --strategies redis-token-bucket \
  --scenarios baseline,overload \
  --output-dir reports/fault-policy
```

### 5.4 Chạy burst experiment

```bash
# Fixed-window – thấy boundary burst
python burst_behavior_experiment.py \
  --label redis-fixed-window \
  --url http://localhost:8082/api/v1/hello \
  --align-to-window \
  --burst-size 60 \
  --output reports/burst/burst-fixed-window.json

# Token-bucket – so sánh kiểm soát burst
python burst_behavior_experiment.py \
  --label redis-token-bucket \
  --url http://localhost:8083/api/v1/hello \
  --align-to-window \
  --burst-size 60 \
  --output reports/burst/burst-token-bucket.json

# Sliding-window
python burst_behavior_experiment.py \
  --label redis-sliding-window \
  --url http://localhost:8080/api/v1/hello \
  --align-to-window \
  --burst-size 60 \
  --output reports/burst/burst-sliding-window.json
```

### 5.5 Vẽ biểu đồ

```bash
python plot_latency_report.py \
  --manifest reports/strategy-matrix/manifest.json \
  --metric gatewayP95Ms \
  --secondary-metric rejectionRate

python plot_latency_report.py \
  --manifest reports/fault-policy/manifest.json \
  --metric gatewayP95Ms
```

---

## 6. Kết Quả – So Sánh Chiến Lược

> Điền sau khi chạy `run_latency_experiments.py --strategy-matrix`

### 6.1 Gateway p95ms

| Strategy | baseline | delay-100 | delay-500 | overload |
|----------|----------|-----------|-----------|----------|
| `in-memory` | TBD | TBD | TBD | TBD |
| `redis-fixed-window` | TBD | TBD | TBD | TBD |
| `redis-sliding-window` | TBD | TBD | TBD | TBD |
| `redis-token-bucket` | TBD | TBD | TBD | TBD |

### 6.2 Rate-Limiter Overhead p95ms (X-RateLimit-Latency-Ms)

| Strategy | baseline | delay-100 | delay-500 | overload |
|----------|----------|-----------|-----------|----------|
| `in-memory` | TBD | TBD | TBD | TBD |
| `redis-fixed-window` | TBD | TBD | TBD | TBD |
| `redis-sliding-window` | TBD | TBD | TBD | TBD |
| `redis-token-bucket` | TBD | TBD | TBD | TBD |

### 6.3 Rejection Rate (429 / totalResponses)

| Strategy | baseline | delay-100 | delay-500 | overload |
|----------|----------|-----------|-----------|----------|
| `in-memory` | TBD | TBD | TBD | TBD |
| `redis-fixed-window` | TBD | TBD | TBD | TBD |
| `redis-sliding-window` | TBD | TBD | TBD | TBD |
| `redis-token-bucket` | TBD | TBD | TBD | TBD |

### 6.4 Throughput (rps)

| Strategy | baseline | delay-100 | delay-500 | overload |
|----------|----------|-----------|-----------|----------|
| `in-memory` | TBD | TBD | TBD | TBD |
| `redis-fixed-window` | TBD | TBD | TBD | TBD |
| `redis-sliding-window` | TBD | TBD | TBD | TBD |
| `redis-token-bucket` | TBD | TBD | TBD | TBD |

---

## 7. Kết Quả – Fault Tolerance Policy

> Điền sau khi chạy `run_latency_experiments.py --fault-policy-matrix`

### 7.1 redis-fixed-window × fault policy (scenario: overload)

| Fault Policy | Gateway p95ms | Rate-limiter p95ms | Rejection rate | Throughput rps |
|--------------|--------------|-------------------|----------------|----------------|
| `fail-closed` | TBD | TBD | TBD | TBD |
| `fail-open` | TBD | TBD | TBD | TBD |
| `local-fallback` | TBD | TBD | TBD | TBD |

### 7.2 redis-token-bucket × fault policy (scenario: overload)

| Fault Policy | Gateway p95ms | Rate-limiter p95ms | Rejection rate | Throughput rps |
|--------------|--------------|-------------------|----------------|----------------|
| `fail-closed` | TBD | TBD | TBD | TBD |
| `fail-open` | TBD | TBD | TBD | TBD |
| `local-fallback` | TBD | TBD | TBD | TBD |

**Quan sát dự kiến:** `fail-open` latency ≈ `local-fallback` latency khi Redis down, nhưng `local-fallback`
nên có rejection rate cao hơn `fail-open` (vì local counter vẫn enforce quota).

---

## 8. Kết Quả – Burst Behavior

> Điền sau khi chạy `burst_behavior_experiment.py` cho từng chiến lược

### 8.1 Requests thành công trước và sau boundary window

| Strategy | Before-boundary accepted | After-boundary accepted | 429 rate before | 429 rate after |
|----------|--------------------------|------------------------|-----------------|----------------|
| `redis-fixed-window` | TBD | TBD | TBD | TBD |
| `redis-sliding-window` | TBD | TBD | TBD | TBD |
| `redis-token-bucket` | TBD | TBD | TBD | TBD |

**Giải thích:** Fixed-window có thể accept 2× quota limit trong khoảng
`window_end - ε` đến `new_window + ε` — đây là boundary burst behavior đã biết.

### 8.2 Tail latency trong burst (client-observed p95ms)

| Strategy | All requests p95ms | Before-boundary p95ms | After-boundary p95ms |
|----------|-------------------|----------------------|---------------------|
| `redis-fixed-window` | TBD | TBD | TBD |
| `redis-sliding-window` | TBD | TBD | TBD |
| `redis-token-bucket` | TBD | TBD | TBD |

---

## 9. Phân Tích và Diễn Giải

### 9.1 Hướng dẫn đọc kết quả

- Nếu `rateLimiterHeader.p95Ms` **tăng** nhưng `backendHeader.p95Ms` thấp → bottleneck nằm ở rate limiter hoặc Redis round-trip.
- Nếu `backendHeader.p95Ms ≈ gatewayHeader.p95Ms` → downstream latency chi phối; rate limiter overhead không đáng kể.
- Nếu `clientObserved.p95Ms` **cao hơn nhiều** so với `gatewayHeader.p95Ms` → chênh lệch đến từ client-side queueing, network stack, hoặc OS scheduling trên máy benchmark.
- Nếu fixed-window có spike `accepted = 2×quota` quanh ranh giới window → đây là boundary burst behavior được thiết kế, không phải bug.
- Nếu token-bucket giảm spike 429 so với fixed-window trong cùng burst scenario → token bucket phù hợp hơn cho workload bursty.

### 9.2 Trade-off giữa các chiến lược

| Tiêu chí | in-memory | redis-fixed | redis-sliding | redis-token-bucket |
|---------|-----------|-------------|---------------|-------------------|
| Rate-limiter overhead | Thấp nhất | Thấp | Cao nhất | Trung bình |
| Accuracy (multi-instance) | Không đảm bảo | Tốt | Tốt nhất | Tốt |
| Burst control | Không | Yếu (boundary) | Tốt | Tốt (configurable capacity) |
| Redis commands / request | 0 | 1 Lua | 3 commands | 1 Lua |
| Memory footprint Redis | 0 | Thấp | Cao (sorted set) | Thấp |

### 9.3 Khi nào nên dùng fault policy nào

- `fail-closed`: hệ thống bảo vệ downstream service trước traffic spike là **ưu tiên cao nhất** (e.g. payment API).
- `fail-open`: **availability quan trọng hơn** strict quota enforcement (e.g. public read-only API).
- `local-fallback`: muốn giữ **cả availability lẫn partial rate control** khi Redis không ổn định. Trade-off: quota không còn global-accurate nếu có nhiều gateway instances.

---

## 10. Threats to Validity

| Loại | Mô tả | Ảnh hưởng |
|------|-------|-----------|
| **Internal** | Benchmark chạy cùng máy với service → CPU scheduling interference | Overstate latency variance |
| **Internal** | JVM warm-up (first 10-20 requests) có latency cao bất thường | Inflate mean/p99 nếu không loại bỏ warm-up |
| **Internal** | Python `ThreadPoolExecutor` không hoàn toàn concurrent tại OS level | Under-represent actual burst pressure |
| **External** | Single-node Redis local không đại diện cho Redis Cluster production | Rate-limiter latency thực tế production có thể cao hơn |
| **External** | Docker Desktop throttling (Windows/macOS) tạo thêm overhead | Kết quả phụ thuộc nền tảng |
| **Construct** | `X-Gateway-Latency-Ms` đo từ filter entry đến filter exit, chưa bao gồm Tomcat accept time | Undercount total gateway contribution |
| **Replication** | Kết quả phải lặp lại ít nhất 3 lần trước khi kết luận định lượng | Variance cao → cần warm-up + nhiều run |

---

## 11. Kết Luận

> Điền sau khi có số liệu thật từ thí nghiệm.

**Tổng kết dự kiến (dựa trên lý thuyết):**

1. `in-memory` có rate-limiter overhead thấp nhất (~0ms) nhưng không đảm bảo quota toàn cục.
2. `redis-fixed-window` có overhead nhỏ (1 round-trip Lua) và là lựa chọn tốt nhất khi không cần burst control nghiêm ngặt.
3. `redis-sliding-window` có overhead cao nhất do phải maintain sorted set, nhưng cho accuracy tốt nhất.
4. `redis-token-bucket` cân bằng tốt giữa burst tolerance và overhead.
5. Khi backend latency ≥ 100ms, rate-limiter overhead trở nên không đáng kể so với backend latency.
6. `fail-closed` là lựa chọn an toàn nhất; `local-fallback` là trade-off tốt cho production.

---

*Báo cáo được tạo từ template bởi run_latency_experiments.py và plot_latency_report.py.*
*Không có số liệu được điền thủ công – tất cả kết quả đến trực tiếp từ script.*
