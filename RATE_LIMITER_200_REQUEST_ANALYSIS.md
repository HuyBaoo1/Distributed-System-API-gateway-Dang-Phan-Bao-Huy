# Phân tích kết quả rate limiter với 200 request

## Bối cảnh thử nghiệm

Dashboard đang hiển thị scenario `baseline` với 4 strategy:

| Strategy | Policy | Gateway p95 | Rate limiter p95 | Backend p95 | Client p95 | 429 rate | Throughput |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `redis-sliding-window` | `fail-closed` | 82.60 ms | 21.29 ms | 49.11 ms | 108.29 ms | 70.0% | 564.4 rps |
| `in-memory` | `fail-closed` | 252.89 ms | 22.76 ms | 119.77 ms | 301.86 ms | 70.0% | 306.3 rps |
| `redis-token-bucket` | `fail-closed` | 1114.05 ms | 963.93 ms | 93.68 ms | 1169.78 ms | 69.5% | 131.3 rps |
| `redis-fixed-window` | `fail-closed` | 1355.45 ms | 1199.77 ms | 85.54 ms | 1438.01 ms | 70.0% | 113.7 rps |

Với 200 request, tỷ lệ `429` khoảng 69.5-70.0%, nghĩa là chỉ khoảng 60-61 request được đi tiếp, còn khoảng 139-140 request bị rate limiter chặn. Đây là dấu hiệu limiter đang hoạt động đúng nếu quota kỳ vọng gần 60 request trong cửa sổ đo.

## Ý nghĩa từng thông số

| Thông số | Ý nghĩa | Cách đọc khi làm API Gateway |
| --- | --- | --- |
| `targetName` | Tên endpoint/instance được benchmark | Ví dụ `redis-sliding-window`, `in-memory`. |
| `strategy` | Thuật toán rate limiter đang dùng | Dùng để so sánh overhead và hành vi reject. |
| `faultPolicy` | Chính sách khi dependency của limiter lỗi | `fail-closed` ưu tiên bảo vệ quota bằng cách reject khi không chắc chắn. |
| `scenario` | Kịch bản test | `baseline` thường là backend không delay nhân tạo. |
| `trialCount` | Số lần chạy được gom kết quả | Càng nhiều trial thì kết luận càng đáng tin hơn. |
| `durationSeconds` | Tổng thời gian hoàn thành batch request | Thấp hơn thường tốt hơn, nhưng cần đọc chung với error và 429. |
| `throughputRequestsPerSecond` | Số response/giây client nhận được | Cao hơn tốt hơn nếu latency và error vẫn ổn. |
| `totalResponses` | Tổng response hoặc kết quả được client ghi nhận | Dùng làm mẫu số cho rejection/error rate. |
| `rejected429` | Số request bị trả HTTP `429 Too Many Requests` | Đây là request bị rate limiter chặn. |
| `errorCount` | Số lỗi client hoặc exception, ví dụ timeout/connection error | Nếu tăng, hệ thống không chỉ bị limit mà còn mất ổn định. |
| `rejectionRate` | `rejected429 / totalResponses` | 70% nghĩa là 7/10 request bị chặn. |
| `clientP50Ms` | Median latency nhìn từ client | Bao gồm network, queue ở client, gateway, backend. |
| `clientP95Ms` | 95% request có latency thấp hơn hoặc bằng giá trị này | Metric chính để đọc tail latency người dùng thấy. |
| `clientP99Ms` | 99% request có latency thấp hơn hoặc bằng giá trị này | Nhạy với request chậm bất thường. |
| `clientMaxMs` | Request chậm nhất | Hữu ích để phát hiện timeout hoặc pause lớn. |
| `gatewayP50Ms` | Median latency do gateway đo | Thời gian xử lý trong gateway, thường không gồm toàn bộ overhead client-side. |
| `gatewayP95Ms` | Tail latency p95 tại gateway | Dùng để so sánh strategy rate limiter ảnh hưởng gateway ra sao. |
| `gatewayP99Ms` | Tail latency p99 tại gateway | Cho biết mức dao động xấu nhất gần cuối phân phối. |
| `backendP95Ms` | p95 thời gian backend xử lý | Nếu gần bằng gateway/client p95, bottleneck nằm ở backend. |
| `backendP99Ms` | p99 thời gian backend xử lý | Dùng để phát hiện backend spike. |
| `rateLimiterP50Ms` | Median overhead của bước kiểm tra quota | Chi phí thông thường của limiter. |
| `rateLimiterP95Ms` | p95 overhead của limiter | Metric rất quan trọng để biết Redis/Lua/lock có nghẽn không. |
| `rateLimiterP99Ms` | p99 overhead của limiter | Nếu tăng mạnh, limiter có tail latency xấu. |

## Nhận xét kết quả 200 request

### 1. `redis-sliding-window` là kết quả tốt nhất ở baseline

`redis-sliding-window` có `gateway p95 = 82.60 ms`, thấp nhất trong 4 strategy. `rate limiter p95 = 21.29 ms` cũng thấp nhất. Throughput đạt `564.4 rps`, cao nhất dashboard. Với cùng mức reject 70%, strategy này đang cho cân bằng tốt nhất giữa độ chính xác quota và latency.

Sliding window thường chính xác hơn fixed window vì nó xét các request trong khoảng thời gian trượt thực tế, tránh việc client lợi dụng ranh giới window. Đổi lại, khi số request rất lớn, Redis sorted set có thể tăng chi phí lưu/xóa phần tử cũ nếu không được tối ưu TTL và cleanup.

### 2. `in-memory` có latency trung bình khá nhưng không phù hợp khi scale nhiều gateway

`in-memory` có `gateway p95 = 252.89 ms`, cao hơn sliding window nhưng vẫn thấp hơn nhiều so với token bucket và fixed window trong lần đo này. Vì không cần gọi Redis, về lý thuyết limiter local phải rất nhanh. Tuy nhiên `rate limiter p95 = 22.76 ms` gần sliding window, còn `backend p95 = 119.77 ms` và `client p95 = 301.86 ms` cao hơn, nên batch này có thể chịu ảnh hưởng từ scheduling, backend hoặc queue nội bộ.

Điểm yếu lớn nhất của in-memory là quota không còn global. Nếu chạy 3 instance gateway, mỗi instance giữ bộ đếm riêng, client có thể được cấp khoảng 3 lần quota nếu load balancer phân phối đều.

### 3. `redis-token-bucket` đang bị overhead limiter rất cao

`redis-token-bucket` có `rate limiter p95 = 963.93 ms` và `gateway p95 = 1114.05 ms`. Vì `backend p95 = 93.68 ms`, phần lớn tail latency đến từ limiter hoặc đường đi tới Redis, không phải backend.

Token bucket phù hợp workload có burst ngắn vì bucket cho phép dùng token tích lũy rồi refill theo tốc độ trung bình. Nhưng nếu implementation phải thao tác Redis chậm, bị lock, script Lua nặng, connection pool thiếu, hoặc Redis đang quá tải, token bucket sẽ biến thành bottleneck.

### 4. `redis-fixed-window` là chậm nhất trong lần đo này

`redis-fixed-window` có `gateway p95 = 1355.45 ms`, `rate limiter p95 = 1199.77 ms`, throughput chỉ `113.7 rps`. Backend p95 chỉ `85.54 ms`, nên bottleneck nằm chủ yếu ở bước rate limiting.

Fixed window thường đơn giản và rẻ, nhưng có nhược điểm boundary burst: client có thể gửi nhiều request cuối window cũ và đầu window mới, tạo burst gần gấp đôi quota trong thời gian rất ngắn. Trong kết quả này, vấn đề nổi bật hơn lại là overhead Redis/limiter.

## Điều gì có thể xảy ra khi chạy nhiều request hơn?

### Trường hợp 1: Tăng request nhưng vẫn trong cùng một window quota

Nếu quota hiệu dụng vẫn khoảng 60 request/window và tất cả request dồn vào cùng một khoảng ngắn, số request được cho qua sẽ không tăng nhiều, còn `429 rate` sẽ tăng:

| Tổng request | Request có thể pass nếu quota khoảng 60 | 429 ước tính | Rejection rate ước tính |
| ---: | ---: | ---: | ---: |
| 200 | 60 | 140 | 70% |
| 500 | 60 | 440 | 88% |
| 1000 | 60 | 940 | 94% |

Đây là hành vi mong muốn của rate limiter: càng vượt quota nhiều thì tỷ lệ reject càng cao.

### Trường hợp 2: Request kéo dài qua nhiều window hoặc token được refill

Nếu batch chạy lâu hơn, limiter có thêm thời gian reset window hoặc refill token. Khi đó số request pass sẽ tăng theo thời gian. Ví dụ token bucket có thể cho qua burst ban đầu, sau đó cho qua thêm theo refill rate. Vì vậy, cùng 1000 request nhưng chạy trong 1 giây và chạy rải trong 60 giây sẽ cho kết quả rất khác nhau.

### Trường hợp 3: Concurrency tăng mạnh

Khi tăng concurrency, các hiện tượng thường gặp:

- `clientP95Ms` và `clientP99Ms` tăng do client-side queueing, connection pool, OS scheduling và gateway queue.
- `rateLimiterP95Ms` tăng nếu Redis hoặc Lua script không theo kịp.
- `gatewayP95Ms` tăng theo vì gateway phải chờ limiter hoặc backend.
- `throughput` tăng đến một ngưỡng rồi bão hòa; sau đó tăng tải chỉ làm latency/error tăng.
- `errorCount` có thể xuất hiện nếu request timeout trước khi gateway trả response.

### Trường hợp 4: Backend chậm hơn

Nếu thêm `delay-100` hoặc `delay-500`, `backendP95Ms` sẽ tăng và có thể chi phối `gatewayP95Ms`/`clientP95Ms`. Khi backend đã chậm 500 ms, khác biệt 20 ms giữa các limiter ít quan trọng hơn. Nhưng nếu limiter p95 lên gần 1000 ms như token bucket/fixed window trong ảnh, limiter vẫn là bottleneck lớn ngay cả khi backend có delay.

### Trường hợp 5: Redis gặp sự cố

Với Redis-based limiter:

- `fail-closed`: an toàn quota, nhưng nhiều request sẽ bị reject hoặc lỗi nếu Redis unreachable.
- `fail-open`: giữ availability, nhưng client có thể vượt quota vì gateway cho qua khi không kiểm tra được Redis.
- `local-fallback`: giảm downtime bằng limiter local, nhưng quota không còn global giữa nhiều gateway instance.

### Trường hợp 6: Scale nhiều gateway instance

Redis-based limiter giữ quota tập trung nên phù hợp distributed API gateway. In-memory limiter sẽ bị lệch quota theo số instance. Ví dụ quota 60/request window, 4 gateway instance có thể cho qua xấp xỉ 240 request nếu mỗi instance tự đếm riêng.

## Kết luận

Trong kết quả 200 request này, `redis-sliding-window` là strategy tốt nhất: gateway p95 thấp nhất, rate limiter p95 thấp nhất, throughput cao nhất, trong khi vẫn reject đúng khoảng 70% request vượt quota. `redis-token-bucket` và `redis-fixed-window` đang có overhead limiter quá cao, cần kiểm tra Redis latency, Lua script, connection pool, lock/contention và cấu hình timeout.

Nếu tăng số request, kỳ vọng chính là `429 rate` tăng nếu traffic vẫn dồn trong cùng quota window. Khi tăng cả concurrency, cần theo dõi thêm `p99`, `errorCount`, timeout và saturation của Redis/backend, vì hệ thống có thể chuyển từ "bị limit đúng" sang "bị nghẽn hoặc timeout".
